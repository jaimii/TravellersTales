package project.kompass.travellersTales.boss.traveller

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.configuration.file.FileConfiguration
import project.kompass.travellersTales.boss.bar.BossBarSettings

data class TravellerConfig(
    val enabled: Boolean,
    val displayName: String,
    val spawnAnnouncement: String,
    val maxHealth: Double,
    val movementSpeed: Double,
    val followRange: Double,
    val maxCombatDistance: Double,
    val expLevelsDropped: Int,
    val requireDragonKilled: Boolean,
    val sleeplessDaysRequired: Int,
    val sleeplessTicksRequired: Int,
    val cooldownMillis: Long,
    val afkTimeoutMillis: Long,
    val minSurfaceSkyLight: Int,
    val bossBar: BossBarSettings,
    val teleportCooldownMillis: Long,
    val teleportProcChance: Double,
    val skullBarrageCooldownMillis: Long,
    val swirlAttackCooldownMillis: Long,
    val arrowDamageReduction: Double,
    val shieldBlockChance: Double,
    val shieldDisableTicks: Int,
    val shieldBashCooldownMillis: Long,
    val shieldBashKnockback: Double
) {
    val maxCombatDistanceSquared: Double = maxCombatDistance * maxCombatDistance

    companion object {
        fun load(config: FileConfiguration): TravellerConfig {
            val sec = config.getConfigurationSection("traveller")
            val spawnSec = sec?.getConfigurationSection("spawning")
            val barSec = sec?.getConfigurationSection("boss-bar")
            val skillSec = sec?.getConfigurationSection("skills")
            val shieldSec = sec?.getConfigurationSection("shield")

            val name = sec?.getString("display-name")
                ?: "<bold><gradient:#E5C158:#FFF3A1:#B8860B>ᴛʜᴇ ᴛʀᴀᴠᴇʟʟᴇʀ</gradient></bold>"
            val announcement = sec?.getString("spawn-announcement")
                ?: "<bold><#E5C158>An otherworldly presence has just spawned.</#E5C158></bold>"

            val sleeplessDays = (spawnSec?.getInt("sleepless-days-required", 5) ?: 5).coerceAtLeast(1)
            val afkSeconds = (spawnSec?.getLong("afk-timeout-seconds", 120L) ?: 120L).coerceAtLeast(10L)

            val barSettings = BossBarSettings(
                enabled = barSec?.getBoolean("enabled", true) ?: true,
                title = barSec?.getString("title") ?: name,
                color = BossBar.Color.YELLOW,
                overlay = BossBar.Overlay.NOTCHED_6,
                flags = if (barSec?.getBoolean("darken-sky", true) != false) setOf(BossBar.Flag.DARKEN_SCREEN) else emptySet(),
                radius = barSec?.getDouble("radius", 64.0) ?: 64.0
            )

            return TravellerConfig(
                enabled = sec?.getBoolean("enabled", true) ?: true,
                displayName = name,
                spawnAnnouncement = announcement,
                maxHealth = (sec?.getDouble("max-health", 250.0) ?: 250.0).coerceAtLeast(20.0),
                movementSpeed = sec?.getDouble("movement-speed", 0.28) ?: 0.28,
                followRange = sec?.getDouble("follow-range", 64.0) ?: 64.0,
                maxCombatDistance = sec?.getDouble("max-combat-distance", 256.0) ?: 256.0,
                expLevelsDropped = (sec?.getInt("exp-levels-dropped", 50) ?: 50).coerceAtLeast(0),
                requireDragonKilled = spawnSec?.getBoolean("require-dragon-killed", true) ?: true,
                sleeplessDaysRequired = sleeplessDays,
                sleeplessTicksRequired = sleeplessDays * 24000,
                cooldownMillis = sleeplessDays * 20L * 60L * 1000L,
                afkTimeoutMillis = afkSeconds * 1000L,
                minSurfaceSkyLight = (spawnSec?.getInt("min-surface-sky-light", 10) ?: 10).coerceIn(0, 15),
                bossBar = barSettings,
                teleportCooldownMillis = (skillSec?.getLong("teleport-cooldown-seconds", 15L) ?: 15L) * 1000L,
                teleportProcChance = (skillSec?.getDouble("teleport-proc-chance", 0.80) ?: 0.80).coerceIn(0.0, 1.0),
                skullBarrageCooldownMillis = (skillSec?.getLong("skull-barrage-cooldown-seconds", 14L) ?: 14L) * 1000L,
                swirlAttackCooldownMillis = (skillSec?.getLong("swirl-attack-cooldown-seconds", 10L) ?: 10L) * 1000L,
                arrowDamageReduction = (shieldSec?.getDouble("arrow-damage-reduction", 0.75) ?: 0.75).coerceIn(0.0, 1.0),
                shieldBlockChance = (shieldSec?.getDouble("shield-block-chance", 0.35) ?: 0.35).coerceIn(0.0, 1.0),
                shieldDisableTicks = (shieldSec?.getInt("shield-disable-ticks", 100) ?: 100).coerceAtLeast(20),
                shieldBashCooldownMillis = (shieldSec?.getLong("shield-bash-cooldown-seconds", 3L) ?: 3L) * 1000L,
                shieldBashKnockback = (shieldSec?.getDouble("shield-bash-knockback", 1.7) ?: 1.7).coerceAtLeast(0.5)
            )
        }
    }
}