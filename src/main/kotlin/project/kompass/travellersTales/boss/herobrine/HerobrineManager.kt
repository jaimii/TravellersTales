package project.kompass.travellersTales.boss.herobrine

import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.Entity
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
        return activeFights.values.any { !it.isDeadOrRemoved && it.mannequin.isValid && !it.mannequin.isDead }
    }

    /**
     * Checks if igniting netherrackBlock completes the Herobrine Shrine:
     * - Layer Y:   Center NETHERRACK surrounded by 4 cardinal REDSTONE_TORCH blocks
     * - Layer Y-1: 3x3 ring of 8 GOLD_BLOCKs around a GOLD_BLOCK or MOSSY_COBBLESTONE center
     */
    fun tryActivateShrine(netherrackBlock: Block, player: Player): Boolean {
        // 1. Server-wide Singleton Rule: Do nothing at all if Herobrine is already active anywhere
        if (isHerobrineActiveAnywhere()) {
            return false
        }

        if (netherrackBlock.type != Material.NETHERRACK) return false

        // 2. Check 4 cardinal Redstone Torches around the Netherrack
        val torches = listOf(
            netherrackBlock.getRelative(1, 0, 0),
            netherrackBlock.getRelative(-1, 0, 0),
            netherrackBlock.getRelative(0, 0, 1),
            netherrackBlock.getRelative(0, 0, -1)
        )
        if (torches.any { it.type != Material.REDSTONE_TORCH && it.type != Material.REDSTONE_WALL_TORCH }) {
            return false
        }

        // 3. Check 3x3 Gold Block base at Y - 1
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

        // 4. Verify the player has slain all 7 required bosses via BossKillTracker
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

        // 5. Shatter the shrine with lightning and spawn Herobrine in the center
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

    /**
     * Builds "The Hero's Tunneler" Trophy Pickaxe (Unbreakable, 3x3 mining, PDC-persistent).
     */
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

    fun getFight(entity: Entity): ActiveHerobrineFight? = activeFights[entity.uniqueId]

    fun getFightBySteve(steveEntity: Entity): ActiveHerobrineFight? {
        return activeFights.values.firstOrNull { fight ->
            fight.activeSteves.any { it.mannequin.uniqueId == steveEntity.uniqueId }
        }
    }

    fun runFixRoutine() {
        activeFights.values.toList().forEach { it.forceCleanup(killEntity = true) }
        activeFights.clear()
    }
}