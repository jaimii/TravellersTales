package project.kompass.travellersTales.boss.herobrine

import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemRarity
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.BossKillTracker
import project.kompass.travellersTales.util.mm
import project.kompass.travellersTales.util.mmItem
import java.util.UUID

class HerobrineManager(private val plugin: TravellersTales) {

    var config: HerobrineConfig = HerobrineConfig.load(plugin.config)
        private set

    val activeFights = HashMap<UUID, ActiveHerobrineFight>()

    fun reloadConfig() {
        plugin.reloadConfig()
        config = HerobrineConfig.load(plugin.config)
        activeFights.values.forEach { fight ->
            fight.config = config
        }
    }

    /**
     * Returns true if a Herobrine boss is currently active in ANY world or dimension on the server.
     */
    fun isHerobrineActiveAnywhere(): Boolean {
        return activeFights.values.any { !it.isDeadOrRemoved && !it.mannequin.isDead }
    }

    fun isHerobrineEntity(entity: Entity): Boolean {
        return entity is Mannequin &&
                entity.persistentDataContainer.get(MobKeys.MOB_ID, PersistentDataType.STRING) == "herobrine"
    }

    fun rebindHerobrineIfTracked(mannequin: Mannequin): ActiveHerobrineFight? {
        val fight = activeFights[mannequin.uniqueId] ?: return null
        fight.rebindMannequin(mannequin)
        return fight
    }

    /**
     * Checks if igniting netherrackBlock completes the Herobrine Shrine:
     * - Layer Y:   Center NETHERRACK surrounded by 4 cardinal REDSTONE_TORCH blocks
     * - Layer Y-1: 3x3 ring of 8 GOLD_BLOCKs around a GOLD_BLOCK or MOSSY_COBBLESTONE center
     */
    fun tryActivateShrine(netherrackBlock: Block, player: Player): Boolean {
        if (isHerobrineActiveAnywhere()) {
            return false
        }

        if (netherrackBlock.type != Material.NETHERRACK) return false

        val torches = listOf(
            netherrackBlock.getRelative(1, 0, 0),
            netherrackBlock.getRelative(-1, 0, 0),
            netherrackBlock.getRelative(0, 0, 1),
            netherrackBlock.getRelative(0, 0, -1)
        )
        if (torches.any { it.type != Material.REDSTONE_TORCH && it.type != Material.REDSTONE_WALL_TORCH }) {
            return false
        }

        val baseCenter = netherrackBlock.getRelative(0, -1, 0)
        if (baseCenter.type != Material.GOLD_BLOCK && baseCenter.type != Material.MOSSY_COBBLESTONE) {
            return false
        }

        val ringOffsets = listOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to  0,          1 to  0,
            -1 to  1, 0 to  1, 1 to  1
        )
        val goldRing = ringOffsets.map { (dx, dz) -> baseCenter.getRelative(dx, 0, dz) }
        if (goldRing.any { it.type != Material.GOLD_BLOCK }) {
            return false
        }

        if (!BossKillTracker.hasKilledAllBosses(player)) {
            val missing = BossKillTracker.REQUIRED_BOSS_IDS - BossKillTracker.getSlainBosses(player)
            val formattedMissing = missing.joinToString(", ") { id ->
                id.split("_").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            }
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, SoundCategory.HOSTILE, 1.5f, 0.5f)
            player.sendMessage(
                "<bold><dark_red>[Shrine]</dark_red></bold> <gray>The shrine rejects your offering. You must first slay:</gray> <red>$formattedMissing</red>".mm()
            )
            return false
        }

        val world = netherrackBlock.world
        val shrineBlocks = mutableListOf<Block>().apply {
            add(netherrackBlock)
            add(netherrackBlock.getRelative(0, 1, 0))
            addAll(torches)
            add(baseCenter)
            addAll(goldRing)
        }

        val spawnLoc = baseCenter.location.add(0.5, 1.0, 0.5)
        world.strikeLightningEffect(spawnLoc)
        world.playSound(spawnLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3.0f, 0.6f)
        world.playSound(spawnLoc, Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 3.0f, 0.5f)

        shrineBlocks.forEach { b ->
            if (!b.type.isAir) {
                world.spawnParticle(Particle.BLOCK, b.location.add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, b.blockData)
                b.type = Material.AIR
            }
        }

        val dir = player.location.toVector().subtract(spawnLoc.toVector()).setY(0.0)
        if (dir.lengthSquared() > 1e-4) spawnLoc.setDirection(dir)

        summonHerobrine(spawnLoc, player.uniqueId)
        return true
    }

    fun summonHerobrine(location: Location, summonerId: UUID? = null): ActiveHerobrineFight? {
        if (isHerobrineActiveAnywhere()) return null
        val world = requireNotNull(location.world) { "World cannot be null" }
        val fight = ActiveHerobrineFight(plugin, world, location, config, summonerId)
        fight.start()
        activeFights[fight.mannequin.uniqueId] = fight
        return fight
    }

    fun createHerosTunneler(): ItemStack {
        return ItemStack(Material.NETHERITE_PICKAXE, 1).apply {
            editMeta { meta ->
                meta.customName("<bold><gradient:#A855F7:#00E5FF>The Hero's Tunneler</gradient></bold>".mmItem())
                meta.setRarity(ItemRarity.EPIC)
                meta.isUnbreakable = true
                meta.lore(
                    listOf(
                        "<gray>The pickaxe of the multidimensional traveller</gray>".mmItem()
                    )
                )
                meta.persistentDataContainer.set(
                    MobKeys.HEROBRINE_TUNNELER,
                    PersistentDataType.BYTE,
                    1
                )
            }
        }
    }

    fun getFight(entity: Entity): ActiveHerobrineFight? {
        val fight = activeFights[entity.uniqueId]
        if (fight != null && entity is Mannequin && entity.isValid && fight.mannequin !== entity) {
            fight.rebindMannequin(entity)
        }
        return fight
    }

    fun getFightBySteve(steveEntity: Entity): ActiveHerobrineFight? {
        return activeFights.values.firstOrNull { fight ->
            fight.activeSteves.any { it.steveId == steveEntity.uniqueId }
        }
    }

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killEntity = true) }
        activeFights.clear()
    }
}