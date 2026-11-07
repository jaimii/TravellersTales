package project.kompass.travellersTales.boss.herobrine

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Team
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.boss.bar.BossBarSettings
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.ExperienceUtil
import project.kompass.travellersTales.util.mm
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class ActiveHerobrineFight(
    private val plugin: TravellersTales,
    val world: World,
    val spawnOrigin: Location,
    var config: HerobrineConfig,
    val summonerId: UUID? = null
) {
    val tablistUuid: UUID = UUID.randomUUID()
    lateinit var mannequin: Mannequin
        private set

    private val tasks = mutableListOf<BukkitTask>()
    val activeSteves = mutableListOf<SteveMinionController>()
    private var herobrineTeam: Team? = null

    // Saved world state for Day/Night sky flickering without advancing day count
    private val savedFullTime: Long = world.fullTime
    private val wasAdvanceTime: Boolean = world.getGameRuleValue(GameRules.ADVANCE_TIME)

    // Slam Settings
    private val slamRadius: Double = 8.0
    private val phase1SlamDamage: Double = 4.0  // 2 hearts unblockable/armor-ignoring
    private val phase2SlamDamage: Double = 8.0  // 4 hearts unblockable/armor-ignoring
    private val phase3SlamDamage: Double = 10.0 // 10 unblockable/armor-ignoring damage

    // Fight State Machine
    var currentPhase: Int = 0 // 0 = Pre-Phase 1, 1 = Phase 1, 2 = Phase 2, 3 = Phase 3
        private set
    var isFloatingInvulnerable: Boolean = false
        private set
    var isDyingAnimation: Boolean = false
        private set
    private var isSlamming: Boolean = false
    private var waitingForStevesToDie: Boolean = false
    private var usingRangedStance: Boolean = false
    private var stanceSwitchTicks: Int = 0
    private var attackCooldownTicks: Int = 0
    private var damageSinceLastSteveWave: Double = 0.0
    private var currentTarget: Player? = null
    var isDeadOrRemoved: Boolean = false
        private set

    private val redstoneDust = Particle.DustOptions(Color.fromRGB(255, 15, 15), 1.6f)
    private val deepRedTransition = Particle.DustTransition(
        Color.fromRGB(255, 0, 0),
        Color.fromRGB(120, 0, 0),
        1.8f
    )
    private val rainbowTeamColors = listOf(
        NamedTextColor.RED,
        NamedTextColor.GOLD,
        NamedTextColor.YELLOW,
        NamedTextColor.GREEN,
        NamedTextColor.AQUA,
        NamedTextColor.BLUE,
        NamedTextColor.LIGHT_PURPLE,
        NamedTextColor.DARK_PURPLE
    )
    private val rainbowDustColors = listOf(
        Color.fromRGB(255, 0, 50),
        Color.fromRGB(255, 140, 0),
        Color.fromRGB(255, 235, 0),
        Color.fromRGB(0, 235, 80),
        Color.fromRGB(0, 225, 255),
        Color.fromRGB(60, 80, 255),
        Color.fromRGB(200, 0, 255)
    )
    private var rainbowColorIndex: Int = 0

    @Suppress("UnstableApiUsage")
    fun start() {
        setupTeam()
        HerobrinePacketController.broadcastTablistAdd(tablistUuid, "Herobrine")

        mannequin = world.spawn(spawnOrigin, Mannequin::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { m ->
            m.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, "herobrine")
            m.profile = HerobrineSkinUtil.createMannequinProfile(tablistUuid, "Herobrine")
            m.customName("<white>Herobrine</white>".mm())
            m.isCustomNameVisible = true
            m.description = null
            m.isImmovable = false
            m.setGravity(true)
            m.isPersistent = true

            m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = config.maxHealth
            m.health = config.maxHealth
            m.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = 12.0
            m.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.6
            m.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 1000.0
            m.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER)?.baseValue = 0.0
        }
        herobrineTeam?.addEntity(mannequin)

        spawnOrigin.getNearbyPlayers(64.0).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, false, true))
        }

        Bukkit.broadcast("<yellow>Herobrine has joined the game.</yellow>".mm())
        DiscordSrvHook.sendJoinMessage()

        scheduleCoreLoops()
    }

    private fun setupTeam() {
        val sb = Bukkit.getScoreboardManager().mainScoreboard
        val teamName = "HB_${tablistUuid.toString().take(8)}"
        sb.getTeam(teamName)?.unregister()
        herobrineTeam = sb.registerNewTeam(teamName).apply {
            setAllowFriendlyFire(false)
            color(NamedTextColor.WHITE)
        }
    }

    // ========================================================================
    // PRE-PHASE 1 -> PHASE 1 AWAKENING
    // ========================================================================

    fun awakenPhase1(firstAttacker: Player?) {
        if (currentPhase > 0) return
        currentPhase = 1
        currentTarget = firstAttacker ?: resolveSummonerOrNearest()

        val barSettings = BossBarSettings(
            enabled = true,
            title = "<bold><white>Herobrine</white></bold>",
            color = BossBar.Color.WHITE,
            overlay = BossBar.Overlay.NOTCHED_10,
            flags = setOf(BossBar.Flag.DARKEN_SCREEN),
            radius = 96.0
        )
        plugin.bossBarManager.createBossBar(mannequin, barSettings)

        world.setStorm(true)
        world.isThundering = true
        if (wasAdvanceTime) {
            world.setGameRule(GameRules.ADVANCE_TIME, false)
        }

        var toggleNight = true
        runTimer(10L, 40L) {
            if (isDeadOrRemoved) return@runTimer
            val currentDayBase = (savedFullTime / 24000L) * 24000L
            world.fullTime = currentDayBase + if (toggleNight) 18000L else 6000L
            toggleNight = !toggleNight
        }

        enterFloatingInvulnerableState(spawnSteves = false)
        runLater(40L) {
            if (!isDeadOrRemoved) {
                val slamTarget = pickRandomOrSolePlayer() ?: return@runLater
                performPhase1RedstoneSlam(slamTarget)
            }
        }
    }

    // ========================================================================
    // PHASE TRANSITIONS (EVERY 500 HP MARK + DARKNESS UNTIL EXPLOSION)
    // ========================================================================

    private fun playPhaseTransitionAnimation(isPhase3Rainbow: Boolean, onExplosionComplete: () -> Unit) {
        damageSinceLastSteveWave = 0.0
        waitingForStevesToDie = false
        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        isFloatingInvulnerable = true
        mannequin.setGravity(false)
        val floatLoc = mannequin.location.clone().add(0.0, 6.0, 0.0)
        mannequin.teleport(floatLoc)
        mannequin.velocity = Vector(0.0, 0.0, 0.0)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

        // Darkness to nearby players until he explodes at tick 75
        mannequin.location.getNearbyPlayers(96.0).forEach { player ->
            player.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 85, 0, false, false, true))
        }

        // 16x16 Rainbow ground aura during Phase 3 transition
        var rainbowTask: BukkitTask? = null
        if (isPhase3Rainbow) {
            rainbowTask = runTimer(1L, 2L) {
                if (isDeadOrRemoved || !mannequin.isValid) return@runTimer
                spawnRainbowGroundAura(mannequin.location, radius = 8.0, count = 24)
            }
        }

        // 4 Successive Lightning Strikes at ticks 15, 30, 45, 60
        listOf(15L, 30L, 45L, 60L).forEach { delay ->
            runLater(delay) {
                if (isDeadOrRemoved || !mannequin.isValid) return@runLater
                val loc = mannequin.location
                world.strikeLightningEffect(loc)
                world.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 3.0f, 0.8f)
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    loc.clone().add(0.0, 1.0, 0.0),
                    60, 1.2, 1.2, 1.2, 0.0,
                    deepRedTransition
                )
            }
        }

        // Tick 75: Clear Darkness & Explode
        runLater(75L) {
            rainbowTask?.cancel()
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater
            val loc = mannequin.location

            loc.getNearbyPlayers(128.0).forEach { player ->
                player.removePotionEffect(PotionEffectType.DARKNESS)
            }

            val explosionPower = if (isPhase3Rainbow) 6.0f else 4.0f // 6.0f = End Crystal damage, 4.0f = TNT damage
            world.createExplosion(loc, explosionPower, false, false, mannequin)
            world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1)

            if (isPhase3Rainbow) {
                // Strike lightning on his 3x3 radius
                for (dx in listOf(-3.0, 0.0, 3.0)) {
                    for (dz in listOf(-3.0, 0.0, 3.0)) {
                        val lLoc = loc.clone().add(dx, 0.0, dz)
                        lLoc.y = (world.getHighestBlockYAt(lLoc) + 1).toDouble()
                        world.strikeLightningEffect(lLoc)
                    }
                }
            }

            onExplosionComplete()
        }
    }

    private fun enterPhase2() {
        if (currentPhase >= 2) return
        currentPhase = 2
        mannequin.health = config.phase2Threshold

        mannequin.customName("<bold><red>Herobrine</red></bold>".mm())
        plugin.bossBarManager.updateBarAppearance(
            mannequin,
            titleMiniMessage = "<bold><red>Herobrine</red></bold>",
            color = BossBar.Color.RED
        )

        playPhaseTransitionAnimation(isPhase3Rainbow = false) {
            val target = pickRandomOrSolePlayer() ?: resolveCombatTarget()
            if (target != null) {
                performPhase2PreSlamSequence(target)
            } else {
                isFloatingInvulnerable = false
                mannequin.setGravity(true)
                mannequin.equipment.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))
            }
        }
    }

    private fun enterPhase3() {
        if (currentPhase >= 3) return
        currentPhase = 3
        mannequin.health = config.phase3Threshold

        mannequin.isGlowing = true
        mannequin.customName("<bold><light_purple>Herobrine</light_purple></bold>".mm())
        plugin.bossBarManager.updateBarAppearance(
            mannequin,
            titleMiniMessage = "<bold><light_purple>Herobrine</light_purple></bold>",
            color = BossBar.Color.PURPLE
        )

        playPhaseTransitionAnimation(isPhase3Rainbow = true) {
            val target = pickRandomOrSolePlayer() ?: resolveCombatTarget()
            if (target != null) {
                performPhase3PreSlamSequence(target)
            } else {
                isFloatingInvulnerable = false
                mannequin.setGravity(true)
                mannequin.equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))
            }
        }
    }

    private fun spawnRainbowGroundAura(center: Location, radius: Double, count: Int) {
        val groundY = world.getHighestBlockYAt(center).toDouble() + 0.2
        repeat(count) {
            val dx = Random.nextDouble(-radius, radius)
            val dz = Random.nextDouble(-radius, radius)
            val yOffset = Random.nextDouble(0.0, 6.0)
            val pt = Location(world, center.x + dx, groundY + yOffset, center.z + dz)
            val c1 = rainbowDustColors.random()
            val c2 = rainbowDustColors.random()
            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                pt,
                1, 0.1, 0.4, 0.1, 0.0,
                Particle.DustTransition(c1, c2, 1.7f)
            )
        }
    }

    // ========================================================================
    // CORE AI & AMBIENT PARTICLES
    // ========================================================================

    private fun scheduleCoreLoops() {
        // 2-Tick Loop: Redstone, Spark, Enchanting Table, and Phase 3 Prismatic Rainbow Glow
        runTimer(2L, 2L) {
            if (!mannequin.isValid || mannequin.isDead || isDeadOrRemoved || isDyingAnimation) return@runTimer
            val pLoc = mannequin.location.add(0.0, 1.1, 0.0)

            if (currentPhase >= 3) {
                rainbowColorIndex = (rainbowColorIndex + 1) % rainbowTeamColors.size
                herobrineTeam?.color(rainbowTeamColors[rainbowColorIndex])
                val c1 = rainbowDustColors[rainbowColorIndex % rainbowDustColors.size]
                val c2 = rainbowDustColors[(rainbowColorIndex + 1) % rainbowDustColors.size]
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION,
                    pLoc,
                    6, 0.4, 0.6, 0.4, 0.0,
                    Particle.DustTransition(c1, c2, 1.8f)
                )
                world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 3, 0.35, 0.5, 0.35, 0.02)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 5, 0.4, 0.5, 0.4, 0.5)
            } else if (currentPhase == 2) {
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pLoc, 6, 0.4, 0.6, 0.4, 0.0, deepRedTransition)
                world.spawnParticle(Particle.DUST, pLoc, 4, 0.35, 0.5, 0.35, 0.0, redstoneDust)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 4, 0.4, 0.5, 0.4, 0.5)
            } else {
                world.spawnParticle(Particle.DUST, pLoc, 3, 0.35, 0.5, 0.35, 0.0, redstoneDust)
                world.spawnParticle(Particle.ELECTRIC_SPARK, pLoc, 3, 0.35, 0.5, 0.35, 0.02)
                world.spawnParticle(Particle.ENCHANT, pLoc.clone().add(0.0, 0.5, 0.0), 4, 0.4, 0.5, 0.4, 0.5)
            }
        }

        // 1-Tick AI Loop
        runTimer(1L, 1L) {
            if (!mannequin.isValid || mannequin.isDead || isDeadOrRemoved || isDyingAnimation) return@runTimer
            if (attackCooldownTicks > 0) attackCooldownTicks--

            if (mannequin.location.distanceSquared(spawnOrigin) > config.maxCombatDistance * config.maxCombatDistance) {
                mannequin.teleport(spawnOrigin)
            }

            if (currentPhase == 0) {
                mannequin.velocity = Vector(0.0, mannequin.velocity.y.coerceAtMost(0.0), 0.0)
                val stareTarget = resolveSummonerOrNearest() ?: return@runTimer
                faceLocation(stareTarget.eyeLocation)
                return@runTimer
            }

            val target = resolveCombatTarget() ?: return@runTimer
            if (activeSteves.isNotEmpty()) {
                val iter = activeSteves.iterator()
                while (iter.hasNext()) {
                    val steve = iter.next()
                    if (!steve.mannequin.isValid || steve.mannequin.isDead) {
                        iter.remove()
                    } else {
                        steve.tick(target)
                    }
                }
                if (waitingForStevesToDie && activeSteves.isEmpty()) {
                    waitingForStevesToDie = false
                    val chosen = pickRandomOrSolePlayer() ?: target
                    when (currentPhase) {
                        1 -> performPhase1RedstoneSlam(chosen)
                        2 -> performPhase2PreSlamSequence(chosen)
                        else -> performPhase3PreSlamSequence(chosen)
                    }
                    return@runTimer
                }
            }

            if (isFloatingInvulnerable) {
                mannequin.velocity = Vector(0.0, sin(Bukkit.getCurrentTick() * 0.15) * 0.04, 0.0)
                faceLocation(target.eyeLocation)
                return@runTimer
            }

            if (isSlamming) return@runTimer

            tickGroundCombat(target)
        }
    }

    private fun getPhaseSwordMaterial(): Material = when (currentPhase) {
        3 -> Material.NETHERITE_SWORD
        2 -> Material.DIAMOND_SWORD
        else -> Material.IRON_SWORD
    }

    private fun getPhaseAxeMaterial(): Material = when (currentPhase) {
        3 -> Material.NETHERITE_AXE
        2 -> Material.DIAMOND_AXE
        else -> Material.IRON_AXE
    }

    private fun createPhaseRangedWeapon(): ItemStack {
        return if (currentPhase >= 3) {
            ItemStack(Material.CROSSBOW).apply {
                editMeta { meta ->
                    meta.addEnchant(Enchantment.QUICK_CHARGE, 3, true)
                    meta.addEnchant(Enchantment.PIERCING, 4, true)
                }
            }
        } else {
            ItemStack(Material.BOW)
        }
    }

    private fun tickGroundCombat(target: Player) {
        faceLocation(target.eyeLocation)
        stanceSwitchTicks++

        if (stanceSwitchTicks >= 160) {
            stanceSwitchTicks = 0
            usingRangedStance = !usingRangedStance
            val weapon = if (usingRangedStance) createPhaseRangedWeapon() else ItemStack(getPhaseSwordMaterial())
            mannequin.equipment.setItemInMainHand(weapon)
            world.playSound(mannequin.location, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.HOSTILE, 1.5f, 1.0f)
        }

        val mLoc = mannequin.location
        val tLoc = target.location
        val flatDiff = tLoc.toVector().subtract(mLoc.toVector()).setY(0.0)
        val distSq = flatDiff.lengthSquared()

        if (!usingRangedStance) {
            if (distSq > 5.5) {
                val moveDir = flatDiff.clone().normalize()
                val ahead = mLoc.clone().add(moveDir).block
                val jumpY = if (mannequin.isOnGround && ahead.type.isSolid && ahead.getRelative(0, 1, 0).isPassable) 0.42 else mannequin.velocity.y
                mannequin.velocity = Vector(moveDir.x * config.movementSpeed, jumpY, moveDir.z * config.movementSpeed)
            }

            if (distSq <= 9.0 && kotlin.math.abs(tLoc.y - mLoc.y) <= 2.5 && attackCooldownTicks <= 0) {
                attackCooldownTicks = 15
                executePvpMeleeStrike(target)
            }
        } else {
            if (distSq < 64.0) {
                val backDir = mLoc.toVector().subtract(tLoc.toVector()).setY(0.0).normalize()
                mannequin.velocity = Vector(backDir.x * 0.25, mannequin.velocity.y, backDir.z * 0.25)
            } else if (distSq > 324.0) {
                val moveDir = flatDiff.clone().normalize()
                mannequin.velocity = Vector(moveDir.x * 0.28, mannequin.velocity.y, moveDir.z * 0.28)
            }

            if (attackCooldownTicks <= 0) {
                attackCooldownTicks = if (currentPhase >= 3) 26 else 35
                fireHomingArrow(target)
            }
        }
    }

    private fun executePvpMeleeStrike(target: Player) {
        val isPlayerShielding = target.isBlocking
        val swordMat = getPhaseSwordMaterial()
        val axeMat = getPhaseAxeMaterial()

        if (isPlayerShielding && Random.nextDouble() < 0.20) {
            mannequin.equipment.setItemInMainHand(ItemStack(axeMat))
            mannequin.swingMainHand()

            target.setCooldown(Material.SHIELD, 100)
            target.clearActiveItem()
            world.playSound(target.location, Sound.ITEM_SHIELD_BREAK, SoundCategory.HOSTILE, 2.0f, 0.9f)
            world.spawnParticle(Particle.DUST, target.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.4, 0.3, 0.0, redstoneDust)

            val axeSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(mannequin)
                .withDirectEntity(mannequin)
                .build()
            val axeDmg = when (currentPhase) {
                3 -> 18.0
                2 -> 16.0
                else -> 14.0
            }
            target.damage(axeDmg, axeSource)

            runLater(12L) {
                if (!usingRangedStance && mannequin.isValid) {
                    mannequin.equipment.setItemInMainHand(ItemStack(swordMat))
                }
            }
        } else {
            mannequin.equipment.setItemInMainHand(ItemStack(swordMat))
            mannequin.swingMainHand()

            if (isPlayerShielding) {
                world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.5f, 1.0f)
                val push = target.location.toVector().subtract(mannequin.location.toVector()).setY(0.0).normalize()
                target.velocity = target.velocity.add(push.multiply(0.4).setY(0.2))
            } else {
                val swordSource = DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(mannequin)
                    .withDirectEntity(mannequin)
                    .build()
                val swordDmg = when (currentPhase) {
                    3 -> 15.0
                    2 -> 13.0
                    else -> 11.0
                }
                target.damage(swordDmg, swordSource)
            }
        }
    }

    private fun fireHomingArrow(target: Player) {
        mannequin.swingMainHand()
        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        val initialDir = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()

        val shootSound = if (currentPhase >= 3) Sound.ITEM_CROSSBOW_SHOOT else Sound.ENTITY_ARROW_SHOOT
        world.playSound(eye, shootSound, SoundCategory.HOSTILE, 1.5f, 0.8f)

        val arrow = world.spawn(eye.add(initialDir), Arrow::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { a ->
            a.shooter = mannequin
            a.velocity = initialDir.multiply(1.45)
            a.damage = if (currentPhase >= 3) 8.5 else 7.0
            if (currentPhase >= 3) {
                a.pierceLevel = 4
            }
            a.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
        }

        var ticks = 0
        var homingTask: BukkitTask? = null
        homingTask = runTimer(1L, 1L) {
            ticks++
            if (!arrow.isValid || arrow.isDead || arrow.isInBlock || !target.isValid || target.isDead || ticks > 70) {
                homingTask?.cancel()
                return@runTimer
            }

            val desired = target.eyeLocation.toVector().subtract(arrow.location.toVector())
            if (desired.lengthSquared() > 1e-4) {
                desired.normalize()
                val cur = arrow.velocity
                val speed = cur.length().coerceIn(1.1, 1.6)
                val steered = cur.normalize().multiply(0.75).add(desired.multiply(0.25)).normalize()
                arrow.velocity = steered.multiply(speed)
            }
            world.spawnParticle(Particle.DUST, arrow.location, 2, 0.05, 0.05, 0.05, 0.0, redstoneDust)
        }
    }

    // ========================================================================
    // STEVE WAVES (PHASE 1 = 2+2, PHASE 2 = 3+3, PHASE 3 = 5+5 NETHERITE)
    // ========================================================================

    private fun enterFloatingInvulnerableState(spawnSteves: Boolean) {
        isFloatingInvulnerable = true
        mannequin.setGravity(false)
        val floatLoc = mannequin.location.clone().add(0.0, 5.5, 0.0)
        mannequin.teleport(floatLoc)
        mannequin.velocity = Vector(0.0, 0.0, 0.0)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

        world.playSound(floatLoc, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2.0f, 0.6f)
        world.spawnParticle(Particle.DUST, floatLoc.add(0.0, 1.0, 0.0), 40, 0.6, 0.8, 0.6, 0.0, redstoneDust)

        if (spawnSteves) {
            waitingForStevesToDie = true
            spawnSteveWave(phaseTier = currentPhase)
        }
    }

    private fun spawnSteveWave(phaseTier: Int) {
        val center = spawnOrigin.clone()
        val countPerType = when (phaseTier) {
            3 -> 5
            2 -> 3
            else -> 2
        }
        val total = countPerType * 2

        world.playSound(center, Sound.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.HOSTILE, 2.5f, 0.5f)
        for (i in 0 until total) {
            val angle = 2.0 * Math.PI * i / total
            val sLoc = center.clone().add(cos(angle) * 4.5, 0.0, sin(angle) * 4.5)
            sLoc.y = (world.getHighestBlockYAt(sLoc) + 1).toDouble()
            world.strikeLightningEffect(sLoc)

            val isArcher = i >= countPerType
            val controller = SteveMinionController.spawnSteve(sLoc, isArcher = isArcher, phaseTier = phaseTier)
            activeSteves.add(controller)
        }
    }

    // ========================================================================
    // PHASE 2 PRE-SLAM SEQUENCE
    // ========================================================================

    private fun performPhase2PreSlamSequence(target: Player) {
        isFloatingInvulnerable = true
        isSlamming = true
        currentTarget = target

        scheduleWaveThenStarCycle(startDelay = 0L, applyWither = false)

        runLater(170L) {
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater
            val tridentTarget = pickRandomOrSolePlayer() ?: resolveCombatTarget() ?: target
            chargeAndThrowChannelingTrident(tridentTarget)
        }
    }

    // ========================================================================
    // PHASE 3 PRE-SLAM SEQUENCE
    // ========================================================================

    private fun performPhase3PreSlamSequence(target: Player) {
        isFloatingInvulnerable = true
        isSlamming = true
        currentTarget = target

        // Cycle 1: Wave -> Star (ticks 0..165)
        scheduleWaveThenStarCycle(startDelay = 0L, applyWither = true)

        // Cycle 2: Wave -> Star (ticks 175..340)
        scheduleWaveThenStarCycle(startDelay = 175L, applyWither = true)

        // Step 3 (Tick 350): Guardian Laser -> Riptiding Steves -> 10-Damage Final Slam
        runLater(350L) {
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater
            performPhase3GuardianLaserAndRiptideSlam(target)
        }
    }

    private fun scheduleWaveThenStarCycle(startDelay: Long, applyWither: Boolean) {
        val armorIgnoreSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        val waveRadii = listOf(4.5, 8.5, 12.5)
        waveRadii.forEachIndexed { waveIdx, radius ->
            val telegraphDelay = startDelay + waveIdx * 24L
            val strikeDelay = telegraphDelay + 12L

            runLater(telegraphDelay) {
                if (isDeadOrRemoved) return@runLater
                val arenaLoc = (resolveCombatTarget() ?: return@runLater).location.clone()
                arenaLoc.y = (world.getHighestBlockYAt(arenaLoc) + 1).toDouble()

                world.playSound(arenaLoc, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 2.5f, 0.8f + waveIdx * 0.2f)
                val points = (radius * 6).toInt()
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = arenaLoc.clone().add(cos(angle) * radius, 0.2, sin(angle) * radius)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 3, 0.1, 0.1, 0.1, 0.0, deepRedTransition)
                }

                runLater(12L) {
                    if (isDeadOrRemoved) return@runLater
                    val strikes = 8 + waveIdx * 2
                    for (i in 0 until strikes) {
                        val angle = 2.0 * Math.PI * i / strikes
                        val sLoc = arenaLoc.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius)
                        sLoc.y = (world.getHighestBlockYAt(sLoc) + 1).toDouble()
                        world.strikeLightningEffect(sLoc)
                        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, sLoc.clone().add(0.0, 0.5, 0.0), 25, 0.8, 0.5, 0.8, 0.0, deepRedTransition)

                        sLoc.getNearbyPlayers(2.4).forEach { p ->
                            if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                                p.damage(5.0, armorIgnoreSource)
                                if (applyWither) {
                                    p.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 200, 0, false, true, true))
                                }
                            }
                        }
                    }
                }
            }
        }

        runLater(startDelay + 75L) {
            if (isDeadOrRemoved) return@runLater
            val starCenter = (resolveCombatTarget() ?: return@runLater).location.clone()
            starCenter.y = (world.getHighestBlockYAt(starCenter) + 1).toDouble()
            val starPoints = buildFivePointedStarLocations(starCenter, starRadius = 9.0)

            starPoints.forEach { pt ->
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt.clone().add(0.0, 0.2, 0.0), 15, 0.3, 0.1, 0.3, 0.0, deepRedTransition)
            }

            starPoints.forEachIndexed { idx, strikePt ->
                runLater(10L + idx * 8L) {
                    if (isDeadOrRemoved) return@runLater
                    val groundPt = strikePt.clone()
                    groundPt.y = (world.getHighestBlockYAt(groundPt) + 1).toDouble()

                    world.strikeLightningEffect(groundPt)
                    world.playSound(groundPt, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 2.0f, 1.1f)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, groundPt.clone().add(0.0, 0.5, 0.0), 30, 1.0, 0.6, 1.0, 0.0, deepRedTransition)

                    groundPt.getNearbyPlayers(2.6).forEach { p ->
                        if (!p.isDead && (p.gameMode == GameMode.SURVIVAL || p.gameMode == GameMode.ADVENTURE)) {
                            p.damage(5.0, armorIgnoreSource)
                            if (applyWither) {
                                p.addPotionEffect(PotionEffect(PotionEffectType.WITHER, 200, 0, false, true, true))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildFivePointedStarLocations(center: Location, starRadius: Double): List<Location> {
        val vertices = (0 until 5).map { i ->
            val angle = Math.toRadians(-90.0 + i * 72.0)
            center.clone().add(cos(angle) * starRadius, 0.0, sin(angle) * starRadius)
        }
        val starOrder = listOf(0, 2, 4, 1, 3)
        val tenPoints = mutableListOf<Location>()

        for (step in 0 until 5) {
            val vStart = vertices[starOrder[step]]
            val vEnd = vertices[starOrder[(step + 1) % 5]]
            val midpoint = vStart.clone().add(vEnd).multiply(0.5)
            tenPoints.add(vStart)
            tenPoints.add(midpoint)
        }
        return tenPoints
    }

    private fun performPhase3GuardianLaserAndRiptideSlam(fallbackTarget: Player) {
        val eye = mannequin.location.add(0.0, 1.62, 0.0)

        val laserGuardians = mutableListOf<Guardian>()
        val initialTargets = mannequin.location.getNearbyPlayers(32.0)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) && mannequin.hasLineOfSight(it) }

        for (p in initialTargets) {
            val g = world.spawn(eye, Guardian::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { guardian ->
                guardian.persistentDataContainer.set(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE, 1)
                guardian.isInvisible = true
                guardian.isInvulnerable = true
                guardian.isSilent = false
                guardian.setAI(true)
                guardian.setGravity(false)
                guardian.target = p
                guardian.setLaser(true)
            }
            laserGuardians.add(g)
        }

        var laserTick = 0
        var laserTask: BukkitTask? = null
        laserTask = runTimer(1L, 2L) {
            laserTick += 2
            if (isDeadOrRemoved || laserTick >= 40) {
                laserTask?.cancel()
                laserGuardians.forEach { it.remove() }
                return@runTimer
            }

            val currentOrigin = mannequin.location.add(0.0, 1.62, 0.0)
            laserGuardians.forEach { if (it.isValid) it.teleport(currentOrigin) }

            val pitch = 0.6f + (laserTick / 40.0f) * 1.4f
            world.playSound(currentOrigin, Sound.ENTITY_GUARDIAN_ATTACK, SoundCategory.HOSTILE, 2.0f, pitch)
        }

        runLater(40L) {
            laserGuardians.forEach { it.remove() }
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater

            val validPlayers = mannequin.location.getNearbyPlayers(32.0)
                .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) && mannequin.hasLineOfSight(it) }

            for (p in validPlayers) {
                launchRiptidingSteveMissile(p)
            }

            runLater(28L) {
                if (isDeadOrRemoved || !mannequin.isValid) return@runLater
                val slamTarget = pickRandomOrSolePlayer() ?: fallbackTarget
                executePhase3FinalSlam(slamTarget.location)
            }
        }
    }

    @Suppress("UnstableApiUsage")
    private fun launchRiptidingSteveMissile(target: Player) {
        val origin = mannequin.location.add(0.0, 1.2, 0.0)
        world.playSound(origin, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.HOSTILE, 2.5f, 1.0f)

        val steveMissile = world.spawn(origin, Mannequin::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { m ->
            m.persistentDataContainer.set(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE, 1)
            m.profile = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                .uuid(UUID.randomUUID())
                .name("Steve")
                .build()
            m.customName("<aqua>Steve</aqua>".mm())
            m.description = null
            m.isInvulnerable = true
            m.isImmovable = false
            m.setGravity(false)
            m.pose = Pose.SPIN_ATTACK
            m.equipment.setItemInMainHand(ItemStack(Material.TRIDENT))
        }

        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(steveMissile)
            .build()

        var ticks = 0
        var missileTask: BukkitTask? = null
        missileTask = runTimer(1L, 1L) {
            ticks++
            if (isDeadOrRemoved || !steveMissile.isValid || ticks > 45 || !target.isValid || target.isDead) {
                steveMissile.remove()
                missileTask?.cancel()
                return@runTimer
            }

            val targetCenter = target.location.add(0.0, 1.0, 0.0)
            val dir = targetCenter.toVector().subtract(steveMissile.location.toVector())
            val distSq = dir.lengthSquared()

            if (distSq > 1e-4) {
                val step = dir.clone().normalize().multiply(1.6)
                val nextLoc = steveMissile.location.add(step)
                nextLoc.setDirection(step)
                steveMissile.teleport(nextLoc)
                steveMissile.pose = Pose.SPIN_ATTACK
            }

            world.spawnParticle(Particle.SPLASH, steveMissile.location, 8, 0.2, 0.2, 0.2, 0.1)
            world.spawnParticle(Particle.ELECTRIC_SPARK, steveMissile.location, 4, 0.2, 0.2, 0.2, 0.05)

            if (distSq <= 3.24 || !steveMissile.location.block.isPassable) {
                val impactLoc = steveMissile.location.clone()
                world.strikeLightningEffect(impactLoc)
                world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.9f)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, impactLoc, 1)

                if (target.location.distanceSquared(impactLoc) <= 9.0) {
                    target.damage(10.0, unblockableSource)
                }

                steveMissile.remove()
                missileTask?.cancel()
            }
        }
    }

    private fun executePhase3FinalSlam(targetLocation: Location) {
        isFloatingInvulnerable = false
        isSlamming = false
        usingRangedStance = false
        stanceSwitchTicks = 0

        val slamLoc = targetLocation.clone()
        slamLoc.y = (world.getHighestBlockYAt(slamLoc) + 1).toDouble()

        mannequin.setGravity(true)
        mannequin.teleport(slamLoc)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.NETHERITE_SWORD))

        world.strikeLightningEffect(slamLoc)
        terraformSlamArea(slamLoc, slamRadius.toInt())

        world.playSound(slamLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3.0f, 0.75f)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, slamLoc, 1)

        val radiusSq = slamRadius * slamRadius
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        slamLoc.getNearbyLivingEntities(slamRadius, 5.0, slamRadius).forEach { victim ->
            if (victim == mannequin) return@forEach
            if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
            if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                return@forEach
            }

            val dx = victim.location.x - slamLoc.x
            val dz = victim.location.z - slamLoc.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                victim.damage(phase3SlamDamage, unblockableSource)

                val kb = victim.location.toVector().subtract(slamLoc.toVector()).setY(0.0)
                if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                victim.velocity = victim.velocity.add(kb.multiply(1.3).setY(0.55))
            }
        }
    }

    private fun chargeAndThrowChannelingTrident(target: Player) {
        val tridentStack = ItemStack(Material.TRIDENT).apply {
            editMeta { it.addEnchant(Enchantment.CHANNELING, 1, true) }
        }
        mannequin.equipment.setItemInMainHand(tridentStack)
        world.playSound(mannequin.location, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.HOSTILE, 3.0f, 1.2f)

        var chargeTick = 0
        var chargeTask: BukkitTask? = null
        chargeTask = runTimer(1L, 2L) {
            chargeTick += 2
            if (isDeadOrRemoved || chargeTick >= 25) {
                chargeTask?.cancel()
                return@runTimer
            }
            faceLocation(target.eyeLocation)
            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                mannequin.location.add(0.0, 1.5, 0.0),
                12, 0.5, 0.5, 0.5, 0.0,
                deepRedTransition
            )
        }

        runLater(26L) {
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater
            mannequin.swingMainHand()
            mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

            val eye = mannequin.location.add(0.0, 1.62, 0.0)
            val throwDir = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()
            world.playSound(eye, Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 2.5f, 0.9f)

            val trident = world.spawn(eye.add(throwDir), Trident::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { t ->
                t.shooter = mannequin
                t.itemStack = tridentStack
                t.velocity = throwDir.multiply(2.2)
                t.damage = 0.0
                t.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                t.persistentDataContainer.set(MobKeys.HEROBRINE_TRIDENT, PersistentDataType.BYTE, 1)
            }

            var flightTicks = 0
            var watchTask: BukkitTask? = null
            watchTask = runTimer(1L, 1L) {
                flightTicks++
                if (isDeadOrRemoved || !trident.isValid || trident.isDead) {
                    watchTask?.cancel()
                    return@runTimer
                }
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, trident.location, 3, 0.1, 0.1, 0.1, 0.0, deepRedTransition)

                if (flightTicks > 60) {
                    watchTask?.cancel()
                    val impactLoc = trident.location.clone()
                    trident.remove()
                    executePhase2InstantLightningSlam(impactLoc)
                }
            }
        }
    }

    fun executePhase2InstantLightningSlam(impactLocation: Location) {
        if (isDeadOrRemoved || !mannequin.isValid) return
        isFloatingInvulnerable = false
        isSlamming = false
        usingRangedStance = false
        stanceSwitchTicks = 0

        val slamLoc = impactLocation.clone()
        slamLoc.y = (world.getHighestBlockYAt(slamLoc) + 1).toDouble()

        mannequin.setGravity(true)
        mannequin.teleport(slamLoc)
        mannequin.equipment.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))

        world.strikeLightningEffect(slamLoc)
        terraformSlamArea(slamLoc, slamRadius.toInt())

        world.playSound(slamLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(slamLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3.0f, 0.75f)

        world.spawnParticle(Particle.GUST_EMITTER_LARGE, slamLoc.clone().add(0.0, 0.4, 0.0), 4, 1.5, 0.2, 1.5, 0.0)
        world.spawnParticle(
            Particle.BLOCK,
            slamLoc.clone().add(0.0, 0.5, 0.0),
            120, 2.5, 0.4, 2.5, 0.15,
            Material.REDSTONE_BLOCK.createBlockData()
        )

        for (wave in 1..4) {
            val r = (slamRadius / 4.0) * wave
            runLater((wave * 2).toLong()) {
                val points = (r * 10).toInt().coerceAtLeast(16)
                for (i in 0 until points) {
                    val angle = 2.0 * Math.PI * i / points
                    val pt = slamLoc.clone().add(cos(angle) * r, 0.2, sin(angle) * r)
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 3, 0.15, 0.05, 0.15, 0.0, deepRedTransition)
                }
            }
        }

        val radiusSq = slamRadius * slamRadius
        val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
            .withCausingEntity(mannequin)
            .withDirectEntity(mannequin)
            .build()

        slamLoc.getNearbyLivingEntities(slamRadius, 5.0, slamRadius).forEach { victim ->
            if (victim == mannequin) return@forEach
            if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
            if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                return@forEach
            }

            val dx = victim.location.x - slamLoc.x
            val dz = victim.location.z - slamLoc.z
            if ((dx * dx + dz * dz) <= radiusSq) {
                victim.damage(phase2SlamDamage, unblockableSource)

                val kb = victim.location.toVector().subtract(slamLoc.toVector()).setY(0.0)
                if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                victim.velocity = victim.velocity.add(kb.multiply(1.25).setY(0.55))
            }
        }
    }

    private fun performPhase1RedstoneSlam(target: Player) {
        isFloatingInvulnerable = false
        isSlamming = true
        currentTarget = target

        val slamTargetLoc = target.location.clone()
        slamTargetLoc.y = (world.getHighestBlockYAt(slamTargetLoc) + 1).toDouble()

        world.playSound(mannequin.location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.HOSTILE, 3.0f, 0.7f)
        world.playSound(slamTargetLoc, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.HOSTILE, 2.5f, 0.5f)

        var telegraphTick = 0
        var telegraphTask: BukkitTask? = null
        telegraphTask = runTimer(1L, 2L) {
            telegraphTick += 2
            if (isDeadOrRemoved || telegraphTick >= 30) {
                telegraphTask?.cancel()
                return@runTimer
            }
            for (i in 0 until 48) {
                val angle = 2.0 * Math.PI * i / 48
                val ringPt = slamTargetLoc.clone().add(cos(angle) * slamRadius, 0.15, sin(angle) * slamRadius)
                world.spawnParticle(Particle.DUST, ringPt, 2, 0.05, 0.05, 0.05, 0.0, redstoneDust)
            }
            val innerR = slamRadius * (1.0 - (telegraphTick / 30.0))
            for (i in 0 until 24) {
                val angle = 2.0 * Math.PI * i / 24
                val innerPt = slamTargetLoc.clone().add(cos(angle) * innerR, 0.15, sin(angle) * innerR)
                world.spawnParticle(Particle.DUST, innerPt, 1, 0.0, 0.0, 0.0, 0.0, redstoneDust)
            }
        }

        runLater(30L) {
            if (isDeadOrRemoved || !mannequin.isValid) return@runLater
            mannequin.setGravity(true)
            val plungeVec = slamTargetLoc.toVector().subtract(mannequin.location.toVector())
            mannequin.velocity = plungeVec.multiply(0.18).setY(-1.4)

            runLater(10L) {
                if (isDeadOrRemoved || !mannequin.isValid) return@runLater
                mannequin.teleport(slamTargetLoc)
                isSlamming = false
                usingRangedStance = false
                stanceSwitchTicks = 0
                mannequin.equipment.setItemInMainHand(ItemStack(Material.IRON_SWORD))

                terraformSlamArea(slamTargetLoc, slamRadius.toInt())

                world.playSound(slamTargetLoc, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 3.5f, 0.75f)
                world.playSound(slamTargetLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.8f)

                world.spawnParticle(Particle.GUST_EMITTER_LARGE, slamTargetLoc.clone().add(0.0, 0.4, 0.0), 3, 1.5, 0.2, 1.5, 0.0)
                world.spawnParticle(Particle.DUST_PLUME, slamTargetLoc.clone().add(0.0, 0.2, 0.0), 90, 3.0, 0.3, 3.0, 0.06)
                world.spawnParticle(
                    Particle.BLOCK,
                    slamTargetLoc.clone().add(0.0, 0.5, 0.0),
                    100, 2.5, 0.4, 2.5, 0.15,
                    Material.REDSTONE_BLOCK.createBlockData()
                )

                for (wave in 1..4) {
                    val r = (slamRadius / 4.0) * wave
                    runLater((wave * 2).toLong()) {
                        val points = (r * 10).toInt().coerceAtLeast(16)
                        for (i in 0 until points) {
                            val angle = 2.0 * Math.PI * i / points
                            val pt = slamTargetLoc.clone().add(cos(angle) * r, 0.2, sin(angle) * r)
                            world.spawnParticle(Particle.DUST, pt, 3, 0.15, 0.05, 0.15, 0.0, redstoneDust)
                        }
                    }
                }

                val radiusSq = slamRadius * slamRadius
                val unblockableSource = DamageSource.builder(DamageType.SONIC_BOOM)
                    .withCausingEntity(mannequin)
                    .withDirectEntity(mannequin)
                    .build()

                slamTargetLoc.getNearbyLivingEntities(slamRadius, 5.0, slamRadius).forEach { victim ->
                    if (victim == mannequin) return@forEach
                    if (victim.persistentDataContainer.has(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE)) return@forEach
                    if (victim is Player && (victim.isDead || (victim.gameMode != GameMode.SURVIVAL && victim.gameMode != GameMode.ADVENTURE))) {
                        return@forEach
                    }

                    val dx = victim.location.x - slamTargetLoc.x
                    val dz = victim.location.z - slamTargetLoc.z
                    if ((dx * dx + dz * dz) <= radiusSq) {
                        victim.damage(phase1SlamDamage, unblockableSource)

                        val kb = victim.location.toVector().subtract(slamTargetLoc.toVector()).setY(0.0)
                        if (kb.lengthSquared() > 1e-4) kb.normalize() else kb.setX(1.0)
                        victim.velocity = victim.velocity.add(kb.multiply(1.1).setY(0.5))
                    }
                }
            }
        }
    }

    private fun terraformSlamArea(center: Location, radius: Int) {
        val radiusSq = radius * radius
        val centerBlock = center.block

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                if ((x * x + z * z) > radiusSq) continue

                for (y in -2..1) {
                    val ground = centerBlock.getRelative(x, y, z)
                    if (ground.type == Material.GRASS_BLOCK ||
                        ground.type == Material.MYCELIUM ||
                        ground.type == Material.PODZOL
                    ) {
                        ground.type = Material.DIRT
                    }
                }

                for (y in -1..4) {
                    val b = centerBlock.getRelative(x, y, z)
                    if (isBreakableFoliage(b)) {
                        b.breakNaturally()
                    }
                }
            }
        }
    }

    private fun isBreakableFoliage(block: Block): Boolean {
        val mat = block.type
        if (mat.isAir) return false
        return Tag.LEAVES.isTagged(mat) ||
                Tag.FLOWERS.isTagged(mat) ||
                Tag.REPLACEABLE_BY_TREES.isTagged(mat) ||
                Tag.CROPS.isTagged(mat) ||
                mat == Material.BAMBOO ||
                mat == Material.SUGAR_CANE ||
                mat == Material.DEAD_BUSH ||
                mat == Material.VINE ||
                mat == Material.MOSS_CARPET ||
                mat == Material.SWEET_BERRY_BUSH
    }

    // ========================================================================
    // ENDER-DRAGON STYLE DEATH ANIMATION + 32 ENCHANTED GOLDEN APPLES + TUNNELER
    // ========================================================================

    fun triggerDeathAnimation(killer: Player?) {
        if (isDyingAnimation || isDeadOrRemoved) return
        isDyingAnimation = true
        isFloatingInvulnerable = true

        tasks.forEach { it.cancel() }
        tasks.clear()
        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        mannequin.health = 1.0
        mannequin.setGravity(false)
        mannequin.isInvulnerable = true
        mannequin.equipment.setItemInMainHand(ItemStack(Material.AIR))

        val baseGroundLoc = mannequin.location.clone()
        world.playSound(baseGroundLoc, Sound.ENTITY_ENDER_DRAGON_DEATH, SoundCategory.HOSTILE, 5.0f, 0.85f)

        var tick = 0
        var deathTask: BukkitTask? = null
        deathTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick++
            if (!mannequin.isValid || tick > 120) {
                deathTask?.cancel()
                return@Runnable
            }

            val nextLoc = mannequin.location.add(0.0, 0.06, 0.0)
            nextLoc.yaw += 14f
            nextLoc.pitch = -35f
            mannequin.teleport(nextLoc)
            HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, world.players, nextLoc.yaw, nextLoc.pitch)

            if (tick % 6 == 0) {
                val lx = baseGroundLoc.x + Random.nextDouble(-14.0, 14.0)
                val lz = baseGroundLoc.z + Random.nextDouble(-14.0, 14.0)
                val ly = world.getHighestBlockYAt(lx.toInt(), lz.toInt()) + 1.0
                world.strikeLightningEffect(Location(world, lx, ly, lz))
            }

            if (tick % 2 == 0) {
                spawnRainbowGroundAura(baseGroundLoc, radius = 8.0, count = 20)
            }

            if (tick == 85) {
                mannequin.isGlowing = true
                herobrineTeam?.color(NamedTextColor.WHITE)
                mannequin.customName("<bold><white>Herobrine</white></bold>".mm())
                plugin.bossBarManager.updateBarAppearance(
                    mannequin,
                    titleMiniMessage = "<bold><white>Herobrine</white></bold>",
                    color = BossBar.Color.WHITE
                )
            }

            if (tick >= 85) {
                world.spawnParticle(Particle.END_ROD, mannequin.location.add(0.0, 1.0, 0.0), 15, 0.6, 0.8, 0.6, 0.15)
                world.spawnParticle(Particle.FLASH, mannequin.location.add(0.0, 1.0, 0.0), 1)
            }

            if (tick == 120) {
                deathTask?.cancel()
                completeHerobrineDeath(mannequin.location.clone(), killer)
            }
        }, 1L, 1L)
    }

    private fun completeHerobrineDeath(finalAscensionLoc: Location, killer: Player?) {
        world.playSound(finalAscensionLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4.0f, 0.7f)
        world.playSound(finalAscensionLoc, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2.0f, 1.0f)
        world.spawnParticle(Particle.EXPLOSION_EMITTER, finalAscensionLoc, 2)
        world.spawnParticle(Particle.FLASH, finalAscensionLoc, 4)
        world.spawnParticle(Particle.END_ROD, finalAscensionLoc, 200, 2.0, 2.0, 2.0, 0.35)

        // Drop 32 Enchanted Golden Apples + "The Hero's Tunneler" Trophy Pickaxe
        val dropLoc = spawnOrigin.clone().add(0.0, 1.2, 0.0)
        val rewards = listOf(
            ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 32),
            plugin.herobrineManager.createHerosTunneler()
        )
        for (stack in rewards) {
            world.dropItemNaturally(dropLoc, stack) { item ->
                item.isGlowing = true
                item.isInvulnerable = true
                item.velocity = Vector(0.0, 0.35, 0.0)
            }
        }

        // Drop configured 500 levels of EXP
        ExperienceUtil.spawnLevelOrbs(dropLoc, levels = config.expLevelsDropped, orbCount = 30)

        val killerName = killer?.name ?: "A player"
        Bukkit.broadcast("<white>$killerName has slain Herobrine.</white>".mm())
        DiscordSrvHook.sendHerobrineSlainByPlayer(killerName)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.broadcast("<yellow>Herobrine has left the game.</yellow>".mm())
            DiscordSrvHook.sendLeaveMessage()
            HerobrinePacketController.broadcastTablistRemove(tablistUuid)
        }, 20L)

        val id = mannequin.uniqueId
        forceCleanup(killEntity = true, removeTablistImmediately = false)
        plugin.herobrineManager.activeFights.remove(id)
    }

    // ========================================================================
    // DAMAGE & PHASE CHECKS
    // ========================================================================

    fun onDamaged(damageAmount: Double, attacker: Player?) {
        if (isDyingAnimation || isDeadOrRemoved) return
        if (attacker != null) {
            currentTarget = attacker
        }

        // 1. Check 1000 HP mark (Phase 1 -> Phase 2)
        if (currentPhase == 1 && mannequin.health <= config.phase2Threshold) {
            enterPhase2()
            return
        }

        // 2. Check 500 HP mark (Phase 2 -> Phase 3)
        if (currentPhase == 2 && mannequin.health <= config.phase3Threshold) {
            enterPhase3()
            return
        }

        if (!isFloatingInvulnerable && !isSlamming) {
            damageSinceLastSteveWave += damageAmount
            if (damageSinceLastSteveWave >= 100.0) {
                damageSinceLastSteveWave = 0.0
                enterFloatingInvulnerableState(spawnSteves = true)
            }
        }
    }

    private fun pickRandomOrSolePlayer(): Player? {
        val players = mannequin.location.getNearbyPlayers(config.followRange)
            .filter { !it.isDead && (it.gameMode == GameMode.SURVIVAL || it.gameMode == GameMode.ADVENTURE) }
        return players.randomOrNull()
    }

    private fun resolveSummonerOrNearest(): Player? {
        summonerId?.let { id ->
            val p = Bukkit.getPlayer(id)
            if (p != null && p.isOnline && !p.isDead && p.world == world) return p
        }
        return pickRandomOrSolePlayer()
    }

    private fun resolveCombatTarget(): Player? {
        val cur = currentTarget
        if (cur != null && cur.isOnline && !cur.isDead && cur.world == world &&
            (cur.gameMode == GameMode.SURVIVAL || cur.gameMode == GameMode.ADVENTURE)
        ) {
            return cur
        }
        val next = pickRandomOrSolePlayer()
        currentTarget = next
        return next
    }

    private fun faceLocation(targetLocation: Location) {
        val eye = mannequin.location.add(0.0, 1.62, 0.0)
        val dir = targetLocation.toVector().subtract(eye.toVector())
        if (dir.lengthSquared() > 1e-4) {
            val rotLoc = mannequin.location.setDirection(dir)
            val yaw = rotLoc.yaw
            val pitch = rotLoc.pitch.coerceIn(-45f, 45f)
            mannequin.setRotation(yaw, pitch)
            HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, world.players, yaw, pitch)
        }
    }

    fun onKilledByPlayer(killer: Player?) {
        triggerDeathAnimation(killer)
    }

    fun forceCleanup(killEntity: Boolean, removeTablistImmediately: Boolean = true) {
        isDeadOrRemoved = true
        tasks.forEach { it.cancel() }
        tasks.clear()

        world.setStorm(false)
        world.isThundering = false
        if (wasAdvanceTime) {
            world.setGameRule(GameRules.ADVANCE_TIME, true)
        }
        world.fullTime = savedFullTime

        herobrineTeam?.unregister()
        herobrineTeam = null

        activeSteves.forEach { if (it.mannequin.isValid) it.mannequin.remove() }
        activeSteves.clear()

        if (::mannequin.isInitialized) {
            plugin.bossBarManager.unregisterEntity(mannequin)
            if (killEntity && mannequin.isValid) {
                mannequin.remove()
            }
        }
        if (removeTablistImmediately) {
            HerobrinePacketController.broadcastTablistRemove(tablistUuid)
        }
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
}