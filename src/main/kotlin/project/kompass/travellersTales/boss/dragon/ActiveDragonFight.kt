package project.kompass.travellersTales.boss.dragon

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarFlag
import org.bukkit.boss.BarStyle
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BossKillTracker
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ActiveDragonFight(
    private val plugin: TravellersTales,
    var dragon: EnderDragon,
    var config: DragonConfig
) {
    private enum class CycleAttack {
        SLAM,
        MULTI_DRAGON_SLAM,
        FIREBALL,
        LASER_360,
        PERCH,
        PERCH_AND_DOUBLE_SLAM,
        PERCH_AND_DOUBLE_MULTI_SLAM
    }

    val dragonId: UUID = dragon.uniqueId
    val world: World
        get() = dragon.world

    private val tasks = mutableListOf<BukkitTask>()
    private val portalCenter: Location = Location(dragon.world, 0.5, 80.0, 0.5)
    private val endStoneData = Material.END_STONE.createBlockData()

    var currentHealth: Double = config.maxHealth
        private set

    var currentPhase: DragonPhaseType = DragonPhaseType.VOID
        private set

    private var dragonTeam: Team? = null
    private var inAttackSequence: Boolean = false
    private var allCrystalsDestroyed: Boolean = false
    private var cycleStepIndex: Int = 0
    private var lastCycleStepMillis: Long = 0L

    var inTotemClimax: Boolean = false
        private set
    var isTotemAscending: Boolean = false
        private set
    private var totemPopped: Boolean = false
    var hasDied: Boolean = false
        private set

    private var totemHoverLocation: Location? = null
    private var totemRepulseTimerTicks: Int = 0

    val spikePillars = listOf(
        Vector(42.0, 110.0, 0.0),
        Vector(33.0, 110.0, -25.0),
        Vector(12.0, 110.0, -40.0),
        Vector(-13.0, 110.0, -40.0),
        Vector(-34.0, 110.0, -25.0),
        Vector(-42.0, 110.0, -1.0),
        Vector(-34.0, 110.0, 24.0),
        Vector(-13.0, 110.0, 39.0),
        Vector(12.0, 110.0, 39.0),
        Vector(33.0, 110.0, 24.0)
    )

    fun start(preserveCurrentHealth: Boolean = false) {
        plugin.bossBarManager.unregisterById(dragonId)
        allCrystalsDestroyed = world.getEntitiesByClass(EnderCrystal::class.java).none { it.isValid && !it.isDead }

        setupTeam()
        configureDragonEntity(preserveCurrentHealth)
        syncNativeBossBar()
        setupCagedTowerPhantoms()
        scheduleCoreLoops()
    }

    fun onAllCrystalsDestroyed() {
        if (!allCrystalsDestroyed) {
            allCrystalsDestroyed = true
            cycleStepIndex = 0
        }
    }

    fun rebindDragon(loadedDragon: EnderDragon) {
        val wasUnloaded = !this.dragon.isValid
        this.dragon = loadedDragon
        plugin.bossBarManager.unregisterById(loadedDragon.uniqueId)
        dragonTeam?.addEntity(loadedDragon)
        syncNativeBossBar()

        if (wasUnloaded && !hasDied && !inTotemClimax) {
            inAttackSequence = false
            loadedDragon.phase = EnderDragon.Phase.CIRCLING
        }
    }

    private fun ensureLiveDragon(): Boolean {
        if (dragon.isValid) return true
        val live = Bukkit.getEntity(dragonId) as? EnderDragon
        if (live != null && live.isValid) {
            rebindDragon(live)
            return true
        }
        return false
    }

    private fun setupTeam() {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        scoreboard.getTeam("EnderDragon")?.unregister()
        dragonTeam = scoreboard.registerNewTeam("EnderDragon").apply {
            setAllowFriendlyFire(false)
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            color(currentPhase.teamColor)
            addEntity(dragon)
        }
    }

    private fun configureDragonEntity(preserveCurrentHealth: Boolean) {
        val savedHealth = if (preserveCurrentHealth) {
            dragon.persistentDataContainer.get(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE)
                ?: config.maxHealth
        } else {
            config.maxHealth
        }

        currentHealth = savedHealth.coerceIn(1.0, config.maxHealth)
        dragon.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "ender_dragon")
        dragon.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, currentHealth)

        val maxVanillaCap = 1024.0
        dragon.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxVanillaCap
        dragon.health = ((currentHealth / config.maxHealth) * maxVanillaCap).coerceIn(1.0, maxVanillaCap)
        dragon.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange
        dragon.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 300.0

        if (!totemPopped) {
            dragon.equipment.setItemInMainHand(ItemStack(Material.TOTEM_OF_UNDYING))
            dragon.equipment.setDropChance(EquipmentSlot.HAND, 0f)
        }

        val resolvedPhase = when {
            currentHealth > config.phase2Threshold -> DragonPhaseType.VOID
            currentHealth > config.phase3Threshold -> DragonPhaseType.CRIMSON
            currentHealth > config.phase4Threshold -> DragonPhaseType.ICHOR
            else -> DragonPhaseType.ENDER
        }
        updatePhase(resolvedPhase)
    }

    fun syncNativeBossBar() {
        val title = config.phaseTitles[currentPhase] ?: currentPhase.defaultTitle
        if (dragon.isValid) {
            dragon.customName(title.mm())
        }

        val nativeBar = world.enderDragonBattle?.bossBar ?: return
        nativeBar.isVisible = !hasDied
        nativeBar.color = when (currentPhase.barColor) {
            BossBar.Color.PURPLE -> BarColor.PURPLE
            BossBar.Color.RED -> BarColor.RED
            BossBar.Color.PINK -> BarColor.PINK
            BossBar.Color.BLUE -> BarColor.BLUE
            BossBar.Color.GREEN -> BarColor.GREEN
            BossBar.Color.YELLOW -> BarColor.YELLOW
            BossBar.Color.WHITE -> BarColor.WHITE
        }
        nativeBar.style = when (config.barStyle) {
            BossBar.Overlay.PROGRESS -> BarStyle.SOLID
            BossBar.Overlay.NOTCHED_6 -> BarStyle.SEGMENTED_6
            BossBar.Overlay.NOTCHED_10 -> BarStyle.SEGMENTED_10
            BossBar.Overlay.NOTCHED_12 -> BarStyle.SEGMENTED_12
            BossBar.Overlay.NOTCHED_20 -> BarStyle.SEGMENTED_20
        }
        if (config.darkenSky) {
            nativeBar.addFlag(BarFlag.DARKEN_SKY)
        } else {
            nativeBar.removeFlag(BarFlag.DARKEN_SKY)
        }
        nativeBar.progress = (currentHealth / config.maxHealth).coerceIn(0.0, 1.0)
    }

    fun applyDamage(amount: Double, attacker: Player? = null): Boolean {
        if (hasDied || isTotemAscending) return false

        // In Phase 6 (Totem Climax), a single melee hit from a player finishes the dragon!
        if (inTotemClimax && totemPopped) {
            currentHealth = 0.0
            dragon.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, 0.0)
            onDeath(attacker)
            return true
        }

        currentHealth = (currentHealth - amount).coerceAtLeast(0.0)
        dragon.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, currentHealth)

        if (currentHealth <= 0.0 && !totemPopped) {
            popTotemAndAscendCenter()
            return false
        }

        val visualHealth = ((currentHealth / config.maxHealth) * 1024.0).coerceIn(1.0, 1024.0)
        if (dragon.isValid) {
            dragon.health = visualHealth
        }
        syncNativeBossBar()
        checkPhaseTransitions()

        if (currentHealth <= 0.0 && totemPopped) {
            onDeath(attacker)
            return true
        }

        return false
    }

    private fun checkPhaseTransitions() {
        if (inTotemClimax) return
        when {
            currentHealth > config.phase2Threshold -> {
                if (currentPhase != DragonPhaseType.VOID) updatePhase(DragonPhaseType.VOID)
            }
            currentHealth in (config.phase3Threshold + 0.1)..config.phase2Threshold -> {
                if (currentPhase != DragonPhaseType.CRIMSON) updatePhase(DragonPhaseType.CRIMSON)
            }
            currentHealth in (config.phase4Threshold + 0.1)..config.phase3Threshold -> {
                if (currentPhase != DragonPhaseType.ICHOR) updatePhase(DragonPhaseType.ICHOR)
            }
            currentHealth <= config.phase4Threshold -> {
                if (currentPhase != DragonPhaseType.ENDER) updatePhase(DragonPhaseType.ENDER)
            }
        }
    }

    private fun updatePhase(phase: DragonPhaseType) {
        if (currentPhase != phase) {
            cycleStepIndex = 0
        }
        currentPhase = phase
        dragon.isGlowing = true
        dragonTeam?.color(phase.teamColor)

        world.spawnParticle(
            Particle.DUST_COLOR_TRANSITION,
            dragon.location.add(0.0, 2.0, 0.0),
            100, 2.0, 2.0, 2.0, 0.2,
            phase.dustTransition
        )
        world.spawnParticle(phase.accentParticle, dragon.location.add(0.0, 2.0, 0.0), 50, 1.5, 1.5, 1.5, 0.1)
        syncNativeBossBar()
    }

    fun onDragonAttackHit(target: Player, damageDealt: Double) {
        when (currentPhase) {
            DragonPhaseType.VOID -> {
                target.addPotionEffect(
                    PotionEffect(PotionEffectType.LEVITATION, config.levitationTicks.coerceAtMost(60), 0, false, true, true)
                )
            }
            DragonPhaseType.CRIMSON -> {
                target.addPotionEffect(
                    PotionEffect(PotionEffectType.WITHER, config.witherTicks.coerceAtMost(140), 0, false, true, true)
                )
            }
            DragonPhaseType.ICHOR -> {
                val healAmount = (damageDealt * config.lifestealMultiplier).coerceAtMost(20.0)
                currentHealth = (currentHealth + healAmount).coerceAtMost(config.maxHealth)
                dragon.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, currentHealth)
                val visualHealth = ((currentHealth / config.maxHealth) * 1024.0).coerceIn(1.0, 1024.0)
                dragon.health = visualHealth
                syncNativeBossBar()
                world.spawnParticle(Particle.HEART, dragon.location.add(0.0, 2.0, 0.0), 10, 1.5, 1.5, 1.5, 0.1)
                world.playSound(dragon.location, Sound.ENTITY_PLAYER_BURP, SoundCategory.HOSTILE, 1.5f, 0.5f)
            }
            DragonPhaseType.ENDER -> {}
        }
    }

    // ========================================================================
    // 6-PHASE DETERMINISTIC ATTACK CYCLE
    // ========================================================================

    private fun getCurrentPhaseCycle(): List<CycleAttack> {
        return when (currentPhase) {
            DragonPhaseType.VOID -> {
                if (!allCrystalsDestroyed) {
                    // Phase 1 (Crystals Alive): Dragon Slam > Fireball (Repeat)
                    listOf(CycleAttack.SLAM, CycleAttack.FIREBALL)
                } else {
                    // Phase 2 (Crystals Destroyed): Dragon Slam > Fireball > Dragon Slam > Fireball > Perch (Repeat)
                    listOf(
                        CycleAttack.SLAM,
                        CycleAttack.FIREBALL,
                        CycleAttack.SLAM,
                        CycleAttack.FIREBALL,
                        CycleAttack.PERCH
                    )
                }
            }
            DragonPhaseType.CRIMSON -> {
                // Phase 3 (Crimson): Dragon Slam > Fireball > Dragon Slam > 360 Attack > Dragon Slam > Perch (Repeat)
                listOf(
                    CycleAttack.SLAM,
                    CycleAttack.FIREBALL,
                    CycleAttack.SLAM,
                    CycleAttack.LASER_360,
                    CycleAttack.SLAM,
                    CycleAttack.PERCH
                )
            }
            DragonPhaseType.ICHOR -> {
                // Phase 4 (Ichor): Slam > Fireball > Slam > 360 Attack > Slam > Perch > Perched Double Slam (Repeat)
                listOf(
                    CycleAttack.SLAM,
                    CycleAttack.FIREBALL,
                    CycleAttack.SLAM,
                    CycleAttack.LASER_360,
                    CycleAttack.SLAM,
                    CycleAttack.PERCH_AND_DOUBLE_SLAM
                )
            }
            DragonPhaseType.ENDER -> {
                // Phase 5 (Ender): Slam > Fireball > Multi-Dragon Slam > 360 Attack > Slam > Perch > Perched Double Multi-Slam (Repeat)
                listOf(
                    CycleAttack.SLAM,
                    CycleAttack.FIREBALL,
                    CycleAttack.MULTI_DRAGON_SLAM,
                    CycleAttack.LASER_360,
                    CycleAttack.SLAM,
                    CycleAttack.PERCH_AND_DOUBLE_MULTI_SLAM
                )
            }
        }
    }

    private fun executeNextCycleAttack() {
        val cycle = getCurrentPhaseCycle()
        if (cycle.isEmpty()) return
        val step = cycle[cycleStepIndex % cycle.size]
        cycleStepIndex = (cycleStepIndex + 1) % cycle.size
        lastCycleStepMillis = System.currentTimeMillis()

        when (step) {
            CycleAttack.SLAM -> triggerDiveSequence(spawnMultipleDragons = false)
            CycleAttack.MULTI_DRAGON_SLAM -> triggerDiveSequence(spawnMultipleDragons = true)
            CycleAttack.FIREBALL -> triggerUltraFireballSequence()
            CycleAttack.LASER_360 -> triggerLaserSequence()
            CycleAttack.PERCH -> triggerControlledPerchSequence(doubleSlamCount = 0, includeMultipleDragons = false)
            CycleAttack.PERCH_AND_DOUBLE_SLAM -> triggerControlledPerchSequence(doubleSlamCount = 2, includeMultipleDragons = false)
            CycleAttack.PERCH_AND_DOUBLE_MULTI_SLAM -> triggerControlledPerchSequence(doubleSlamCount = 2, includeMultipleDragons = true)
        }
    }

    // ========================================================================
    // CORE LOOPS
    // ========================================================================

    private fun scheduleCoreLoops() {
        runTimer(1L, 1L) {
            if (hasDied) return@runTimer
            if (!ensureLiveDragon() || dragon.isDead) return@runTimer

            if (config.dragonParticleTrail) {
                val tailLoc = dragon.location.add(0.0, 2.0, 0.0)
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    tailLoc,
                    2, 0.7, 0.5, 0.7, 0.0,
                    currentPhase.dustTransition
                )
                world.spawnParticle(Particle.DRAGON_BREATH, tailLoc, 2, 0.5, 0.4, 0.5, 0.01, 1.0f)
                world.spawnParticle(currentPhase.accentParticle, tailLoc, 1, 0.4, 0.4, 0.4, 0.01)
            }

            if (inTotemClimax && !isTotemAscending) {
                totemHoverLocation?.let { hoverLoc ->
                    hoverLoc.yaw = (hoverLoc.yaw + 2.5f) % 360f
                    dragon.teleport(hoverLoc)
                    dragon.velocity = Vector(0.0, 0.0, 0.0)
                    dragon.phase = EnderDragon.Phase.HOVER
                }
            }
        }

        runTimer(20L, 20L) {
            if (hasDied) return@runTimer
            if (!ensureLiveDragon() || dragon.isDead) return@runTimer

            syncNativeBossBar()

            if (!allCrystalsDestroyed) {
                val remainingCrystals = world.getEntitiesByClass(EnderCrystal::class.java).count { it.isValid && !it.isDead }
                if (remainingCrystals == 0) {
                    onAllCrystalsDestroyed()
                }
            }

            if (dragon.location.distanceSquared(portalCenter) > config.maxCombatDistanceSquared) {
                dragon.teleport(portalCenter)
            }

            // Prevent vanilla AI from independently perching or strafing outside our deterministic cycle
            if (!inAttackSequence && !inTotemClimax) {
                val p = dragon.phase
                if (p == EnderDragon.Phase.STRAFING ||
                    p == EnderDragon.Phase.FLY_TO_PORTAL ||
                    p == EnderDragon.Phase.LAND_ON_PORTAL
                ) {
                    dragon.phase = EnderDragon.Phase.CIRCLING
                }
            }

            if (config.crystalBeaconParticles) {
                world.getEntitiesByClass(EnderCrystal::class.java).forEach { crystal ->
                    val cLoc = crystal.location.add(0.0, 1.0, 0.0)
                    for (y in 0..24 step 2) {
                        world.spawnParticle(Particle.PORTAL, cLoc.clone().add(0.0, y.toDouble(), 0.0), 4, 0.15, 0.5, 0.15, 0.05)
                    }
                    world.spawnParticle(Particle.END_ROD, crystal.location.add(0.0, 15.0, 0.0), 2, 0.0, 10.0, 0.0, 0.0, null, true)
                }
            }

            if (inTotemClimax || inAttackSequence) return@runTimer

            val now = System.currentTimeMillis()
            if ((now - lastCycleStepMillis) >= 5500L) {
                if (resolveTargetPlayer() != null) {
                    executeNextCycleAttack()
                }
            }
        }
    }

    // ========================================================================
    // ENDERMAN INSTA-KILL HELPER
    // ========================================================================

    private fun instaKillEndermenAt(location: Location, radius: Double) {
        location.getNearbyEntitiesByType(Enderman::class.java, radius).forEach { enderman ->
            if (enderman.isValid && !enderman.isDead) {
                world.spawnParticle(Particle.PORTAL, enderman.location.add(0.0, 1.5, 0.0), 25, 0.4, 0.8, 0.4, 0.2)
                world.playSound(enderman.location, Sound.ENTITY_ENDERMAN_DEATH, SoundCategory.HOSTILE, 1.2f, 0.8f)
                enderman.health = 0.0
            }
        }
    }

    // ========================================================================
    // SKILL 1: MOVING FIREBALL BARRAGE (INSTA-KILLS ENDERMEN + FIREWORK FLASH)
    // ========================================================================

    fun triggerUltraFireballSequence() {
        if (!ensureLiveDragon() || inAttackSequence || inTotemClimax || hasDied) return
        inAttackSequence = true
        lastCycleStepMillis = System.currentTimeMillis()

        val shotCount = when (currentPhase) {
            DragonPhaseType.VOID -> Random.nextInt(2, 4)
            DragonPhaseType.CRIMSON -> Random.nextInt(3, 5)
            DragonPhaseType.ICHOR, DragonPhaseType.ENDER -> Random.nextInt(5, 7)
        }

        val headLoc = dragon.location.clone().add(0.0, 1.5, 0.0)
        world.playSound(headLoc, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.HOSTILE, 6f, 0.5f)
        world.playSound(headLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 7f, 0.8f)
        world.spawnParticle(Particle.FLASH, headLoc, 2, 0.3, 0.3, 0.3, 0.0, currentPhase.primaryColor)

        for (i in 0 until shotCount) {
            val delay = 10L + (i * 12L)
            val spreadOffset = if (i == 0) 0.02 else 0.10
            runLater(delay) {
                if (ensureLiveDragon() && !hasDied && !inTotemClimax) {
                    launchUltraFireball(spreadOffset)
                }
            }
        }

        runLater(15L + shotCount * 12L) {
            inAttackSequence = false
            lastCycleStepMillis = System.currentTimeMillis()
        }
    }

    private fun launchUltraFireball(spread: Double) {
        val target = resolveTargetPlayer() ?: return
        val origin = dragon.location.clone().add(0.0, 1.5, 0.0)
        val dir = target.location.clone().add(0.0, 0.8, 0.0).toVector().subtract(origin.toVector())
        if (dir.lengthSquared() < 1e-4) return
        dir.normalize()

        if (spread > 0) {
            dir.add(
                Vector(
                    (Math.random() - 0.5) * spread,
                    (Math.random() - 0.5) * spread,
                    (Math.random() - 0.5) * spread
                )
            ).normalize()
        }

        world.playSound(origin, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 5f, 1f)
        world.spawnParticle(Particle.FLASH, origin, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)

        val fireballPhase = currentPhase
        var step = 0
        val currentLoc = origin.clone()
        var speed = 0.75

        var task: BukkitTask? = null
        task = runTimer(1L, 1L) {
            step++
            speed = (speed + 0.09).coerceAtMost(2.3)
            val stepVec = dir.clone().multiply(speed)

            val subSteps = 3
            for (s in 1..subSteps) {
                val interp = currentLoc.clone().add(stepVec.clone().multiply(s.toDouble() / subSteps))
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    interp,
                    3, 0.15, 0.15, 0.15, 0.0,
                    fireballPhase.dustTransition
                )
                world.spawnParticle(Particle.DRAGON_BREATH, interp, 2, 0.1, 0.1, 0.1, 0.01, 1.0f)
                world.spawnParticle(fireballPhase.accentParticle, interp, 1, 0.1, 0.1, 0.1, 0.01)
            }

            currentLoc.add(stepVec)
            instaKillEndermenAt(currentLoc, 2.5)

            val hitBlock = !currentLoc.block.isPassable
            val hitPlayer = currentLoc.getNearbyPlayers(1.8).any { isValidPlayer(it) }

            if (hitBlock || hitPlayer || step > 85) {
                task?.cancel()
                detonateUltraFireball(currentLoc, fireballPhase)
            }
        }
    }

    private fun detonateUltraFireball(loc: Location, phase: DragonPhaseType) {
        val groundLoc = loc.clone()
        world.playSound(groundLoc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 4f, 1f)
        world.playSound(groundLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 1.2f)

        world.spawnParticle(Particle.FLASH, groundLoc.clone().add(0.0, 0.5, 0.0), 2, 0.2, 0.2, 0.2, 0.0, phase.primaryColor)
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, groundLoc, 70, 1.2, 0.6, 1.2, 0.2, phase.dustTransition)
        world.spawnParticle(Particle.DRAGON_BREATH, groundLoc, 70, 1.8, 0.4, 1.8, 0.05, 1.0f)
        world.spawnParticle(phase.accentParticle, groundLoc, 35, 1.0, 0.5, 1.0, 0.1)

        instaKillEndermenAt(groundLoc, 4.5)

        world.spawn(groundLoc, AreaEffectCloud::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { cloud ->
            cloud.ownerUniqueId = dragonId
            cloud.setParticle(Particle.DRAGON_BREATH, 1.0f)
            cloud.duration = 320
            cloud.radius = 4.0f
            cloud.radiusPerTick = -0.004f
            cloud.reapplicationDelay = 10
            cloud.addCustomEffect(PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), true)
        }

        val attackSource = DamageSource.builder(DamageType.DRAGON_BREATH)
            .withCausingEntity(dragon)
            .withDirectEntity(dragon)
            .build()

        groundLoc.getNearbyPlayers(3.8).filter { isValidPlayer(it) }.forEach { p ->
            p.damage(10.0, attackSource)
            onDragonAttackHit(p, 10.0)
        }

        shatterEnderPearls(groundLoc, 3.8)
    }

    // ========================================================================
    // SKILL 2: FLY-UP DIVE SLAM & MULTIPLE DRAGON SLAM (WITH DECOY SHOCKWAVES)
    // ========================================================================

    fun triggerDiveSequence(spawnMultipleDragons: Boolean = false) {
        if (!ensureLiveDragon() || inAttackSequence || inTotemClimax || hasDied) return
        val target = resolveTargetPlayer() ?: run {
            inAttackSequence = false
            return
        }

        inAttackSequence = true
        lastCycleStepMillis = System.currentTimeMillis()

        val offsetAngle = Random.nextDouble(0.0, 2.0 * Math.PI)
        val offsetDist = Random.nextDouble(4.5, 7.0)
        val rawGroundX = (target.location.x + cos(offsetAngle) * offsetDist).coerceIn(-36.0, 36.0)
        val rawGroundZ = (target.location.z + sin(offsetAngle) * offsetDist).coerceIn(-36.0, 36.0)
        val groundY = (world.getHighestBlockYAt(rawGroundX.toInt(), rawGroundZ.toInt()) + 1).toDouble().coerceAtLeast(58.0)
        val groundTarget = Location(world, rawGroundX, groundY, rawGroundZ)

        val startPos = dragon.location.clone()
        val midHoriz = startPos.toVector().multiply(0.6).add(groundTarget.toVector().multiply(0.4))
        val climbApex = Location(
            world,
            midHoriz.x,
            (maxOf(startPos.y, groundTarget.y + 16.0) + 18.0).coerceAtMost(112.0),
            midHoriz.z
        )

        world.playSound(startPos, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 1.0f)

        if (spawnMultipleDragons) {
            spawnFourMirrorDecoyDragons(groundTarget)
        }

        val climbTicks = 22
        val plungeTicks = 22
        val totalTicks = climbTicks + plungeTicks
        var tick = 0
        var prevLoc = startPos.clone()
        var diveTask: BukkitTask? = null
        diveTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveDragon() || hasDied || inTotemClimax) {
                diveTask?.cancel()
                inAttackSequence = false
                return@runTimer
            }

            if (tick % 2 == 0) {
                for (i in 0 until 24) {
                    val angle = 2.0 * Math.PI * i / 24
                    val ringLoc = groundTarget.clone().add(cos(angle) * 5.0, 0.2, sin(angle) * 5.0)
                    world.spawnParticle(Particle.DRAGON_BREATH, ringLoc, 1, 0.05, 0.05, 0.05, 0.0, 1.0f)
                }
            }

            val nextVec = if (tick <= climbTicks) {
                val t = tick.toDouble() / climbTicks
                val smoothT = t * (2.0 - t)
                if (tick % 6 == 0) {
                    world.playSound(dragon.location, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 5f, 0.9f)
                }
                startPos.toVector().multiply(1.0 - smoothT).add(climbApex.toVector().multiply(smoothT))
            } else {
                val t = (tick - climbTicks).toDouble() / plungeTicks
                val smoothT = t * t
                climbApex.toVector().multiply(1.0 - smoothT).add(groundTarget.toVector().multiply(smoothT))
            }

            val nextLoc = nextVec.toLocation(world)
            val flightDelta = nextVec.clone().subtract(prevLoc.toVector())
            if (flightDelta.lengthSquared() > 1e-4) {
                val lookLoc = nextLoc.clone().setDirection(flightDelta.clone().multiply(-1.0))
                nextLoc.yaw = lookLoc.yaw
                nextLoc.pitch = lookLoc.pitch
            }
            prevLoc = nextLoc.clone()

            dragon.teleport(nextLoc)

            if (tick >= totalTicks) {
                diveTask?.cancel()
                impactDiveGround(groundTarget)
                inAttackSequence = false
                lastCycleStepMillis = System.currentTimeMillis()
                if (dragon.isValid && !hasDied && !inTotemClimax) {
                    dragon.phase = EnderDragon.Phase.CIRCLING
                }
            }
        }
    }

    private fun spawnFourMirrorDecoyDragons(groundCenter: Location) {
        val decoyOffsets = listOf(
            Vector(12.0, 0.0, 12.0),
            Vector(-12.0, 0.0, 12.0),
            Vector(12.0, 0.0, -12.0),
            Vector(-12.0, 0.0, -12.0)
        )
        decoyOffsets.forEach { offset ->
            spawnDecoyMirrorDragon(groundCenter, offset)
        }
    }

    private fun spawnDecoyMirrorDragon(groundCenter: Location, horizOffset: Vector) {
        val impactSpot = groundCenter.clone().add(horizOffset).apply {
            x = x.coerceIn(-38.0, 38.0)
            z = z.coerceIn(-38.0, 38.0)
            y = (world.getHighestBlockYAt(x.toInt(), z.toInt()) + 1).toDouble().coerceAtLeast(58.0)
        }
        val spawnLoc = impactSpot.clone().add(horizOffset.clone().multiply(0.4)).add(0.0, 32.0, 0.0)
        val diveDir = impactSpot.toVector().subtract(spawnLoc.toVector())
        if (diveDir.lengthSquared() > 1e-4) {
            val look = spawnLoc.clone().setDirection(diveDir.clone().multiply(-1.0))
            spawnLoc.yaw = look.yaw
            spawnLoc.pitch = look.pitch
        }

        val decoy = world.spawn(spawnLoc, EnderDragon::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { d ->
            d.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            d.isSilent = true
            d.setInvulnerable(true)
            d.customName(null)
            d.isCustomNameVisible = false
            d.isGlowing = true
            d.phase = EnderDragon.Phase.HOVER
            dragonTeam?.addEntity(d)
        }

        val windupTicks = 18
        val diveSteps = 26
        val startVec = spawnLoc.toVector()
        val endVec = impactSpot.toVector()
        var tick = 0
        var decoyTask: BukkitTask? = null
        decoyTask = runTimer(1L, 1L) {
            tick++
            if (!decoy.isValid || hasDied) {
                if (decoy.isValid) decoy.remove()
                decoyTask?.cancel()
                return@runTimer
            }

            if (tick <= windupTicks) {
                decoy.teleport(spawnLoc)
                decoy.phase = EnderDragon.Phase.HOVER
                world.spawnParticle(Particle.DRAGON_BREATH, spawnLoc.clone().add(0.0, 2.0, 0.0), 5, 1.0, 1.0, 1.0, 0.02, 1.0f)
                return@runTimer
            }

            val diveTick = tick - windupTicks
            if (diveTick >= diveSteps) {
                world.playSound(impactSpot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4f, 0.9f)
                world.playSound(impactSpot, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 4f, 1.25f)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, impactSpot, 1)
                world.spawnParticle(Particle.FLASH, impactSpot.clone().add(0.0, 0.8, 0.0), 1, 0.0, 0.0, 0.0, 0.0, currentPhase.primaryColor)
                world.spawnParticle(Particle.DRAGON_BREATH, impactSpot, 60, 2.5, 0.4, 2.5, 0.05, 1.0f)

                instaKillEndermenAt(impactSpot, 6.0)

                world.spawn(impactSpot, AreaEffectCloud::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { cloud ->
                    cloud.ownerUniqueId = dragonId
                    cloud.setParticle(Particle.DRAGON_BREATH, 1.0f)
                    cloud.duration = 200
                    cloud.radius = 3.5f
                    cloud.reapplicationDelay = 10
                    cloud.addCustomEffect(PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), true)
                }

                val decoySource = DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(dragon)
                    .withDirectEntity(dragon)
                    .build()
                impactSpot.getNearbyPlayers(5.0).filter { isValidPlayer(it) }.forEach { p ->
                    p.damage(10.0, decoySource)
                }

                // Each Mirror Decoy Dragon also unleashes a circular shockwave upon impact!
                spawnConcentricShockwaves(impactSpot)

                decoy.remove()
                decoyTask?.cancel()
                return@runTimer
            }

            val progress = diveTick.toDouble() / diveSteps
            val curLoc = startVec.clone().multiply(1.0 - progress).add(endVec.clone().multiply(progress)).toLocation(world)
            curLoc.yaw = spawnLoc.yaw
            curLoc.pitch = spawnLoc.pitch
            decoy.teleport(curLoc)
            decoy.phase = EnderDragon.Phase.HOVER
            world.spawnParticle(Particle.DRAGON_BREATH, curLoc.clone().add(0.0, 1.5, 0.0), 4, 0.8, 0.8, 0.8, 0.01, 1.0f)
        }
    }

    private fun impactDiveGround(loc: Location) {
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6f, 0.8f)
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5f, 1.2f)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2)
        world.spawnParticle(Particle.FLASH, loc.clone().add(0.0, 1.0, 0.0), 2, 0.2, 0.2, 0.2, 0.0, currentPhase.primaryColor)
        world.spawnParticle(Particle.DRAGON_BREATH, loc, 100, 4.5, 0.4, 4.5, 0.05, 1.0f)

        instaKillEndermenAt(loc, 9.0)

        val slamSource = DamageSource.builder(DamageType.MOB_ATTACK)
            .withCausingEntity(dragon)
            .withDirectEntity(dragon)
            .build()

        loc.getNearbyPlayers(8.0).filter { isValidPlayer(it) }.forEach { p ->
            val dist = p.location.distance(loc)
            val dmg = if (dist <= 4.5) 16.0 else 10.0
            p.setCooldown(Material.SHIELD, 80)
            p.clearActiveItem()
            p.damage(dmg, slamSource)
            onDragonAttackHit(p, dmg)

            val kb = p.location.toVector().subtract(loc.toVector()).setY(0.0)
            if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
            p.velocity = kb.multiply(1.25).setY(0.55)
        }

        shatterEnderPearls(loc, 6.0)
        spawnConcentricShockwaves(loc)
    }

    // ========================================================================
    // SKILL 3: 30-SECOND CONTROLLED PERCH & SPACED-OUT PERCHED DOUBLE SLAM
    // ========================================================================

    private fun triggerControlledPerchSequence(doubleSlamCount: Int, includeMultipleDragons: Boolean) {
        if (!ensureLiveDragon() || inAttackSequence || inTotemClimax || hasDied) return
        inAttackSequence = true
        lastCycleStepMillis = System.currentTimeMillis()

        val startLoc = dragon.location.clone()
        val perchLoc = getPortalPerchLocation()
        var prevLoc = startLoc.clone()
        val approachTicks = 30

        var tick = 0
        var approachTask: BukkitTask? = null
        approachTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveDragon() || hasDied || inTotemClimax) {
                approachTask?.cancel()
                inAttackSequence = false
                return@runTimer
            }

            if (tick <= approachTicks) {
                val t = tick.toDouble() / approachTicks
                val smoothT = t * t * (3.0 - 2.0 * t)
                val curVec = startLoc.toVector().multiply(1.0 - smoothT).add(perchLoc.toVector().multiply(smoothT))
                val curLoc = curVec.toLocation(world)

                val delta = curVec.clone().subtract(prevLoc.toVector())
                if (delta.lengthSquared() > 1e-4) {
                    val look = curLoc.clone().setDirection(delta.multiply(-1.0))
                    curLoc.yaw = look.yaw
                    curLoc.pitch = 0f
                }
                prevLoc = curLoc.clone()
                dragon.teleport(curLoc)
                return@runTimer
            }

            approachTask?.cancel()
            dragon.teleport(perchLoc)
            dragon.phase = EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET
            world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 0.8f)
            spawnConcentricShockwaves(perchLoc)

            if (doubleSlamCount <= 0) {
                // Standard Perch (Phase 2 & Phase 3): Stay perched for 600 ticks (30 seconds),
                // emitting a shockwave every 120 ticks (6 seconds)
                listOf(120L, 240L, 360L, 480L).forEach { delay ->
                    runLater(delay) {
                        if (ensureLiveDragon() && !hasDied && !inTotemClimax && inAttackSequence) {
                            world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 0.85f)
                            spawnConcentricShockwaves(perchLoc)
                        }
                    }
                }
                holdPerchedForTicks(perchLoc, 600) {
                    inAttackSequence = false
                    lastCycleStepMillis = System.currentTimeMillis()
                    if (dragon.isValid && !hasDied && !inTotemClimax) {
                        dragon.phase = EnderDragon.Phase.CIRCLING
                    }
                }
            } else {
                // Phase 4 & Phase 5 (30s total perch): Stay perched for 200 ticks (10s) before Slam #1!
                runLater(100L) {
                    if (ensureLiveDragon() && !hasDied && !inTotemClimax && inAttackSequence) {
                        world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 0.85f)
                        spawnConcentricShockwaves(perchLoc)
                    }
                }
                holdPerchedForTicks(perchLoc, 200) {
                    executePerchedHopSlam(perchLoc, remainingSlams = doubleSlamCount, includeMultipleDragons = includeMultipleDragons)
                }
            }
        }
    }

    private fun holdPerchedForTicks(perchLoc: Location, durationTicks: Int, onComplete: () -> Unit) {
        var step = 0
        var perchTask: BukkitTask? = null
        perchTask = runTimer(1L, 1L) {
            step++
            if (!ensureLiveDragon() || hasDied || inTotemClimax || step > durationTicks) {
                perchTask?.cancel()
                if (!hasDied && !inTotemClimax) {
                    onComplete()
                }
                return@runTimer
            }
            val locked = perchLoc.clone().apply { yaw = dragon.location.yaw }
            dragon.teleport(locked)
            dragon.phase = EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET
        }
    }

    /**
     * Spaces out the 2 perched hop-slams across the 30-second perch:
     * 200 ticks (10s) initial perch -> Slam #1 -> 200 ticks (10s) mid-perch -> Slam #2 -> 200 ticks (10s) final perch!
     */
    private fun executePerchedHopSlam(perchLoc: Location, remainingSlams: Int, includeMultipleDragons: Boolean) {
        if (remainingSlams <= 0 || !ensureLiveDragon() || hasDied || inTotemClimax) {
            // Final 200 ticks (10 seconds) perched after Slam #2
            runLater(100L) {
                if (ensureLiveDragon() && !hasDied && !inTotemClimax && inAttackSequence) {
                    world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 0.85f)
                    spawnConcentricShockwaves(perchLoc)
                }
            }
            holdPerchedForTicks(perchLoc, 200) {
                inAttackSequence = false
                lastCycleStepMillis = System.currentTimeMillis()
                if (dragon.isValid && !hasDied && !inTotemClimax) {
                    dragon.phase = EnderDragon.Phase.CIRCLING
                }
            }
            return
        }

        val apexLoc = perchLoc.clone().add(0.0, 18.0, 0.0)
        world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 6f, 0.8f)
        world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 6f, 1.1f)

        if (includeMultipleDragons) {
            spawnFourMirrorDecoyDragons(perchLoc)
        }

        val riseTicks = 18
        val slamTicks = 14
        val totalTicks = riseTicks + slamTicks
        var tick = 0
        var hopTask: BukkitTask? = null
        hopTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveDragon() || hasDied || inTotemClimax) {
                hopTask?.cancel()
                inAttackSequence = false
                return@runTimer
            }

            val curY = if (tick <= riseTicks) {
                val t = tick.toDouble() / riseTicks
                perchLoc.y + (18.0 * (t * (2.0 - t)))
            } else {
                val t = (tick - riseTicks).toDouble() / slamTicks
                apexLoc.y - (18.0 * (t * t))
            }

            val stepLoc = Location(world, perchLoc.x, curY, perchLoc.z, dragon.location.yaw, 0f)
            dragon.teleport(stepLoc)
            dragon.phase = EnderDragon.Phase.HOVER

            if (tick >= totalTicks) {
                hopTask?.cancel()
                dragon.teleport(perchLoc)
                dragon.phase = EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET
                impactDiveGround(perchLoc)

                // Hold perched for 200 ticks (10 seconds) between Slam #1 and Slam #2
                val postSlamHoldTicks = if (remainingSlams > 1) 200 else 1
                if (postSlamHoldTicks >= 200) {
                    runLater(100L) {
                        if (ensureLiveDragon() && !hasDied && !inTotemClimax && inAttackSequence) {
                            world.playSound(perchLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 0.85f)
                            spawnConcentricShockwaves(perchLoc)
                        }
                    }
                }
                holdPerchedForTicks(perchLoc, postSlamHoldTicks) {
                    executePerchedHopSlam(perchLoc, remainingSlams - 1, includeMultipleDragons)
                }
            }
        }
    }

    // ========================================================================
    // SKILL 4: CONDENSED OUTWARD-SPIRALING 360° LASER (PORTAL -> TOWERS)
    // ========================================================================

    fun triggerLaserSequence() {
        if (!ensureLiveDragon() || inAttackSequence || inTotemClimax || hasDied) return
        inAttackSequence = true
        lastCycleStepMillis = System.currentTimeMillis()

        val startLoc = dragon.location.clone()
        val centerHoverLoc = Location(world, 0.5, 84.0, 0.5)
        var prevLoc = startLoc.clone()

        val flyToCenterTicks = 30
        val sonicChargeTicks = 35
        val spiralLaserTicks = 100
        val totalTicks = flyToCenterTicks + sonicChargeTicks + spiralLaserTicks

        var tick = 0
        var laserTask: BukkitTask? = null
        laserTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveDragon() || hasDied || inTotemClimax || tick > totalTicks) {
                laserTask?.cancel()
                inAttackSequence = false
                lastCycleStepMillis = System.currentTimeMillis()
                if (dragon.isValid && !hasDied && !inTotemClimax) {
                    dragon.phase = EnderDragon.Phase.CIRCLING
                }
                return@runTimer
            }

            if (tick <= flyToCenterTicks) {
                val t = tick.toDouble() / flyToCenterTicks
                val smoothT = t * t * (3.0 - 2.0 * t)
                val curVec = startLoc.toVector().multiply(1.0 - smoothT).add(centerHoverLoc.toVector().multiply(smoothT))
                val curLoc = curVec.toLocation(world)

                val delta = curVec.clone().subtract(prevLoc.toVector())
                if (delta.lengthSquared() > 1e-4) {
                    val look = curLoc.clone().setDirection(delta.multiply(-1.0))
                    curLoc.yaw = look.yaw
                    curLoc.pitch = look.pitch
                }
                prevLoc = curLoc.clone()

                if (tick % 7 == 0) {
                    world.playSound(curLoc, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.HOSTILE, 5f, 1.0f)
                }
                dragon.teleport(curLoc)
                return@runTimer
            }

            if (tick <= flyToCenterTicks + sonicChargeTicks) {
                val chargeStep = tick - flyToCenterTicks
                if (chargeStep == 1) {
                    world.playSound(centerHoverLoc, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 10f, 0.9f)
                    world.playSound(centerHoverLoc, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 8f, 1.2f)
                }

                centerHoverLoc.yaw = (centerHoverLoc.yaw + 6f) % 360f
                dragon.teleport(centerHoverLoc)
                dragon.phase = EnderDragon.Phase.HOVER

                val headLoc = centerHoverLoc.clone().add(0.0, 1.5, 0.0)
                world.spawnParticle(Particle.END_ROD, headLoc, 10, 1.8, 1.8, 1.8, 0.05)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, headLoc, 12, 1.4, 1.4, 1.4, 0.0, currentPhase.dustTransition)
                if (chargeStep % 8 == 0) {
                    world.spawnParticle(Particle.FLASH, headLoc, 1, 0.2, 0.2, 0.2, 0.0, currentPhase.primaryColor)
                }
                return@runTimer
            }

            val laserStep = tick - (flyToCenterTicks + sonicChargeTicks)
            if (laserStep == 1) {
                world.playSound(centerHoverLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 10f, 0.85f)
                world.playSound(centerHoverLoc, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8f, 0.75f)
                world.spawnParticle(Particle.FLASH, centerHoverLoc.clone().add(0.0, 1.5, 0.0), 3, 0.3, 0.3, 0.3, 0.0, Color.WHITE)
            }

            val spiralProgress = laserStep.toDouble() / spiralLaserTicks
            val currentRadius = 2.0 + spiralProgress * 42.0 // Spirals from 2.0 (Portal) out to 44.0 (Towers)!
            val angleDeg = (laserStep * 14.4) % 360.0
            val angleRad = Math.toRadians(angleDeg)

            val targetX = 0.5 + cos(angleRad) * currentRadius
            val targetZ = 0.5 + sin(angleRad) * currentRadius
            val targetY = (world.getHighestBlockYAt(targetX.toInt(), targetZ.toInt()) + 1).toDouble().coerceAtLeast(57.0)
            val groundTargetLoc = Location(world, targetX, targetY, targetZ)

            val beamOrigin = centerHoverLoc.clone().add(0.0, 1.2, 0.0)
            val beamVec = groundTargetLoc.toVector().subtract(beamOrigin.toVector())

            val dragonLook = centerHoverLoc.clone().setDirection(beamVec.clone().multiply(-1.0))
            centerHoverLoc.yaw = dragonLook.yaw
            centerHoverLoc.pitch = dragonLook.pitch
            dragon.teleport(centerHoverLoc)
            dragon.phase = EnderDragon.Phase.HOVER

            fireSpiralingLaserBeam(beamOrigin, groundTargetLoc, laserStep)
        }
    }

    /**
     * Condensed laser beam (0.45-block step intervals) that insta-kills Endermen,
     * creates punchy ground impacts, and deals 2 hearts (4.0 HP) per tick of unavoidable damage.
     */
    private fun fireSpiralingLaserBeam(beamOrigin: Location, groundImpactLoc: Location, stepTick: Int) {
        val diff = groundImpactLoc.toVector().subtract(beamOrigin.toVector())
        val totalDist = diff.length()
        if (totalDist < 1e-4) return
        val dir = diff.clone().normalize()

        val hitPlayersThisTick = HashSet<UUID>()

        var d = 0.0
        while (d <= totalDist) {
            val pt = beamOrigin.clone().add(dir.clone().multiply(d))
            world.spawnParticle(Particle.END_ROD, pt, 2, 0.04, 0.04, 0.04, 0.0)
            world.spawnParticle(Particle.DRAGON_BREATH, pt, 2, 0.06, 0.06, 0.06, 0.0, 1.0f)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 2, 0.05, 0.05, 0.05, 0.0, currentPhase.dustTransition)

            if (d.toInt() % 3 == 0) {
                instaKillEndermenAt(pt, 2.2)
            }

            pt.getNearbyPlayers(2.2).filter { isValidPlayer(it) }.forEach { p ->
                if (hitPlayersThisTick.add(p.uniqueId)) {
                    inflictUnavoidableLaserDamage(p, 4.0)
                }
            }
            d += 0.45
        }

        instaKillEndermenAt(groundImpactLoc, 3.5)

        world.spawnParticle(Particle.FLASH, groundImpactLoc.clone().add(0.0, 0.4, 0.0), 1, 0.2, 0.2, 0.2, 0.0, currentPhase.primaryColor)
        world.spawnParticle(Particle.EXPLOSION, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 2, 0.5, 0.2, 0.5, 0.0)
        world.spawnParticle(Particle.BLOCK, groundImpactLoc.clone().add(0.0, 0.3, 0.0), 25, 0.8, 0.3, 0.8, 0.15, endStoneData)
        world.spawnParticle(Particle.DRAGON_BREATH, groundImpactLoc, 25, 1.2, 0.3, 1.2, 0.05, 1.0f)
        world.spawnParticle(Particle.GUST, groundImpactLoc.clone().add(0.0, 0.4, 0.0), 2, 0.6, 0.2, 0.6, 0.0)
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, groundImpactLoc, 6, 0.6, 0.2, 0.6, 0.03)

        if (stepTick % 3 == 0) {
            world.playSound(groundImpactLoc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 3.0f, 1.2f)
            world.playSound(groundImpactLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.9f)
            world.playSound(groundImpactLoc, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 2.5f, 0.8f)
        }
        if (stepTick % 12 == 0) {
            world.playSound(groundImpactLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 3.5f, 1.3f)
        }

        groundImpactLoc.getNearbyPlayers(2.8).filter { isValidPlayer(it) }.forEach { p ->
            if (hitPlayersThisTick.add(p.uniqueId)) {
                inflictUnavoidableLaserDamage(p, 4.0)
            }
        }
    }

    private fun inflictUnavoidableLaserDamage(player: Player, damage: Double) {
        player.noDamageTicks = 0
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(dragon)
            .withDirectEntity(dragon)
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
        onDragonAttackHit(player, damage)
    }

    // ========================================================================
    // PHASE 6 (TOTEM CLIMAX): ARROW IMMUNE, 32 BLOCKS UP, LEVITATION & REPULSE PUSHBACK
    // ========================================================================

    private fun popTotemAndAscendCenter() {
        if (totemPopped) return
        totemPopped = true
        inAttackSequence = true
        inTotemClimax = true
        isTotemAscending = true
        totemRepulseTimerTicks = 0

        currentHealth = 15.0
        dragon.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, currentHealth)
        if (dragon.isValid) {
            dragon.health = 10.0
            dragon.equipment.setItemInMainHand(ItemStack(Material.AIR))
        }
        updatePhase(DragonPhaseType.ENDER)

        val popLoc = dragon.location.clone()
        world.playSound(popLoc, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 10f, 1f)
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, popLoc.clone().add(0.0, 2.0, 0.0), 300, 2.5, 2.5, 2.5, 0.5)
        world.spawnParticle(Particle.FLASH, popLoc.clone().add(0.0, 2.0, 0.0), 3, 0.5, 0.5, 0.5, 0.0, Color.WHITE)

        val perchBase = getPortalPerchLocation()
        val finalFloatLoc = perchBase.clone().add(0.0, 32.0, 0.0)
        totemHoverLocation = finalFloatLoc.clone()

        val startPos = dragon.location.clone()
        val ascendTicks = 75
        var step = 0
        var prevLoc = startPos.clone()
        var ascendTask: BukkitTask? = null
        ascendTask = runTimer(1L, 1L) {
            step++
            if (!ensureLiveDragon() || hasDied) {
                ascendTask?.cancel()
                return@runTimer
            }

            val t = (step.toDouble() / ascendTicks).coerceIn(0.0, 1.0)
            val smoothT = t * t * (3.0 - 2.0 * t)
            val curVec = startPos.toVector().multiply(1.0 - smoothT).add(finalFloatLoc.toVector().multiply(smoothT))
            val curLoc = curVec.toLocation(world)

            val delta = curVec.clone().subtract(prevLoc.toVector())
            if (delta.lengthSquared() > 1e-4) {
                val look = curLoc.clone().setDirection(delta.multiply(-1.0))
                curLoc.yaw = look.yaw
                curLoc.pitch = 0f
            }
            prevLoc = curLoc.clone()

            dragon.teleport(curLoc)
            dragon.phase = EnderDragon.Phase.HOVER

            world.spawnParticle(Particle.DRAGON_BREATH, curLoc.clone().add(0.0, 1.5, 0.0), 10, 1.5, 1.5, 1.5, 0.05, 1.0f)
            if (step % 18 == 0) {
                world.spawnParticle(Particle.EXPLOSION_EMITTER, curLoc.clone().add(0.0, 1.5, 0.0), 1)
                world.spawnParticle(Particle.FLASH, curLoc.clone().add(0.0, 1.5, 0.0), 2, 0.3, 0.3, 0.3, 0.0, currentPhase.primaryColor)
                world.playSound(curLoc, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 8f, 1.0f)
            }

            if (step == 40) {
                isTotemAscending = false
            }

            if (step >= ascendTicks) {
                ascendTask?.cancel()
                isTotemAscending = false
                totemHoverLocation = curLoc.clone()
            }
        }

        runTimer(1L, 5L) {
            if (!ensureLiveDragon() || hasDied) return@runTimer
            val pCenter = getPortalPerchLocation()

            for (h in 1..30 step 3) {
                val colPt = pCenter.clone().add(0.0, h.toDouble(), 0.0)
                world.spawnParticle(Particle.BUBBLE_POP, colPt, 8, 1.8, 1.2, 1.8, 0.05)
                world.spawnParticle(Particle.END_ROD, colPt, 2, 1.4, 1.0, 1.4, 0.02)
            }

            world.players.filter { isValidPlayer(it) }.forEach { p ->
                val dx = p.location.x - pCenter.x
                val dz = p.location.z - pCenter.z
                if ((dx * dx + dz * dz) <= 25.0 && p.location.y in (pCenter.y - 2.0)..(pCenter.y + 36.0)) {
                    p.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 35, 5, false, false, true))
                    p.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 140, 0, false, false, true))
                }
            }

            if (!isTotemAscending) {
                totemRepulseTimerTicks += 5
                val dLoc = dragon.location.clone().add(0.0, 1.5, 0.0)

                if (totemRepulseTimerTicks % 25 == 0) {
                    val pitch = (0.9f + (totemRepulseTimerTicks / 140.0f) * 0.9f).coerceAtMost(2.0f)
                    world.playSound(dLoc, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 10f, pitch)
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, dLoc, 1)
                    world.spawnParticle(Particle.FLASH, dLoc, 1, 0.2, 0.2, 0.2, 0.0, currentPhase.primaryColor)
                    world.spawnParticle(Particle.DRAGON_BREATH, dLoc, 45, 2.5, 2.0, 2.5, 0.08, 1.0f)
                }

                if (totemRepulseTimerTicks >= 140) {
                    totemRepulseTimerTicks = 0
                    world.playSound(dLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 10f, 0.8f)
                    world.playSound(dLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 8f, 0.7f)
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, dLoc, 3)
                    world.spawnParticle(Particle.FLASH, dLoc, 3, 0.5, 0.5, 0.5, 0.0, Color.WHITE)

                    dLoc.getNearbyPlayers(18.0).filter { isValidPlayer(it) }.forEach { p ->
                        p.removePotionEffect(PotionEffectType.LEVITATION)
                        val push = p.location.toVector().subtract(dLoc.toVector())
                        if (push.lengthSquared() > 1e-4) push.normalize() else push.setX(1.0)
                        p.velocity = push.multiply(2.2).setY(-0.6)
                        p.sendActionBar(
                            "<bold><light_purple>[Ender Dragon]</light_purple></bold> <red>The dragon's heartbeat repulsed you! Ride the portal stream back up!</red>".mm()
                        )
                    }
                }
            }
        }
    }

    // ========================================================================
    // 64-BLOCK CIRCULAR SLAM SHOCKWAVE (GROWS OUT TO 64 BLOCKS + INSTA-KILLS ENDERMEN)
    // ========================================================================

    private fun spawnConcentricShockwaves(center: Location) {
        val shockwavePhase = currentPhase
        val unblockableSource = DamageSource.builder(DamageType.MOB_ATTACK)
            .withCausingEntity(dragon)
            .withDirectEntity(dragon)
            .build()

        var step = 0
        val maxSteps = 80 // 80 steps * 0.8 blocks/tick = 64.0 blocks radius!
        val hitPlayers = HashSet<UUID>()
        var task: BukkitTask? = null
        task = runTimer(1L, 1L) {
            step++
            if (step > maxSteps || !ensureLiveDragon() || hasDied) {
                task?.cancel()
                return@runTimer
            }

            val radius = step * 0.8
            val pointCount = (radius * 5.0).toInt().coerceIn(24, 140)

            for (i in 0 until pointCount) {
                val angle = 2.0 * Math.PI * i / pointCount
                val x = center.x + cos(angle) * radius
                val z = center.z + sin(angle) * radius
                val y = (world.getHighestBlockYAt(x.toInt(), z.toInt()) + 1).toDouble()
                val pLoc = Location(world, x, y + 0.15, z)

                world.spawnParticle(Particle.DRAGON_BREATH, pLoc, 1, 0.15, 0.05, 0.15, 0.0, 1.0f)
                world.spawnParticle(Particle.DUST, pLoc, 1, 0.15, 0.05, 0.15, 0.0, shockwavePhase.dustOptions)
                if (i % 4 == 0) {
                    world.spawnParticle(shockwavePhase.accentParticle, pLoc, 1, 0.1, 0.05, 0.1, 0.0)
                }
            }

            center.getNearbyEntitiesByType(Enderman::class.java, radius + 1.5).forEach { enderman ->
                if (!enderman.isDead) {
                    val dx = enderman.location.x - center.x
                    val dz = enderman.location.z - center.z
                    val dist = sqrt(dx * dx + dz * dz)
                    if (abs(dist - radius) <= 1.2) {
                        world.spawnParticle(Particle.PORTAL, enderman.location.add(0.0, 1.5, 0.0), 20, 0.4, 0.8, 0.4, 0.2)
                        enderman.health = 0.0
                    }
                }
            }

            center.getNearbyPlayers(radius + 1.5).filter { isValidPlayer(it) }.forEach { p ->
                if (p.uniqueId in hitPlayers) return@forEach
                val dx = p.location.x - center.x
                val dz = p.location.z - center.z
                val dist = sqrt(dx * dx + dz * dz)
                if (abs(dist - radius) <= 1.1) {
                    val groundY = (world.getHighestBlockYAt(p.location) + 1).toDouble()
                    if (p.location.y <= groundY + 0.75) {
                        hitPlayers.add(p.uniqueId)
                        p.damage(10.0, unblockableSource)
                        onDragonAttackHit(p, 10.0)
                        p.velocity = p.velocity.add(Vector(0.0, 0.42, 0.0))
                    }
                }
            }
        }
    }

    // ========================================================================
    // CAGED TOWER GUARD PHANTOMS & PEARL SHATTERING
    // ========================================================================

    private fun setupCagedTowerPhantoms() {
        if (!config.cagedTowerPhantoms) return

        spikePillars.forEach { pillarVec ->
            val topLoc = pillarVec.toLocation(world)
            val hasBars = (topLoc.block.type == Material.IRON_BARS) ||
                    (topLoc.clone().add(0.0, 1.0, 0.0).block.type == Material.IRON_BARS)

            if (hasBars) {
                listOf(
                    topLoc.clone().add(2.0, -2.0, 2.0),
                    topLoc.clone().add(-2.0, -2.0, 2.0),
                    topLoc.clone().add(-2.0, -2.0, -2.0),
                    topLoc.clone().add(2.0, -2.0, -2.0)
                ).forEach { it.block.type = Material.OBSIDIAN }

                spawnOrbitingGuardPhantom(topLoc)
            }
        }
    }

    private fun spawnOrbitingGuardPhantom(towerLoc: Location) {
        val phantom = world.spawn(towerLoc.clone().add(0.0, 8.0, 0.0), Phantom::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { p ->
            p.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            p.setAI(false)
            p.setInvulnerable(true)
        }

        var orbitAngle = 0.0
        var enrageDive = false
        var pTask: BukkitTask? = null
        pTask = runTimer(1L, 1L) {
            if (!phantom.isValid || !ensureLiveDragon() || hasDied) {
                phantom.remove()
                pTask?.cancel()
                return@runTimer
            }

            if (!enrageDive) {
                orbitAngle = (orbitAngle + 3.0) % 360.0
                val rad = Math.toRadians(orbitAngle)
                val targetLoc = towerLoc.clone().add(cos(rad) * 10.0, 6.0, sin(rad) * 10.0)
                targetLoc.setDirection(towerLoc.toVector().subtract(targetLoc.toVector()))
                phantom.teleport(targetLoc)

                if (phantom.noDamageTicks > 0) {
                    enrageDive = true
                    phantom.setInvulnerable(false)
                    world.playSound(phantom.location, Sound.ENTITY_PHANTOM_HURT, SoundCategory.HOSTILE, 4f, 1f)
                }
            } else {
                val diveDir = towerLoc.toVector().subtract(phantom.location.toVector()).normalize().multiply(0.8)
                phantom.teleport(phantom.location.add(diveDir))

                if (phantom.location.distanceSquared(towerLoc) <= 4.0 || phantom.location.block.type == Material.IRON_BARS) {
                    for (x in -2..2) {
                        for (y in -3..3) {
                            for (z in -2..2) {
                                val b = towerLoc.clone().add(x.toDouble(), y.toDouble(), z.toDouble()).block
                                if (b.type == Material.IRON_BARS) b.type = Material.AIR
                            }
                        }
                    }
                    world.playSound(towerLoc, Sound.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 4f, 0.6f)
                    world.playSound(towerLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4f, 1.2f)
                    world.spawnParticle(Particle.EXPLOSION, towerLoc, 5)
                    phantom.remove()
                    pTask?.cancel()
                }
            }
        }
    }

    private fun shatterEnderPearls(loc: Location, radius: Double) {
        if (!config.antiPearlFarming) return
        loc.getNearbyEntitiesByType(Item::class.java, radius).forEach { item ->
            if (item.itemStack.type == Material.ENDER_PEARL) {
                world.playSound(item.location, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 0.7f, 1.6f)
                world.spawnParticle(Particle.BLOCK, item.location, 6, 0.1, 0.1, 0.1, Material.ENDER_CHEST.createBlockData())
                item.remove()
            }
        }
    }

    // ========================================================================
    // DEATH & CLEANUP
    // ========================================================================

    fun onDeath(killer: Player? = null) {
        if (hasDied) return
        hasDied = true

        cancelAllTasks()
        plugin.bossBarManager.unregisterById(dragonId)
        dragonTeam?.unregister()
        dragonTeam = null

        val participants = HashSet<Player>()
        killer?.let { participants.add(it) }
        participants.addAll(dragon.location.getNearbyPlayers(128.0))
        participants.forEach { BossKillTracker.recordBossKill(it, "ender_dragon") }

        world.playSound(dragon.location, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 6f, 0.5f)
        world.playSound(dragon.location, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 6f, 0.8f)

        val portalLoc = getPortalPerchLocation()
        ExperienceUtil.spawnLevelOrbs(portalLoc, config.expLevelsDropped, orbCount = 25)

        for (i in 0 until 72) {
            val angle = Math.toRadians(i * 5.0)
            val pDir = Vector(cos(angle), 0.0, sin(angle)).multiply(1.5)
            world.spawnParticle(Particle.DUST, portalLoc, 8, pDir.x, 0.1, pDir.z, 0.0, DragonPhaseType.ENDER.dustOptions)
            world.spawnParticle(Particle.END_ROD, portalLoc, 4, pDir.x * 0.5, 0.1, pDir.z * 0.5, 0.05)
        }

        killer?.sendMessage(
            """
            <gradient:#00b09b:#96c93d><bold>You have freed the end. You are no longer marked.</bold></gradient>
            <gray><i>(Hostile mobs will no longer spawn on the surface of the overworld. If you want to toggle this, do /tweakskompass surfacespawn)</i></gray>
            """.trimIndent().mm()
        )
    }

    fun forceCleanup() {
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(dragonId)
        dragonTeam?.unregister()
        dragonTeam = null
        world.entities.forEach { ent ->
            if (ent.persistentDataContainer.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)) {
                ent.remove()
            }
        }
    }

    private fun getPortalPerchLocation(): Location {
        val bedrockY = world.getHighestBlockYAt(0, 0).coerceIn(58, 75)
        return Location(world, 0.5, (bedrockY + 1).toDouble(), 0.5)
    }

    private fun resolveTargetPlayer(): Player? {
        return dragon.location.getNearbyPlayers(config.followRange)
            .filter { isValidPlayer(it) }
            .randomOrNull()
            ?: dragon.location.getNearbyPlayers(128.0)
                .filter { isValidPlayer(it) }
                .minByOrNull { it.location.distanceSquared(dragon.location) }
    }

    private fun isValidPlayer(player: Player): Boolean {
        return !player.isDead && (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE)
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