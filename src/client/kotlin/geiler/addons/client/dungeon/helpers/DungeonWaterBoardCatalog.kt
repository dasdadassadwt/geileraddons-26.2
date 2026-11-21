package geiler.addons.client.dungeon.helpers

import kotlin.jvm.JvmName
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Loads the BSD-3-Clause Water Board schedules bundled with this mod. */
@JvmSuppressWildcards
class DungeonWaterBoardCatalog private constructor() {
    companion object {
        private val BUNDLED = DungeonWaterBoardCatalogCore.loadBundled()

        @JvmStatic
        fun bundled(): CatalogResult = BUNDLED

        @JvmStatic
        fun route(optimized: Boolean, patternId: Int, extendedSlots: String?): List<Click> =
            DungeonWaterBoardCatalogCore.route(BUNDLED, optimized, patternId, extendedSlots)

        /** Package helper retained for focused callers of the former Java facade. */
        @JvmStatic
        @JvmName("parse")
        internal fun parse(json: String?): CatalogResult = DungeonWaterBoardCatalogCore.parse(json)
    }

    @JvmRecord
    data class RouteKey(val optimized: Boolean, val patternId: Int, val extendedSlots: String?) {
        override fun toString(): String =
            "RouteKey[optimized=$optimized, patternId=$patternId, extendedSlots=$extendedSlots]"
    }

    @JvmRecord
    data class Click(val leverId: String?, val seconds: Double) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Click && leverId == other.leverId &&
                java.lang.Double.compare(seconds, other.seconds) == 0)

        override fun hashCode(): Int {
            var result = leverId?.hashCode() ?: 0
            result = 31 * result + java.lang.Double.hashCode(seconds)
            return result
        }

        override fun toString(): String = "Click[leverId=$leverId, seconds=$seconds]"
    }

    class CatalogResult(
        available: Boolean,
        routes: Map<RouteKey, List<Click>>?,
        reason: String?,
    ) {
        private val availableValue = available
        private val routesValue: Map<RouteKey, List<Click>> = java.util.Map.copyOf(routes ?: emptyMap())
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun routes(): Map<RouteKey, List<Click>> = routesValue
        fun reason(): String = reasonValue

        override fun equals(other: Any?): Boolean =
            this === other || (other is CatalogResult && availableValue == other.availableValue &&
                routesValue == other.routesValue && reasonValue == other.reasonValue)

        override fun hashCode(): Int {
            var result = java.lang.Boolean.hashCode(availableValue)
            result = 31 * result + routesValue.hashCode()
            result = 31 * result + reasonValue.hashCode()
            return result
        }

        override fun toString(): String =
            "CatalogResult[available=$availableValue, routes=$routesValue, reason=$reasonValue]"
    }
}
