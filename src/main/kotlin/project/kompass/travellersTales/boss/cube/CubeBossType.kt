package project.kompass.travellersTales.boss.cube

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.AbstractCubeMob
import org.bukkit.entity.EntityType
import org.bukkit.entity.MagmaCube
import org.bukkit.entity.Slime

enum class CubeBossType(
    val configKey: String,
    val mobId: String,
    val entityType: EntityType,
    val entityClass: Class<out AbstractCubeMob>,
    val shrineBlock: Material,
    val barColor: BossBar.Color,
    val primaryColor: Color,
    val secondaryColor: Color,
    val accentParticle: Particle,
    val bootsId: String
) {
    WORLD_SLIME(
        configKey = "world-slime",
        mobId = "world_slime",
        entityType = EntityType.SLIME,
        entityClass = Slime::class.java,
        shrineBlock = Material.SLIME_BLOCK,
        barColor = BossBar.Color.GREEN,
        primaryColor = Color.fromRGB(46, 204, 113),
        secondaryColor = Color.fromRGB(163, 230, 53),
        accentParticle = Particle.ITEM_SLIME,
        bootsId = "bouncy_boots"
    ),
    MAGMA_CORE(
        configKey = "magma-core",
        mobId = "magma_core",
        entityType = EntityType.MAGMA_CUBE,
        entityClass = MagmaCube::class.java,
        shrineBlock = Material.MAGMA_BLOCK,
        barColor = BossBar.Color.RED,
        primaryColor = Color.fromRGB(255, 69, 0),
        secondaryColor = Color.fromRGB(255, 140, 0),
        accentParticle = Particle.LAVA,
        bootsId = "magma_core_boots"
    );

    val dustTransition: Particle.DustTransition
        get() = Particle.DustTransition(primaryColor, secondaryColor, 2.0f)
}