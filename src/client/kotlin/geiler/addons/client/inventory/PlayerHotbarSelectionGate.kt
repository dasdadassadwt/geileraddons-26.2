package geiler.addons.client.inventory

/** Pure input boundary for custom hotbar selection keys. */
object PlayerHotbarSelectionGate {
    /** Custom selection keys only run on a press during in-world play with no open screen. */
    @JvmStatic
    fun allowsSelection(press: Boolean, playerAndWorldReady: Boolean, screenOpen: Boolean): Boolean =
        press && playerAndWorldReady && !screenOpen
}
