package geiler.addons.client.inventory

/** Pure protection policy for inventory-slot clicks, quick moves, swaps, and drops. */
object PlayerSlotLockRules {
    enum class ContainerAction {
        PICKUP,
        QUICK_MOVE,
        SWAP,
        CLONE,
        THROW,
        QUICK_CRAFT,
        PICKUP_ALL,
    }

    /** One slot's state needed to simulate vanilla's ordered moveItemStackTo algorithm. */
    class QuickMoveDestination(
        locked: Boolean,
        empty: Boolean,
        sameItem: Boolean,
        mayPlace: Boolean,
        existingCount: Int,
        maxStackSize: Int,
    ) {
        private val lockedValue = locked
        private val emptyValue = empty
        private val sameItemValue = sameItem
        private val mayPlaceValue = mayPlace
        private val existingCountValue = existingCount.coerceAtLeast(0)
        private val maxStackSizeValue = maxStackSize.coerceAtLeast(0)

        fun locked(): Boolean = lockedValue

        fun empty(): Boolean = emptyValue

        fun sameItem(): Boolean = sameItemValue

        fun mayPlace(): Boolean = mayPlaceValue

        fun existingCount(): Int = existingCountValue

        fun maxStackSize(): Int = maxStackSizeValue

        override fun equals(other: Any?): Boolean = other is QuickMoveDestination &&
            lockedValue == other.lockedValue && emptyValue == other.emptyValue &&
            sameItemValue == other.sameItemValue && mayPlaceValue == other.mayPlaceValue &&
            existingCountValue == other.existingCountValue && maxStackSizeValue == other.maxStackSizeValue

        override fun hashCode(): Int {
            var result = lockedValue.booleanHashCode()
            result = 31 * result + emptyValue.booleanHashCode()
            result = 31 * result + sameItemValue.booleanHashCode()
            result = 31 * result + mayPlaceValue.booleanHashCode()
            result = 31 * result + existingCountValue
            result = 31 * result + maxStackSizeValue
            return result
        }

        override fun toString(): String =
            "QuickMoveDestination[locked=$lockedValue, empty=$emptyValue, sameItem=$sameItemValue, " +
                "mayPlace=$mayPlaceValue, existingCount=$existingCountValue, maxStackSize=$maxStackSizeValue]"

        private fun Boolean.booleanHashCode(): Int = if (this) 1231 else 1237
    }

    @JvmStatic
    fun blocksSlotClick(
        playerInventorySlot: Boolean,
        inventorySlot: Int,
        settings: PlayerInventoryControlsData?,
    ): Boolean = playerInventorySlot && settings?.isLocked(inventorySlot) == true

    @JvmStatic
    fun blocksWorldDrop(selectedHotbarIndex: Int, settings: PlayerInventoryControlsData?): Boolean =
        selectedHotbarIndex in 0..8 && settings?.isLocked(selectedHotbarIndex) == true

    /** Persistent lock/link data only participates while the feature itself is enabled. */
    @JvmStatic
    fun shouldPreflightQuickMove(moduleEnabled: Boolean, hasProtectedSlots: Boolean): Boolean =
        moduleEnabled && hasProtectedSlots

    /** A lock protects movement; selecting and using a hotbar slot stay available. */
    @JvmStatic
    fun allowsHotbarSelect(hotbarIndex: Int): Boolean = hotbarIndex in 0..8

    /** Returns the carried player inventory index targeted by a container SWAP button. */
    @JvmStatic
    fun swapTargetInventorySlot(button: Int): Int = when {
        button in 0..8 -> button
        button == 40 -> 40
        else -> -1
    }

    /** Simulates vanilla's ordered merge and empty-slot phases over the actual destination range. */
    @JvmStatic
    fun quickMoveWouldMutateLockedSlot(
        sourceCount: Int,
        stackable: Boolean,
        orderedDestinations: List<QuickMoveDestination?>?,
    ): Boolean {
        if (sourceCount <= 0 || orderedDestinations.isNullOrEmpty()) return false
        var remaining = sourceCount
        if (stackable) {
            for (destination in orderedDestinations) {
                if (destination == null || destination.empty() || !destination.sameItem()) continue
                val capacity = destination.maxStackSize() - destination.existingCount()
                val moved = minOf(remaining, capacity)
                if (moved <= 0) continue
                if (destination.locked()) return true
                remaining -= moved
                if (remaining == 0) return false
            }
        }
        for (destination in orderedDestinations) {
            if (destination == null || !destination.empty() || !destination.mayPlace()) continue
            val moved = minOf(remaining, destination.maxStackSize())
            return moved > 0 && destination.locked()
        }
        return false
    }

    /** Feature-gated overload used by the runtime and offline checks. */
    @JvmStatic
    fun quickMoveWouldMutateLockedSlot(
        moduleEnabled: Boolean,
        hasProtectedSlots: Boolean,
        sourceCount: Int,
        stackable: Boolean,
        orderedDestinations: List<QuickMoveDestination?>?,
    ): Boolean = shouldPreflightQuickMove(moduleEnabled, hasProtectedSlots) &&
        quickMoveWouldMutateLockedSlot(sourceCount, stackable, orderedDestinations)

    /** Evaluates operations which can move through more than the directly clicked slot. */
    @JvmStatic
    fun blocksContainerAction(
        action: ContainerAction?,
        clickedSlotLocked: Boolean,
        swapTargetLocked: Boolean,
        quickMoveCouldEnterLockedSlot: Boolean,
        pickupAllWouldTakeLockedSlot: Boolean,
    ): Boolean = when (action) {
        null -> false
        ContainerAction.PICKUP,
        ContainerAction.CLONE,
        ContainerAction.THROW,
        ContainerAction.QUICK_CRAFT -> clickedSlotLocked
        ContainerAction.QUICK_MOVE -> clickedSlotLocked || quickMoveCouldEnterLockedSlot
        ContainerAction.SWAP -> clickedSlotLocked || swapTargetLocked
        ContainerAction.PICKUP_ALL -> clickedSlotLocked || pickupAllWouldTakeLockedSlot
    }
}
