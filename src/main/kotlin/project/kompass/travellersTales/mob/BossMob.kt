package project.kompass.travellersTales.mob

import net.kyori.adventure.text.Component
import org.bukkit.entity.EntityType

abstract class BossMob(
    id: String,
    displayName: Component?,
    entityType: EntityType,
    maxHealth: Double,
    movementSpeed: Double? = null
) : BaseCustomMob(id, displayName, entityType, maxHealth, null, movementSpeed) {
    abstract fun cleanupAll()
}