package project.kompass.travellersTales.boss.warden

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
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.AxisAngle4f
import org.joml.Vector3f
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

class ActiveWardenFight(
    private val plugin: TravellersTales,
    var warden: Warden,
    var config: WardenConfig,
    val summonerId: UUID? = null
) {
    val wardenId: UUID = warden.uniqueId
    val world: World
        get() = warden.world

    private val tasks = mutableListOf<BukkitTask>()
    private var wardenTeam: Team? = null
    private val snareJumpLockKey = NamespacedKey(plugin, "warden_snare_jump_lock")

    var arenaCenter: Location = warden.location.clone()
        private set

    // Phase & Combat State Flags
    var currentPhase: Int = 1
        private set
    var isTransitionInvulnerable: Boolean = false
        private set
    var isVulnerable: Boolean = false
        private set
    var parryWindow: Boolean = false
        private set
    var isParryCharging: Boolean = false
        private set
    var isStunned: Boolean = false
        private set
    var isDiggingTeleport: Boolean = false
        private set
    var isDying: Boolean = false
        private set

    // Re-entrancy lock to prevent SONIC_BOOM damage events from recursively triggering fireExtendedSonicBoomRay
    var isFiringExtendedBoom: Boolean = false
        private set

    private var parryTask: BukkitTask? = null
    private var unreachableTicks: Int = 0
    private var digCooldownTicks: Int = 0
    private var extendedBoomCooldownTicks: Int = 0
    var lastCombatTimestamp: Long = System.currentTimeMillis()

    private val darkSculkBlue = Color.fromRGB(0, 71, 171)
    private val soulCyanTransition = Particle.DustTransition(
        Color.fromRGB(0, 110, 215),
        Color.fromRGB(5, 20, 45),
        2.0f
    )

    fun start(playEmergeIntro: Boolean = false) {
        setupTeam()
        configureWardenEntity()
        plugin.bossBarManager.createBossBar(warden, config.bossBar)

        if (playEmergeIntro) {
            summonerId?.let { id ->
                Bukkit.getPlayer(id)?.let { player ->
                    if (player.world == world && !player.isDead) {
                        warden.target = player
                        warden.increaseAnger(player, 150)
                    }
                }
            }
        }

        scheduleCoreLoops()
    }

    private fun ensureLiveWarden(): Boolean {
        if (warden.isValid) return true
        val live = Bukkit.getEntity(wardenId) as? Warden
        if (live != null && live.isValid) {
            warden = live
            plugin.bossBarManager.rebindEntity(live)
            return true
        }
        return false
    }

    private fun setupTeam() {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val teamName = "Warden_${wardenId.toString().take(8)}"
        scoreboard.getTeam(teamName)?.unregister()
        wardenTeam = scoreboard.registerNewTeam(teamName).apply {
            setAllowFriendlyFire(false)
            color(NamedTextColor.DARK_BLUE)
            addEntity(warden)
        }
    }

    private fun configureWardenEntity() {
        warden.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "the_warden")
        warden.customName(null)
        warden.isCustomNameVisible = false

        warden.isPersistent = false
        warden.removeWhenFarAway = true

        warden.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
        warden.health = config.maxHealth
        warden.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange // 64.0
    }

    // ========================================================================
    // CORE LOOPS & UNREACHABLE TARGET / EXTENDED SONIC BOOM CHECKS
    // ========================================================================

    private fun scheduleCoreLoops() {
        runTimer(20L, 20L) {
            if (isDying) return@runTimer
            if (!ensureLiveWarden() || warden.isDead) return@runTimer
            if (digCooldownTicks > 0) digCooldownTicks -= 20
            if (extendedBoomCooldownTicks > 0) extendedBoomCooldownTicks -= 20

            if (warden.world == arenaCenter.world &&
                warden.location.distanceSquared(arenaCenter) > config.maxCombatDistanceSquared
            ) {
                warden.teleport(arenaCenter)
            }

            warden.location.getNearbyPlayers(config.followRange).filter { isValidCombatTarget(it) }.forEach { player ->
                val minAnger = if (currentPhase == 3) 150 else 90
                if (warden.getAnger(player) < minAnger) {
                    warden.setAnger(player, minAnger)
                }
            }

            if (isBusyWithSpecialAction()) return@runTimer

            val target = resolvePrimaryTarget() ?: return@runTimer
            warden.target = target
            val dist = warden.location.distance(target.location)
            val maxBoomRange = 15.0 + config.sonicBoomExtraRange // 25.0 blocks

            if (dist in 15.0..maxBoomRange && extendedBoomCooldownTicks <= 0) {
                extendedBoomCooldownTicks = 100 // 5s cooldown
                triggerExtendedSonicBoomWindup(target)
                return@runTimer
            }

            val cantReachMelee = if (dist <= 5.0 && warden.hasLineOfSight(target)) {
                false
            } else if (dist > 5.0 && (System.currentTimeMillis() - lastCombatTimestamp) > 10000L) {
                val path = warden.pathfinder.findPath(target.location)
                path == null
            } else {
                false
            }

            val outOfBoomRange = dist > maxBoomRange || (abs(target.location.y - warden.location.y) > 6.0 && !warden.hasLineOfSight(target))

            if (cantReachMelee && outOfBoomRange) {
                unreachableTicks += 20
                if (unreachableTicks >= 60 && digCooldownTicks <= 0) {
                    unreachableTicks = 0
                    digCooldownTicks = 240 // 12s cooldown
                    performDigTeleportTo(target)
                }
            } else {
                unreachableTicks = 0
            }
        }

        // Phase 1+: Tendril Snare every 15s (300 ticks)
        runTimer(config.tendrilIntervalTicks, config.tendrilIntervalTicks) {
            if (!ensureLiveWarden() || isBusyWithSpecialAction()) return@runTimer
            val target = resolvePrimaryTarget() ?: return@runTimer
            spawnTendrilSnare(target)
        }

        // Phase 2+: Vibration Beacon every 30 ticks
        runTimer(config.vibrationBeaconIntervalTicks, config.vibrationBeaconIntervalTicks) {
            if (!ensureLiveWarden() || currentPhase < 2 || isDying) return@runTimer
            tickVibrationBeacons()
        }

        // Phase 2+: Periodic Sculk Parasite Ambush every 12s (240 ticks) if fewer than 4 parasites are alive
        runTimer(240L, 240L) {
            if (!ensureLiveWarden() || currentPhase < 2 || isBusyWithSpecialAction()) return@runTimer
            val activeParasites = warden.location.getNearbyEntitiesByType(Silverfish::class.java, 32.0)
                .count { it.isValid && !it.isDead && it.persistentDataContainer.has(MobKeys.WARDEN_PARASITE, PersistentDataType.BYTE) }
            if (activeParasites < 4) {
                val target = resolvePrimaryTarget() ?: return@runTimer
                spawnSculkParasites(target.location)
            }
        }

        // Phase 2+: Seismic Echo (Particle Jump-Rope) every 20s (400 ticks)
        runTimer(config.seismicEchoIntervalTicks, config.seismicEchoIntervalTicks) {
            if (!ensureLiveWarden() || currentPhase < 2 || isBusyWithSpecialAction()) return@runTimer
            triggerSeismicEcho()
        }

        // Phase 3: Heartbeat Parry every 18s (360 ticks)
        runTimer(config.parryIntervalTicks, config.parryIntervalTicks) {
            if (!ensureLiveWarden() || currentPhase < 3 || isBusyWithSpecialAction()) return@runTimer
            triggerHeartbeatParry()
        }
    }

    // ========================================================================
    // DIG TELEPORT & EXTENDED SONIC BOOM (+10 BLOCKS + RE-ENTRANCY LOCK)
    // ========================================================================

    private fun performDigTeleportTo(target: LivingEntity) {
        if (isDiggingTeleport || isDying) return
        isDiggingTeleport = true

        val origin = warden.location.clone()
        val sculkData = Material.SCULK.createBlockData()
        world.playSound(origin, Sound.ENTITY_WARDEN_DIG, SoundCategory.HOSTILE, 4f, 1f)

        var digTick = 0
        var digParticleTask: BukkitTask? = null
        digParticleTask = runTimer(1L, 2L) {
            digTick += 2
            if (!ensureLiveWarden() || isDying || digTick >= 30) {
                digParticleTask?.cancel()
                return@runTimer
            }
            world.spawnParticle(Particle.BLOCK, warden.location, 25, 0.6, 0.2, 0.6, sculkData)
            world.spawnParticle(Particle.SCULK_SOUL, warden.location.add(0.0, 0.5, 0.0), 6, 0.4, 0.3, 0.4, 0.02)
        }

        runLater(30L) {
            try {
                if (!ensureLiveWarden() || isDying) return@runLater
                val dest = findSafeSpotBeside(target.location)
                arenaCenter = dest.clone()
                warden.teleport(dest)
                warden.pose = Pose.STANDING
                warden.setAI(true)
                warden.target = target
                warden.increaseAnger(target, 120)

                world.playSound(dest, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 4f, 1f)
                world.spawnParticle(Particle.BLOCK, dest, 45, 0.8, 0.3, 0.8, sculkData)
                world.spawnParticle(Particle.SCULK_CHARGE_POP, dest.clone().add(0.0, 0.6, 0.0), 25, 0.6, 0.4, 0.6, 0.04)
                lastCombatTimestamp = System.currentTimeMillis()
            } finally {
                isDiggingTeleport = false
            }
        }
    }

    private fun triggerExtendedSonicBoomWindup(target: LivingEntity) {
        world.playSound(warden.location, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 3f, 1f)
        runLater(34L) {
            if (!ensureLiveWarden() || isBusyWithSpecialAction()) return@runLater
            world.playSound(warden.location, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 3f, 1f)
            fireExtendedSonicBoomRay(target.eyeLocation, includePrimaryTargetDamage = true)
        }
    }

    fun fireExtendedSonicBoomRay(targetLocation: Location, includePrimaryTargetDamage: Boolean) {
        if (isFiringExtendedBoom || isDying) return
        isFiringExtendedBoom = true

        try {
            lastCombatTimestamp = System.currentTimeMillis()
            val origin = warden.location.clone().add(0.0, 1.6, 0.0)
            val dir = targetLocation.toVector().subtract(origin.toVector())
            if (dir.lengthSquared() < 1e-4) return
            dir.normalize()

            val totalLength = (15.0 + config.sonicBoomExtraRange).toInt()
            val hitEntities = HashSet<UUID>()
            val sonicSource = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(warden)
                .withDirectEntity(warden)
                .build()

            for (i in 1..totalLength) {
                val point = origin.clone().add(dir.clone().multiply(i.toDouble()))
                if (i > 15 || includePrimaryTargetDamage) {
                    world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0.0, 0.0, 0.0, 0.0)
                }

                point.getNearbyLivingEntities(1.5).forEach { entity ->
                    if (entity.uniqueId == wardenId) return@forEach
                    if (entity.persistentDataContainer.has(MobKeys.WARDEN_PARASITE, PersistentDataType.BYTE)) return@forEach
                    if (!includePrimaryTargetDamage && entity == warden.target && i <= 15) return@forEach

                    if (hitEntities.add(entity.uniqueId)) {
                        entity.damage(10.0, sonicSource)
                        val flatPush = dir.clone().setY(0.0)
                        if (flatPush.lengthSquared() > 1e-4) flatPush.normalize().multiply(1.1)
                        entity.velocity = flatPush.setY(0.4)
                    }
                }
            }
        } finally {
            isFiringExtendedBoom = false
        }
    }

    // ========================================================================
    // PHASE TRANSITIONS
    // ========================================================================

    fun checkPhaseTransitions() {
        if (isDying) return
        val hp = warden.health
        if (currentPhase == 1 && hp <= config.phase2Threshold) {
            enterPhase2()
        } else if (currentPhase < 3 && hp <= config.phase3Threshold) {
            if (currentPhase < 2) enterPhase2()
            enterPhase3()
        }
    }

    private fun enterPhase2() {
        currentPhase = 2
        isTransitionInvulnerable = true
        warden.setAI(false)
        warden.pose = Pose.ROARING

        plugin.bossBarManager.updateBarAppearance(
            warden,
            titleMiniMessage = config.bossBar.phase2Title,
            color = config.bossBar.phase2Color
        )

        world.playSound(warden.location, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 5f, 1f)
        warden.location.getNearbyPlayers(config.darknessRadius).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, false, true))
        }

        resolvePrimaryTarget()?.let { spawnSculkParasites(it.location) }

        runLater(config.transitionInvulnTicks) {
            isTransitionInvulnerable = false
            if (ensureLiveWarden() && !warden.isDead && !isDying && !isStunned && !isParryCharging) {
                warden.pose = Pose.STANDING
                warden.setAI(true)
            }
        }
    }

    private fun enterPhase3() {
        currentPhase = 3
        isVulnerable = true
        warden.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.phase3MovementSpeed

        plugin.bossBarManager.updateBarAppearance(
            warden,
            titleMiniMessage = config.bossBar.enragedTitle,
            color = config.bossBar.enragedColor
        )

        world.playSound(warden.location, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 5f, 0.7f)
        world.spawnParticle(Particle.SCULK_SOUL, warden.location.add(0.0, 1.5, 0.0), 100, 1.0, 1.0, 1.0, 0.1)
    }

    // ========================================================================
    // PHASE 1: TENDRIL SNARE (SAFE JUMP_STRENGTH LOCK INSTEAD OF JUMP_BOOST 250)
    // ========================================================================

    private fun spawnTendrilSnare(target: LivingEntity) {
        val startLoc = warden.location.clone()
        val flatDir = target.location.toVector().subtract(startLoc.toVector()).setY(0.0)
        if (flatDir.lengthSquared() < 1e-4) return
        flatDir.normalize()

        val blockData = config.tendrilMaterial.createBlockData()
        world.playSound(startLoc, Sound.BLOCK_SCULK_SPREAD, SoundCategory.HOSTILE, 2.5f, 0.8f)

        var step = 1
        var snareTask: BukkitTask? = null
        snareTask = runTimer(1L, 1L) {
            if (!ensureLiveWarden() || isDying || step > 22) {
                snareTask?.cancel()
                return@runTimer
            }

            val point = startLoc.clone().add(flatDir.clone().multiply(step.toDouble()))
            point.y = findGroundY(point)

            val display = world.spawn(point, BlockDisplay::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { bd ->
                bd.persistentDataContainer.set(MobKeys.WARDEN_TEMP_ENTITY, PersistentDataType.BYTE, 1)
                bd.block = blockData
                bd.transformation = Transformation(
                    Vector3f(-0.5f, 0f, -0.5f),
                    AxisAngle4f(0f, 0f, 1f, 0f),
                    Vector3f(1f, 1.3f, 1f),
                    AxisAngle4f(0f, 0f, 1f, 0f)
                )
            }

            world.playSound(point, Sound.BLOCK_SCULK_PLACE, SoundCategory.BLOCKS, 1.2f, 1.2f)
            world.spawnParticle(Particle.SCULK_CHARGE_POP, point.clone().add(0.0, 0.5, 0.0), 6, 0.2, 0.2, 0.2, 0.02)

            point.getNearbyPlayers(1.4).forEach { player ->
                if (isValidCombatTarget(player)) {
                    player.addPotionEffect(
                        PotionEffect(PotionEffectType.SLOWNESS, config.snareDurationTicks, 9, false, true, true)
                    )
                    applyJumpLock(player, config.snareDurationTicks)
                    world.playSound(player.location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, 1.2f, 1.5f)
                }
            }

            runLater(25L) { display.remove() }
            step++
        }
    }

    /**
     * Temporarily sets [player]'s JUMP_STRENGTH to 0 using an AttributeModifier
     * instead of JUMP_BOOST amplifier 250 (which launches players 800+ blocks high in 1.21.4+ / 26.3).
     */
    private fun applyJumpLock(player: Player, durationTicks: Int) {
        val jumpAttr = player.getAttribute(Attribute.JUMP_STRENGTH) ?: return
        jumpAttr.getModifier(snareJumpLockKey)?.let { jumpAttr.removeModifier(it) }
        jumpAttr.addModifier(
            AttributeModifier(snareJumpLockKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
        )
        player.velocity = Vector(0.0, player.velocity.y.coerceAtMost(0.0), 0.0)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            player.getAttribute(Attribute.JUMP_STRENGTH)?.getModifier(snareJumpLockKey)?.let { mod ->
                player.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(mod)
            }
        }, durationTicks.toLong())
    }

    private fun clearAllJumpLocks() {
        Bukkit.getOnlinePlayers().forEach { player ->
            val jumpAttr = player.getAttribute(Attribute.JUMP_STRENGTH) ?: return@forEach
            jumpAttr.getModifier(snareJumpLockKey)?.let { jumpAttr.removeModifier(it) }
        }
    }

    // ========================================================================
    // PHASE 2: SCULK PARASITES, VIBRATION BEACON & SEISMIC ECHO
    // ========================================================================

    fun spawnSculkParasites(location: Location) {
        if (currentPhase < 2 || !ensureLiveWarden() || isDying) return
        val nearestTarget = resolvePrimaryTarget()
        repeat(2) {
            world.spawn(location.clone().add(0.0, 0.2, 0.0), Silverfish::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { sf ->
                sf.persistentDataContainer.set(MobKeys.WARDEN_PARASITE, PersistentDataType.BYTE, 1)
                sf.persistentDataContainer.set(MobKeys.WARDEN_TEMP_ENTITY, PersistentDataType.BYTE, 1)
                sf.customName(config.parasiteName.mm())
                sf.isCustomNameVisible = true
                sf.isGlowing = true
                sf.addPotionEffect(PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false))
                wardenTeam?.addEntity(sf)
                if (nearestTarget != null) {
                    sf.target = nearestTarget
                }
            }
        }
        world.playSound(location, Sound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.HOSTILE, 2.0f, 1.2f)
        world.spawnParticle(Particle.SCULK_CHARGE_POP, location.clone().add(0.0, 0.4, 0.0), 20, 0.4, 0.2, 0.4, 0.03)
    }

    private fun tickVibrationBeacons() {
        val nearestPlayer = warden.location.getNearbyPlayers(config.followRange)
            .filter { isValidCombatTarget(it) }
            .minByOrNull { it.location.distanceSquared(warden.location) } ?: return

        val parasites = warden.location.getNearbyEntitiesByType(Silverfish::class.java, config.vibrationBeaconRadius)
            .filter { it.isValid && !it.isDead && it.persistentDataContainer.has(MobKeys.WARDEN_PARASITE, PersistentDataType.BYTE) }

        if (parasites.isEmpty()) return

        val vibrationData = Vibration(Vibration.Destination.EntityDestination(warden), 20)
        for (silverfish in parasites) {
            warden.increaseAnger(nearestPlayer, 30)
            world.spawnParticle(
                Particle.VIBRATION,
                silverfish.location.add(0.0, 0.3, 0.0),
                1, 0.0, 0.0, 0.0, 0.0,
                vibrationData
            )
        }
    }

    private fun triggerSeismicEcho() {
        val origin = warden.location.clone()
        origin.y = findGroundY(origin)
        world.playSound(origin, Sound.ENTITY_WARDEN_STEP, SoundCategory.HOSTILE, 4f, 0.5f)
        world.playSound(origin, Sound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.HOSTILE, 4f, 0.6f)

        for (wave in 0 until 3) {
            runLater(wave * 40L) {
                if (!ensureLiveWarden() || warden.isDead || isDying) return@runLater
                expandSeismicRing(warden.location.clone())
            }
        }
    }

    private fun expandSeismicRing(origin: Location) {
        world.playSound(origin, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, 2f, 0.7f)
        val hitPlayersThisWave = HashSet<UUID>()

        var tick = 0
        var ringTask: BukkitTask? = null
        ringTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveWarden() || isDying || tick > 20) {
                ringTask?.cancel()
                return@runTimer
            }

            val radius = 1.0 + (tick - 1) * (15.0 / 19.0)
            val points = (radius * 8).toInt().coerceAtLeast(16)

            for (i in 0 until points) {
                val angle = 2.0 * Math.PI * i / points
                val pLoc = origin.clone().add(cos(angle) * radius, 0.2, sin(angle) * radius)
                world.spawnParticle(Particle.SCULK_CHARGE_POP, pLoc, 1, 0.0, 0.0, 0.0, 0.0)
            }

            origin.getNearbyPlayers(radius + 2.0).forEach { player ->
                if (!isValidCombatTarget(player) || player.uniqueId in hitPlayersThisWave) return@forEach
                val dx = player.location.x - origin.x
                val dz = player.location.z - origin.z
                val horizDist = sqrt(dx * dx + dz * dz)
                val vertDist = abs(player.location.y - origin.y)

                if (abs(horizDist - radius) <= 0.8 && vertDist <= 1.5 && player.velocity.y <= 0.0) {
                    hitPlayersThisWave.add(player.uniqueId)
                    val dmgSource = DamageSource.builder(DamageType.MOB_ATTACK)
                        .withCausingEntity(warden)
                        .withDirectEntity(warden)
                        .build()
                    player.damage(config.seismicEchoDamage, dmgSource)

                    val kbDir = player.location.toVector().subtract(origin.toVector()).setY(0.0)
                    if (kbDir.lengthSquared() > 1e-4) kbDir.normalize()
                    player.velocity = kbDir.multiply(1.1).setY(0.45)
                }
            }
        }
    }

    // ========================================================================
    // PHASE 3: HEARTBEAT PARRY (COUNTER & PUNISHMENT)
    // ========================================================================

    private fun triggerHeartbeatParry() {
        isParryCharging = true
        parryWindow = false
        warden.setAI(false)

        var tick = 0
        parryTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveWarden() || warden.isDead || isDying) {
                parryWindow = false
                isParryCharging = false
                parryTask?.cancel()
                return@runTimer
            }

            if (tick == 20 || tick == 40 || tick == 60) {
                val pitch = when (tick) {
                    20 -> 0.8f
                    40 -> 1.3f
                    else -> 1.8f
                }
                world.playSound(warden.location, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, pitch)
                spawnChestFlameRing(warden.location.clone().add(0.0, 1.5, 0.0), 1.4)
            }

            parryWindow = tick in 50..65

            if (tick > 65) {
                parryWindow = false
                isParryCharging = false
                parryTask?.cancel()
                warden.setAI(true)
                triggerParryPunishment()
            }
        }
    }

    fun triggerParryCounter(attacker: Player?) {
        if (isDying) return
        parryWindow = false
        isParryCharging = false
        parryTask?.cancel()
        parryTask = null

        val counterDamage = config.maxHealth * config.parryCounterDamagePercent
        if (warden.health - counterDamage <= 0.0) {
            triggerFinalBreathDeath(attacker)
            return
        }

        warden.health = (warden.health - counterDamage).coerceAtLeast(1.0)

        isStunned = true
        warden.setAI(false)
        warden.pose = Pose.ROARING

        world.playSound(warden.location, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 3f, 1.6f)
        world.playSound(warden.location, Sound.ENTITY_WARDEN_HURT, SoundCategory.HOSTILE, 4f, 0.6f)
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, warden.location.add(0.0, 1.5, 0.0), 80, 0.8, 0.8, 0.8, 0.15)
        world.spawnParticle(Particle.FLASH, warden.location.add(0.0, 1.5, 0.0), 2, 0.0, 0.0, 0.0, 0.0, darkSculkBlue)

        attacker?.sendActionBar("<bold><aqua>⚡ PARRIED! Warden Stunned! ⚡</aqua></bold>".mm())

        runLater(config.parryStunTicks) {
            isStunned = false
            if (ensureLiveWarden() && !warden.isDead && !isDying) {
                warden.pose = Pose.STANDING
                warden.setAI(true)
            }
        }
    }

    private fun triggerParryPunishment() {
        val chestLoc = warden.location.clone().add(0.0, 1.5, 0.0)
        world.playSound(chestLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5f, 0.8f)
        world.spawnParticle(Particle.SONIC_BOOM, chestLoc, 12, 2.0, 1.5, 2.0, 0.0)

        val punishSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(warden)
            .withDirectEntity(warden)
            .build()

        warden.location.getNearbyPlayers(32.0).forEach { player ->
            if (isValidCombatTarget(player) && warden.hasLineOfSight(player)) {
                world.spawnParticle(Particle.SONIC_BOOM, player.eyeLocation, 2, 0.2, 0.2, 0.2, 0.0)
                player.damage(config.parryPunishDamage, punishSource)
            }
        }
    }

    // ========================================================================
    // FINAL BREATH ROAR & SOUL EXPLOSION DEATH SEQUENCE
    // ========================================================================

    fun triggerFinalBreathDeath(killer: Player?) {
        if (isDying) return
        isDying = true

        ensureLiveWarden()
        cancelAllTasks()
        clearAllJumpLocks()
        plugin.bossBarManager.unregisterById(wardenId)
        purgeTempEntities()

        val participants = HashSet<Player>()
        killer?.let { participants.add(it) }
        summonerId?.let { id -> Bukkit.getPlayer(id)?.let { participants.add(it) } }
        participants.addAll(warden.location.getNearbyPlayers(config.followRange))
        participants.forEach {
            plugin.wardenManager.applyGracePeriod(it)
            BossKillTracker.recordBossKill(it, "the_warden")
        }

        warden.clearActivePotionEffects()
        warden.health = 1.0
        warden.setAI(false)
        warden.setInvulnerable(true)
        warden.isSilent = true
        warden.velocity = Vector(0.0, 0.0, 0.0)
        warden.pose = Pose.ROARING

        var lastChestLoc = warden.location.clone().add(0.0, 1.5, 0.0)

        world.playSound(lastChestLoc, Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 5.0f, 0.5f)
        world.playSound(lastChestLoc, Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 5.0f, 0.6f)
        world.playSound(lastChestLoc, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, 4.0f, 0.5f)

        var tick = 0
        var buildupTask: BukkitTask? = null
        buildupTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick++
            if (!ensureLiveWarden() || tick >= 60) {
                buildupTask?.cancel()
                if (warden.isValid) {
                    lastChestLoc = warden.location.clone().add(0.0, 1.5, 0.0)
                }
                detonateSoulExplosion(lastChestLoc)
                return@Runnable
            }

            warden.pose = Pose.ROARING
            val currentChest = warden.location.add(0.0, 1.5, 0.0)
            lastChestLoc = currentChest.clone()

            when (tick) {
                10 -> world.playSound(currentChest, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 0.6f)
                25 -> world.playSound(currentChest, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 0.9f)
                38 -> world.playSound(currentChest, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 1.3f)
                48 -> world.playSound(currentChest, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 1.7f)
                55 -> world.playSound(currentChest, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 5f, 2.0f)
            }

            val angle1 = tick * 0.45
            val angle2 = angle1 + Math.PI
            val spiralRadius = 0.4 + (tick / 60.0) * 1.4
            val p1 = currentChest.clone().add(cos(angle1) * spiralRadius, (tick / 60.0) * 0.8, sin(angle1) * spiralRadius)
            val p2 = currentChest.clone().add(cos(angle2) * spiralRadius, (tick / 60.0) * 0.8, sin(angle2) * spiralRadius)

            world.spawnParticle(Particle.SCULK_SOUL, p1, 3, 0.15, 0.15, 0.15, 0.04)
            world.spawnParticle(Particle.SOUL, p2, 3, 0.15, 0.15, 0.15, 0.04)
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, currentChest, 4, 0.4, 0.5, 0.4, 0.05)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, currentChest, 5, 0.5, 0.6, 0.5, 0.0, soulCyanTransition)

            if (tick % 4 == 0) {
                world.playSound(currentChest, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 3.0f, 0.6f + (tick / 60.0f))
            }
        }, 1L, 1L)
    }

    private fun detonateSoulExplosion(chestLoc: Location) {
        try {
            val targetWorld = chestLoc.world ?: world
            targetWorld.playSound(chestLoc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5.0f, 0.6f)
            targetWorld.playSound(chestLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 0.7f)
            targetWorld.playSound(chestLoc, Sound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.HOSTILE, 5.0f, 0.5f)
            targetWorld.playSound(chestLoc, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 6.0f, 0.5f)
            targetWorld.playSound(chestLoc, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 6.0f, 1.2f)

            targetWorld.spawnParticle(Particle.FLASH, chestLoc, 3, 0.0, 0.0, 0.0, 0.0, darkSculkBlue)
            targetWorld.spawnParticle(Particle.SONIC_BOOM, chestLoc, 8, 1.5, 1.5, 1.5, 0.0)
            targetWorld.spawnParticle(Particle.SOUL_FIRE_FLAME, chestLoc, 150, 1.2, 1.2, 1.2, 0.25)
            targetWorld.spawnParticle(Particle.SCULK_CHARGE_POP, chestLoc, 120, 2.0, 1.5, 2.0, 0.15)
            targetWorld.spawnParticle(Particle.DUST_COLOR_TRANSITION, chestLoc, 180, 2.2, 2.0, 2.2, 0.1, soulCyanTransition)

            val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
            val soulCount = 160
            for (i in 0 until soulCount) {
                val y = 1.0 - (i / (soulCount - 1).toDouble()) * 2.0
                val radiusAtY = sqrt(1.0 - y * y)
                val theta = goldenAngle * i
                val vx = cos(theta) * radiusAtY
                val vz = sin(theta) * radiusAtY
                val speed = Random.nextDouble(0.25, 0.65)

                targetWorld.spawnParticle(Particle.SCULK_SOUL, chestLoc, 0, vx, y, vz, speed)
                if (i % 2 == 0) {
                    targetWorld.spawnParticle(Particle.SOUL, chestLoc, 0, vx, y, vz, speed * 0.85)
                }
            }

            val drops = listOf(
                ItemStack(Material.SCULK_CATALYST, 1),
                ItemStack(Material.RECOVERY_COMPASS, 1),
                ItemStack(Material.ECHO_SHARD, 2)
            )
            drops.forEach { stack ->
                targetWorld.dropItemNaturally(chestLoc, stack) { item ->
                    item.isGlowing = true
                    item.velocity = item.velocity.add(Vector(0.0, 0.25, 0.0))
                }
            }

            ExperienceUtil.spawnLevelOrbs(chestLoc, config.expLevelsDropped, orbCount = 20)
        } finally {
            forceCleanup(killWarden = true)
            plugin.wardenManager.activeFights.remove(wardenId)
        }
    }

    // ========================================================================
    // CLEANUP & HELPERS
    // ========================================================================

    fun onDeath() {
        forceCleanup(killWarden = false)
    }

    fun forceCleanup(killWarden: Boolean) {
        cancelAllTasks()
        clearAllJumpLocks()
        plugin.bossBarManager.unregisterById(wardenId)
        wardenTeam?.unregister()
        wardenTeam = null
        purgeTempEntities()

        if (killWarden) {
            if (warden.isValid) {
                warden.remove()
            } else {
                (Bukkit.getEntity(wardenId) as? Warden)?.remove()
            }
        }
    }

    private fun purgeTempEntities() {
        world.entities.forEach { ent ->
            if (ent.persistentDataContainer.has(MobKeys.WARDEN_TEMP_ENTITY, PersistentDataType.BYTE)) {
                ent.remove()
            }
        }
    }

    private fun spawnChestFlameRing(center: Location, radius: Double) {
        for (i in 0 until 24) {
            val angle = 2.0 * Math.PI * i / 24
            val pLoc = center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, pLoc, 2, 0.05, 0.05, 0.05, 0.01)
        }
    }

    private fun resolvePrimaryTarget(): LivingEntity? {
        val currentTarget = warden.target
        if (currentTarget is LivingEntity && currentTarget.isValid && !currentTarget.isDead) {
            if (currentTarget !is Player || isValidCombatTarget(currentTarget)) return currentTarget
        }
        return warden.location.getNearbyPlayers(config.followRange)
            .filter { isValidCombatTarget(it) }
            .minByOrNull { it.location.distanceSquared(warden.location) }
    }

    private fun isValidCombatTarget(player: Player): Boolean {
        return !player.isDead && (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE)
    }

    private fun isBusyWithSpecialAction(): Boolean {
        return isDying || isTransitionInvulnerable || isParryCharging || isStunned || isDiggingTeleport
    }

    private fun findSafeSpotBeside(targetLoc: Location): Location {
        val offsets = listOf(
            Vector(2.0, 0.0, 0.0), Vector(-2.0, 0.0, 0.0),
            Vector(0.0, 0.0, 2.0), Vector(0.0, 0.0, -2.0),
            Vector(1.5, 0.0, 1.5), Vector(-1.5, 0.0, -1.5)
        )
        for (offset in offsets) {
            val candidate = targetLoc.clone().add(offset)
            val groundBlock = candidate.clone().add(0.0, -1.0, 0.0).block
            if (groundBlock.type.isSolid && candidate.block.isPassable && candidate.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                val dir = targetLoc.toVector().subtract(candidate.toVector())
                if (dir.lengthSquared() > 1e-4) candidate.setDirection(dir)
                return candidate
            }
        }
        return targetLoc.clone()
    }

    private fun findGroundY(loc: Location): Double {
        val check = loc.clone()
        for (dy in 3 downTo -6) {
            val block = check.clone().add(0.0, dy.toDouble(), 0.0).block
            val above = block.getRelative(0, 1, 0)
            if (block.type.isSolid && above.isPassable) {
                return block.y + 1.0
            }
        }
        return loc.y
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
        parryTask?.cancel()
        tasks.forEach { it.cancel() }
        tasks.clear()
    }
}