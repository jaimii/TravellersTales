package project.kompass.travellersTales.boss.cube

import org.bukkit.*
import org.bukkit.entity.AbstractCubeMob
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mmItem
import java.util.EnumMap

class CubeBossManager(private val plugin: TravellersTales) {

    private val settingsMap = EnumMap<CubeBossType, CubeBossSettings>(CubeBossType::class.java)
    val activeFights = mutableListOf<ActiveCubeBossFight>()

    init {
        reloadConfig()
        registerSpawnEggRecipes()
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        for (type in CubeBossType.entries) {
            settingsMap[type] = CubeBossSettings.load(plugin.config, type)
        }
        activeFights.forEach { fight ->
            fight.settings = getSettings(fight.type)
        }
    }

    fun getSettings(type: CubeBossType): CubeBossSettings =
        settingsMap[type] ?: CubeBossSettings.load(plugin.config, type)

    fun createBossSpawnEgg(type: CubeBossType): ItemStack {
        val eggMaterial = if (type == CubeBossType.WORLD_SLIME) {
            Material.SLIME_SPAWN_EGG
        } else {
            Material.MAGMA_CUBE_SPAWN_EGG
        }

        val name = if (type == CubeBossType.WORLD_SLIME) {
            "<bold><gradient:#2ECC71:#A3E635>World Slime Spawn Egg</gradient></bold>"
        } else {
            "<bold><gradient:#FF4500:#FF8C00>Magma Core Spawn Egg</gradient></bold>"
        }

        return ItemStack(eggMaterial, 1).apply {
            editMeta { meta ->
                meta.customName(name.mmItem())
                meta.setRarity(ItemRarity.EPIC)
                meta.setEnchantmentGlintOverride(true)
                meta.lore(
                    listOf(
                        "<gray>Right-click on the ground to summon</gray>".mmItem(),
                        getSettings(type).displayName.mmItem()
                    )
                )
                meta.persistentDataContainer.set(
                    MobKeys.CUBE_BOSS_EGG,
                    PersistentDataType.STRING,
                    type.name
                )
            }
        }
    }

    private fun registerSpawnEggRecipes() {
        Bukkit.removeRecipe(MobKeys.WORLD_SLIME_RECIPE)
        val slimeRecipe = ShapedRecipe(MobKeys.WORLD_SLIME_RECIPE, createBossSpawnEgg(CubeBossType.WORLD_SLIME)).apply {
            shape("SSS", "SES", "SSS")
            setIngredient('S', Material.SLIME_BLOCK)
            setIngredient('E', Material.ENDER_EYE)
        }
        Bukkit.addRecipe(slimeRecipe)

        Bukkit.removeRecipe(MobKeys.MAGMA_CORE_RECIPE)
        val magmaRecipe = ShapedRecipe(MobKeys.MAGMA_CORE_RECIPE, createBossSpawnEgg(CubeBossType.MAGMA_CORE)).apply {
            shape("MMM", "MEM", "MMM")
            setIngredient('M', Material.MAGMA_BLOCK)
            setIngredient('E', Material.ENDER_EYE)
        }
        Bukkit.addRecipe(magmaRecipe)
    }

    fun getBossTypeFromEgg(item: ItemStack?): CubeBossType? {
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null
        val raw = item.itemMeta.persistentDataContainer.get(MobKeys.CUBE_BOSS_EGG, PersistentDataType.STRING)
            ?: return null
        return runCatching { CubeBossType.valueOf(raw) }.getOrNull()
    }

    fun summonCubeBossWithIntro(spawnLoc: Location, bossType: CubeBossType) {
        val world = spawnLoc.world ?: return
        world.playSound(spawnLoc, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.HOSTILE, 2.0f, 1.2f)
        world.strikeLightningEffect(spawnLoc)

        var tick = 0
        var ritualTask: org.bukkit.scheduler.BukkitTask? = null
        ritualTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            tick += 2
            if (tick > 40) {
                ritualTask?.cancel()
                world.createExplosion(spawnLoc, 3.0f, false, false)
                spawnCubeBoss(spawnLoc, bossType)
                return@Runnable
            }
            world.spawnParticle(
                Particle.DUST_COLOR_TRANSITION,
                spawnLoc.clone().add(0.0, 1.0, 0.0),
                20, 1.2, 1.2, 1.2, 0.0,
                bossType.dustTransition
            )
            world.spawnParticle(bossType.accentParticle, spawnLoc.clone().add(0.0, 1.0, 0.0), 15, 1.0, 1.0, 1.0, 0.1)
        }, 0L, 2L)
    }

    fun spawnCubeBoss(location: Location, type: CubeBossType): ActiveCubeBossFight {
        val world = requireNotNull(location.world) { "World cannot be null" }
        val fight = ActiveCubeBossFight(plugin, type, world, location, getSettings(type))
        activeFights.add(fight)
        fight.start()
        return fight
    }

    fun getFightByEntity(entity: Entity): ActiveCubeBossFight? {
        if (entity !is AbstractCubeMob) return null
        return activeFights.firstOrNull { entity in it.activeCubes }
    }

    fun unregisterFight(fight: ActiveCubeBossFight) {
        activeFights.remove(fight)
    }

    fun runFixRoutine() {
        activeFights.toList().forEach { it.forceCleanup() }
        activeFights.clear()
    }

    fun createSpecialBoots(type: CubeBossType): ItemStack {
        return when (type) {
            CubeBossType.WORLD_SLIME -> ItemStack(Material.DIAMOND_BOOTS).apply {
                editMeta(ArmorMeta::class.java) { meta ->
                    meta.customName("<bold><gradient:#2ECC71:#A3E635>Bouncy Boots</gradient></bold>".mmItem())
                    meta.setRarity(ItemRarity.EPIC)
                    meta.trim = ArmorTrim(TrimMaterial.EMERALD, TrimPattern.WARD)
                    meta.lore(
                        listOf(
                            "<gray>Negates all fall damage and bounces</gray>".mmItem(),
                            "<gray>you high into the air upon landing.</gray>".mmItem(),
                            "<dark_gray><i>(Hold Sneak to land without bouncing)</i></dark_gray>".mmItem()
                        )
                    )
                    meta.persistentDataContainer.set(
                        MobKeys.SPECIAL_BOOTS_TYPE,
                        PersistentDataType.STRING,
                        CubeBossType.WORLD_SLIME.bootsId
                    )
                }
            }

            CubeBossType.MAGMA_CORE -> ItemStack(Material.DIAMOND_BOOTS).apply {
                editMeta(ArmorMeta::class.java) { meta ->
                    meta.customName("<bold><gradient:#FF4500:#FF8C00>Magma Core Boots</gradient></bold>".mmItem())
                    meta.setRarity(ItemRarity.EPIC)
                    meta.trim = ArmorTrim(TrimMaterial.NETHERITE, TrimPattern.WARD)
                    meta.lore(
                        listOf(
                            "<gray>Grants immunity to fire and magma blocks,</gray>".mmItem(),
                            "<gray>solidifies lava into magma blocks, and</gray>".mmItem(),
                            "<gray>turns water beneath your feet into stone.</gray>".mmItem()
                        )
                    )
                    meta.persistentDataContainer.set(
                        MobKeys.SPECIAL_BOOTS_TYPE,
                        PersistentDataType.STRING,
                        CubeBossType.MAGMA_CORE.bootsId
                    )
                }
            }
        }
    }

    fun getEquippedSpecialBootsType(player: Player): String? {
        val boots = player.inventory.boots
        if (boots.type.isAir || !boots.hasItemMeta()) return null
        return boots.itemMeta.persistentDataContainer.get(MobKeys.SPECIAL_BOOTS_TYPE, PersistentDataType.STRING)
    }
}