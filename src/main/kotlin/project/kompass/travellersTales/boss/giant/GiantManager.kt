package project.kompass.travellersTales.boss.giant

import org.bukkit.*
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Giant
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BiomeValidator
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GiantManager(private val plugin: TravellersTales) {

    var config: GiantConfig = GiantConfig.load(plugin.config)
        private set

    val activeFights = HashMap<UUID, ActiveGiantFight>()
    var purpurGiantAiActive: Boolean = false
        private set

    private var trackedNightDay: Long = -1L
    private val spawnedPlayersTonight = HashSet<UUID>()

    private val dragonAdvancementKey = NamespacedKey.minecraft("end/kill_dragon")

    fun init() {
        purpurGiantAiActive = detectAndEnablePurpurGiantAi()

        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!config.enabled) return@Runnable
            checkNightlyPerPlayerSpawns()
        }, 200L, 200L)
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        config = GiantConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
            plugin.bossBarManager.createBossBar(fight.giant, config.bossBar)
        }
    }

    private fun detectAndEnablePurpurGiantAi(): Boolean {
        val purpurFile = File(Bukkit.getWorldContainer(), "purpur.yml")
        val isPurpur = try {
            Class.forName("org.purpurmc.purpur.PurpurConfig")
            true
        } catch (_: ClassNotFoundException) {
            purpurFile.exists()
        }

        if (!isPurpur) {
            plugin.logger.info("[Giant] Standard Paper detected — using built-in custom Giant AI.")
            return false
        }

        try {
            if (purpurFile.exists()) {
                val yaml = YamlConfiguration.loadConfiguration(purpurFile)
                yaml.set("world-settings.default.mobs.giant.have-ai", true)
                yaml.set("world-settings.default.mobs.giant.have-hostile-ai", true)
                yaml.set("world-settings.default.mobs.giant.movement-speed", config.movementSpeed)
                yaml.set("world-settings.default.mobs.giant.attack-damage", config.attackDamage)
                yaml.set("world-settings.default.mobs.giant.max-health", config.maxHealth)
                yaml.set("world-settings.default.mobs.giant.step-height", 2.0)
                yaml.save(purpurFile)
            }

            for (world in Bukkit.getWorlds()) {
                runCatching {
                    val handle = world.javaClass.getMethod("getHandle").invoke(world)
                    val purpurConfigField = handle.javaClass.fields.firstOrNull { it.name == "purpurConfig" }
                    val purpurConfig = purpurConfigField?.get(handle)
                    if (purpurConfig != null) {
                        purpurConfig.javaClass.getField("giantHaveAI").setBoolean(purpurConfig, true)
                        purpurConfig.javaClass.getField("giantHaveHostileAI").setBoolean(purpurConfig, true)
                    }
                }
            }

            plugin.logger.info("[Giant] Purpur detected — enabled purpur.yml Giant AI!")
            return true
        } catch (ex: Exception) {
            plugin.logger.warning("[Giant] Purpur detected, but live config reflection fell back to custom AI: ${ex.message}")
            return false
        }
    }

    fun hasKilledEnderDragon(player: Player): Boolean {
        val adv = Bukkit.getAdvancement(dragonAdvancementKey)
        val hasAdvancement = adv != null && player.getAdvancementProgress(adv).isDone
        val hasStatistic = player.getStatistic(Statistic.KILL_ENTITY, EntityType.ENDER_DRAGON) > 0
        return hasAdvancement || hasStatistic
    }

    fun isOnThreeDayCooldown(player: Player): Boolean {
        val until = player.persistentDataContainer.get(MobKeys.GIANT_COOLDOWN_UNTIL, PersistentDataType.LONG)
            ?: return false
        if (System.currentTimeMillis() >= until) {
            player.persistentDataContainer.remove(MobKeys.GIANT_COOLDOWN_UNTIL)
            return false
        }
        return true
    }

    fun applyThreeDayCooldown(player: Player) {
        val expiresAt = System.currentTimeMillis() + config.cooldownMillis
        player.persistentDataContainer.set(MobKeys.GIANT_COOLDOWN_UNTIL, PersistentDataType.LONG, expiresAt)
        plugin.travellerManager.deferNaturalBossesForThreeDays(player)
    }

    fun isPlayerEngagedOrSpawnedTonight(player: Player): Boolean {
        return player.uniqueId in spawnedPlayersTonight ||
                activeFights.values.any { it.huntedPlayerId == player.uniqueId }
    }

    fun recordGiantEncounterCooldowns(fight: ActiveGiantFight, killer: Player?) {
        killer?.let { applyThreeDayCooldown(it) }
        fight.huntedPlayerId?.let { id ->
            Bukkit.getPlayer(id)?.let { hunted -> applyThreeDayCooldown(hunted) }
        }
    }

    /**
     * When the Giant's chunk unloads, despawn the Giant cleanly and reset the hunted player's
     * timer back to its original 3-day cooldown so it does NOT spawn again the next night.
     */
    fun handleGiantChunkUnloaded(fight: ActiveGiantFight) {
        if (fight.isDying) return
        activeFights.remove(fight.giant.uniqueId)
        fight.huntedPlayerId?.let { id ->
            Bukkit.getPlayer(id)?.let { hunted ->
                applyThreeDayCooldown(hunted)
            }
        }
        fight.forceCleanup(killGiant = true)
    }

    private fun checkNightlyPerPlayerSpawns() {
        for (world in Bukkit.getWorlds()) {
            if (world.environment != World.Environment.NORMAL) continue

            val timeOfDay = world.time % 24000L
            val isNight = timeOfDay in 13000L..23000L
            if (!isNight) continue

            val currentDay = world.fullTime / 24000L
            if (currentDay != trackedNightDay) {
                trackedNightDay = currentDay
                spawnedPlayersTonight.clear()
            }

            for (player in world.players) {
                if (player.isDead || (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE)) continue
                if (player.uniqueId in spawnedPlayersTonight) continue

                if (abs(player.location.x) <= config.safeZoneHalfSize && abs(player.location.z) <= config.safeZoneHalfSize) {
                    continue
                }

                if (!BiomeValidator.isValidGiantBiome(player.location)) {
                    continue
                }

                // Mutual Exclusion & 3-Day Deferral Check
                if (plugin.travellerManager.isDeferredByRecentBoss(player)) {
                    continue
                }
                if (plugin.travellerManager.isPlayerEngagedOrDue(player)) {
                    continue
                }

                val killedDragon = hasKilledEnderDragon(player)
                if (!killedDragon) {
                    if (currentDay % config.preDragonIntervalDays != 0L) {
                        continue
                    }
                } else {
                    if (isOnThreeDayCooldown(player)) {
                        continue
                    }
                }

                val highestY = world.getHighestBlockYAt(player.location)
                if (player.location.blockY < highestY - 4) continue

                val spawnLoc = findSmartGiantSpawnLocation(player.location) ?: continue
                spawnedPlayersTonight.add(player.uniqueId)
                applyThreeDayCooldown(player)
                spawnGiant(spawnLoc, player.uniqueId)
            }
        }
    }

    fun findSmartGiantSpawnLocation(playerLoc: Location): Location? {
        val world = playerLoc.world ?: return null

        repeat(24) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val dist = Random.nextDouble(32.0, 48.0)
            val x = (playerLoc.x + cos(angle) * dist).toInt()
            val z = (playerLoc.z + sin(angle) * dist).toInt()

            if (abs(x) <= config.safeZoneHalfSize && abs(z) <= config.safeZoneHalfSize) {
                return@repeat
            }

            val y = world.getHighestBlockYAt(x, z) + 1
            val candidate = Location(world, x + 0.5, y.toDouble(), z + 0.5)

            if (!BiomeValidator.isValidGiantBiome(candidate)) {
                return@repeat
            }

            val isOutside = candidate.block.lightFromSky >= 10 && y >= world.getHighestBlockYAt(x, z)
            val isDark = candidate.block.lightFromBlocks <= 7

            if (isOutside && isDark && hasGiantClearance(candidate)) {
                val dir = playerLoc.toVector().subtract(candidate.toVector()).setY(0.0)
                if (dir.lengthSquared() > 1e-4) candidate.setDirection(dir)
                return candidate
            }
        }
        return null
    }

    private fun hasGiantClearance(loc: Location): Boolean {
        val center = loc.block

        val baseBlock = center.getRelative(0, -1, 0)
        if (!baseBlock.type.isSolid || baseBlock.isLiquid) return false
        val groundCorners = listOf(
            center.getRelative(-3, -1, -3), center.getRelative(3, -1, -3),
            center.getRelative(-3, -1, 3), center.getRelative(3, -1, 3)
        )
        if (groundCorners.any { !it.type.isSolid || it.isLiquid }) return false

        val perimeterCorners = listOf(
            center.getRelative(-3, 0, -3), center.getRelative(3, 0, -3),
            center.getRelative(-3, 0, 3), center.getRelative(3, 0, 3),
            center.getRelative(-3, 12, -3), center.getRelative(3, 12, -3),
            center.getRelative(-3, 12, 3), center.getRelative(3, 12, 3)
        )
        if (perimeterCorners.any { it.isLiquid || (it.type.isSolid && !Tag.LEAVES.isTagged(it.type)) }) {
            return false
        }

        for (x in -3..3) {
            for (z in -3..3) {
                for (y in 0..12) {
                    val b = center.getRelative(x, y, z)
                    if (b.isLiquid) return false
                    if (b.type.isSolid && !Tag.LEAVES.isTagged(b.type)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    fun spawnGiant(location: Location, huntedPlayerId: UUID? = null): ActiveGiantFight {
        val world = requireNotNull(location.world) { "World cannot be null" }
        val giant = world.spawn(location, Giant::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM)
        val fight = ActiveGiantFight(plugin, giant, config, huntedPlayerId, useFallbackAi = !purpurGiantAiActive)
        activeFights[giant.uniqueId] = fight
        fight.start()
        return fight
    }

    fun handleGiantKilledByPlayer(fight: ActiveGiantFight, killer: Player?) {
        activeFights.remove(fight.giant.uniqueId)
        fight.onDeath()
        recordGiantEncounterCooldowns(fight, killer)
    }

    fun handlePlayerKilledByGiant(fight: ActiveGiantFight, deadPlayer: Player) {
        applyThreeDayCooldown(deadPlayer)
    }

    fun getFight(entity: org.bukkit.entity.Entity): ActiveGiantFight? = activeFights[entity.uniqueId]

    fun getFightHuntingPlayer(playerId: UUID): ActiveGiantFight? =
        activeFights.values.firstOrNull { it.huntedPlayerId == playerId }

    fun unregisterGiant(id: UUID) {
        activeFights.remove(id)?.onDeath()
    }

    fun generateDrops(): List<ItemStack> = listOf(
        ItemStack(Material.ROTTEN_FLESH, 64),
        ItemStack(Material.ROTTEN_FLESH, 64),
        ItemStack(Material.BONE, 64)
    )

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killGiant = true) }
        activeFights.clear()
    }
}