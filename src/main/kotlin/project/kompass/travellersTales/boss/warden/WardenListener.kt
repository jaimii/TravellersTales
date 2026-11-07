package project.kompass.travellersTales.boss.warden

import org.bukkit.*
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.GenericGameEvent
import org.bukkit.inventory.ItemStack
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID

class WardenListener(
    private val plugin: TravellersTales,
    private val manager: WardenManager
) : Listener {

    private val parasiteSpawnCooldowns = HashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        manager.syncOfflinePendingRespawn(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        for (entity in event.entities) {
            if (entity is Warden) {
                val fight = manager.getFight(entity) ?: continue
                manager.handleWardenChunkUnloaded(fight)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWardenSpawn(event: CreatureSpawnEvent) {
        val entity = event.entity
        if (entity !is Warden || !manager.config.enabled) return

        // 1. Prevent a Sculk Shrieker within the 32x32 area from spawning a second Warden
        if (manager.hasActiveWardenInArea(event.location)) {
            event.isCancelled = true
            return
        }

        // 2. Prevent spawning if a nearby player is within their 10-minute post-kill grace period
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.CUSTOM &&
            event.spawnReason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG &&
            event.spawnReason != CreatureSpawnEvent.SpawnReason.COMMAND
        ) {
            val gracedPlayer = manager.findGracePeriodPlayerInArea(event.location)
            if (gracedPlayer != null) {
                event.isCancelled = true
                val remainingSec = manager.getRemainingGraceSeconds(gracedPlayer)
                val mins = remainingSec / 60
                val secs = remainingSec % 60
                gracedPlayer.sendActionBar(
                    "<bold><#00A8FF>[The Warden]</#00A8FF></bold> <aqua>Sculk is dormant (${mins}m ${secs}s grace remaining)</aqua>".mm()
                )
                return
            }
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (entity.isValid && !entity.isDead) {
                manager.registerWarden(entity)
            }
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameEvent(event: GenericGameEvent) {
        val gameEvent = event.event

        // Case 1: Sculk Shrieker activated -> Check if triggering player has a pending despawned Warden
        if (gameEvent == GameEvent.SHRIEK) {
            val shriekerLoc = event.location
            val triggeringPlayer = (event.entity as? Player)
                ?: shriekerLoc.getNearbyPlayers(16.0)
                    .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                    .minByOrNull { it.location.distanceSquared(shriekerLoc) }
                ?: return

            if (manager.hasPendingWardenRespawn(triggeringPlayer)) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    manager.spawnPendingWardenFromShrieker(shriekerLoc, triggeringPlayer)
                })
            }
            return
        }

        // Case 2: Phase 2 Sculk Parasite spawning on sprinting/jumping over Sculk
        if (gameEvent != GameEvent.STEP && gameEvent != GameEvent.HIT_GROUND) return

        val player = event.entity as? Player ?: return
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return
        if (player.isSneaking) return

        val isSprintingStep = (gameEvent == GameEvent.STEP && player.isSprinting)
        val isJumpingLand = (gameEvent == GameEvent.HIT_GROUND)
        if (!isSprintingStep && !isJumpingLand) return

        val feetBlock = player.location.block
        val underBlock = player.location.clone().subtract(0.0, 0.1, 0.0).block
        if (feetBlock.type != Material.SCULK && underBlock.type != Material.SCULK) return

        val fight = manager.findNearestPhase2Fight(player.location, 64.0) ?: return

        val now = System.currentTimeMillis()
        val lastSpawn = parasiteSpawnCooldowns[player.uniqueId] ?: 0L
        if (now - lastSpawn < 1500L) return
        parasiteSpawnCooldowns[player.uniqueId] = now

        fight.spawnSculkParasites(player.location)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWardenCombat(event: EntityDamageByEntityEvent) {
        val damagedWarden = event.entity as? Warden
        if (damagedWarden != null) {
            val fight = manager.getFight(damagedWarden) ?: return

            if (fight.isTransitionInvulnerable || fight.isDying) {
                event.isCancelled = true
                return
            }

            val damager = event.damager

            // Phase 3 Heartbeat Parry Counter Check (ticks 50..65)
            if (fight.parryWindow) {
                val isCritArrow = damager is AbstractArrow && damager.isCritical
                val isCritMelee = damager is Player && event.isCritical

                if (isCritArrow || isCritMelee) {
                    event.damage = 0.0
                    val player = (damager as? Player) ?: ((damager as? AbstractArrow)?.shooter as? Player)
                    fight.triggerParryCounter(player)
                    if (damager is AbstractArrow) damager.remove()
                    return
                }
            }

            // Immune to all arrow projectiles outside of the Parry Counter window
            if (damager is AbstractArrow) {
                event.isCancelled = true
                damagedWarden.world.playSound(
                    damagedWarden.location,
                    Sound.ITEM_SHIELD_BLOCK,
                    SoundCategory.HOSTILE,
                    1.5f,
                    0.6f
                )
                return
            }
        }

        val attackingWarden = event.damager as? Warden
        if (attackingWarden != null) {
            val fight = manager.getFight(attackingWarden) ?: return
            if (fight.isDying) {
                event.isCancelled = true
                return
            }
            fight.lastCombatTimestamp = System.currentTimeMillis()

            // Extend vanilla Sonic Boom by +10 blocks and damage any mobs caught inside
            if (event.damageSource.damageType == DamageType.SONIC_BOOM) {
                val targetLoc = (event.entity as? LivingEntity)?.eyeLocation ?: event.entity.location
                fight.fireExtendedSonicBoomRay(targetLoc, includePrimaryTargetDamage = false)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onWardenGeneralDamage(event: EntityDamageEvent) {
        val warden = event.entity as? Warden ?: return
        val fight = manager.getFight(warden) ?: return

        if (fight.isTransitionInvulnerable || fight.isDying) {
            event.isCancelled = true
            return
        }

        // Intercept lethal damage to play the Final Breath Roar & Soul Explosion animation
        if (warden.health - event.finalDamage <= 0.0) {
            event.damage = 0.0
            val killer: Player? = when (val damager = (event as? EntityDamageByEntityEvent)?.damager) {
                is Player -> damager
                is Projectile -> damager.shooter as? Player
                else -> warden.killer
            }
            fight.triggerFinalBreathDeath(killer)
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (warden.isValid && !warden.isDead && !fight.isDying) {
                fight.checkPhaseTransitions()
            }
        })
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onWardenDeath(event: EntityDeathEvent) {
        val warden = event.entity as? Warden ?: return
        val fight = manager.getFight(warden) ?: return

        manager.unregisterWarden(warden.uniqueId)

        if (!fight.isDying) {
            val participants = HashSet<Player>()
            warden.killer?.let { participants.add(it) }
            fight.summonerId?.let { id -> Bukkit.getPlayer(id)?.let { participants.add(it) } }
            participants.addAll(warden.location.getNearbyPlayers(64.0))
            participants.forEach { manager.applyGracePeriod(it) }

            event.drops.clear()
            event.drops.add(ItemStack(Material.SCULK_CATALYST, 1))
            event.drops.add(ItemStack(Material.RECOVERY_COMPASS, 1))
            event.drops.add(ItemStack(Material.ECHO_SHARD, 2))

            event.droppedExp = 0
            ExperienceUtil.spawnLevelOrbs(warden.location.add(0.0, 0.5, 0.0), manager.config.expLevelsDropped, orbCount = 20)
        } else {
            event.drops.clear()
            event.droppedExp = 0
        }
    }
}