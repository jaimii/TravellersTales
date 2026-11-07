package project.kompass.travellersTales.boss.herobrine

import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm
import java.util.UUID

class SteveMinionController(
    val mannequin: Mannequin,
    val isArcher: Boolean,
    val phaseTier: Int // 1 = Iron, 2 = Diamond, 3 = Netherite + Piercing Crossbow
) {
    private var attackCooldownTicks: Int = if (isArcher) 32 else 16
    var stunnedUntilTick: Int = 0
        private set

    fun isStunned(): Boolean = Bukkit.getCurrentTick() < stunnedUntilTick

    fun stun(ticks: Int) {
        stunnedUntilTick = Bukkit.getCurrentTick() + ticks
        mannequin.velocity = Vector(0.0, mannequin.velocity.y.coerceAtMost(0.0), 0.0)
    }

    fun tick(target: Player) {
        if (!mannequin.isValid || mannequin.isDead) return
        if (isStunned()) {
            mannequin.world.spawnParticle(
                Particle.CRIT,
                mannequin.location.add(0.0, 1.9, 0.0),
                2, 0.25, 0.1, 0.25, 0.02
            )
            return
        }

        if (attackCooldownTicks > 0) attackCooldownTicks--

        val mLoc = mannequin.location
        val tLoc = target.location

        val lookDir = target.eyeLocation.toVector().subtract(mLoc.clone().add(0.0, 1.62, 0.0).toVector())
        if (lookDir.lengthSquared() > 1e-4) {
            val rotLoc = mLoc.clone().setDirection(lookDir)
            mannequin.setRotation(rotLoc.yaw, rotLoc.pitch.coerceIn(-45f, 45f))
            HerobrinePacketController.broadcastHeadRotation(mannequin.entityId, mLoc.world.players, rotLoc.yaw, rotLoc.pitch)
        }

        val flatDiff = tLoc.toVector().subtract(mLoc.toVector()).setY(0.0)
        val distSq = flatDiff.lengthSquared()

        if (!isArcher) {
            // Melee Sword Steve
            if (distSq > 5.0) {
                val moveDir = flatDiff.clone().normalize()
                val ahead = mLoc.clone().add(moveDir).block
                val jumpY = if (mannequin.isOnGround && ahead.type.isSolid && ahead.getRelative(0, 1, 0).isPassable) 0.42 else mannequin.velocity.y
                mannequin.velocity = Vector(moveDir.x * 0.31, jumpY, moveDir.z * 0.31)
            }
            if (distSq <= 8.5 && kotlin.math.abs(tLoc.y - mLoc.y) <= 2.5 && attackCooldownTicks <= 0) {
                attackCooldownTicks = 18
                mannequin.swingMainHand()
                val source = DamageSource.builder(DamageType.MOB_ATTACK)
                    .withCausingEntity(mannequin)
                    .withDirectEntity(mannequin)
                    .build()
                val meleeDmg = when (phaseTier) {
                    3 -> 12.0
                    2 -> 9.5
                    else -> 7.0
                }
                target.damage(meleeDmg, source)
            }
        } else {
            // Ranged Bow / Crossbow Steve
            if (distSq > 324.0) {
                val moveDir = flatDiff.clone().normalize()
                mannequin.velocity = Vector(moveDir.x * 0.26, mannequin.velocity.y, moveDir.z * 0.26)
            } else if (distSq < 64.0) {
                val backDir = mLoc.toVector().subtract(tLoc.toVector()).setY(0.0).normalize()
                mannequin.velocity = Vector(backDir.x * 0.24, mannequin.velocity.y, backDir.z * 0.24)
            }

            if (distSq <= 900.0 && attackCooldownTicks <= 0) {
                // Quick Charge 3 in Phase 3 fires faster (28t vs 45t)
                attackCooldownTicks = if (phaseTier >= 3) 28 else 45
                mannequin.swingMainHand()
                val eye = mLoc.clone().add(0.0, 1.6, 0.0)
                val arrowDir = target.eyeLocation.toVector().subtract(eye.toVector()).normalize()
                val shootSound = if (phaseTier >= 3) Sound.ITEM_CROSSBOW_SHOOT else Sound.ENTITY_SKELETON_SHOOT
                mLoc.world.playSound(eye, shootSound, SoundCategory.HOSTILE, 1.4f, 1.0f)

                mLoc.world.spawn(eye.add(arrowDir), Arrow::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { arrow ->
                    arrow.shooter = mannequin
                    arrow.velocity = arrowDir.multiply(1.75)
                    arrow.damage = when (phaseTier) {
                        3 -> 8.0
                        2 -> 6.5
                        else -> 5.0
                    }
                    if (phaseTier >= 3) {
                        arrow.pierceLevel = 4 // Piercing arrows in Phase 3
                    }
                    arrow.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                }
            }
        }
    }

    companion object {
        @Suppress("UnstableApiUsage")
        fun spawnSteve(location: Location, isArcher: Boolean, phaseTier: Int = 1): SteveMinionController {
            val world = location.world
            val mannequin = world.spawn(location, Mannequin::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { m ->
                m.persistentDataContainer.set(MobKeys.HEROBRINE_MINION, PersistentDataType.BYTE, 1)
                m.profile = io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .uuid(UUID.randomUUID())
                    .name("Steve")
                    .build()
                m.customName("<aqua>Steve</aqua>".mm())
                m.isCustomNameVisible = true
                m.description = null
                m.isImmovable = false
                m.setGravity(true)

                m.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 35.0
                m.health = 35.0

                // No visible armor worn, but full Iron / Diamond / Netherite armor attributes applied!
                m.getAttribute(Attribute.ARMOR)?.baseValue = if (phaseTier >= 2) 20.0 else 15.0
                m.getAttribute(Attribute.ARMOR_TOUGHNESS)?.baseValue = when (phaseTier) {
                    3 -> 12.0
                    2 -> 8.0
                    else -> 0.0
                }
                if (phaseTier >= 3) {
                    m.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 0.4
                }

                val eq = m.equipment
                val mainWeapon: ItemStack = if (isArcher) {
                    if (phaseTier >= 3) {
                        ItemStack(Material.CROSSBOW).apply {
                            editMeta { meta ->
                                meta.addEnchant(Enchantment.QUICK_CHARGE, 3, true)
                                meta.addEnchant(Enchantment.PIERCING, 4, true)
                            }
                        }
                    } else {
                        ItemStack(Material.BOW)
                    }
                } else {
                    when (phaseTier) {
                        3 -> ItemStack(Material.NETHERITE_SWORD)
                        2 -> ItemStack(Material.DIAMOND_SWORD)
                        else -> ItemStack(Material.IRON_SWORD)
                    }
                }

                eq.setItemInMainHand(mainWeapon)
                eq.setItemInOffHand(ItemStack(Material.SHIELD))
                EquipmentSlot.entries.forEach { eq.setDropChance(it, 0f) }
            }
            return SteveMinionController(mannequin, isArcher, phaseTier)
        }
    }
}