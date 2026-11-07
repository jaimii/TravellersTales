package project.kompass.travellersTales.boss.dragon

import org.bukkit.World
import org.bukkit.entity.EnderDragon
import project.kompass.travellersTales.TravellersTales

class DragonManager(private val plugin: TravellersTales) {

    var config: DragonConfig = DragonConfig.load(plugin.config)
        private set

    var activeFight: ActiveDragonFight? = null
        private set

    fun reloadConfig() {
        plugin.reloadConfig()
        config = DragonConfig.load(plugin.config)
        activeFight?.config = config
    }

    fun handleDragonSpawn(dragon: EnderDragon) {
        if (!config.enabled || dragon.world.environment != World.Environment.THE_END) return

        val fight = ActiveDragonFight(plugin, dragon, config)
        activeFight = fight
        fight.start()
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