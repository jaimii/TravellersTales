package project.kompass.travellersTales.util

import org.bukkit.Location
import org.bukkit.World

object BiomeValidator {

    private val NON_HOSTILE_OR_AQUATIC_BIOMES = setOf(
        "mushroom_fields",
        "deep_dark"
    )

    private val HIGH_ALTITUDE_BIOME_KEYWORDS = listOf(
        "peaks",       // jagged_peaks, frozen_peaks, stony_peaks
        "slopes",      // snowy_slopes
        "windswept",   // windswept_hills, windswept_gravelly_hills, windswept_forest, windswept_savanna
        "meadow",
        "grove",       // grove, cherry_grove
        "eroded_badlands",
        "wooded_badlands"
    )

    private const val MAX_GIANT_ALTITUDE_Y = 110

    /**
     * Valid for The Traveller:
     * - In Nether or The End: valid on solid non-liquid ground.
     * - In Overworld: blocks Mushroom Fields, Deep Dark, Oceans, and Rivers.
     */
    fun isValidTravellerBiome(location: Location): Boolean {
        val world = location.world ?: return false
        if (world.environment != World.Environment.NORMAL) return true

        val key = world.getBiome(location).key().value().lowercase()
        if (key in NON_HOSTILE_OR_AQUATIC_BIOMES) return false
        if (key.contains("ocean") || key.contains("river")) return false

        return true
    }

    /**
     * Valid for The Giant:
     * - Requires Overworld and [isValidTravellerBiome] (no Mushroom Fields, Deep Dark, Oceans, or Rivers).
     * - Blocks high-altitude mountain/hill biomes and elevations above Y = 110.
     */
    fun isValidGiantBiome(location: Location): Boolean {
        val world = location.world ?: return false
        if (world.environment != World.Environment.NORMAL) return false
        if (location.blockY > MAX_GIANT_ALTITUDE_Y) return false
        if (!isValidTravellerBiome(location)) return false

        val key = world.getBiome(location).key().value().lowercase()
        if (HIGH_ALTITUDE_BIOME_KEYWORDS.any { key.contains(it) }) {
            return false
        }

        return true
    }
}