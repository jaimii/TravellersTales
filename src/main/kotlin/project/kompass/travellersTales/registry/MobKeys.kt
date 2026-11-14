package project.kompass.travellersTales.registry

import org.bukkit.NamespacedKey
import project.kompass.travellersTales.TravellersTales

object MobKeys {
    lateinit var MOB_ID: NamespacedKey
        private set
    lateinit var BOSS_CUSTOM_HEALTH: NamespacedKey
        private set
    lateinit var WITHER_MINION: NamespacedKey
        private set
    lateinit var WITHER_ARCHER: NamespacedKey
        private set
    lateinit var WITHER_TEMP_ENTITY: NamespacedKey
        private set
    lateinit var GRAVITY_MODIFIER: NamespacedKey
        private set
    lateinit var WARDEN_PARASITE: NamespacedKey
        private set
    lateinit var WARDEN_TEMP_ENTITY: NamespacedKey
        private set
    lateinit var WARDEN_GRACE_UNTIL: NamespacedKey
        private set
    lateinit var WARDEN_PENDING_RESPAWN: NamespacedKey
        private set
    lateinit var TRAVELLER_PROJECTILE: NamespacedKey
        private set
    lateinit var TRAVELLER_COOLDOWN_UNTIL: NamespacedKey
        private set
    lateinit var TRAVELLER_ESCAPED: NamespacedKey
        private set
    lateinit var GIANT_MINION: NamespacedKey
        private set
    lateinit var GIANT_COOLDOWN_UNTIL: NamespacedKey
        private set
    lateinit var NIGHT_BOSS_DEFER_UNTIL_DAY: NamespacedKey
        private set
    lateinit var CUBE_BOSS_MINION: NamespacedKey
        private set
    lateinit var SPECIAL_BOOTS_TYPE: NamespacedKey
        private set
    lateinit var CUBE_BOSS_EGG: NamespacedKey
        private set
    lateinit var WORLD_SLIME_RECIPE: NamespacedKey
        private set
    lateinit var MAGMA_CORE_RECIPE: NamespacedKey
        private set
    lateinit var SLAIN_BOSSES: NamespacedKey
        private set
    lateinit var HEROBRINE_MINION: NamespacedKey
        private set
    lateinit var HEROBRINE_TRIDENT: NamespacedKey
        private set
    lateinit var HEROBRINE_TUNNELER: NamespacedKey
        private set

    fun init(plugin: TravellersTales) {
        MOB_ID = NamespacedKey(plugin, "custom_mob_id")
        BOSS_CUSTOM_HEALTH = NamespacedKey(plugin, "boss_custom_health")
        WITHER_MINION = NamespacedKey(plugin, "wither_minion")
        WITHER_ARCHER = NamespacedKey(plugin, "wither_archer")
        WITHER_TEMP_ENTITY = NamespacedKey(plugin, "wither_temp_entity")
        GRAVITY_MODIFIER = NamespacedKey(plugin, "wither_gravity_pull")
        WARDEN_PARASITE = NamespacedKey(plugin, "warden_parasite")
        WARDEN_TEMP_ENTITY = NamespacedKey(plugin, "warden_temp_entity")
        WARDEN_GRACE_UNTIL = NamespacedKey(plugin, "warden_grace_until")
        WARDEN_PENDING_RESPAWN = NamespacedKey(plugin, "warden_pending_respawn")
        TRAVELLER_PROJECTILE = NamespacedKey(plugin, "traveller_projectile")
        TRAVELLER_COOLDOWN_UNTIL = NamespacedKey(plugin, "traveller_cooldown_until")
        TRAVELLER_ESCAPED = NamespacedKey(plugin, "traveller_escaped")
        GIANT_MINION = NamespacedKey(plugin, "giant_minion")
        GIANT_COOLDOWN_UNTIL = NamespacedKey(plugin, "giant_cooldown_until")
        NIGHT_BOSS_DEFER_UNTIL_DAY = NamespacedKey(plugin, "night_boss_defer_until_day")
        CUBE_BOSS_MINION = NamespacedKey(plugin, "cube_boss_minion")
        SPECIAL_BOOTS_TYPE = NamespacedKey(plugin, "special_boots_type")
        CUBE_BOSS_EGG = NamespacedKey(plugin, "cube_boss_egg")
        WORLD_SLIME_RECIPE = NamespacedKey(plugin, "world_slime_spawn_egg")
        MAGMA_CORE_RECIPE = NamespacedKey(plugin, "magma_core_spawn_egg")
        SLAIN_BOSSES = NamespacedKey(plugin, "slain_bosses")
        HEROBRINE_MINION = NamespacedKey(plugin, "herobrine_minion")
        HEROBRINE_TRIDENT = NamespacedKey(plugin, "herobrine_trident")
        HEROBRINE_TUNNELER = NamespacedKey(plugin, "herobrine_tunneler")
    }
}