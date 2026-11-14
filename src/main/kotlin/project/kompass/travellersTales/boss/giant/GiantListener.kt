package project.kompass.travellersTales.boss.giant

import org.bukkit.entity.Giant
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import project.kompass.travellersTales.util.ExperienceUtil

class GiantListener(private val manager: GiantManager) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        for (entity in event.entities) {
            if (entity is Giant) {
                val fight = manager.getFight(entity) ?: continue
                manager.handleGiantChunkUnloaded(fight)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onGiantDamage(event: EntityDamageEvent) {
        val giant = event.entity as? Giant ?: return
        val fight = manager.getFight(giant) ?: return

        if (event.cause == EntityDamageEvent.DamageCause.FALL) {
            event.isCancelled = true
            return
        }

        if (fight.isCrawlingOut || fight.isDying) {
            event.isCancelled = true
            return
        }

        if (giant.health - event.finalDamage <= 0.0) {
            event.damage = 0.0

            val killer: Player? = when (val damager = (event as? EntityDamageByEntityEvent)?.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> giant.killer
            }
            manager.recordGiantEncounterCooldowns(fight, killer)
            fight.triggerBloodyDeathAnimation()
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val lastDamage = player.lastDamageCause as? EntityDamageByEntityEvent

        val killerFight: ActiveGiantFight? = when (val damager = lastDamage?.damager) {
            is Giant -> manager.getFight(damager)
            else -> null
        } ?: manager.getFightHuntingPlayer(player.uniqueId)

        if (killerFight != null) {
            manager.handlePlayerKilledByGiant(killerFight, player)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onGiantDeath(event: EntityDeathEvent) {
        val giant = event.entity as? Giant ?: return
        val fight = manager.getFight(giant) ?: return

        manager.handleGiantKilledByPlayer(fight, giant.killer)

        if (!fight.isDying) {
            event.drops.clear()
            event.drops.addAll(manager.generateDrops())
            event.droppedExp = 0
            ExperienceUtil.spawnLevelOrbs(giant.location.add(0.0, 2.0, 0.0), manager.config.expLevelsDropped, orbCount = 15)
        } else {
            event.drops.clear()
            event.droppedExp = 0
        }
    }
}