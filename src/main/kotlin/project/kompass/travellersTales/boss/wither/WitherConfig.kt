package project.kompass.travellersTales.boss.wither

import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

enum class AnimationMode {
    DEFAULT, BEDROCK, DISABLED;

    companion object {
        fun fromString(value: String?): AnimationMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

data class WitherConfig(
    val enabled: Boolean,
    val maxHealth: Double,
    val maxSingleHitDamage: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val spearMaceDamageMultiplier: Double,
    val animationMode: AnimationMode,
    val bossBar: BossBarSettings,
    val dropTrophy: Boolean,
    val destruction: Boolean,
    val homingSkulls: Boolean,
    val chargeAttack: Boolean,
    val minionSpawns: Boolean,
    val directHitCounter: Boolean,
    val windDashes: Boolean,
    val weatherEffects: Boolean,
    val requireBadOmen: Boolean
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance
    val homingThreshold: Double get() = maxHealth * 0.75
    val midpointThreshold: Double get() = maxHealth * 0.50
    val chargePrepThreshold: Double get() = maxHealth * (260.0 / 600.0)
    val chargeThreshold: Double get() = maxHealth * (250.0 / 600.0)
    val swarmThreshold: Double get() = maxHealth * 0.25
    val enragedThreshold: Double get() = maxHealth * (100.0 / 600.0)
    val bedrockDeathThreshold: Double get() = maxHealth * 0.05

    companion object {
        fun load(config: FileConfiguration): WitherConfig {
            val sec = config.getConfigurationSection("wither")
            return WitherConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                maxHealth = (sec?.getDouble("max-health", 1000.0) ?: 1000.0).coerceAtLeast(100.0),
                maxSingleHitDamage = (sec?.getDouble("max-single-hit-damage", 50.0) ?: 50.0).coerceAtLeast(10.0),
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 200) ?: 200).coerceAtLeast(0),
                spearMaceDamageMultiplier = (sec?.getDouble("spear-mace-damage-multiplier", 0.25) ?: 0.25).coerceIn(0.0, 1.0),
                animationMode = AnimationMode.fromString(sec?.getString("animation-mode", "DEFAULT")),
                bossBar = BossBarSettings.fromConfig(sec?.getConfigurationSection("boss-bar")),
                dropTrophy = sec?.getBoolean("drop-trophy", true) ?: true,
                destruction = sec?.getBoolean("destruction", true) ?: true,
                homingSkulls = sec?.getBoolean("homing-skulls", true) ?: true,
                chargeAttack = sec?.getBoolean("charge-attack", true) ?: true,
                minionSpawns = sec?.getBoolean("minion-spawns", true) ?: true,
                directHitCounter = sec?.getBoolean("direct-hit-counter", true) ?: true,
                windDashes = sec?.getBoolean("wind-dashes", true) ?: true,
                weatherEffects = sec?.getBoolean("weather-effects", true) ?: true,
                requireBadOmen = sec?.getBoolean("require-bad-omen", false) ?: false
            )
        }
    }
}