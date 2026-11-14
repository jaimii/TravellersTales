package project.kompass.travellersTales.boss.herobrine

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.boss.bar.BossBarSettings
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ActiveHerobrineFight(
    private val plugin: TravellersTales,
    val world: World,
    val spawnOrigin: Location,
    var config: HerobrineConfig,
    val summonerId: UUID? = null
) {
    val tablistUuid: UUID = UUID.randomUUID()
    lateinit var mannequin: Mannequin
        private set
    lateinit var mannequinId: UUID
        private set

    private val tasks = mutableListOf<BukkitTask>()
    val activeSteves = mutableListOf<SteveMinionController>()
    private var herobrineTeam: Team? = null
    private var steveTeam: Team? = null

    // Saved world state for Day/Night sky flickering without advancing day count
    private val savedFullTime: Long = world.fullTime
    private val wasAdvanceTime: Boolean = world.getGameRuleValue(GameRules.ADVANCE_TIME)

    // 3x3 Slam Settings (1.8-block radius from center covers the 3x3 block grid, -1..1 terraform radius)
    private val slamRadius: Double = 1.8
    private val slamBlockHalfSize: Int = 1 // -1..1 X and -1..1 Z = exact 3x3 blocks
    private val phase1SlamDamage: Double = 4.0  // 2 hearts unblockable/armor-ignoring
    private val phase2SlamDamage: Double = 8.0  // 4 hearts unblockable/armor-ignoring
    private val phase3SlamDamage: Double = 10.0 // 10 unblockable/armor-ignoring damage

    // Fight State Machine
    var currentPhase: Int = 0 // 0 = Pre-Phase 1, 1 = Phase 1, 2 = Phase 2, 3 = Phase 3
        private set
    var isFloatingInvulnerable: Boolean = false
        private set
    var isDyingAnimation: Boolean = false
        private set
    private var isSlamming: Boolean = false
    private var isLevitatingUp: Boolean = false
    private var waitingForStevesToDie: Boolean = false
    private var floatingSteveWaitTicks: Int = 0
    private var usingRangedStance: Boolean = false
    private var stanceSwitchTicks: Int = 0
    private var attackCooldownTicks: Int = 0
    private var lastMeleeBlinkMillis: Long = 0L
    private var damageSinceLastSteveWave: Double = 0.0
    private var currentTarget: Player? = null
    var isDeadOrRemoved: Boolean = false
        private set

    private val redstoneDust = Particle.DustOptions(Color.fromRGB(255, 15, 15), 1.6f)
    private val deepRedTransition = Particle.DustTransition(
        Color.fromRGB(255, 0, 0),
        Color.fromRGB(120, 0, 0),
        1.8f
    )
    private val rainbowTeamColors = listOf(
        NamedTextColor.RED,
        NamedTextColor.GOLD,
        NamedTextColor.YELLOW,
        NamedTextColor.GREEN,
        NamedTextColor.AQUA,
        NamedTextColor.BLUE,
        NamedTextColor.LIGHT_PURPLE,
        NamedTextColor.DARK_PURPLE
    )
    private val rainbowDustColors = listOf(
        Color.fromRGB(255, 0, 50),
        Color.fromRGB(255, 140, 0),
        Color.fromRGB(255, 235, 0),
        Color.fromRGB(0, 235, 80),
        Color.fromRGB(0, 225, 255),
        Color.fromRGB(60, 80, 255),
        Color.fromRGB(200, 0, 255)
    )
    private var rainbowColorIndex: Int = 0

    @Suppress("UnstableApiUsage")
    fun start() {
        setupTeam()
        HerobrinePacketController.broadcastTablistAdd(tablistUuid, "Herobrine")

        mannequin = world.spawn(spawnOrigin, Mannequin::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { m ->
            m.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "herobrine")
            m.profile = HerobrineSkinUtil.createMannequinProfile(tablistUuid, "Herobrine")
            m.customName("<white>Herobrine</white>".mm())
            m.isCustomNameVisible = true
            m.description = null
            m.isImmovable = false
            m.setGravity(true)
            m.isPersistent = true

            m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
            m.health = config.maxHealth
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 12.0
            m.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.6
            m.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 1000.0
            m.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue = 0.0
        }
        mannequinId = mannequin.uniqueId
        herobrineTeam?.addEntity(mannequin)

        spawnOrigin.getNearbyPlayers(64.0).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, false, true))
        }

        Bukkit.broadcast("<yellow>Herobrine has joined the game.</yellow>".mm())
        DiscordSrvHook.sendJoinMessage()

        scheduleCoreLoops()
    }

    fun rebindMannequin(loaded: Mannequin) {
        val wasUnloaded = !this.mannequin.isValid
        this.mannequin = loaded
        herobrineTeam?.addEntity(loaded)
        plugin.bossBarManager.rebindEntity(loaded)
        syncPhaseBossBar()

        if (wasUnloaded && !isDyingAnimation && currentPhase > 0 && !waitingForStevesToDie) {
            isFloatingInvulnerable = false
            isSlamming = false
            isLevitatingUp = false
            loaded.setGravity(true)
            val groundLoc = loaded.location.clone().apply {
                y = findArenaGroundY(this, spawnOrigin.y)
            }
            loaded.teleport(groundLoc)
            loaded.equipment.setItemInMainHand(ItemStack(getPhaseSwordMaterial()))
        }
    }

    private fun syncPhaseBossBar() {
        if (!::mannequin.isInitialized || currentPhase <= 0) return
        val (title, color) = when (currentPhase) {
            3 -> "<bold><light_purple>Herobrine</light_purple></bold>" to BossBar.Color.PURPLE
            2 -> "<bold><red>Herobrine</red></bold>" to BossBar.Color.RED
            else -> "<bold><white>Herobrine</white></bold>" to BossBar.Color.WHITE
        }
        val settings = BossBarSettings(
            enabled = true,
            title = title,
            color = color,
            overlay = BossBar.Overlay.NOTCHED_10,
            flags = setOf(BossBar.Flag.DARKEN_SCREEN),
            radius = 96.0
        )
        plugin.bossBarManager.createBossBar(mannequin, settings)
    }

    private fun ensureLiveMannequin(): Boolean {
        if (mannequin.isValid) return true
        val live = Bukkit.getEntity(mannequinId) as? Mannequin
        if (live != null && live.isValid) {
            rebindMannequin(live)
            return true
        }
        return false
    }

    private fun setupTeam() {
        val sb = Bukkit.getScoreboardManager().mainScoreboard
        val prefix = tablistUuid.toString().take(8)

        val hbTeamName = "HB_$prefix"
        sb.getTeam(hbTeamName)?.unregister()
        herobrineTeam = sb.registerNewTeam(hbTeamName).apply {
            setAllowFriendlyFire(false)
            color(NamedTextColor.WHITE)
        }

        // Dedicated team for Steve minions with NAME_TAG_VISIBILITY = NEVER so no names render!
        val steveTeamName = "HBS_$prefix"
        sb.getTeam(steveTeamName)?.unregister()
        steveTeam = sb.registerNewTeam(steveTeamName).apply {
            setAllowFriendlyFire(false)
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            color(NamedTextColor.AQUA)
        }
    }

    // ========================================================================
    // SMOOTH GROUND-TO-AIR LEVITATION (WITH RESONATING BELL SOUND)
    // ========================================================================

    private fun levitateToFloatHeight(heightAboveFloor: Double = 5.2, durationTicks: Int = 25, onComplete: () -> Unit = {}) {
        isFloatingInvulnerable = true
        isLevitatingUp = true

        val startLoc = mannequin.location.clone()
        val floorY = findArenaGroundY(startLoc, spawnOrigin.y)
        val targetY = (floorY + heightAboveFloor).coerceAtLeast(startLoc.y + 1.5)

        mannequin.setGravity(false)
        mannequin.velocity = Vector(0.0, 0.0, 0.0)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

        world.playSound(startLoc, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 3.0f, 0.8f)
        world.playSound(startLoc, Sound.BLOCK_BELL_USE, SoundCategory.HOSTILE, 2.5f, 0.7f)
        world.playSound(startLoc, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 0.6f)

        var step = 0
        var riseTask: BukkitTask? = null
        riseTask = runTimer(1L, 1L) {
            step++
            if (isDeadOrRemoved || !ensureLiveMannequin() || step > durationTicks) {
                isLevitatingUp = false
                riseTask?.cancel()
                if (!isDeadOrRemoved && mannequin.isValid) {
                    onComplete()
                }
                return@runTimer
            }

            val progress = step.toDouble() / durationTicks
            val curY = startLoc.y + (targetY - startLoc.y) * progress
            val nextLoc = Location(world, startLoc.x, curY, startLoc.z, mannequin.location.yaw, mannequin.location.pitch)
            mannequin.teleport(nextLoc)

            resolveCombatTarget()?.let { faceLocation(it.eyeLocation) }
            world.spawnParticle(Particle.DUST, nextLoc.clone().add(0.0, 0.8, 0.0), 6, 0.4, 0.4, 0.4, 0.0, redstoneDust)
            world.spawnParticle(Particle.ELECTRIC_SPARK, nextLoc.clone().add(0.0, 0.8, 0.0), 4, 0.3, 0.3, 0.3, 0.02)
        }
    }

    // ========================================================================
    // DISORIENTING COMBAT MELEE BLINK (CONFUSES PLAYERS WHEN MELEE'D)
    // ========================================================================

    fun tryCombatMeleeBlink(attacker: Player) {
        if (isDeadOrRemoved || isDyingAnimation || isFloatingInvulnerable || isSlamming || currentPhase <= 0) return
        if (!ensureLiveMannequin()) return

        val now = System.currentTimeMillis()
        if (now - lastMeleeBlinkMillis < 1200L) return
        lastMeleeBlinkMillis = now

        val origin = mannequin.location.clone()
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 1.2f)
        world.spawnParticle(Particle.DUST, origin.clone().add(0.0, 1.0, 0.0), 25, 0.4, 0.6, 0.4, 0.0, redstoneDust)
        world.spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0.0, 1.0, 0.0), 15, 0.4, 0.6, 0.4, 0.05)

        val pLoc = attacker.location
        var dest: Location? = null
        repeat(10) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val dist = Random.nextDouble(3.2, 5.5)
            val candidate = Location(world, pLoc.x + cos(angle) * dist, pLoc.y, pLoc.z + sin(angle) * dist)
            val groundY = findArenaGroundY(candidate, pLoc.y)
            candidate.y = groundY
            if (candidate.block.isPassable && candidate.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                dest = candidate
                return@repeat
            }
        }

        val finalDest = dest ?: return
        val lookDir = attacker.eyeLocation.toVector().subtract(finalDest.clone().add(0.0, 1.62, 0.0).toVector())
        if (lookDir.lengthSquared() > 1e-4) {
            finalDest.setDirection(lookDir)
        }

        mannequin.teleport(finalDest)
        faceLocation(attacker.eyeLocation)
        world.playSound(finalDest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 0.8f)
        world.spawnParticle(Particle.DUST, finalDest.clone().add(0.0, 1.0, 0.0), 25, 0.4, 0.6, 0.4, 0.0, redstoneDust)
        world.spawnParticle(Particle.ELECTRIC_SPARK, finalDest.clone().add(0.0, 1.0, 0.0), 15, 0.4, 0.6, 0.4, 0.05)
    }

    // ========================================================================
    // PRE-PHASE 1 -> PHASE 1 AWAKENING
    // ========================================================================

    fun awakenPhase1(firstAttacker: Player?) {
        if (currentPhase > 0) return
        currentPhase = 1
        currentTarget = firstAttacker ?: resolveSummonerOrNearest()

        syncPhaseBossBar()

        world.setStorm(true)
        world.isThundering = true
        if (wasAdvanceTime) {
            world.setGameRule(GameRules.ADVANCE_TIME, false)
        }

        var toggleNight = true
        runTimer(10L, 40L) {
            if (isDeadOrRemoved) return@runTimer
            val currentDayBase = (savedFullTime / 24000L) * 24000L
            world.fullTime = currentDayBase + if (toggleNight) 18000L else 6000L
            toggleNight = !toggleNight
        }

        enterFloatingInvulnerableState(spawnSteves = false)
        runLater(45L) {
            if (!isDeadOrRemoved && ensureLiveMannequin()) {
                val slamTarget = pickRandomOrSolePlayer() ?: currentTarget ?: resolveSummonerOrNearest()
                if (slamTarget != null) {
                    performPhase1RedstoneSlam(slamTarget)
                } else {
                    isFloatingInvulnerable = false
                    isSlamming = false
                    mannequin.setGravity(true)
                    mannequin.equipment.setItemInMainHand(ItemStack(Material.IRON_SWORD))
                }
            }
        }
    }

    // ========================================================================
    // PHASE TRANSITIONS (EVERY 500 HP MARK + SMOOTH FLOAT + DARKNESS)
    // ========================================================================

    private fun playPhaseTransitionAnimation(isPhase3Rainbow: Boolean, onExplosionComplete: () -> Unit) {
        damageSinceLastSteveWave = 0.0
        waitingForStevesToDie = false
        floatingSteveWaitTicks = 0
        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        mannequin.location.getNearbyPlayers(96.0).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 95, 0, false, false, true))
        }

        levitateToFloatHeight(heightAboveFloor = 5.5, durationTicks = 25) {
            listOf(15L, 30L, 45L, 60L).forEach { delay ->
                runLater(delay) {
                    if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
                    val loc = mannequin.location
                    world.strikeLightningEffect(loc)
                    world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 3.0f, 0.8f)
                    world.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION,
                        loc.clone().add(0.0, 1.0, 0.0),
                        60, 1.2, 1.2, 1.2, 0.0,
                        deepRedTransition
                    )
                }
            }

            runLater(70L) {
                if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
                val loc = mannequin.location
                val arenaFloorLoc = Location(world, loc.x, findArenaGroundY(loc, spawnOrigin.y), loc.z)

                loc.getNearbyPlayers(128.0).forEach { player ->
                    player.removePotionEffect(PotionEffectType.DARKNESS)
                }

                val explosionPower = if (isPhase3Rainbow) 6.0f else 4.0f
                world.createExplosion(arenaFloorLoc, explosionPower, false, false, mannequin)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, arenaFloorLoc, 1)

                if (isPhase3Rainbow) {
                    for (dx in listOf(-1.0, 0.0, 1.0)) {
                        for (dz in listOf(-1.0, 0.0, 1.0)) {
                            val lLoc = arenaFloorLoc.clone().add(dx, 0.0, dz)
                            lLoc.y = findArenaGroundY(lLoc, arenaFloorLoc.y)
                            world.strikeLightningEffect(lLoc)
                        }
                    }
                }

                onExplosionComplete()
            }
        }
    }

    private fun enterPhase2() {
        if (currentPhase >= 2) return
        currentPhase = 2
        mannequin.health = config.phase2Threshold // 1000.0 HP

        mannequin.customName("<bold><red>Herobrine</red></bold>".mm())
        syncPhaseBossBar()

        playPhaseTransitionAnimation(isPhase3Rainbow = false) {
            val target = pickRandomOrSolePlayer() ?: resolveCombatTarget()
            if (target != null) {
                performPhase2PreSlamSequence(target)
            } else {
                isFloatingInvulnerable = false
                isSlamming = false
                mannequin.setGravity(true)
                mannequin.equipment.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))
            }
        }
    }

    private fun enterPhase3() {
        if (currentPhase >= 3) return
        currentPhase = 3
        mannequin.health = config.phase3Threshold // 500.0 HP

        mannequin.isGlowing = true
        mannequin.customName("<bold><light_purple>Herobrine</light_purple></bold>".mm())
        syncPhaseBossBar()

        playPhaseTransitionAnimation(isPhase3Rainbow = true) {
            val target = pickRandomOrSolePlayer() ?: resolveCombatTarget()
            if (target != null) {
                performPhase3PreSlamSequence(target)
            } else {
                isFloatingInvulnerable = false
                isSlamming = false
                mannequin.setGravity(true)
                mannequin.equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
            }
        }
    }

    // ========================================================================
    // 32x32 PHASE-CHANGING ARENA PARTICLE AURA (radius = 16.0 blocks)
    // ========================================================================

    private fun tickPhaseArenaAura(center: Location) {
        if (currentPhase <= 0) return
        val halfSize = 16.0 // 32x32 footprint!
        val groundY = findArenaGroundY(center, spawnOrigin.y) + 0.2

        repeat(24) {
            val dx = Random.nextDouble(-halfSize, halfSize)
            val dz = Random.nextDouble(-halfSize, halfSize)
            val yOffset = Random.nextDouble(0.0, 5.5)
            val pt = Location(world, center.x + dx, groundY + yOffset, center.z + dz)

            when (currentPhase) {
                1 -> {
                    world.spawnParticle(Particle.DUST, pt, 1, 0.1, 0.2, 0.1, 0.0, redstoneDust)
                    if (it % 3 == 0) {
                        world.spawnParticle(Particle.END_ROD, pt, 1, 0.05, 0.1, 0.05, 0.01)
                        world.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.05, 0.1, 0.05, 0.02)
                    }
                }
                2 -> {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.15, 0.3, 0.15, 0.0, deepRedTransition)
                    if (it % 3 == 0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, pt, 1, 0.05, 0.1, 0.05, 0.01)
                        world.spawnParticle(Particle.LARGE_SMOKE, pt, 1, 0.1, 0.1, 0.1, 0.01)
                    }
                }
                else -> {
                    val c1 = rainbowDustColors.random()
                    val c2 = rainbowDustColors.random()
                    world.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION,
                        pt,
                        1, 0.15, 0.35, 0.15, 0.0,
                        Particle.DustTransition(c1, c2, 1.7f)
                    )
                    if (it % 3 == 0) {
                        world.spawnParticle(Particle.END_ROD, pt, 1, 0.05, 0.1, 0.05, 0.02)
                        world.spawnParticle(Particle.PORTAL, pt, 2, 0.1, 0.1, 0.1, 0.05)
                    }
                }
            }
        }
    }

    // ========================================================================
    // CORE AI & AMBIENT PARTICLES
    // ========================================================================

    private fun scheduleCoreLoops() {
        // 2-Tick Loop: Body Aura + 32x32 Arena Aura
        runTimer(2L, 2L) {
            if (isDeadOrRemoved || isDyingAnimation) return@runTimer
            if (!ensureLiveMannequin() || mannequin.isDead) return@runTimer
            val pLoc = mannequin.location.add(0.0, 1.1, 0.0)

            tickPhaseArenaAura(mannequin.location)

            if (currentPhase >= 3) {
                rainbowColorIndex = (rainbowColorIndex + 1) % rainbowTeamColors.size
                herobrineTeam?.color(rainbowTeamColors[rainbowColorIndex])
                val c1 = rainbowDustColors[rainbowColorIndex % rainbowDustColors.size]
                val c2 = rainbowDustColors[(rainbowColorIndex + 1) % rainbowDustColors.size]
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    pLoc,
                    6, 0.4, 0.6, 0.4, 0.0,
                    Particle.DustTransition(c1, c2, 1.8f)
                )
                world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 3, 0.35, 0.5, 0.35, 0.02)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 5, 0.4, 0.5, 0.4, 0.5)
            } else if (currentPhase == 2) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pLoc, 6, 0.4, 0.6, 0.4, 0.0, deepRedTransition)
                world.spawnParticle(Particle.DUST, pLoc, 4, 0.35, 0.5, 0.35, 0.0, redstoneDust)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 4, 0.4, 0.5, 0.4, 0.5)
            } else {
                world.spawnParticle(Particle.DUST, pLoc, 3, 0.35, 0.5, 0.35, 0.0, redstoneDust)
                world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 3, 0.35, 0.5, 0.35, 0.02)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 4, 0.4, 0.5, 0.4, 0.5)
            }
        }

        // 1-Tick AI Loop
        runTimer(1L, 1L) {
            if (isDeadOrRemoved || isDyingAnimation) return@runTimer
            if (!ensureLiveMannequin() || mannequin.isDead) return@runTimer
            if (attackCooldownTicks > 0) attackCooldownTicks--

            if (mannequin.location.distanceSquared(spawnOrigin) > config.maxCombatDistance * config.maxCombatDistance) {
                mannequin.teleport(spawnOrigin)
            }

            if (currentPhase == 0) {
                mannequin.velocity = Vector(0.0, mannequin.velocity.y.coerceAtMost(0.0), 0.0)
                val stareTarget = resolveSummonerOrNearest() ?: return@runTimer
                faceLocation(stareTarget.eyeLocation)
                return@runTimer
            }

            activeSteves.removeIf { it.mannequin.isDead }

            val target = resolveCombatTarget()
            if (target != null && activeSteves.isNotEmpty()) {
                for (steve in activeSteves) {
                    steve.tick(target)
                }
            }

            if (waitingForStevesToDie && !isLevitatingUp) {
                floatingSteveWaitTicks++

                // In Phase 3, while floating with minions, periodically unleash his Wither Skull Attack!
                if (currentPhase >= 3 && floatingSteveWaitTicks % 160 == 60 && activeSteves.isNotEmpty()) {
                    triggerWitherSkullVolley()
                }

                if (activeSteves.isEmpty() || floatingSteveWaitTicks >= 1200) {
                    waitingForStevesToDie = false
                    floatingSteveWaitTicks = 0
                    activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
                    activeSteves.clear()

                    val chosen = pickRandomOrSolePlayer() ?: target
                    if (chosen != null) {
                        when (currentPhase) {
                            1 -> performPhase1RedstoneSlam(chosen)
                            2 -> performPhase2PreSlamSequence(chosen)
                            else -> performPhase3PreSlamSequence(chosen)
                        }
                    } else {
                        isFloatingInvulnerable = false
                        isSlamming = false
                        mannequin.setGravity(true)
                        mannequin.equipment.setItemInMainHand(ItemStack(getPhaseSwordMaterial()))
                    }
                    return@runTimer
                }
            }

            if (isFloatingInvulnerable) {
                if (!isLevitatingUp) {
                    mannequin.velocity = Vector(0.0, sin(Bukkit.getCurrentTick() * 0.15) * 0.04, 0.0)
                }
                if (target != null) {
                    faceLocation(target.eyeLocation)
                }
                return@runTimer
            }

            if (isSlamming || target == null) return@runTimer

            tickGroundCombat(target)
        }
    }

    private fun getPhaseSwordMaterial(): Material = when (currentPhase) {
        3 -> Material.NETHERITE_SWORD
        2 -> Material.DIAMOND_SWORD
        else -> Material.IRON_SWORD
    }

    private fun getPhaseAxeMaterial(): Material = when (currentPhase) {
        3 -> Material.NETHERITE_AXE
        2 -> Material.DIAMOND_AXE
        else -> Material.IRON_AXE
    }

    private fun createPhaseRangedWeapon(): ItemStack {
        return if (currentPhase >= 3) {
            ItemStack(Material.CROSSBOW).apply {
                editMeta { meta ->
                    meta.addEnchant(Enchantment.QUICK_CHARGE, 3, true)
                    meta.addEnchant(Enchantment.PIERCING, 4, true)
                }
            }
        } else {
            ItemStack(Material.BOW)
        }
    }

    private fun tickGroundCombat(target: Player) {
        faceLocation(target.eyeLocation)
        stanceSwitchTicks++

        if (stanceSwitchTicks >= 160) {
            stanceSwitchTicks = 0
            usingRangedStance = !usingRangedStance
            val weapon = if (usingRangedStance) createPhaseRangedWeapon() else ItemStack(getPhaseSwordMaterial())
            mannequin.equipment.setItemInMainHand(weapon)
            world.playSound(mannequin.location, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.HOSTILE, 1.5f, 1.0f)
        }

        val mLoc = mannequin.location
        val tLoc = target.location
        val flatDiff = tLoc.toVector().subtract(mLoc.toVector()).setY(0.0)
        val distSq = flatDiff.lengthSquared()

        if (!usingRangedStance) {
            if (distSq > 5.5) {
                val moveDir = flatDiff.clone().normalize()
                val ahead = mLoc.clone().add(moveDir).block
                val jumpY = if (mannequin.isOnGround && ahead.type.isSolid && ahead.getRelative(0, 1, 0).isPassable) 0.42 else mannequin.velocity.y
                mannequin.velocity = Vector(moveDir.x * config.movementSpeed, jumpY, moveDir.z * config.movementSpeed)
            }

            if (distSq <= 9.0 && kotlin.math.abs(tLoc.y - mLoc.y) <= 2.5 && attackCooldownTicks <= 0) {
                attackCooldownTicks = 15
                executePvpMeleeStrike(target)
            }
        } else {
            if (distSq < 64.0) {
                val backDir = mLoc.toVector().subtract(tLoc.toVector()).setY(0.0).normalize()
                mannequin.velocity = Vector(backDir.x * 0.25, mannequin.velocity.y, backDir.z * 0.25)
            } else if (distSq > 324.0) {
                val moveDir = flatDiff.clone().normalize()
                mannequin.velocity = Vector(moveDir.x * 0.28, mannequin.velocity.y, moveDir.z * 0.28)
            }

            if (attackCooldownTicks <= 0) {
                attackCooldownTicks = if (currentPhase >= 3) 26 else 35
                fireHomingArrow(target)
            }
        }
    }

    private fun isFrontalHit(victim: LivingEntity, attacker: Entity): Boolean {
        val yawRad = Math.toRadians(victim.yaw.toDouble())
        val lookX = -sin(yawRad)
        val lookZ = cos(yawRad)
        val toAttackerX = attacker.location.x - victim.location.x
        val toAttackerZ = attacker.location.z - victim.location.z
        return (lookX * toAttackerX + lookZ * toAttackerZ) >= 0.0
    }

    private fun executePvpMeleeStrike(target: Player) {
        val isPlayerShielding = target.isBlocking
        val swordMat = getPhaseSwordMaterial()
        val axeMat = getPhaseAxeMaterial()

        if (isPlayerShielding && Random.nextDouble() < 0.20) {
            mannequin.equipment.setItemInMainHand(ItemStack(axeMat))
            mannequin.swingMainHand()

            target.setCooldown(Material.SHIELD, 100)
            target.clearActiveItem()
            world.playSound(target.location, Sound.ITEM_SHIELD_BREAK, SoundCategory.HOSTILE, 2.0f, 0.9f)
            world.spawnParticle(Particle.DUST, target.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.4, 0.3, 0.0, redstoneDust)

            val axeSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(mannequin)
                .withDirectEntity(mannequin)
                .build()
            val axeDmg = when (currentPhase) {
                3 -> 18.0
                2 -> 16.0
                else -> 14.0
            }
            target.damage(axeDmg, axeSource)

            runLater(12L) {
                if (!usingRangedStance && mannequin.isValid) {
                    mannequin.equipment.setItemInMainHand(ItemStack(swordMat))
                }
            }
        } else {
            mannequin.equipment.setItemInMainHand(ItemStack(swordMat))
            mannequin.swingMainHand()

            if (isPlayerShielding) {
                world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 1.0f)
                val push = target.location.toVector().subtract(mannequin.location.toVector()).setY(0.0).normalize()
                target.velocity = target.velocity.add(push.multiply(0.4).setY(0.2))
            } else {
                val swordSource = DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(mannequin)
                    .withDirectEntity(mannequin)
                    .build()
                val swordDmg = when (currentPhase) {
                    3 -> 15.0
                    2 -> 13.0
                    else -> 11.0
                }
                target.damage(swordDmg, swordSource)
            }
        }
    }

    private fun fireHomingArrow(target: Player) {
        mannequin.swingMainHand()
        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        val initialDir = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()

        val shootSound = if (currentPhase >= 3) Sound.ITEM_CROSSBOW_SHOOT else Sound.ENTITY_ARROW_SHOOT
        world.playSound(eye, shootSound, SoundCategory.HOSTILE, 1.5f, 0.8f)

        val arrow = world.spawn(eye.add(initialDir), Arrow::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { a ->
            a.shooter = mannequin
            a.velocity = initialDir.multiply(1.45)
            a.damage = if (currentPhase >= 3) 8.5 else 7.0
            if (currentPhase >= 3) {
                a.pierceLevel = 4
            }
            a.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        }

        var ticks = 0
        var homingTask: BukkitTask? = null
        homingTask = runTimer(1L, 1L) {
            ticks++
            if (!arrow.isValid || arrow.isDead || arrow.isInBlock || !target.isValid || target.isDead || ticks > 70) {
                homingTask?.cancel()
                return@runTimer
            }

            val desired = target.eyeLocation.toVector().subtract(arrow.location.toVector())
            if (desired.lengthSquared() > 1e-4) {
                desired.normalize()
                val cur = arrow.velocity
                val speed = cur.length().coerceIn(1.1, 1.6)
                val steered = cur.normalize().multiply(0.75).add(desired.multiply(0.25)).normalize()
                arrow.velocity = steered.multiply(speed)
            }
            world.spawnParticle(Particle.DUST, arrow.location, 2, 0.05, 0.05, 0.05, 0.0, redstoneDust)
        }
    }

    // ========================================================================
    // STEVE WAVES (PHASE 1 = 4-4, PHASE 2 = 7-7, PHASE 3 = 10-10 + BELL SOUND)
    // ========================================================================

    private fun enterFloatingInvulnerableState(spawnSteves: Boolean) {
        val baseLoc = mannequin.location.clone()
        val arenaFloorY = findArenaGroundY(baseLoc, spawnOrigin.y)

        if (spawnSteves) {
            waitingForStevesToDie = true
            floatingSteveWaitTicks = 0
        }

        levitateToFloatHeight(heightAboveFloor = 5.0, durationTicks = 25) {
            if (spawnSteves) {
                spawnSteveWave(phaseTier = currentPhase, referenceLoc = Location(world, baseLoc.x, arenaFloorY, baseLoc.z))
            }
        }
    }

    private fun spawnSteveWave(phaseTier: Int, referenceLoc: Location) {
        val countPerType = when (phaseTier) {
            3 -> 10 // 10 Melee + 10 Archer = 20 Steves
            2 -> 7  // 7 Melee + 7 Archer = 14 Steves
            else -> 4 // 4 Melee + 4 Archer = 8 Steves
        }
        val total = countPerType * 2

        world.playSound(referenceLoc, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 3.5f, 0.65f)
        world.playSound(referenceLoc, Sound.BLOCK_BELL_USE, SoundCategory.HOSTILE, 3.0f, 0.6f)
        world.playSound(referenceLoc, Sound.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.HOSTILE, 2.5f, 0.5f)

        for (i in 0 until total) {
            val angle = 2.0 * Math.PI * i / total
            val ringRadius = 4.0 + (i % 3) * 2.2
            val candidate = referenceLoc.clone().add(cos(angle) * ringRadius, 0.0, sin(angle) * ringRadius)
            candidate.y = findArenaGroundY(candidate, referenceLoc.y)
            if (i % 2 == 0) {
                world.strikeLightningEffect(candidate)
            }

            val isArcher = i >= countPerType
            val controller = SteveMinionController.spawnSteve(candidate, isArcher = isArcher, phaseTier = phaseTier)
            steveTeam?.addEntity(controller.mannequin)
            activeSteves.add(controller)
        }
    }

    // ========================================================================
    // PHASE 2 PRE-SLAM: LIGHTNING -> LASER -> 1 TRIDENT/PLAYER -> WITHER (1x) -> 3s DELAY -> SLAM
    // ========================================================================

    private fun performPhase2PreSlamSequence(target: Player) {
        isFloatingInvulnerable = true
        isSlamming = true
        currentTarget = target

        // 1. Ticks 0..170: Condensed 5-Wave Lightning Circles + Star
        scheduleWaveThenStarCycle(startDelay = 0L, applyWither = false)

        // 2. Ticks 175..215: Guardian Laser Lock-On
        runLater(175L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            spawnGuardianLaserLockOn(durationTicks = 40, fallbackTarget = target)
        }

        // 3. Tick 218: Throw 1 Channeling Trident at each nearby player
        runLater(218L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            throwTridentVolleyAtAllPlayers(fallbackTarget = target)
        }

        // 4. Tick 250: Enraged Wither 360° Homing Skull Attack (1x, lightning on player hit)
        runLater(250L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            triggerWitherSkullVolley()
        }

        // 5. Tick 345: 3-Second Telegraph Delay -> 3x3 Phase 2 Lightning Slam
        runLater(345L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            scheduleThreeSecondDelayedSlam(fallbackTarget = target, isPhase3 = false)
        }
    }

    // ========================================================================
    // PHASE 3 PRE-SLAM: LIGHTNING -> LASER -> 3 SETS OF TRIDENTS -> WITHER (2x) -> DRAGON LASER -> 3s DELAY -> SLAM
    // ========================================================================

    private fun performPhase3PreSlamSequence(target: Player) {
        isFloatingInvulnerable = true
        isSlamming = true
        currentTarget = target

        // 1. Ticks 0..170: Condensed 5-Wave Lightning Circles + Star
        scheduleWaveThenStarCycle(startDelay = 0L, applyWither = true)

        // 2. Ticks 175..215: Guardian Laser Lock-On
        runLater(175L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            spawnGuardianLaserLockOn(durationTicks = 40, fallbackTarget = target)
        }

        // 3. Ticks 218, 244, 270: Three Sets (3 Volleys) of Channeling Trident Attacks on all nearby players
        listOf(218L, 244L, 270L).forEach { delay ->
            runLater(delay) {
                if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
                throwTridentVolleyAtAllPlayers(fallbackTarget = target)
            }
        }

        // 4. Ticks 300 & 395: Enraged Wither 360° Homing Skull Attack TWICE (2x, lightning on player hit)
        runLater(300L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            triggerWitherSkullVolley()
        }
        runLater(395L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            triggerWitherSkullVolley()
        }

        // 5. Tick 490..605: Outward-Spiraling Ender Dragon Laser Beam (Sonic Boom Charge/Fire + 2 hearts/tick)
        runLater(490L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            triggerPhase3DragonLaserSweep()
        }

        // 6. Tick 610: 3-Second Telegraph Delay -> 3x3 Phase 3 Final Slam
        runLater(610L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            scheduleThreeSecondDelayedSlam(fallbackTarget = target, isPhase3 = true)
        }
    }

    // ========================================================================
    // GUARDIAN LASER LOCK-ON & MULTI-PLAYER CHANNELING TRIDENT VOLLEYS
    // ========================================================================

    private fun spawnGuardianLaserLockOn(durationTicks: Int, fallbackTarget: Player) {
        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        val laserGuardians = mutableListOf<Guardian>()

        val targets = mannequin.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .ifEmpty { listOfNotNull(fallbackTarget.takeIf { !it.isDead }) }

        for (p in targets) {
            val g = world.spawn(eye, Guardian::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { guardian ->
                guardian.persistentDataContainer.set(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE, 1)
                guardian.isInvisible = true
                guardian.setInvulnerable(true)
                guardian.isSilent = false
                guardian.setAI(true)
                guardian.setGravity(false)
                guardian.target = p
                guardian.setLaser(true)
            }
            laserGuardians.add(g)
        }

        var laserTick = 0
        var laserTask: BukkitTask? = null
        laserTask = runTimer(1L, 2L) {
            laserTick += 2
            if (isDeadOrRemoved || !ensureLiveMannequin() || laserTick >= durationTicks) {
                laserTask?.cancel()
                laserGuardians.forEach { it.remove() }
                return@runTimer
            }

            val currentOrigin = mannequin.location.add(0.0, 1.62, 0.0)
            laserGuardians.forEach { if (it.isValid) it.teleport(currentOrigin) }

            val pitch = 0.6f + (laserTick / durationTicks.toFloat()) * 1.4f
            world.playSound(currentOrigin, Sound.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 2.0f, pitch)
        }

        runLater(durationTicks.toLong()) {
            laserGuardians.forEach { it.remove() }
        }
    }

    /**
     * Throws 1 Channeling Trident at each nearby survival/adventure player in the arena.
     */
    private fun throwTridentVolleyAtAllPlayers(fallbackTarget: Player) {
        val tridentStack = ItemStack(Material.TRIDENT).apply {
            editMeta { it.addEnchant(Enchantment.CHANNELING, 1, true) }
        }
        mannequin.equipment.setItemInMainHand(tridentStack)
        mannequin.swingMainHand()

        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        world.playSound(eye, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.HOSTILE, 3.0f, 1.2f)
        world.playSound(eye, Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 2.5f, 0.9f)

        val targets = mannequin.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .ifEmpty { listOfNotNull(fallbackTarget.takeIf { !it.isDead }) }

        for (target in targets) {
            faceLocation(target.eyeLocation)
            val throwDir = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()

            val trident = world.spawn(eye.clone().add(throwDir), Trident::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { t ->
                t.shooter = mannequin
                t.itemStack = tridentStack
                t.velocity = throwDir.multiply(2.2)
                t.damage = 0.0
                t.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                t.persistentDataContainer.set(MobKeys.HEROBRINE_TRIDENT, PersistentDataType.BYTE, 1)
            }

            var flightTicks = 0
            var watchTask: BukkitTask? = null
            watchTask = runTimer(1L, 1L) {
                flightTicks++
                if (isDeadOrRemoved || !trident.isValid || trident.isDead || trident.isInBlock || flightTicks > 50) {
                    if (trident.isValid && (trident.isInBlock || flightTicks > 50)) {
                        world.strikeLightningEffect(trident.location)
                        trident.remove()
                    }
                    watchTask?.cancel()
                    return@runTimer
                }
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, trident.location, 3, 0.1, 0.1, 0.1, 0.0, deepRedTransition)
            }
        }

        runLater(10L) {
            if (!isDeadOrRemoved && mannequin.isValid && isFloatingInvulnerable) {
                mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))
            }
        }
    }

    // ========================================================================
    // ENRAGED WITHER 360° HOMING SKULL RING (STRIKES LIGHTNING ON PLAYER HIT)
    // ========================================================================

    private fun triggerWitherSkullVolley() {
        if (isDeadOrRemoved || !ensureLiveMannequin()) return
        val center = mannequin.location.clone().add(0.0, 1.2, 0.0)
        val skulls = mutableListOf<WitherSkull>()

        world.playSound(center, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 2.5f, 1.2f)
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 2.0f, 1.8f)

        for (i in 0 until 24) {
            val angle = Math.toRadians(i * 15.0)
            val motion = Vector(sin(angle) * 0.75, 0.35, cos(angle) * 0.75)
            val skull = world.spawn(center, WitherSkull::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { s ->
                s.shooter = mannequin
                s.velocity = motion
                s.persistentDataContainer.set(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE, 1)
                herobrineTeam?.addEntity(s)
            }
            skulls.add(skull)
        }

        runLater(20L) {
            if (isDeadOrRemoved) {
                skulls.forEach { if (it.isValid) it.remove() }
                return@runLater
            }

            val target = pickRandomOrSolePlayer() ?: resolveCombatTarget()
            skulls.filter { it.isValid }.forEach { s ->
                s.velocity = Vector(0.0, 0.0, 0.0)
                s.setGravity(false)
                s.isGlowing = true
                if (target != null) {
                    val dir = target.eyeLocation.toVector().subtract(s.location.toVector())
                    if (dir.lengthSquared() > 1e-4) {
                        val rot = s.location.setDirection(dir)
                        s.setRotation(rot.yaw, rot.pitch)
                    }
                }
            }

            listOf(8L to 0.5f, 16L to 0.8f, 24L to 1.3f).forEach { (delay, pitch) ->
                runLater(delay) {
                    val p = pickRandomOrSolePlayer() ?: resolveCombatTarget() ?: return@runLater
                    p.playSound(p.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.HOSTILE, 1.0f, pitch)
                    skulls.filter { it.isValid }.forEach { s ->
                        val dir = p.eyeLocation.toVector().subtract(s.location.toVector())
                        if (dir.lengthSquared() > 1e-4) {
                            val rot = s.location.setDirection(dir)
                            s.setRotation(rot.yaw, rot.pitch)
                        }
                    }
                }
            }

            runLater(32L) {
                val p = pickRandomOrSolePlayer() ?: resolveCombatTarget()
                if (p != null) {
                    world.playSound(mannequin.location, Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 3.0f, 0.8f)
                }

                var homeStep = 0
                var homeTask: BukkitTask? = null
                homeTask = runTimer(1L, 1L) {
                    homeStep++
                    val liveTarget = resolveCombatTarget()
                    val validSkulls = skulls.filter { it.isValid && !it.isDead }

                    if (isDeadOrRemoved || validSkulls.isEmpty() || homeStep > 40) {
                        validSkulls.forEach { s ->
                            world.spawnParticle(Particle.EXPLOSION, s.location, 2, 0.2, 0.2, 0.2, 0.0)
                            s.remove()
                        }
                        homeTask?.cancel()
                        return@runTimer
                    }

                    for (s in validSkulls) {
                        if (liveTarget != null) {
                            val desired = liveTarget.eyeLocation.toVector().subtract(s.location.toVector())
                            if (desired.lengthSquared() > 1e-4) {
                                desired.normalize()
                                val cur = s.velocity
                                val steered = if (cur.lengthSquared() > 1e-4) {
                                    cur.normalize().multiply(0.70).add(desired.multiply(0.30)).normalize()
                                } else {
                                    desired
                                }
                                s.velocity = steered.multiply(1.15)
                            }
                        }
                        world.spawnParticle(Particle.DUST, s.location, 1, 0.05, 0.05, 0.05, 0.0, redstoneDust)

                        val hitPlayer = s.location.getNearbyPlayers(1.6)
                            .firstOrNull { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                        if (hitPlayer != null) {
                            if (hitPlayer.isBlocking && isFrontalHit(hitPlayer, s)) {
                                hitPlayer.world.playSound(hitPlayer.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 1.0f)
                                hitPlayer.world.spawnParticle(Particle.CRIT, s.location, 12, 0.25, 0.25, 0.25, 0.1)
                                val push = hitPlayer.location.toVector().subtract(s.location.toVector()).setY(0.0)
                                if (push.lengthSquared() > 1e-4) push.normalize()
                                hitPlayer.velocity = hitPlayer.velocity.add(push.multiply(0.35).setY(0.15))
                                s.remove()
                            } else {
                                world.strikeLightningEffect(hitPlayer.location)
                                world.playSound(hitPlayer.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 2.0f, 1.1f)
                                val skullSource = DamageSource.builder(DamageType.WITHER_SKULL)
                                    .withCausingEntity(mannequin)
                                    .withDirectEntity(s)
                                    .build()
                                hitPlayer.damage(6.0, skullSource)
                                s.remove()
                            }
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // PHASE 3 FLOATING SKILL: OUTWARD-SPIRALING ENDER DRAGON LASER BEAM
    // (Warden Sonic Boom Charge/Fire + Punchy Ground Debris + 2 Hearts/Tick)
    // ========================================================================

    private fun triggerPhase3DragonLaserSweep() {
        val origin = mannequin.location.clone()
        val chargeTicks = 30
        val spiralTicks = 80
        val totalTicks = chargeTicks + spiralTicks
        val groundDebrisData = Material.REDSTONE_BLOCK.createBlockData()

        world.playSound(origin, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 5.0f, 0.95f)
        world.playSound(origin, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 4.0f, 1.2f)

        var tick = 0
        var laserTask: BukkitTask? = null
        laserTask = runTimer(1L, 1L) {
            tick++
            if (isDeadOrRemoved || !ensureLiveMannequin() || tick > totalTicks) {
                laserTask?.cancel()
                return@runTimer
            }

            val eye = mannequin.location.clone().add(0.0, 1.62, 0.0)

            if (tick <= chargeTicks) {
                world.spawnParticle(Particle.END_ROD, eye, 8, 1.2, 1.2, 1.2, 0.05)
                if (tick % 6 == 0) {
                    world.spawnParticle(Particle.FLASH, eye, 1, 0.1, 0.1, 0.1, 0.0, Color.WHITE)
                }
                return@runTimer
            }

            val laserStep = tick - chargeTicks
            if (laserStep == 1) {
                world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5.0f, 0.85f)
                world.playSound(eye, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 0.9f)
                world.spawnParticle(Particle.FLASH, eye, 2, 0.2, 0.2, 0.2, 0.0, Color.WHITE)
            }

            val spiralProgress = laserStep.toDouble() / spiralTicks
            val currentRadius = 2.0 + spiralProgress * 22.0
            val angleDeg = (laserStep * 16.0) % 360.0
            val angleRad = Math.toRadians(angleDeg)

            val targetX = mannequin.location.x + cos(angleRad) * currentRadius
            val targetZ = mannequin.location.z + sin(angleRad) * currentRadius
            val groundCandidate = Location(world, targetX, spawnOrigin.y, targetZ)
            val targetY = findArenaGroundY(groundCandidate, spawnOrigin.y)
            val groundImpactLoc = Location(world, targetX, targetY, targetZ)

            val beamVec = groundImpactLoc.toVector().subtract(eye.toVector())
            if (beamVec.lengthSquared() > 1e-4) {
                val rotLoc = mannequin.location.clone().setDirection(beamVec)
                mannequin.setRotation(rotLoc.yaw, rotLoc.pitch.coerceIn(-65f, 65f))
                HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, world.players, rotLoc.yaw, rotLoc.pitch)
            }

            val totalDist = beamVec.length()
            val dir = beamVec.clone().normalize()
            val c1 = rainbowDustColors[laserStep % rainbowDustColors.size]
            val c2 = rainbowDustColors[(laserStep + 1) % rainbowDustColors.size]
            val rainbowLaserDust = Particle.DustTransition(c1, c2, 1.8f)

            val hitPlayersThisTick = HashSet<UUID>()
            var d = 0.0
            while (d <= totalDist) {
                val pt = eye.clone().add(dir.clone().multiply(d))
                world.spawnParticle(Particle.END_ROD, pt, 2, 0.06, 0.06, 0.06, 0.01)
                world.spawnParticle(Particle.DRAGON_BREATH, pt, 2, 0.1, 0.1, 0.1, 0.01, 1.0f)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.08, 0.08, 0.08, 0.0, rainbowLaserDust)

                pt.getNearbyPlayers(2.0).forEach { p ->
                    if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                        if (hitPlayersThisTick.add(p.uniqueId)) {
                            inflictUnavoidableHerobrineLaserDamage(p, 4.0)
                        }
                    }
                }
                d += 1.3
            }

            world.spawnParticle(Particle.FLASH, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 1, 0.15, 0.15, 0.15, 0.0, c1)
            world.spawnParticle(Particle.EXPLOSION, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 2, 0.4, 0.2, 0.4, 0.0)
            world.spawnParticle(Particle.BLOCK, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 20, 0.6, 0.3, 0.6, 0.15, groundDebrisData)
            world.spawnParticle(Particle.DRAGON_BREATH, groundImpactLoc, 15, 1.0, 0.3, 1.0, 0.04, 1.0f)
            world.spawnParticle(Particle.GUST, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 2, 0.5, 0.2, 0.5, 0.0)

            if (laserStep % 3 == 0) {
                world.playSound(groundImpactLoc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 2.8f, 1.2f)
                world.playSound(groundImpactLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.2f, 0.9f)
                world.playSound(groundImpactLoc, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 2.2f, 0.85f)
            }

            groundImpactLoc.getNearbyPlayers(2.4).forEach { p ->
                if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                    if (hitPlayersThisTick.add(p.uniqueId)) {
                        inflictUnavoidableHerobrineLaserDamage(p, 4.0)
                    }
                }
            }
        }
    }

    private fun inflictUnavoidableHerobrineLaserDamage(player: Player, damage: Double) {
        player.noDamageTicks = 0
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()
        val hpBefore = player.health
        player.damage(damage, unblockableSource)
        if (player.isValid && !player.isDead && (hpBefore - player.health) < damage * 0.9) {
            val newHp = (hpBefore - damage).coerceAtLeast(0.0)
            if (newHp <= 0.0) {
                player.health = 0.0
            } else {
                player.health = newHp
                player.playHurtAnimation(0f)
                player.world.playSound(player.location, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1.0f, 1.0f)
            }
        }
    }

    // ========================================================================
    // CONDENSED 5-WAVE LIGHTNING CIRCLES + CENTER STRIKE + 5-POINTED STAR
    // ========================================================================

    private fun scheduleWaveThenStarCycle(startDelay: Long, applyWither: Boolean) {
        val armorIgnoreSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        val waveRadii = listOf(1.5, 3.2, 5.0, 7.0, 9.5)
        waveRadii.forEachIndexed { waveIdx, radius ->
            val telegraphDelay = startDelay + waveIdx * 18L

            runLater(telegraphDelay) {
                if (isDeadOrRemoved) return@runLater
                val targetLoc = resolveCombatTarget()?.location ?: spawnOrigin
                val arenaLoc = targetLoc.clone().apply {
                    y = findArenaGroundY(this, targetLoc.y)
                }

                world.playSound(arenaLoc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 2.5f, 0.7f + waveIdx * 0.15f)
                val points = (radius * 8).toInt().coerceAtLeast(16)
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = arenaLoc.clone().add(cos(angle) * radius, 0.2, sin(angle) * radius)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 3, 0.1, 0.1, 0.1, 0.0, deepRedTransition)
                }
                if (waveIdx == 0) {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, arenaLoc.clone().add(0.0, 0.2, 0.0), 12, 0.4, 0.1, 0.4, 0.0, deepRedTransition)
                }

                runLater(10L) {
                    if (isDeadOrRemoved) return@runLater

                    if (waveIdx == 0) {
                        world.strikeLightningEffect(arenaLoc)
                        arenaLoc.getNearbyPlayers(2.0).forEach { p ->
                            if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                                p.damage(5.0, armorIgnoreSource)
                                if (applyWither) {
                                    p.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 200, 0, false, true, true))
                                }
                            }
                        }
                    }

                    val strikes = 6 + waveIdx * 4
                    for (i in 0 until strikes) {
                        val angle = 2.0 * Math.PI * i / strikes
                        val sLoc = arenaLoc.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
                        sLoc.y = findArenaGroundY(sLoc, arenaLoc.y)
                        world.strikeLightningEffect(sLoc)
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, sLoc.clone().add(0.0, 0.5, 0.0), 20, 0.6, 0.5, 0.6, 0.0, deepRedTransition)

                        sLoc.getNearbyPlayers(2.2).forEach { p ->
                            if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                                p.damage(5.0, armorIgnoreSource)
                                if (applyWither) {
                                    p.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 200, 0, false, true, true))
                                }
                            }
                        }
                    }
                }
            }
        }

        runLater(startDelay + 95L) {
            if (isDeadOrRemoved) return@runLater
            val targetLoc = resolveCombatTarget()?.location ?: spawnOrigin
            val starCenter = targetLoc.clone().apply {
                y = findArenaGroundY(this, targetLoc.y)
            }
            val starPoints = buildFivePointedStarLocations(starCenter, starRadius = 8.0)

            starPoints.forEach { pt ->
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt.clone().add(0.0, 0.2, 0.0), 15, 0.3, 0.1, 0.3, 0.0, deepRedTransition)
            }

            starPoints.forEachIndexed { idx, strikePt ->
                runLater(10L + idx * 7L) {
                    if (isDeadOrRemoved) return@runLater
                    val groundPt = strikePt.clone()
                    groundPt.y = findArenaGroundY(groundPt, starCenter.y)

                    world.strikeLightningEffect(groundPt)
                    world.playSound(groundPt, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 2.0f, 1.1f)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, groundPt.clone().add(0.0, 0.5, 0.0), 30, 1.0, 0.6, 1.0, 0.0, deepRedTransition)

                    groundPt.getNearbyPlayers(2.6).forEach { p ->
                        if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                            p.damage(5.0, armorIgnoreSource)
                            if (applyWither) {
                                p.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 200, 0, false, true, true))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildFivePointedStarLocations(center: Location, starRadius: Double): List<Location> {
        val vertices = (0 until 5).map { i ->
            val angle = Math.toRadians(-90.0 + i * 72.0)
            center.clone().add(cos(angle) * starRadius, 0.0, sin(angle) * starRadius)
        }
        val starOrder = listOf(0, 2, 4, 1, 3)
        val tenPoints = mutableListOf<Location>()

        for (step in 0 until 5) {
            val vStart = vertices[starOrder[step]]
            val vEnd = vertices[starOrder[(step + 1) % 5]]
            val midpoint = vStart.clone().add(vEnd).multiply(0.5)
            tenPoints.add(vStart)
            tenPoints.add(midpoint)
        }
        return tenPoints
    }

    // ========================================================================
    // 3-SECOND DELAYED SLAM TELEGRAPH & 3x3 SLAMS (PHASE 1, PHASE 2, PHASE 3)
    // ========================================================================

    private fun scheduleThreeSecondDelayedSlam(fallbackTarget: Player, isPhase3: Boolean) {
        var lockedSlamLoc = (pickRandomOrSolePlayer() ?: fallbackTarget).location.clone().apply {
            y = findArenaGroundY(this, this.y)
        }

        runLater(30L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            val chosen = pickRandomOrSolePlayer()
                ?: fallbackTarget.takeIf { it.world == world && it.location.distanceSquared(spawnOrigin) <= config.maxCombatDistance * config.maxCombatDistance }
            lockedSlamLoc = (chosen?.location?.clone() ?: spawnOrigin.clone()).apply {
                y = findArenaGroundY(this, this.y)
            }

            world.playSound(mannequin.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 3.0f, 0.8f)
            world.playSound(lockedSlamLoc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE, 2.5f, 0.6f)

            var telegraphTick = 0
            var telegraphTask: BukkitTask? = null
            telegraphTask = runTimer(1L, 2L) {
                telegraphTick += 2
                if (isDeadOrRemoved || telegraphTick >= 30) {
                    telegraphTask?.cancel()
                    return@runTimer
                }
                for (i in 0 until 24) {
                    val angle = 2.0 * Math.PI * i / 24
                    val ringPt = lockedSlamLoc.clone().add(cos(angle) * slamRadius, 0.15, sin(angle) * slamRadius)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, ringPt, 2, 0.05, 0.05, 0.05, 0.0, deepRedTransition)
                }
            }
        }

        runLater(60L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            if (isPhase3) {
                executePhase3FinalSlam(lockedSlamLoc)
            } else {
                executePhase2InstantLightningSlam(lockedSlamLoc)
            }
        }
    }

    private fun executePhase3FinalSlam(targetLocation: Location) {
        if (isDeadOrRemoved || !ensureLiveMannequin()) return
        isFloatingInvulnerable = false
        isSlamming = false
        usingRangedStance = false
        stanceSwitchTicks = 0

        val slamLoc = targetLocation.clone()
        slamLoc.y = findArenaGroundY(slamLoc, targetLocation.y)

        mannequin.setGravity(true)
        mannequin.teleport(slamLoc)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))

        world.strikeLightningEffect(slamLoc)
        terraformSlamArea(slamLoc, slamBlockHalfSize)

        world.playSound(slamLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3.0f, 0.75f)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, slamLoc, 1)

        val radiusSq = slamRadius * slamRadius
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        slamLoc.getNearbyLivingEntities(slamRadius, 3.5, slamRadius).forEach { victim ->
            if (victim == mannequin) return@forEach
            if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
            if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                return@forEach
            }

            val dx = victim.location.x - slamLoc.x
            val dz = victim.location.z - slamLoc.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                victim.damage(phase3SlamDamage, unblockableSource)

                val kb = victim.location.toVector().subtract(slamLoc.toVector()).setY(0.0)
                if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                victim.velocity = victim.velocity.add(kb.multiply(1.3).setY(0.55))
            }
        }
    }

    fun executePhase2InstantLightningSlam(impactLocation: Location) {
        if (isDeadOrRemoved || !ensureLiveMannequin()) return
        if (!isFloatingInvulnerable && !isSlamming) return

        isFloatingInvulnerable = false
        isSlamming = false
        usingRangedStance = false
        stanceSwitchTicks = 0

        val slamLoc = impactLocation.clone()
        slamLoc.y = findArenaGroundY(slamLoc, impactLocation.y)

        mannequin.setGravity(true)
        mannequin.teleport(slamLoc)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))

        world.strikeLightningEffect(slamLoc)
        terraformSlamArea(slamLoc, slamBlockHalfSize)

        world.playSound(slamLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3.0f, 0.75f)

        world.spawnParticle(Particle.GUST_EMITTER_LARGE, slamLoc.clone().add(0.0, 0.4, 0.0), 2, 0.8, 0.2, 0.8, 0.0)
        world.spawnParticle(
            Particle.BLOCK,
            slamLoc.clone().add(0.0, 0.5, 0.0),
            60, 1.2, 0.4, 1.2, 0.15,
            Material.REDSTONE_BLOCK.createBlockData()
        )

        for (wave in 1..2) {
            val r = (slamRadius / 2.0) * wave
            runLater((wave * 2).toLong()) {
                val points = 16
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = slamLoc.clone().add(cos(angle) * r, 0.2, sin(angle) * r)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 2, 0.1, 0.05, 0.1, 0.0, deepRedTransition)
                }
            }
        }

        val radiusSq = slamRadius * slamRadius
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        slamLoc.getNearbyLivingEntities(slamRadius, 3.5, slamRadius).forEach { victim ->
            if (victim == mannequin) return@forEach
            if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
            if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                return@forEach
            }

            val dx = victim.location.x - slamLoc.x
            val dz = victim.location.z - slamLoc.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                victim.damage(phase2SlamDamage, unblockableSource)

                val kb = victim.location.toVector().subtract(slamLoc.toVector()).setY(0.0)
                if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                victim.velocity = victim.velocity.add(kb.multiply(1.25).setY(0.55))
            }
        }
    }

    private fun performPhase1RedstoneSlam(target: Player) {
        isFloatingInvulnerable = false
        isSlamming = true
        currentTarget = target

        val slamTargetLoc = if (target.world == world &&
            target.location.distanceSquared(spawnOrigin) <= config.maxCombatDistance * config.maxCombatDistance
        ) {
            target.location.clone()
        } else {
            spawnOrigin.clone()
        }
        slamTargetLoc.y = findArenaGroundY(slamTargetLoc, slamTargetLoc.y)

        world.playSound(mannequin.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 3.0f, 0.7f)
        world.playSound(slamTargetLoc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE, 2.5f, 0.5f)

        var telegraphTick = 0
        var telegraphTask: BukkitTask? = null
        telegraphTask = runTimer(1L, 2L) {
            telegraphTick += 2
            if (isDeadOrRemoved || telegraphTick >= 30) {
                telegraphTask?.cancel()
                return@runTimer
            }
            for (i in 0 until 24) {
                val angle = 2.0 * Math.PI * i / 24
                val ringPt = slamTargetLoc.clone().add(cos(angle) * slamRadius, 0.15, sin(angle) * slamRadius)
                world.spawnParticle(Particle.DUST, ringPt, 2, 0.05, 0.05, 0.05, 0.0, redstoneDust)
            }
            val innerR = slamRadius * (1.0 - (telegraphTick / 30.0))
            for (i in 0 until 12) {
                val angle = 2.0 * Math.PI * i / 12
                val innerPt = slamTargetLoc.clone().add(cos(angle) * innerR, 0.15, sin(angle) * innerR)
                world.spawnParticle(Particle.DUST, innerPt, 1, 0.0, 0.0, 0.0, 0.0, redstoneDust)
            }
        }

        runLater(30L) {
            if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
            mannequin.setGravity(true)
            val plungeVec = slamTargetLoc.toVector().subtract(mannequin.location.toVector())
            mannequin.velocity = plungeVec.multiply(0.18).setY(-1.4)

            runLater(10L) {
                if (isDeadOrRemoved || !ensureLiveMannequin()) return@runLater
                mannequin.teleport(slamTargetLoc)
                isSlamming = false
                usingRangedStance = false
                stanceSwitchTicks = 0
                mannequin.equipment.setItemInMainHand(ItemStack(Material.IRON_SWORD))

                terraformSlamArea(slamTargetLoc, slamBlockHalfSize)

                world.playSound(slamTargetLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 3.5f, 0.75f)
                world.playSound(slamTargetLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.8f)

                world.spawnParticle(Particle.GUST_EMITTER_LARGE, slamTargetLoc.clone().add(0.0, 0.4, 0.0), 2, 0.8, 0.2, 0.8, 0.0)
                world.spawnParticle(Particle.DUST_PLUME, slamTargetLoc.clone().add(0.0, 0.2, 0.0), 45, 1.2, 0.3, 1.2, 0.06)
                world.spawnParticle(
                    Particle.BLOCK,
                    slamTargetLoc.clone().add(0.0, 0.5, 0.0),
                    60, 1.2, 0.4, 1.2, 0.15,
                    Material.REDSTONE_BLOCK.createBlockData()
                )

                for (wave in 1..2) {
                    val r = (slamRadius / 2.0) * wave
                    runLater((wave * 2).toLong()) {
                        val points = 16
                        for (i in 0 until points) {
                            val angle = 2.0 * Math.PI * i / points
                            val pt = slamTargetLoc.clone().add(cos(angle) * r, 0.2, sin(angle) * r)
                            world.spawnParticle(Particle.DUST, pt, 2, 0.1, 0.05, 0.1, 0.0, redstoneDust)
                        }
                    }
                }

                val radiusSq = slamRadius * slamRadius
                val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
                    .withCausingEntity(mannequin)
                    .withDirectEntity(mannequin)
                    .build()

                slamTargetLoc.getNearbyLivingEntities(slamRadius, 3.5, slamRadius).forEach { victim ->
                    if (victim == mannequin) return@forEach
                    if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
                    if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                        return@forEach
                    }

                    val dx = victim.location.x - slamTargetLoc.x
                    val dz = victim.location.z - slamTargetLoc.z
                    if ((dx * dx + dz * dz) <= radiusSq) {
                        victim.damage(phase1SlamDamage, unblockableSource)

                        val kb = victim.location.toVector().subtract(slamTargetLoc.toVector()).setY(0.0)
                        if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                        victim.velocity = victim.velocity.add(kb.multiply(1.1).setY(0.5))
                    }
                }
            }
        }
    }

    private fun terraformSlamArea(center: Location, halfSize: Int) {
        val centerBlock = center.block

        for (x in -halfSize..halfSize) {
            for (z in -halfSize..halfSize) {
                for (y in -2..1) {
                    val ground = centerBlock.getRelative(x, y, z)
                    if (ground.type == Material.GRASS_BLOCK ||
                        ground.type == Material.MYCELIUM ||
                        ground.type == Material.PODZOL
                    ) {
                        ground.type = Material.DIRT
                    }
                }

                for (y in -1..4) {
                    val b = centerBlock.getRelative(x, y, z)
                    if (isBreakableFoliage(b)) {
                        b.breakNaturally()
                    }
                }
            }
        }
    }

    private fun isBreakableFoliage(block: Block): Boolean {
        val mat = block.type
        if (mat.isAir) return false
        return Tag.LEAVES.isTagged(mat) ||
                Tag.FLOWERS.isTagged(mat) ||
                Tag.REPLACEABLE_BY_TREES.isTagged(mat) ||
                Tag.CROPS.isTagged(mat) ||
                mat == Material.BAMBOO ||
                mat == Material.SUGAR_CANE ||
                mat == Material.DEAD_BUSH ||
                mat == Material.VINE ||
                mat == Material.MOSS_CARPET ||
                mat == Material.SWEET_BERRY_BUSH
    }

    // ========================================================================
    // ENDER-DRAGON STYLE DEATH ANIMATION + 32 ENCHANTED GOLDEN APPLES + TUNNELER
    // ========================================================================

    fun triggerDeathAnimation(killer: Player?) {
        if (isDyingAnimation || isDeadOrRemoved) return
        isDyingAnimation = true
        isFloatingInvulnerable = true

        ensureLiveMannequin()
        tasks.forEach { it.cancel() }
        tasks.clear()
        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        mannequin.health = 1.0
        mannequin.setGravity(false)
        mannequin.setInvulnerable(true)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

        val baseGroundLoc = mannequin.location.clone()
        baseGroundLoc.y = findArenaGroundY(baseGroundLoc, spawnOrigin.y)
        world.playSound(baseGroundLoc, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 5.0f, 0.85f)

        var lastAscendLoc = mannequin.location.clone()
        var tick = 0
        var deathTask: BukkitTask? = null
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick++
            if (!ensureLiveMannequin() || tick >= 120) {
                deathTask?.cancel()
                if (mannequin.isValid) {
                    lastAscendLoc = mannequin.location.clone()
                }
                completeHerobrineDeath(lastAscendLoc, killer)
                return@Runnable
            }

            val nextLoc = mannequin.location.add(0.0, 0.06, 0.0)
            nextLoc.yaw += 14f
            nextLoc.pitch = -35f
            mannequin.teleport(nextLoc)
            lastAscendLoc = nextLoc.clone()
            HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, world.players, nextLoc.yaw, nextLoc.pitch)

            if (tick % 6 == 0) {
                val lx = baseGroundLoc.x + Random.nextDouble(-14.0, 14.0)
                val lz = baseGroundLoc.z + Random.nextDouble(-14.0, 14.0)
                val strikeCandidate = Location(world, lx, baseGroundLoc.y, lz)
                strikeCandidate.y = findArenaGroundY(strikeCandidate, baseGroundLoc.y)
                world.strikeLightningEffect(strikeCandidate)
            }

            if (tick % 2 == 0) {
                tickPhaseArenaAura(baseGroundLoc)
            }

            if (tick == 85) {
                mannequin.isGlowing = true
                herobrineTeam?.color(NamedTextColor.WHITE)
                mannequin.customName("<bold><white>Herobrine</white></bold>".mm())
                plugin.bossBarManager.updateBarAppearance(
                    mannequin,
                    titleMiniMessage = "<bold><white>Herobrine</white></bold>",
                    color = BossBar.Color.WHITE
                )
            }

            if (tick >= 85) {
                world.spawnParticle(Particle.END_ROD, mannequin.location.add(0.0, 1.0, 0.0), 15, 0.6, 0.8, 0.6, 0.15)
                world.spawnParticle(Particle.FLASH, mannequin.location.add(0.0, 1.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
            }
        }, 1L, 1L)
    }

    private fun completeHerobrineDeath(finalAscensionLoc: Location, killer: Player?) {
        try {
            world.playSound(finalAscensionLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 0.7f)
            world.playSound(finalAscensionLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2.0f, 1.0f)
            world.spawnParticle(Particle.EXPLOSION_EMITTER, finalAscensionLoc, 2)
            world.spawnParticle(Particle.FLASH, finalAscensionLoc, 4, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
            world.spawnParticle(Particle.END_ROD, finalAscensionLoc, 200, 2.0, 2.0, 2.0, 0.35)

            // Guaranteed drops right at Herobrine's actual death location floor
            val dropFloorY = findArenaGroundY(finalAscensionLoc, spawnOrigin.y)
            val dropLoc = Location(world, finalAscensionLoc.x, dropFloorY + 0.8, finalAscensionLoc.z)

            val rewards = listOf(
                ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 32),
                plugin.herobrineManager.createHerosTunneler()
            )
            for (stack in rewards) {
                world.dropItemNaturally(dropLoc, stack) { item ->
                    item.isGlowing = true
                    item.setInvulnerable(true)
                    item.isUnlimitedLifetime = true
                    item.velocity = Vector(0.0, 0.35, 0.0)
                }
            }

            ExperienceUtil.spawnLevelOrbs(dropLoc, levels = config.expLevelsDropped, orbCount = 30)

            val killerName = killer?.name ?: "A player"
            Bukkit.broadcast("<white>$killerName has slain Herobrine.</white>".mm())
            DiscordSrvHook.sendHerobrineSlainByPlayer(killerName, killer?.uniqueId)

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                Bukkit.broadcast("<yellow>Herobrine has left the game.</yellow>".mm())
                DiscordSrvHook.sendLeaveMessage()
                HerobrinePacketController.broadcastTablistRemove(tablistUuid)
            }, 20L)
        } finally {
            forceCleanup(killEntity = true, removeTablistImmediately = false)
            plugin.herobrineManager.activeFights.remove(mannequinId)
        }
    }

    // ========================================================================
    // DAMAGE, ARENA FLOOR FINDER & PHASE CHECKS
    // ========================================================================

    fun onDamaged(damageAmount: Double, attacker: Player?) {
        if (isDyingAnimation || isDeadOrRemoved) return
        if (attacker != null) {
            currentTarget = attacker
        }

        if (currentPhase == 1 && mannequin.health <= config.phase2Threshold) {
            enterPhase2()
            return
        }

        if (currentPhase == 2 && mannequin.health <= config.phase3Threshold) {
            enterPhase3()
            return
        }

        if (!isFloatingInvulnerable && !isSlamming) {
            damageSinceLastSteveWave += damageAmount
            if (damageSinceLastSteveWave >= 100.0) {
                damageSinceLastSteveWave = 0.0
                enterFloatingInvulnerableState(spawnSteves = true)
            }
        }
    }

    private fun findArenaGroundY(loc: Location, referenceY: Double = spawnOrigin.y): Double {
        val baseBlock = Location(world, loc.x, referenceY, loc.z).block
        for (dy in 5 downTo -12) {
            val ground = baseBlock.getRelative(0, dy - 1, 0)
            val feet = baseBlock.getRelative(0, dy, 0)
            val head = baseBlock.getRelative(0, dy + 1, 0)
            if (ground.type.isSolid && !ground.isLiquid && feet.isPassable && head.isPassable) {
                return feet.y.toDouble()
            }
        }
        return referenceY
    }

    private fun pickRandomOrSolePlayer(): Player? {
        val maxDistSq = config.maxCombatDistance * config.maxCombatDistance
        val players = mannequin.location.getNearbyPlayers(config.followRange)
            .filter {
                !it.isDead &&
                        (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) &&
                        it.location.distanceSquared(spawnOrigin) <= maxDistSq
            }
        return players.randomOrNull()
    }

    private fun resolveSummonerOrNearest(): Player? {
        val maxDistSq = config.maxCombatDistance * config.maxCombatDistance
        summonerId?.let { id ->
            val p = Bukkit.getPlayer(id)
            if (p != null && p.isOnline && !p.isDead && p.world == world &&
                p.location.distanceSquared(spawnOrigin) <= maxDistSq
            ) {
                return p
            }
        }
        return pickRandomOrSolePlayer()
    }

    private fun resolveCombatTarget(): Player? {
        val maxDistSq = config.maxCombatDistance * config.maxCombatDistance
        val cur = currentTarget
        if (cur != null && cur.isOnline && !cur.isDead && cur.world == world &&
            (cur.gameMode == GameMode.SURVIVAL || cur.gameMode == GameMode.ADVENTURE) &&
            cur.location.distanceSquared(spawnOrigin) <= maxDistSq
        ) {
            return cur
        }
        val next = pickRandomOrSolePlayer()
        currentTarget = next
        return next
    }

    private fun faceLocation(targetLocation: Location) {
        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        val dir = targetLocation.toVector().subtract(eye.toVector())
        if (dir.lengthSquared() > 1e-4) {
            val rotLoc = mannequin.location.setDirection(dir)
            val yaw = rotLoc.yaw
            val pitch = rotLoc.pitch.coerceIn(-45f, 45f)
            mannequin.setRotation(yaw, pitch)
            HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, world.players, yaw, pitch)
        }
    }

    fun onKilledByPlayer(killer: Player?) {
        triggerDeathAnimation(killer)
    }

    fun forceCleanup(killEntity: Boolean, removeTablistImmediately: Boolean = true) {
        isDeadOrRemoved = true
        tasks.forEach { it.cancel() }
        tasks.clear()

        world.setStorm(false)
        world.isThundering = false
        if (wasAdvanceTime) {
            world.setGameRule(GameRules.ADVANCE_TIME, true)
        }
        world.fullTime = savedFullTime

        herobrineTeam?.unregister()
        herobrineTeam = null
        steveTeam?.unregister()
        steveTeam = null

        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        world.entities.forEach { ent ->
            if (ent.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
                ent.remove()
            }
        }

        if (::mannequinId.isInitialized) {
            plugin.bossBarManager.unregisterById(mannequinId)
            if (killEntity) {
                if (::mannequin.isInitialized && mannequin.isValid) {
                    mannequin.remove()
                } else {
                    (Bukkit.getEntity(mannequinId) as? Mannequin)?.remove()
                }
            }
        }
        if (removeTablistImmediately) {
            HerobrinePacketController.broadcastTablistRemove(tablistUuid)
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
}