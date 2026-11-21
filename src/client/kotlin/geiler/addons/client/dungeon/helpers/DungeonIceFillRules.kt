package geiler.addons.client.dungeon.helpers

/** Java-callable boundary for the pure Ice Fill matching rules. */
class DungeonIceFillRules private constructor() {
    enum class UnknownReason {
        INCOMPLETE_SCAN,
        CATALOG_UNAVAILABLE,
        INVALID_OBSERVATION,
        NO_PATTERN_MATCH,
        AMBIGUOUS_PATTERN_ORIENTATION,
        INVALID_CATALOG,
    }

    class WorldPosition(x: Int, y: Int, z: Int) {
        private val xValue = x
        private val yValue = y
        private val zValue = z

        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is WorldPosition && xValue == other.x() && yValue == other.y() && zValue == other.z()

        override fun hashCode(): Int = odinValueHash(xValue, yValue, zValue)

        override fun toString(): String = odinValueText("WorldPosition", "x" to xValue, "y" to yValue, "z" to zValue)
    }

    class ObservedBlock(position: WorldPosition?, isAir: Boolean?) {
        private val positionValue = position
        private val isAirValue = isAir

        fun position(): WorldPosition? = positionValue
        fun isAir(): Boolean? = isAirValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is ObservedBlock && positionValue == other.position() && isAirValue == other.isAir()

        override fun hashCode(): Int = odinValueHash(positionValue, isAirValue)

        override fun toString(): String = odinValueText("ObservedBlock", "position" to positionValue, "isAir" to isAirValue)
    }

    class Detection(
        floor: Int,
        pattern: Int,
        rotation: Int,
        floorY: Int,
        route: List<WorldPosition>?,
        unknownReason: UnknownReason?,
    ) {
        private val floorValue = floor
        private val patternValue = pattern
        private val rotationValue = rotation
        private val floorYValue = floorY
        private val routeValue: List<WorldPosition> = if (route == null) java.util.List.of() else java.util.List.copyOf(route)
        private val unknownReasonValue = unknownReason

        fun floor(): Int = floorValue
        fun pattern(): Int = patternValue
        fun rotation(): Int = rotationValue
        fun floorY(): Int = floorYValue
        fun route(): List<WorldPosition> = routeValue
        fun unknownReason(): UnknownReason? = unknownReasonValue

        fun known(): Boolean = unknownReasonValue == null && floorValue >= 0 && patternValue >= 0 &&
            rotationValue >= 0 && routeValue.size >= 2

        override fun equals(other: Any?): Boolean = this === other ||
            other is Detection && floorValue == other.floor() && patternValue == other.pattern() &&
            rotationValue == other.rotation() && floorYValue == other.floorY() && routeValue == other.route() &&
            unknownReasonValue == other.unknownReason()

        override fun hashCode(): Int =
            odinValueHash(floorValue, patternValue, rotationValue, floorYValue, routeValue, unknownReasonValue)

        override fun toString(): String = odinValueText(
            "Detection",
            "floor" to floorValue,
            "pattern" to patternValue,
            "rotation" to rotationValue,
            "floorY" to floorYValue,
            "route" to routeValue,
            "unknownReason" to unknownReasonValue,
        )
    }

    companion object {
        @JvmStatic
        fun probePositions(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonIceFillCatalog.CatalogResult?,
            floor: Int,
        ): List<WorldPosition> = DungeonIceFillRulesCore.probePositions(roomBaseX, roomBaseZ, catalog, floor)

        @JvmStatic
        fun detect(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonIceFillCatalog.CatalogResult?,
            floor: Int,
            observations: List<ObservedBlock?>?,
            completeLoadedScan: Boolean,
            optimized: Boolean,
        ): Detection = DungeonIceFillRulesCore.detect(
            roomBaseX, roomBaseZ, catalog, floor, observations, completeLoadedScan, optimized,
        )

        @JvmStatic
        fun selectRoute(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonIceFillCatalog.CatalogResult?,
            detected: Detection?,
            optimized: Boolean,
        ): Detection = DungeonIceFillRulesCore.selectRoute(roomBaseX, roomBaseZ, catalog, detected, optimized)
    }
}
