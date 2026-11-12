package geiler.addons.client.dungeon

import kotlin.jvm.JvmRecord

/** Java enum/record API for the Kotlin Secret Waypoints click-feedback policy. */
class SecretWaypointClickFeedbackRules private constructor() {
    enum class Type {
        WAYPOINT,
        CHEST,
        LEVER,
        BAT,
        REDSTONE_KEY,
        ESSENCE_DROP,
        DROPPED_ITEM,
        ITEM_FRAME,
        UNKNOWN,
    }

    enum class Action { IGNORE, ACTIVATE_NOW, WAIT_FOR_PICKUP }

    /** Mirrors the six visible marker switches; item frames follow the Items switch. */
    @JvmRecord
    data class Selection(
        val chests: Boolean,
        val droppedItems: Boolean,
        val essenceDrops: Boolean,
        val bats: Boolean,
        val redstoneKeys: Boolean,
        val levers: Boolean,
    ) {
        override fun toString(): String =
            "Selection[chests=$chests, droppedItems=$droppedItems, essenceDrops=$essenceDrops, " +
                "bats=$bats, redstoneKeys=$redstoneKeys, levers=$levers]"
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 1_000
        const val MIN_WINDOW_MILLIS = 100
        const val MAX_WINDOW_MILLIS = 10_000

        @JvmStatic
        fun fromCandidateLabel(label: String?): Type = SecretWaypointClickFeedbackRulesLogic.fromCandidateLabel(label)

        @JvmStatic
        fun selected(type: Type?, selection: Selection?): Boolean =
            SecretWaypointClickFeedbackRulesLogic.selected(type, selection)

        @JvmStatic
        fun clickAction(
            type: Type?,
            selection: Selection?,
            bossPhase: Boolean,
            allowSecretBlocksInBoss: Boolean,
        ): Action = SecretWaypointClickFeedbackRulesLogic.clickAction(
            type,
            selection,
            bossPhase,
            allowSecretBlocksInBoss,
        )

        @JvmStatic
        fun isSecretBlock(type: Type?): Boolean = SecretWaypointClickFeedbackRulesLogic.isSecretBlock(type)

        @JvmStatic
        fun requiresPickupConfirmation(type: Type?): Boolean =
            SecretWaypointClickFeedbackRulesLogic.requiresPickupConfirmation(type)

        /** Accepts only a local-player pickup packet for a recognized item-entity marker. */
        @JvmStatic
        fun confirmsPickupPacket(
            packetPlayerId: Int,
            localPlayerId: Int,
            targetIsItemEntity: Boolean,
            type: Type?,
        ): Boolean = SecretWaypointClickFeedbackRulesLogic.confirmsPickupPacket(
            packetPlayerId,
            localPlayerId,
            targetIsItemEntity,
            type,
        )

        /** Validates a packet against the exact click-time entity when Vanilla already removed it locally. */
        @JvmStatic
        fun confirmsPendingItemPickup(
            packetPlayerId: Int,
            localPlayerId: Int,
            packetEntityId: Int,
            pendingEntityId: Int,
            pendingType: Type?,
            hasCollectionTarget: Boolean,
        ): Boolean = SecretWaypointClickFeedbackRulesLogic.confirmsPendingItemPickup(
            packetPlayerId,
            localPlayerId,
            packetEntityId,
            pendingEntityId,
            pendingType,
            hasCollectionTarget,
        )

        /** Bat markers are collected only from the server's exact bat-death sound event. */
        @JvmStatic
        fun confirmsBatDeathSound(soundId: String?): Boolean =
            SecretWaypointClickFeedbackRulesLogic.confirmsBatDeathSound(soundId)

        /** Client-only entity disappearance is not authoritative collection evidence. */
        @JvmStatic
        fun pickupConfirmed(
            type: Type?,
            entityGone: Boolean,
            itemCountBefore: Int,
            itemCountNow: Int,
            itemFrameNowEmpty: Boolean,
        ): Boolean = SecretWaypointClickFeedbackRulesLogic.pickupConfirmed(
            type,
            entityGone,
            itemCountBefore,
            itemCountNow,
            itemFrameNowEmpty,
        )

        @JvmStatic
        fun deadlineNanos(nowNanos: Long, windowMillis: Int): Long =
            SecretWaypointClickFeedbackRulesLogic.deadlineNanos(nowNanos, windowMillis)

        /** Converts the configured feedback window to the client ticks used by pending block interactions. */
        @JvmStatic
        fun confirmationWindowTicks(windowMillis: Int): Int =
            SecretWaypointClickFeedbackRulesLogic.confirmationWindowTicks(windowMillis)

        /** Uses monotonic-time subtraction so a nanosecond counter wrap remains safe. */
        @JvmStatic
        fun expired(nowNanos: Long, deadlineNanos: Long): Boolean =
            SecretWaypointClickFeedbackRulesLogic.expired(nowNanos, deadlineNanos)
    }
}

// Keep the existing Kotlin rule callers' Java-record accessor syntax source-compatible.
internal fun SecretWaypointClickFeedbackRules.Selection.chests(): Boolean = this.chests
internal fun SecretWaypointClickFeedbackRules.Selection.droppedItems(): Boolean = this.droppedItems
internal fun SecretWaypointClickFeedbackRules.Selection.essenceDrops(): Boolean = this.essenceDrops
internal fun SecretWaypointClickFeedbackRules.Selection.bats(): Boolean = this.bats
internal fun SecretWaypointClickFeedbackRules.Selection.redstoneKeys(): Boolean = this.redstoneKeys
internal fun SecretWaypointClickFeedbackRules.Selection.levers(): Boolean = this.levers
