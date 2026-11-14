package project.kompass.travellersTales.boss.giant

import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ActiveGiantFight(
    private val plugin: TravellersTales,
    val giant: Giant,
    var config: GiantConfig,
    val huntedPlayerId: UUID? = null,
    private val useFallbackAi: Boolean
) {
    val world: World = giant.world
    private val tasks = mutableListOf<BukkitTask>()
    private val spawnOrigin: Location = giant.location.clone()

    var isCrawlingOut: Boolean = true
        private set
    var isDying: Boolean = false
        private set

    private var meleeCooldownTicks: Int = 0
    private var lastTrampleBlockLoc: Location? = null

    private val bloodDustTransition = Particle.DustTransition(
        Color.fromRGB(165, 5, 5),
        Color.fromRGB(55, 0, 0),
        2.2f
    )

    fun start() {
        configureGiantEntity()
        plugin.bossBarManager.createBossBar(giant, config.bossBar)
        playCrawlOutAnimation()
        scheduleCoreLoops()
    }

    private fun configureGiantEntity() {
        giant.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "giant")
        giant.customName(config.displayName.mm())
        giant.isCustomNameVisible = false

        // Giant is despawnable when its chunk unloads (GiantListener resets the player's timer cleanly)
        giant.isPersistent = false
        giant.removeWhenFarAway = true

        giant.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
        giant.health = config.maxHealth
        giant.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = config.attackDamage
        giant.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.movementSpeed
        giant.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange
        giant.getAttribute(Attribute.STEP_HEIGHT)?.baseValue = 2.0
        giant.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.85
        giant.getAttribute(Attribute.SPAWN_REINFORCEMENTS)?.baseValue = config.reinforcementsChance

        giant.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 1000.0
        giant.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue = 0.0

        huntedPlayerId?.let { id ->
            Bukkit.getPlayer(id)?.let { giant.target = it }
        }
    }

    private fun playCrawlOutAnimation() {
        isCrawlingOut = true
        val surfaceLoc = giant.location.clone()
        val buriedLoc = surfaceLoc.clone().subtract(0.0, 11.5, 0.0)

        giant.setAI(false)
        giant.isInvulnerable = true
        giant.setGravity(false)
        giant.teleport(buriedLoc)

        val surfaceBlockData = surfaceLoc.clone().subtract(0.0, 1.0, 0.0).block.type
            .takeIf { it.isSolid }?.createBlockData() ?: Material.DIRT.createBlockData()

        world.playSound(surfaceLoc, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 4.0f, 0.6f)

        var tick = 0
        var crawlTask: BukkitTask? = null
        crawlTask = runTimer(1L, 1L) {
            tick++
            if (!giant.isValid || isDying || tick > 60) {
                crawlTask?.cancel()
                if (giant.isValid && !isDying) {
                    giant.teleport(surfaceLoc)
                    giant.setGravity(true)
                    giant.isInvulnerable = false
                    giant.setAI(true)
                    isCrawlingOut = false
                    world.playSound(surfaceLoc, Sound.ENTITY_ZOMBIE_AMBIENT, SoundCategory.HOSTILE, 3.5f, 0.5f)
                }
                return@runTimer
            }

            val progress = tick / 60.0
            val currentY = buriedLoc.y + (11.5 * progress)
            val lurchYaw = surfaceLoc.yaw + (sin(tick * 0.35) * 18.0).toFloat()
            val stepLoc = Location(world, surfaceLoc.x, currentY, surfaceLoc.z, lurchYaw, -15f + (progress * 15f).toFloat())
            giant.teleport(stepLoc)

            if (tick % 4 == 0) {
                giant.swingMainHand()
                world.playSound(surfaceLoc, Sound.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.BLOCKS, 2.5f, 0.6f)
                world.playSound(surfaceLoc, Sound.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 2.5f, 0.5f)
            }
            if (tick % 15 == 0) {
                world.playSound(surfaceLoc, Sound.ENTITY_ZOMBIE_AMBIENT, SoundCategory.HOSTILE, 3.0f, 0.5f)
            }

            world.spawnParticle(Particle.BLOCK, surfaceLoc.clone().add(0.0, 0.3, 0.0), 35, 2.2, 0.4, 2.2, 0.15, surfaceBlockData)
            world.spawnParticle(Particle.DUST_PLUME, surfaceLoc.clone().add(0.0, 0.2, 0.0), 12, 1.8, 0.2, 1.8, 0.05)
        }
    }

    private fun scheduleCoreLoops() {
        runTimer(1L, 1L) {
            if (!giant.isValid || giant.isDead || isCrawlingOut || isDying) return@runTimer

            if (meleeCooldownTicks > 0) meleeCooldownTicks--

            if (useFallbackAi) {
                tickCustomGiantAi()
            }
        }

        runTimer(5L, 5L) {
            if (!giant.isValid || giant.isDead || isCrawlingOut || isDying) return@runTimer
            tickFoliageAndGrassTrample()
        }

        runTimer(20L, 20L) {
            if (!giant.isValid || giant.isDead || isCrawlingOut || isDying) return@runTimer

            if (giant.location.distanceSquared(spawnOrigin) > config.maxCombatDistance * config.maxCombatDistance) {
                giant.teleport(spawnOrigin)
            }
        }
    }

    private fun tickCustomGiantAi() {
        val target = resolveTarget() ?: return
        giant.target = target

        val gLoc = giant.location
        val tLoc = target.location
        val flatDiff = tLoc.toVector().subtract(gLoc.toVector()).setY(0.0)
        val distSq = flatDiff.lengthSquared()

        if (distSq > 0.01) {
            val dirLoc = gLoc.clone().setDirection(tLoc.toVector().subtract(gLoc.toVector()))
            giant.setRotation(dirLoc.yaw, dirLoc.pitch.coerceIn(-30f, 30f))
        }

        if (distSq > 12.0 && giant.isOnGround) {
            val stepSpeed = (config.movementSpeed * 0.45).coerceAtMost(0.38)
            val moveVec = flatDiff.clone().normalize().multiply(stepSpeed)
            val currentVel = giant.velocity

            val aheadBlock = gLoc.clone().add(flatDiff.clone().normalize().multiply(2.5)).block
            val yVel = if (aheadBlock.type.isSolid && aheadBlock.getRelative(0, 2, 0).isPassable) {
                0.42
            } else {
                currentVel.y
            }

            giant.velocity = Vector(moveVec.x, yVel, moveVec.z)
        }

        val vertDist = abs(tLoc.y - gLoc.y)
        if (distSq <= 23.0 && vertDist <= 6.5 && meleeCooldownTicks <= 0) {
            meleeCooldownTicks = 25
            giant.swingMainHand()
            world.playSound(gLoc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.HOSTILE, 2.0f, 0.6f)
            val dmgSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(giant)
                .withDirectEntity(giant)
                .build()
            target.damage(config.attackDamage, dmgSource)
        }
    }

    private fun tickFoliageAndGrassTrample() {
        val currentLoc = giant.location
        val center = currentLoc.block

        for (x in -2..2) {
            for (z in -2..2) {
                for (y in -1..0) {
                    val groundBlock = center.getRelative(x, y, z)
                    if (groundBlock.type == Material.GRASS_BLOCK ||
                        groundBlock.type == Material.MYCELIUM ||
                        groundBlock.type == Material.PODZOL
                    ) {
                        groundBlock.type = Material.DIRT
                    }
                }
            }
        }

        val last = lastTrampleBlockLoc
        if (last != null && last.blockX == center.x && last.blockY == center.y && last.blockZ == center.z) {
            return
        }
        lastTrampleBlockLoc = center.location

        for (x in -2..2) {
            for (z in -2..2) {
                for (y in 0..10) {
                    val b = center.getRelative(x, y, z)
                    if (isBreakableFoliageOrLeaf(b)) {
                        world.spawnParticle(Particle.BLOCK, b.location.add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, b.blockData)
                        b.breakNaturally()
                    }
                }
            }
        }
    }

    private fun isBreakableFoliageOrLeaf(block: Block): Boolean {
        val type = block.type
        if (type.isAir) return false
        return Tag.LEAVES.isTagged(type) ||
                Tag.FLOWERS.isTagged(type) ||
                Tag.REPLACEABLE_BY_TREES.isTagged(type) ||
                Tag.CROPS.isTagged(type) ||
                type == Material.BAMBOO ||
                type == Material.SUGAR_CANE ||
                type == Material.CACTUS ||
                type == Material.VINE ||
                type == Material.LILY_PAD ||
                type == Material.DEAD_BUSH ||
                type == Material.MOSS_CARPET ||
                type == Material.SWEET_BERRY_BUSH
    }

    fun triggerBloodyDeathAnimation() {
        if (isDying) return
        isDying = true

        cancelAllTasks()
        plugin.bossBarManager.unregisterEntity(giant)

        giant.health = 1.0
        giant.setAI(false)
        giant.isInvulnerable = true
        giant.velocity = Vector(0.0, 0.0, 0.0)

        val bloodBlockData = Material.REDSTONE_BLOCK.createBlockData()
        val fleshBlockData = Material.NETHER_WART_BLOCK.createBlockData()
        val goreBlockData = Material.CRIMSON_HYPHAE.createBlockData()
        val rottenFleshStack = ItemStack(Material.ROTTEN_FLESH)
        val boneStack = ItemStack(Material.BONE)

        var lastBaseLoc = giant.location.clone()
        world.playSound(lastBaseLoc, Sound.ENTITY_ZOMBIE_DEATH, SoundCategory.HOSTILE, 4.0f, 0.5f)

        var tick = 0
        var deathTask: BukkitTask? = null
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick++
            if (!giant.isValid || tick >= 30) {
                deathTask?.cancel()
                if (giant.isValid) {
                    lastBaseLoc = giant.location.clone()
                }
                detonateGoreClimax(lastBaseLoc, bloodBlockData, fleshBlockData, goreBlockData, rottenFleshStack, boneStack)
                return@Runnable
            }

            lastBaseLoc = giant.location.clone()
            val bodyLoc = giant.location.add(0.0, Random.nextDouble(2.0, 9.0), 0.0)
            giant.setRotation(giant.yaw + Random.nextFloat() * 30f - 15f, Random.nextFloat() * 30f - 15f)

            world.spawnParticle(Particle.BLOCK, bodyLoc, 25, 1.5, 2.0, 1.5, 0.15, bloodBlockData)
            world.spawnParticle(Particle.BLOCK, bodyLoc, 20, 1.5, 2.0, 1.5, 0.15, fleshBlockData)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, bodyLoc, 30, 1.8, 2.5, 1.8, 0.0, bloodDustTransition)
            world.spawnParticle(Particle.ITEM, bodyLoc, 10, 1.2, 1.5, 1.2, 0.2, rottenFleshStack)

            if (tick % 5 == 0) {
                world.playSound(bodyLoc, Sound.BLOCK_HONEY_BLOCK_BREAK, SoundCategory.HOSTILE, 3.0f, 0.5f)
                world.playSound(bodyLoc, Sound.ENTITY_WARDEN_DIG, SoundCategory.HOSTILE, 3.0f, 0.8f)
            }
        }, 1L, 1L)
    }

    private fun detonateGoreClimax(
        baseLoc: Location,
        bloodData: org.bukkit.block.data.BlockData,
        fleshData: org.bukkit.block.data.BlockData,
        goreData: org.bukkit.block.data.BlockData,
        fleshItem: ItemStack,
        boneItem: ItemStack
    ) {
        val torsoLoc = baseLoc.clone().add(0.0, 5.0, 0.0)

        world.playSound(torsoLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 0.6f)
        world.playSound(torsoLoc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 4.0f, 0.5f)
        world.playSound(torsoLoc, Sound.BLOCK_SLIME_BLOCK_BREAK, SoundCategory.HOSTILE, 4.0f, 0.5f)

        for (yOffset in listOf(2.0, 5.0, 8.0)) {
            val ruptureLoc = baseLoc.clone().add(0.0, yOffset, 0.0)
            world.spawnParticle(Particle.BLOCK, ruptureLoc, 140, 2.2, 2.0, 2.2, 0.3, bloodData)
            world.spawnParticle(Particle.BLOCK, ruptureLoc, 120, 2.2, 2.0, 2.2, 0.3, fleshData)
            world.spawnParticle(Particle.BLOCK, ruptureLoc, 80, 2.0, 1.8, 2.0, 0.25, goreData)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, ruptureLoc, 150, 2.5, 2.2, 2.5, 0.05, bloodDustTransition)
            world.spawnParticle(Particle.ITEM, ruptureLoc, 60, 1.8, 1.8, 1.8, 0.35, fleshItem)
            world.spawnParticle(Particle.ITEM, ruptureLoc, 40, 1.8, 1.8, 1.8, 0.35, boneItem)
        }

        spawnZombieBurst(torsoLoc, bloodData)

        val drops = listOf(
            ItemStack(Material.ROTTEN_FLESH, 64),
            ItemStack(Material.ROTTEN_FLESH, 64),
            ItemStack(Material.BONE, 64)
        )
        drops.forEach { stack ->
            world.dropItemNaturally(torsoLoc, stack)
        }

        ExperienceUtil.spawnLevelOrbs(torsoLoc, config.expLevelsDropped, orbCount = 15)

        val id = giant.uniqueId
        forceCleanup(killGiant = true)
        plugin.giantManager.activeFights.remove(id)
    }

    private fun spawnZombieBurst(origin: Location, bloodData: org.bukkit.block.data.BlockData) {
        val count = Random.nextInt(config.deathZombiesMin, config.deathZombiesMax + 1)
        val zombieTypes = listOf(
            EntityType.ZOMBIE,
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.DROWNED,
            EntityType.ZOMBIE_VILLAGER
        )

        for (i in 0 until count) {
            val angle = (2.0 * Math.PI * i / count) + Random.nextDouble(-0.2, 0.2)
            val horizSpeed = Random.nextDouble(0.45, 0.95)
            val launchVec = Vector(cos(angle) * horizSpeed, Random.nextDouble(0.45, 0.85), sin(angle) * horizSpeed)

            val chosenType = zombieTypes.random()
            val zombie = world.spawnEntity(origin, chosenType, CreatureSpawnEvent.SpawnReason.CUSTOM) as? Zombie ?: continue

            zombie.persistentDataContainer.set(MobKeys.GIANT_MINION, PersistentDataType.BYTE, 1)
            zombie.velocity = launchVec

            if (Random.nextDouble() < 0.25) {
                zombie.setBaby()
            }
            if (Random.nextDouble() < 0.35) {
                val weapons = listOf(Material.IRON_SWORD, Material.IRON_SHOVEL, Material.STONE_AXE)
                zombie.equipment.setItemInMainHand(ItemStack(weapons.random()))
            }

            world.spawnParticle(Particle.BLOCK, origin, 15, 0.4, 0.4, 0.4, 0.1, bloodData)
        }
    }

    fun onDeath() {
        forceCleanup(killGiant = false)
    }

    fun forceCleanup(killGiant: Boolean) {
        cancelAllTasks()
        plugin.bossBarManager.unregisterEntity(giant)
        if (killGiant && giant.isValid) {
            giant.remove()
        }
    }

    private fun resolveTarget(): LivingEntity? {
        huntedPlayerId?.let { id ->
            val p = Bukkit.getPlayer(id)
            if (p != null && p.isOnline && !p.isDead && p.world == world) {
                return p
            }
        }
        val current = giant.target
        if (current != null && current.isValid && !current.isDead) {
            if (current !is Player || isValidPlayer(current)) return current
        }
        return giant.location.getNearbyPlayers(config.followRange)
            .filter { isValidPlayer(it) }
            .minByOrNull { it.location.distanceSquared(giant.location) }
    }

    private fun isValidPlayer(player: Player): Boolean {
        return !player.isDead && (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE)
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