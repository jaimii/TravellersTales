package project.kompass.travellersTales.boss.golem

import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class ActiveDreadnoughtFight(
    private val plugin: TravellersTales,
    var golem: IronGolem,
    var config: DreadnoughtConfig
) {
    val golemId: UUID = golem.uniqueId
    val world: World
        get() = golem.world

    private val tasks = mutableListOf<BukkitTask>()
    private var patrolAnchor: Location = golem.location.clone()

    var isDying: Boolean = false
        private set
    private var isPerformingSmash: Boolean = false
    private var lastSmashMillis: Long = 0L
    private var lastBashMillis: Long = 0L
    private var fastAttackCooldownTicks: Int = 0

    // Performance target cache
    private var cachedTarget: LivingEntity? = null
    private var targetScanCooldownTicks: Int = 0

    // Beacon Energy Particle Palette
    private val beaconCyanColor = Color.fromRGB(0, 245, 255)
    private val beaconCyanGold = Particle.DustTransition(
        beaconCyanColor,              // Beacon Cyan
        Color.fromRGB(255, 215, 0),   // Dreadnought Gold
        1.7f
    )
    private val beaconOverloadRed = Particle.DustTransition(
        beaconCyanColor,
        Color.fromRGB(255, 50, 50),
        2.0f
    )

    fun start(playSpawnAnimation: Boolean = true, preserveCurrentHealth: Boolean = false) {
        configureGolemEntity(preserveCurrentHealth)
        plugin.bossBarManager.createBossBar(golem, config.bossBar)
        scheduleCoreLoops()
        if (playSpawnAnimation) {
            playBeaconSpawnAnimation()
        } else {
            world.playSound(golem.location, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.NEUTRAL, 2.5f, 1.0f)
        }
    }

    /**
     * Updates the live IronGolem entity reference after a chunk unload/reload.
     */
    fun rebindGolem(loadedGolem: IronGolem) {
        this.golem = loadedGolem
        if (!isDying) {
            isPerformingSmash = false
            configureGolemEntity(preserveCurrentHealth = true)
            plugin.bossBarManager.createBossBar(loadedGolem, config.bossBar)
        }
    }

    private fun ensureLiveGolem(): Boolean {
        if (golem.isValid) return true
        val live = Bukkit.getEntity(golemId) as? IronGolem
        if (live != null && live.isValid) {
            rebindGolem(live)
            return true
        }
        return false
    }

    private fun configureGolemEntity(preserveCurrentHealth: Boolean) {
        val savedHealth = if (preserveCurrentHealth) {
            golem.persistentDataContainer.get(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE)
                ?: golem.health.takeIf { it > 0.0 }
                ?: config.maxHealth
        } else {
            config.maxHealth
        }

        golem.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "dreadnought_golem")
        golem.customName(config.displayName.mm())
        golem.isCustomNameVisible = true
        golem.isPlayerCreated = true
        golem.isPersistent = true
        golem.removeWhenFarAway = false

        golem.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
        golem.health = savedHealth.coerceIn(1.0, config.maxHealth)
        golem.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, golem.health)

        golem.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = config.attackDamage
        golem.getAttribute(Attribute.ARMOR)?.baseValue = config.armor
        golem.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.movementSpeed
        golem.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange // 64.0
        golem.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = config.knockbackResistance
        golem.getAttribute(Attribute.STEP_HEIGHT)?.baseValue = 1.5
        golem.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 100.0

        // Permanent self-regeneration (ambient = true, particles = false)
        if (!isDying) {
            golem.addPotionEffect(
                PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 0, true, false, false)
            )
        }
    }

    // ========================================================================
    // BEACON SPAWN ANIMATION
    // ========================================================================

    private fun playBeaconSpawnAnimation() {
        val origin = golem.location.clone()
        val coreLoc = origin.clone().add(0.0, 1.4, 0.0)

        world.playSound(origin, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.NEUTRAL, 3.0f, 1.0f)
        world.playSound(origin, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.NEUTRAL, 3.0f, 1.2f)
        world.spawnParticle(Particle.FLASH, coreLoc, 2, 0.0, 0.0, 0.0, 0.0, beaconCyanColor)

        // Vertical Beacon Sky-Beam shooting 24 blocks upward
        for (y in 0..24) {
            val beamPt = origin.clone().add(0.0, y.toDouble(), 0.0)
            world.spawnParticle(Particle.END_ROD, beamPt, 4, 0.15, 0.3, 0.15, 0.02)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, beamPt, 6, 0.25, 0.3, 0.25, 0.0, beaconCyanGold)
        }

        // 30-Tick (1.5s) Beacon Core Power-Up Rings
        var tick = 0
        var introTask: BukkitTask? = null
        introTask = runTimer(1L, 1L) {
            tick++
            if (!ensureLiveGolem() || isDying || tick > 30) {
                introTask?.cancel()
                return@runTimer
            }

            val radius = 2.4 * (1.0 - (tick / 30.0))
            val yOffset = (tick / 30.0) * 2.2
            val center = golem.location.add(0.0, yOffset, 0.0)
            for (i in 0 until 12) {
                val angle = (tick * 0.25) + (i * Math.PI / 6.0)
                val pt = center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, beaconCyanGold)
                if (i % 3 == 0) {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.05, 0.05, 0.05, 0.0)
                }
            }
        }
    }

    // ========================================================================
    // CORE LOOPS: FASTER ATTACK SPEED, BEACON HUM, 16x16 GOLEM HEAL & SMASH AI
    // ========================================================================

    private fun scheduleCoreLoops() {
        // 1-Tick Loop: Rapid Attack Cadence with Cached Target System
        runTimer(1L, 1L) {
            if (isDying || isPerformingSmash) return@runTimer
            if (!ensureLiveGolem() || golem.isDead) return@runTimer

            // Always disallow targeting players
            if (golem.target is Player) {
                golem.target = null
            }

            if (fastAttackCooldownTicks > 0) fastAttackCooldownTicks--

            // Validate existing cached target
            val target = cachedTarget
            if (target == null || !target.isValid || target.isDead || !isHostileEnemy(target) ||
                target.world != world ||
                target.location.distanceSquared(golem.location) > config.followRange * config.followRange
            ) {
                cachedTarget = null
            }

            // Perform nearby entity lookup every 15 ticks when a new target is needed
            if (cachedTarget == null) {
                if (targetScanCooldownTicks > 0) {
                    targetScanCooldownTicks--
                } else {
                    targetScanCooldownTicks = 15
                    cachedTarget = resolveHostileTarget()
                    if (cachedTarget != null) {
                        golem.target = cachedTarget
                    }
                }
            }

            // Execute rapid melee cadence against cached target
            val current = cachedTarget ?: return@runTimer
            val distSq = golem.location.distanceSquared(current.location)
            if (distSq <= 14.0 && fastAttackCooldownTicks <= 0) {
                fastAttackCooldownTicks = config.attackCadenceTicks // 12 ticks
                golem.swingMainHand()
                golem.attack(current)
            }
        }

        // 20-Tick (1s) Loop: MaxCombatDistance (256.0), Health Sync, Pursuit & SmashMech Trigger
        runTimer(20L, 20L) {
            if (isDying || isPerformingSmash) return@runTimer
            if (!ensureLiveGolem() || golem.isDead) return@runTimer

            golem.persistentDataContainer.set(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE, golem.health)

            if (golem.world == patrolAnchor.world &&
                golem.location.distanceSquared(patrolAnchor) > config.maxCombatDistanceSquared
            ) {
                golem.teleport(patrolAnchor)
            }

            val target = cachedTarget ?: resolveHostileTarget() ?: return@runTimer
            cachedTarget = target
            golem.target = target

            val distSq = golem.location.distanceSquared(target.location)
            if (distSq > 9.0) {
                golem.pathfinder.moveTo(target, 1.15)
            }

            val now = System.currentTimeMillis()
            if (distSq <= config.smashGrabRangeSquared && (now - lastSmashMillis) >= config.smashCooldownMillis) {
                lastSmashMillis = now
                triggerGrabAndSlamAttack(target)
            }
        }

        // 40-Tick (2s) Loop: Audible Passive Beacon Hum & 16x16 Allied Golem Healing Aura
        runTimer(40L, 40L) {
            if (isDying) return@runTimer
            if (!ensureLiveGolem() || golem.isDead) return@runTimer

            val coreLoc = golem.location.add(0.0, 1.5, 0.0)
            world.playSound(coreLoc, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.NEUTRAL, 3.5f, 1.0f)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, coreLoc, 8, 0.35, 0.35, 0.35, 0.0, beaconCyanGold)
            world.spawnParticle(Particle.END_ROD, coreLoc, 3, 0.25, 0.25, 0.25, 0.01)

            if (!golem.hasPotionEffect(PotionEffectType.REGENERATION)) {
                golem.addPotionEffect(
                    PotionEffect(PotionEffectType.REGENERATION, PotionEffect.INFINITE_DURATION, 0, true, false, false)
                )
            }

            // Heal all other Iron Golems within a 16x16 radius
            val r = config.healAuraRadius
            golem.location.getNearbyEntitiesByType(IronGolem::class.java, r, r, r).forEach { allyGolem ->
                if (allyGolem == golem || !allyGolem.isValid || allyGolem.isDead) return@forEach
                allyGolem.addPotionEffect(
                    PotionEffect(PotionEffectType.REGENERATION, 100, 0, true, true, true)
                )
                world.spawnParticle(
                    Particle.HAPPY_VILLAGER,
                    allyGolem.location.add(0.0, 1.5, 0.0),
                    6, 0.5, 0.5, 0.5, 0.0
                )
            }
        }
    }

    // ========================================================================
    // GRAB-AND-SLAM ATTACK (SmashMech) WITH BOOSTED Y-KNOCKBACK
    // ========================================================================

    private fun triggerGrabAndSlamAttack(target: LivingEntity) {
        isPerformingSmash = true

        val dist = golem.location.distance(target.location)
        if (dist > 4.5) {
            val jumpDir = target.location.toVector().subtract(golem.location.toVector()).setY(0.0)
            if (jumpDir.lengthSquared() > 1e-4) jumpDir.normalize()
            golem.velocity = jumpDir.multiply((dist * 0.16).coerceAtMost(1.6)).setY(0.65)
        }

        val grabDelay = if (dist > 4.5) 12L else 1L
        runLater(grabDelay) {
            if (!ensureLiveGolem() || isDying) {
                isPerformingSmash = false
                return@runLater
            }

            val grabTarget = if (target.isValid && !target.isDead) target else resolveHostileTarget()
            if (grabTarget == null) {
                isPerformingSmash = false
                return@runLater
            }

            golem.swingMainHand()
            golem.velocity = Vector(0.0, 1.0, 0.0)
            world.playSound(golem.location, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.NEUTRAL, 2.5f, 0.6f)
            world.playSound(golem.location, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.NEUTRAL, 2.5f, 1.5f)

            var holdTick = 0
            var holdTask: BukkitTask? = null
            holdTask = runTimer(1L, 1L) {
                holdTick++
                if (!ensureLiveGolem() || isDying) {
                    holdTask?.cancel()
                    isPerformingSmash = false
                    return@runTimer
                }

                if (holdTick <= 22 && grabTarget.isValid && !grabTarget.isDead) {
                    val overheadLoc = golem.location.clone().add(0.0, 3.1, 0.0)
                    overheadLoc.yaw = grabTarget.location.yaw
                    overheadLoc.pitch = grabTarget.location.pitch
                    grabTarget.teleport(overheadLoc)
                    grabTarget.velocity = Vector(0.0, 0.0, 0.0)

                    world.spawnParticle(Particle.ELECTRIC_SPARK, overheadLoc.clone().add(0.0, 0.5, 0.0), 5, 0.4, 0.4, 0.4, 0.05)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, overheadLoc, 4, 0.3, 0.3, 0.3, 0.0, beaconCyanGold)
                }

                if (holdTick == 20) {
                    golem.velocity = Vector(0.0, -1.6, 0.0)
                }

                if (holdTick >= 26) {
                    holdTask?.cancel()
                    try {
                        executeSlamImpact(grabTarget)
                    } finally {
                        isPerformingSmash = false
                    }
                }
            }
        }
    }

    private fun executeSlamImpact(grabbedTarget: LivingEntity) {
        val forward = golem.location.direction.setY(0.0)
        if (forward.lengthSquared() > 1e-4) forward.normalize() else forward.setX(1.0)
        val slamOrigin = golem.location.clone().add(forward.multiply(1.5))
        slamOrigin.y = golem.location.y

        val groundBlockData = slamOrigin.clone().subtract(0.0, 1.0, 0.0).block.type
            .takeIf { it.isSolid }?.createBlockData() ?: Material.DIRT.createBlockData()

        val pierceSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(golem)
            .withDirectEntity(golem)
            .build()

        if (grabbedTarget.isValid && !grabbedTarget.isDead) {
            grabbedTarget.teleport(slamOrigin)
            grabbedTarget.damage(config.smashPrimaryDamage, pierceSource) // 50.0 damage
            // Boosted primary target vertical rebound
            grabbedTarget.velocity = Vector(0.0, 0.85, 0.0)
        }

        golem.swingMainHand()
        world.playSound(slamOrigin, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.NEUTRAL, 3.0f, 0.7f)
        world.playSound(slamOrigin, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.NEUTRAL, 2.5f, 0.8f)
        world.playSound(slamOrigin, Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.NEUTRAL, 2.0f, 0.5f)

        world.spawnParticle(Particle.EXPLOSION_EMITTER, slamOrigin, 1)
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, slamOrigin.clone().add(0.0, 0.4, 0.0), 3, 1.2, 0.2, 1.2, 0.0)
        world.spawnParticle(Particle.BLOCK, slamOrigin.clone().add(0.0, 0.8, 0.0), 200, 2.0, 0.5, 2.0, 0.15, groundBlockData)
        world.spawnParticle(Particle.DUST_PLUME, slamOrigin.clone().add(0.0, 0.3, 0.0), 80, 2.5, 0.3, 2.5, 0.06)

        val maxR = config.smashAoeRadius
        for (wave in 1..4) {
            val r = (maxR / 4.0) * wave
            runLater((wave * 2).toLong()) {
                val points = (r * 10).toInt().coerceAtLeast(16)
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = slamOrigin.clone().add(cos(angle) * r, 0.25, sin(angle) * r)
                    world.spawnParticle(Particle.BLOCK, pt, 5, 0.2, 0.15, 0.2, 0.1, groundBlockData)
                    if (i % 2 == 0) {
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, beaconCyanGold)
                    }
                }
            }
        }

        // Hostile-Only AoE Damage (10.0 armor-ignoring) & Boosted Upward Throw (Y = 1.55) within 8 blocks
        val radiusSq = maxR * maxR
        slamOrigin.getNearbyLivingEntities(maxR, 5.0, maxR).forEach { entity ->
            if (entity == golem || entity == grabbedTarget) return@forEach
            if (!isHostileEnemy(entity)) return@forEach

            val dx = entity.location.x - slamOrigin.x
            val dz = entity.location.z - slamOrigin.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                entity.damage(config.smashAoeDamage, pierceSource)
                val pushDir = entity.location.toVector().subtract(slamOrigin.toVector()).setY(0.0)
                if (pushDir.lengthSquared() > 1e-4) pushDir.normalize()
                entity.velocity = pushDir.multiply(1.3).setY(1.55)
            }
        }
    }

    // ========================================================================
    // DREADNOUGHT BASH (20 Hearts Damage + Great Knockback)
    // ========================================================================

    fun onMeleeAttack(target: LivingEntity, event: org.bukkit.event.entity.EntityDamageByEntityEvent) {
        if (!isHostileEnemy(target)) {
            event.isCancelled = true
            return
        }

        val now = System.currentTimeMillis()
        if ((now - lastBashMillis) < config.bashCooldownMillis) return
        lastBashMillis = now

        event.damage = config.bashDamage

        val tLoc = target.location.add(0.0, 1.0, 0.0)
        world.playSound(tLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.NEUTRAL, 2.0f, 1.0f)
        world.playSound(tLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.NEUTRAL, 1.5f, 1.0f)

        listOf(0L, 5L).forEach { delay ->
            runLater(delay) {
                if (!target.isValid) return@runLater
                val pLoc = target.location.add(0.0, 1.0, 0.0)
                world.spawnParticle(Particle.GUST, pLoc, 7, 0.9, 0.9, 0.9, 0.0)
                world.spawnParticle(Particle.CRIT, pLoc, 25, 0.6, 0.6, 0.6, 0.2)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pLoc, 20, 0.7, 0.7, 0.7, 0.0, beaconCyanGold)
            }
        }

        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 200, 6, false, true, true))

        val bashDir = target.location.toVector().subtract(golem.location.toVector()).setY(0.0)
        if (bashDir.lengthSquared() > 1e-4) bashDir.normalize() else bashDir.setX(1.0)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (target.isValid) {
                target.velocity = bashDir.clone().multiply(config.bashKnockbackStrength).setY(0.85)
            }
        })
    }

    // ========================================================================
    // BEACON DEATH ANIMATION (CHUNK-UNLOAD SAFE)
    // ========================================================================

    fun triggerBeaconDeathAnimation() {
        if (isDying) return
        isDying = true

        ensureLiveGolem()
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(golemId)

        // Strip Regeneration and all active potion effects immediately
        golem.clearActivePotionEffects()
        golem.health = 1.0
        golem.setAI(false)
        golem.isInvulnerable = true
        golem.velocity = Vector(0.0, 0.0, 0.0)

        var lastCoreLoc = golem.location.clone().add(0.0, 1.5, 0.0)
        world.playSound(lastCoreLoc, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.NEUTRAL, 3.0f, 1.0f)

        var tick = 0
        var deathTask: BukkitTask? = null
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick++
            // If the chunk unloads mid-death-animation or 40 ticks complete, finish climax & removal immediately!
            if (!ensureLiveGolem() || tick >= 40) {
                deathTask?.cancel()
                if (golem.isValid) {
                    lastCoreLoc = golem.location.clone().add(0.0, 1.5, 0.0)
                }
                detonateBeaconCoreClimax(lastCoreLoc)
                return@Runnable
            }

            val currentCore = golem.location.add(0.0, 1.5, 0.0)
            lastCoreLoc = currentCore.clone()
            val ringRadius = 0.5 + (tick / 40.0) * 2.0

            for (i in 0 until 10) {
                val angle = (tick * 0.3) + (i * Math.PI / 5.0)
                val pt = currentCore.clone().add(cos(angle) * ringRadius, sin(tick * 0.2) * 0.5, sin(angle) * ringRadius)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, beaconOverloadRed)
                world.spawnParticle(Particle.END_ROD, pt, 1, 0.02, 0.02, 0.02, 0.01)
            }

            if (tick % 10 == 0) {
                val pitch = (1.0f - (tick / 60.0f)).coerceAtLeast(0.5f)
                world.playSound(currentCore, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.NEUTRAL, 2.5f, pitch)
                world.playSound(currentCore, Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.NEUTRAL, 2.5f, pitch)
            }
        }, 1L, 1L)
    }

    private fun detonateBeaconCoreClimax(coreLoc: Location) {
        try {
            val targetWorld = coreLoc.world ?: world
            targetWorld.playSound(coreLoc, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.NEUTRAL, 3.5f, 0.55f)
            targetWorld.playSound(coreLoc, Sound.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 3.0f, 0.7f)
            targetWorld.playSound(coreLoc, Sound.ENTITY_IRON_GOLEM_DEATH, SoundCategory.NEUTRAL, 2.5f, 0.8f)

            targetWorld.spawnParticle(Particle.FLASH, coreLoc, 2, 0.0, 0.0, 0.0, 0.0, beaconCyanColor)
            targetWorld.spawnParticle(Particle.END_ROD, coreLoc, 120, 1.2, 1.2, 1.2, 0.25)
            targetWorld.spawnParticle(Particle.ELECTRIC_SPARK, coreLoc, 100, 1.5, 1.5, 1.5, 0.2)
            targetWorld.spawnParticle(Particle.BLOCK, coreLoc, 80, 1.0, 1.2, 1.0, 0.15, Material.BEACON.createBlockData())
            targetWorld.spawnParticle(Particle.BLOCK, coreLoc, 80, 1.0, 1.2, 1.0, 0.15, Material.IRON_BLOCK.createBlockData())

            // Drop 1x Beacon
            targetWorld.dropItemNaturally(coreLoc, ItemStack(Material.BEACON, 1)) { item ->
                item.isGlowing = true
                item.velocity = item.velocity.add(Vector(0.0, 0.25, 0.0))
            }
        } finally {
            forceCleanup(killGolem = true)
            plugin.dreadnoughtManager.activeFights.remove(golemId)
        }
    }

    // ========================================================================
    // HOSTILE TARGETING & CLEANUP HELPERS
    // ========================================================================

    fun isHostileEnemy(entity: LivingEntity): Boolean {
        if (entity == golem) return false
        if (entity is Player || entity is AbstractVillager || entity is Golem || entity is Animals || entity is Allay) {
            return false
        }
        if (entity is Tameable && entity.isTamed) return false
        return entity is Enemy || entity is Monster || entity is Slime || entity is Ghast || entity is Phantom || entity is Shulker
    }

    private fun resolveHostileTarget(): LivingEntity? {
        val current = golem.target
        if (current != null && current.isValid && !current.isDead && isHostileEnemy(current)) {
            return current
        }
        return golem.location.getNearbyLivingEntities(config.followRange)
            .filter { !it.isDead && isHostileEnemy(it) }
            .minByOrNull { it.location.distanceSquared(golem.location) }
    }

    fun onDeath() {
        forceCleanup(killGolem = false)
    }

    fun forceCleanup(killGolem: Boolean) {
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(golemId)
        if (killGolem) {
            if (golem.isValid) {
                golem.remove()
            } else {
                (Bukkit.getEntity(golemId) as? IronGolem)?.remove()
            }
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