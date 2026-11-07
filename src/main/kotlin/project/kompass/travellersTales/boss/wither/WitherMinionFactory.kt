package project.kompass.travellersTales.boss.wither

import com.destroystokyo.paper.profile.ProfileProperty
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.*
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.inventory.meta.trim.ArmorTrim
import org.bukkit.inventory.meta.trim.TrimMaterial
import org.bukkit.inventory.meta.trim.TrimPattern
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scoreboard.Team
import org.bukkit.util.EulerAngle
import org.bukkit.util.Vector
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm
import project.kompass.travellersTales.util.mmItem
import java.util.UUID

object WitherMinionFactory {

    private const val HOMING_SKULL_TEXTURE =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTQ0NDE1ODI3N2EwYTk0YWVmZmZiYjQ0MjI4NTU1ZWU2ODc2OTZjYjFmNmY5YzdlZTdmM2IxZTdiZTE0ZTA5OCJ9fX0="

    fun equipWitherBlastArmor(wither: Wither) {
        val eq = wither.equipment
        fun createPiece(mat: Material): ItemStack = ItemStack(mat).apply {
            editMeta { meta ->
                meta.isUnbreakable = true
                meta.addEnchant(Enchantment.BLAST_PROTECTION, 255, true)
            }
        }
        eq.setHelmet(createPiece(Material.LEATHER_HELMET))
        eq.setChestplate(createPiece(Material.IRON_CHESTPLATE))
        eq.setLeggings(createPiece(Material.IRON_LEGGINGS))
        eq.setBoots(createPiece(Material.LEATHER_BOOTS))
        EquipmentSlot.entries.forEach { slot -> eq.setDropChance(slot, 0f) }
    }

    // Spawns the 50 HP periodic Wither Skeleton Guard
    fun spawnPeriodicGuard(loc: Location, team: Team?): WitherSkeleton {
        return loc.world.spawn(loc, WitherSkeleton::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { skel ->
            skel.persistentDataContainer.set(MobKeys.WITHER_MINION, PersistentDataType.BYTE, 1)
            skel.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 50.0
            skel.health = 50.0
            skel.getAttribute(Attribute.SCALE)?.baseValue = 1.1
            skel.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 100.0
            skel.equipment.setBoots(createTrimmedArmor(Material.NETHERITE_BOOTS, TrimMaterial.DIAMOND, TrimPattern.SILENCE))
            skel.equipment.setDropChance(EquipmentSlot.FEET, 0f)
            team?.addEntity(skel)
        }
    }

    // Spawns a Blaze-mounted Wither Skeleton Archer
    fun spawnBlazeArcher(witherLoc: Location, motion: Vector, team: Team?) {
        val spawnLoc = witherLoc.clone().add(0.0, 3.0, 0.0)
        val world = spawnLoc.world

        val blaze = world.spawn(spawnLoc, Blaze::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { b ->
            b.persistentDataContainer.set(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE, 1)
            b.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            b.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 60.0
            b.health = 60.0
            b.getAttribute(Attribute.SCALE)?.baseValue = 0.8
            b.velocity = motion
            team?.addEntity(b)
        }

        val archer = world.spawn(spawnLoc, WitherSkeleton::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { skel ->
            skel.persistentDataContainer.set(MobKeys.WITHER_ARCHER, PersistentDataType.BYTE, 1)
            skel.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            skel.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 55.0
            skel.health = 55.0
            skel.getAttribute(Attribute.SAFE_FALL_DISTANCE)?.baseValue = 100.0
            skel.getAttribute(Attribute.SCALE)?.baseValue = 1.25
            skel.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 1, false, false, false))

            val bow = ItemStack(Material.BOW).apply {
                editMeta { it.customName("Soul Bow".mmItem()) }
            }
            skel.equipment.setItemInMainHand(bow)
            skel.equipment.setChestplate(createTrimmedArmor(Material.NETHERITE_CHESTPLATE, TrimMaterial.RESIN, TrimPattern.SILENCE))
            EquipmentSlot.entries.forEach { skel.equipment.setDropChance(it, 0f) }
            team?.addEntity(skel)
        }

        blaze.addPassenger(archer)
    }

    // Spawns one of the 15 mini Wither Skeletons for Phase 2 Swarm
    fun spawnSwarmSkeleton(loc: Location, team: Team?): WitherSkeleton {
        return loc.world.spawn(loc, WitherSkeleton::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { skel ->
            skel.persistentDataContainer.set(MobKeys.WITHER_MINION, PersistentDataType.BYTE, 1)
            skel.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 10.0
            skel.health = 10.0
            skel.getAttribute(Attribute.ARMOR)?.baseValue = 5.0
            skel.getAttribute(Attribute.ARMOR_TOUGHNESS)?.baseValue = 20.0
            skel.getAttribute(Attribute.ATTACK_KNOCKBACK)?.baseValue = 0.0
            skel.getAttribute(Attribute.ATTACK_SPEED)?.baseValue = 16.0
            skel.getAttribute(Attribute.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
            skel.getAttribute(Attribute.MOVEMENT_SPEED)?.baseValue = 0.5
            skel.getAttribute(Attribute.SCALE)?.baseValue = 0.5
            skel.equipment.setHelmet(ItemStack(Material.WITHER_SKELETON_SKULL))
            skel.equipment.setDropChance(EquipmentSlot.HEAD, 0f)
            team?.addEntity(skel)
        }
    }

    // Spawns converted Zombified Piglin
    fun spawnConvertedPiglin(loc: Location, team: Team?): PigZombie {
        return loc.world.spawn(loc, PigZombie::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { piglin ->
            piglin.persistentDataContainer.set(MobKeys.WITHER_MINION, PersistentDataType.BYTE, 1)
            piglin.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 10.0
            piglin.health = 10.0
            piglin.anger = 999999
            team?.addEntity(piglin)
        }
    }

    // Spawns converted Wither Skeleton
    fun spawnConvertedSkeleton(loc: Location, team: Team?): WitherSkeleton {
        return loc.world.spawn(loc, WitherSkeleton::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { skel ->
            skel.persistentDataContainer.set(MobKeys.WITHER_MINION, PersistentDataType.BYTE, 1)
            skel.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 15.0
            skel.health = 15.0
            val bow = ItemStack(Material.BOW).apply {
                editMeta { it.itemName("Ancient Bow".mmItem()) }
            }
            skel.equipment.setItemInMainHand(bow)
            skel.equipment.setBoots(createTrimmedArmor(Material.NETHERITE_BOOTS, TrimMaterial.DIAMOND, TrimPattern.SILENCE))
            skel.equipment.setDropChance(EquipmentSlot.HAND, 0f)
            skel.equipment.setDropChance(EquipmentSlot.FEET, 0f)
            team?.addEntity(skel)
        }
    }

    // Spawns the Homing Skull marker Armor Stand
    fun spawnHomingSkullStand(loc: Location, team: Team?): ArmorStand {
        return loc.world.spawn(loc.clone().add(0.0, 1.5, 0.0), ArmorStand::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM) { stand ->
            stand.persistentDataContainer.set(MobKeys.WITHER_TEMP_ENTITY, PersistentDataType.BYTE, 1)
            stand.isInvisible = true
            stand.isInvulnerable = true
            stand.isMarker = true
            stand.setGravity(false)
            stand.isGlowing = true
            stand.setBasePlate(false)
            stand.headPose = EulerAngle(Math.PI, Math.PI, Math.PI)

            val skull = ItemStack(Material.PLAYER_HEAD).apply {
                editMeta(SkullMeta::class.java) { meta ->
                    val profile = Bukkit.createProfile(UUID.randomUUID(), "HomingSkull")
                    profile.setProperty(ProfileProperty("textures", HOMING_SKULL_TEXTURE))
                    meta.playerProfile = profile
                }
            }
            stand.equipment.setHelmet(skull)
            team?.addEntity(stand)
        }
    }

    // Spawns the Withered Rose Trophy item
    fun spawnTrophyItem(loc: Location): Item {
        val roseStack = ItemStack(Material.WITHER_ROSE, 1).apply {
            editMeta { meta ->
                meta.setMaxStackSize(1)
                meta.setRarity(ItemRarity.EPIC)
                meta.setEnchantmentGlintOverride(true)
                meta.customName("<bold><#CC99FF>ᴡɪᴛʜᴇʀᴇᴅ ʀᴏꜱᴇ</#CC99FF></bold>".mmItem())
                meta.lore(
                    listOf(
                        "<blue>Trophy for defeating</blue>".mmItem(),
                        "<blue>the </blue><bold><italic><dark_purple>Wither</dark_purple></italic></bold>".mmItem()
                    )
                )
            }
        }

        return loc.world.dropItem(loc, roseStack) { item ->
            item.velocity = Vector(0.0, 0.6, 0.0)
            item.isGlowing = true
            item.isInvulnerable = true
            item.isUnlimitedLifetime = true
            item.customName("<bold><#CC99FF>ᴡɪᴛʜᴇʀᴇᴅ ʀᴏꜱᴇ</#CC99FF></bold>".mm())
            item.isCustomNameVisible = true
        }
    }

    private fun createTrimmedArmor(material: Material, trimMat: TrimMaterial, trimPat: TrimPattern): ItemStack {
        return ItemStack(material).apply {
            editMeta(ArmorMeta::class.java) { meta ->
                meta.isUnbreakable = true
                meta.trim = ArmorTrim(trimMat, trimPat)
            }
        }
    }
}