package geiler.addons.client.inventory

/**
 * The equipment column's pure rules. The menu title and its necklace, cloak, belt and glove slots follow SkyHanni's
 * current equipment reader.
 */
object EquipmentRules {
    const val SLOTS = 4
    /** The player menu's offhand slot, which gives its place to the column and moves one slot right. */
    const val OFFHAND_MENU_SLOT = 45
    const val OFFHAND_HOME_X = 77
    const val OFFHAND_MOVED_X = OFFHAND_HOME_X + 18
    const val OFFHAND_Y = 62
    /** The recipe book button would overlap the moved offhand slot, so it moves this far right. */
    const val RECIPE_BUTTON_SHIFT = 10
    private val MENU_SLOTS = intArrayOf(10, 19, 28, 37)
    private val MENU_TITLES = setOf("Stats & Equipment", "Your Equipment and Stats")
    private val PROFILE = Regex("""^Profile ID: ([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$""")
    val NAMES = listOf("Necklace", "Cloak", "Belt", "Gloves")

    @JvmStatic
    fun isMenu(title: String): Boolean = title.trim() in MENU_TITLES

    @JvmStatic
    fun menuSlot(index: Int): Int = MENU_SLOTS[index]

    /** The SkyBlock profile id Hypixel prints on joining a server, or null. */
    @JvmStatic
    fun profileId(line: String): String? = PROFILE.matchEntire(line.trim())?.groupValues?.get(1)

    @JvmStatic
    fun key(profileId: String, rift: Boolean): String = if (rift) "$profileId:rift" else profileId

    @JvmStatic
    fun slotX(leftPos: Int): Int = leftPos + OFFHAND_HOME_X

    /** Lines each slot up with the vanilla armor slot beside it. */
    @JvmStatic
    fun slotY(topPos: Int, index: Int): Int = topPos + 8 + index * 18

    /** The four slots with their one pixel borders, so a click between two of them is swallowed too. */
    @JvmStatic
    fun panelContains(leftPos: Int, topPos: Int, x: Int, y: Int): Boolean =
        x >= slotX(leftPos) - 1 && x < slotX(leftPos) + 17 &&
            y >= slotY(topPos, 0) - 1 && y < slotY(topPos, SLOTS - 1) + 17

    @JvmStatic
    fun slotAt(leftPos: Int, topPos: Int, x: Int, y: Int): Int {
        if (x < slotX(leftPos) || x >= slotX(leftPos) + 16) return -1
        for (index in 0 until SLOTS) {
            if (y >= slotY(topPos, index) && y < slotY(topPos, index) + 16) return index
        }
        return -1
    }
}
