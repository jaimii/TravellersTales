package project.kompass.travellersTales.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

private val MINI_MESSAGE = MiniMessage.miniMessage()

/** Parses a MiniMessage string into an Adventure Component. */
fun String.mm(): Component = MINI_MESSAGE.deserialize(this)

/** Parses a MiniMessage string for ItemStack names/lore with default italics disabled. */
fun String.mmItem(): Component = MINI_MESSAGE.deserialize("<!i>$this")