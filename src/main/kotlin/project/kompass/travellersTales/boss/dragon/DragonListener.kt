package project.kompass.travellersTales.boss.dragon

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.util.mm

class DragonListener(
    private val plugin: TravellersTales,
    private val manager: DragonManager
) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDragonSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (entity is EnderDragon && entity.world.environment == World.Environment.THE_END) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (entity.isValid) {
                    manager.handleDragonSpawn(entity)
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDragonDamage(event: EntityDamageEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity != fight.dragon) return

        val damage = event.finalDamage
        event.damage = 0.0

        // Resolved on fight.dragon (LivingEntity) rather than event.entity (Entity)
        val hittingPlayer: Player? = when (val damager = (event as? EntityDamageByEntityEvent)?.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> fight.dragon.killer
        }

        val died = fight.applyDamage(damage, hittingPlayer)
        if (died) {
            event.isCancelled = false
            fight.dragon.health = 0.0
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDragonDealDamage(event: EntityDamageByEntityEvent) {
        val fight = manager.activeFight ?: return
        val target = event.entity as? Player ?: return

        val damager = event.damager
        val isFromDragon = damager == fight.dragon ||
                (damager is AreaEffectCloud && damager.ownerUniqueId == fight.dragon.uniqueId)

        if (isFromDragon) {
            fight.onDragonAttackHit(target, event.finalDamage)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onCrystalBreak(event: EntityDamageEvent) {
        val crystal = event.entity as? EnderCrystal ?: return
        if (crystal.world.environment != World.Environment.THE_END) return

        if (!manager.config.crystalCounterActionbar) return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val remaining = crystal.world.getEntitiesByClass(EnderCrystal::class.java).count { it.isValid && !it.isDead }
            if (remaining > 0) {
                crystal.world.players.forEach { p ->
                    p.sendActionBar("<bold><light_purple>$remaining</light_purple> <gray>End Crystals left</gray></bold>".mm())
                }
            } else {
                crystal.world.players.forEach { p ->
                    p.sendActionBar("<bold><light_purple>All End Crystals destroyed!</light_purple></bold>".mm())
                    p.playSound(p.location, Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 2f, 1f)
                }
            }
        })
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onDragonDeath(event: EntityDeathEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity == fight.dragon) {
            event.droppedExp = 0
            fight.onDeath(event.entity.killer)
            manager.clearActiveFight(fight)
        }
    }
}