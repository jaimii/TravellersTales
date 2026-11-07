package project.kompass.travellersTales.boss.herobrine

import github.scarsz.discordsrv.DiscordSRV
import org.bukkit.Bukkit

object DiscordSrvHook {

    private fun isDiscordSrvEnabled(): Boolean =
        Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")

    fun sendHerobrineMessage(plainMessage: String) {
        if (!isDiscordSrvEnabled()) return
        runCatching {
            val channel = DiscordSRV.getPlugin().mainTextChannel ?: return
            channel.sendMessage(plainMessage).queue()
        }
    }

    fun sendJoinMessage() {
        sendHerobrineMessage("📥 **Herobrine has joined the game.**")
    }

    fun sendLeaveMessage() {
        sendHerobrineMessage("📤 **Herobrine has left the game.**")
    }

    fun sendPlayerSlainByHerobrine(playerName: String) {
        sendHerobrineMessage("💀 **Herobrine has slain $playerName**")
    }

    fun sendHerobrineSlainByPlayer(playerName: String) {
        sendHerobrineMessage("⚔️ **$playerName has slain Herobrine.**")
    }
}