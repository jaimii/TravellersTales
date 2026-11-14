package project.kompass.travellersTales.boss.herobrine

import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
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
    private val stunJumpLockKey = NamespacedKey(plugin, "herobrine_stun_jump_lock")

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) {
            if (entity is Mannequin) {
                if (manager.isHerobrineEntity(entity)) {
                    manager.rebindHerobrineIfTracked(entity)
                } else if (entity.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
                    manager.getFightBySteve(entity)?.activeSteves
                        ?.firstOrNull { it.steveId == entity.uniqueId }
                        ?.mannequin = entity
                }
            }
        }
    }

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
    // 3. PROJECTILES: TRIDENT STUN, SHIELD-BLOCKABLE WITHER SKULLS & REFLECTIONS
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHerobrineSkullBlockExplode(event: EntityExplodeEvent) {
        val skull = event.entity as? WitherSkull ?: return
        if (skull.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            event.blockList().clear()
        }
    }

    /**
     * Allows players to block Herobrine's Wither Skulls with a raised shield!
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerDamagedByHerobrineSkull(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val skull = event.damager as? WitherSkull ?: return
        if (!skull.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return

        if (player.isBlocking && isFrontalHit(player, skull)) {
            event.isCancelled = true
            player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 1.0f)
            player.world.spawnParticle(Particle.CRIT, player.eyeLocation, 10, 0.25, 0.25, 0.25, 0.1)
            val push = player.location.toVector().subtract(skull.location.toVector()).setY(0.0)
            if (push.lengthSquared() > 1e-4) push.normalize()
            player.velocity = player.velocity.add(push.multiply(0.35).setY(0.15))
            skull.remove()
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity

        // A. Herobrine's Thrown Channeling Trident Hits Ground or Player -> Lightning Strike + Stun!
        if (projectile is Trident && projectile.persistentDataContainer.has(MobKeys.HEROBRINE_TRIDENT, PersistentDataType.BYTE)) {
            val shooter = projectile.shooter as? Mannequin ?: return
            val fight = manager.getFight(shooter) ?: return

            val impactLoc = event.hitEntity?.location ?: projectile.location
            impactLoc.world?.strikeLightningEffect(impactLoc)
            impactLoc.world?.playSound(impactLoc, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.HOSTILE, 3.0f, 1.0f)

            val hitPlayer = event.hitEntity as? Player
            if (hitPlayer != null && !hitPlayer.isDead) {
                handleHerobrineTridentHitPlayer(fight, projectile, hitPlayer)
            } else {
                impactLoc.getNearbyPlayers(2.0)
                    .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
                    .forEach { handleHerobrineTridentHitPlayer(fight, projectile, it) }
            }

            projectile.remove()
            return
        }

        // B. Herobrine's Homing Wither Skulls -> Shield-Blockable, or Lightning Animation on Hit!
        if (projectile is WitherSkull && projectile.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            val hitEnt = event.hitEntity
            if (hitEnt is Mannequin && (manager.getFight(hitEnt) != null || hitEnt.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE))) {
                event.isCancelled = true
                return
            }

            val impactLoc = hitEnt?.location ?: projectile.location
            val hitPlayer = (hitEnt as? Player)
                ?: impactLoc.getNearbyPlayers(2.4).firstOrNull { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }

            if (hitPlayer != null) {
                // If player is blocking with a shield facing the skull, deflect it without lightning or damage!
                if (hitPlayer.isBlocking && isFrontalHit(hitPlayer, projectile)) {
                    event.isCancelled = true
                    hitPlayer.world.playSound(hitPlayer.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 1.0f)
                    hitPlayer.world.spawnParticle(Particle.CRIT, projectile.location, 12, 0.25, 0.25, 0.25, 0.1)
                    val push = hitPlayer.location.toVector().subtract(projectile.location.toVector()).setY(0.0)
                    if (push.lengthSquared() > 1e-4) push.normalize()
                    hitPlayer.velocity = hitPlayer.velocity.add(push.multiply(0.35).setY(0.15))
                    projectile.remove()
                    return
                }

                // Unblocked hit: strike lightning animation!
                impactLoc.world?.strikeLightningEffect(hitPlayer.location)
                impactLoc.world?.playSound(hitPlayer.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 2.0f, 1.1f)
            }
            return
        }

        val hitEntity = event.hitEntity as? Mannequin ?: return

        // C. Friendly fire filter between Herobrine and his Steves
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
        if (projectile.shooter == fight.mannequin) return

        // D. Herobrine ALWAYS reflects Wind Charges & Fireballs back at the shooter!
        if (projectile is Fireball || projectile is AbstractWindCharge) {
            event.isCancelled = true
            fight.mannequin.swingMainHand()
            reflectProjectileBackToShooter(fight.mannequin, projectile)
            if (fight.currentPhase == 0) {
                fight.awakenPhase1(projectile.shooter as? Player)
            }
            return
        }

        val arrowOrTrident = projectile as? AbstractArrow ?: return

        // E. Herobrine ALWAYS parries Trident projectiles back at the shooter!
        if (arrowOrTrident is Trident) {
            event.isCancelled = true
            fight.mannequin.swingMainHand()
            reflectProjectileBackToShooter(fight.mannequin, arrowOrTrident)
            if (fight.currentPhase == 0) {
                fight.awakenPhase1(arrowOrTrident.shooter as? Player)
            }
            return
        }

        // F. Reflect all Arrow types (Normal, Tipped, Spectral) while floating invulnerable
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

        player.clearActiveItem()
        player.setCooldown(Material.SHIELD, 20)
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20, 254, false, true, true))
        applyStunJumpLock(player, 20)
        player.sendActionBar("<bold><red>⚡ STUNNED BY HEROBRINE'S TRIDENT! ⚡</red></bold>".mm())
    }

    private fun applyStunJumpLock(player: Player, durationTicks: Int) {
        val jumpAttr = player.getAttribute(Attribute.JUMP_STRENGTH) ?: return
        jumpAttr.getModifier(stunJumpLockKey)?.let { jumpAttr.removeModifier(it) }
        jumpAttr.addModifier(
            AttributeModifier(stunJumpLockKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1)
        )
        player.velocity = Vector(0.0, player.velocity.y.coerceAtMost(0.0), 0.0)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            player.getAttribute(Attribute.JUMP_STRENGTH)?.getModifier(stunJumpLockKey)?.let { mod ->
                player.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(mod)
            }
        }, durationTicks.toLong())
    }

    private fun reflectProjectileBackToShooter(herobrine: Mannequin, projectile: Projectile) {
        try {
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
            val spawnLoc = projectile.location.clone().add(reflectDir.clone().multiply(1.0))
            spawnLoc.direction = newVelocity

            val world = herobrine.world
            val spawned = world.spawnEntity(spawnLoc, projectile.type, CreatureSpawnEvent.SpawnReason.CUSTOM)

            if (spawned is Projectile) {
                spawned.shooter = herobrine
                spawned.velocity = newVelocity
            }

            when {
                spawned is AbstractArrow && projectile is AbstractArrow -> {
                    spawned.damage = projectile.damage
                    spawned.isCritical = projectile.isCritical
                    spawned.pierceLevel = projectile.pierceLevel
                    spawned.fireTicks = projectile.fireTicks
                    spawned.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED

                    if (spawned is Arrow && projectile is Arrow) {
                        projectile.basePotionType?.let { spawned.basePotionType = it }
                        projectile.customEffects.forEach { effect ->
                            spawned.addCustomEffect(effect, true)
                        }
                        projectile.color?.let { spawned.color = it }
                    } else if (spawned is SpectralArrow && projectile is SpectralArrow) {
                        spawned.glowingTicks = projectile.glowingTicks
                    } else if (spawned is Trident && projectile is Trident) {
                        spawned.itemStack = projectile.itemStack.clone()
                    }
                }

                spawned is Fireball && projectile is Fireball -> {
                    spawned.direction = reflectDir
                    spawned.velocity = newVelocity
                    spawned.yield = projectile.yield
                    spawned.setIsIncendiary(projectile.isIncendiary)
                }
            }

            val deflectSound = when (projectile) {
                is AbstractWindCharge -> Sound.ENTITY_BREEZE_DEFLECT
                is Fireball -> Sound.ITEM_FIRECHARGE_USE
                is Trident -> Sound.ITEM_TRIDENT_HIT
                else -> Sound.BLOCK_ANVIL_LAND
            }
            world.playSound(projectile.location, deflectSound, SoundCategory.HOSTILE, 1.6f, 1.3f)
            world.spawnParticle(Particle.CRIT, projectile.location, 15, 0.2, 0.2, 0.2, 0.1)
            world.spawnParticle(
                Particle.DUST,
                projectile.location,
                10, 0.2, 0.2, 0.2, 0.0,
                Particle.DustOptions(Color.fromRGB(255, 15, 15), 1.5f)
            )
        } finally {
            if (projectile.isValid) {
                projectile.remove()
            }
        }
    }

    // ========================================================================
    // 4. STEVE 35% MELEE SHIELD BLOCK & STUN + HEROBRINE COMBAT & MELEE BLINK
    // ========================================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMannequinDamagedByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Mannequin ?: return

        // Case A: Steve Minion Shield Block (35% melee block) & Axe/Shield Stun
        if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            val fight = manager.getFightBySteve(victim) ?: return

            val rawDamager = event.damager
            if (rawDamager == fight.mannequin ||
                rawDamager.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE) ||
                (rawDamager is Projectile && (rawDamager.shooter == fight.mannequin ||
                        (rawDamager.shooter as? Entity)?.persistentDataContainer?.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE) == true))
            ) {
                event.isCancelled = true
                return
            }

            val steveController = fight.activeSteves.firstOrNull { it.steveId == victim.uniqueId } ?: return
            val attacker = rawDamager as? LivingEntity ?: return

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

        // Case B: Safety fallback for Tridents, Wind Charges, and Fireballs hitting Herobrine
        val fight = manager.getFight(victim) ?: return
        val damager = event.damager
        if (damager is Projectile && damager.shooter != fight.mannequin) {
            if (damager is Trident || damager is Fireball || damager is AbstractWindCharge) {
                event.isCancelled = true
                fight.mannequin.swingMainHand()
                reflectProjectileBackToShooter(fight.mannequin, damager)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHerobrineDamaged(event: EntityDamageEvent) {
        val mannequin = event.entity as? Mannequin ?: return

        if (mannequin.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) {
            if (event.cause == EntityDamageEvent.DamageCause.FALL ||
                event.cause == EntityDamageEvent.DamageCause.LIGHTNING ||
                event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
                event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
                event.cause == EntityDamageEvent.DamageCause.WITHER
            ) {
                event.isCancelled = true
            }
            return
        }

        val fight = manager.getFight(mannequin) ?: return

        if (event.cause == EntityDamageEvent.DamageCause.FALL ||
            event.cause == EntityDamageEvent.DamageCause.LIGHTNING ||
            event.cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION ||
            event.cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION ||
            event.cause == EntityDamageEvent.DamageCause.WITHER
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
            if (damager is Projectile && damager.shooter != fight.mannequin) {
                reflectProjectileBackToShooter(fight.mannequin, damager)
            }
            return
        }

        val rawDamager = (event as? EntityDamageByEntityEvent)?.damager
        val wasDirectMeleeHit = rawDamager is Player
        val attacker: Player? = when (rawDamager) {
            is Player -> rawDamager
            is Projectile -> rawDamager.shooter as? Player
            else -> null
        }

        if (fight.currentPhase == 0) {
            event.damage = 0.0
            fight.awakenPhase1(attacker)
            return
        }

        if (mannequin.health - event.finalDamage <= 0.0) {
            event.damage = 0.0
            fight.triggerDeathAnimation(attacker)
            return
        }

        val finalDmg = event.finalDamage
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!fight.isDeadOrRemoved && !fight.isDyingAnimation && fight.mannequin.isValid && !fight.mannequin.isDead) {
                fight.onDamaged(finalDmg, attacker)
                if (wasDirectMeleeHit && attacker != null) {
                    fight.tryCombatMeleeBlink(attacker)
                }
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
            DiscordSrvHook.sendPlayerSlainByHerobrine(player.name, player.uniqueId)
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onMannequinDeath(event: EntityDeathEvent) {
        val entity = event.entity as? Mannequin ?: return

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