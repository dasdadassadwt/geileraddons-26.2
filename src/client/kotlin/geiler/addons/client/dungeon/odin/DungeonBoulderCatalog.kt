package geiler.addons.client.dungeon.odin

/** Java-callable boundary for the bounded Boulder catalog loader. */
class DungeonBoulderCatalog private constructor() {
    companion object {
        private val bundledCatalog: CatalogResult = DungeonBoulderCatalogCore.loadBundled()

        @JvmStatic
        fun bundled(): CatalogResult = bundledCatalog

        @JvmStatic
        fun parse(json: String?): CatalogResult = DungeonBoulderCatalogCore.parse(json)
    }

    class Target(renderX: Int, renderZ: Int, clickX: Int, clickZ: Int) {
        private val renderXValue = renderX
        private val renderZValue = renderZ
        private val clickXValue = clickX
        private val clickZValue = clickZ

        fun renderX(): Int = renderXValue
        fun renderZ(): Int = renderZValue
        fun clickX(): Int = clickXValue
        fun clickZ(): Int = clickZValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is Target && renderXValue == other.renderX() && renderZValue == other.renderZ() &&
            clickXValue == other.clickX() && clickZValue == other.clickZ()

        override fun hashCode(): Int = odinValueHash(renderXValue, renderZValue, clickXValue, clickZValue)

        override fun toString(): String = odinValueText(
            "Target",
            "renderX" to renderXValue,
            "renderZ" to renderZValue,
            "clickX" to clickXValue,
            "clickZ" to clickZValue,
        )
    }

    class CatalogResult(available: Boolean, solutions: Map<String, List<Target>>?, reason: String?) {
        private val availableValue = available
        private val solutionsValue: Map<String, List<Target>> =
            if (solutions == null) java.util.Map.of() else java.util.Map.copyOf(solutions)
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun solutions(): Map<String, List<Target>> = solutionsValue
        fun reason(): String = reasonValue

        fun targets(signature: String?): List<Target> =
            if (availableValue && signature != null) solutionsValue.getOrDefault(signature, java.util.List.of())
            else java.util.List.of()

        override fun equals(other: Any?): Boolean = this === other ||
            other is CatalogResult && availableValue == other.available() && solutionsValue == other.solutions() &&
            reasonValue == other.reason()

        override fun hashCode(): Int = odinValueHash(availableValue, solutionsValue, reasonValue)

        override fun toString(): String = odinValueText(
            "CatalogResult",
            "available" to availableValue,
            "solutions" to solutionsValue,
            "reason" to reasonValue,
        )
    }
}

internal fun odinValueHash(vararg components: Any?): Int {
    var result = 0
    for (component in components) result = 31 * result + (component?.hashCode() ?: 0)
    return result
}

internal fun odinValueText(typeName: String, vararg components: Pair<String, Any?>): String =
    components.joinToString(prefix = "$typeName[", postfix = "]", separator = ", ") { (name, value) ->
        "$name=${value ?: "null"}"
    }
