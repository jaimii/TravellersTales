package project.kompass.travellersTales.boss.herobrine

import com.destroystokyo.paper.profile.ProfileProperty
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import io.papermc.paper.datacomponent.item.ResolvableProfile
import java.util.UUID

object HerobrineSkinUtil {

    const val DEFAULT_TEXTURE =
        "ewogICJ0aW1lc3RhbXAiIDogMTczNjIyMDkyNjc2MCwKICAicHJvZmlsZUlkIiA6ICJjNjI5MzRjYmE5YTE0NjYwYTk3NTk0YjNlMjQ0ZjhhOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJBQUFycm9iYSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jY2NmNmU0YjIxZmQzODc1YTA4NTY3ZDliZDU1NmYzZWU2OTYyMTg2NzM5NGQyNGI5NmQ3ZjBmYThkMmFlZDE4IgogICAgfQogIH0KfQ=="

    const val DEFAULT_SIGNATURE =
        "IAUARrvpQqBq7QmE407LCQ373/zt6yTpBmtCFfxE63WGma+jnrcrXwsBa0NTGFIciicogG212fpWBNnuYIhcKXS6G1rsKKwSqTho9WXysAsTApYWSGZ6Pz/PzsUCJT53xNbplY1d2P5dZL1hSI//Q6UKOtAd5mLtwheZDAXPCLyNruXU0ysUDJBFd6QPggXl/RLUVGcA+X5sP8sfQUsaZDdUduMgoJ8ob675q6/veBaYaoiSGWmcthn5/RQAnPxB7LOaMT+h82MrXu4b7Lzu0D1BnwKFJFCDrZxBg0vsGVQWTTtUetsPNaFZ4o8uEmHQOZ8UkimCNhW6MYLCbX/8m0V1v7gb51M4Iqp907Rh9vLwU2l2YaoolKAE/MchnXEgsJUDaWjONYT8FPIAatWR5Ckc6r4UydV/p0CHelDO0wM3kcys2Z7BMz6q3v0wxwFRbNGU0VGPVzgT0LOJ/26VBxxHSk4DOW49jjvY8pueNYs47pVeEX7Yf1oO+1wEL+RCKBXjxBEVxAJEERSsHk+iBdMfnev+Lvc9nD4BhV/QADZELJv6nyvuZ+iVFaqLww07jWnKadwYF3pVTUKfsqhaijagAf2jjQrTqAeuWtzCaMR5ueau0AZ9p5MY5EsKM7NMh+GjUojWMO089eldp1B0RsB3YY3vKZGbSk8V+QXWnxA="

    /**
     * Builds Paper 26.3's ResolvableProfile for the Mannequin entity.
     */
    @Suppress("UnstableApiUsage")
    fun createMannequinProfile(
        uuid: UUID,
        name: String = "Herobrine",
        texture: String = DEFAULT_TEXTURE,
        signature: String = DEFAULT_SIGNATURE
    ): ResolvableProfile {
        return ResolvableProfile.resolvableProfile()
            .uuid(uuid)
            .name(name)
            .addProperty(ProfileProperty("textures", texture, signature))
            .build()
    }

    /**
     * Builds PacketEvents' UserProfile for the fake player Tablist entry.
     */
    fun createPacketEventsProfile(
        uuid: UUID,
        name: String = "Herobrine",
        texture: String = DEFAULT_TEXTURE,
        signature: String = DEFAULT_SIGNATURE
    ): UserProfile {
        val profile = UserProfile(uuid, name)
        profile.textureProperties = listOf(TextureProperty("textures", texture, signature))
        return profile
    }
}