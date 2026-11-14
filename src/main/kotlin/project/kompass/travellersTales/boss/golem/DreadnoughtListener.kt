package project.kompass.travellersTales.boss.golem

import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.IronGolem
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.SpectralArrow
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.world.EntitiesLoadEvent
import project.kompass.travellersTales.TravellersTales

class DreadnoughtListener(
    private val plugin: TravellersTales,
    private val manager: DreadnoughtManager
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is IronGolem && manager.isDreadnoughtEntity(entity)) {
                manager.rebindOrRestoreGolem(entity)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onStructureBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        Bukkit.getScheduler().runTask(plugin, Runnable {
            manager.tryConstructFromBlockPlace(block)
        })
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDreadnoughtTargetLiving(event: EntityTargetLivingEntityEvent) {
        val fight = manager.getFight(event.entity) ?: return
        val target = event.target ?: return

        if (target is Player || !fight.isHostileEnemy(target)) {
            event.isCancelled = true
            event.target = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDreadnoughtTargetGeneral(event: EntityTargetEvent) {
        val fight = manager.getFight(event.entity) ?: return
        val target = event.target as? LivingEntity ?: return

        if (target is Player || !fight.isHostileEnemy(target)) {
            event.isCancelled = true
            event.target = null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onArrowHitDreadnought(event: ProjectileHitEvent) {
        val hitEntity = event.hitEntity ?: return
        val fight = manager.getFight(hitEntity) ?: return
        val arrow = event.entity as? AbstractArrow ?: return

        if (arrow.shooter == fight.golem) return

        event.isCancelled = true
        reflectArrowBack(fight.golem, arrow)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDreadnoughtCombat(event: EntityDamageByEntityEvent) {
        // Case 1: Dreadnought Golem is dealing damage
        val attackerFight = manager.getFight(event.damager)
        if (attackerFight != null) {
            val victim = event.entity as? LivingEntity ?: return
            if (victim is Player || !attackerFight.isHostileEnemy(victim)) {
                event.isCancelled = true
                return
            }
            attackerFight.onMeleeAttack(victim, event)
            return
        }

        // Case 2: Dreadnought Golem is being damaged
        val damagedFight = manager.getFight(event.entity) ?: return
        val damager = event.damager

        if (damager is AbstractArrow) {
            event.isCancelled = true
            if (damager.shooter != damagedFight.golem) {
                reflectArrowBack(damagedFight.golem, damager)
            }
            return
        }

        val attackingPlayer: Player? = when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        if (attackingPlayer != null) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (damagedFight.golem.isValid && damagedFight.golem.target is Player) {
                    damagedFight.golem.target = null
                }
            })
        }
    }

    private fun reflectArrowBack(golem: IronGolem, arrow: AbstractArrow) {
        try {
            val incomingVelocity = arrow.velocity
            val speed = incomingVelocity.length().coerceAtLeast(1.2)
            val shooter = arrow.shooter as? LivingEntity

            val reflectDir = if (shooter != null && shooter.isValid && shooter.world == golem.world) {
                shooter.location.clone().add(0.0, shooter.height * 0.65, 0.0)
                    .toVector()
                    .subtract(arrow.location.toVector())
                    .normalize()
            } else if (incomingVelocity.lengthSquared() > 1e-4) {
                incomingVelocity.clone().multiply(-1.0).normalize()
            } else {
                golem.location.direction.normalize()
            }

            val newVelocity = reflectDir.clone().multiply(speed)
            val spawnLoc = arrow.location.clone().add(reflectDir.clone().multiply(0.9))
            spawnLoc.direction = newVelocity

            val world = golem.world
            val spawned = world.spawnEntity(spawnLoc, arrow.type, CreatureSpawnEvent.SpawnReason.CUSTOM) as? AbstractArrow
            if (spawned != null) {
                spawned.shooter = golem
                spawned.velocity = newVelocity
                spawned.damage = arrow.damage
                spawned.isCritical = arrow.isCritical
                spawned.pierceLevel = arrow.pierceLevel
                spawned.fireTicks = arrow.fireTicks
                spawned.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED

                if (spawned is Arrow && arrow is Arrow) {
                    arrow.basePotionType?.let { spawned.basePotionType = it }
                    arrow.customEffects.forEach { effect ->
                        spawned.addCustomEffect(effect, true)
                    }
                    arrow.color?.let { spawned.color = it }
                } else if (spawned is SpectralArrow && arrow is SpectralArrow) {
                    spawned.glowingTicks = arrow.glowingTicks
                } else if (spawned is Trident && arrow is Trident) {
                    spawned.itemStack = arrow.itemStack.clone()
                }
            }

            world.playSound(arrow.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.NEUTRAL, 1.5f, 1.2f)
            world.playSound(arrow.location, Sound.BLOCK_ANVIL_LAND, SoundCategory.NEUTRAL, 0.8f, 1.8f)
            world.spawnParticle(Particle.CRIT, arrow.location, 12, 0.2, 0.2, 0.2, 0.1)
            world.spawnParticle(Particle.ELECTRIC_SPARK, arrow.location, 10, 0.2, 0.2, 0.2, 0.05)
        } finally {
            if (arrow.isValid) {
                arrow.remove()
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDreadnoughtDamage(event: EntityDamageEvent) {
        val golem = event.entity as? IronGolem ?: return
        val fight = manager.getFight(golem) ?: return
        fight.rebindGolem(golem)

        if (event.cause == EntityDamageEvent.DamageCause.FALL ||
            event.cause == EntityDamageEvent.DamageCause.PROJECTILE
        ) {
            event.isCancelled = true
            return
        }

        if (fight.isDying) {
            event.isCancelled = true
            return
        }

        // Intercept lethal damage to play the Beacon Deactivation & Shatter death animation
        if (golem.health - event.finalDamage <= 0.0) {
            event.damage = 0.0
            fight.triggerBeaconDeathAnimation()
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onDreadnoughtDeath(event: EntityDeathEvent) {
        val golem = event.entity as? IronGolem ?: return
        val fight = manager.getFight(golem) ?: return

        manager.unregisterGolem(golem.uniqueId)

        if (!fight.isDying) {
            event.drops.clear()
            event.drops.addAll(manager.generateDrops())
        } else {
            event.drops.clear()
        }
    }
}