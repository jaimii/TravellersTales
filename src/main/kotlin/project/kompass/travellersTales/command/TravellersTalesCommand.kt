package project.kompass.travellersTales.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import org.bukkit.entity.Wither
import org.bukkit.event.entity.CreatureSpawnEvent
import project.kompass.travellersTales.TravellersTales
import project.kompass.travellersTales.boss.cube.CubeBossType
import project.kompass.travellersTales.util.mm

object TravellersTalesCommand {

    fun create(plugin: TravellersTales): LiteralCommandNode<CommandSourceStack> {
        return Commands.literal("travellerstales")
            .requires { it.sender.hasPermission("travellerstales.admin") }
            .then(
                Commands.literal("summon")
                    .then(
                        Commands.literal("wither")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                player.world.spawn(player.location, Wither::class.java, CreatureSpawnEvent.SpawnReason.CUSTOM)
                                player.sendMessage("<bold><#8F09CE>[The Wither]</#8F09CE></bold> <gray>Summoned with intro animation!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("warden")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.wardenManager.summonWardenWithIntro(player.location, player)
                                player.sendMessage("<bold><#00A8FF>[The Warden]</#00A8FF></bold> <gray>Emerging from the deep dark!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("traveller")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.travellerManager.spawnTraveller(player.location, player.uniqueId)
                                player.sendMessage("<bold><#E5C158>[The Traveller]</#E5C158></bold> <gray>Summoned with rift intro!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("giant")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.giantManager.spawnGiant(player.location, player.uniqueId)
                                player.sendMessage("<bold><green>[Giant]</green></bold> <gray>Crawling out of the earth!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("dreadnought")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.dreadnoughtManager.spawnDreadnought(player.location, playSpawnAnimation = true)
                                player.sendMessage("<bold><gold>[Dreadnought Golem]</gold></bold> <gray>Beacon core powering online!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("world_slime")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.cubeBossManager.summonCubeBossWithIntro(player.location, CubeBossType.WORLD_SLIME)
                                player.sendMessage("<bold><green>[World Slime]</green></bold> <gray>Awakening The World Slime!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("magma_core")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                plugin.cubeBossManager.summonCubeBossWithIntro(player.location, CubeBossType.MAGMA_CORE)
                                player.sendMessage("<bold><red>[Magma Core]</red></bold> <gray>Awakening The Magma Core!</gray>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("boots")
                    .then(
                        Commands.literal("bouncy")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                player.inventory.addItem(plugin.cubeBossManager.createSpecialBoots(CubeBossType.WORLD_SLIME))
                                player.sendMessage("<green>Given Bouncy Boots!</green>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
                    .then(
                        Commands.literal("magma")
                            .executes { ctx ->
                                val player = requirePlayer(ctx.source) ?: return@executes Command.SINGLE_SUCCESS
                                player.inventory.addItem(plugin.cubeBossManager.createSpecialBoots(CubeBossType.MAGMA_CORE))
                                player.sendMessage("<gold>Given Magma Core Boots!</gold>".mm())
                                Command.SINGLE_SUCCESS
                            }
                    )
            )
            .then(
                Commands.literal("reload")
                    .executes { ctx ->
                        plugin.reloadConfig()
                        plugin.weaponResistanceListener.reloadConfig()
                        plugin.witherManager.reloadConfig()
                        plugin.dragonManager.reloadConfig()
                        plugin.wardenManager.reloadConfig()
                        plugin.travellerManager.reloadConfig()
                        plugin.giantManager.reloadConfig()
                        plugin.dreadnoughtManager.reloadConfig()
                        plugin.cubeBossManager.reloadConfig()

                        ctx.source.sender.sendMessage(
                            "<bold><gold>[TravellersTales]</gold></bold> <green>All boss configurations and weapon resistances reloaded from config.yml!</green>".mm()
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("fix")
                    .executes { ctx ->
                        plugin.witherManager.runFixRoutine()
                        plugin.dragonManager.runFixRoutine()
                        plugin.wardenManager.runFixRoutine()
                        plugin.travellerManager.runFixRoutine()
                        plugin.giantManager.runFixRoutine()
                        plugin.dreadnoughtManager.runFixRoutine()
                        plugin.cubeBossManager.runFixRoutine()
                        plugin.bossBarManager.clearAllBars()

                        ctx.source.sender.sendMessage(
                            "<bold><gold>[TravellersTales]</gold></bold> <red>Cleared all active boss fights, tasks, and boss bars!</red>".mm()
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .then(
                Commands.literal("status")
                    .executes { ctx ->
                        val witherAlive = plugin.witherManager.activeFight?.wither?.isValid == true
                        val dragonAlive = plugin.dragonManager.activeFight?.dragon?.isValid == true
                        val wardens = plugin.wardenManager.activeFights.size
                        val travellers = plugin.travellerManager.activeFights.size
                        val giants = plugin.giantManager.activeFights.size
                        val dreadnoughts = plugin.dreadnoughtManager.activeFights.size
                        val cubeBosses = plugin.cubeBossManager.activeFights.size
                        val weaponResOnlyBosses = plugin.weaponResistanceListener.onlyCustomBosses
                        val weaponResMult = plugin.weaponResistanceListener.damageMultiplier

                        ctx.source.sender.sendMessage(
                            """
                            <bold><gold>--- TravellersTales Boss Status ---</gold></bold>
                            <gray>The Wither:</gray> ${if (witherAlive) "<red>Active</red>" else "<green>None</green>"}
                            <gray>Ender Dragon:</gray> ${if (dragonAlive) "<red>Active</red>" else "<green>None</green>"}
                            <gray>Active Wardens:</gray> <aqua>$wardens</aqua>
                            <gray>Active Travellers:</gray> <yellow>$travellers</yellow>
                            <gray>Active Giants:</gray> <green>$giants</green>
                            <gray>Active Dreadnoughts:</gray> <gold>$dreadnoughts</gold>
                            <gray>Active Cube Bosses:</gray> <light_purple>$cubeBosses</light_purple>
                            <gray>Weapon Resistance:</gray> <white>${(1.0 - weaponResMult) * 100}% reduction</white> <gray>(Only Bosses: <white>$weaponResOnlyBosses</white>)</gray>
                            """.trimIndent().mm()
                        )
                        Command.SINGLE_SUCCESS
                    }
            )
            .build()
    }

    private fun requirePlayer(source: CommandSourceStack): Player? {
        val player = source.executor as? Player ?: (source.sender as? Player)
        if (player == null) {
            source.sender.sendMessage("<red>Only players can execute this command at their location.</red>".mm())
        }
        return player
    }
}