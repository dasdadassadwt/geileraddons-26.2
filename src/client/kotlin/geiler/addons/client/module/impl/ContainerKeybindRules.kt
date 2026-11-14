package geiler.addons.client.module.impl

/** Pure rules of the Wardrobe and Loadout keybinds: which container slot a pressed key clicks. */
object ContainerKeybindRules {
    /** What a key press means: [handled] cancels the key, [slot] is the slot to click or -1, [message] a local chat line. */
    data class Outcome(val handled: Boolean, val slot: Int, val message: String?) {
        companion object {
            val IGNORED = Outcome(false, -1, null)
        }
    }

    private val WARDROBE_TITLE = Regex("\\((\\d)/(\\d)\\) (Armor|Equipment) Sets")
    private val WARDROBE_EQUIPPED = Regex("Slot (\\d): Equipped")
    private val LOADOUT_TITLE = Regex("\\((\\d)/(\\d)\\) Loadout")

    const val WARDROBE_NEXT_SLOT = 53
    const val WARDROBE_PREVIOUS_SLOT = 45
    const val WARDROBE_FIRST_SET_SLOT = 36
    const val LOADOUT_NEXT_SLOT = 44
    const val LOADOUT_PREVIOUS_SLOT = 17
    const val ALREADY_EQUIPPED = "§cSet already equipped."

    /** The twelve loadout slots in the menu, row by row. */
    val LOADOUT_SLOTS = intArrayOf(14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43)

    /** A saved key is a full key name, or one character for a plain key; anything else is returned trimmed. */
    @JvmStatic
    fun normalize(serialized: String?): String {
        val value = serialized?.trim().orEmpty()
        return if (value.length == 1 && !value.contains('.')) "key.keyboard.${value.lowercase()}" else value
    }

    @JvmStatic
    fun isWardrobeTitle(title: String): Boolean = WARDROBE_TITLE.containsMatchIn(title)

    @JvmStatic
    fun isLoadoutTitle(title: String): Boolean = LOADOUT_TITLE.containsMatchIn(title)

    /** The index of the equipped set's slot from the item names, or null. */
    @JvmStatic
    fun equippedWardrobeSlot(names: List<Pair<Int, String>>): Int? =
        names.firstOrNull { WARDROBE_EQUIPPED.matches(it.second) }?.first

    /**
     * Wardrobe: Next/Previous page, Unequip, or one of nine set keys. With [disallowUnequip], pressing the key of the
     * already equipped set shows a message and passes through, and an empty target slot is ignored.
     */
    @JvmStatic
    fun wardrobe(
        title: String,
        pressed: String,
        next: String,
        previous: String,
        unequip: String,
        setKeys: List<String>,
        equippedSlot: Int?,
        disallowUnequip: Boolean,
        slotEmpty: (Int) -> Boolean,
    ): Outcome {
        val match = WARDROBE_TITLE.find(title) ?: return Outcome.IGNORED
        val current = match.groupValues[1].toIntOrNull() ?: return Outcome.IGNORED
        val total = match.groupValues[2].toIntOrNull() ?: return Outcome.IGNORED
        val slot = when {
            pressed.isEmpty() -> return Outcome.IGNORED
            pressed == next -> if (current < total) WARDROBE_NEXT_SLOT else return Outcome.IGNORED
            pressed == previous -> if (current > 1) WARDROBE_PREVIOUS_SLOT else return Outcome.IGNORED
            pressed == unequip -> equippedSlot ?: return Outcome.IGNORED
            else -> {
                val index = setKeys.indexOf(pressed)
                if (index < 0) return Outcome.IGNORED
                if (equippedSlot == index + WARDROBE_FIRST_SET_SLOT && disallowUnequip) {
                    return Outcome(false, -1, ALREADY_EQUIPPED)
                }
                index + WARDROBE_FIRST_SET_SLOT
            }
        }
        if (disallowUnequip && slotEmpty(slot)) return Outcome.IGNORED
        return Outcome(true, slot, null)
    }

    /** Loadout: Next/Previous page, or one of twelve loadout keys. */
    @JvmStatic
    fun loadout(
        title: String,
        pressed: String,
        next: String,
        previous: String,
        loadoutKeys: List<String>,
    ): Outcome {
        val match = LOADOUT_TITLE.find(title) ?: return Outcome.IGNORED
        val current = match.groupValues[1].toIntOrNull() ?: return Outcome.IGNORED
        val total = match.groupValues[2].toIntOrNull() ?: return Outcome.IGNORED
        val slot = when {
            pressed.isEmpty() -> return Outcome.IGNORED
            pressed == next -> if (current < total) LOADOUT_NEXT_SLOT else return Outcome.IGNORED
            pressed == previous -> if (current > 1) LOADOUT_PREVIOUS_SLOT else return Outcome.IGNORED
            else -> {
                val index = loadoutKeys.indexOf(pressed)
                if (index < 0) return Outcome.IGNORED
                LOADOUT_SLOTS.getOrNull(index) ?: return Outcome.IGNORED
            }
        }
        return Outcome(true, slot, null)
    }
}
