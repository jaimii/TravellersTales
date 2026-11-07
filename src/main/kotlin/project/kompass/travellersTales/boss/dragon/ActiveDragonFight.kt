package project.kompass.travellersTales.boss.dragon

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
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
import project.kompass.travellersTales.boss.bar.BossBarSettings
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ActiveDragonFight(
    private val plugin: TravellersTales,
    val dragon: EnderDragon,
    var config: DragonConfig
) {
    val world: World = dragon.world
    private val tasks = mutableListOf<BukkitTask>()
    private val portalCenter: Location = Location(world, 0.5, 80.0, 0.5)

    var currentHealth: Double = config.maxHealth
        private set

    var currentPhase: DragonPhaseType = DragonPhaseType.VOID
        private set

    private var dragonTeam: Team? = null
    private var isInvulnerableFinal: Boolean = false
    private var inAttackSequence: Boolean = false
    private var totemPopped: Boolean = false
    private var hasDied: Boolean = false
    private var perchTimerTicks: Int = 0

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

    fun start() {
        setupTeam()
        configureDragonEntity()
        initBossBar()
        setupCagedTowerPhantoms()
        scheduleCoreLoops()
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

    private fun configureDragonEntity() {
        dragon.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "ender_dragon")
        val maxVanillaCap = 1024.0
        dragon.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxVanillaCap
        dragon.health = maxVanillaCap
        dragon.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange // 64.0
        dragon.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 300.0

        dragon.equipment.setItemInMainHand(ItemStack(Material.TOTEM_OF_UNDYING))
        dragon.equipment.setDropChance(EquipmentSlot.HAND, 0f)

        updatePhase(DragonPhaseType.VOID)
    }

    private fun initBossBar() {
        val settings = BossBarSettings(
            enabled = true,
            title = config.phaseTitles[currentPhase] ?: currentPhase.defaultTitle,
            color = currentPhase.barColor,
            overlay = config.barStyle,
            flags = if (config.darkenSky) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
            radius = config.barRadius
        )
        plugin.bossBarManager.createBossBar(dragon, settings)
    }

    fun applyDamage(amount: Double, attacker: Player? = null): Boolean {
        if (isInvulnerableFinal && dragon.phase != EnderDragon.Phase.LAND_ON_PORTAL) {
            world.spawnParticle(Particle.END_ROD, dragon.location.add(0.0, 2.0, 0.0), 10, 1.0, 1.0, 1.0, 0.05)
            return false
        }

        currentHealth = (currentHealth - amount).coerceAtLeast(0.0)

        if (currentHealth <= 0.0 && !totemPopped) {
            popTotem()
            return false
        }

        val visualHealth = ((currentHealth / config.maxHealth) * 1024.0).coerceIn(0.1, 1024.0)
        dragon.health = visualHealth

        checkPhaseTransitions()

        if (currentHealth <= 0.0 && totemPopped) {
            onDeath(attacker)
            return true
        }

        return false
    }

    private fun checkPhaseTransitions() {
        when {
            currentHealth > config.phase2Threshold -> {
                if (currentPhase != DragonPhaseType.VOID) updatePhase(DragonPhaseType.VOID)
            }
            currentHealth in (config.phase3Threshold + 0.1)..config.phase2Threshold -> {
                if (currentPhase != DragonPhaseType.CRIMSON) {
                    updatePhase(DragonPhaseType.CRIMSON)
                    triggerUltraFireballSequence()
                }
            }
            currentHealth in (config.phase4Threshold + 0.1)..config.phase3Threshold -> {
                if (currentPhase != DragonPhaseType.ICHOR) {
                    updatePhase(DragonPhaseType.ICHOR)
                    triggerLaserSequence()
                }
            }
            currentHealth <= config.phase4Threshold -> {
                if (currentPhase != DragonPhaseType.ENDER) {
                    updatePhase(DragonPhaseType.ENDER)
                    triggerFinalStand()
                }
            }
        }
    }

    private fun updatePhase(phase: DragonPhaseType) {
        currentPhase = phase
        dragon.isGlowing = true
        dragonTeam?.color(phase.teamColor)

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, dragon.location.add(0.0, 2.0, 0.0), 100, 2.0, 2.0, 2.0, 0.2, phase.dustTransition)
        world.spawnParticle(phase.accentParticle, dragon.location.add(0.0, 2.0, 0.0), 50, 1.5, 1.5, 1.5, 0.1)

        val title = config.phaseTitles[phase] ?: phase.defaultTitle
        dragon.customName(title.mm())
        plugin.bossBarManager.updateBarAppearance(
            dragon,
            titleMiniMessage = title,
            color = phase.barColor,
            overlay = config.barStyle
        )
    }

    fun onDragonAttackHit(target: Player, damageDealt: Double) {
        when (currentPhase) {
            DragonPhaseType.VOID -> {
                target.addPotionEffect(
                    PotionEffect(PotionEffectType.LEVITATION, config.levitationTicks, 0, false, true, true)
                )
            }
            DragonPhaseType.CRIMSON -> {
                target.addPotionEffect(
                    PotionEffect(PotionEffectType.WITHER, config.witherTicks, 0, false, true, true)
                )
            }
            DragonPhaseType.ICHOR -> {
                val healAmount = damageDealt * config.lifestealMultiplier
                currentHealth = (currentHealth + healAmount).coerceAtMost(config.maxHealth)
                world.spawnParticle(Particle.HEART, dragon.location.add(0.0, 2.0, 0.0), 10, 1.5, 1.5, 1.5, 0.1)
                world.playSound(dragon.location, Sound.ENTITY_PLAYER_BURP, SoundCategory.HOSTILE, 1f, 0.5f)
            }
            DragonPhaseType.ENDER -> {
                // No extra status effect
            }
        }
    }

    private fun scheduleCoreLoops() {
        runTimer(1L, 1L) {
            if (!dragon.isValid || dragon.isDead) return@runTimer

            if (config.dragonParticleTrail) {
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    dragon.location.add(0.0, 2.5, 0.0),
                    2, 0.6, 0.6, 0.6, 1.0,
                    currentPhase.dustTransition
                )
                world.spawnParticle(
                    currentPhase.accentParticle,
                    dragon.location.add(0.0, 2.0, 0.0),
                    1, 0.3, 0.3, 0.3, 0.01
                )
            }

            if (currentPhase == DragonPhaseType.ENDER) {
                perchTimerTicks++
                if (dragon.phase == EnderDragon.Phase.LAND_ON_PORTAL) {
                    isInvulnerableFinal = false
                } else {
                    isInvulnerableFinal = true
                    world.spawnParticle(Particle.END_ROD, dragon.location.add(0.0, 2.0, 0.0), 2, 1.5, 1.5, 1.5, 0.02)
                    if (perchTimerTicks >= 600) {
                        dragon.phase = EnderDragon.Phase.LAND_ON_PORTAL
                        perchTimerTicks = 0
                    }
                }
            }
        }

        // 20-Tick (1s) Loop: MaxCombatDistance (256.0), Dives & Crystal Beacons
        runTimer(20L, 20L) {
            if (!dragon.isValid || inAttackSequence) return@runTimer

            // Enforce MaxCombatDistance (256.0 blocks from portalCenter)
            if (dragon.location.distanceSquared(portalCenter) > config.maxCombatDistanceSquared) {
                dragon.teleport(portalCenter)
            }

            if (Math.random() <= 0.06 && dragon.phase != EnderDragon.Phase.LAND_ON_PORTAL) {
                triggerDiveSequence()
            }

            if (config.crystalBeaconParticles) {
                world.getEntitiesByClass(EnderCrystal::class.java).forEach { crystal ->
                    world.spawnParticle(Particle.END_ROD, crystal.location.add(0.0, 15.0, 0.0), 2, 0.0, 10.0, 0.0, 0.0, null, true)
                }
            }
        }
    }

    fun triggerUltraFireballSequence() {
        if (!dragon.isValid || inAttackSequence) return
        inAttackSequence = true

        dragon.phase = EnderDragon.Phase.HOVER
        world.playSound(dragon.location, Sound.BLOCK_ENDER_CHEST_OPEN, SoundCategory.HOSTILE, 6f, 0.5f)
        world.playSound(dragon.location, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 7f, 0.8f)

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, dragon.location.add(0.0, 1.5, 0.0), 80, 0.5, 0.5, 0.5, 0.2, currentPhase.dustTransition)
        world.spawnParticle(currentPhase.accentParticle, dragon.location.add(0.0, 1.5, 0.0), 40, 0.4, 0.4, 0.4, 0.05)

        for (i in 0 until 10) {
            val delay = 20L + (i * 10L)
            val spreadOffset = if (i % 2 != 0) 0.15 else 0.0
            runLater(delay) {
                if (dragon.isValid) {
                    launchUltraFireball(spreadOffset)
                }
            }
        }

        runLater(140L) {
            inAttackSequence = false
            if (dragon.isValid) dragon.phase = EnderDragon.Phase.CIRCLING
        }
    }

    private fun launchUltraFireball(spread: Double) {
        val target = dragon.location.getNearbyPlayers(config.followRange).randomOrNull()
            ?: dragon.location.getNearbyPlayers(128.0).randomOrNull() ?: return
        val origin = dragon.location.add(0.0, 1.5, 0.0)
        val dir = target.eyeLocation.toVector().subtract(origin.toVector()).normalize()

        if (spread > 0) {
            dir.add(Vector((Math.random() - 0.5) * spread, (Math.random() - 0.5) * spread, (Math.random() - 0.5) * spread)).normalize()
        }

        world.playSound(origin, Sound.ENTITY_ENDER_DRAGON_SHOOT, SoundCategory.HOSTILE, 5f, 1f)

        val fireballPhase = currentPhase
        var step = 0
        val currentLoc = origin.clone()
        var speed = 0.5

        var task: BukkitTask? = null
        task = runTimer(1L, 1L) {
            step++
            speed = (speed + 0.05).coerceAtMost(1.8)
            currentLoc.add(dir.clone().multiply(speed))

            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION, currentLoc, 2, 0.1, 0.1, 0.1, 1.0,
                fireballPhase.dustTransition
            )
            world.spawnParticle(fireballPhase.accentParticle, currentLoc, 1, 0.1, 0.1, 0.1, 0.0)

            val hitBlock = !currentLoc.block.isPassable
            val hitPlayer = currentLoc.getNearbyPlayers(1.5).isNotEmpty()

            if (hitBlock || hitPlayer || step > 100) {
                task?.cancel()
                detonateUltraFireball(currentLoc, fireballPhase)
            }
        }
    }

    private fun detonateUltraFireball(loc: Location, phase: DragonPhaseType) {
        world.playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 4f, 1f)

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 60, 0.8, 0.8, 0.8, 0.3, phase.dustTransition)
        world.spawnParticle(phase.accentParticle, loc, 30, 0.5, 0.5, 0.5, 0.1)

        world.spawn(loc, AreaEffectCloud::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { cloud ->
            cloud.setParticle(Particle.DUST, phase.dustOptions)
            cloud.duration = 600
            cloud.radius = 4.0f
            cloud.addCustomEffect(PotionEffect(PotionEffectType.INSTANT_DAMAGE, 1, 0), true)
        }

        loc.getNearbyPlayers(3.5).forEach { p ->
            p.damage(8.0, DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(dragon).build())
            p.damage(8.0, DamageSource.builder(DamageType.GENERIC).withDirectEntity(dragon).build())
            onDragonAttackHit(p, 16.0)
        }

        shatterEnderPearls(loc, 3.5)
    }

    fun triggerDiveSequence() {
        if (!dragon.isValid || inAttackSequence) return
        inAttackSequence = true

        val target = dragon.location.getNearbyPlayers(config.followRange).randomOrNull()
            ?: dragon.location.getNearbyPlayers(128.0).randomOrNull() ?: return
        val groundTarget = target.location.clone()

        for (i in 0 until 36) {
            val angle = Math.toRadians(i * 10.0)
            val ringLoc = groundTarget.clone().add(cos(angle) * 6.0, 0.2, sin(angle) * 6.0)
            world.spawnParticle(Particle.DUST, ringLoc, 2, 0.1, 0.0, 0.1, 0.0, currentPhase.dustOptions)
            world.spawnParticle(currentPhase.accentParticle, ringLoc, 1, 0.0, 0.0, 0.0, 0.0)
        }

        world.playSound(dragon.location, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5f, 1f)

        if (currentPhase == DragonPhaseType.CRIMSON || currentPhase == DragonPhaseType.ICHOR) {
            spawnDecoyMirrorDragon(groundTarget.clone().add(8.0, 25.0, 8.0))
            spawnDecoyMirrorDragon(groundTarget.clone().add(-8.0, 25.0, -8.0))
        }

        dragon.phase = EnderDragon.Phase.CHARGE_PLAYER
        runLater(60L) {
            impactDiveGround(groundTarget)
            inAttackSequence = false
            if (dragon.isValid) dragon.phase = EnderDragon.Phase.CIRCLING
        }
    }

    private fun spawnDecoyMirrorDragon(spawnLoc: Location) {
        val decoy = world.spawn(spawnLoc, EnderDragon::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { d ->
            d.isSilent = true
            d.isInvulnerable = true
            d.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            d.phase = EnderDragon.Phase.LAND_ON_PORTAL
        }
        runLater(60L) {
            if (decoy.isValid) {
                world.spawnParticle(Particle.EXPLOSION, decoy.location, 1)
                decoy.remove()
            }
        }
    }

    private fun impactDiveGround(loc: Location) {
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 6f, 0.8f)
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 6f, 1.3f)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1)

        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 120, 6.0, 0.2, 6.0, 0.2, currentPhase.dustTransition)
        world.spawnParticle(currentPhase.accentParticle, loc, 50, 4.0, 0.2, 4.0, 0.1)

        loc.getNearbyPlayers(12.0).forEach { p ->
            val dist = p.location.distance(loc)
            val dmg = if (dist <= 6.0) 20.0 else 16.0
            p.setCooldown(Material.SHIELD, 100)
            p.damage(dmg, DamageSource.builder(DamageType.MOB_ATTACK).withDirectEntity(dragon).build())
            onDragonAttackHit(p, dmg)
        }

        shatterEnderPearls(loc, 7.0)
        spawnConcentricShockwaves(loc)
    }

    fun triggerLaserSequence() {
        if (!dragon.isValid || inAttackSequence) return
        inAttackSequence = true

        dragon.phase = EnderDragon.Phase.HOVER
        val hoverLoc = dragon.location
        hoverLoc.y = 85.0
        dragon.teleport(hoverLoc)

        world.playSound(dragon.location, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 8f, 1.2f)
        world.playSound(dragon.location, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, SoundCategory.HOSTILE, 8f, 1.1f)

        runLater(60L) {
            if (!dragon.isValid) return@runLater
            world.playSound(dragon.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 8f, 0.5f)

            var sweepTick = 0
            var laserTask: BukkitTask? = null
            laserTask = runTimer(1L, 1L) {
                sweepTick++
                if (!dragon.isValid || sweepTick > 80) {
                    laserTask?.cancel()
                    inAttackSequence = false
                    dragon.phase = EnderDragon.Phase.CIRCLING
                    return@runTimer
                }

                val currentYaw = dragon.location.yaw + (sweepTick * 2.25f)
                val currentPitch = 30f + (sin(Math.toRadians(sweepTick * 4.0)) * 15f).toFloat()
                dragon.setRotation(currentYaw, currentPitch)

                fireLaserRaycast(dragon.location.add(0.0, 2.0, 0.0), dragon.location.direction)
            }
        }
    }

    private fun fireLaserRaycast(start: Location, direction: Vector) {
        val ray = start.clone()
        val step = direction.clone().normalize().multiply(1.5)

        for (i in 0 until 85) {
            ray.add(step)

            world.spawnParticle(
                Particle.DUST, ray, 1, 0.1, 0.1, 0.1, 0.0,
                currentPhase.dustOptions
            )
            world.spawnParticle(currentPhase.accentParticle, ray, 1, 0.05, 0.05, 0.05, 0.0)

            ray.getNearbyPlayers(1.5).forEach { p ->
                p.damage(24.0, DamageSource.builder(DamageType.GENERIC).withDirectEntity(dragon).build())
                onDragonAttackHit(p, 24.0)
                world.playSound(p.location, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.HOSTILE, 1.5f, 2f)
            }

            val block = ray.block
            if (!block.isPassable) {
                if (isLaserBreakable(block)) {
                    block.breakNaturally()
                } else {
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, ray, 15, 0.2, 0.2, 0.2, 0.1, currentPhase.dustTransition)
                    world.spawnParticle(currentPhase.accentParticle, ray, 8, 0.2, 0.2, 0.2, 0.05)
                    break
                }
            }
        }
    }

    private fun isLaserBreakable(block: Block): Boolean {
        val type = block.type
        return type != Material.BEDROCK && type != Material.OBSIDIAN &&
                type != Material.END_STONE && type != Material.CRYING_OBSIDIAN &&
                type != Material.BARRIER && type != Material.END_PORTAL &&
                type != Material.END_PORTAL_FRAME && type != Material.END_GATEWAY
    }

    fun triggerFinalStand() {
        dragon.phase = EnderDragon.Phase.LAND_ON_PORTAL
        world.playSound(dragon.location, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 10f, 0.8f)

        spawnJumpPads()

        runTimer(40L, 80L) {
            if (!dragon.isValid || currentPhase != DragonPhaseType.ENDER) return@runTimer
            if (dragon.phase == EnderDragon.Phase.LAND_ON_PORTAL) {
                world.playSound(dragon.location, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 8f, 1.2f)
                spawnConcentricShockwaves(Location(world, 0.5, 65.0, 0.5))
            }
        }
    }

    private fun popTotem() {
        totemPopped = true
        currentHealth = config.maxHealth * 0.10
        world.playSound(dragon.location, Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 10f, 1f)
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, dragon.location.add(0.0, 2.0, 0.0), 300, 2.0, 2.0, 2.0, 0.5)
        dragon.equipment.setItemInMainHand(ItemStack(Material.AIR))
        triggerFinalStand()
    }

    private fun spawnJumpPads() {
        val padOffsets = listOf(
            Vector(12.0, 65.0, 0.0),
            Vector(-12.0, 65.0, 0.0),
            Vector(0.0, 65.0, 12.0),
            Vector(0.0, 65.0, -12.0)
        )
        padOffsets.forEach { offset ->
            val padLoc = offset.toLocation(world)
            runTimer(1L, 5L) {
                if (!dragon.isValid || currentPhase != DragonPhaseType.ENDER) return@runTimer
                world.spawnParticle(Particle.DUST, padLoc, 6, 0.8, 0.1, 0.8, 0.0, currentPhase.dustOptions)
                world.spawnParticle(Particle.END_ROD, padLoc, 4, 0.5, 0.2, 0.5, 0.02)
                padLoc.getNearbyPlayers(3.0).forEach { p ->
                    p.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, 40, 9, false, false, true))
                    p.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, 160, 0, false, false, true))
                }
            }
        }
    }

    // Consolidated 1-task expanding shockwave (eliminates 72 concurrent repeating tasks)
    private fun spawnConcentricShockwaves(center: Location) {
        val shockwavePhase = currentPhase
        val unblockableSource = DamageSource.builder(DamageType.MOB_ATTACK)
            .withDirectEntity(dragon)
            .build()

        var step = 0
        val hitPlayers = HashSet<UUID>()
        var task: BukkitTask? = null
        task = runTimer(1L, 1L) {
            step++
            if (step > 60 || !dragon.isValid) {
                task?.cancel()
                return@runTimer
            }

            val radius = step * 0.5 // Expands up to 30 blocks
            val pointCount = (radius * 5.0).toInt().coerceIn(16, 64)

            for (i in 0 until pointCount) {
                val angle = 2.0 * Math.PI * i / pointCount
                val x = center.x + cos(angle) * radius
                val z = center.z + sin(angle) * radius
                val y = (world.getHighestBlockYAt(x.toInt(), z.toInt()) + 1).toDouble()
                val pLoc = Location(world, x, y, z)

                world.spawnParticle(
                    Particle.DUST, pLoc, 1, 0.2, 0.05, 0.2, 0.0,
                    shockwavePhase.dustOptions
                )
                if (i % 2 == 0) {
                    world.spawnParticle(shockwavePhase.accentParticle, pLoc, 1, 0.1, 0.05, 0.1, 0.0)
                }
            }

            // Damage grounded players touched by the expanding wave ring
            center.getNearbyPlayers(radius + 1.5).forEach { p ->
                if (p.uniqueId in hitPlayers) return@forEach
                val dx = p.location.x - center.x
                val dz = p.location.z - center.z
                val dist = kotlin.math.sqrt(dx * dx + dz * dz)
                if (abs(dist - radius) <= 1.0) {
                    val groundY = (world.getHighestBlockYAt(p.location) + 1).toDouble()
                    if (p.location.y <= groundY + 1.2) {
                        hitPlayers.add(p.uniqueId)
                        p.damage(16.0, unblockableSource)
                        onDragonAttackHit(p, 16.0)
                    }
                }
            }
        }
    }

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
            p.isInvulnerable = true
        }

        var orbitAngle = 0.0
        var enrageDive = false
        var pTask: BukkitTask? = null
        pTask = runTimer(1L, 1L) {
            if (!phantom.isValid || !dragon.isValid) {
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
                    phantom.isInvulnerable = false
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

    fun onDeath(killer: Player? = null) {
        if (hasDied) return
        hasDied = true

        cancelAllTasks()
        plugin.bossBarManager.unregisterEntity(dragon)
        dragonTeam?.unregister()
        dragonTeam = null

        world.playSound(dragon.location, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 6f, 0.5f)
        world.playSound(dragon.location, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 6f, 0.8f)

        // Radial particle shockwaves & 300 Levels of Experience (358,470 XP) at the Exit Portal
        val portalLoc = Location(world, 0.5, 67.0, 0.5)
        ExperienceUtil.spawnLevelOrbs(portalLoc, config.expLevelsDropped, orbCount = 25)

        for (i in 0 until 72) {
            val angle = Math.toRadians(i * 5.0)
            val pDir = Vector(cos(angle), 0.0, sin(angle)).multiply(1.5)
            world.spawnParticle(Particle.DUST, portalLoc, 8, pDir.x, 0.1, pDir.z, 0.0, DragonPhaseType.ENDER.dustOptions)
            world.spawnParticle(Particle.END_ROD, portalLoc, 4, pDir.x * 0.5, 0.1, pDir.z * 0.5, 0.05)
        }

        // Deliver defeat message ONLY to the player who landed the killing blow
        killer?.sendMessage(
            """
            <gradient:#00b09b:#96c93d><bold>You have freed the end. You are no longer marked.</bold></gradient>
            <gray><i>(Hostile mobs will no longer spawn on the surface of the overworld. If you want to toggle this, do /tweakskompass surfacespawn)</i></gray>
            """.trimIndent().mm()
        )
    }

    fun forceCleanup() {
        cancelAllTasks()
        plugin.bossBarManager.unregisterEntity(dragon)
        dragonTeam?.unregister()
        dragonTeam = null
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