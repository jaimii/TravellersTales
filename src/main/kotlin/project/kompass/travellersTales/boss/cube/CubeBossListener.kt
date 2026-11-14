package project.kompass.travellersTales.boss.cube

import org.bukkit.*
import org.bukkit.damage.DamageType
import org.bukkit.entity.AbstractCubeMob
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.SlimeSplitEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm

class CubeBossListener(
    private val plugin: TravellersTales,
    private val manager: CubeBossManager
) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is AbstractCubeMob && manager.isCubeBossEntity(entity)) {
                manager.rebindOrRestoreCube(entity)
            }
        }
    }

    /**
     * Universal 3-Day Deferral Hook:
     * If ANY boss (or boss minion/projectile) kills a player, defer Giant & Traveller spawns for 3 days.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerKilledByAnyBoss(event: PlayerDeathEvent) {
        val player = event.player
        val lastByEntity = player.lastDamageCause as? EntityDamageByEntityEvent
        val damager: Entity? = when (val raw = lastByEntity?.damager) {
            is Projectile -> (raw.shooter as? Entity) ?: raw
            else -> raw
        }

        val killedByBossOrMinion = (damager != null && isBossOrMinionEntity(damager)) ||
                player.location.getNearbyLivingEntities(48.0).any { isBossOrMinionEntity(it) }

        if (killedByBossOrMinion) {
            plugin.travellerManager.deferNaturalBossesForThreeDays(player)
        }
    }

    /**
     * Universal 3-Day Deferral Hook:
     * If ANY boss is killed by a player, defer Giant & Traveller spawns for 3 days for all participants.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onAnyBossKilledDeferNightBosses(event: EntityDeathEvent) {
        val entity = event.entity
        if (!entity.persistentDataContainer.has(MobKeys.MOB_ID, PersistentDataType.STRING)) return

        val participants = HashSet<Player>()
        entity.killer?.let { participants.add(it) }
        participants.addAll(entity.location.getNearbyPlayers(64.0))
        participants.forEach { plugin.travellerManager.deferNaturalBossesForThreeDays(it) }
    }

    private fun isBossOrMinionEntity(entity: Entity): Boolean {
        val pdc = entity.persistentDataContainer
        return pdc.has(MobKeys.MOB_ID, PersistentDataType.STRING) ||
                pdc.has(MobKeys.WITHER_MINION, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.WARDEN_PARASITE, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.GIANT_MINION, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.CUBE_BOSS_MINION, PersistentDataType.BYTE) ||
                pdc.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)
    }

    // ========================================================================
    // 1. CUSTOM SPAWN EGG SUMMONING
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onUseBossSpawnEgg(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        val bossType = manager.getBossTypeFromEgg(item) ?: return

        event.isCancelled = true
        val clickedBlock = event.clickedBlock ?: return
        val spawnBlock = clickedBlock.getRelative(event.blockFace)
        val spawnLoc = spawnBlock.location.add(0.5, 0.0, 0.5)

        if (!manager.getSettings(bossType).enabled) {
            event.player.sendMessage("<red>This boss is currently disabled in config.yml.</red>".mm())
            return
        }

        if (event.player.gameMode != GameMode.CREATIVE) {
            item.amount -= 1
        }

        manager.summonCubeBossWithIntro(spawnLoc, bossType)
        event.player.sendMessage(
            "<bold><gold>[TravellersTales]</gold></bold> <yellow>You have summoned ${manager.getSettings(bossType).displayName}<yellow>!</yellow>".mm()
        )
    }

    // ========================================================================
    // 2. BOSS COMBAT, FALL IMMUNITY & CUSTOM PHASE 2 SPLITTING
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onVanillaSlimeSplit(event: SlimeSplitEvent) {
        if (manager.getFightByEntity(event.entity) != null) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCubeBossCombat(event: EntityDamageByEntityEvent) {
        val attackerCube = event.damager as? AbstractCubeMob ?: return
        val fight = manager.getFightByEntity(attackerCube) ?: return
        val victim = event.entity as? LivingEntity ?: return

        fight.onBossDealAttack(attackerCube, victim)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamage(event: EntityDamageEvent) {
        val entity = event.entity
        val dmgType = event.damageSource.damageType

        // A. Boss Fall Damage Immunity
        if (entity is AbstractCubeMob && dmgType == DamageType.FALL) {
            if (manager.getFightByEntity(entity) != null) {
                event.isCancelled = true
                return
            }
        }

        // B. Player Special Boots Immunities & Bouncy Boots Rebound
        val player = entity as? Player ?: return
        val bootsType = manager.getEquippedSpecialBootsType(player) ?: return

        if (bootsType == CubeBossType.WORLD_SLIME.bootsId && dmgType == DamageType.FALL) {
            event.isCancelled = true
            if (!player.isSneaking && player.fallDistance > 1.5f) {
                val bounceY = (player.fallDistance / 7.5).coerceIn(0.65, 2.4)
                val currentVel = player.velocity
                val bounceVel = currentVel.clone().apply {
                    x = (x * 1.35).coerceIn(-1.8, 1.8)
                    y = bounceY
                    z = (z * 1.35).coerceIn(-1.8, 1.8)
                }

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (player.isValid) {
                        player.velocity = bounceVel
                        player.world.playSound(player.location, Sound.BLOCK_SLIME_BLOCK_FALL, SoundCategory.PLAYERS, 1.2f, 1.1f)
                        player.world.spawnParticle(Particle.ITEM_SLIME, player.location, 18, 0.35, 0.1, 0.35, 0.05)
                    }
                })
            }
            return
        }

        if (bootsType == CubeBossType.MAGMA_CORE.bootsId) {
            if (dmgType == DamageType.HOT_FLOOR ||
                dmgType == DamageType.IN_FIRE ||
                dmgType == DamageType.ON_FIRE ||
                dmgType == DamageType.CAMPFIRE
            ) {
                event.isCancelled = true
                player.fireTicks = 0
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onCubeBossDeath(event: EntityDeathEvent) {
        val cube = event.entity as? AbstractCubeMob ?: return
        val fight = manager.getFightByEntity(cube) ?: return

        event.drops.clear()
        event.droppedExp = 0
        fight.handleCubeDeath(cube)
    }

    // ========================================================================
    // 3. MAGMA CORE BOOTS: TURN LAVA TO MAGMA BLOCKS & WATER TO STONE
    // ========================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMoveWithMagmaBoots(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return
        }

        val player = event.player
        if (manager.getEquippedSpecialBootsType(player) != CubeBossType.MAGMA_CORE.bootsId) return

        if (player.fireTicks > 0) {
            player.fireTicks = 0
        }

        val centerBlock = to.block
        val world = player.world
        var convertedAny = false

        for (dx in -2..2) {
            for (dz in -2..2) {
                if ((dx * dx + dz * dz) > 5) continue
                for (dy in -1..0) {
                    val b = centerBlock.getRelative(dx, dy, dz)
                    when (b.type) {
                        Material.LAVA -> {
                            b.type = Material.MAGMA_BLOCK
                            convertedAny = true
                            world.spawnParticle(Particle.LARGE_SMOKE, b.location.add(0.5, 1.0, 0.5), 2, 0.2, 0.1, 0.2, 0.0)
                        }
                        Material.WATER, Material.BUBBLE_COLUMN -> {
                            b.type = Material.STONE
                            convertedAny = true
                            world.spawnParticle(Particle.LARGE_SMOKE, b.location.add(0.5, 1.0, 0.5), 2, 0.2, 0.1, 0.2, 0.0)
                        }
                        else -> {}
                    }
                }
            }
        }

        if (convertedAny) {
            world.playSound(player.location, Sound.BLOCK_LAVA_EXTINGUISH, SoundCategory.BLOCKS, 0.4f, 1.4f)
        }
    }
}