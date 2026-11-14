package project.kompass.travellersTales.boss.traveller

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.BannerPatternLayers
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ActiveTravellerFight(
    private val plugin: TravellersTales,
    var entity: WitherSkeleton,
    var config: TravellerConfig,
    val huntedPlayerId: UUID? = null
) {
    val travellerId: UUID = entity.uniqueId
    val world: World = entity.world
    private val tasks = mutableListOf<BukkitTask>()
    private var travellerTeam: Team? = null
    private val spawnOrigin: Location = entity.location.clone()
    private val spawnTimestampMillis: Long = System.currentTimeMillis()

    // Neutral / Stalking state
    var isProvoked: Boolean = false
        private set

    // Gilded Abyss & Crimson Banner Particle Palette
    private val goldColor = Color.fromRGB(229, 193, 88)
    private val crimsonColor = Color.fromRGB(185, 15, 25)
    private val voidBlackColor = Color.fromRGB(15, 10, 22)
    private val gildedTransition = Particle.DustTransition(goldColor, voidBlackColor, 1.4f)
    private val crimsonTransition = Particle.DustTransition(crimsonColor, voidBlackColor, 1.5f)
    private val riftTransition = Particle.DustTransition(crimsonColor, goldColor, 1.8f)

    private var orbitAngle: Double = 0.0

    // Pathfinding throttle state
    private var pathfindingCooldownTicks: Int = 0
    private var combatPathCooldownTicks: Int = 0
    private var lastStalkTargetLoc: Location? = null

    // Shield State Tracking
    var shieldDisabledUntilTick: Int = 0
        private set
    private var lastShieldBashMillis: Long = 0L

    // Cooldown timestamps
    private var lastTeleportMillis: Long = 0L
    private var lastSwirlMillis: Long = 0L
    private var lastSkullBarrageMillis: Long = 0L
    private var isTeleporting: Boolean = false
    private var isSpinning: Boolean = false

    fun isShieldActive(): Boolean = Bukkit.getCurrentTick() >= shieldDisabledUntilTick

    fun disableShield(ticks: Int) {
        shieldDisabledUntilTick = Bukkit.getCurrentTick() + ticks
    }

    fun rebindEntity(loaded: WitherSkeleton) {
        this.entity = loaded
        travellerTeam?.addEntity(loaded)
        plugin.bossBarManager.rebindEntity(loaded)
    }

    fun start() {
        setupTeam()
        configureEntity()
        equipWayfinderNetheriteAndShield()
        plugin.bossBarManager.createBossBar(entity, config.bossBar)
        playSpawnIntro()
        scheduleCoreLoops()
    }

    private fun setupTeam() {
        val scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        val teamName = "Trav_${travellerId.toString().take(8)}"
        scoreboard.getTeam(teamName)?.unregister()
        travellerTeam = scoreboard.registerNewTeam(teamName).apply {
            setAllowFriendlyFire(false)
            color(NamedTextColor.GOLD)
            addEntity(entity)
        }
    }

    private fun configureEntity() {
        entity.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "the_traveller")
        entity.customName(config.displayName.mm())
        entity.isCustomNameVisible = true

        // Despawnable when chunk unloads (treated as player escaped -> spawns next night!)
        entity.isPersistent = false
        entity.removeWhenFarAway = true
        entity.isGlowing = true

        entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
        entity.health = config.maxHealth
        entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = config.movementSpeed
        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 12.0
        entity.getAttribute(Attribute.FOLLOW_RANGE)?.baseValue = config.followRange // 64.0
        entity.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.6
    }

    private fun equipWayfinderNetheriteAndShield() {
        fun wayfinderPiece(mat: Material): ItemStack = ItemStack(mat).apply {
            editMeta(ArmorMeta::class.java) { meta ->
                meta.isUnbreakable = true
                meta.trim = ArmorTrim(TrimMaterial.GOLD, TrimPattern.WAYFINDER)
            }
        }

        val eq = entity.equipment
        eq.setHelmet(wayfinderPiece(Material.NETHERITE_HELMET))
        eq.setChestplate(wayfinderPiece(Material.NETHERITE_CHESTPLATE))
        eq.setLeggings(wayfinderPiece(Material.NETHERITE_LEGGINGS))
        eq.setBoots(wayfinderPiece(Material.NETHERITE_BOOTS))
        eq.setItemInMainHand(ItemStack(Material.MACE))
        eq.setItemInOffHand(createTravellerBannerShield())

        EquipmentSlot.entries.forEach { slot -> eq.setDropChance(slot, 0f) }
    }

    private fun createTravellerBannerShield(): ItemStack {
        val shield = ItemStack(Material.SHIELD)
        shield.editMeta { meta ->
            meta.isUnbreakable = true
        }

        val patterns = BannerPatternLayers.bannerPatternLayers()
            .add(Pattern(DyeColor.BLACK, PatternType.STRIPE_BOTTOM))
            .add(Pattern(DyeColor.BLACK, PatternType.MOJANG))
            .add(Pattern(DyeColor.RED, PatternType.RHOMBUS))
            .add(Pattern(DyeColor.RED, PatternType.STRIPE_BOTTOM))
            .add(Pattern(DyeColor.BLACK, PatternType.CIRCLE))
            .add(Pattern(DyeColor.BLACK, PatternType.CURLY_BORDER))
            .add(Pattern(DyeColor.BLACK, PatternType.GRADIENT))
            .build()

        shield.setData(DataComponentTypes.BASE_COLOR, DyeColor.BLACK)
        shield.setData(DataComponentTypes.BANNER_PATTERNS, patterns)
        return shield
    }

    private fun playSpawnIntro() {
        val loc = entity.location.add(0.0, 1.0, 0.0)
        world.playSound(loc, Sound.BLOCK_END_GATEWAY_SPAWN, SoundCategory.HOSTILE, 2f, 0.7f)
        world.playSound(loc, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 2f, 0.6f)
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 120, 1.2, 1.5, 1.2, 0.05, gildedTransition)
        world.spawnParticle(Particle.REVERSE_PORTAL, loc, 80, 0.8, 1.2, 0.8, 0.1)
    }

    private fun scheduleCoreLoops() {
        // 2-Tick Loop: Ambient particles, Stalking positioning (unprovoked), or Combat Pursuit (provoked)
        runTimer(2L, 2L) {
            if (!entity.isValid || entity.isDead) return@runTimer
            tickCircularAmbientParticles()

            if (!isProvoked) {
                entity.target = null
                val player = resolveHuntedPlayer() ?: resolveNearestSurvivalPlayer() ?: return@runTimer
                faceTarget(player.eyeLocation)
                tickStalkingMovement(player)
            } else {
                val target = resolveCombatTarget() ?: return@runTimer
                tickCombatPursuit(target)
            }
        }

        // 10-Tick (0.5s) Combat Skill & Chunk-Unload / Escape Detection Loop
        runTimer(10L, 10L) {
            if (entity.isDead) return@runTimer

            // If The Traveller's chunk unloaded (!entity.isValid), treat as player escaped -> spawns next night!
            if (!entity.isValid || checkHuntedPlayerEscaped()) {
                plugin.travellerManager.handlePlayerEscaped(this)
                return@runTimer
            }
            if (huntedPlayerId == null && entity.location.distanceSquared(spawnOrigin) > config.maxCombatDistanceSquared) {
                entity.teleport(spawnOrigin)
            }

            if (!isProvoked) return@runTimer

            val target = resolveCombatTarget() ?: return@runTimer
            entity.target = target

            if (isTeleporting || isSpinning) return@runTimer

            val now = System.currentTimeMillis()
            val distSq = entity.location.distanceSquared(target.location)

            // 1. Shield Bash if shield is disabled and target is within 6 blocks
            if (!isShieldActive() && distSq <= 36.0 && (now - lastShieldBashMillis) >= config.shieldBashCooldownMillis) {
                performShieldBash(target)
                return@runTimer
            }

            // 2. Swirl Mace Attack within 7 blocks
            if (distSq <= 49.0 && (now - lastSwirlMillis) >= config.swirlAttackCooldownMillis) {
                lastSwirlMillis = now
                triggerSwirlMaceAttack(target)
                return@runTimer
            }

            // 3. Homing Wither Skull Shower within 35 blocks
            if (distSq in 9.0..1225.0 && (now - lastSkullBarrageMillis) >= config.skullBarrageCooldownMillis) {
                lastSkullBarrageMillis = now
                triggerHomingWitherSkullShower(target)
            }
        }

        // 160-Tick (8s) Audible Enderman Stare Loop
        runTimer(160L, 160L) {
            if (!entity.isValid || entity.isDead) return@runTimer
            if (Random.nextDouble() <= 0.65) {
                world.playSound(entity.location, Sound.ENTITY_ENDERMAN_STARE, SoundCategory.HOSTILE, 2.0f, 0.6f)
            }
        }
    }

    // ========================================================================
    // STALKING, PROVOCATION & ACTIVE COMBAT PURSUIT
    // ========================================================================

    private fun tickStalkingMovement(player: Player) {
        val dist = entity.location.distance(player.location)

        if (pathfindingCooldownTicks > 0) {
            pathfindingCooldownTicks--
            val lastLoc = lastStalkTargetLoc
            if (lastLoc == null || player.location.distanceSquared(lastLoc) > 16.0) {
                pathfindingCooldownTicks = 0
            } else {
                return
            }
        }

        pathfindingCooldownTicks = 15
        lastStalkTargetLoc = player.location.clone()

        when {
            dist < 11.0 -> {
                val backDir = entity.location.toVector().subtract(player.location.toVector()).setY(0.0)
                if (backDir.lengthSquared() > 1e-4) backDir.normalize()
                val backLoc = entity.location.clone().add(backDir.multiply(4.0))
                entity.pathfinder.moveTo(backLoc, 0.85)
            }
            dist > 18.0 -> {
                entity.pathfinder.moveTo(player.location, 0.95)
            }
            else -> {
                entity.pathfinder.stopPathfinding()
            }
        }
    }

    private fun tickCombatPursuit(target: LivingEntity) {
        entity.target = target
        if (isSpinning) return

        val distSq = entity.location.distanceSquared(target.location)
        if (distSq <= 6.25) {
            faceTarget(target.eyeLocation)
        }

        if (combatPathCooldownTicks > 0) {
            combatPathCooldownTicks--
            return
        }
        combatPathCooldownTicks = 6

        if (distSq > 3.0) {
            entity.pathfinder.moveTo(target, 1.15)
        }
    }

    fun provoke(attacker: LivingEntity?) {
        if (isProvoked) return
        isProvoked = true
        combatPathCooldownTicks = 0

        world.playSound(entity.location, Sound.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 2.5f, 0.65f)
        world.playSound(entity.location, Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 2.0f, 0.6f)
        world.spawnParticle(
            Particle.DUST_COLOR_TRANSITION,
            entity.location.add(0.0, 1.2, 0.0),
            50, 0.8, 1.0, 0.8, 0.1,
            riftTransition
        )

        val target = attacker ?: resolveHuntedPlayer() ?: resolveNearestSurvivalPlayer()
        if (target != null) {
            entity.target = target
            entity.pathfinder.moveTo(target, 1.15)
            if (target is Player) {
                target.sendActionBar("<bold><red>The Traveller has been provoked!</red></bold>".mm())
            }
        }
    }

    private fun checkHuntedPlayerEscaped(): Boolean {
        val targetId = huntedPlayerId ?: return false
        val hunted = Bukkit.getPlayer(targetId)

        if (hunted == null || !hunted.isOnline || hunted.world != world) {
            return true
        }

        if (!isTeleporting && hunted.location.distanceSquared(entity.location) > config.maxCombatDistanceSquared) {
            return true
        }

        val aliveMillis = System.currentTimeMillis() - spawnTimestampMillis
        if (aliveMillis > 60_000L && plugin.travellerManager.isDaytime()) {
            return true
        }

        return false
    }

    private fun tickCircularAmbientParticles() {
        orbitAngle = (orbitAngle + 0.14) % (2.0 * Math.PI)
        val baseLoc = entity.location

        // Ground Wayfinder Circle
        val groundRadius = 1.65
        val groundY = 0.15
        for (i in 0 until 12) {
            val theta = orbitAngle + (i * Math.PI / 6.0)
            val pt = baseLoc.clone().add(cos(theta) * groundRadius, groundY, sin(theta) * groundRadius)
            val transition = if (i % 2 == 0) gildedTransition else crimsonTransition
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, transition)
        }
        for (node in 0..1) {
            val nodeAngle = orbitAngle + (node * Math.PI)
            val nodeLoc = baseLoc.clone().add(cos(nodeAngle) * groundRadius, groundY + 0.05, sin(nodeAngle) * groundRadius)
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, nodeLoc, 1, 0.02, 0.02, 0.02, 0.0)
        }

        // Counter-Rotating Oscillating Torso Ring
        val midRadius = 1.1
        val midY = 1.15 + sin(orbitAngle * 2.0) * 0.35
        for (i in 0 until 8) {
            val theta = -orbitAngle * 1.5 + (i * Math.PI / 4.0)
            val pt = baseLoc.clone().add(cos(theta) * midRadius, midY, sin(theta) * midRadius)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, gildedTransition)
        }

        // Crimson-Black Crown Halo
        val haloRadius = 0.55
        val haloY = 2.65
        for (i in 0 until 6) {
            val theta = (orbitAngle * 2.0) + (i * Math.PI / 3.0)
            val pt = baseLoc.clone().add(cos(theta) * haloRadius, haloY, sin(theta) * haloRadius)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0.0, 0.0, 0.0, 0.0, crimsonTransition)
        }
    }

    // ========================================================================
    // SHIELD BASH SKILL
    // ========================================================================

    fun performShieldBash(target: LivingEntity) {
        val now = System.currentTimeMillis()
        if ((now - lastShieldBashMillis) < config.shieldBashCooldownMillis) return
        lastShieldBashMillis = now

        entity.swingOffHand()

        val origin = entity.location.clone().add(0.0, 1.0, 0.0)
        world.playSound(origin, Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 1.8f, 0.8f)
        world.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.HOSTILE, 1.6f, 0.9f)
        world.playSound(origin, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 1.2f, 1.4f)

        val lookDir = entity.location.direction.setY(0.0)
        if (lookDir.lengthSquared() > 1e-4) lookDir.normalize() else lookDir.setX(1.0)
        val impactLoc = origin.clone().add(lookDir.clone().multiply(1.2))

        world.spawnParticle(Particle.SWEEP_ATTACK, impactLoc, 3, 0.3, 0.1, 0.3, 0.0)
        world.spawnParticle(Particle.GUST, impactLoc, 6, 0.4, 0.4, 0.4, 0.0)
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, impactLoc, 30, 0.5, 0.5, 0.5, 0.05, crimsonTransition)

        val pushDir = target.location.toVector().subtract(entity.location.toVector()).setY(0.0)
        if (pushDir.lengthSquared() > 1e-4) pushDir.normalize() else pushDir.setX(1.0)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (target.isValid) {
                target.velocity = pushDir.multiply(config.shieldBashKnockback).setY(0.35)
                if (target is Player) {
                    target.sendActionBar("<bold><red>The Traveller bashes you back with his shield!</red></bold>".mm())
                }
            }
        })
    }

    // ========================================================================
    // COMBAT EVENTS & SKILLS
    // ========================================================================

    fun onDamaged(attacker: LivingEntity?) {
        provoke(attacker)
        world.playSound(entity.location, Sound.ENTITY_ENDERMAN_HURT, SoundCategory.HOSTILE, 1.8f, 0.65f)

        val target = attacker ?: resolveCombatTarget() ?: return
        entity.target = target

        if (!isShieldActive() && entity.location.distanceSquared(target.location) <= 25.0) {
            val now = System.currentTimeMillis()
            if ((now - lastShieldBashMillis) >= config.shieldBashCooldownMillis) {
                performShieldBash(target)
            }
        }

        val now = System.currentTimeMillis()
        if (!isTeleporting &&
            (now - lastTeleportMillis) >= config.teleportCooldownMillis &&
            Random.nextDouble() <= config.teleportProcChance
        ) {
            lastTeleportMillis = now
            triggerTravellerTeleport(target)
        }
    }

    fun onMeleeAttack(target: LivingEntity) {
        world.playSound(target.location, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 1.5f, 0.75f)
        world.spawnParticle(
            Particle.DUST_COLOR_TRANSITION,
            target.location.add(0.0, 1.0, 0.0),
            25, 0.4, 0.4, 0.4, 0.05,
            riftTransition
        )
    }

    private fun triggerTravellerTeleport(target: LivingEntity) {
        isTeleporting = true
        world.playSound(entity.location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 1.8f, 1.6f)
        world.playSound(entity.location, Sound.BLOCK_BELL_USE, SoundCategory.HOSTILE, 1.8f, 1.5f)

        runLater(10L) {
            if (!entity.isValid) {
                isTeleporting = false
                return@runLater
            }
            spawnParticleSphere(entity.location.add(0.0, 1.2, 0.0), radius = 3.5, points = 180)
        }

        listOf(25L, 30L, 35L).forEach { delay ->
            runLater(delay) {
                if (!entity.isValid) return@runLater
                val cLoc = entity.location.add(0.0, 1.2, 0.0)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cLoc, 80, 1.1, 1.1, 1.1, 0.0, crimsonTransition)
                world.spawnParticle(Particle.REVERSE_PORTAL, cLoc, 60, 1.0, 1.0, 1.0, 0.05)
                world.spawnParticle(Particle.SCULK_SOUL, cLoc, 25, 0.8, 0.8, 0.8, 0.02)
            }
        }

        runLater(38L) {
            if (!entity.isValid) return@runLater
            world.playSound(entity.location, Sound.BLOCK_END_GATEWAY_SPAWN, SoundCategory.HOSTILE, 1.8f, 0.6f)
            world.playSound(entity.location, Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.8f, 0.6f)
        }

        runLater(40L) {
            try {
                if (!entity.isValid) return@runLater
                val origin = entity.location.add(0.0, 1.2, 0.0)
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, origin, 150, 1.4, 1.4, 1.4, 0.02, riftTransition)
                world.spawnParticle(Particle.FLASH, origin, 1, 0.0, 0.0, 0.0, 0.0, goldColor)

                val currentTarget = if (target.isValid && target.world == world) {
                    target
                } else {
                    resolveCombatTarget()
                }

                val dest = if (currentTarget != null && currentTarget.world == world) {
                    findTeleportSpotAround(currentTarget.location, spreadRadius = 4.0)
                } else {
                    entity.location
                }

                entity.teleport(dest)
                world.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 0.7f)
                world.playSound(dest, Sound.ENTITY_SHULKER_TELEPORT, SoundCategory.HOSTILE, 1.8f, 0.6f)
                world.playSound(dest, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.HOSTILE, 1.5f, 1.6f)

                if (currentTarget != null) {
                    entity.target = currentTarget
                    entity.pathfinder.moveTo(currentTarget, 1.15)
                }

                listOf(2L, 5L).forEach { postDelay ->
                    runLater(postDelay) {
                        if (!entity.isValid) return@runLater
                        val aLoc = entity.location.add(0.0, 1.2, 0.0)
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, aLoc, 100, 1.2, 1.2, 1.2, 0.0, gildedTransition)
                        world.spawnParticle(Particle.PORTAL, aLoc, 80, 1.0, 1.0, 1.0, 0.2)
                    }
                }
            } finally {
                runLater(6L) { isTeleporting = false }
            }
        }
    }

    private fun triggerSwirlMaceAttack(target: LivingEntity) {
        isSpinning = true
        world.playSound(entity.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.8f, 0.8f)
        world.playSound(entity.location, Sound.ENTITY_BREEZE_INHALE, SoundCategory.HOSTILE, 1.8f, 0.9f)

        val lungeDir = target.location.toVector().subtract(entity.location.toVector()).setY(0.0)
        if (lungeDir.lengthSquared() > 1e-4) {
            lungeDir.normalize().multiply(0.55).setY(0.25)
            entity.velocity = lungeDir
        }

        runLater(10L) {
            if (!entity.isValid) {
                isSpinning = false
                return@runLater
            }

            var spinTick = 0
            var spinTask: BukkitTask? = null
            spinTask = runTimer(1L, 1L) {
                spinTick++
                if (!entity.isValid || spinTick > 10) {
                    spinTask?.cancel()
                    return@runTimer
                }

                entity.setRotation(entity.yaw + 60f, entity.pitch)
                if (spinTick % 2 == 0) {
                    entity.swingMainHand()
                }
                if (spinTick == 1 || spinTick == 6) {
                    val pLoc = entity.location.add(0.0, 1.0, 0.0)
                    world.spawnParticle(Particle.CLOUD, pLoc, 7, 0.9, 0.9, 0.9, 0.0)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pLoc, 15, 0.9, 0.5, 0.9, 0.0, gildedTransition)
                }
            }

            runLater(10L) {
                isSpinning = false
                if (!entity.isValid) return@runLater

                val center = entity.location.add(0.0, 1.0, 0.0)
                entity.swingMainHand()
                world.spawnParticle(Particle.CLOUD, center, 15, 1.2, 0.9, 1.2, 0.02)
                world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 2.2f, 0.8f)
                world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2.0f, 0.7f)

                for (i in 0 until 8) {
                    val angle = 2.0 * Math.PI * i / 8.0
                    val ringPt = center.clone().add(cos(angle) * 2.2, 0.0, sin(angle) * 2.2)
                    world.spawnParticle(Particle.SWEEP_ATTACK, ringPt, 3, 0.3, 0.1, 0.3, 0.0)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, ringPt, 10, 0.3, 0.2, 0.3, 0.0, riftTransition)
                }

                val pierceSource = DamageSource.builder(DamageType.MAGIC)
                    .withCausingEntity(entity)
                    .withDirectEntity(entity)
                    .build()

                entity.location.getNearbyLivingEntities(4.0).forEach { victim ->
                    if (victim == entity) return@forEach
                    victim.damage(8.0, pierceSource)
                    val pushDir = victim.location.toVector().subtract(entity.location.toVector()).setY(0.0)
                    if (pushDir.lengthSquared() > 1e-4) pushDir.normalize() else pushDir.setX(1.0)
                    victim.velocity = pushDir.multiply(1.4).setY(0.65)
                }
            }
        }
    }

    private fun triggerHomingWitherSkullShower(primaryTarget: LivingEntity) {
        world.playSound(entity.location, Sound.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.6f, 0.75f)
        for (i in 0 until 3) {
            runLater(i * 10L) {
                if (!entity.isValid || entity.isDead) return@runLater
                val currentTarget = if (primaryTarget.isValid && !primaryTarget.isDead) {
                    primaryTarget
                } else {
                    resolveCombatTarget() ?: return@runLater
                }
                entity.swingMainHand()
                spawnHomingWitherSkull(currentTarget)
            }
        }
    }

    private fun spawnHomingWitherSkull(target: LivingEntity) {
        val spawnLoc = entity.eyeLocation.clone().add(entity.location.direction.normalize().multiply(0.8))
        val initialDir = target.location.clone().add(0.0, 1.1, 0.0)
            .toVector().subtract(spawnLoc.toVector()).normalize()

        world.playSound(spawnLoc, Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 1.8f, 0.75f)
        world.playSound(spawnLoc, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.8f, 1.0f)

        val skull = world.spawn(spawnLoc, WitherSkull::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { ws ->
            ws.shooter = entity
            ws.persistentDataContainer.set(MobKeys.TRAVELLER_PROJECTILE, PersistentDataType.BYTE, 1)
            ws.velocity = initialDir.clone().multiply(0.9)
        }

        var ticks = 0
        var homingTask: BukkitTask? = null
        homingTask = runTimer(1L, 1L) {
            ticks++
            if (!skull.isValid || skull.isDead || !target.isValid || target.isDead ||
                ticks > 80 || skull.location.distanceSquared(spawnLoc) > 1600.0
            ) {
                if (skull.isValid) {
                    detonateHomingSkull(skull.location)
                    skull.remove()
                }
                homingTask?.cancel()
                return@runTimer
            }

            val targetPos = target.location.clone().add(0.0, 1.1, 0.0).toVector()
            val desiredDir = targetPos.subtract(skull.location.toVector())
            if (desiredDir.lengthSquared() > 1e-4) {
                desiredDir.normalize()
                val currentVel = skull.velocity
                val currentDir = if (currentVel.lengthSquared() > 1e-4) currentVel.normalize() else desiredDir
                val steered = currentDir.multiply(0.65).add(desiredDir.multiply(0.35)).normalize()
                skull.velocity = steered.multiply(0.9)
            }

            val sLoc = skull.location
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, sLoc, 4, 0.15, 0.15, 0.15, 0.0, riftTransition)
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, sLoc, 3, 0.15, 0.15, 0.15, 0.02)
            world.spawnParticle(Particle.SMOKE, sLoc, 2, 0.1, 0.1, 0.1, 0.01)

            val hitEntity = sLoc.getNearbyLivingEntities(1.5).any { it != entity }
            val hitBlock = !sLoc.block.isPassable
            if (hitEntity || hitBlock) {
                detonateHomingSkull(sLoc)
                skull.remove()
                homingTask?.cancel()
            }
        }
    }

    fun detonateHomingSkull(loc: Location) {
        world.playSound(loc, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.HOSTILE, 1.5f, 1.0f)
        world.spawnParticle(Particle.LAVA, loc, 15, 0.5, 0.5, 0.5, 0.0)
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc, 25, 0.3, 0.3, 0.3, 0.12)
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 35, 0.6, 0.6, 0.6, 0.05, riftTransition)

        val magicSource = DamageSource.builder(DamageType.WITHER_SKULL)
            .withCausingEntity(entity)
            .withDirectEntity(entity)
            .build()

        loc.getNearbyLivingEntities(2.5).forEach { victim ->
            if (victim == entity) return@forEach
            victim.damage(8.0, magicSource)
            victim.fireTicks = 60
            victim.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 80, 0, false, true, true))
        }
    }

    fun playRiftVanishAndCleanup() {
        if (entity.isValid) {
            val loc = entity.location.add(0.0, 1.2, 0.0)
            world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 0.6f)
            world.spawnParticle(Particle.REVERSE_PORTAL, loc, 100, 0.8, 1.2, 0.8, 0.1)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, loc, 80, 1.0, 1.2, 1.0, 0.05, crimsonTransition)
        }
        forceCleanup(killEntity = true)
    }

    fun onDeath() {
        world.playSound(entity.location, Sound.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 2.0f, 0.6f)
        world.spawnParticle(
            Particle.DUST_COLOR_TRANSITION,
            entity.location.add(0.0, 1.0, 0.0),
            120, 1.0, 1.2, 1.0, 0.1,
            riftTransition
        )
        forceCleanup(killEntity = false)
    }

    fun forceCleanup(killEntity: Boolean) {
        cancelAllTasks()
        plugin.bossBarManager.unregisterById(travellerId)
        travellerTeam?.unregister()
        travellerTeam = null

        world.entities.forEach { ent ->
            if (ent.persistentDataContainer.has(MobKeys.TRAVELLER_PROJECTILE, PersistentDataType.BYTE)) {
                ent.remove()
            }
        }

        if (killEntity) {
            if (entity.isValid) {
                entity.remove()
            } else {
                (Bukkit.getEntity(travellerId) as? WitherSkeleton)?.remove()
            }
        }
    }

    private fun resolveHuntedPlayer(): Player? {
        val id = huntedPlayerId ?: return null
        val p = Bukkit.getPlayer(id)
        return if (p != null && p.isOnline && !p.isDead && p.world == world &&
            (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)
        ) p else null
    }

    private fun resolveNearestSurvivalPlayer(): Player? {
        return entity.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
            .minByOrNull { it.location.distanceSquared(entity.location) }
    }

    private fun resolveCombatTarget(): LivingEntity? {
        val current = entity.target
        if (current != null && current.isValid && !current.isDead && current.world == world) {
            if (current !is Player || (current.gameMode == GameMode.SURVIVAL || current.gameMode == GameMode.ADVENTURE)) {
                return current
            }
        }
        val hunted = resolveHuntedPlayer()
        if (hunted != null) return hunted
        return resolveNearestSurvivalPlayer()
    }

    private fun faceTarget(targetLocation: Location) {
        val dir = targetLocation.toVector().subtract(entity.eyeLocation.toVector())
        if (dir.lengthSquared() > 1e-4) {
            val rot = entity.location.setDirection(dir)
            entity.setRotation(rot.yaw, rot.pitch.coerceIn(-30f, 30f))
        }
    }

    private fun spawnParticleSphere(center: Location, radius: Double, points: Int) {
        val goldenAngle = Math.PI * (3.0 - sqrt(5.0))
        for (i in 0 until points) {
            val y = 1.0 - (i / (points - 1).toDouble()) * 2.0
            val r = sqrt(1.0 - y * y)
            val theta = goldenAngle * i
            val x = cos(theta) * r * radius
            val z = sin(theta) * r * radius
            val pLoc = center.clone().add(x, y * radius, z)
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pLoc, 1, 0.0, 0.0, 0.0, 0.0, riftTransition)
        }
    }

    private fun findTeleportSpotAround(targetLoc: Location, spreadRadius: Double): Location {
        repeat(12) {
            val angle = Random.nextDouble(0.0, 2.0 * Math.PI)
            val r = Random.nextDouble(2.0, spreadRadius)
            val dx = cos(angle) * r
            val dz = sin(angle) * r
            val candidate = targetLoc.clone().add(dx, 0.0, dz)
            for (dy in 2 downTo -3) {
                val check = candidate.clone().add(0.0, dy.toDouble(), 0.0)
                val ground = check.clone().add(0.0, -1.0, 0.0).block
                if (ground.type.isSolid && !ground.isLiquid && check.block.isPassable && check.clone().add(0.0, 1.0, 0.0).block.isPassable) {
                    val dir = targetLoc.toVector().subtract(check.toVector())
                    if (dir.lengthSquared() > 1e-4) check.setDirection(dir)
                    return check
                }
            }
        }
        return targetLoc.clone()
    }

    private fun runLater(delay: Long, action: () -> Unit): BukkitTask {
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable(action), delay)
        tasks.add(task)
        return task
    }

    private fun runTimer(delay: Long, period: Long, action: () -> Unit): BukkitTask {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(action), delay, period)
        tasks.add(task)
        return task
    }

    private fun cancelAllTasks() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }
}