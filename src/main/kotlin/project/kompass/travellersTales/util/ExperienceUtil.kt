package project.kompass.travellersTales.util

import org.bukkit.Location
import org.bukkit.entity.ExperienceOrb
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ExperienceUtil {

    /**
     * Converts a target Minecraft Experience Level (0 -> [level])
     * into the exact total Experience Points required to reach that level from 0.
     */
    fun getTotalExpForLevel(level: Int): Int {
        if (level <= 0) return 0
        return when {
            level <= 16 -> (level * level) + (6 * level)
            level <= 31 -> (2.5 * level * level - 40.5 * level + 360.0).roundToInt()
            else -> (4.5 * level * level - 162.5 * level + 2220.0).roundToInt()
        }
    }

    /**
     * Spawns a radial burst of high-value ExperienceOrbs totaling [levels] worth of XP
     * without lagging the server with thousands of tiny orbs.
     */
    fun spawnLevelOrbs(location: Location, levels: Int, orbCount: Int = 20) {
        val totalXp = getTotalExpForLevel(levels)
        if (totalXp <= 0) return

        val world = location.world ?: return
        val count = orbCount.coerceAtLeast(1)
        val xpPerOrb = totalXp / count
        val remainder = totalXp % count

        for (i in 0 until count) {
            val angle = 2.0 * Math.PI * i / count
            val velocity = Vector(cos(angle) * 0.35, 0.5, sin(angle) * 0.35)
            val amount = if (i == 0) xpPerOrb + remainder else xpPerOrb

            if (amount > 0) {
                world.spawn(location, ExperienceOrb::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { orb ->
                    orb.experience = amount
                    orb.velocity = velocity
                }
            }
        }
    }
}