package project.kompass.travellersTales.boss.warden

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

data class WardenConfig(
    val enabled: Boolean,
    val maxHealth: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val gracePeriodSeconds: Long,
    val shriekerSpawnLockRadius: Double,
    val sonicBoomExtraRange: Double,
    val bossBar: BossBarSettings,
    val tendrilIntervalTicks: Long,
    val tendrilMaterial: Material,
    val snareDurationTicks: Int,
    val transitionInvulnTicks: Long,
    val darknessRadius: Double,
    val parasiteName: String,
    val vibrationBeaconIntervalTicks: Long,
    val vibrationBeaconRadius: Double,
    val seismicEchoIntervalTicks: Long,
    val seismicEchoDamage: Double,
    val phase3MovementSpeed: Double,
    val parryIntervalTicks: Long,
    val parryCounterDamagePercent: Double,
    val parryStunTicks: Long,
    val parryPunishDamage: Double
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance
    val phase2Threshold: Double get() = maxHealth * 0.66
    val phase3Threshold: Double get() = maxHealth * 0.33

    companion object {
        private const val DEFAULT_DARK_BLUE_TITLE = "<bold><#0047AB>ᴛʜᴇ ᴡᴀʀᴅᴇɴ</#0047AB></bold>"
        private const val DEFAULT_PHASE_2_DARK_BLUE_TITLE = "<bold><#003B8E>ᴛʜᴇ ᴡᴀʀᴅᴇɴ</#003B8E></bold>"
        private const val DEFAULT_PHASE_3_DARK_BLUE_TITLE = "<bold><#002D6E>ᴛʜᴇ ᴡᴀʀᴅᴇɴ</#002D6E></bold>"

        fun load(config: FileConfiguration): WardenConfig {
            val sec = config.getConfigurationSection("warden")
            val barSec = sec?.getConfigurationSection("boss-bar")
            val p1Sec = sec?.getConfigurationSection("phase-1")
            val p2Sec = sec?.getConfigurationSection("phase-2")
            val p3Sec = sec?.getConfigurationSection("phase-3")

            val blockName = p1Sec?.getString("tendril-block", "POINTED_DRIPSTONE") ?: "POINTED_DRIPSTONE"
            val tendrilMat = Material.matchMaterial(blockName) ?: Material.POINTED_DRIPSTONE

            val rawTitle = barSec?.getString("title")
            val title = if (rawTitle.isNullOrBlank() || rawTitle.contains("#00A8FF", ignoreCase = true)) {
                DEFAULT_DARK_BLUE_TITLE
            } else {
                rawTitle
            }

            val rawP2Title = barSec?.getString("phase-2-title")
            val p2Title = if (rawP2Title.isNullOrBlank() || rawP2Title.contains("ꜱʏᴍʙɪᴏᴛɪᴄ") || rawP2Title.contains("#0077FF", ignoreCase = true)) {
                DEFAULT_PHASE_2_DARK_BLUE_TITLE
            } else {
                rawP2Title
            }

            val rawP3Title = barSec?.getString("phase-3-title")
            val p3Title = if (rawP3Title.isNullOrBlank() || rawP3Title.contains("ᴜɴʙᴏᴜɴᴅ") || rawP3Title.contains("#00E5FF", ignoreCase = true)) {
                DEFAULT_PHASE_3_DARK_BLUE_TITLE
            } else {
                rawP3Title
            }

            val barSettings = BossBarSettings(
                enabled = barSec?.getBoolean("enabled", true) ?: true,
                title = title,
                phase2Title = p2Title,
                enragedTitle = p3Title,
                color = BossBar.Color.BLUE,
                phase2Color = BossBar.Color.BLUE,
                enragedColor = BossBar.Color.BLUE,
                overlay = BossBar.Overlay.NOTCHED_20,
                flags = if (barSec?.getBoolean("darken-sky", true) != false) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
                radius = barSec?.getDouble("radius", 128.0) ?: 128.0
            )

            return WardenConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                maxHealth = (sec?.getDouble("max-health", 1000.0) ?: 1000.0).coerceAtLeast(100.0),
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 100) ?: 100).coerceAtLeast(0),
                gracePeriodSeconds = (sec?.getLong("grace-period-seconds", 600L) ?: 600L).coerceAtLeast(0L),
                shriekerSpawnLockRadius = sec?.getDouble("shrieker-spawn-lock-radius", 32.0) ?: 32.0,
                sonicBoomExtraRange = sec?.getDouble("sonic-boom-extra-range", 10.0) ?: 10.0,
                bossBar = barSettings,
                tendrilIntervalTicks = p1Sec?.getLong("tendril-interval-ticks", 300L) ?: 300L,
                tendrilMaterial = tendrilMat,
                snareDurationTicks = p1Sec?.getInt("snare-duration-ticks", 40) ?: 40,
                transitionInvulnTicks = p2Sec?.getLong("transition-invuln-ticks", 100L) ?: 100L,
                darknessRadius = p2Sec?.getDouble("darkness-radius", 128.0) ?: 128.0,
                parasiteName = p2Sec?.getString("parasite-name") ?: "<bold><#0047AB>Sculk Parasite</#0047AB></bold>",
                vibrationBeaconIntervalTicks = p2Sec?.getLong("vibration-beacon-interval-ticks", 30L) ?: 30L,
                vibrationBeaconRadius = p2Sec?.getDouble("vibration-beacon-radius", 20.0) ?: 20.0,
                seismicEchoIntervalTicks = p2Sec?.getLong("seismic-echo-interval-ticks", 400L) ?: 400L,
                seismicEchoDamage = p2Sec?.getDouble("seismic-echo-damage", 10.0) ?: 10.0,
                phase3MovementSpeed = p3Sec?.getDouble("movement-speed", 0.38) ?: 0.38,
                parryIntervalTicks = p3Sec?.getLong("parry-interval-ticks", 360L) ?: 360L,
                parryCounterDamagePercent = p3Sec?.getDouble("parry-counter-damage-percent", 0.15) ?: 0.15,
                parryStunTicks = p3Sec?.getLong("parry-stun-ticks", 80L) ?: 80L,
                parryPunishDamage = p3Sec?.getDouble("parry-punish-damage", 25.0) ?: 25.0
            )
        }
    }
}