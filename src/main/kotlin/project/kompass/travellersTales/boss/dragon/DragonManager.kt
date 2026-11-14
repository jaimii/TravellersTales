package project.kompass.travellersTales.boss.dragon

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys

class DragonManager(private val plugin: TravellersTales) {

    var config: DragonConfig = DragonConfig.load(plugin.config)
        private set

    var activeFight: ActiveDragonFight? = null
        private set

    init {
        // Re-attach any existing Ender Dragon in The End on server startup or plugin reload
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (world in Bukkit.getWorlds()) {
                if (world.environment == World.Environment.THE_END) {
                    world.getEntitiesByClass(EnderDragon::class.java)
                        .firstOrNull { isRealDragon(it) }
                        ?.let { rebindOrRestoreDragon(it) }
                }
            }
        })
    }

    fun reloadConfig() {
        plugin.reloadConfig()
        config = DragonConfig.load(plugin.config)
        activeFight?.let { fight ->
            fight.config = config
            fight.syncNativeBossBar()
        }
    }

    fun isRealDragon(entity: Entity): Boolean {
        return entity is EnderDragon &&
                entity.world.environment == World.Environment.THE_END &&
                !entity.persistentDataContainer.has(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE)
    }

    fun handleDragonSpawn(dragon: EnderDragon) {
        if (!config.enabled || !isRealDragon(dragon)) return

        val existing = activeFight
        if (existing != null && !existing.hasDied) {
            if (existing.dragonId == dragon.uniqueId) {
                existing.rebindDragon(dragon)
                return
            }
            existing.forceCleanup()
            activeFight = null
        }

        val hasSavedHealth = dragon.persistentDataContainer.has(MobKeys.BOSS_CUSTOM_HEALTH, PersistentDataType.DOUBLE)
        val fight = ActiveDragonFight(plugin, dragon, config)
        activeFight = fight
        fight.start(preserveCurrentHealth = hasSavedHealth)
    }

    /**
     * Re-binds the live EnderDragon reference when its chunk reloads in The End,
     * or restores the ActiveDragonFight from PDC if it was unloaded/reloaded.
     */
    fun rebindOrRestoreDragon(dragon: EnderDragon): ActiveDragonFight? {
        if (!config.enabled || !isRealDragon(dragon) || dragon.isDead) return null

        val existing = activeFight
        if (existing != null && !existing.hasDied) {
            existing.rebindDragon(dragon)
            return existing
        }

        val restoredFight = ActiveDragonFight(plugin, dragon, config)
        activeFight = restoredFight
        restoredFight.start(preserveCurrentHealth = true)
        return restoredFight
    }

    fun clearActiveFight(fight: ActiveDragonFight) {
        if (activeFight == fight) {
            activeFight = null
        }
    }

    fun runFixRoutine() {
        activeFight?.forceCleanup()
        activeFight = null
    }
}