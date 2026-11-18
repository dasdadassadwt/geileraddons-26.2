package geiler.addons.client.misc

/** The text format of an Inventory Capture: one bounded line per slot, safe to paste to a person or an AI agent. */
object InventoryCaptureRules {
    const val MAX_SLOTS = 256
    private const val MAX_LORE_LINES = 24
    private const val MAX_TEXT = 200

    @JvmStatic
    fun fileName(stamp: String): String = "inventory-$stamp.txt"

    @JvmStatic
    fun slotLine(slot: Int, playerSlot: Boolean, itemId: String, count: Int, name: String, lore: List<String>): String {
        val head = "slot=$slot container=${if (playerSlot) "player" else "menu"} item=${clean(itemId)} count=$count name=${clean(name)}"
        if (lore.isEmpty()) return head
        return head + " | lore: " + lore.take(MAX_LORE_LINES).joinToString(" / ") { clean(it) }
    }

    private fun clean(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').let { if (it.length > MAX_TEXT) it.substring(0, MAX_TEXT) else it }
}
