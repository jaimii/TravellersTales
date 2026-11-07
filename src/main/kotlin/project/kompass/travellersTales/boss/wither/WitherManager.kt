package project.kompass.travellersTales.boss.wither

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Wither
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.util.mm

class WitherManager(private val plugin: TravellersTales) {

    var config: WitherConfig = WitherConfig.load(plugin.config)
        private set

    var activeFight: ActiveWitherFight? = null
        private set

    fun reloadConfig() {
        plugin.reloadConfig()
        config = WitherConfig.load(plugin.config)
        activeFight?.config = config
    }

    fun handleWitherSpawn(wither: Wither) {
        if (!config.enabled) return

        // Enforce Singleton Wither Rule
        val current = activeFight
        if (current != null && current.wither.isValid && !current.wither.isDead && current.wither != wither) {
            refundAndRemoveExtraWither(wither)
            return
        }

        // Check optional Bad Omen requirement
        if (config.requireBadOmen) {
            val summoner = wither.location.getNearbyPlayers(50.0).firstOrNull {
                it.hasPotionEffect(PotionEffectType.BAD_OMEN)
            }
            if (summoner == null) return

            summoner.removePotionEffect(PotionEffectType.BAD_OMEN)
            wither.world.playSound(wither.location, Sound.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 1f, 0.1f)
        }

        val fight = ActiveWitherFight(plugin, wither, config)
        activeFight = fight
        fight.start()
    }

    private fun refundAndRemoveExtraWither(extraWither: Wither) {
        val loc = extraWither.location
        val world = extraWither.world

        world.dropItem(loc, ItemStack(Material.WITHER_SKELETON_SKULL, 3)) {
            it.isInvulnerable = true
            it.velocity = Vector(0.0, 0.45, 0.0)
        }
        world.dropItem(loc, ItemStack(Material.SOUL_SAND, 4)) {
            it.isInvulnerable = true
            it.velocity = Vector(0.0, 0.35, 0.0)
        }

        world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, SoundCategory.MASTER, 1f, 0.8f)
        loc.getNearbyPlayers(20.0).forEach { p ->
            p.stopSound(Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE)
            p.sendMessage(
                "<bold><#322A32>[ᴛʜᴇ ᴡɪᴛʜᴇʀ] </#322A32><#8F09CE>There can only be </#8F09CE><#3C1650>ONE</#3C1650></bold>".mm()
            )
        }
        extraWither.remove()
    }

    fun clearActiveFight(fight: ActiveWitherFight) {
        if (activeFight == fight) {
            activeFight = null
        }
    }

    fun runFixRoutine() {
        activeFight?.forceCleanup(killWither = true)
        activeFight = null
    }
}