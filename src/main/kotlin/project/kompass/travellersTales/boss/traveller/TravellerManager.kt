package project.kompass.travellersTales.boss.traveller

import org.bukkit.*
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.WitherSkeleton
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BiomeValidator
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TravellerManager(private val plugin: TravellersTales) {

    var config: TravellerConfig = TravellerConfig.load(plugin.config)
        private set

    val activeFights = HashMap<UUID, ActiveTravellerFight>()
    private val lastActivityMillis = HashMap<UUID, Long>()
    private val deferredToDay = HashMap<UUID, Long>()
    private var lastSpawnedNightDay: Long = -1L

    private val dragonAdvancementKey = NamespacedKey.minecraft("end/kill_dragon")

    fun init() {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!config.enabled) return@Runnable
            checkNightQueueAndSpawn()
        }, 200L, 200L)
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        config = TravellerConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
            plugin.bossBarManager.createBossBar(fight.entity, config.bossBar)
        }
    }

    fun recordPlayerActivity(player: Player) {
        lastActivityMillis[player.uniqueId] = System.currentTimeMillis()
    }

    fun isPlayerAfk(player: Player): Boolean {
        val lastActive = lastActivityMillis[player.uniqueId] ?: System.currentTimeMillis()
        return (System.currentTimeMillis() - lastActive) >= config.afkTimeoutMillis
    }

    fun hasKilledEnderDragon(player: Player): Boolean {
        if (!config.requireDragonKilled) return true
        val adv = Bukkit.getAdvancement(dragonAdvancementKey)
        val hasAdvancement = adv != null && player.getAdvancementProgress(adv).isDone
        val hasStatistic = player.getStatistic(Statistic.KILL_ENTITY, EntityType.ENDER_DRAGON) > 0
        return hasAdvancement || hasStatistic
    }

    private fun getReferenceOverworld(): World {
        return Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NORMAL }
            ?: Bukkit.getWorlds().first()
    }

    fun getCurrentDayNumber(): Long = getReferenceOverworld().fullTime / 24000L

    fun isNighttime(): Boolean {
        val time = getReferenceOverworld().time % 24000L
        return time in 13000L..23000L
    }

    fun isDaytime(): Boolean = !isNighttime()

    /**
     * Shared check for both Traveller and Giant:
     * Returns true if the player is currently within their 3-day deferral window
     * after fighting/killing any boss or being killed by any boss.
     */
    fun isDeferredByRecentBoss(player: Player): Boolean {
        val deferUntilDay = player.persistentDataContainer.get(
            MobKeys.NIGHT_BOSS_DEFER_UNTIL_DAY,
            PersistentDataType.LONG
        ) ?: return false

        val currentDay = getCurrentDayNumber()
        if (currentDay >= deferUntilDay) {
            player.persistentDataContainer.remove(MobKeys.NIGHT_BOSS_DEFER_UNTIL_DAY)
            return false
        }
        return true
    }

    /**
     * Defers both The Traveller and The Giant from naturally spawning for [player]
     * for 3 full days (`currentDay + 3L`).
     * Called whenever any boss is killed or kills a player.
     */
    fun deferNaturalBossesForThreeDays(player: Player) {
        val targetDay = getCurrentDayNumber() + 3L
        player.persistentDataContainer.set(
            MobKeys.NIGHT_BOSS_DEFER_UNTIL_DAY,
            PersistentDataType.LONG,
            targetDay
        )
        deferredToDay[player.uniqueId] = targetDay
    }

    private fun isOnFiveDayCooldown(player: Player): Boolean {
        val until = player.persistentDataContainer.get(MobKeys.TRAVELLER_COOLDOWN_UNTIL, PersistentDataType.LONG)
            ?: return false
        if (System.currentTimeMillis() >= until) {
            player.persistentDataContainer.remove(MobKeys.TRAVELLER_COOLDOWN_UNTIL)
            return false
        }
        return true
    }

    fun applyFiveDayCooldown(player: Player) {
        val expiresAt = System.currentTimeMillis() + config.cooldownMillis
        player.persistentDataContainer.set(MobKeys.TRAVELLER_COOLDOWN_UNTIL, PersistentDataType.LONG, expiresAt)
        player.persistentDataContainer.remove(MobKeys.TRAVELLER_ESCAPED)
        player.setStatistic(Statistic.TIME_SINCE_REST, 0)
        deferredToDay.remove(player.uniqueId)
        deferNaturalBossesForThreeDays(player)
    }

    private fun hasEscapedPendingRespawn(player: Player): Boolean {
        return player.persistentDataContainer.has(MobKeys.TRAVELLER_ESCAPED, PersistentDataType.BYTE)
    }

    fun isPlayerEngagedOrDue(player: Player): Boolean {
        if (activeFights.values.any { it.huntedPlayerId == player.uniqueId }) return true
        if (!hasKilledEnderDragon(player) || isOnFiveDayCooldown(player) || isDeferredByRecentBoss(player)) return false
        val sleeplessEligible = player.getStatistic(Statistic.TIME_SINCE_REST) >= config.sleeplessTicksRequired
        val escapedEligible = hasEscapedPendingRespawn(player)
        return sleeplessEligible || escapedEligible
    }

    private fun checkNightQueueAndSpawn() {
        if (activeFights.values.any { !it.entity.isDead }) return
        if (!isNighttime()) return
        val currentDay = getCurrentDayNumber()
        if (lastSpawnedNightDay == currentDay) return

        val viablePlayers = mutableListOf<Player>()

        for (player in Bukkit.getOnlinePlayers()) {
            if (player.isDead || (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE)) continue
            if (!hasKilledEnderDragon(player)) continue
            if (isOnFiveDayCooldown(player)) continue

            // Prevent spawning within 3 days of any boss kill / player death to a boss
            if (isDeferredByRecentBoss(player)) continue
            if (plugin.giantManager.isPlayerEngagedOrSpawnedTonight(player)) continue

            val sleeplessEligible = player.getStatistic(Statistic.TIME_SINCE_REST) >= config.sleeplessTicksRequired
            val escapedEligible = hasEscapedPendingRespawn(player)
            if (!sleeplessEligible && !escapedEligible) {
                deferredToDay.remove(player.uniqueId)
                continue
            }

            if (!isInValidSpawnEnvironment(player)) continue

            val targetDay = deferredToDay[player.uniqueId]
            if (targetDay != null && currentDay < targetDay) continue

            if (isPlayerAfk(player)) {
                deferredToDay[player.uniqueId] = currentDay + 1L
                continue
            }

            viablePlayers.add(player)
        }

        if (viablePlayers.isEmpty()) return

        val chosenPlayer = viablePlayers.random()

        for (other in viablePlayers) {
            if (other.uniqueId != chosenPlayer.uniqueId) {
                deferredToDay[other.uniqueId] = currentDay + 1L
            }
        }

        val spawnLoc = findSafeSpawnNear(chosenPlayer.location) ?: return
        lastSpawnedNightDay = currentDay
        deferredToDay.remove(chosenPlayer.uniqueId)
        deferNaturalBossesForThreeDays(chosenPlayer)

        spawnTraveller(spawnLoc, chosenPlayer.uniqueId)
    }

    private fun isInValidSpawnEnvironment(player: Player): Boolean {
        val loc = player.location
        val world = loc.world ?: return false

        if (!BiomeValidator.isValidTravellerBiome(loc)) return false

        return if (world.environment == World.Environment.NORMAL) {
            if (abs(loc.x) <= 250.0 && abs(loc.z) <= 250.0) return false
            if (loc.block.lightFromSky < config.minSurfaceSkyLight) return false
            val highestY = world.getHighestBlockYAt(loc)
            loc.blockY >= highestY - 4
        } else {
            true
        }
    }

    private fun findSafeSpawnNear(playerLoc: Location): Location? {
        val world = playerLoc.world ?: return null

        repeat(30) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val dist = Random.nextDouble(32.0, 48.0)
            val x = (playerLoc.x + cos(angle) * dist).toInt()
            val z = (playerLoc.z + sin(angle) * dist).toInt()

            if (world.environment == World.Environment.NORMAL && abs(x) <= 250 && abs(z) <= 250) {
                return@repeat
            }

            if (world.environment == World.Environment.NORMAL) {
                val y = world.getHighestBlockYAt(x, z) + 1
                val candidate = Location(world, x + 0.5, y.toDouble(), z + 0.5)
                if (!BiomeValidator.isValidTravellerBiome(candidate)) {
                    return@repeat
                }

                val block = candidate.block
                val ground = block.getRelative(0, -1, 0)
                val isOutside = block.y >= world.getHighestBlockYAt(x, z)
                val isDark = block.lightFromBlocks <= 7

                if (isOutside && isDark && ground.type.isSolid && !ground.isLiquid &&
                    block.isPassable && block.getRelative(0, 1, 0).isPassable
                ) {
                    return candidate
                }
            } else {
                val baseCheck = Location(world, x + 0.5, playerLoc.y, z + 0.5)
                for (dy in 4 downTo -5) {
                    val check = baseCheck.clone().add(0.0, dy.toDouble(), 0.0)
                    val ground = check.clone().add(0.0, -1.0, 0.0).block
                    if (ground.type.isSolid && !ground.isLiquid && ground.type != Material.BEDROCK &&
                        check.block.isPassable && check.clone().add(0.0, 1.0, 0.0).block.isPassable
                    ) {
                        return check
                    }
                }
            }
        }
        return null
    }

    fun spawnTraveller(location: Location, huntedPlayerId: UUID? = null): ActiveTravellerFight {
        val world = requireNotNull(location.world) { "World cannot be null" }
        val skeleton = world.spawn(location, WitherSkeleton::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM)
        val fight = ActiveTravellerFight(plugin, skeleton, config, huntedPlayerId)
        activeFights[skeleton.uniqueId] = fight
        fight.start()

        Bukkit.broadcast(config.spawnAnnouncement.mm())
        return fight
    }

    fun handlePlayerEscaped(fight: ActiveTravellerFight) {
        activeFights.remove(fight.travellerId)
        fight.playRiftVanishAndCleanup()

        val targetId = fight.huntedPlayerId ?: return
        val nextNightDay = getCurrentDayNumber() + 1L
        deferredToDay[targetId] = nextNightDay

        Bukkit.getPlayer(targetId)?.let { player ->
            player.persistentDataContainer.set(MobKeys.TRAVELLER_ESCAPED, PersistentDataType.BYTE, 1)
            player.persistentDataContainer.set(MobKeys.NIGHT_BOSS_DEFER_UNTIL_DAY, PersistentDataType.LONG, nextNightDay)
        }
    }

    fun handleTravellerKilledByPlayer(fight: ActiveTravellerFight, killer: Player?) {
        activeFights.remove(fight.travellerId)
        fight.onDeath()

        killer?.let { applyFiveDayCooldown(it) }
        fight.huntedPlayerId?.let { id ->
            Bukkit.getPlayer(id)?.let { applyFiveDayCooldown(it) }
        }
    }

    fun handlePlayerKilledByTraveller(fight: ActiveTravellerFight, deadPlayer: Player) {
        applyFiveDayCooldown(deadPlayer)
        activeFights.remove(fight.travellerId)
        fight.playRiftVanishAndCleanup()
    }

    fun getFight(entity: org.bukkit.entity.Entity): ActiveTravellerFight? {
        val fight = activeFights[entity.uniqueId]
        if (fight != null && entity is WitherSkeleton && entity.isValid && fight.entity !== entity) {
            fight.rebindEntity(entity)
        }
        return fight
    }

    fun getFightHuntingPlayer(playerId: UUID): ActiveTravellerFight? =
        activeFights.values.firstOrNull { it.huntedPlayerId == playerId }

    fun generateDrops(): List<ItemStack> {
        val drops = mutableListOf<ItemStack>()
        drops.add(ItemStack(Material.GOLD_INGOT, 32))

        val roll = Random.nextDouble()
        val bonusDrop = when {
            roll < 0.20 -> ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1)
            roll < 0.55 -> ItemStack(Material.ANCIENT_DEBRIS, 4)
            else -> ItemStack(Material.NETHERITE_SCRAP, 2)
        }
        drops.add(bonusDrop)

        return drops
    }

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killEntity = true) }
        activeFights.clear()
    }
}