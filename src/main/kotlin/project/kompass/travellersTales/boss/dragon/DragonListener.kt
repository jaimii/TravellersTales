package project.kompass.travellersTales.boss.dragon

import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.ComplexEntityPart
import org.bukkit.entity.DragonFireball
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
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BossKillTracker
import project.kompass.travellersTales.util.mm
import java.util.UUID

class DragonListener(
    private val plugin: TravellersTales,
    private val manager: DragonManager
) : Listener {

    private val lastHitTimestampByEntity = HashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (event.world.environment != World.Environment.THE_END) return
        for (entity in event.entities) {
            if (entity is EnderDragon && manager.isRealDragon(entity)) {
                manager.rebindOrRestoreDragon(entity)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerEnterEnd(event: PlayerChangedWorldEvent) {
        val world = event.player.world
        if (world.environment != World.Environment.THE_END) return

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            world.getEntitiesByClass(EnderDragon::class.java)
                .firstOrNull { manager.isRealDragon(it) }
                ?.let { dragon ->
                    manager.rebindOrRestoreDragon(dragon)?.syncNativeBossBar()
                }
        }, 2L)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDragonSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (entity is EnderDragon && entity.world.environment == World.Environment.THE_END) {
            // Never register Decoy Mirror Dragons as the main boss fight!
            if (entity.persistentDataContainer.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)) {
                return
            }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (entity.isValid && manager.isRealDragon(entity)) {
                    manager.handleDragonSpawn(entity)
                }
            })
        }
    }

    /**
     * Blocks the Ender Dragon from shooting its weak vanilla base DragonFireball
     * so it strictly follows our custom deterministic Attack Cycle!
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVanillaDragonFireballLaunch(event: ProjectileLaunchEvent) {
        val fireball = event.entity as? DragonFireball ?: return
        if (fireball.world.environment != World.Environment.THE_END) return
        if (manager.activeFight != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVanillaDragonFireballSpawn(event: EntitySpawnEvent) {
        val fireball = event.entity as? DragonFireball ?: return
        if (fireball.world.environment != World.Environment.THE_END) return
        if (manager.activeFight != null) {
            event.isCancelled = true
        }
    }

    /**
     * Handles arrow hits on the Ender Dragon (or its ComplexEntityPart wings/body):
     * - In Phase 6 (Totem Climax): Dragon is 100% IMMUNE to arrows (players must levitate up and melee hit!).
     * - In Phases 1..5 during HOVER or Perched states: Applies consistent arrow damage so arrows don't bounce off.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHitDragon(event: ProjectileHitEvent) {
        val projectile = event.entity
        val hitEntity = event.hitEntity ?: return

        val hitDragon: EnderDragon = when (hitEntity) {
            is EnderDragon -> hitEntity
            is ComplexEntityPart -> hitEntity.parent as? EnderDragon ?: return
            else -> return
        }

        if (!manager.isRealDragon(hitDragon)) return
        val fight = manager.rebindOrRestoreDragon(hitDragon) ?: return

        // Phase 6 (Totem Climax): Dragon is 100% immune to arrows and projectiles!
        if (fight.inTotemClimax) {
            event.isCancelled = true
            hitDragon.world.playSound(projectile.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 2.0f, 0.8f)
            hitDragon.world.spawnParticle(Particle.CRIT, projectile.location, 10, 0.2, 0.2, 0.2, 0.1)
            (projectile.shooter as? Player)?.sendActionBar(
                "<bold><light_purple>[Ender Dragon]</light_purple></bold> <red>Immune to arrows! Ride the portal levitation up to strike it!</red>".mm()
            )
            projectile.remove()
            return
        }

        val arrow = projectile as? AbstractArrow ?: return

        val phase = hitDragon.phase
        val vanillaDeflectsArrows = phase == EnderDragon.Phase.HOVER ||
                phase == EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET ||
                phase == EnderDragon.Phase.BREATH_ATTACK ||
                phase == EnderDragon.Phase.ROAR_BEFORE_ATTACK ||
                phase == EnderDragon.Phase.LAND_ON_PORTAL

        if (!vanillaDeflectsArrows) return

        val shooter = arrow.shooter as? Player
        if (shooter != null) {
            val now = System.currentTimeMillis()
            val lastHit = lastHitTimestampByEntity[shooter.uniqueId] ?: 0L
            if (now - lastHit < 150L) return
            lastHitTimestampByEntity[shooter.uniqueId] = now
        }

        val speed = arrow.velocity.length().coerceIn(0.8, 3.2)
        val rawArrowDamage = (arrow.damage * speed * if (arrow.isCritical) 1.35 else 1.0).coerceIn(4.0, 32.0)

        event.isCancelled = true
        arrow.remove()

        hitDragon.world.playSound(hitDragon.location, Sound.ENTITY_ENDER_DRAGON_HURT, SoundCategory.HOSTILE, 2.0f, 1.0f)
        shooter?.playSound(shooter.location, Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.6f, 1.2f)

        val died = fight.applyDamage(rawArrowDamage, shooter)
        if (died && hitDragon.isValid) {
            hitDragon.health = 0.0
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDragonDamage(event: EntityDamageEvent) {
        val dragon = event.entity as? EnderDragon ?: return
        if (!manager.isRealDragon(dragon)) return
        val fight = manager.rebindOrRestoreDragon(dragon) ?: return

        val byEntity = event as? EntityDamageByEntityEvent
        if (byEntity != null) {
            val damager = byEntity.damager
            if (damager == fight.dragon ||
                (damager is EnderDragon && damager.persistentDataContainer.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)) ||
                (damager is AreaEffectCloud && damager.ownerUniqueId == fight.dragonId)
            ) {
                event.isCancelled = true
                return
            }

            // Phase 6 (Totem Climax): Block any projectile damage that reaches EntityDamageByEntityEvent
            if (fight.inTotemClimax && damager is Projectile) {
                event.isCancelled = true
                return
            }
        }

        val hittingPlayer: Player? = when (val damager = byEntity?.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> fight.dragon.killer
        }

        if (hittingPlayer != null) {
            val now = System.currentTimeMillis()
            val lastHit = lastHitTimestampByEntity[hittingPlayer.uniqueId] ?: 0L
            if (now - lastHit < 150L) {
                event.isCancelled = true
                return
            }
            lastHitTimestampByEntity[hittingPlayer.uniqueId] = now
        }

        val normalizedDamage = (event.damage * 2.0).coerceAtLeast(event.finalDamage).coerceAtMost(50.0)
        event.damage = 0.0

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (fight.dragon.isValid && !fight.dragon.isDead) {
                fight.dragon.noDamageTicks = 0
            }
        })

        val died = fight.applyDamage(normalizedDamage, hittingPlayer)
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
        val isFromDragon = damager.uniqueId == fight.dragonId ||
                (damager is AreaEffectCloud && damager.ownerUniqueId == fight.dragonId)

        if (isFromDragon) {
            fight.onDragonAttackHit(target, event.finalDamage)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onCrystalBreak(event: EntityDamageEvent) {
        val crystal = event.entity as? EnderCrystal ?: return
        if (crystal.world.environment != World.Environment.THE_END) return

        Bukkit.getScheduler().runTask(plugin, Runnable {
            val remaining = crystal.world.getEntitiesByClass(EnderCrystal::class.java).count { it.isValid && !it.isDead }
            if (remaining > 0) {
                if (manager.config.crystalCounterActionbar) {
                    crystal.world.players.forEach { p ->
                        p.sendActionBar("<bold><light_purple>$remaining</light_purple> <gray>End Crystals Left</gray></bold>".mm())
                    }
                }
            } else {
                manager.activeFight?.onAllCrystalsDestroyed()
                if (manager.config.crystalCounterActionbar) {
                    crystal.world.players.forEach { p ->
                        p.sendActionBar("<bold><light_purple>All End Crystals destroyed!</light_purple></bold>".mm())
                        p.playSound(p.location, Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 2f, 1f)
                    }
                }
            }
        })
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onDragonDeath(event: EntityDeathEvent) {
        val fight = manager.activeFight ?: return
        if (event.entity.uniqueId == fight.dragonId) {
            event.droppedExp = 0

            val participants = HashSet<Player>()
            event.entity.killer?.let { participants.add(it) }
            participants.addAll(event.entity.location.getNearbyPlayers(128.0))
            participants.forEach { BossKillTracker.recordBossKill(it, "ender_dragon") }

            fight.onDeath(event.entity.killer)
            manager.clearActiveFight(fight)
        }
    }
}