package project.kompass.travellersTales.boss.wither

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.bukkit.util.EulerAngle
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class ActiveWitherFight(
    private val plugin: TravellersTales,
    var wither: Wither,
    var config: WitherConfig
) {
    val witherId: UUID = wither.uniqueId
    val world: World
        get() = wither.world

    private val tasks = mutableListOf<BukkitTask>()
    private val spawnOrigin: Location = wither.location.clone()

    private val wasAdvanceTime: Boolean = world.getGameRuleValue(GameRules.ADVANCE_TIME)
    private val wasMobGriefing: Boolean = world.getGameRuleValue(GameRules.MOB_GRIEFING)

    var inIntro: Boolean = true
        private set
    var inMidpoint: Boolean = false
        private set
    var inPhase2: Boolean = false
        private set
    var chargePrepReady: Boolean = false
    var chargeUnlocked: Boolean = false
        private set
    var swarmTriggered: Boolean = false
        private set
    var dashUnlocked: Boolean = false
        private set
    var isEnraged: Boolean = false
        private set
    var bedrockDeathTriggered: Boolean = false
        private set

    private var isCharging: Boolean = false
    private var chargeChainCount: Int = 0
    private var lastDirectHitCounterMillis: Long = 0L
    private var homingStand: ArmorStand? = null
    private var witherTeam: Team? = null

    fun start() {
        setupTeam()
        configureWitherEntity()
        plugin.bossBarManager.createBossBar(wither, config.bossBar)
        applyEnvironmentAndIntro()
        scheduleCoreLoops()
    }

    /**
     * Re-binds the live Wither reference when its chunk reloads so its BossBar and AI stay active.
     */
    fun rebindWither(loaded: Wither) {
        val wasUnloaded = !this.wither.isValid
        this.wither = loaded
        witherTeam?.addEntity(loaded)
        plugin.bossBarManager.rebindEntity(loaded)

        // If the chunk unloaded mid-charge or mid-slam, restore AI & vulnerability immediately
        if (wasUnloaded && !loaded.isDead && !inIntro) {
            inMidpoint = false
            isCharging = false
            loaded.setInvulnerable(false)
            loaded.invulnerableTicks = 0
            loaded.setAI(true)
            loaded.setGravity(true)
        }
    }

    private fun ensureLiveWither(): Boolean {
        if (wither.isValid) return true
        val live = Bukkit.getEntity(witherId) as? Wither
        if (live != null && live.isValid) {
            rebindWither(live)
            return true
        }
        return false
    }

    private fun setupTeam() {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        scoreboard.getTeam("Wither")?.unregister()
        witherTeam = scoreboard.registerNewTeam("Wither").apply {
            setAllowFriendlyFire(false)
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            addEntity(wither)
        }
    }

    private fun configureWitherEntity() {
        wither.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "the_wither")
        wither.customName("<bold><dark_purple>The Wither</dark_purple></bold>".mm())
        wither.isPersistent = true
        wither.removeWhenFarAway = false
        wither.invulnerableTicks = 220

        wither.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
        wither.health = config.maxHealth
        wither.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange // 64.0
        wither.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.5
        wither.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.8
        wither.getAttribute(Attribute.STEP_HEIGHT)?.baseValue = 2.0
        wither.getAttribute(Attribute.EXPLOSION_KNOCKBACK_RESISTANCE)?.baseValue = 0.9
        wither.getAttribute(Attribute.ARMOR)?.baseValue = 1.0
        wither.getAttribute(Attribute.ARMOR_TOUGHNESS)?.baseValue = 0.0
        wither.getAttribute(Attribute.FLYING_SPEED)?.baseValue = 0.0

        WitherMinionFactory.equipWitherBlastArmor(wither)
    }

    // ========================================================================
    // SPAWN INTRO SEQUENCE
    // ========================================================================

    private fun applyEnvironmentAndIntro() {
        if (config.weatherEffects) {
            if (config.animationMode == AnimationMode.DEFAULT) {
                world.setStorm(true)
                world.isThundering = true
            }
            if (wasAdvanceTime) world.setGameRule(GameRules.ADVANCE_TIME, false)
            val timeOfDay = world.time % 24000L
            val ticksToNight = if (timeOfDay <= 22000L) (22000L - timeOfDay) else (46500L - timeOfDay)
            world.fullTime += ticksToNight
        }

        if (!config.destruction && wasMobGriefing) {
            world.setGameRule(GameRules.MOB_GRIEFING, false)
        }

        val loc = wither.location
        if ((1..4).any { loc.clone().add(0.0, it.toDouble(), 0.0).block.type == Material.BEDROCK }) {
            wither.teleport(loc.clone().add(0.0, -8.0, 0.0))
        } else if ((1..4).any { loc.clone().add(0.0, -it.toDouble(), 0.0).block.type == Material.BEDROCK }) {
            wither.teleport(loc.clone().add(0.0, 8.0, 0.0))
        }
        if (config.destruction) {
            breakBoxAround(wither.location, 2, 1..5, 2, dropItems = true)
        }

        val nearbyPlayers = wither.location.getNearbyPlayers(100.0)
        nearbyPlayers.forEach { p ->
            p.stopSound(org.bukkit.SoundCategory.MUSIC)
            p.playSound(wither.location, "minecraft:wither.10sec", SoundCategory.RECORDS, 1f, 1f)
            p.playSound(wither.location, org.bukkit.Sound.MUSIC_NETHER_BASALT_DELTAS, SoundCategory.MUSIC, 2f, 1f)
            p.playSound(p.location, org.bukkit.Sound.ENTITY_PARROT_IMITATE_WITHER, SoundCategory.HOSTILE, 2f, 0.5f)
        }

        if (config.animationMode == AnimationMode.DEFAULT) {
            wither.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 140, 3, false, false))
            wither.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 215, 20, false, false))
            world.spawnParticle(Particle.SCULK_SOUL, wither.location.add(0.0, 1.0, 0.0), 50, 0.5, 0.35, 0.5, 0.1)
            world.spawnParticle(Particle.SCULK_SOUL, wither.location, 1000, 15.0, 0.0, 15.0, 1e-20)
            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                wither.location.add(0.0, 0.8, 0.0),
                100, 0.9, 1.2, 0.9, 0.001,
                Particle.DustTransition(Color.fromRGB(43, 43, 43), Color.fromRGB(240, 240, 230), 4f)
            )

            nearbyPlayers.forEach { p ->
                p.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 160, 1, false, false, true))
                applyGravityPull(p)
            }

            val soulTask = runTimer(1L, 1L) {
                if (!inIntro || !ensureLiveWither()) return@runTimer
                val targetLoc = wither.location.add(0.0, 1.0, 0.0)
                val trailData = Particle.Trail(targetLoc, Color.fromRGB(5, 4, 3), 30)
                wither.location.getNearbyEntities(50.0, 50.0, 50.0)
                    .filter { it.type in WitherTags.MOBS_WITH_SOULS && it != wither }
                    .forEach { soulMob ->
                        world.spawnParticle(Particle.TRAIL, soulMob.location.add(0.0, 0.5, 0.0), 15, 0.5, 0.5, 0.5, 0.1, trailData)
                    }
            }
            runLater(200L) {
                soulTask.cancel()
                clearAllGravityPulls()
            }
        } else if (config.animationMode == AnimationMode.BEDROCK) {
            val spinTask = runTimer(1L, 1L) {
                if (!inIntro || !ensureLiveWither()) return@runTimer
                wither.setRotation(wither.yaw + 20f, wither.pitch)
            }
            runLater(219L) { spinTask.cancel() }
        }

        val introPulse = runTimer(1L, 20L) {
            if (!inIntro || !ensureLiveWither()) return@runTimer
            if (config.animationMode == AnimationMode.DEFAULT) {
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    wither.location.add(0.0, 1.0, 0.0),
                    100, 0.9, 1.2, 0.9, 0.001,
                    Particle.DustTransition(Color.fromRGB(153, 153, 153), Color.WHITE, 3f)
                )
                world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_HURT, SoundCategory.HOSTILE, 1f, 0.5f)
            } else if (config.animationMode == AnimationMode.BEDROCK) {
                world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1f, 1f)
            }
        }

        runLater(219L) {
            inIntro = false
            introPulse.cancel()
            clearAllGravityPulls()
            if (!ensureLiveWither()) return@runLater

            wither.invulnerableTicks = 0
            wither.isInvulnerable = false
            wither.removePotionEffect(PotionEffectType.SLOW_FALLING)
            wither.getAttribute(Attribute.FLYING_SPEED)?.baseValue = 1.0

            if (config.animationMode == AnimationMode.DEFAULT) {
                world.createExplosion(wither.location, 13f, false, config.destruction, wither)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, wither.location, 6, 0.0, 1.0, 0.0, 0.6, null, true)
                world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, wither.location, 300, 0.0, 1.0, 0.0, 0.6, null, true)
                world.spawnParticle(Particle.LAVA, wither.location, 200, 3.0, 3.0, 3.0, 0.1, null, true)
            } else if (config.animationMode == AnimationMode.BEDROCK) {
                world.playSound(wither.location, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1f, 1f)
            }

            runTimer(1L, 2780L) {
                if (inPhase2 || !ensureLiveWither()) return@runTimer
                wither.location.getNearbyPlayers(200.0).forEach { p ->
                    p.playSound(p.location, "minecraft:wither.phase1", SoundCategory.RECORDS, 1f, 1f)
                }
            }
        }
    }

    // ========================================================================
    // CORE LOOPS
    // ========================================================================

    private fun scheduleCoreLoops() {
        runTimer(1L, 1L) {
            if (!ensureLiveWither() || wither.isDead) return@runTimer

            if (inPhase2 && wither.health >= config.midpointThreshold) {
                wither.health = (config.midpointThreshold - 2.0).coerceAtLeast(1.0)
            }
            if (dashUnlocked && wither.health >= config.enragedThreshold) {
                wither.health = (config.enragedThreshold - 2.0).coerceAtLeast(1.0)
            }

            if (config.animationMode == AnimationMode.DEFAULT && !inIntro) {
                tickSkullImpactExplosions()
            }
        }

        if (config.animationMode == AnimationMode.DEFAULT) {
            runTimer(240L, 2L) {
                if (inPhase2 || !ensureLiveWither() || inMidpoint || inIntro) return@runTimer
                if (wither.health > config.midpointThreshold) {
                    val nearestPlayer = wither.location.getNearbyPlayers(10.0)
                        .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                        .minByOrNull { it.location.distanceSquared(wither.location) }
                    if (nearestPlayer != null) {
                        shiftAwayFrom(nearestPlayer)
                    }
                }
            }
        }

        // 5-Tick Loop: MaxCombatDistance (256.0) check, Aura particles, Iron Golem debuff, Blaze archer dismount
        runTimer(5L, 5L) {
            if (!ensureLiveWither()) return@runTimer
            if (wither.world == spawnOrigin.world &&
                wither.location.distanceSquared(spawnOrigin) > config.maxCombatDistanceSquared
            ) {
                wither.teleport(spawnOrigin)
            }

            val wLoc = wither.location
            world.spawnParticle(Particle.ASH, wLoc, 300, 10.0, 10.0, 10.0, 0.0)
            if (isEnraged) {
                world.spawnParticle(Particle.MYCELIUM, wLoc.clone().add(0.0, 1.0, 0.0), 1500, 1.0, 1.0, 1.0, 0.0)
                world.spawnParticle(Particle.TRIAL_OMEN, wLoc.clone().add(0.0, 1.0, 0.0), 10, 1.0, 1.0, 1.0, 2.0)
            }

            wLoc.getNearbyEntities(config.followRange, config.followRange, config.followRange).forEach { ent ->
                val pdc = ent.persistentDataContainer
                if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE)) {
                    witherTeam?.addEntity(ent)
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, ent.location, 10, 0.0, 0.0, 0.0, 0.03, null, true)
                }
                if (ent is Blaze && pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE)) {
                    witherTeam?.addEntity(ent)
                    if (inMidpoint) {
                        world.spawnParticle(Particle.SMOKE, ent.location, 1, 0.0, 0.0, 0.0, 0.0)
                        world.spawnParticle(Particle.LAVA, ent.location.add(0.0, -1.0, 0.0), 4, 0.2, 0.2, 0.2, 0.003)
                    }
                    if (!ent.location.clone().add(0.0, -0.1, 0.0).block.type.isAir) {
                        ent.passengers.toList().forEach { ent.removePassenger(it) }
                    }
                }
                if (ent is IronGolem && !pdc.has(MobKeys.MOB_ID, PersistentDataType.STRING) && ent.location.distanceSquared(wLoc) <= 400.0) {
                    ent.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 80, 3, false, true))
                    ent.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 80, 1, false, false))
                }
            }
        }

        runTimer(40L, 40L) {
            if (!ensureLiveWither()) return@runTimer
            convertNearbyUndead()
        }

        runTimer(300L, 40L) {
            if (!ensureLiveWither() || inMidpoint || isCharging) return@runTimer
            unstuckCheck()
        }

        if (config.minionSpawns) {
            scheduleNextGuardSpawn(300L)
        }

        if (config.homingSkulls) {
            scheduleNextHomingCheck(200L)
        }

        runTimer(440L, 160L) {
            if (!ensureLiveWither() || isEnraged || inMidpoint || inIntro) return@runTimer
            launchAimedSkull(dangerous = true, customName = "Wither Missile", forwardOffset = 0.0)
        }

        scheduleNextBurstVolley(600L)
        runLater(24000L) { restoreWorldState() }
    }

    fun checkPhaseTransitions() {
        val hp = wither.health

        if (!inPhase2 && hp <= config.midpointThreshold) {
            triggerMidpoint()
        }
        if (hp <= config.chargePrepThreshold) {
            chargePrepReady = true
        }
        if (!chargeUnlocked && config.chargeAttack && hp <= config.chargeThreshold) {
            chargeUnlocked = true
            startChargeSequence()
        }
        if (!swarmTriggered && config.animationMode == AnimationMode.DEFAULT && hp <= config.swarmThreshold) {
            swarmTriggered = true
            triggerSwarm()
        }
        if (!isEnraged && hp <= config.enragedThreshold) {
            if (config.animationMode == AnimationMode.DEFAULT) {
                triggerEnraged()
            } else {
                isEnraged = true
                plugin.bossBarManager.updateBarAppearance(
                    wither,
                    titleMiniMessage = config.bossBar.enragedTitle,
                    color = config.bossBar.enragedColor
                )
            }
            if (!dashUnlocked && config.windDashes) {
                dashUnlocked = true
                startWindDashLoop()
            }
        }
        if (!bedrockDeathTriggered && config.animationMode == AnimationMode.BEDROCK && hp <= config.bedrockDeathThreshold) {
            bedrockDeathTriggered = true
            wither.health = 0.0
        }
    }

    fun triggerPhase1DirectHitCounter() {
        if (!config.directHitCounter || inPhase2 || inIntro || inMidpoint) return
        if (wither.health <= config.midpointThreshold) return

        val now = System.currentTimeMillis()
        if (now - lastDirectHitCounterMillis < 1200L) return
        lastDirectHitCounterMillis = now

        world.createExplosion(wither.location.add(0.0, -1.0, 0.0), 4f, false, config.destruction, wither)

        val hasBedrockAbove = (1..7).any {
            wither.location.clone().add(0.0, it.toDouble(), 0.0).block.type == Material.BEDROCK
        }
        if (!hasBedrockAbove) {
            wither.velocity = Vector(0.0, 4.5, 0.0)
        }
    }

    private fun shiftAwayFrom(player: Player) {
        val away = wither.location.toVector().subtract(player.location.toVector())
        wither.velocity = Vector(away.x * 0.3, 0.4, away.z * 0.3)
        faceEntity(wither, player.eyeLocation)
    }

    // ========================================================================
    // HOMING SKULL
    // ========================================================================

    private fun scheduleNextHomingCheck(delayTicks: Long) {
        runLater(delayTicks) {
            if (wither.isDead) return@runLater
            if (ensureLiveWither() && wither.health <= config.homingThreshold && (homingStand == null || !homingStand!!.isValid)) {
                spawnHomingSkull()
            }
            val nextDelay = if (isEnraged) 200L else 400L
            scheduleNextHomingCheck(nextDelay)
        }
    }

    private fun spawnHomingSkull() {
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 1f, 0.8f)
        val stand = WitherMinionFactory.spawnHomingSkullStand(wither.location, witherTeam)
        homingStand = stand

        var ticksAlive = 0
        var task: BukkitTask? = null
        task = runTimer(1L, 1L) {
            ticksAlive++
            val target = stand.location.getNearbyPlayers(config.followRange)
                .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                .minByOrNull { it.location.distanceSquared(stand.location) }
            if (!stand.isValid || !ensureLiveWither() || target == null || ticksAlive > 300) {
                stand.remove()
                task?.cancel()
                return@runTimer
            }

            if (ticksAlive % 5 == 0) {
                world.spawnParticle(Particle.LAVA, stand.location.add(0.0, 1.5, 0.0), 5, 0.3, 0.3, 0.3, 1.0)
                world.playSound(stand.location, org.bukkit.Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 2f, 0.5f)
            }

            val speed = if (isEnraged) 0.6 else 0.35
            val dir = target.location.toVector().subtract(stand.location.toVector()).normalize()
            val nextLoc = stand.location.add(dir.multiply(speed)).add(0.0, 0.1, 0.0)
            nextLoc.setDirection(dir)
            stand.teleport(nextLoc)
            stand.headPose = EulerAngle(Math.toRadians(nextLoc.pitch.toDouble()), 0.0, 0.0)

            world.spawnParticle(Particle.SMOKE, stand.location.add(0.0, 1.5, 0.0), 4, 0.2, 0.2, 0.2, 0.0)
            stand.location.getNearbyPlayers(7.0).forEach { p ->
                p.playSound(stand.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.HOSTILE, 1f, 2f)
            }

            val hitPlayer = stand.location.getNearbyPlayers(0.65).isNotEmpty()
            val hitBlock = !stand.eyeLocation.add(dir.clone().normalize()).block.type.isAir
            if (hitPlayer || hitBlock) {
                world.createExplosion(stand.location, 2f, false, config.destruction, wither)
                world.spawnParticle(Particle.LAVA, stand.location, 100, 1.0, 1.0, 1.0, 0.01, null, true)
                world.playSound(stand.location, org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 1.7f)
                stand.remove()
                task?.cancel()
            }
        }
    }

    // ========================================================================
    // MIDPOINT TRANSITION: BLAZE ARCHERS & GROUND SLAM
    // ========================================================================

    private fun triggerMidpoint() {
        inPhase2 = true
        if (!isEnraged) {
            plugin.bossBarManager.updateBarAppearance(
                wither,
                titleMiniMessage = config.bossBar.phase2Title,
                color = config.bossBar.phase2Color
            )
        }

        if (config.weatherEffects) {
            world.fullTime += 1000L
            if (wasAdvanceTime) world.setGameRule(GameRules.ADVANCE_TIME, true)
            world.setStorm(false)
            world.isThundering = false
        }

        Bukkit.getOnlinePlayers().forEach { p ->
            p.stopSound(Sound.sound(Key.key("minecraft:wither.phase1"), Sound.Source.RECORD, 1f, 1f))
        }
        runTimer(1L, 4660L) {
            if (!ensureLiveWither()) return@runTimer
            wither.location.getNearbyPlayers(200.0).forEach { p ->
                p.playSound(p.location, "minecraft:wither.phase2", SoundCategory.RECORDS, 1f, 1f)
            }
        }

        if (config.animationMode == AnimationMode.BEDROCK) {
            wither.velocity = Vector(0.0, -10.0, 0.0)
            runLater(10L) {
                if (config.destruction) {
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, wither.location, 1)
                }
            }
            return
        }

        if (config.animationMode != AnimationMode.DEFAULT) return

        inMidpoint = true
        wither.isInvulnerable = true
        wither.setAI(false)
        wither.setGravity(false)

        if (config.destruction) {
            breakBoxAround(wither.location, 1, 0..3, 1, dropItems = false)
        }
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.BLOCKS, 0.8f, 0.5f)
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_SKELETON_HURT, SoundCategory.HOSTILE, 0.8f, 0.5f)
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 2f, 0.8f)

        val playerCount = wither.location.getNearbyPlayers(100.0).size
        val archerCount = when {
            playerCount <= 4 -> 10
            playerCount <= 9 -> 20
            else -> 30
        }

        WitherTags.BLAZE_ARCHER_VECTORS.take(archerCount).forEachIndexed { index, vec ->
            val delay = 3L + (index * 36L / archerCount)
            runLater(delay) {
                if (!ensureLiveWither()) return@runLater
                WitherMinionFactory.spawnBlazeArcher(wither.location, vec, witherTeam)
                world.playSound(wither.location, org.bukkit.Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.HOSTILE, 1f, 2f)
            }
        }

        var midpointTicks = 0
        var slamPhase = false
        var midTask: BukkitTask? = null
        midTask = runTimer(1L, 1L) {
            if (!ensureLiveWither()) {
                inMidpoint = false
                midTask?.cancel()
                return@runTimer
            }
            midpointTicks++
            if (!slamPhase) {
                if (midpointTicks >= 40) {
                    slamPhase = true
                    wither.setGravity(true)
                    wither.velocity = Vector(0.0, -6.9, 0.0)
                } else {
                    val upLoc = wither.location.add(0.0, 1.0, 0.0)
                    upLoc.yaw += 20f
                    if (upLoc.block.type != Material.BEDROCK) {
                        wither.teleport(upLoc)
                    }
                }
            } else {
                if (wither.isOnGround || !wither.location.clone().add(0.0, -0.8, 0.0).block.type.isAir || midpointTicks > 100) {
                    midTask?.cancel()
                    onMidpointGroundSlam()
                } else {
                    wither.velocity = Vector(0.0, -3.5, 0.0)
                }
            }
        }
    }

    private fun onMidpointGroundSlam() {
        inMidpoint = false
        wither.isInvulnerable = false
        wither.invulnerableTicks = 0
        wither.setAI(true)
        wither.setGravity(true)

        val shockwaveStages = listOf(
            Triple(0.5, 0.0..0.8, 150),
            Triple(1.0, 0.9..1.6, 100),
            Triple(3.0, 1.7..3.5, 65),
            Triple(7.0, 3.6..7.0, 40),
            Triple(14.0, 7.1..14.0, 20)
        )

        shockwaveStages.forEachIndexed { idx, (spread, distRange, levAmp) ->
            runLater((idx + 1).toLong()) {
                if (!ensureLiveWither()) return@runLater
                val loc = wither.location
                world.spawnParticle(Particle.DUST_PLUME, loc, (idx + 1) * 400, spread, 0.2, spread, 1e-7, null, true)
                if (idx <= 2) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, (idx + 1) * 200, spread, 0.2, spread, 1e-7, null, true)
                }
                if (idx == 0) {
                    world.playSound(loc, org.bukkit.Sound.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 1f, 0.5f)
                } else {
                    world.playSound(loc, org.bukkit.Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1f, 0.5f)
                }

                loc.getNearbyPlayers(14.0).forEach { p ->
                    val d = p.location.distance(loc)
                    if (d in distRange) {
                        p.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 20, levAmp, false, false, true))
                    }
                }
            }
        }

        runLater(10L) {
            if (ensureLiveWither()) {
                wither.location.getNearbyPlayers(30.0).forEach { it.removePotionEffect(PotionEffectType.LEVITATION) }
            }
        }

        runLater(5L) {
            if (!ensureLiveWither()) return@runLater
            world.createExplosion(wither.location.add(0.0, 1.0, 0.0), 10f, false, config.destruction, wither)
            world.strikeLightningEffect(wither.location)
            wither.getAttribute(Attribute.ARMOR)?.baseValue = 6.0
            wither.getAttribute(Attribute.ARMOR_TOUGHNESS)?.baseValue = 6.0
            world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1f, 0.6f)
            world.spawnParticle(Particle.ELECTRIC_SPARK, wither.location.add(0.0, 0.8, 0.0), 400, 0.3, 0.5, 0.3, 8.0)
            world.spawnParticle(Particle.END_ROD, wither.location.add(0.0, 2.0, 0.0), 150, 0.0, 0.0, 0.0, 0.15)
        }
    }

    // ========================================================================
    // CHARGE ATTACK & TRIPLE ENRAGED CHAIN CHARGES
    // ========================================================================

    private fun startChargeSequence() {
        if (wither.isDead) return
        if (!ensureLiveWither() || inMidpoint || inIntro) {
            runLater(80L) { startChargeSequence() }
            return
        }

        isCharging = true
        wither.setAI(false)
        wither.isGlowing = true

        val nearestPlayer = wither.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .minByOrNull { it.location.distanceSquared(wither.location) }
        if (nearestPlayer != null) {
            faceEntity(wither, nearestPlayer.location)
        }

        world.playSound(wither.location, org.bukkit.Sound.ENTITY_BREEZE_INHALE, SoundCategory.HOSTILE, 1.5f, 0.5f)
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 0.8f, 2f)
        wither.location.getNearbyPlayers(30.0).forEach { p ->
            p.playSound(p.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 0.7f)
            p.playSound(p.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1f, 0.6f)
        }

        val windupTicks = if (isEnraged) 10L else 30L
        val chargeDurationTicks = if (isEnraged) 10 else 20
        val stepSpeed = if (isEnraged) 2.3 else 1.4

        runLater(windupTicks) {
            if (!ensureLiveWither()) {
                isCharging = false
                return@runLater
            }
            val target = wither.location.getNearbyPlayers(config.followRange)
                .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                .minByOrNull { it.location.distanceSquared(wither.location) }
            if (target != null) {
                faceEntity(wither, target.location)
            }
            val chargeDir = wither.location.direction.normalize()
            val hitPlayers = HashSet<UUID>()
            val chargeSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(wither)
                .withDirectEntity(wither)
                .build()

            var step = 0
            var chargeTask: BukkitTask? = null
            chargeTask = runTimer(1L, 1L) {
                step++
                if (!ensureLiveWither() || step > chargeDurationTicks) {
                    chargeTask?.cancel()
                    finishCharge()
                    return@runTimer
                }

                val nextLoc = wither.location.clone().add(chargeDir.clone().multiply(stepSpeed))
                val nextBlock = nextLoc.block
                if (nextBlock.type == Material.BEDROCK || (!config.destruction && nextBlock.type.isSolid)) {
                    world.playSound(wither.location, org.bukkit.Sound.BLOCK_ANVIL_PLACE, SoundCategory.HOSTILE, 1f, 2f)
                    chargeTask?.cancel()
                    finishCharge()
                    return@runTimer
                } else {
                    wither.teleport(nextLoc)
                    world.playSound(nextLoc, org.bukkit.Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 1f, 0.5f)
                    if (config.destruction && !nextBlock.type.isAir) {
                        world.createExplosion(nextLoc, 4f, false, true, wither)
                        breakBoxAround(nextLoc, 2, -2..2, 2, dropItems = true)
                    }
                }

                wither.location.getNearbyPlayers(2.8).forEach { p ->
                    if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE) && hitPlayers.add(p.uniqueId)) {
                        p.damage(14.0, chargeSource)
                        p.velocity = chargeDir.clone().setY(0.45).multiply(1.3)
                    }
                }

                if (config.animationMode == AnimationMode.DEFAULT) {
                    world.spawnParticle(Particle.LAVA, wither.location, 30, 0.2, 0.2, 0.2, 1.0)
                    world.spawnParticle(Particle.SQUID_INK, wither.location, 10, 1.5, 2.0, 1.5, 0.1)
                    world.spawn(wither.location, AreaEffectCloud::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { cloud ->
                        cloud.setParticle(Particle.SMOKE)
                        cloud.reapplicationDelay = 5
                        cloud.radius = 2.5f
                        cloud.radiusPerTick = -0.08f
                        cloud.duration = 80
                        cloud.addCustomEffect(PotionEffect(PotionEffectType.WITHER, 80, 2), true)
                    }
                }
            }
        }
    }

    private fun finishCharge() {
        isCharging = false
        if (wither.isDead) return
        if (ensureLiveWither()) {
            wither.setAI(true)
            wither.isGlowing = false
        }

        if (isEnraged) {
            chargeChainCount++
            if (chargeChainCount < 3) {
                runLater(4L) { startChargeSequence() }
            } else {
                chargeChainCount = 0
                runLater(360L) { startChargeSequence() }
            }
        } else {
            runLater(400L) { startChargeSequence() }
        }
    }

    // ========================================================================
    // 25% HP SWARM & ENRAGED 360° SKULL VOLLEY + WIND DASHES
    // ========================================================================

    private fun triggerSwarm() {
        repeat(15) {
            WitherMinionFactory.spawnSwarmSkeleton(wither.location, witherTeam)
        }
        listOf(1.5f, 1.6f, 1.4f, 1.8f, 1.3f).forEach { pitch ->
            world.playSound(wither.location, org.bukkit.Sound.ENTITY_SPIDER_STEP, SoundCategory.HOSTILE, 1f, pitch)
        }
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 1f, 0.5f)
        world.spawnParticle(Particle.SQUID_INK, wither.location.add(0.0, 1.0, 0.0), 100, 1.0, 0.5, 1.0, 5e-11)
    }

    private fun triggerEnraged() {
        isEnraged = true
        plugin.bossBarManager.updateBarAppearance(
            wither,
            titleMiniMessage = config.bossBar.enragedTitle,
            color = config.bossBar.enragedColor
        )

        wither.getAttribute(Attribute.GRAVITY)?.baseValue = 1.5
        wither.getAttribute(Attribute.ARMOR)?.baseValue = 10.0
        wither.getAttribute(Attribute.ARMOR_TOUGHNESS)?.baseValue = 10.0
        witherTeam?.color(NamedTextColor.DARK_RED)

        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 1f, 0.5f)
        world.playSound(wither.location, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 1f, 2f)

        spawn360SkullRing()
    }

    private fun spawn360SkullRing() {
        if (!ensureLiveWither()) return
        val center = wither.location.add(0.0, 1.0, 0.0)
        val skulls = mutableListOf<WitherSkull>()

        for (i in 0 until 24) {
            val angle = Math.toRadians(i * 15.0)
            val motion = Vector(sin(angle), 0.4, cos(angle))
            val skull = world.spawn(center, WitherSkull::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { s ->
                s.shooter = wither
                s.velocity = motion
                s.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            }
            skulls.add(skull)
        }

        runLater(20L) {
            val target = wither.location.getNearbyPlayers(config.followRange)
                .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                .minByOrNull { it.location.distanceSquared(wither.location) }
            skulls.filter { it.isValid }.forEach { s ->
                s.velocity = Vector(0.0, 0.0, 0.0)
                s.setGravity(false)
                s.isGlowing = true
                witherTeam?.addEntity(s)
                if (target != null) faceEntity(s, target.eyeLocation)
            }
            target?.let {
                it.playSound(it.location, org.bukkit.Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.HOSTILE, 0.8f, 0.5f)
                it.playSound(it.location, org.bukkit.Sound.ITEM_FLINTANDSTEEL_USE, SoundCategory.HOSTILE, 0.8f, 0.7f)
            }

            listOf(10L to 0.5f, 20L to 0.8f, 30L to 1.2f).forEach { (delay, pitch) ->
                runLater(delay) {
                    val p = wither.location.getNearbyPlayers(config.followRange)
                        .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                        .minByOrNull { it.location.distanceSquared(wither.location) } ?: return@runLater
                    p.playSound(p.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, pitch)
                    skulls.filter { it.isValid }.forEach { s -> faceEntity(s, p.eyeLocation) }
                }
            }

            runLater(40L) {
                val p = wither.location.getNearbyPlayers(config.followRange)
                    .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                    .minByOrNull { it.location.distanceSquared(wither.location) }
                if (p != null) {
                    val eyeVec = p.eyeLocation.toVector()
                    skulls.filter { it.isValid }.forEach { s ->
                        val launchVec = eyeVec.clone().subtract(s.location.toVector()).multiply(0.1)
                        s.velocity = launchVec
                        world.playSound(s.location, org.bukkit.Sound.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.25f, 1.2f)
                    }
                }

                runLater(40L) {
                    skulls.filter { it.isValid }.forEach { s ->
                        world.createExplosion(s.location, 3f, false, config.destruction, wither)
                        s.remove()
                    }
                    if (!wither.isDead && isEnraged) {
                        runLater(100L) { spawn360SkullRing() }
                    }
                }
            }
        }
    }

    private fun startWindDashLoop() {
        runTimer(1L, 60L) {
            if (!ensureLiveWither() || isCharging || inMidpoint || inIntro) return@runTimer
            val target = wither.location.getNearbyPlayers(config.followRange)
                .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                .minByOrNull { it.location.distanceSquared(wither.location) } ?: return@runTimer

            faceEntity(wither, target.location)
            val flatDir = target.location.toVector().subtract(wither.location.toVector()).setY(0.0)
            if (flatDir.lengthSquared() < 1e-4) return@runTimer
            val dashDir = flatDir.normalize().multiply(1.8)

            var dashStep = 0
            var dashTask: BukkitTask? = null
            dashTask = runTimer(1L, 1L) {
                dashStep++
                if (!ensureLiveWither() || isCharging || inMidpoint || dashStep > 5) {
                    dashTask?.cancel()
                    return@runTimer
                }

                val candidate = wither.location.clone().add(dashDir)
                if (!candidate.block.type.isSolid) {
                    wither.teleport(candidate)
                }

                world.spawnParticle(Particle.POOF, wither.location.add(0.0, 0.75, 0.0), 40, 0.8, 1.0, 0.8, 0.2)
                world.playSound(wither.location, org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.HOSTILE, 1f, 0.5f)
                world.spawn(wither.location, WindCharge::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { wc ->
                    wc.shooter = wither
                    wc.velocity = Vector(0.0, -10.0, 0.0)
                    wc.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
                }
            }
        }
    }

    private fun launchAimedSkull(dangerous: Boolean, customName: String, forwardOffset: Double) {
        val target = wither.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .minByOrNull { it.location.distanceSquared(wither.location) } ?: return

        faceEntity(wither, target.eyeLocation)
        val spawnLoc = wither.location.clone().add(0.0, 3.0, 0.0)
            .add(wither.location.direction.normalize().multiply(forwardOffset))

        world.spawn(spawnLoc, WitherSkull::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { skull ->
            skull.shooter = wither
            skull.isCharged = dangerous
            skull.fireTicks = 200
            skull.customName(customName.mm())
            skull.velocity = target.eyeLocation.toVector().subtract(spawnLoc.toVector()).multiply(0.1)
        }
        world.playSound(spawnLoc, org.bukkit.Sound.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.25f, 1.2f)
    }

    private fun scheduleNextBurstVolley(delayTicks: Long) {
        runLater(delayTicks) {
            if (wither.isDead) return@runLater
            if (ensureLiveWither()) {
                for (i in 0 until 5) {
                    runLater(i * 2L) {
                        if (ensureLiveWither() && !inMidpoint && !inIntro) {
                            launchAimedSkull(dangerous = false, customName = "burst wither skulls", forwardOffset = 2.0)
                        }
                    }
                }
            }
            val nextDelay = if (wither.health >= config.midpointThreshold) 110L else 410L
            scheduleNextBurstVolley(nextDelay)
        }
    }

    private fun tickSkullImpactExplosions() {
        wither.location.getNearbyEntitiesByType(WitherSkull::class.java, config.followRange).forEach { skull ->
            if (skull.persistentDataContainer.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)) return@forEach
            val dir = skull.velocity.clone()
            if (dir.lengthSquared() < 1e-4) return@forEach
            val aheadBlock = skull.location.add(dir.normalize()).block
            if (!aheadBlock.type.isAir && aheadBlock.type != Material.WATER) {
                if (skull.isCharged) {
                    world.createExplosion(skull.location, 3f, false, config.destruction, wither)
                    world.spawnParticle(Particle.EXPLOSION, skull.location, 50, 1.5, 1.5, 1.5, 0.0)
                    world.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, skull.location, 200, 1.0, 1.5, 1.0, 0.0)
                } else {
                    world.createExplosion(skull.location, 2f, false, config.destruction, wither)
                    world.spawnParticle(Particle.SMOKE, skull.location, 200, 2.0, 2.0, 2.0, 0.01, null, true)
                }
                skull.remove()
            }
        }
    }

    private fun scheduleNextGuardSpawn(delayTicks: Long) {
        runLater(delayTicks) {
            if (wither.isDead) return@runLater
            if (ensureLiveWither()) {
                val hasNearbySkeleton = wither.location.getNearbyEntitiesByType(WitherSkeleton::class.java, 10.0).isNotEmpty()
                if (!hasNearbySkeleton && !inIntro) {
                    WitherMinionFactory.spawnPeriodicGuard(wither.location, witherTeam)
                    world.spawnParticle(Particle.SQUID_INK, wither.location.add(0.0, -0.3, 0.0), 120, 0.0, 0.5, 0.0, 0.07, null, true)
                    world.spawnParticle(Particle.SMOKE, wither.location.add(0.0, -0.3, 0.0), 450, 1.0, 0.5, 1.0, 0.1, null, true)
                }
            }
            val nextDelay = if (isEnraged) 100L else 200L
            scheduleNextGuardSpawn(nextDelay)
        }
    }

    private fun convertNearbyUndead() {
        wither.location.getNearbyEntities(20.0, 20.0, 20.0).forEach { entity ->
            if (entity.customName() != null) return@forEach
            val pdc = entity.persistentDataContainer
            if (pdc.has(MobKeys.MOB_ID, PersistentDataType.STRING)) return@forEach
            if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE)) return@forEach
            if (pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE)) return@forEach
            if (pdc.has(MobKeys.GIANT_MINION, PersistentDataType.BYTE)) return@forEach

            when (entity) {
                is Zombie -> if (entity !is PigZombie) {
                    val loc = entity.location
                    entity.remove()
                    WitherMinionFactory.spawnConvertedPiglin(loc, witherTeam)
                }
                is AbstractSkeleton -> if (entity !is WitherSkeleton) {
                    val loc = entity.location
                    entity.remove()
                    WitherMinionFactory.spawnConvertedSkeleton(loc, witherTeam)
                }
            }
        }
    }

    private fun unstuckCheck() {
        val loc = wither.location
        if (!isEnraged && (loc.clone().add(0.0, -0.5, 0.0).block.type == Material.WATER ||
                    loc.clone().add(0.0, 0.5, 0.0).block.type == Material.WATER)
        ) {
            wither.velocity = Vector(0.0, 3.0, 0.0)
        }

        for (r in 1..3) {
            if (loc.clone().add(0.0, r.toDouble(), 0.0).block.type == Material.BEDROCK) {
                wither.velocity = Vector(0.0, -3.0, 0.0)
                break
            }
            if (loc.clone().add(0.0, -r.toDouble(), 0.0).block.type == Material.BEDROCK) {
                wither.velocity = Vector(0.0, 3.0, 0.0)
                break
            }
        }

        if (config.destruction) {
            for (x in -2..2) {
                for (y in 0..2) {
                    for (z in -2..2) {
                        val b = loc.clone().add(x.toDouble(), y.toDouble(), z.toDouble()).block
                        if (b.type in WitherTags.BREAKABLE_BLOCKS) {
                            b.type = Material.AIR
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // DEATH SEQUENCE: ORBITING NETHER STAR RINGS, TROPHY & XP FOUNTAIN
    // ========================================================================

    fun onDeath(deathLocation: Location) {
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(witherId)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val starItem = deathLocation.getNearbyEntitiesByType(Item::class.java, 12.0)
                .firstOrNull { it.itemStack.type == Material.NETHER_STAR }

            val anchorLoc = starItem?.location ?: deathLocation
            presentNetherStarAndRewards(starItem, anchorLoc)
            spawnXpFountain(anchorLoc)
            cleanupAfterDeath(anchorLoc)
        }, 1L)
    }

    private fun presentNetherStarAndRewards(starItem: Item?, anchorLoc: Location) {
        anchorLoc.getNearbyEntitiesByType(Item::class.java, 10.0).forEach { it.isInvulnerable = true }

        if (config.dropTrophy) {
            WitherMinionFactory.spawnTrophyItem(anchorLoc)
        }

        if (config.animationMode == AnimationMode.BEDROCK) {
            starItem?.velocity = Vector(0.0, 0.1, 0.0)
            return
        }

        if (config.animationMode != AnimationMode.DEFAULT || starItem == null) return

        if (!starItem.location.clone().add(0.0, -0.7, 0.0).block.type.isAir) {
            starItem.teleport(starItem.location.add(0.0, 0.75, 0.0))
        }
        starItem.pickupDelay = 20
        starItem.isInvulnerable = true
        starItem.setGravity(false)
        starItem.velocity = Vector(0.0, 0.0, 0.0)

        val display = world.spawn(starItem.location, ItemDisplay::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { d ->
            d.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            d.setItemStack(ItemStack(Material.NETHER_STAR))
            d.setGravity(false)
            d.isGlowing = true
            d.glowColorOverride = Color.WHITE
            d.brightness = Display.Brightness(15, 15)
            d.transformation = Transformation(
                Vector3f(0f, 0.8f, 0f),
                AxisAngle4f(0f, 0f, 0f, 1f),
                Vector3f(1.5f, 1.5f, 1.5f),
                AxisAngle4f(0f, 0f, 0f, 1f)
            )
        }

        world.spawnParticle(Particle.SOUL, starItem.location, 220, 0.0, 0.5, 0.0, 0.07)
        world.spawnParticle(Particle.SOUL, starItem.location.add(0.0, 1.0, 0.0), 80, 0.0, 1.0, 0.0, 0.04)
        world.playSound(starItem.location, org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.MASTER, 100f, 0.5f)
        world.playSound(starItem.location, org.bukkit.Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.MASTER, 30f, 1f)
        world.playSound(starItem.location, org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 1f, 0.5f)

        var ticks = 0
        var starTask: BukkitTask? = null
        starTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            ticks++
            if (!starItem.isValid || starItem.isDead || ticks >= 4800) {
                display.remove()
                starTask?.cancel()
                return@Runnable
            }
            val sLoc = starItem.location
            if (sLoc.clone().add(0.0, -1.0, 0.0).block.type.isAir) {
                sLoc.add(0.0, -0.005, 0.0)
            }
            sLoc.yaw += 10f
            sLoc.pitch = 0f
            starItem.teleport(sLoc)

            val dLoc = sLoc.clone()
            dLoc.yaw = display.yaw + 4f
            display.teleport(dLoc)

            val rad = Math.toRadians(sLoc.yaw.toDouble())
            val forwardX = -sin(rad)
            val forwardZ = cos(rad)

            val lowerRingPt = sLoc.clone().add(forwardX * 1.25, 0.3, forwardZ * 1.25)
            val upperRingPt = sLoc.clone().add(-forwardX * 0.85, 1.4, -forwardZ * 0.85)

            world.spawnParticle(Particle.END_ROD, lowerRingPt, 2, 0.02, 0.02, 0.02, 0.0)
            world.spawnParticle(Particle.ELECTRIC_SPARK, sLoc.clone().add(0.0, 0.8, 0.0), 2, 0.05, 0.05, 0.05, 0.2)
            world.spawnParticle(Particle.END_ROD, upperRingPt, 2, 0.02, 0.02, 0.02, 0.0)
        }, 1L, 1L)
    }

    private fun spawnXpFountain(origin: Location) {
        val totalXp = ExperienceUtil.getTotalExpForLevel(config.expLevelsDropped)
        val bursts = WitherTags.XP_FOUNTAIN_BURSTS
        val xpPerBurst = (totalXp / bursts.size).coerceAtLeast(1)

        bursts.forEach { (delay, vec) ->
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                world.spawn(origin, ExperienceOrb::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { orb ->
                    orb.experience = xpPerBurst
                    orb.velocity = vec
                }
                world.playSound(origin, org.bukkit.Sound.ENTITY_EXPERIENCE_BOTTLE_THROW, SoundCategory.NEUTRAL, 1f, 2f)
                world.spawnParticle(Particle.HAPPY_VILLAGER, origin.clone().add(0.0, 0.5, 0.0), 10, 0.4, 0.4, 0.4, 0.0)
            }, delay)
        }
    }

    private fun cleanupAfterDeath(origin: Location) {
        restoreWorldState()
        Bukkit.getOnlinePlayers().forEach { p ->
            p.stopSound(Sound.sound(Key.key("minecraft:wither.phase2"), Sound.Source.RECORD, 1f, 1f))
            p.playSound(origin, "minecraft:wither.death", SoundCategory.HOSTILE, 1f, 1f)
            p.playSound(origin, org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 0.9f)
            p.playSound(origin, org.bukkit.Sound.MUSIC_CREATIVE, SoundCategory.MUSIC, 1f, 1f)
            p.playSound(origin, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 100f, 0.85f)
        }
        purgeMinionsAndTempEntities()
    }

    fun forceCleanup(killWither: Boolean) {
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(witherId)
        restoreWorldState()
        purgeMinionsAndTempEntities()
        Bukkit.getOnlinePlayers().forEach { p ->
            p.stopSound(org.bukkit.SoundCategory.RECORDS)
        }
        if (killWither) {
            if (wither.isValid) {
                wither.remove()
            } else {
                (Bukkit.getEntity(witherId) as? Wither)?.remove()
            }
        }
    }

    private fun restoreWorldState() {
        clearAllGravityPulls()
        if (config.weatherEffects) {
            world.setStorm(false)
            world.isThundering = false
            world.clearWeatherDuration = 10000
            if (wasAdvanceTime) world.setGameRule(GameRules.ADVANCE_TIME, true)
        }
        if (!config.destruction && wasMobGriefing) {
            world.setGameRule(GameRules.MOB_GRIEFING, true)
        }
        witherTeam?.unregister()
        witherTeam = null
    }

    private fun purgeMinionsAndTempEntities() {
        homingStand?.remove()
        world.entities.forEach { ent ->
            val pdc = ent.persistentDataContainer
            if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)
            ) {
                ent.remove()
            }
        }
    }

    private fun applyGravityPull(player: Player) {
        val attr = player.getAttribute(Attribute.GRAVITY) ?: return
        attr.getModifier(MobKeys.GRAVITY_MODIFIER)?.let { attr.removeModifier(it) }
        attr.addModifier(
            AttributeModifier(MobKeys.GRAVITY_MODIFIER, 0.05, AttributeModifier.Operation.ADD_NUMBER)
        )
    }

    private fun clearAllGravityPulls() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val attr = player.getAttribute(Attribute.GRAVITY) ?: return@forEach
            attr.getModifier(MobKeys.GRAVITY_MODIFIER)?.let { attr.removeModifier(it) }
        }
    }

    private fun breakBoxAround(center: Location, rx: Int, yRange: IntRange, rz: Int, dropItems: Boolean) {
        for (x in -rx..rx) {
            for (y in yRange) {
                for (z in -rz..rz) {
                    val b = center.clone().add(x.toDouble(), y.toDouble(), z.toDouble()).block
                    if (b.type != Material.BEDROCK && !b.type.isAir) {
                        if (dropItems) b.breakNaturally() else b.type = Material.AIR
                    }
                }
            }
        }
    }

    private fun faceEntity(entity: Entity, targetLocation: Location) {
        val loc = entity.location
        val dir = targetLocation.toVector().subtract(loc.toVector())
        if (dir.lengthSquared() > 1e-4) {
            loc.setDirection(dir)
            entity.setRotation(loc.yaw, loc.pitch)
        }
    }

    private fun runLater(delay: Long, action: () -> Unit): BukkitTask {
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable(action), delay)
        tasks.add(task)
        return task
    }

    private fun runTimer(delay: Long, period: Long, action: () -> Unit): BukkitTask {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(action), delay, period)
        tasks.add(task)
        return task
    }

    private fun cancelAllTasks() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }
}