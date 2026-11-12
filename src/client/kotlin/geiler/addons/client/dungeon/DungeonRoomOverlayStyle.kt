package geiler.addons.client.dungeon

import java.util.Comparator
import java.util.Locale
import kotlin.jvm.JvmRecord
import kotlin.math.roundToInt

/** Java-callable room and door styling API, implemented in Kotlin. */
class DungeonRoomOverlayStyle private constructor() {
    companion object {
        @JvmStatic fun roomText(type: DungeonMapGeometry.RoomType?, mode: String?): String =
            StyleCore.roomText(type, mode)
        @JvmStatic fun roomColor(type: DungeonMapGeometry.RoomType?, colors: RoomColors?): Int =
            StyleCore.roomColor(type, colors)
        @JvmStatic fun roomFillColor(cell: DungeonRoomOverlayGeometry.Cell?, colors: RoomColors?,
            unopenedRoomColor: Int, darkenFactor: Double): Int =
            StyleCore.roomFillColor(cell, colors, unopenedRoomColor, darkenFactor)
        @JvmStatic fun roomTextColor(visibility: DungeonRoomOverlayGeometry.RoomVisibility?, checkmark: String?,
            colorByCheckmark: Boolean, textColor: Int, unopenedTextColor: Int): Int =
            StyleCore.roomTextColor(visibility, checkmark, colorByCheckmark, textColor, unopenedTextColor)
        @JvmStatic fun predictionsVisible(disabled: Boolean): Boolean = StyleCore.predictionsVisible(disabled)
        @JvmStatic fun predictionTargetAllowed(cell: DungeonRoomOverlayGeometry.Cell?): Boolean =
            StyleCore.predictionTargetAllowed(cell)
        @JvmStatic fun predictionsVisible(revealUnopenedRooms: Boolean, disabled: Boolean): Boolean =
            StyleCore.predictionsVisible(revealUnopenedRooms, disabled)
        @JvmStatic fun roomDecoration(showCheckmarks: Boolean, checkmark: String?, label: String?): RoomDecoration =
            StyleCore.roomDecoration(showCheckmarks, checkmark, label)
        @JvmStatic fun dynamicDoorRoomType(first: DungeonMapGeometry.RoomType?,
            second: DungeonMapGeometry.RoomType?): DungeonMapGeometry.RoomType =
            StyleCore.dynamicDoorRoomType(first, second)
        @JvmStatic fun dynamicDoorColor(first: DungeonMapGeometry.RoomType?,
            second: DungeonMapGeometry.RoomType?, colors: RoomColors?, fallbackColor: Int): Int =
            StyleCore.dynamicDoorColor(first, second, colors, fallbackColor)
        @JvmStatic fun alignmentAnchor(footprint: List<DungeonRoomOverlayGeometry.Cell>?,
            alignment: String?): Anchor = StyleCore.alignmentAnchor(footprint, alignment)
        @JvmStatic fun iconStyle(value: String?): IconStyle = StyleCore.iconStyle(value)
        @JvmStatic fun textShadow(value: String?): TextShadow = StyleCore.textShadow(value)
        @JvmStatic fun doorColor(kind: DungeonRoomOverlayGeometry.DoorKind?, colors: DoorColors?): Int =
            StyleCore.doorColor(kind, colors)
        @JvmStatic fun darken(argb: Int, factor: Double): Int = StyleCore.darken(argb, factor)
    }

    @JvmRecord
    data class RoomColors(
        val room: Int, val puzzle: Int, val trap: Int, val miniboss: Int, val entrance: Int,
        val fairy: Int, val blood: Int, val unknown: Int, val rare: Int, val champion: Int,
    ) {
        constructor(
            room: Int, puzzle: Int, trap: Int, miniboss: Int, entrance: Int,
            fairy: Int, blood: Int, unknown: Int,
        ) : this(room, puzzle, trap, miniboss, entrance, fairy, blood, unknown, room, miniboss)

        override fun toString(): String = recordToString(
            "RoomColors", "room" to room, "puzzle" to puzzle, "trap" to trap, "miniboss" to miniboss,
            "entrance" to entrance, "fairy" to fairy, "blood" to blood, "unknown" to unknown,
            "rare" to rare, "champion" to champion,
        )
    }

    enum class IconStyle { ILLEGAL_MAP, NEU, VANILLA_NEW, VANILLA_OLD }
    enum class TextShadow { NONE, DROP, OUTLINE }

    class Anchor(x: Double, z: Double) {
        private val xValue = if (x.isFinite()) x else 0.0
        private val zValue = if (z.isFinite()) z else 0.0
        fun x(): Double = xValue
        fun z(): Double = zValue
        override fun equals(other: Any?): Boolean = other is Anchor &&
            recordEquals(arrayOf<Any?>(xValue, zValue), arrayOf<Any?>(other.xValue, other.zValue))
        override fun hashCode(): Int = recordHash(xValue, zValue)
        override fun toString(): String = recordToString("Anchor", "x" to xValue, "z" to zValue)
    }

    @JvmRecord
    data class RoomDecoration(val checkmark: String?, val label: String?) {
        override fun toString(): String =
            recordToString("RoomDecoration", "checkmark" to checkmark, "label" to label)
    }

    @JvmRecord
    data class DoorColors(
        val normal: Int, val glass: Int, val start: Int, val wither: Int, val blood: Int,
        val fairy: Int, val unknown: Int,
    ) {
        constructor(normal: Int, glass: Int, start: Int, wither: Int, blood: Int) : this(
            normal, glass, start, wither, blood,
            DungeonRoomOverlayStyle.darken(normal, 0.65), DungeonRoomOverlayStyle.darken(normal, 0.45),
        )

        override fun toString(): String = recordToString(
            "DoorColors", "normal" to normal, "glass" to glass, "start" to start, "wither" to wither,
            "blood" to blood, "fairy" to fairy, "unknown" to unknown,
        )
    }
}

private fun recordEquals(first: Array<out Any?>, second: Array<out Any?>): Boolean = first.contentEquals(second)
private fun recordHash(vararg values: Any?): Int =
    values.fold(0) { hash, value -> 31 * hash + (value?.hashCode() ?: 0) }
private fun recordToString(type: String, vararg components: Pair<String, Any?>): String =
    components.joinToString(prefix = "$type[", postfix = "]") { (name, value) -> "$name=$value" }

private object StyleCore {
    fun roomText(type: DungeonMapGeometry.RoomType?, mode: String?): String {
        val roomType = type ?: DungeonMapGeometry.RoomType.UNKNOWN
        if (mode == "Off") return ""
        if (mode == "Room Type") return roomType.label().uppercase(Locale.ROOT)
        return when (roomType) {
            DungeonMapGeometry.RoomType.ROOM -> "R"
            DungeonMapGeometry.RoomType.RARE -> "R*"
            DungeonMapGeometry.RoomType.PUZZLE -> "P"
            DungeonMapGeometry.RoomType.TRAP -> "T"
            DungeonMapGeometry.RoomType.MINIBOSS -> "M"
            DungeonMapGeometry.RoomType.CHAMPION -> "C"
            DungeonMapGeometry.RoomType.ENTRANCE -> "E"
            DungeonMapGeometry.RoomType.FAIRY -> "F"
            DungeonMapGeometry.RoomType.BLOOD -> "B"
            DungeonMapGeometry.RoomType.UNKNOWN -> "?"
        }
    }

    fun roomColor(type: DungeonMapGeometry.RoomType?, colors: DungeonRoomOverlayStyle.RoomColors?): Int {
        if (type == null || colors == null) return 0xFF292F39.toInt()
        return when (type) {
            DungeonMapGeometry.RoomType.ROOM -> colors.room
            DungeonMapGeometry.RoomType.RARE -> colors.rare
            DungeonMapGeometry.RoomType.PUZZLE -> colors.puzzle
            DungeonMapGeometry.RoomType.TRAP -> colors.trap
            DungeonMapGeometry.RoomType.MINIBOSS -> colors.miniboss
            DungeonMapGeometry.RoomType.CHAMPION -> colors.champion
            DungeonMapGeometry.RoomType.ENTRANCE -> colors.entrance
            DungeonMapGeometry.RoomType.FAIRY -> colors.fairy
            DungeonMapGeometry.RoomType.BLOOD -> colors.blood
            DungeonMapGeometry.RoomType.UNKNOWN -> colors.unknown
        }
    }

    fun roomFillColor(
        cell: DungeonRoomOverlayGeometry.Cell?,
        colors: DungeonRoomOverlayStyle.RoomColors?,
        unopenedRoomColor: Int,
        darkenFactor: Double,
    ): Int {
        if (cell == null) return roomColor(DungeonMapGeometry.RoomType.UNKNOWN, colors)
        if (cell.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) {
            return if (cell.type() == DungeonMapGeometry.RoomType.UNKNOWN) unopenedRoomColor
            else darken(roomColor(cell.type(), colors), darkenFactor)
        }
        return roomColor(cell.type(), colors)
    }

    fun roomTextColor(
        visibility: DungeonRoomOverlayGeometry.RoomVisibility?,
        checkmark: String?,
        colorByCheckmark: Boolean,
        textColor: Int,
        unopenedTextColor: Int,
    ): Int {
        if (visibility == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED) return unopenedTextColor
        if (!colorByCheckmark || checkmark == null) return textColor
        return when (checkmark) {
            "GREEN" -> 0xFF55FF55.toInt()
            "WHITE" -> 0xFFFFFFFF.toInt()
            "RED" -> 0xFFFF5555.toInt()
            else -> textColor
        }
    }

    fun predictionsVisible(disabled: Boolean): Boolean = !disabled

    fun predictionTargetAllowed(cell: DungeonRoomOverlayGeometry.Cell?): Boolean {
        val roomId = cell?.roomId()
        return cell != null && cell.type() == DungeonMapGeometry.RoomType.UNKNOWN &&
            roomId != null && !roomId.startsWith("partial-core:") &&
            cell.visibility() == DungeonRoomOverlayGeometry.RoomVisibility.VERIFIED_UNOPENED
    }

    fun predictionsVisible(revealUnopenedRooms: Boolean, disabled: Boolean): Boolean = predictionsVisible(disabled)

    fun roomDecoration(
        showCheckmarks: Boolean,
        checkmark: String?,
        label: String?,
    ): DungeonRoomOverlayStyle.RoomDecoration =
        DungeonRoomOverlayStyle.RoomDecoration(if (showCheckmarks && checkmark != null) checkmark else "", label ?: "")

    fun dynamicDoorRoomType(
        first: DungeonMapGeometry.RoomType?,
        second: DungeonMapGeometry.RoomType?,
    ): DungeonMapGeometry.RoomType {
        val a = first ?: DungeonMapGeometry.RoomType.UNKNOWN
        val b = second ?: DungeonMapGeometry.RoomType.UNKNOWN
        return if (dynamicDoorRoomPriority(a) <= dynamicDoorRoomPriority(b)) a else b
    }

    fun dynamicDoorColor(
        first: DungeonMapGeometry.RoomType?,
        second: DungeonMapGeometry.RoomType?,
        colors: DungeonRoomOverlayStyle.RoomColors?,
        fallbackColor: Int,
    ): Int {
        val selected = dynamicDoorRoomType(first, second)
        return if (selected == DungeonMapGeometry.RoomType.UNKNOWN) fallbackColor else roomColor(selected, colors)
    }

    private fun dynamicDoorRoomPriority(type: DungeonMapGeometry.RoomType): Int = when (type) {
        DungeonMapGeometry.RoomType.ENTRANCE -> 0
        DungeonMapGeometry.RoomType.FAIRY -> 1
        DungeonMapGeometry.RoomType.BLOOD -> 2
        DungeonMapGeometry.RoomType.PUZZLE -> 3
        DungeonMapGeometry.RoomType.TRAP -> 4
        DungeonMapGeometry.RoomType.CHAMPION -> 5
        DungeonMapGeometry.RoomType.MINIBOSS -> 6
        DungeonMapGeometry.RoomType.RARE -> 7
        DungeonMapGeometry.RoomType.ROOM -> 8
        DungeonMapGeometry.RoomType.UNKNOWN -> 100
    }

    /** Coordinates are room-cell indices; the renderer applies pixel-center offsets later. */
    fun alignmentAnchor(
        footprint: List<DungeonRoomOverlayGeometry.Cell>?,
        alignment: String?,
    ): DungeonRoomOverlayStyle.Anchor {
        if (footprint.isNullOrEmpty()) return DungeonRoomOverlayStyle.Anchor(0.0, 0.0)
        val cells = java.util.List.copyOf(footprint)
        val mode = alignment ?: "Center"
        val primary: Comparator<DungeonRoomOverlayGeometry.Cell>? = when (mode) {
            "Top Left" -> compareBy { it.x() + it.z() }
            "Top Right" -> compareBy { -it.x() + it.z() }
            "Bottom Left" -> compareBy { it.x() - it.z() }
            "Bottom Right" -> compareBy { -it.x() - it.z() }
            else -> null
        }
        if (primary != null) {
            val order = primary.thenComparingInt { it.z() }.thenComparingInt { it.x() }
            val selected = cells.minWithOrNull(order) ?: cells.first()
            return DungeonRoomOverlayStyle.Anchor(selected.x().toDouble(), selected.z().toDouble())
        }
        if (cells.size == 3 && isLShape(cells)) {
            val sorted = cells.sortedWith(compareBy<DungeonRoomOverlayGeometry.Cell> { it.z() * 11 + it.x() })
            if (mode == "CenterL") {
                val excluded = if (sorted[0].z() == sorted[1].z()) 2 else 0
                return meanWithout(sorted, excluded)
            }
            val index = when {
                sorted[0].x() > sorted[1].x() -> 2
                sorted[0].x() == sorted[2].x() -> 0
                else -> 1
            }
            val selected = sorted[index]
            return DungeonRoomOverlayStyle.Anchor(selected.x().toDouble(), selected.z().toDouble())
        }
        return DungeonRoomOverlayStyle.Anchor(
            cells.map { it.x() }.average(),
            cells.map { it.z() }.average(),
        )
    }

    private fun meanWithout(
        cells: List<DungeonRoomOverlayGeometry.Cell>,
        excluded: Int,
    ): DungeonRoomOverlayStyle.Anchor {
        var x = 0.0
        var z = 0.0
        var count = 0
        for (index in cells.indices) {
            if (index == excluded) continue
            x += cells[index].x()
            z += cells[index].z()
            count++
        }
        return if (count == 0) DungeonRoomOverlayStyle.Anchor(0.0, 0.0)
        else DungeonRoomOverlayStyle.Anchor(x / count, z / count)
    }

    private fun isLShape(cells: List<DungeonRoomOverlayGeometry.Cell>): Boolean {
        val minX = cells.minOfOrNull { it.x() } ?: 0
        val maxX = cells.maxOfOrNull { it.x() } ?: 0
        val minZ = cells.minOfOrNull { it.z() } ?: 0
        val maxZ = cells.maxOfOrNull { it.z() } ?: 0
        return maxX - minX == 1 && maxZ - minZ == 1
    }

    fun iconStyle(value: String?): DungeonRoomOverlayStyle.IconStyle = when (value) {
        "NEU" -> DungeonRoomOverlayStyle.IconStyle.NEU
        "Vanilla New" -> DungeonRoomOverlayStyle.IconStyle.VANILLA_NEW
        "Vanilla Old" -> DungeonRoomOverlayStyle.IconStyle.VANILLA_OLD
        else -> DungeonRoomOverlayStyle.IconStyle.ILLEGAL_MAP
    }

    fun textShadow(value: String?): DungeonRoomOverlayStyle.TextShadow = when (value) {
        "Drop" -> DungeonRoomOverlayStyle.TextShadow.DROP
        "Outline" -> DungeonRoomOverlayStyle.TextShadow.OUTLINE
        else -> DungeonRoomOverlayStyle.TextShadow.NONE
    }

    fun doorColor(
        kind: DungeonRoomOverlayGeometry.DoorKind?,
        colors: DungeonRoomOverlayStyle.DoorColors?,
    ): Int {
        if (colors == null) return 0xFFFFE066.toInt()
        return when (kind ?: DungeonRoomOverlayGeometry.DoorKind.NORMAL) {
            DungeonRoomOverlayGeometry.DoorKind.NORMAL -> colors.normal
            DungeonRoomOverlayGeometry.DoorKind.GLASS -> colors.glass
            DungeonRoomOverlayGeometry.DoorKind.START -> colors.start
            DungeonRoomOverlayGeometry.DoorKind.WITHER -> colors.wither
            DungeonRoomOverlayGeometry.DoorKind.BLOOD -> colors.blood
            DungeonRoomOverlayGeometry.DoorKind.FAIRY -> colors.fairy
            DungeonRoomOverlayGeometry.DoorKind.UNKNOWN -> colors.unknown
        }
    }

    fun darken(argb: Int, factor: Double): Int {
        val boundedFactor = if (factor.isFinite()) factor.coerceIn(0.0, 1.0) else 0.7
        val alpha = (argb ushr 24) and 0xFF
        val red = scale((argb ushr 16) and 0xFF, boundedFactor)
        val green = scale((argb ushr 8) and 0xFF, boundedFactor)
        val blue = scale(argb and 0xFF, boundedFactor)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun scale(channel: Int, factor: Double): Int = (channel * factor).roundToInt()

}


