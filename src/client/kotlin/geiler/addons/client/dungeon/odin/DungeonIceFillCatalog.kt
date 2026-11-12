package geiler.addons.client.dungeon.odin

/** Java-callable boundary for the bounded Ice Fill catalog loader. */
class DungeonIceFillCatalog private constructor() {
    companion object {
        private val bundledCatalog: CatalogResult = DungeonIceFillCatalogCore.loadBundled()

        @JvmStatic
        fun bundled(): CatalogResult = bundledCatalog

        @JvmStatic
        fun parse(json: String?): CatalogResult = DungeonIceFillCatalogCore.parse(json)
    }

    class Point(x: Int, y: Int, z: Int) {
        private val xValue = x
        private val yValue = y
        private val zValue = z

        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is Point && xValue == other.x() && yValue == other.y() && zValue == other.z()

        override fun hashCode(): Int = odinValueHash(xValue, yValue, zValue)

        override fun toString(): String = odinValueText("Point", "x" to xValue, "y" to yValue, "z" to zValue)
    }

    class CatalogResult(
        available: Boolean,
        identifiers: List<List<List<Point>>>?,
        easy: List<List<List<Point>>>?,
        hard: List<List<List<Point>>>?,
        reason: String?,
    ) {
        private val availableValue = available
        private val identifiersValue: List<List<List<Point>>> =
            if (identifiers == null) java.util.List.of() else java.util.List.copyOf(identifiers)
        private val easyValue: List<List<List<Point>>> =
            if (easy == null) java.util.List.of() else java.util.List.copyOf(easy)
        private val hardValue: List<List<List<Point>>> =
            if (hard == null) java.util.List.of() else java.util.List.copyOf(hard)
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun identifiers(): List<List<List<Point>>> = identifiersValue
        fun easy(): List<List<List<Point>>> = easyValue
        fun hard(): List<List<List<Point>>> = hardValue
        fun reason(): String = reasonValue

        fun route(optimized: Boolean, floor: Int, pattern: Int): List<Point> {
            val source = if (optimized) hardValue else easyValue
            if (!availableValue || floor < 0 || floor >= source.size || pattern < 0 || pattern >= source[floor].size)
                return java.util.List.of()
            return source[floor][pattern]
        }

        override fun equals(other: Any?): Boolean = this === other ||
            other is CatalogResult && availableValue == other.available() && identifiersValue == other.identifiers() &&
            easyValue == other.easy() && hardValue == other.hard() && reasonValue == other.reason()

        override fun hashCode(): Int = odinValueHash(availableValue, identifiersValue, easyValue, hardValue, reasonValue)

        override fun toString(): String = odinValueText(
            "CatalogResult",
            "available" to availableValue,
            "identifiers" to identifiersValue,
            "easy" to easyValue,
            "hard" to hardValue,
            "reason" to reasonValue,
        )
    }
}
