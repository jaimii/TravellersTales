package project.kompass.travellersTales.boss.herobrine

import github.scarsz.discordsrv.DiscordSRV
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder
import org.bukkit.Bukkit
import java.awt.Color
import java.util.UUID

object DiscordSrvHook {

    // Herobrine's head render from his skin texture hash in HerobrineSkinUtil
    private const val HEROBRINE_AVATAR_URL =
        "https://mc-heads.net/avatar/cccf6e4b21fd3875a08567d9bd556f3ee69621867394d24b96d7f0fa8d2aed18/128.png"

    private val JOIN_COLOR = Color(0, 255, 0)       // Bright DiscordSRV Join Green
    private val LEAVE_COLOR = Color(240, 71, 71)    // DiscordSRV Leave Red
    private val DEATH_COLOR = Color(180, 0, 0)      // Deep Crimson for Player Death
    private val VICTORY_COLOR = Color(255, 185, 0)  // Gold for Slaying Herobrine

    private fun isDiscordSrvEnabled(): Boolean =
        Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")

    private fun resolvePlayerAvatarUrl(playerName: String, playerUuid: UUID?): String {
        return runCatching {
            val player = playerUuid?.let { Bukkit.getPlayer(it) } ?: Bukkit.getPlayerExact(playerName)
            if (player != null) {
                DiscordSRV.getAvatarUrl(player)
            } else {
                "https://mc-heads.net/avatar/${playerUuid ?: playerName}/128.png"
            }
        }.getOrDefault("https://mc-heads.net/avatar/${playerUuid ?: playerName}/128.png")
    }

    private fun sendEmbed(authorText: String, iconUrl: String, color: Color, description: String? = null) {
        if (!isDiscordSrvEnabled()) return
        runCatching {
            val channel = DiscordSRV.getPlugin().mainTextChannel ?: return
            val embed = EmbedBuilder()
                .setColor(color)
                .setAuthor(authorText, null, iconUrl)
                .apply {
                    if (!description.isNullOrBlank()) {
                        setDescription(description)
                    }
                }
                .build()

            channel.sendMessageEmbeds(embed).queue()
        }
    }

    fun sendJoinMessage() {
        sendEmbed(
            authorText = "Herobrine has joined the game.",
            iconUrl = HEROBRINE_AVATAR_URL,
            color = JOIN_COLOR
        )
    }

    fun sendLeaveMessage() {
        sendEmbed(
            authorText = "Herobrine has left the game.",
            iconUrl = HEROBRINE_AVATAR_URL,
            color = LEAVE_COLOR
        )
    }

    fun sendPlayerSlainByHerobrine(playerName: String, playerUuid: UUID? = null) {
        val avatarUrl = resolvePlayerAvatarUrl(playerName, playerUuid)
        sendEmbed(
            authorText = "Herobrine has slain $playerName",
            iconUrl = avatarUrl,
            color = DEATH_COLOR
        )
    }

    fun sendHerobrineSlainByPlayer(playerName: String, playerUuid: UUID? = null) {
        val avatarUrl = resolvePlayerAvatarUrl(playerName, playerUuid)
        sendEmbed(
            authorText = "$playerName has slain Herobrine.",
            iconUrl = avatarUrl,
            color = VICTORY_COLOR
        )
    }
}