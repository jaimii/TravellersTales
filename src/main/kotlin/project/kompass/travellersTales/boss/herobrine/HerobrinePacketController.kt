package project.kompass.travellersTales.boss.herobrine

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.EnumSet
import java.util.UUID

object HerobrinePacketController {

    private fun isPacketEventsAvailable(): Boolean =
        Bukkit.getPluginManager().isPluginEnabled("packetevents")

    /**
     * Adds Herobrine to [viewer]'s Tablist (PlayerList) with his skin and name.
     */
    fun sendTablistAdd(viewer: Player, herobrineUuid: UUID, name: String = "Herobrine") {
        if (!isPacketEventsAvailable()) return

        val profile = HerobrineSkinUtil.createPacketEventsProfile(herobrineUuid, name)
        val displayName = Component.text(name, NamedTextColor.WHITE)

        val playerInfo = WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
            profile,
            true, // listed = true so Herobrine appears on the playerlist!
            0,
            GameMode.SURVIVAL,
            displayName,
            null
        )

        val actions = EnumSet.of(
            Action.ADD_PLAYER,
            Action.UPDATE_LISTED,
            Action.UPDATE_GAME_MODE,
            Action.UPDATE_DISPLAY_NAME,
            Action.UPDATE_LATENCY
        )

        val infoPacket = WrapperPlayServerPlayerInfoUpdate(actions, playerInfo)
        PacketEvents.getAPI().playerManager.sendPacket(viewer, infoPacket)
    }

    fun broadcastTablistAdd(herobrineUuid: UUID, name: String = "Herobrine") {
        for (player in Bukkit.getOnlinePlayers()) {
            sendTablistAdd(player, herobrineUuid, name)
        }
    }

    /**
     * Removes Herobrine from all online players' Tablists when he leaves/dies.
     */
    fun broadcastTablistRemove(herobrineUuid: UUID) {
        if (!isPacketEventsAvailable()) return
        val removePacket = WrapperPlayServerPlayerInfoRemove(herobrineUuid)
        for (player in Bukkit.getOnlinePlayers()) {
            PacketEvents.getAPI().playerManager.sendPacket(player, removePacket)
        }
    }

    /**
     * Sends smooth head-look and body rotation packets for the Mannequin entity ID.
     */
    fun broadcastHeadRotation(entityId: Int, viewers: Collection<Player>, yaw: Float, pitch: Float) {
        if (!isPacketEventsAvailable()) return
        val rotationPacket = WrapperPlayServerEntityRotation(entityId, yaw, pitch, true)
        val headPacket = WrapperPlayServerEntityHeadLook(entityId, yaw)

        for (viewer in viewers) {
            if (viewer.isOnline) {
                PacketEvents.getAPI().playerManager.sendPacket(viewer, rotationPacket)
                PacketEvents.getAPI().playerManager.sendPacket(viewer, headPacket)
            }
        }
    }
}