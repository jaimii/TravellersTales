package project.kompass.travellersTales

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.entity.Wither
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.travellersTales.boss.bar.BossBarManager
import project.kompass.travellersTales.boss.cube.CubeBossListener
import project.kompass.travellersTales.boss.cube.CubeBossManager
import project.kompass.travellersTales.boss.dragon.DragonListener
import project.kompass.travellersTales.boss.dragon.DragonManager
import project.kompass.travellersTales.boss.giant.GiantListener
import project.kompass.travellersTales.boss.giant.GiantManager
import project.kompass.travellersTales.boss.golem.DreadnoughtListener
import project.kompass.travellersTales.boss.golem.DreadnoughtManager
import project.kompass.travellersTales.boss.herobrine.HerobrineListener
import project.kompass.travellersTales.boss.herobrine.HerobrineManager
import project.kompass.travellersTales.boss.traveller.TravellerListener
import project.kompass.travellersTales.boss.traveller.TravellerManager
import project.kompass.travellersTales.boss.warden.WardenListener
import project.kompass.travellersTales.boss.warden.WardenManager
import project.kompass.travellersTales.boss.wither.WitherListener
import project.kompass.travellersTales.boss.wither.WitherManager
import project.kompass.travellersTales.command.TravellersTalesCommand
import project.kompass.travellersTales.listener.BossWeaponResistanceListener
import project.kompass.travellersTales.registry.MobKeys
import project.kompass.travellersTales.util.mm

class TravellersTales : JavaPlugin() {

    lateinit var bossBarManager: BossBarManager
        private set

    lateinit var weaponResistanceListener: BossWeaponResistanceListener
        private set

    lateinit var witherManager: WitherManager
        private set

    lateinit var dragonManager: DragonManager
        private set

    lateinit var wardenManager: WardenManager
        private set

    lateinit var travellerManager: TravellerManager
        private set

    lateinit var giantManager: GiantManager
        private set

    lateinit var dreadnoughtManager: DreadnoughtManager
        private set

    lateinit var cubeBossManager: CubeBossManager
        private set

    lateinit var herobrineManager: HerobrineManager
        private set

    override fun onEnable() {
        saveDefaultConfig()
        MobKeys.init(this)

        // 1. Shared Adventure BossBar Manager & Configurable Mace/Spear Weapon Resistance
        bossBarManager = BossBarManager()
        bossBarManager.init(this)

        weaponResistanceListener = BossWeaponResistanceListener(this)
        server.pluginManager.registerEvents(weaponResistanceListener, this)

        // 2. The Wither Boss module
        witherManager = WitherManager(this)
        server.pluginManager.registerEvents(WitherListener(this, witherManager), this)

        // 3. The Ender Dragon Boss module
        dragonManager = DragonManager(this)
        server.pluginManager.registerEvents(DragonListener(this, dragonManager), this)

        // 4. The Warden Boss module
        wardenManager = WardenManager(this)
        server.pluginManager.registerEvents(WardenListener(this, wardenManager), this)

        // 5. The Traveller Mini-Boss module
        travellerManager = TravellerManager(this)
        travellerManager.init()
        server.pluginManager.registerEvents(TravellerListener(travellerManager), this)

        // 6. The Giant Mini-Boss module
        giantManager = GiantManager(this)
        giantManager.init()
        server.pluginManager.registerEvents(GiantListener(giantManager), this)

        // 7. The Dreadnought Golem Construct module
        dreadnoughtManager = DreadnoughtManager(this)
        server.pluginManager.registerEvents(DreadnoughtListener(this, dreadnoughtManager), this)

        // 8. The World Slime & Magma Core module (includes Spawn Egg ShapedRecipes)
        cubeBossManager = CubeBossManager(this)
        server.pluginManager.registerEvents(CubeBossListener(this, cubeBossManager), this)

        // 9. Herobrine Boss module
        herobrineManager = HerobrineManager(this)
        server.pluginManager.registerEvents(HerobrineListener(this, herobrineManager), this)

        // 10. Register ONLY the central /travellerstales (/tt) command
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                TravellersTalesCommand.create(this),
                "Central command to summon and manage TravellersTales bosses",
                listOf("tt")
            )
        }

        // Clean up any stray Withers if the server/plugin was reloaded mid-fight
        Bukkit.getWorlds().forEach { world ->
            val strayWithers = world.getEntitiesByClass(Wither::class.java)
            if (strayWithers.isNotEmpty()) {
                Bukkit.broadcast(
                    "<bold><#322A32>[ᴛʜᴇ ᴡɪᴛʜᴇʀ] </#322A32><dark_red>ᴛʜᴇ ᴘʟᴜɢɪɴ ᴡᴀꜱ (ʀᴇ)ʟᴏᴀᴅᴇᴅ ᴡʜɪʟᴇ ᴀ ᴡɪᴛʜᴇʀ ᴡᴀꜱ ᴀʟɪᴠᴇ... ᴋɪʟʟɪɴɢ ɪᴛ!!!</dark_red></bold>".mm()
                )
                strayWithers.forEach { it.remove() }
                witherManager.runFixRoutine()
            }
        }
    }

    override fun onDisable() {
        if (::witherManager.isInitialized) witherManager.runFixRoutine()
        if (::dragonManager.isInitialized) dragonManager.runFixRoutine()
        if (::wardenManager.isInitialized) wardenManager.runFixRoutine()
        if (::travellerManager.isInitialized) travellerManager.runFixRoutine()
        if (::giantManager.isInitialized) giantManager.runFixRoutine()
        if (::dreadnoughtManager.isInitialized) dreadnoughtManager.runFixRoutine()
        if (::cubeBossManager.isInitialized) cubeBossManager.runFixRoutine()
        if (::herobrineManager.isInitialized) herobrineManager.runFixRoutine()
        if (::bossBarManager.isInitialized) bossBarManager.clearAllBars()
    }
}