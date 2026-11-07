package project.kompass.travellersTales.boss.traveller

import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.WitherSkeleton
import org.bukkit.entity.WitherSkull
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityTargetLivingEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class TravellerListener(private val manager: TravellerManager) : Listener {

    companion object {
        private val AXE_MATERIALS: Set<Material> = EnumSet.noneOf(Material::class.java).apply {
            for (mat in Material.entries) {
                if (!mat.name.startsWith("LEGACY_") && mat.name.endsWith("_AXE")) {
                    add(mat)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        manager.recordPlayerActivity(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val fight = manager.getFightHuntingPlayer(event.player.uniqueId) ?: return
        manager.handlePlayerEscaped(fight)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (event.hasChangedPosition() || event.hasChangedOrientation()) {
            manager.recordPlayerActivity(event.player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        manager.recordPlayerActivity(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerChat(event: AsyncChatEvent) {
        manager.recordPlayerActivity(event.player)
    }

    // Prevents The Traveller from acquiring an attack target unless provoked
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTravellerTarget(event: EntityTargetLivingEntityEvent) {
        val fight = manager.getFight(event.entity) ?: return
        if (!fight.isProvoked && event.target is Player) {
            event.isCancelled = true
            event.target = null
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTravellerCombat(event: EntityDamageByEntityEvent) {
        // Case 1: The Traveller takes damage -> Provokes him immediately
        val damagedFight = manager.getFight(event.entity)
        if (damagedFight != null) {
            val rawDamager = event.damager

            // Arrow Handling (75% reduction + 35% shield deflection if not piercing)
            if (rawDamager is AbstractArrow) {
                if (damagedFight.isShieldActive() && isFrontalHit(damagedFight.entity, rawDamager)) {
                    if (rawDamager.pierceLevel == 0 && Random.nextDouble() < damagedFight.config.shieldBlockChance) {
                        event.isCancelled = true
                        damagedFight.world.playSound(damagedFight.entity.location, Sound.ITEM_SHIELD_BLOCK, 1.5f, 1.0f)
                        damagedFight.world.spawnParticle(Particle.CRIT, rawDamager.location, 12, 0.2, 0.2, 0.2, 0.1)
                        damagedFight.world.spawnParticle(Particle.ELECTRIC_SPARK, rawDamager.location, 10, 0.2, 0.2, 0.2, 0.05)
                        rawDamager.velocity = rawDamager.velocity.multiply(-0.2)
                        return
                    }
                }
                event.damage *= (1.0 - damagedFight.config.arrowDamageReduction)
            }

            // Axe Guard Break & Immediate Retaliatory Shield Bash
            val attacker: LivingEntity? = when (rawDamager) {
                is LivingEntity -> rawDamager
                is Projectile -> rawDamager.shooter as? LivingEntity
                else -> null
            }

            if (attacker != null && isAxeAttack(attacker) && isFrontalHit(damagedFight.entity, attacker)) {
                if (damagedFight.isShieldActive()) {
                    damagedFight.disableShield(damagedFight.config.shieldDisableTicks)
                    damagedFight.world.playSound(damagedFight.entity.location, Sound.ITEM_SHIELD_BREAK, 1.5f, 0.8f)
                    damagedFight.world.spawnParticle(
                        Particle.BLOCK, damagedFight.entity.location.add(0.0, 1.2, 0.0),
                        30, 0.3, 0.4, 0.3, 0.1, Material.SHIELD.createBlockData()
                    )
                    damagedFight.performShieldBash(attacker)
                }
            }

            damagedFight.onDamaged(attacker)
        }

        // Case 2: The Traveller deals melee damage
        val attackerFight = manager.getFight(event.damager)
        if (attackerFight != null) {
            if (!attackerFight.isProvoked && event.entity is Player) {
                event.isCancelled = true
                return
            }
            val victim = event.entity as? LivingEntity ?: return
            attackerFight.onMeleeAttack(victim)
        }
    }

    private fun isFrontalHit(victim: LivingEntity, attacker: Entity): Boolean {
        val yawRad = Math.toRadians(victim.yaw.toDouble())
        val lookX = -sin(yawRad)
        val lookZ = cos(yawRad)
        val toAttackerX = attacker.location.x - victim.location.x
        val toAttackerZ = attacker.location.z - victim.location.z
        return (lookX * toAttackerX + lookZ * toAttackerZ) >= 0.0
    }

    private fun isAxeAttack(damager: LivingEntity): Boolean {
        val weapon = damager.equipment?.itemInMainHand ?: return false
        return AXE_MATERIALS.contains(weapon.type)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHomingSkullImpact(event: ProjectileHitEvent) {
        val skull = event.entity as? WitherSkull ?: return
        if (!skull.persistentDataContainer.has(MobKeys.TRAVELLER_PROJECTILE, PersistentDataType.BYTE)) return

        val shooter = skull.shooter as? WitherSkeleton ?: return
        val fight = manager.getFight(shooter) ?: return

        fight.detonateHomingSkull(skull.location)
        skull.remove()
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHomingSkullBlockExplode(event: EntityExplodeEvent) {
        val skull = event.entity as? WitherSkull ?: return
        if (skull.persistentDataContainer.has(MobKeys.TRAVELLER_PROJECTILE, PersistentDataType.BYTE)) {
            event.blockList().clear()
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val lastDamage = player.lastDamageCause as? EntityDamageByEntityEvent

        val killerFight: ActiveTravellerFight? = when (val damager = lastDamage?.damager) {
            is WitherSkeleton -> manager.getFight(damager)
            is Projectile -> (damager.shooter as? WitherSkeleton)?.let { manager.getFight(it) }
            else -> null
        } ?: manager.getFightHuntingPlayer(player.uniqueId)

        if (killerFight != null) {
            manager.handlePlayerKilledByTraveller(killerFight, player)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onTravellerDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val fight = manager.getFight(entity) ?: return

        manager.handleTravellerKilledByPlayer(fight, entity.killer)
        event.drops.clear()
        event.drops.addAll(manager.generateDrops())

        // Drop configured EXP levels (Default: 50 Levels)
        event.droppedExp = 0
        ExperienceUtil.spawnLevelOrbs(
            entity.location.add(0.0, 0.5, 0.0),
            manager.config.expLevelsDropped,
            orbCount = 15
        )
    }
}