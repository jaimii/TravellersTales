package project.kompass.travellersTales.boss.bar

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.ConfigurationSection

data class BossBarSettings(
    val enabled: Boolean = true,
    val title: String = "<bold><#8F09CE>ᴛʜᴇ ᴡɪᴛʜᴇʀ</#8F09CE></bold>",
    val phase2Title: String = "<bold><#B300B3>ᴛʜᴇ ᴡɪᴛʜᴇʀ</#B300B3></bold>",
    val enragedTitle: String = "<bold><dark_red>ᴛʜᴇ ᴡɪᴛʜᴇʀ <gray>[</gray><red>ᴇɴʀᴀɢᴇᴅ</red><gray>]</gray></dark_red></bold>",
    val color: BossBar.Color = BossBar.Color.PURPLE,
    val phase2Color: BossBar.Color = BossBar.Color.PINK,
    val enragedColor: BossBar.Color = BossBar.Color.RED,
    val overlay: BossBar.Overlay = BossBar.Overlay.NOTCHED_10,
    val flags: Set<BossBar.Flag> = setOf(BossBar.Flag.DARKEN_SCREEN),
    val radius: Double = 144.0
) {
    val radiusSquared: Double = radius * radius

    companion object {
        fun fromConfig(sec: ConfigurationSection?): BossBarSettings {
            if (sec == null) return BossBarSettings()

            val flags = mutableSetOf<BossBar.Flag>()
            if (sec.getBoolean("darken-sky", true)) flags.add(BossBar.Flag.DARKEN_SCREEN)
            if (sec.getBoolean("create-fog", false)) flags.add(BossBar.Flag.CREATE_WORLD_FOG)
            if (sec.getBoolean("play-boss-music", false)) flags.add(BossBar.Flag.PLAY_BOSS_MUSIC)

            return BossBarSettings(
                enabled = sec.getBoolean("enabled", true),
                title = sec.getString("title") ?: "<bold><#8F09CE>ᴛʜᴇ ᴡɪᴛʜᴇʀ</#8F09CE></bold>",
                phase2Title = sec.getString("phase-2-title") ?: "<bold><#B300B3>ᴛʜᴇ ᴡɪᴛʜᴇʀ</#B300B3></bold>",
                enragedTitle = sec.getString("enraged-title")
                    ?: "<bold><dark_red>ᴛʜᴇ ᴡɪᴛʜᴇʀ <gray>[</gray><red>ᴇɴʀᴀɢᴇᴅ</red><gray>]</gray></dark_red></bold>",
                color = parseColor(sec.getString("color"), BossBar.Color.PURPLE),
                phase2Color = parseColor(sec.getString("phase-2-color"), BossBar.Color.PINK),
                enragedColor = parseColor(sec.getString("enraged-color"), BossBar.Color.RED),
                overlay = parseOverlay(sec.getString("style"), BossBar.Overlay.NOTCHED_10),
                flags = flags,
                radius = sec.getDouble("radius", 144.0).coerceAtLeast(16.0)
            )
        }

        private fun parseColor(input: String?, fallback: BossBar.Color): BossBar.Color =
            BossBar.Color.entries.firstOrNull { it.name.equals(input, ignoreCase = true) } ?: fallback

        private fun parseOverlay(input: String?, fallback: BossBar.Overlay): BossBar.Overlay =
            BossBar.Overlay.entries.firstOrNull { it.name.equals(input, ignoreCase = true) } ?: fallback
    }
}