package geiler.addons.client.dungeon.odin

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/** Kotlin state owner for source-resolved, room-scoped Three Weirdos chest markers. */
class DungeonWeirdosMarkerCache {
    private val wrongMarkers = LinkedHashMap<Position, DungeonWeirdosMarkerCache.Marker>()
    private var correctMarker: DungeonWeirdosMarkerCache.Marker? = null
    private var roomIdentity: String? = null
    private var levelIdentity: Any? = null

    fun beginRoom(roomIdentity: String?, levelIdentity: Any?) {
        if (roomIdentity.isNullOrBlank() || levelIdentity == null) {
            clear()
            return
        }
        if (roomIdentity != this.roomIdentity || levelIdentity !== this.levelIdentity) {
            wrongMarkers.clear()
            correctMarker = null
            this.roomIdentity = roomIdentity
            this.levelIdentity = levelIdentity
        }
    }

    fun put(
        roomIdentity: String?,
        levelIdentity: Any?,
        speaker: String?,
        x: Int,
        y: Int,
        z: Int,
        correctChest: Boolean,
    ) {
        beginRoom(roomIdentity, levelIdentity)
        if (this.roomIdentity == null || speaker.isNullOrBlank()) return
        val marker = DungeonWeirdosMarkerCache.Marker(speaker.trim { it <= ' ' }, x, y, z, correctChest)
        if (correctChest) correctMarker = marker else wrongMarkers.putIfAbsent(Position(x, y, z), marker)
    }

    fun snapshot(roomIdentity: String?, levelIdentity: Any?): List<DungeonWeirdosMarkerCache.Marker> {
        if (roomIdentity == null || levelIdentity == null || roomIdentity != this.roomIdentity
            || levelIdentity !== this.levelIdentity
        ) return emptyList()
        val snapshot = ArrayList(wrongMarkers.values)
        correctMarker?.let(snapshot::add)
        return Collections.unmodifiableList(snapshot)
    }

    fun clear() {
        wrongMarkers.clear()
        correctMarker = null
        roomIdentity = null
        levelIdentity = null
    }

    private data class Position(val x: Int, val y: Int, val z: Int)

    class Marker(speaker: String?, x: Int, y: Int, z: Int, correctChest: Boolean) {
        private val speakerValue = speaker ?: ""
        private val xValue = x
        private val yValue = y
        private val zValue = z
        private val correctChestValue = correctChest

        fun speaker(): String = speakerValue
        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue
        fun correctChest(): Boolean = correctChestValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is Marker && speakerValue == other.speaker() && xValue == other.x() && yValue == other.y() &&
            zValue == other.z() && correctChestValue == other.correctChest()

        override fun hashCode(): Int =
            odinValueHash(speakerValue, xValue, yValue, zValue, correctChestValue)

        override fun toString(): String = odinValueText(
            "Marker",
            "speaker" to speakerValue,
            "x" to xValue,
            "y" to yValue,
            "z" to zValue,
            "correctChest" to correctChestValue,
        )
    }
}
