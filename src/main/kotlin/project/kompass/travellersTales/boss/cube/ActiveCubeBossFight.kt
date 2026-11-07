package project.kompass.travellersTales.boss.cube

import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ActiveCubeBossFight(
    private val plugin: TravellersTales,
    val type: CubeBossType,
    val world: World,
    val spawnOrigin: Location,
    var settings: CubeBossSettings
) {
    private val tasks = mutableListOf<BukkitTask>()

    // Active boss entities: 1 entity in Phase 1 (300 HP), 2 twin entities in Phase 2 (100 HP each)
    val activeCubes = mutableListOf<AbstractCubeMob>()
    private val slammingCubes = HashSet<UUID>()

    var currentPhase: Int = 1
        private set
    private var isCompleted: Boolean = false

    fun start() {
        val mainBoss = spawnBossCube(
            location = spawnOrigin,
            size = settings.phase1Size,
            maxHealth = settings.phase1Health,
            nameMiniMessage = settings.displayName
        )
        activeCubes.add(mainBoss)
        plugin.bossBarManager.createBossBar(mainBoss, settings.bossBar)
        scheduleCoreLoops()
    }

    private fun spawnBossCube(location: Location, size: Int, maxHealth: Double, nameMiniMessage: String): AbstractCubeMob {
        return world.spawn(location, type.entityClass, CreatureSpawnEvent.SpawnReason.CUSTOM) { cube ->
            cube.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, type.mobId)
            cube.size = size
            cube.customName(nameMiniMessage.mm())
            cube.isCustomNameVisible = true
            cube.isPersistent = true
            cube.removeWhenFarAway = false

            // Set health & combat stats AFTER setting size
            cube.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
            cube.health = maxHealth
            cube.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = settings.followRange
            cube.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = if (currentPhase == 1) 0.75 else 0.45
            cube.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = if (currentPhase == 1) 12.0 else 7.0

            // Immune to fall damage
            cube.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 1000.0
            cube.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue = 0.0
        }
    }

    private fun scheduleCoreLoops() {
        // 2-Tick Loop: Ambient Particles
        runTimer(2L, 2L) {
            if (isCompleted) return@runTimer

            for (cube in activeCubes.toList()) {
                if (!cube.isValid || cube.isDead) continue

                val center = cube.location.add(0.0, cube.height * 0.5, 0.0)
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    center,
                    4, cube.width * 0.4, cube.height * 0.4, cube.width * 0.4, 0.0,
                    type.dustTransition
                )
                world.spawnParticle(
                    type.accentParticle,
                    center,
                    2, cube.width * 0.35, cube.height * 0.35, cube.width * 0.35, 0.02
                )
            }
        }

        // 20-Tick (1s) Loop: MaxCombatDistance (256.0) & Target Acquisition
        runTimer(20L, 20L) {
            if (isCompleted) return@runTimer

            for (cube in activeCubes.toList()) {
                if (!cube.isValid || cube.isDead) continue

                if (cube.location.distanceSquared(spawnOrigin) > settings.maxCombatDistanceSquared) {
                    cube.teleport(spawnOrigin)
                }

                val target = resolveTarget(cube)
                if (target != null) {
                    cube.target = target
                }
            }
        }

        // Special Attack Loop: Sky-High Jump & Ground Slam
        runTimer(settings.slamIntervalTicks, settings.slamIntervalTicks) {
            if (isCompleted) return@runTimer

            for (cube in activeCubes.toList()) {
                if (!cube.isValid || cube.isDead) continue
                if (cube.uniqueId in slammingCubes || !cube.isOnGround) continue

                val target = resolveTarget(cube) ?: continue
                if (cube.location.distanceSquared(target.location) <= 1024.0) {
                    performSkyHighJumpSlam(cube, target)
                }
            }
        }
    }

    fun onBossDealAttack(attackerCube: AbstractCubeMob, victim: LivingEntity) {
        if (type == CubeBossType.WORLD_SLIME) {
            victim.addPotionEffect(
                PotionEffect(PotionEffectType.OOZING, settings.debuffDurationTicks, 0, false, true, true)
            )
            world.playSound(victim.location, Sound.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 1.5f, 0.7f)
            spawnNormalMinions(attackerCube.location, count = 1, size = 2)
        } else {
            victim.fireTicks = victim.fireTicks.coerceAtLeast(settings.debuffDurationTicks)
            world.playSound(victim.location, Sound.ITEM_FIRECHARGE_USE, SoundCategory.HOSTILE, 1.5f, 0.8f)
            spawnNormalMinions(attackerCube.location, count = 1, size = 2)
        }
    }

    private fun performSkyHighJumpSlam(cube: AbstractCubeMob, target: LivingEntity) {
        slammingCubes.add(cube.uniqueId)

        val launchOrigin = cube.location.clone()
        world.playSound(launchOrigin, Sound.ENTITY_SLIME_JUMP, SoundCategory.HOSTILE, 3.0f, 0.5f)
        world.playSound(launchOrigin, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.HOSTILE, 2.0f, 0.6f)
        world.spawnParticle(Particle.GUST_EMITTER_SMALL, launchOrigin, 3, 1.0, 0.2, 1.0, 0.0)
        world.spawnParticle(type.accentParticle, launchOrigin, 40, 1.2, 0.5, 1.2, 0.1)

        val toTarget = target.location.toVector().subtract(launchOrigin.toVector()).setY(0.0)
        if (toTarget.lengthSquared() > 1e-4) toTarget.normalize()
        cube.velocity = Vector(toTarget.x * 0.75, 1.75, toTarget.z * 0.75)

        var airborneTicks = 0
        var hasStartedPlunge = false
        var slamTask: BukkitTask? = null
        slamTask = runTimer(1L, 1L) {
            airborneTicks++
            if (!cube.isValid || cube.isDead || airborneTicks > 120) {
                slammingCubes.remove(cube.uniqueId)
                slamTask?.cancel()
                return@runTimer
            }

            val cLoc = cube.location
            world.spawnParticle(type.accentParticle, cLoc, 8, 0.8, 0.4, 0.8, 0.05)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cLoc, 6, 0.8, 0.4, 0.8, 0.0, type.dustTransition)

            if (airborneTicks == 22 && !hasStartedPlunge) {
                hasStartedPlunge = true
                if (target.isValid && target.world == world) {
                    val steer = target.location.toVector().subtract(cLoc.toVector()).setY(0.0)
                    if (steer.lengthSquared() > 1e-4) steer.normalize()
                    cube.velocity = Vector(steer.x * 0.55, -2.1, steer.z * 0.55)
                } else {
                    cube.velocity = Vector(0.0, -2.1, 0.0)
                }
                world.playSound(cLoc, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 2.5f, 1.5f)
            }

            if (airborneTicks > 10 && cube.isOnGround) {
                slammingCubes.remove(cube.uniqueId)
                slamTask?.cancel()
                executeGroundSlamImpact(cube)
            }
        }
    }

    private fun executeGroundSlamImpact(cube: AbstractCubeMob) {
        val origin = cube.location.clone()
        val radius = settings.slamRadius
        val damage = if (currentPhase == 1) settings.slamDamagePhase1 else settings.slamDamagePhase2

        val groundBlockData = if (type == CubeBossType.WORLD_SLIME) {
            Material.SLIME_BLOCK.createBlockData()
        } else {
            Material.MAGMA_BLOCK.createBlockData()
        }

        world.playSound(origin, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 3.5f, 0.7f)
        world.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.75f)
        if (type == CubeBossType.WORLD_SLIME) {
            world.playSound(origin, Sound.BLOCK_SLIME_BLOCK_BREAK, SoundCategory.HOSTILE, 3.0f, 0.6f)
        } else {
            world.playSound(origin, Sound.ENTITY_MAGMA_CUBE_SQUISH, SoundCategory.HOSTILE, 3.0f, 0.5f)
            world.playSound(origin, Sound.ITEM_FIRECHARGE_USE, SoundCategory.HOSTILE, 3.0f, 0.6f)
        }

        world.spawnParticle(Particle.GUST_EMITTER_LARGE, origin.clone().add(0.0, 0.4, 0.0), 3, 1.5, 0.2, 1.5, 0.0)
        world.spawnParticle(Particle.DUST_PLUME, origin.clone().add(0.0, 0.2, 0.0), 90, 3.0, 0.3, 3.0, 0.06)
        world.spawnParticle(type.accentParticle, origin.clone().add(0.0, 0.8, 0.0), 100, 2.5, 0.8, 2.5, 0.15)

        terraformSlamArea(origin, radius.toInt())

        for (wave in 1..4) {
            val r = (radius / 4.0) * wave
            runLater((wave * 2).toLong()) {
                val points = (r * 10).toInt().coerceAtLeast(16)
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = origin.clone().add(cos(angle) * r, 0.25, sin(angle) * r)
                    world.spawnParticle(Particle.BLOCK, pt, 5, 0.2, 0.15, 0.2, 0.1, groundBlockData)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 2, 0.15, 0.1, 0.15, 0.0, type.dustTransition)
                    if (i % 2 == 0) {
                        world.spawnParticle(type.accentParticle, pt.clone().add(0.0, 0.2, 0.0), 2, 0.1, 0.1, 0.1, 0.03)
                    }
                }
            }
        }

        val radiusSq = radius * radius
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(cube)
            .withDirectEntity(cube)
            .build()

        origin.getNearbyLivingEntities(radius, 5.0, radius).forEach { victim ->
            if (victim in activeCubes) return@forEach
            if (victim.persistentDataContainer.has(MobKeys.CUBE_BOSS_MINION, PersistentDataType.BYTE)) return@forEach

            val dx = victim.location.x - origin.x
            val dz = victim.location.z - origin.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                victim.damage(damage, unblockableSource)

                if (type == CubeBossType.WORLD_SLIME) {
                    victim.addPotionEffect(
                        PotionEffect(PotionEffectType.OOZING, settings.debuffDurationTicks, 0, false, true, true)
                    )
                } else {
                    victim.fireTicks = victim.fireTicks.coerceAtLeast(settings.debuffDurationTicks)
                }

                val pushDir = victim.location.toVector().subtract(origin.toVector()).setY(0.0)
                if (pushDir.lengthSquared() > 1e-4) pushDir.normalize()
                victim.velocity = victim.velocity.add(pushDir.multiply(0.75).setY(0.5))
            }
        }
    }

    private fun terraformSlamArea(center: Location, radius: Int) {
        val radiusSq = radius * radius
        val centerBlock = center.block

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                if ((x * x + z * z) > radiusSq) continue

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

                if (type == CubeBossType.MAGMA_CORE && Random.nextDouble() < 0.28) {
                    val surfaceY = world.getHighestBlockYAt(centerBlock.x + x, centerBlock.z + z)
                    if (kotlin.math.abs(surfaceY - centerBlock.y) <= 4) {
                        val fireBlock = world.getBlockAt(centerBlock.x + x, surfaceY + 1, centerBlock.z + z)
                        val below = fireBlock.getRelative(0, -1, 0)
                        if (fireBlock.type.isAir && below.type.isSolid) {
                            fireBlock.type = Material.FIRE
                        }
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

    fun handleCubeDeath(deadCube: AbstractCubeMob) {
        activeCubes.remove(deadCube)
        slammingCubes.remove(deadCube.uniqueId)
        plugin.bossBarManager.unregisterEntity(deadCube)

        val deathLoc = deadCube.location.clone()

        if (currentPhase == 1) {
            currentPhase = 2
            world.playSound(deathLoc, Sound.ENTITY_SLIME_SQUISH, SoundCategory.HOSTILE, 3.0f, 0.5f)
            world.playSound(deathLoc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, SoundCategory.HOSTILE, 2.0f, 0.8f)
            world.spawnParticle(type.accentParticle, deathLoc.clone().add(0.0, 1.5, 0.0), 120, 1.8, 1.5, 1.8, 0.15)
            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                deathLoc.clone().add(0.0, 1.5, 0.0),
                100, 1.8, 1.5, 1.8, 0.0,
                type.dustTransition
            )

            val offsets = listOf(Vector(2.2, 0.5, 0.0), Vector(-2.2, 0.5, 0.0))
            for (offset in offsets) {
                val twinLoc = deathLoc.clone().add(offset)
                val twin = spawnBossCube(
                    location = twinLoc,
                    size = settings.phase2Size,
                    maxHealth = settings.phase2Health,
                    nameMiniMessage = settings.splitDisplayName
                )
                twin.velocity = offset.clone().normalize().multiply(0.55).setY(0.5)
                activeCubes.add(twin)
                plugin.bossBarManager.createBossBar(twin, settings.bossBar.copy(title = settings.bossBar.phase2Title))
            }
            return
        }

        spawnNormalMinions(deathLoc, count = 5, size = 2)

        if (activeCubes.isEmpty()) {
            completeFightAndDropRewards(deathLoc)
        }
    }

    private fun spawnNormalMinions(origin: Location, count: Int, size: Int) {
        for (i in 0 until count) {
            val angle = (2.0 * Math.PI * i / count) + Random.nextDouble(-0.3, 0.3)
            val launchVec = Vector(cos(angle) * 0.45, 0.45, sin(angle) * 0.45)

            world.spawn(origin.clone().add(0.0, 0.5, 0.0), type.entityClass, CreatureSpawnEvent.SpawnReason.CUSTOM) { minion ->
                minion.persistentDataContainer.set(MobKeys.CUBE_BOSS_MINION, PersistentDataType.BYTE, 1)
                minion.size = size
                minion.velocity = launchVec
            }
        }
    }

    private fun completeFightAndDropRewards(finalLocation: Location) {
        if (isCompleted) return
        isCompleted = true
        cancelAllTasks()

        val dropLoc = finalLocation.clone().add(0.0, 0.8, 0.0)
        world.playSound(dropLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.5f, 1.0f)
        world.playSound(dropLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.9f)
        world.spawnParticle(Particle.FLASH, dropLoc, 2)
        world.spawnParticle(
            Particle.DUST_COLOR_TRANSITION,
            dropLoc,
            120, 1.5, 1.2, 1.5, 0.05,
            type.dustTransition
        )

        val boots = plugin.cubeBossManager.createSpecialBoots(type)
        world.dropItemNaturally(dropLoc, boots) { item ->
            item.isGlowing = true
            item.isInvulnerable = true
            item.velocity = Vector(0.0, 0.35, 0.0)
        }

        ExperienceUtil.spawnLevelOrbs(dropLoc, settings.expLevelsDropped, orbCount = 15)
        plugin.cubeBossManager.unregisterFight(this)
    }

    fun forceCleanup() {
        isCompleted = true
        cancelAllTasks()
        activeCubes.toList().forEach { cube ->
            plugin.bossBarManager.unregisterEntity(cube)
            if (cube.isValid) cube.remove()
        }
        activeCubes.clear()
        slammingCubes.clear()
    }

    private fun resolveTarget(cube: AbstractCubeMob): Player? {
        val current = cube.target as? Player
        if (current != null && current.isValid && !current.isDead &&
            (current.gameMode == GameMode.SURVIVAL || current.gameMode == GameMode.ADVENTURE)
        ) {
            return current
        }
        return cube.location.getNearbyPlayers(settings.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .minByOrNull { it.location.distanceSquared(cube.location) }
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