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
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BossKillTracker

class WitherListener(
    private val plugin: TravellersTales,
    private val manager: WitherManager
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        val fight = manager.activeFight ?: return
        for (entity in event.entities) {
            if (entity is Wither && entity.uniqueId == fight.witherId) {
                fight.rebindWither(entity)
            }
        }
    }

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
    fun onWitherGeneralDamage(event: EntityDamageEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity.uniqueId != fight.witherId) return
        (event.entity as? Wither)?.let { fight.rebindWither(it) }

        if (fight.inIntro || fight.inMidpoint) {
            event.isCancelled = true
            return
        }

        val byEntity = event as? EntityDamageByEntityEvent
        if (byEntity != null) {
            val damager = byEntity.damager
            if (damager.uniqueId == fight.witherId ||
                (damager is Projectile && (damager.shooter as? org.bukkit.entity.Entity)?.uniqueId == fight.witherId)
            ) {
                event.isCancelled = true
                return
            }
        }

        val cap = fight.config.maxSingleHitDamage
        if (event.damage > cap) {
            event.damage = cap
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWitherDamagedByEntity(event: EntityDamageByEntityEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity.uniqueId != fight.witherId) return

        val damager = event.damager
        val attackingPlayer: Player? = when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        if (damager is Player && !fight.inPhase2) {
            fight.triggerPhase1DirectHitCounter()
        }

        if (attackingPlayer != null) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!fight.wither.isValid || fight.wither.isDead) return@Runnable
                fight.checkPhaseTransitions()
            })
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val pdc = entity.persistentDataContainer

        if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE) ||
            pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE)
        ) {
            event.drops.clear()
            event.droppedExp = 0
        }

        val fight = manager.activeFight ?: return

        if (pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE)) {
            val killer = entity.killer
            if (killer != null && fight.wither.isValid && !fight.wither.isDead && !fight.inIntro && !fight.inMidpoint) {
                val arrowSource = DamageSource.builder(DamageType.ARROW)
                    .withCausingEntity(killer)
                    .withDirectEntity(killer)
                    .build()
                fight.wither.damage(10.0, arrowSource)
                fight.checkPhaseTransitions()
            }
            return
        }

        if (entity.uniqueId == fight.witherId) {
            event.droppedExp = 0

            val participants = HashSet<Player>()
            entity.killer?.let { participants.add(it) }
            participants.addAll(entity.location.getNearbyPlayers(64.0))
            participants.forEach { BossKillTracker.recordBossKill(it, "the_wither") }

            fight.onDeath(entity.location)
            manager.clearActiveFight(fight)
        }
    }
}