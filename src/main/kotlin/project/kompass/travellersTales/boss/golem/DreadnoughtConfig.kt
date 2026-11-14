package project.kompass.travellersTales.boss.golem

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

data class DreadnoughtConfig(
    val enabled: Boolean,
    val displayName: String,
    val maxHealth: Double,
    val attackDamage: Double,
    val armor: Double,
    val movementSpeed: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val knockbackResistance: Double,
    val attackCadenceTicks: Int,
    val bossBar: BossBarSettings,
    val healAuraRadius: Double,
    val smashCooldownMillis: Long,
    val smashGrabRange: Double,
    val smashPrimaryDamage: Double,
    val smashAoeDamage: Double,
    val smashAoeRadius: Double,
    val bashCooldownMillis: Long,
    val bashDamage: Double,
    val bashKnockbackStrength: Double
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance
    val smashGrabRangeSquared: Double = smashGrabRange * smashGrabRange

    companion object {
        private const val DEFAULT_SMALL_CAPS_NAME = "<bold><gold>ᴅʀᴇᴀᴅɴᴏᴜɢʜᴛ</gold> <green>ɢᴏʟᴇᴍ</green></bold>"
        private const val LEGACY_PLAIN_NAME = "<bold><gold>Dreadnought</gold> <green>Golem</green></bold>"

        fun load(config: FileConfiguration): DreadnoughtConfig {
            val sec = config.getConfigurationSection("dreadnought")
            val barSec = sec?.getConfigurationSection("boss-bar")
            val skillSec = sec?.getConfigurationSection("skills")

            val rawName = sec?.getString("display-name")
            val name = if (rawName.isNullOrBlank() || rawName == LEGACY_PLAIN_NAME) {
                DEFAULT_SMALL_CAPS_NAME
            } else {
                rawName
            }

            val rawBarTitle = barSec?.getString("title")
            val barTitle = if (rawBarTitle.isNullOrBlank() || rawBarTitle == LEGACY_PLAIN_NAME) {
                name
            } else {
                rawBarTitle
            }

            val barSettings = BossBarSettings(
                enabled = barSec?.getBoolean("enabled", true) ?: true,
                title = barTitle,
                color = BossBar.Color.GREEN,
                overlay = BossBar.Overlay.PROGRESS,
                flags = if (barSec?.getBoolean("darken-sky", false) == true) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
                radius = barSec?.getDouble("radius", 64.0) ?: 64.0
            )

            return DreadnoughtConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                displayName = name,
                maxHealth = (sec?.getDouble("max-health", 800.0) ?: 800.0).coerceAtLeast(100.0),
                attackDamage = (sec?.getDouble("attack-damage", 15.0) ?: 15.0).coerceAtLeast(1.0),
                armor = (sec?.getDouble("armor", 10.0) ?: 10.0).coerceAtLeast(0.0),
                movementSpeed = sec?.getDouble("movement-speed", 0.40) ?: 0.40,
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                knockbackResistance = (sec?.getDouble("knockback-resistance", 1.0) ?: 1.0).coerceIn(0.0, 1.0),
                attackCadenceTicks = (sec?.getInt("attack-cadence-ticks", 12) ?: 12).coerceAtLeast(5),
                bossBar = barSettings,
                healAuraRadius = skillSec?.getDouble("heal-aura-radius", 16.0) ?: 16.0,
                smashCooldownMillis = (skillSec?.getLong("smash-cooldown-seconds", 20L) ?: 20L) * 1000L,
                smashGrabRange = skillSec?.getDouble("smash-grab-range", 40.0) ?: 40.0,
                smashPrimaryDamage = skillSec?.getDouble("smash-primary-damage", 50.0) ?: 50.0,
                smashAoeDamage = skillSec?.getDouble("smash-aoe-damage", 10.0) ?: 10.0,
                smashAoeRadius = skillSec?.getDouble("smash-aoe-radius", 8.0) ?: 8.0,
                bashCooldownMillis = (skillSec?.getLong("bash-cooldown-seconds", 10L) ?: 10L) * 1000L,
                bashDamage = skillSec?.getDouble("bash-damage", 40.0) ?: 40.0,
                bashKnockbackStrength = skillSec?.getDouble("bash-knockback-strength", 2.8) ?: 2.8
            )
        }
    }
}