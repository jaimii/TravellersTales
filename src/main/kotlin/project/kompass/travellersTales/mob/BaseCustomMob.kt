package project.kompass.travellersTales.mob

import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.registry.MobKeys

abstract class BaseCustomMob(
    val id: String,
    val displayName: Component?,
    val entityType: EntityType,
    open val maxHealth: Double,
    open val attackDamage: Double? = null,
    open val movementSpeed: Double? = null
) {
    open fun spawn(location: Location): LivingEntity {
        val world = requireNotNull(location.world) { "World cannot be null" }
        return world.spawnEntity(location, entityType, CreatureSpawnEvent.SpawnReason.CUSTOM) { entity ->
            if (entity !is LivingEntity) return@spawnEntity
            applyToEntity(entity)
        } as LivingEntity
    }

    open fun applyToEntity(entity: LivingEntity) {
        entity.persistentDataContainer.set(MobKeys.MOB_ID, PersistentDataType.STRING, id)
        displayName?.let {
            entity.customName(it)
            entity.isCustomNameVisible = true
        }
        entity.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
        entity.health = maxHealth
        attackDamage?.let { entity.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue = it }
        movementSpeed?.let { entity.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = it }
        onSpawn(entity)
    }

    protected open fun onSpawn(entity: LivingEntity) {}
    open fun onCombat(entity: LivingEntity, event: EntityDamageByEntityEvent) {}
    open fun onDeath(entity: LivingEntity, event: EntityDeathEvent) {}
}