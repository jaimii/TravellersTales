package project.kompass.travellersTales.boss.wither

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.util.Vector

object WitherTags {
    /** From breakable.json */
    val BREAKABLE_BLOCKS: Set<Material> = setOf(
        Material.STONE, Material.GRANITE, Material.POLISHED_GRANITE,
        Material.DIORITE, Material.POLISHED_DIORITE, Material.ANDESITE, Material.POLISHED_ANDESITE,
        Material.DEEPSLATE, Material.POLISHED_DEEPSLATE, Material.TUFF,
        Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.COBBLED_DEEPSLATE,
        Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK,
        Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH, Material.GRAVEL,
        Material.SAND, Material.RED_SAND, Material.SANDSTONE, Material.SMOOTH_SANDSTONE,
        Material.CUT_SANDSTONE, Material.RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE, Material.CUT_RED_SANDSTONE,
        Material.NETHERRACK, Material.BASALT, Material.SMOOTH_BASALT, Material.POLISHED_BASALT,
        Material.BLACKSTONE, Material.POLISHED_BLACKSTONE, Material.GILDED_BLACKSTONE,
        Material.END_STONE, Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.CALCITE,
        Material.DRIPSTONE_BLOCK, Material.POINTED_DRIPSTONE, Material.SOUL_SAND, Material.SOUL_SOIL,
        Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM, Material.MAGMA_BLOCK,
        Material.TERRACOTTA, Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA,
        Material.MAGENTA_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
        Material.LIME_TERRACOTTA, Material.PINK_TERRACOTTA, Material.GRAY_TERRACOTTA,
        Material.LIGHT_GRAY_TERRACOTTA, Material.CYAN_TERRACOTTA, Material.PURPLE_TERRACOTTA,
        Material.BLUE_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.GREEN_TERRACOTTA,
        Material.RED_TERRACOTTA, Material.BLACK_TERRACOTTA,
        Material.PRISMARINE, Material.PRISMARINE_BRICKS, Material.DARK_PRISMARINE, Material.SEA_LANTERN
    )

    /** From mobs_with_souls.json */
    val MOBS_WITH_SOULS: Set<EntityType> = setOf(
        EntityType.ALLAY, EntityType.ARMADILLO, EntityType.AXOLOTL, EntityType.BAT, EntityType.BEE,
        EntityType.CAMEL, EntityType.CAT, EntityType.CHICKEN, EntityType.COD, EntityType.COW,
        EntityType.DOLPHIN, EntityType.DONKEY, EntityType.FOX, EntityType.FROG, EntityType.GLOW_SQUID,
        EntityType.GOAT, EntityType.HORSE, EntityType.IRON_GOLEM, EntityType.LLAMA, EntityType.MOOSHROOM,
        EntityType.MULE, EntityType.OCELOT, EntityType.PANDA, EntityType.PARROT, EntityType.PIG,
        EntityType.POLAR_BEAR, EntityType.PUFFERFISH, EntityType.RABBIT, EntityType.SALMON,
        EntityType.SHEEP, EntityType.SILVERFISH, EntityType.SNIFFER, EntityType.SNOW_GOLEM,
        EntityType.SPIDER, EntityType.SQUID, EntityType.STRIDER, EntityType.TADPOLE,
        EntityType.TROPICAL_FISH, EntityType.TURTLE, EntityType.VILLAGER, EntityType.WANDERING_TRADER,
        EntityType.WOLF, EntityType.PLAYER
    )

    /** Launch vectors from blaze1.mcfunction through blaze30.mcfunction */
    val BLAZE_ARCHER_VECTORS: List<Vector> = listOf(
        Vector(-1.2, 0.9, -2.0), Vector(-1.4, 1.0, -1.5), Vector(-2.0, 1.0, 1.0),
        Vector(-0.4, 1.25, -0.3), Vector(1.0, 1.5, 0.7), Vector(1.2, 1.75, -1.6),
        Vector(-1.0, 2.0, 1.1), Vector(1.3, 2.25, -1.2), Vector(1.9, 2.0, -1.7),
        Vector(-1.2, 2.0, 1.7), Vector(0.8, 1.8, 1.9), Vector(-1.8, 1.4, -0.9),
        Vector(2.1, 1.1, 0.5), Vector(-0.6, 1.6, 2.0), Vector(1.5, 2.1, -0.8),
        Vector(-1.6, 1.3, 1.4), Vector(0.9, 2.3, -1.3), Vector(-2.2, 1.2, -0.7),
        Vector(1.7, 1.9, 1.0), Vector(-0.5, 2.4, -1.8), Vector(2.0, 1.7, 0.6),
        Vector(-1.1, 2.2, 1.5), Vector(1.4, 1.5, -2.1), Vector(-1.9, 1.8, 0.4),
        Vector(0.7, 2.5, 1.8), Vector(-0.9, 2.0, -1.7), Vector(1.3, 1.6, 1.6),
        Vector(-2.0, 1.3, 1.2), Vector(0.4, 2.4, -1.4), Vector(1.8, 1.5, -0.6)
    )

    /** XP fountain vectors & delays (ticks) from xp1..xp10.mcfunction */
    val XP_FOUNTAIN_BURSTS: List<Pair<Long, Vector>> = listOf(
        1L to Vector(0.0, 0.6, 0.0),
        2L to Vector(0.0, 0.6, 0.3),
        5L to Vector(0.0, 0.4, -0.3),
        8L to Vector(0.3, 0.3, 0.2),
        11L to Vector(0.4, 0.4, -0.3),
        14L to Vector(0.0, 0.5, -0.3),
        17L to Vector(0.3, 0.5, 0.0),
        20L to Vector(-0.2, 0.5, 0.0),
        23L to Vector(-0.4, 0.4, -0.3),
        26L to Vector(0.2, 0.7, -0.3)
    )
}