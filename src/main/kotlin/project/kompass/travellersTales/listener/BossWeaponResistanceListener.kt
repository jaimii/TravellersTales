package project.kompass.travellersTales.listener

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.damage.DamageType
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys

class BossWeaponResistanceListener(private val plugin: TravellersTales) : Listener {

    var enabled: Boolean = true
        private set
    var onlyCustomBosses: Boolean = false
        private set
    var damageMultiplier: Double = 0.25
        private set

    init {
        reloadConfig()
    }

    fun reloadConfig() {
        val sec = plugin.config.getConfigurationSection("weapon-resistance")
        enabled = sec?.getBoolean("enabled", true) ?: true
        onlyCustomBosses = sec?.getBoolean("only-bosses", false) ?: false
        damageMultiplier = (sec?.getDouble("damage-multiplier", 0.25) ?: 0.25).coerceIn(0.0, 1.0)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onMobDamagedBySpearOrMace(event: EntityDamageByEntityEvent) {
        if (!enabled) return

        val victim = event.entity
        if (victim !is LivingEntity || victim is Player) return

        // If only-bosses is true, check for the MobKeys.MOB_ID PDC tag
        if (onlyCustomBosses && !victim.persistentDataContainer.has(MobKeys.MOB_ID, PersistentDataType.STRING)) {
            return
        }

        if (isSpearChargeOrMaceHit(event)) {
            event.damage *= damageMultiplier
        }
    }

    private fun isSpearChargeOrMaceHit(event: EntityDamageByEntityEvent): Boolean {
        val damageType = event.damageSource.damageType

        // 1. Check native Paper 26.3 DamageTypes
        if (damageType == DamageType.SPEAR || damageType == DamageType.MACE_SMASH) {
            return true
        }

        // 2. Check attacking weapon or arrow source weapon
        val damager = event.damager
        when (damager) {
            is LivingEntity -> {
                val eq = damager.equipment ?: return false
                val mainHand = eq.itemInMainHand.type
                val offHand = eq.itemInOffHand.type

                if (mainHand == Material.MACE || isSpear(mainHand) || isSpear(offHand)) {
                    return true
                }
            }

            is AbstractArrow -> {
                val weaponMat = damager.weapon?.type ?: return false
                if (weaponMat == Material.MACE || isSpear(weaponMat)) {
                    return true
                }
            }
        }

        return false
    }

    private fun isSpear(material: Material): Boolean {
        return Tag.ITEMS_SPEARS.isTagged(material)
    }
}