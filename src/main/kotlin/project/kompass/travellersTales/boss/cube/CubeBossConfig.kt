package project.kompass.travellersTales.boss.cube

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

data class CubeBossSettings(
    val enabled: Boolean,
    val displayName: String,
    val splitDisplayName: String,
    val phase1Health: Double,
    val phase2Health: Double,
    val phase1Size: Int,
    val phase2Size: Int,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val debuffDurationTicks: Int,
    val slamIntervalTicks: Long,
    val slamRadius: Double,
    val slamDamagePhase1: Double,
    val slamDamagePhase2: Double,
    val bossBar: BossBarSettings
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance

    companion object {
        fun load(config: FileConfiguration, type: CubeBossType): CubeBossSettings {
            val sec = config.getConfigurationSection(type.configKey)
            val barSec = sec?.getConfigurationSection("boss-bar")

            val defaultName = if (type == CubeBossType.WORLD_SLIME) {
                "<bold><gradient:#2ECC71:#A3E635>ᴛʜᴇ ᴡᴏʀʟᴅ ꜱʟɪᴍᴇ</gradient></bold>"
            } else {
                "<bold><gradient:#FF4500:#FF8C00>ᴛʜᴇ ᴍᴀɢᴍᴀ ᴄᴏʀᴇ</gradient></bold>"
            }
            val defaultSplitName = if (type == CubeBossType.WORLD_SLIME) {
                "<bold><gradient:#2ECC71:#A3E635>ᴡᴏʀʟᴅ ꜱʟɪᴍᴇ ᴛᴡɪɴ</gradient></bold>"
            } else {
                "<bold><gradient:#FF4500:#FF8C00>ᴍᴀɢᴍᴀ ᴄᴏʀᴇ ᴛᴡɪɴ</gradient></bold>"
            }

            val name = sec?.getString("display-name") ?: defaultName
            val splitName = sec?.getString("split-display-name") ?: defaultSplitName
            val defaultSlamP1 = if (type == CubeBossType.WORLD_SLIME) 4.0 else 8.0
            val defaultSlamP2 = defaultSlamP1 / 2.0

            val barSettings = BossBarSettings(
                enabled = barSec?.getBoolean("enabled", true) ?: true,
                title = barSec?.getString("title") ?: name,
                phase2Title = barSec?.getString("phase-2-title") ?: splitName,
                color = type.barColor,
                phase2Color = type.barColor,
                overlay = BossBar.Overlay.NOTCHED_10,
                flags = if (barSec?.getBoolean("darken-sky", false) == true) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
                radius = barSec?.getDouble("radius", 64.0) ?: 64.0
            )

            return CubeBossSettings(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                displayName = name,
                splitDisplayName = splitName,
                phase1Health = (sec?.getDouble("phase-1-health", 300.0) ?: 300.0).coerceAtLeast(20.0),
                phase2Health = (sec?.getDouble("phase-2-health", 100.0) ?: 100.0).coerceAtLeast(10.0),
                phase1Size = (sec?.getInt("phase-1-size", 9) ?: 9).coerceIn(2, 20),
                phase2Size = (sec?.getInt("phase-2-size", 5) ?: 5).coerceIn(2, 15),
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 50) ?: 50).coerceAtLeast(0),
                debuffDurationTicks = (sec?.getInt("debuff-duration-ticks", 600) ?: 600).coerceAtLeast(20),
                slamIntervalTicks = (sec?.getLong("slam-interval-ticks", 200L) ?: 200L).coerceAtLeast(40L),
                slamRadius = (sec?.getDouble("slam-radius", 8.0) ?: 8.0).coerceAtLeast(2.0),
                slamDamagePhase1 = (sec?.getDouble("slam-damage-phase-1", defaultSlamP1) ?: defaultSlamP1).coerceAtLeast(1.0),
                slamDamagePhase2 = (sec?.getDouble("slam-damage-phase-2", defaultSlamP2) ?: defaultSlamP2).coerceAtLeast(1.0),
                bossBar = barSettings
            )
        }
    }
}