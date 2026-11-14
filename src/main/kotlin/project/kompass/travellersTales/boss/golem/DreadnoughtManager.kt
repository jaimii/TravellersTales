package project.kompass.travellersTales.boss.golem

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.IronGolem
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
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

    init {
        // Re-attach any existing Dreadnought Golems in loaded worlds on startup/reload
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (world in Bukkit.getWorlds()) {
                for (golem in world.getEntitiesByClass(IronGolem::class.java)) {
                    if (isDreadnoughtEntity(golem)) {
                        rebindOrRestoreGolem(golem)
                    }
                }
            }
        })
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        config = DreadnoughtConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
            plugin.bossBarManager.createBossBar(fight.golem, config.bossBar)
        }
    }

    fun isDreadnoughtEntity(entity: Entity): Boolean {
        val mobId = entity.persistentDataContainer.get(MobKeys.MOB_ID, PersistentDataType.STRING)
        return mobId == "dreadnought_golem"
    }

    /**
     * Re-binds a Dreadnought Golem when its chunk reloads, or restores its ActiveDreadnoughtFight
     * if it was loaded from disk without an active fight in memory.
     */
    fun rebindOrRestoreGolem(golem: IronGolem): ActiveDreadnoughtFight? {
        if (!golem.isValid || golem.isDead) return null

        val existing = activeFights[golem.uniqueId]
        // If it was unloaded mid-death-animation (or saved at <= 1 HP while marked invulnerable), finish removing it cleanly
        if ((existing != null && existing.isDying) || (golem.isMarkedInvulnerable && golem.health <= 1.0)) {
            val dropLoc = golem.location.add(0.0, 1.2, 0.0)
            golem.world.dropItemNaturally(dropLoc, ItemStack(Material.BEACON, 1)) { item ->
                item.isGlowing = true
                item.velocity = item.velocity.add(Vector(0.0, 0.25, 0.0))
            }
            existing?.forceCleanup(killGolem = true)
            activeFights.remove(golem.uniqueId)
            if (golem.isValid) golem.remove()
            return null
        }

        if (existing != null) {
            existing.rebindGolem(golem)
            return existing
        }

        val restoredFight = ActiveDreadnoughtFight(plugin, golem, config)
        activeFights[golem.uniqueId] = restoredFight
        restoredFight.start(playSpawnAnimation = false, preserveCurrentHealth = true)
        return restoredFight
    }

    fun tryConstructFromBlockPlace(placedBlock: Block): Boolean {
        if (!config.enabled) return false
        val mat = placedBlock.type
        if (mat != Material.BEACON && mat != Material.IRON_BLOCK && mat !in pumpkinMaterials) {
            return false
        }

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
        fight.start(playSpawnAnimation = playSpawnAnimation, preserveCurrentHealth = false)
        return fight
    }

    fun getFight(entity: Entity): ActiveDreadnoughtFight? {
        val fight = activeFights[entity.uniqueId]
        if (fight != null) {
            if (entity is IronGolem && entity.isValid && fight.golem !== entity) {
                fight.rebindGolem(entity)
            }
            return fight
        }
        if (entity is IronGolem && isDreadnoughtEntity(entity)) {
            return rebindOrRestoreGolem(entity)
        }
        return null
    }

    fun unregisterGolem(id: UUID) {
        activeFights.remove(id)?.onDeath()
    }

    fun generateDrops(): List<ItemStack> = listOf(ItemStack(Material.BEACON, 1))

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killGolem = true) }
        activeFights.clear()
    }
}