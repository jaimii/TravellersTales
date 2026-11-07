package project.kompass.travellersTales.util

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Statistic
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.registry.MobKeys

object BossKillTracker {

    val REQUIRED_BOSS_IDS = setOf(
        "the_wither",
        "ender_dragon",
        "the_warden",
        "the_traveller",
        "giant",
        "world_slime",
        "magma_core"
    )

    private val dragonAdvKey = NamespacedKey.minecraft("end/kill_dragon")

    fun recordBossKill(player: Player, bossId: String) {
        val current = getSlainBosses(player).toMutableSet()
        current.add(bossId)
        player.persistentDataContainer.set(
            MobKeys.SLAIN_BOSSES,
            PersistentDataType.STRING,
            current.joinToString(",")
        )
    }

    fun getSlainBosses(player: Player): Set<String> {
        val raw = player.persistentDataContainer.get(MobKeys.SLAIN_BOSSES, PersistentDataType.STRING) ?: ""
        val set = raw.split(",").filter { it.isNotBlank() }.toMutableSet()

        // Also check vanilla stats for Wither, Ender Dragon, Warden, and Giant
        if (player.getStatistic(Statistic.KILL_ENTITY, EntityType.WITHER) > 0) set.add("the_wither")
        if (player.getStatistic(Statistic.KILL_ENTITY, EntityType.ENDER_DRAGON) > 0) set.add("ender_dragon")
        if (player.getStatistic(Statistic.KILL_ENTITY, EntityType.WARDEN) > 0) set.add("the_warden")
        if (player.getStatistic(Statistic.KILL_ENTITY, EntityType.GIANT) > 0) set.add("giant")
        Bukkit.getAdvancement(dragonAdvKey)?.let {
            if (player.getAdvancementProgress(it).isDone) set.add("ender_dragon")
        }

        return set
    }

    fun hasKilledAllBosses(player: Player): Boolean {
        val slain = getSlainBosses(player)
        return REQUIRED_BOSS_IDS.all { it in slain }
    }

    fun unlockAllForPlayer(player: Player) {
        player.persistentDataContainer.set(
            MobKeys.SLAIN_BOSSES,
            PersistentDataType.STRING,
            REQUIRED_BOSS_IDS.joinToString(",")
        )
    }
}