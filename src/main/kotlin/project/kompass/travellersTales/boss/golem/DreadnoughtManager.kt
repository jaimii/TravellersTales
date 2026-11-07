package project.kompass.travellersTales.boss.golem

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.IronGolem
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import project.kompass.travellersTales.TravellersTales
import java.util.UUID

class DreadnoughtManager(private val plugin: TravellersTales) {

    var config: DreadnoughtConfig = DreadnoughtConfig.load(plugin.config)
        private set

    val activeFights = HashMap<UUID, ActiveDreadnoughtFight>()

    private val pumpkinMaterials = setOf(
        Material.CARVED_PUMPKIN,
        Material.JACK_O_LANTERN,
        Material.PUMPKIN
    )

    fun reloadConfig() {
        plugin.reloadConfig()
        config = DreadnoughtConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
            plugin.bossBarManager.createBossBar(fight.golem, config.bossBar)
        }
    }

    /**
     * Checks if placing [placedBlock] completes the Dreadnought Golem multiblock pattern:
     *       [Pumpkin]       (0, +1, 0)
     * [Iron] [Beacon] [Iron] (0,  0, 0) along X or Z axis
     *        [Iron]         (0, -1, 0)
     */
    fun tryConstructFromBlockPlace(placedBlock: Block): Boolean {
        if (!config.enabled) return false
        val mat = placedBlock.type
        if (mat != Material.BEACON && mat != Material.IRON_BLOCK && mat !in pumpkinMaterials) {
            return false
        }

        // Search nearby 3x3x3 for a Beacon that could be the center core of the structure
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    val candidateCore = placedBlock.getRelative(dx, dy, dz)
                    if (candidateCore.type == Material.BEACON) {
                        if (checkAndConsumeStructure(candidateCore)) {
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    private fun checkAndConsumeStructure(beaconCore: Block): Boolean {
        val headBlock = beaconCore.getRelative(0, 1, 0)
        val baseBlock = beaconCore.getRelative(0, -1, 0)

        if (headBlock.type !in pumpkinMaterials || baseBlock.type != Material.IRON_BLOCK) {
            return false
        }

        // Check X-axis arms or Z-axis arms
        val xArm1 = beaconCore.getRelative(1, 0, 0)
        val xArm2 = beaconCore.getRelative(-1, 0, 0)
        val zArm1 = beaconCore.getRelative(0, 0, 1)
        val zArm2 = beaconCore.getRelative(0, 0, -1)

        val matchedBlocks: List<Block> = when {
            xArm1.type == Material.IRON_BLOCK && xArm2.type == Material.IRON_BLOCK ->
                listOf(headBlock, beaconCore, baseBlock, xArm1, xArm2)
            zArm1.type == Material.IRON_BLOCK && zArm2.type == Material.IRON_BLOCK ->
                listOf(headBlock, beaconCore, baseBlock, zArm1, zArm2)
            else -> return false
        }

        val world = beaconCore.world
        matchedBlocks.forEach { b ->
            world.spawnParticle(Particle.BLOCK, b.location.add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, b.blockData)
            b.type = Material.AIR
        }

        val spawnLoc = baseBlock.location.add(0.5, 0.0, 0.5)
        spawnDreadnought(spawnLoc, playSpawnAnimation = true)
        return true
    }

    fun spawnDreadnought(location: Location, playSpawnAnimation: Boolean = true): ActiveDreadnoughtFight {
        val world = requireNotNull(location.world) { "World cannot be null" }
        val golem = world.spawn(location, IronGolem::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM)
        val fight = ActiveDreadnoughtFight(plugin, golem, config)
        activeFights[golem.uniqueId] = fight
        fight.start(playSpawnAnimation)
        return fight
    }

    fun getFight(entity: Entity): ActiveDreadnoughtFight? = activeFights[entity.uniqueId]

    fun unregisterGolem(id: UUID) {
        activeFights.remove(id)?.onDeath()
    }

    fun generateDrops(): List<ItemStack> = listOf(ItemStack(Material.BEACON, 1))

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killGolem = true) }
        activeFights.clear()
    }
}