package geiler.addons.client.dungeon.helpers

import java.util.List as JavaList
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Java-facing catalog values and static entrypoints for the Kotlin endpoint catalog loader. */
class DungeonCreeperBeamsCatalog private constructor() {
    companion object {
        private val BUNDLED: CatalogResult = DungeonCreeperBeamsCatalogLogic.bundled()

        @JvmStatic
        fun bundled(): CatalogResult = BUNDLED

        /** Package-level parser retained as a Java-callable fixture seam. */
        @JvmStatic
        fun parse(json: String?): CatalogResult = DungeonCreeperBeamsCatalogLogic.parse(json)
    }

    @JvmRecord
    data class Point(val x: Int, val y: Int, val z: Int) {
        override fun toString(): String = "Point[x=$x, y=$y, z=$z]"
    }

    @JvmRecord
    data class Pair(val first: Point?, val second: Point?) {
        override fun toString(): String = "Pair[first=$first, second=$second]"
    }

    /** A value class because its former compact record constructor snapshots and normalizes components. */
    class CatalogResult(available: Boolean, pairs: List<Pair?>?, reason: String?) {
        private val availableValue = available
        private val pairsValue: List<Pair> = JavaList.copyOf((pairs ?: JavaList.of()).map { it!! })
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun pairs(): List<Pair> = pairsValue
        fun reason(): String = reasonValue

        override fun equals(other: Any?): Boolean = other is CatalogResult && availableValue == other.availableValue &&
            pairsValue == other.pairsValue && reasonValue == other.reasonValue

        override fun hashCode(): Int {
            var result = availableValue.hashCode()
            result = 31 * result + pairsValue.hashCode()
            result = 31 * result + reasonValue.hashCode()
            return result
        }

        override fun toString(): String = "CatalogResult[available=$availableValue, pairs=$pairsValue, reason=$reasonValue]"
    }
}
