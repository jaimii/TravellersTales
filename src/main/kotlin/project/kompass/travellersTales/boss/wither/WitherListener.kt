package project.kompass.travellersTales.boss.wither

import org.bukkit.Bukkit
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Wither
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys

class WitherListener(
    private val plugin: TravellersTales,
    private val manager: WitherManager
) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWitherSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (entity is Wither) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (entity.isValid) {
                    manager.handleWitherSpawn(entity)
                }
            })
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWitherDamaged(event: EntityDamageByEntityEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity != fight.wither) return

        val damager = event.damager
        val attackingPlayer: Player? = when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        // 1. Phase 1 Direct Non-Projectile Hit Counter
        if (damager is Player && !fight.inPhase2) {
            fight.triggerPhase1DirectHitCounter()
        }

        // 2. Burst Damage Counter & Phase Transitions
        if (attackingPlayer != null) {
            val healthBefore = fight.wither.health
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!fight.wither.isValid || fight.wither.isDead) return@Runnable
                val healthLost = (healthBefore - fight.wither.health).coerceAtLeast(0.0)
                fight.handleBurstDamageCheck(healthLost)
                fight.checkPhaseTransitions()
            })
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val pdc = entity.persistentDataContainer

        // Empty loot table for all Wither minions
        if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE) ||
            pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE)
        ) {
            event.drops.clear()
            event.droppedExp = 0
        }

        val fight = manager.activeFight ?: return

        // Killing a Wither Skeleton minion deals 10 arrow damage to the Wither
        if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE)) {
            val killer = entity.killer
            if (killer != null && fight.wither.isValid && !fight.wither.isDead) {
                val arrowSource = DamageSource.builder(DamageType.ARROW)
                    .withCausingEntity(killer)
                    .withDirectEntity(killer)
                    .build()
                fight.wither.damage(10.0, arrowSource)
                fight.checkPhaseTransitions()
            }
            return
        }

        // Wither Boss Death
        if (entity == fight.wither) {
            event.droppedExp = 0
            fight.onDeath(entity.location)
            manager.clearActiveFight(fight)
        }
    }
}