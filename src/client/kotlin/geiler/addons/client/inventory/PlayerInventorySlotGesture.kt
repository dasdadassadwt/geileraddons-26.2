package geiler.addons.client.inventory

/** Resolves a single configured-key gesture over player inventory slots. */
object PlayerInventorySlotGesture {
    const val TAP_LOCK_MAX_NANOS: Long = 250_000_000L

    enum class Action {
        NONE,
        TOGGLE_LOCK,
        BIND,
        CLEAR_LINKS,
    }

    @JvmRecord
    data class Resolution(val action: Action, val inventorySlot: Int, val hotbarSlot: Int) {
        override fun toString(): String =
            "Resolution[action=$action, inventorySlot=$inventorySlot, hotbarSlot=$hotbarSlot]"
    }

    @JvmStatic
    fun resolve(sourceSlot: Int, targetSlot: Int, heldNanos: Long): Resolution =
        resolve(sourceSlot, targetSlot, heldNanos, false)

    /** A tap on a slot that already has bindings removes them (like Odin); otherwise it toggles the lock. */
    @JvmStatic
    fun resolve(sourceSlot: Int, targetSlot: Int, heldNanos: Long, sourceHasLinks: Boolean): Resolution {
        if (!isPlayerSlot(sourceSlot) || !isPlayerSlot(targetSlot)) return none()
        if (heldNanos < TAP_LOCK_MAX_NANOS) {
            return when {
                sourceSlot != targetSlot -> none()
                sourceHasLinks -> Resolution(Action.CLEAR_LINKS, sourceSlot, -1)
                else -> Resolution(Action.TOGGLE_LOCK, sourceSlot, -1)
            }
        }
        if (sourceSlot == targetSlot) return Resolution(Action.CLEAR_LINKS, sourceSlot, -1)
        if (sourceSlot >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT && targetSlot < PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) {
            return Resolution(Action.BIND, sourceSlot, targetSlot)
        }
        if (targetSlot >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT && sourceSlot < PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) {
            return Resolution(Action.BIND, targetSlot, sourceSlot)
        }
        return none()
    }

    private fun isPlayerSlot(slot: Int): Boolean =
        slot in 0 until PlayerInventoryControlsData.PLAYER_SLOT_COUNT

    private fun none(): Resolution = Resolution(Action.NONE, -1, -1)
}
