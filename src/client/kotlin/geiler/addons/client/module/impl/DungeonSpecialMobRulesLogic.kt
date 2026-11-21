package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.helpers.SpiritLeapRoster
import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Name and class-color rules for the optional special-mob dungeon overlay. */
class DungeonSpecialMobRules private constructor() {
    companion object {
        /** Strict F7 label filter (regular and Master Mode); entity type is checked separately by the caller. */
        @JvmStatic
        fun isF7WitherBossName(name: String?): Boolean = "wither" == normalize(name)

        /** A Wither overlay is enabled only in F7, regular or Master, after the dungeon's boss-entry signal. [masterMode] is kept for callers and diagnostics. */
        @JvmStatic
        fun shouldScanF7WitherBoss(
            enabled: Boolean,
            inDungeon: Boolean,
            floorSeven: Boolean,
            masterMode: Boolean,
            bossPhaseObserved: Boolean,
        ): Boolean = enabled && inDungeon && floorSeven && bossPhaseObserved

        /** Early entity-type signal is accepted only after boss entry has been observed. */
        @JvmStatic
        fun isEarlyF7WitherBoss(
            floorSeven: Boolean,
            masterMode: Boolean,
            bossPhaseObserved: Boolean,
            witherEntity: Boolean,
        ): Boolean = floorSeven && bossPhaseObserved && witherEntity

        @JvmStatic
        fun isF7WitherBoss(
            floorSeven: Boolean,
            masterMode: Boolean,
            witherBossEntity: Boolean,
            name: String?,
        ): Boolean = floorSeven && witherBossEntity && isF7WitherBossName(name)

        /**
         * Mimic highlighting is safe only when a licensed room record identifies the exact chest
         * coordinate and Hypixel currently marks that block trapped during pre-blood Clear.
         */
        @JvmStatic
        fun shouldHighlightMimic(
            knownChest: ChestLocation?,
            observedChest: ChestLocation?,
            trappedChest: Boolean,
            duringClear: Boolean,
        ): Boolean = knownChest != null
            && !knownChest.roomId().all { Character.isWhitespace(it) }
            && knownChest == observedChest
            && trappedChest
            && duringClear

        /** Stable fallback palette when the module is first installed. */
        @JvmStatic
        fun defaultClassColor(dungeonClass: DungeonClass?): Int =
            dungeonClass?.let { SpiritLeapRoster.classColorArgb(it.displayName()) } ?: 0xFFFFFFFF.toInt()

        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val WHITESPACE = Regex("\\s+")

        private fun normalize(name: String?): String {
            val stripped = ChatText.stripForMatch(name ?: "")
            val lowered = stripped.lowercase(Locale.ROOT)
            return lowered.replace(NON_ALNUM, " ")
                .trimJava()
                .replace(WHITESPACE, " ")
        }
    }

    /** Value snapshot retaining the Java record constructor and accessor behavior. */
    class ChestLocation(
        roomId: String?,
        val x: Int,
        val y: Int,
        val z: Int,
    ) {
        private val normalizedRoomId: String = roomId?.trimJava() ?: ""

        /** Matches the record accessor while retaining Java String.trim normalization. */
        fun roomId(): String = normalizedRoomId

        /** Record-style accessors remain callable from existing Java sources. */
        fun x(): Int = x
        fun y(): Int = y
        fun z(): Int = z

        /** Returns a new value while leaving this instance unchanged. */
        fun copy(
            roomId: String? = normalizedRoomId,
            x: Int = this.x,
            y: Int = this.y,
            z: Int = this.z,
        ): ChestLocation = ChestLocation(roomId, x, y, z)

        override fun equals(other: Any?): Boolean =
            this === other || (other is ChestLocation
                && roomId() == other.roomId()
                && x == other.x
                && y == other.y
                && z == other.z)

        /** Uses the same field order and 31-folding as the former Java record. */
        override fun hashCode(): Int {
            var result = 0
            result = 31 * result + roomId().hashCode()
            result = 31 * result + x
            result = 31 * result + y
            result = 31 * result + z
            return result
        }

        /** Keeps the former record's readable representation. */
        override fun toString(): String = "ChestLocation[roomId=${roomId()}, x=$x, y=$y, z=$z]"
    }
}

/** Mirrors java.lang.String.trim(), which removes only UTF-16 chars at or below U+0020. */
private fun String.trimJava(): String {
    var start = 0
    var end = length
    while (start < end && this[start] <= '\u0020') start++
    while (start < end && this[end - 1] <= '\u0020') end--
    return substring(start, end)
}
