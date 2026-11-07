package project.kompass.travellersTales.boss.dragon

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.file.FileConfiguration

data class DragonConfig(
    val enabled: Boolean,
    val maxHealth: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val barStyle: BossBar.Overlay,
    val barRadius: Double,
    val darkenSky: Boolean,
    val antiPearlFarming: Boolean,
    val cagedTowerPhantoms: Boolean,
    val crystalBeaconParticles: Boolean,
    val crystalCounterActionbar: Boolean,
    val dragonParticleTrail: Boolean,
    val respawnCinematic: Boolean,
    val phaseTitles: Map<DragonPhaseType, String>,
    val levitationTicks: Int,
    val witherTicks: Int,
    val lifestealMultiplier: Double
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance
    val phase2Threshold: Double get() = maxHealth * (2.0 / 3.0)
    val phase3Threshold: Double get() = maxHealth * (1.0 / 3.0)
    val phase4Threshold: Double get() = maxHealth * 0.10

    companion object {
        fun load(config: FileConfiguration): DragonConfig {
            val sec = config.getConfigurationSection("dragon")
            val barSec = sec?.getConfigurationSection("boss-bar")
            val phasesSec = sec?.getConfigurationSection("phases")

            val titles = mutableMapOf<DragonPhaseType, String>()
            for (type in DragonPhaseType.entries) {
                val configured = phasesSec?.getConfigurationSection(type.titleKey)?.getString("name")
                titles[type] = configured ?: type.defaultTitle
            }

            val styleInput = barSec?.getString("style", "NOTCHED_20")
            val overlay = BossBar.Overlay.entries.firstOrNull { it.name.equals(styleInput, ignoreCase = true) }
                ?: BossBar.Overlay.NOTCHED_20

            return DragonConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                maxHealth = (sec?.getDouble("max-health", 1500.0) ?: 1500.0).coerceAtLeast(100.0),
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 300) ?: 300).coerceAtLeast(0),
                barStyle = overlay,
                barRadius = sec?.getDouble("boss-bar.radius", 256.0) ?: 256.0,
                darkenSky = sec?.getBoolean("boss-bar.darken-sky", true) ?: true,
                antiPearlFarming = sec?.getBoolean("anti-pearl-farming", true) ?: true,
                cagedTowerPhantoms = sec?.getBoolean("caged-tower-phantoms", true) ?: true,
                crystalBeaconParticles = sec?.getBoolean("crystal-beacon-particles", true) ?: true,
                crystalCounterActionbar = sec?.getBoolean("crystal-counter-actionbar", true) ?: true,
                dragonParticleTrail = sec?.getBoolean("dragon-particle-trail", true) ?: true,
                respawnCinematic = sec?.getBoolean("respawn-cinematic", true) ?: true,
                phaseTitles = titles,
                levitationTicks = phasesSec?.getConfigurationSection("phase-1")?.getInt("levitation-duration-ticks", 600) ?: 600,
                witherTicks = phasesSec?.getConfigurationSection("phase-2")?.getInt("wither-duration-ticks", 600) ?: 600,
                lifestealMultiplier = phasesSec?.getConfigurationSection("phase-3")?.getDouble("lifesteal-multiplier", 1.0) ?: 1.0
            )
        }
    }
}