package project.kompass.travellersTales.boss.herobrine

import org.bukkit.*
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BossKillTracker
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.EnumSet
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class HerobrineListener(
    private val plugin: TravellersTales,
    private val manager: HerobrineManager
) : Listener {

    companion object {
        private val AXE_MATERIALS: Set<Material> = EnumSet.noneOf(Material::class.java).apply {
            for (mat in Material.entries) {
                if (!mat.name.startsWith("LEGACY_") && mat.name.endsWith("_AXE")) {
                    add(mat)
                }
            }
        }
    }

    private val activeTunnelingPlayers = HashSet<UUID>()

    // ========================================================================
    // 1. HEROBRINE SHRINE IGNITION
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onShrineIgnite(event: BlockIgniteEvent) {
        val player = event.player ?: return
        val ignitedBlock = event.block
        val belowBlock = ignitedBlock.getRelative(0, -1, 0)

        if (belowBlock.type == Material.NETHERRACK) {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                manager.tryActivateShrine(belowBlock, player)
            })
        }
    }

    // ========================================================================
    // 2. AUTOMATIC BOSS KILL TRACKING ACROSS ALL 7 BOSSES
    // ========================================================================

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAnyBossDeathTrack(event: EntityDeathEvent) {
        val entity = event.entity
        val bossId = entity.persistentDataContainer.get(MobKeys.MOB_ID, PersistentDataType.STRING) ?: return

        if (bossId == "world_slime" || bossId == "magma_core") {
            val cubeFight = plugin.cubeBossManager.getFightByEntity(entity) ?: return
            if (cubeFight.currentPhase != 2 || cubeFight.activeCubes.size > 1) {
                return
            }
        }

        if (bossId in BossKillTracker.REQUIRED_BOSS_IDS) {
            awardBossKillToParticipants(entity, bossId)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCustomDeathAnimationBossKillTrack(event: EntityDamageEvent) {
        val entity = event.entity as? LivingEntity ?: return
        val bossId = entity.persistentDataContainer.get(MobKeys.MOB_ID, PersistentDataType.STRING) ?: return

        when (bossId) {
            "the_warden" -> {
                val fight = (entity as? Warden)?.let { plugin.wardenManager.getFight(it) } ?: return
                if (fight.isDying) {
                    awardBossKillToParticipants(entity, "the_warden")
                }
            }
            "giant" -> {
                val fight = (entity as? Giant)?.let { plugin.giantManager.getFight(it) } ?: return
                if (fight.isDying) {
                    awardBossKillToParticipants(entity, "giant")
                }
            }
        }
    }

    private fun awardBossKillToParticipants(bossEntity: LivingEntity, bossId: String) {
        val participants = HashSet<Player>()
        bossEntity.killer?.let { participants.add(it) }
        participants.addAll(bossEntity.location.getNearbyPlayers(64.0))

        for (player in participants) {
            BossKillTracker.recordBossKill(player, bossId)
        }
    }

    // ========================================================================
    // 3. PROJECTILES: CHANNELING TRIDENT SLAM, TRIDENT PARRY & ARROW REFLECTION
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity

        // A. Herobrine's Thrown Channeling Trident Hits Ground or Entity -> Stun + Instant Slam!
        if (projectile is Trident && projectile.persistentDataContainer.has(MobKeys.HEROBRINE_TRIDENT, PersistentDataType.BYTE)) {
            val shooter = projectile.shooter as? Mannequin ?: return
            val fight = manager.getFight(shooter) ?: return

            val hitPlayer = event.hitEntity as? Player
            if (hitPlayer != null && !hitPlayer.isDead) {
                handleHerobrineTridentHitPlayer(fight, projectile, hitPlayer)
            }

            val impactLoc = event.hitEntity?.location ?: projectile.location
            projectile.remove()
            fight.executePhase2InstantLightningSlam(impactLoc)
            return
        }

        val hitEntity = event.hitEntity as? Mannequin ?: return

        // B. Friendly fire filter between Herobrine and his Steves
        val shooter = projectile.shooter
        if (shooter is Mannequin) {
            val shooterIsAllied = manager.getFight(shooter) != null ||
                    shooter.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)
            val hitIsAllied = manager.getFight(hitEntity) != null ||
                    hitEntity.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)

            if (shooterIsAllied && hitIsAllied) {
                event.isCancelled = true
                return
            }
        }

        val fight = manager.getFight(hitEntity) ?: return
        val arrowOrTrident = projectile as? AbstractArrow ?: return
        if (arrowOrTrident.shooter == fight.mannequin) return

        // C. Herobrine ALWAYS parries Trident projectiles back at the shooter!
        if (arrowOrTrident is Trident) {
            event.isCancelled = true
            fight.mannequin.swingMainHand()
            reflectProjectileBackToShooter(fight.mannequin, arrowOrTrident)
            return
        }

        // D. Reflect arrows while floating invulnerable
        if (fight.isFloatingInvulnerable) {
            event.isCancelled = true
            reflectProjectileBackToShooter(fight.mannequin, arrowOrTrident)
        }
    }

    private fun handleHerobrineTridentHitPlayer(fight: ActiveHerobrineFight, trident: Trident, player: Player) {
        val blockedByShield = player.isBlocking && isFrontalHit(player, trident)

        if (blockedByShield) {
            player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 0.9f)
        } else {
            val armorIgnoreSource = DamageSource.builder(DamageType.MAGIC)
                .withCausingEntity(fight.mannequin)
                .withDirectEntity(trident)
                .build()
            player.damage(4.0, armorIgnoreSource)
        }

        // Stun player for 1 second (20 ticks) regardless of shield
        player.clearActiveItem()
        player.setCooldown(Material.SHIELD, 20)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 254, false, true, true))
        player.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 20, 250, false, true, true))
        player.sendActionBar("<bold><red>⚡ STUNNED BY HEROBRINE'S TRIDENT! ⚡</red></bold>".mm())
    }

    private fun reflectProjectileBackToShooter(herobrine: Mannequin, projectile: AbstractArrow) {
        val incomingVel = projectile.velocity
        val speed = incomingVel.length().coerceAtLeast(1.4)
        val shooter = projectile.shooter as? LivingEntity

        val reflectDir = if (shooter != null && shooter.isValid && shooter.world == herobrine.world) {
            shooter.location.clone().add(0.0, shooter.height * 0.65, 0.0)
                .toVector()
                .subtract(projectile.location.toVector())
                .normalize()
        } else if (incomingVel.lengthSquared() > 1e-4) {
            incomingVel.clone().multiply(-1.0).normalize()
        } else {
            herobrine.location.direction.normalize()
        }

        val newVelocity = reflectDir.clone().multiply(speed)
        val spawnLoc = projectile.location.clone().add(reflectDir.clone().multiply(0.8))
        spawnLoc.direction = newVelocity

        val world = herobrine.world
        world.spawn(spawnLoc, projectile.javaClass) { reflected ->
            reflected.shooter = herobrine
            reflected.velocity = newVelocity
            reflected.damage = projectile.damage
            reflected.isCritical = projectile.isCritical
            reflected.pierceLevel = projectile.pierceLevel
            reflected.fireTicks = projectile.fireTicks
            reflected.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        }

        world.playSound(projectile.location, Sound.ITEM_TRIDENT_HIT, SoundCategory.HOSTILE, 1.5f, 1.4f)
        world.playSound(projectile.location, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 0.9f, 1.8f)
        world.spawnParticle(Particle.CRIT, projectile.location, 15, 0.2, 0.2, 0.2, 0.1)
        world.spawnParticle(
            Particle.DUST,
            projectile.location,
            10, 0.2, 0.2, 0.2, 0.0,
            Particle.DustOptions(Color.fromRGB(255, 15, 15), 1.5f)
        )

        projectile.remove()
    }

    // ========================================================================
    // 4. STEVE 35% MELEE SHIELD BLOCK & STUN + HEROBRINE COMBAT & DEATH TRIGGER
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMannequinDamagedByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Mannequin ?: return

        // Case A: Steve Minion Shield Block (35% melee block) & Axe/Shield Stun
        if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            val fight = manager.getFightBySteve(victim) ?: return
            val steveController = fight.activeSteves.firstOrNull { it.mannequin.uniqueId == victim.uniqueId } ?: return
            val attacker = event.damager as? LivingEntity ?: return

            if (!steveController.isStunned() && isFrontalHit(victim, attacker)) {
                val mainItem = attacker.equipment?.itemInMainHand?.type ?: Material.AIR
                val offItem = attacker.equipment?.itemInOffHand?.type ?: Material.AIR

                if (AXE_MATERIALS.contains(mainItem) || mainItem == Material.SHIELD || (offItem == Material.SHIELD && attacker.isSneaking)) {
                    steveController.stun(100)
                    victim.world.playSound(victim.location, Sound.ITEM_SHIELD_BREAK, SoundCategory.HOSTILE, 1.5f, 0.9f)
                    victim.world.spawnParticle(
                        Particle.BLOCK,
                        victim.location.add(0.0, 1.2, 0.0),
                        25, 0.3, 0.4, 0.3, 0.1,
                        Material.SHIELD.createBlockData()
                    )
                    return
                }

                if (Random.nextDouble() < 0.35) {
                    event.isCancelled = true
                    victim.swingOffHand()
                    victim.world.playSound(victim.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 1.5f, 1.0f)
                    val push = attacker.location.toVector().subtract(victim.location.toVector()).setY(0.0).normalize()
                    attacker.velocity = attacker.velocity.add(push.multiply(0.5).setY(0.2))
                }
            }
            return
        }

        // Case B: Safety fallback for Tridents hitting Herobrine
        val fight = manager.getFight(victim) ?: return
        val damager = event.damager
        if (damager is Trident && damager.shooter != fight.mannequin) {
            event.isCancelled = true
            fight.mannequin.swingMainHand()
            reflectProjectileBackToShooter(fight.mannequin, damager)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHerobrineDamaged(event: EntityDamageEvent) {
        val mannequin = event.entity as? Mannequin ?: return
        val fight = manager.getFight(mannequin) ?: return

        if (event.cause == EntityDamageEvent.DamageCause.FALL ||
            event.cause == EntityDamageEvent.DamageCause.LIGHTNING ||
            event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
            event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
        ) {
            event.isCancelled = true
            return
        }

        if (fight.isDyingAnimation) {
            event.isCancelled = true
            return
        }

        if (fight.isFloatingInvulnerable) {
            event.isCancelled = true
            val damager = (event as? EntityDamageByEntityEvent)?.damager
            if (damager is AbstractArrow && damager.shooter != fight.mannequin) {
                reflectProjectileBackToShooter(fight.mannequin, damager)
            }
            return
        }

        val attacker: Player? = when (val damager = (event as? EntityDamageByEntityEvent)?.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }

        if (fight.currentPhase == 0) {
            event.damage = 0.0
            fight.awakenPhase1(attacker)
            return
        }

        // Intercept lethal damage to play Herobrine's ascension death animation
        if (mannequin.health - event.finalDamage <= 0.0) {
            event.damage = 0.0
            fight.triggerDeathAnimation(attacker)
            return
        }

        val finalDmg = event.finalDamage
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!fight.isDeadOrRemoved && !fight.isDyingAnimation && mannequin.isValid && !mannequin.isDead) {
                fight.onDamaged(finalDmg, attacker)
            }
        })
    }

    private fun isFrontalHit(victim: LivingEntity, attacker: Entity): Boolean {
        val yawRad = Math.toRadians(victim.yaw.toDouble())
        val lookX = -sin(yawRad)
        val lookZ = cos(yawRad)
        val toAttackerX = attacker.location.x - victim.location.x
        val toAttackerZ = attacker.location.z - victim.location.z
        return (lookX * toAttackerX + lookZ * toAttackerZ) >= 0.0
    }

    // ========================================================================
    // 5. "THE HERO'S TUNNELER" 3x3 MINING HANDLER (PDC-PERSISTENT)
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHerosTunnelerBreak(event: BlockBreakEvent) {
        val player = event.player
        if (player.uniqueId in activeTunnelingPlayers) return

        val tool = player.inventory.itemInMainHand
        if (tool.type.isAir || !tool.hasItemMeta()) return
        if (!tool.itemMeta.persistentDataContainer.has(MobKeys.HEROBRINE_TUNNELER, PersistentDataType.BYTE)) return

        activeTunnelingPlayers.add(player.uniqueId)
        try {
            val center = event.block
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val b = center.getRelative(dx, dy, dz)
                        if (isTunnelerBreakable(b.type)) {
                            b.breakNaturally(tool, true)
                        }
                    }
                }
            }
        } finally {
            activeTunnelingPlayers.remove(player.uniqueId)
        }
    }

    private fun isTunnelerBreakable(mat: Material): Boolean {
        if (mat.isAir || !mat.isBlock || mat.hardness < 0f) return false
        return mat != Material.BEDROCK &&
                mat != Material.BARRIER &&
                mat != Material.END_PORTAL_FRAME &&
                mat != Material.END_PORTAL &&
                mat != Material.END_GATEWAY &&
                mat != Material.COMMAND_BLOCK &&
                mat != Material.CHAIN_COMMAND_BLOCK &&
                mat != Material.REPEATING_COMMAND_BLOCK
    }

    // ========================================================================
    // 6. TABLIST SYNC, PLAYER DEATHS & MINION/HEROBRINE DEATH
    // ========================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        for (fight in manager.activeFights.values) {
            if (!fight.isDeadOrRemoved) {
                HerobrinePacketController.sendTablistAdd(event.player, fight.tablistUuid, "Herobrine")
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val lastDamage = player.lastDamageCause as? EntityDamageByEntityEvent ?: return

        val killerFight: ActiveHerobrineFight? = when (val damager = lastDamage.damager) {
            is Mannequin -> manager.getFight(damager) ?: manager.getFightBySteve(damager)
            is Projectile -> (damager.shooter as? Mannequin)?.let {
                manager.getFight(it) ?: manager.getFightBySteve(it)
            }
            else -> null
        }

        if (killerFight != null) {
            event.deathMessage("<white>Herobrine has slain ${player.name}</white>".mm())
            DiscordSrvHook.sendPlayerSlainByHerobrine(player.name)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onMannequinDeath(event: EntityDeathEvent) {
        val entity = event.entity as? Mannequin ?: return

        // Steve Minion Death: drop 10 levels of EXP (manager.config.steveMinionExpLevelsDropped)
        if (entity.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            event.drops.clear()
            event.droppedExp = 0
            ExperienceUtil.spawnLevelOrbs(
                entity.location.add(0.0, 0.5, 0.0),
                manager.config.steveMinionExpLevelsDropped,
                orbCount = 5
            )
            return
        }

        // Herobrine Boss Death fallback
        val fight = manager.activeFights.remove(entity.uniqueId) ?: return
        event.drops.clear()
        event.droppedExp = 0

        if (!fight.isDyingAnimation) {
            val killer: Player? = entity.killer ?: run {
                val lastDmg = entity.lastDamageCause as? EntityDamageByEntityEvent
                when (val d = lastDmg?.damager) {
                    is Player -> d
                    is Projectile -> d.shooter as? Player
                    else -> null
                }
            }
            fight.onKilledByPlayer(killer)
        }
    }
}