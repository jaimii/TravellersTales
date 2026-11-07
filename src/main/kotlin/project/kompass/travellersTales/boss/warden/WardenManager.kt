package project.kompass.travellersTales.boss.warden

import org.bukkit.*
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.entity.Warden
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs

class WardenManager(private val plugin: TravellersTales) {

    var config: WardenConfig = WardenConfig.load(plugin.config)
        private set

    val activeFights = HashMap<UUID, ActiveWardenFight>()
    private val offlinePendingRespawns = HashSet<UUID>()

    fun reloadConfig() {
        plugin.reloadConfig()
        config = WardenConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
            plugin.bossBarManager.createBossBar(fight.warden, config.bossBar)
        }
    }

    fun hasActiveWardenInArea(loc: Location): Boolean {
        val r = config.shriekerSpawnLockRadius
        return activeFights.values.any { fight ->
            val w = fight.warden
            w.isValid && !w.isDead && w.world == loc.world &&
                    abs(w.location.x - loc.x) <= r &&
                    abs(w.location.z - loc.z) <= r
        }
    }

    /**
     * Summons The Warden via command (/tt summon warden) and plays its 60-tick sculk-emerging intro animation.
     */
    fun summonWardenWithIntro(location: Location, summoner: Player? = null): ActiveWardenFight? {
        val world = location.world ?: return null
        val warden = world.spawn(location, Warden::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { w ->
            w.pose = Pose.EMERGING
        }

        world.playSound(location, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 4.0f, 1.0f)
        world.playSound(location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, 3.0f, 0.8f)

        val sculkData = Material.SCULK.createBlockData()
        var tick = 0
        var emergeTask: org.bukkit.scheduler.BukkitTask? = null
        emergeTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick += 2
            if (!warden.isValid || tick > 60) {
                emergeTask?.cancel()
                return@Runnable
            }
            world.spawnParticle(Particle.BLOCK, location, 25, 0.6, 0.2, 0.6, sculkData)
            world.spawnParticle(Particle.SCULK_CHARGE_POP, location.clone().add(0.0, 0.5, 0.0), 8, 0.5, 0.3, 0.5, 0.02)
        }, 0L, 2L)

        return registerWarden(warden, summonerId = summoner?.uniqueId, playEmergeIntro = true)
    }

    // ========================================================================
    // PER-PLAYER PENDING WARDEN TRACKING (CHUNK UNLOAD -> 1-SHRIEKER RESPAWN)
    // ========================================================================

    fun markPendingWardenRespawn(playerId: UUID) {
        val player = Bukkit.getPlayer(playerId)
        if (player != null) {
            player.persistentDataContainer.set(MobKeys.WARDEN_PENDING_RESPAWN, PersistentDataType.BYTE, 1)
        } else {
            offlinePendingRespawns.add(playerId)
        }
    }

    fun syncOfflinePendingRespawn(player: Player) {
        if (offlinePendingRespawns.remove(player.uniqueId)) {
            player.persistentDataContainer.set(MobKeys.WARDEN_PENDING_RESPAWN, PersistentDataType.BYTE, 1)
        }
    }

    fun hasPendingWardenRespawn(player: Player): Boolean {
        return player.persistentDataContainer.has(MobKeys.WARDEN_PENDING_RESPAWN, PersistentDataType.BYTE) ||
                player.uniqueId in offlinePendingRespawns
    }

    fun clearPendingWardenRespawn(player: Player) {
        player.persistentDataContainer.remove(MobKeys.WARDEN_PENDING_RESPAWN)
        offlinePendingRespawns.remove(player.uniqueId)
    }

    fun handleWardenChunkUnloaded(fight: ActiveWardenFight) {
        if (fight.isDying) return
        activeFights.remove(fight.warden.uniqueId)

        fight.summonerId?.let { summonerUuid ->
            markPendingWardenRespawn(summonerUuid)
            Bukkit.getPlayer(summonerUuid)?.sendActionBar(
                "<bold><#00A8FF>[The Warden]</#00A8FF></bold> <gray>The Warden retreated into the deep dark... It awaits your next shriek.</gray>".mm()
            )
        }

        fight.forceCleanup(killWarden = true)
    }

    fun spawnPendingWardenFromShrieker(shriekerLoc: Location, player: Player): ActiveWardenFight? {
        if (!config.enabled) return null
        if (hasActiveWardenInArea(shriekerLoc)) return null
        if (getRemainingGraceSeconds(player) > 0L) return null

        val spawnLoc = findSafeSpotNearShrieker(shriekerLoc, player.location)
        val world = spawnLoc.world ?: return null

        clearPendingWardenRespawn(player)

        val warden = world.spawn(spawnLoc, Warden::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { w ->
            w.pose = Pose.EMERGING
        }

        world.playSound(spawnLoc, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 4.0f, 1.0f)
        player.sendActionBar(
            "<bold><#00A8FF>[The Warden]</#00A8FF></bold> <aqua>The Warden has emerged anew at full strength!</aqua>".mm()
        )

        return registerWarden(warden, summonerId = player.uniqueId, playEmergeIntro = true)
    }

    private fun findSafeSpotNearShrieker(shriekerLoc: Location, playerLoc: Location): Location {
        val offsets = listOf(
            Vector(1.5, 0.0, 0.0), Vector(-1.5, 0.0, 0.0),
            Vector(0.0, 0.0, 1.5), Vector(0.0, 0.0, -1.5),
            Vector(1.5, 0.0, 1.5), Vector(-1.5, 0.0, -1.5),
            Vector(0.0, 1.0, 0.0)
        )
        for (offset in offsets) {
            val candidate = shriekerLoc.clone().add(0.5, 0.0, 0.5).add(offset)
            for (dy in 2 downTo -2) {
                val check = candidate.clone().add(0.0, dy.toDouble(), 0.0)
                val ground = check.clone().add(0.0, -1.0, 0.0).block
                if (ground.type.isSolid && check.block.isPassable && check.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                    val dir = playerLoc.toVector().subtract(check.toVector())
                    if (dir.lengthSquared() > 1e-4) check.setDirection(dir)
                    return check
                }
            }
        }
        return shriekerLoc.clone().add(0.5, 1.0, 0.5)
    }

    // ========================================================================
    // 10-MINUTE POST-KILL GRACE PERIOD
    // ========================================================================

    fun applyGracePeriod(player: Player) {
        clearPendingWardenRespawn(player)
        if (config.gracePeriodSeconds <= 0L) return
        val expiresAt = System.currentTimeMillis() + (config.gracePeriodSeconds * 1000L)
        player.persistentDataContainer.set(MobKeys.WARDEN_GRACE_UNTIL, PersistentDataType.LONG, expiresAt)
    }

    fun getRemainingGraceSeconds(player: Player): Long {
        val expiresAt = player.persistentDataContainer.get(MobKeys.WARDEN_GRACE_UNTIL, PersistentDataType.LONG)
            ?: return 0L
        val remainingMillis = expiresAt - System.currentTimeMillis()
        if (remainingMillis <= 0L) {
            player.persistentDataContainer.remove(MobKeys.WARDEN_GRACE_UNTIL)
            return 0L
        }
        return (remainingMillis + 999L) / 1000L
    }

    fun findGracePeriodPlayerInArea(loc: Location): Player? {
        val r = config.shriekerSpawnLockRadius
        return loc.world?.players?.firstOrNull { player ->
            !player.isDead &&
                    abs(player.location.x - loc.x) <= r &&
                    abs(player.location.z - loc.z) <= r &&
                    getRemainingGraceSeconds(player) > 0L
        }
    }

    // ========================================================================
    // FIGHT REGISTRATION & LIFECYCLE
    // ========================================================================

    fun registerWarden(
        warden: Warden,
        summonerId: UUID? = null,
        playEmergeIntro: Boolean = false
    ): ActiveWardenFight? {
        if (!config.enabled) return null
        val existing = activeFights[warden.uniqueId]
        if (existing != null) return existing

        val resolvedSummonerId = summonerId ?: warden.location.getNearbyPlayers(32.0)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .minByOrNull { it.location.distanceSquared(warden.location) }
            ?.uniqueId

        resolvedSummonerId?.let { id ->
            Bukkit.getPlayer(id)?.let { clearPendingWardenRespawn(it) }
        }

        val fight = ActiveWardenFight(plugin, warden, config, resolvedSummonerId)
        activeFights[warden.uniqueId] = fight
        fight.start(playEmergeIntro)
        return fight
    }

    fun getFight(warden: Warden): ActiveWardenFight? = activeFights[warden.uniqueId]

    fun findNearestPhase2Fight(loc: Location, maxDistance: Double): ActiveWardenFight? {
        val maxDistSq = maxDistance * maxDistance
        return activeFights.values
            .filter { it.warden.isValid && !it.warden.isDead && it.warden.world == loc.world && it.currentPhase >= 2 }
            .minByOrNull { it.warden.location.distanceSquared(loc) }
            ?.takeIf { it.warden.location.distanceSquared(loc) <= maxDistSq }
    }

    fun unregisterWarden(wardenId: UUID) {
        val fight = activeFights.remove(wardenId) ?: return
        fight.summonerId?.let { id ->
            Bukkit.getPlayer(id)?.let { clearPendingWardenRespawn(it) }
            offlinePendingRespawns.remove(id)
        }
        fight.onDeath()
    }

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killWarden = true) }
        activeFights.clear()
    }
}