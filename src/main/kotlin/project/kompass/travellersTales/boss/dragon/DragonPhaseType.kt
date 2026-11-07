package project.kompass.travellersTales.boss.dragon

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Particle

enum class DragonPhaseType(
    val titleKey: String,
    val defaultTitle: String,
    val barColor: BossBar.Color,
    val teamColor: NamedTextColor,
    val primaryColor: Color,
    val secondaryColor: Color,
    val accentParticle: Particle
) {
    VOID(
        titleKey = "phase-1",
        defaultTitle = "<bold><#A855F7>ᴛʜᴇ ᴠᴏɪᴅ ᴅʀᴀɢᴏɴ</#A855F7></bold>",
        barColor = BossBar.Color.PURPLE,
        teamColor = NamedTextColor.DARK_PURPLE,
        primaryColor = Color.fromRGB(168, 85, 247),   // Amethyst Void
        secondaryColor = Color.fromRGB(76, 29, 149),  // Deep Violet
        accentParticle = Particle.PORTAL
    ),
    CRIMSON(
        titleKey = "phase-2",
        defaultTitle = "<bold><#EF4444>ᴛʜᴇ ᴄʀɪᴍꜱᴏɴ ᴅʀᴀɢᴏɴ</#EF4444></bold>",
        barColor = BossBar.Color.RED,
        teamColor = NamedTextColor.RED,
        primaryColor = Color.fromRGB(239, 68, 68),   // Crimson Red
        secondaryColor = Color.fromRGB(249, 115, 22), // Blaze Orange
        accentParticle = Particle.FLAME
    ),
    ICHOR(
        titleKey = "phase-3",
        defaultTitle = "<bold><#EC4899>ᴛʜᴇ ɪᴄʜᴏʀ ᴅʀᴀɢᴏɴ</#EC4899></bold>",
        barColor = BossBar.Color.PINK,
        teamColor = NamedTextColor.LIGHT_PURPLE,
        primaryColor = Color.fromRGB(236, 72, 153),  // Ichor Magenta-Pink
        secondaryColor = Color.fromRGB(251, 191, 36), // Amber Gold
        accentParticle = Particle.ELECTRIC_SPARK
    ),
    ENDER(
        titleKey = "phase-4",
        defaultTitle = "<bold><#581C87>ᴛʜᴇ ᴇɴᴅᴇʀ ᴅʀᴀɢᴏɴ</#581C87></bold>",
        barColor = BossBar.Color.PURPLE,
        teamColor = NamedTextColor.DARK_PURPLE,
        primaryColor = Color.fromRGB(88, 28, 135),    // Deep Void Obsidian
        secondaryColor = Color.fromRGB(255, 255, 255), // Pure White
        accentParticle = Particle.END_ROD
    );

    val dustOptions: Particle.DustOptions
        get() = Particle.DustOptions(primaryColor, 1.5f)

    val dustTransition: Particle.DustTransition
        get() = Particle.DustTransition(primaryColor, secondaryColor, 2.0f)
}