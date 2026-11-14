package project.kompass.travellersTales.boss.bar

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Boss
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.abs

class BossBarManager : Listener {

    private class TrackedBossBar(
        var boss: LivingEntity,
        val bar: BossBar,
        var settings: BossBarSettings,
        val viewers: MutableSet<UUID> = HashSet()
    )

    private val activeBars = HashMap<UUID, TrackedBossBar>()

    companion object {
        private const val TICK_PERIOD = 10L
    }

    fun init(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 0L, TICK_PERIOD)
    }

    fun createBossBar(boss: LivingEntity, settings: BossBarSettings): BossBar? {
        hideVanillaBar(boss)

        if (!settings.enabled) return null

        val existing = activeBars[boss.uniqueId]
        if (existing != null) {
            existing.boss = boss
            existing.settings = settings
            existing.bar.name(settings.title.mm())
            existing.bar.color(settings.color)
            existing.bar.overlay(settings.overlay)
            updateBossBarProgress(existing)
            updateBossBarVisibility(existing)
            return existing.bar
        }

        val bar = BossBar.bossBar(
            settings.title.mm(),
            1.0f,
            settings.color,
            settings.overlay,
            settings.flags
        )

        val tracked = TrackedBossBar(boss, bar, settings)
        activeBars[boss.uniqueId] = tracked
        updateBossBarProgress(tracked)
        updateBossBarVisibility(tracked)
        return bar
    }

    fun rebindEntity(boss: LivingEntity) {
        hideVanillaBar(boss)
        val tracked = activeBars[boss.uniqueId] ?: return
        tracked.boss = boss
        updateBossBarProgress(tracked)
        updateBossBarVisibility(tracked)
    }

    private fun hideVanillaBar(boss: LivingEntity) {
        if (boss is EnderDragon) {
            boss.world.enderDragonBattle?.bossBar?.apply {
                isVisible = false
                removeAll()
            }
        }
        if (boss is Boss) {
            boss.bossBar?.apply {
                isVisible = false
                removeAll()
            }
        }
    }

    fun updateBarAppearance(
        boss: LivingEntity,
        titleMiniMessage: String? = null,
        color: BossBar.Color? = null,
        overlay: BossBar.Overlay? = null
    ) {
        val tracked = activeBars[boss.uniqueId] ?: return
        tracked.boss = boss
        titleMiniMessage?.let { tracked.bar.name(it.mm()) }
        color?.let { tracked.bar.color(it) }
        overlay?.let { tracked.bar.overlay(it) }
    }

    fun unregisterEntity(boss: LivingEntity) {
        unregisterById(boss.uniqueId)
    }

    fun unregisterById(bossId: UUID) {
        val tracked = activeBars.remove(bossId) ?: return
        for (viewerId in tracked.viewers) {
            Bukkit.getPlayer(viewerId)?.hideBossBar(tracked.bar)
        }
        tracked.viewers.clear()
    }

    fun clearAllBars() {
        for (bossId in activeBars.keys.toList()) {
            unregisterById(bossId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is LivingEntity) {
                val tracked = activeBars[entity.uniqueId]
                if (tracked != null) {
                    tracked.boss = entity
                    hideVanillaBar(entity)
                    updateBossBarProgress(tracked)
                    updateBossBarVisibility(tracked)
                }
            }
        }
    }

    @EventHandler
    fun onBossDeath(event: EntityDeathEvent) {
        unregisterById(event.entity.uniqueId)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        activeBars.values.forEach { tracked ->
            if (tracked.viewers.remove(player.uniqueId)) {
                player.hideBossBar(tracked.bar)
            }
        }
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        val player = event.player
        activeBars.values.forEach { tracked ->
            if (player.world != tracked.boss.world && tracked.viewers.remove(player.uniqueId)) {
                player.hideBossBar(tracked.bar)
            }
        }
    }

    private fun tick() {
        if (activeBars.isEmpty()) return

        for (bossId in activeBars.keys.toList()) {
            val tracked = activeBars[bossId] ?: continue
            var boss = tracked.boss

            // NOTE: In Paper, CraftEntity#isDead() returns true when a chunk unloads (RemovalReason.UNLOADED_TO_CHUNK)!
            // Only unregister if health <= 0.0; if !boss.isValid, keep the bar and wait for the chunk to reload!
            if (boss.health <= 0.0) {
                unregisterById(bossId)
                continue
            }

            if (!boss.isValid) {
                val live = Bukkit.getEntity(bossId) as? LivingEntity
                if (live != null && live.isValid) {
                    if (live.health <= 0.0) {
                        unregisterById(bossId)
                        continue
                    }
                    tracked.boss = live
                    boss = live
                } else {
                    // Chunk is currently unloaded: hide bar from viewers temporarily without deleting it!
                    syncBarPlayers(tracked, emptySet())
                    continue
                }
            }

            hideVanillaBar(boss)
            updateBossBarProgress(tracked)
            updateBossBarVisibility(tracked)
        }
    }

    private fun updateBossBarProgress(tracked: TrackedBossBar) {
        val boss = tracked.boss
        val maxHealth = boss.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val progress = (boss.health / maxHealth).toFloat().coerceIn(0.0f, 1.0f)

        if (abs(tracked.bar.progress() - progress) > 1e-4f) {
            tracked.bar.progress(progress)
        }
    }

    private fun updateBossBarVisibility(tracked: TrackedBossBar) {
        val boss = tracked.boss
        val bossWorld = boss.world
        val bossLoc = boss.location
        val radiusSq = tracked.settings.radiusSquared

        val targetPlayers = HashSet<Player>()
        for (player in bossWorld.players) {
            if (player.world != bossWorld) continue
            if (player.location.distanceSquared(bossLoc) <= radiusSq) {
                targetPlayers.add(player)
            }
        }

        syncBarPlayers(tracked, targetPlayers)
    }

    private fun syncBarPlayers(tracked: TrackedBossBar, targetPlayers: Set<Player>) {
        val targetIds = targetPlayers.mapTo(HashSet(targetPlayers.size)) { it.uniqueId }

        val iterator = tracked.viewers.iterator()
        while (iterator.hasNext()) {
            val currentId = iterator.next()
            if (currentId !in targetIds) {
                Bukkit.getPlayer(currentId)?.hideBossBar(tracked.bar)
                iterator.remove()
            }
        }

        for (target in targetPlayers) {
            if (tracked.viewers.add(target.uniqueId)) {
                target.showBossBar(tracked.bar)
            }
        }
    }
}