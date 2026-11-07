package project.kompass.travellersTales.boss.giant

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

data class GiantConfig(
    val enabled: Boolean,
    val displayName: String,
    val maxHealth: Double,
    val attackDamage: Double,
    val movementSpeed: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val reinforcementsChance: Double,
    val expLevelsDropped: Int,
    val bossBar: BossBarSettings,
    val jumpIntervalTicks: Long,
    val earthquakeRadius: Double,
    val earthquakeDamage: Double,
    val deathZombiesMin: Int,
    val deathZombiesMax: Int,
    // Spawning & Cooldown Settings
    val safeZoneHalfSize: Double,
    val postDragonCooldownDays: Long,
    val preDragonIntervalDays: Long
) {
    val cooldownMillis: Long = postDragonCooldownDays * 20L * 60L * 1000L

    companion object {
        fun load(config: FileConfiguration): GiantConfig {
            val sec = config.getConfigurationSection("giant")
            val barSec = sec?.getConfigurationSection("boss-bar")
            val skillSec = sec?.getConfigurationSection("skills")
            val spawnSec = sec?.getConfigurationSection("spawning")

            val name = sec?.getString("display-name") ?: "<bold><green>Giant</green></bold>"

            val barSettings = BossBarSettings(
                enabled = barSec?.getBoolean("enabled", true) ?: true,
                title = barSec?.getString("title") ?: name,
                color = BossBar.Color.GREEN,
                overlay = BossBar.Overlay.PROGRESS,
                flags = if (barSec?.getBoolean("darken-sky", true) != false) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
                radius = barSec?.getDouble("radius", 64.0) ?: 64.0
            )

            val minZombies = (skillSec?.getInt("death-zombies-min", 6) ?: 6).coerceAtLeast(1)
            val maxZombies = (skillSec?.getInt("death-zombies-max", 12) ?: 12).coerceAtLeast(minZombies)

            return GiantConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                displayName = name,
                maxHealth = (sec?.getDouble("max-health", 500.0) ?: 500.0).coerceAtLeast(20.0),
                attackDamage = (sec?.getDouble("attack-damage", 15.0) ?: 15.0).coerceAtLeast(1.0),
                movementSpeed = sec?.getDouble("movement-speed", 0.50) ?: 0.50,
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                reinforcementsChance = (sec?.getDouble("reinforcements-chance", 0.38) ?: 0.38).coerceIn(0.0, 1.0),
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 50) ?: 50).coerceAtLeast(0),
                bossBar = barSettings,
                jumpIntervalTicks = (skillSec?.getLong("jump-interval-ticks", 220L) ?: 220L).coerceAtLeast(40L),
                earthquakeRadius = (skillSec?.getDouble("earthquake-radius", 8.0) ?: 8.0).coerceAtLeast(2.0),
                earthquakeDamage = (skillSec?.getDouble("earthquake-damage", 4.0) ?: 4.0).coerceAtLeast(0.0),
                deathZombiesMin = minZombies,
                deathZombiesMax = maxZombies,
                safeZoneHalfSize = (spawnSec?.getDouble("safe-zone-half-size", 250.0) ?: 250.0).coerceAtLeast(0.0),
                postDragonCooldownDays = (spawnSec?.getLong("post-dragon-cooldown-days", 3L) ?: 3L).coerceAtLeast(1L),
                preDragonIntervalDays = (spawnSec?.getLong("pre-dragon-interval-days", 5L) ?: 5L).coerceAtLeast(1L)
            )
        }
    }
}