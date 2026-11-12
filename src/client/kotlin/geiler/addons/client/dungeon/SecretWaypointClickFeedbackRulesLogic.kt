package geiler.addons.client.dungeon

/** Pure click-feedback policy behind the Java record and enum API. */
object SecretWaypointClickFeedbackRulesLogic {
    private const val NANOS_PER_MILLI = 1_000_000L

    @JvmStatic
    fun fromCandidateLabel(label: String?): SecretWaypointClickFeedbackRules.Type {
        if (label == null) return SecretWaypointClickFeedbackRules.Type.UNKNOWN
        return when (label) {
            "Chest", "Chest candidate" -> SecretWaypointClickFeedbackRules.Type.CHEST
            "Lever", "Lever candidate" -> SecretWaypointClickFeedbackRules.Type.LEVER
            "Bat", "Bat candidate" -> SecretWaypointClickFeedbackRules.Type.BAT
            "Redstone Key", "Redstone key candidate" -> SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY
            "Essence", "Essence drop candidate" -> SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP
            "Dropped Item", "Dropped item candidate" -> SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM
            "Item Frame", "Item frame candidate" -> SecretWaypointClickFeedbackRules.Type.ITEM_FRAME
            else -> SecretWaypointClickFeedbackRules.Type.UNKNOWN
        }
    }

    @JvmStatic
    fun selected(
        type: SecretWaypointClickFeedbackRules.Type?,
        selection: SecretWaypointClickFeedbackRules.Selection?,
    ): Boolean {
        if (type == null) return false
        if (type == SecretWaypointClickFeedbackRules.Type.WAYPOINT) return true
        if (selection == null) return false
        return when (type) {
            SecretWaypointClickFeedbackRules.Type.CHEST -> selection.chests()
            SecretWaypointClickFeedbackRules.Type.LEVER -> selection.levers()
            SecretWaypointClickFeedbackRules.Type.BAT -> selection.bats()
            SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY -> selection.redstoneKeys()
            SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP -> selection.essenceDrops()
            SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM -> selection.droppedItems()
            SecretWaypointClickFeedbackRules.Type.ITEM_FRAME -> selection.droppedItems()
            SecretWaypointClickFeedbackRules.Type.WAYPOINT,
            SecretWaypointClickFeedbackRules.Type.UNKNOWN,
            -> false
        }
    }

    @JvmStatic
    fun clickAction(
        type: SecretWaypointClickFeedbackRules.Type?,
        selection: SecretWaypointClickFeedbackRules.Selection?,
        bossPhase: Boolean,
        allowSecretBlocksInBoss: Boolean,
    ): SecretWaypointClickFeedbackRules.Action {
        if (!selected(type, selection)) return SecretWaypointClickFeedbackRules.Action.IGNORE
        if (isSecretBlock(type) && bossPhase && !allowSecretBlocksInBoss) {
            return SecretWaypointClickFeedbackRules.Action.IGNORE
        }
        return if (requiresPickupConfirmation(type)) {
            SecretWaypointClickFeedbackRules.Action.WAIT_FOR_PICKUP
        } else {
            SecretWaypointClickFeedbackRules.Action.ACTIVATE_NOW
        }
    }

    @JvmStatic
    fun isSecretBlock(type: SecretWaypointClickFeedbackRules.Type?): Boolean =
        type == SecretWaypointClickFeedbackRules.Type.CHEST || type == SecretWaypointClickFeedbackRules.Type.LEVER

    @JvmStatic
    fun requiresPickupConfirmation(type: SecretWaypointClickFeedbackRules.Type?): Boolean =
        type == SecretWaypointClickFeedbackRules.Type.BAT ||
            type == SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY ||
            type == SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP ||
            type == SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM ||
            type == SecretWaypointClickFeedbackRules.Type.ITEM_FRAME

    /** Accepts only a local-player pickup packet for a recognized item-entity marker. */
    @JvmStatic
    fun confirmsPickupPacket(
        packetPlayerId: Int,
        localPlayerId: Int,
        targetIsItemEntity: Boolean,
        type: SecretWaypointClickFeedbackRules.Type?,
    ): Boolean = packetPlayerId == localPlayerId && targetIsItemEntity &&
        (type == SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY ||
            type == SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP ||
            type == SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM ||
            type == SecretWaypointClickFeedbackRules.Type.ITEM_FRAME)

    /** Validates a packet against the exact click-time entity when Vanilla already removed it locally. */
    @JvmStatic
    fun confirmsPendingItemPickup(
        packetPlayerId: Int,
        localPlayerId: Int,
        packetEntityId: Int,
        pendingEntityId: Int,
        pendingType: SecretWaypointClickFeedbackRules.Type?,
        hasCollectionTarget: Boolean,
    ): Boolean = packetEntityId == pendingEntityId && hasCollectionTarget &&
        confirmsPickupPacket(packetPlayerId, localPlayerId, true, pendingType)

    /** Bat markers are collected only from the server's exact bat-death sound event. */
    @JvmStatic
    fun confirmsBatDeathSound(soundId: String?): Boolean = "minecraft:entity.bat.death" == soundId

    /** Client-only entity disappearance is not authoritative collection evidence. */
    @JvmStatic
    fun pickupConfirmed(
        type: SecretWaypointClickFeedbackRules.Type?,
        entityGone: Boolean,
        itemCountBefore: Int,
        itemCountNow: Int,
        itemFrameNowEmpty: Boolean,
    ): Boolean {
        if (!requiresPickupConfirmation(type)) return false
        return when (type) {
            SecretWaypointClickFeedbackRules.Type.BAT -> false
            SecretWaypointClickFeedbackRules.Type.ITEM_FRAME -> itemFrameNowEmpty
            SecretWaypointClickFeedbackRules.Type.REDSTONE_KEY,
            SecretWaypointClickFeedbackRules.Type.ESSENCE_DROP,
            SecretWaypointClickFeedbackRules.Type.DROPPED_ITEM,
            -> itemCountNow < itemCountBefore
            SecretWaypointClickFeedbackRules.Type.WAYPOINT,
            SecretWaypointClickFeedbackRules.Type.CHEST,
            SecretWaypointClickFeedbackRules.Type.LEVER,
            SecretWaypointClickFeedbackRules.Type.UNKNOWN,
            null,
            -> false
        }
    }

    @JvmStatic
    fun deadlineNanos(nowNanos: Long, windowMillis: Int): Long {
        val bounded = maxOf(
            SecretWaypointClickFeedbackRules.MIN_WINDOW_MILLIS,
            minOf(SecretWaypointClickFeedbackRules.MAX_WINDOW_MILLIS, windowMillis),
        )
        return nowNanos + bounded * NANOS_PER_MILLI
    }

    /** Converts the configured feedback window to the client ticks used by pending block interactions. */
    @JvmStatic
    fun confirmationWindowTicks(windowMillis: Int): Int {
        val bounded = maxOf(
            SecretWaypointClickFeedbackRules.MIN_WINDOW_MILLIS,
            minOf(SecretWaypointClickFeedbackRules.MAX_WINDOW_MILLIS, windowMillis),
        )
        return maxOf(1, (bounded + 49) / 50)
    }

    /** Uses monotonic-time subtraction so a nanosecond counter wrap remains safe. */
    @JvmStatic
    fun expired(nowNanos: Long, deadlineNanos: Long): Boolean = nowNanos - deadlineNanos > 0L
}
