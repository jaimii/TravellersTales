package project.kompass.travellersTales.boss.herobrine

import org.bukkit.configuration.file.FileConfiguration

data class HerobrineConfig(
    val enabled: Boolean,
    val maxHealth: Double,
    val phase2Threshold: Double,
    val phase3Threshold: Double,
    val movementSpeed: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val steveMinionExpLevelsDropped: Int
) {
    companion object {
        fun load(config: FileConfiguration): HerobrineConfig {
            val sec = config.getConfigurationSection("herobrine")
            return HerobrineConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                maxHealth = (sec?.getDouble("max-health", 1500.0) ?: 1500.0).coerceAtLeast(100.0),
                phase2Threshold = sec?.getDouble("phase-2-threshold", 1000.0) ?: 1000.0,
                phase3Threshold = sec?.getDouble("phase-3-threshold", 500.0) ?: 500.0,
                movementSpeed = sec?.getDouble("movement-speed", 0.35) ?: 0.35,
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 500) ?: 500).coerceAtLeast(0),
                steveMinionExpLevelsDropped = (sec?.getInt("steve-minion-exp-levels-dropped", 10) ?: 10).coerceAtLeast(0)
            )
        }
    }
}