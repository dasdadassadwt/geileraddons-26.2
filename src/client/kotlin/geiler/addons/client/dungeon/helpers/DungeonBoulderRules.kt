package geiler.addons.client.dungeon.helpers

/** Java-callable boundary for the pure Boulder matching rules. */
class DungeonBoulderRules private constructor() {
    enum class UnknownReason {
        INCOMPLETE_SCAN,
        CATALOG_UNAVAILABLE,
        INVALID_OBSERVATION,
        NO_CATALOG_MATCH,
        AMBIGUOUS_ORIENTATION,
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

    class Target(renderPosition: WorldPosition?, clickPosition: WorldPosition?) {
        private val renderPositionValue = renderPosition
        private val clickPositionValue = clickPosition

        fun renderPosition(): WorldPosition? = renderPositionValue
        fun clickPosition(): WorldPosition? = clickPositionValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is Target && renderPositionValue == other.renderPosition() && clickPositionValue == other.clickPosition()

        override fun hashCode(): Int = odinValueHash(renderPositionValue, clickPositionValue)

        override fun toString(): String =
            odinValueText("Target", "renderPosition" to renderPositionValue, "clickPosition" to clickPositionValue)
    }

    class Detection(rotation: Int, signature: String?, targets: List<Target>?, unknownReason: UnknownReason?) {
        private val rotationValue = rotation
        private val signatureValue = signature
        private val targetsValue: List<Target> =
            if (targets == null) java.util.List.of() else java.util.List.copyOf(targets)
        private val unknownReasonValue = unknownReason

        fun rotation(): Int = rotationValue
        fun signature(): String? = signatureValue
        fun targets(): List<Target> = targetsValue
        fun unknownReason(): UnknownReason? = unknownReasonValue

        fun renderTargets(): List<WorldPosition> = DungeonBoulderRulesCore.renderTargets(this)
        fun known(): Boolean = DungeonBoulderRulesCore.known(this)

        override fun equals(other: Any?): Boolean = this === other ||
            other is Detection && rotationValue == other.rotation() && signatureValue == other.signature() &&
            targetsValue == other.targets() && unknownReasonValue == other.unknownReason()

        override fun hashCode(): Int = odinValueHash(rotationValue, signatureValue, targetsValue, unknownReasonValue)

        override fun toString(): String = odinValueText(
            "Detection",
            "rotation" to rotationValue,
            "signature" to signatureValue,
            "targets" to targetsValue,
            "unknownReason" to unknownReasonValue,
        )
    }

    companion object {
        @JvmStatic
        fun shouldRenderTarget(selection: String?, activePuzzleName: String?): Boolean =
            DungeonBoulderRulesCore.shouldRenderTarget(selection, activePuzzleName)

        @JvmStatic
        fun shouldAdvanceInteraction(mainHand: Boolean, dungeonClearPhase: Boolean): Boolean =
            DungeonBoulderRulesCore.shouldAdvanceInteraction(mainHand, dungeonClearPhase)

        @JvmStatic
        fun probePositions(roomBaseX: Int, roomBaseZ: Int): List<WorldPosition> =
            DungeonBoulderRulesCore.probePositions(roomBaseX, roomBaseZ)

        @JvmStatic
        fun probePositions(roomBaseX: Int, roomBaseZ: Int, rotation: Int): List<WorldPosition> =
            DungeonBoulderRulesCore.probePositions(roomBaseX, roomBaseZ, rotation)

        @JvmStatic
        fun detectAtRotation(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonBoulderCatalog.CatalogResult?,
            observations: List<ObservedBlock?>?,
            completeLoadedScan: Boolean,
            rotation: Int,
        ): Detection = DungeonBoulderRulesCore.detectAtRotation(
            roomBaseX, roomBaseZ, catalog, observations, completeLoadedScan, rotation,
        )

        @JvmStatic
        fun detect(
            roomBaseX: Int,
            roomBaseZ: Int,
            catalog: DungeonBoulderCatalog.CatalogResult?,
            observations: List<ObservedBlock?>?,
            completeLoadedScan: Boolean,
        ): Detection = DungeonBoulderRulesCore.detect(roomBaseX, roomBaseZ, catalog, observations, completeLoadedScan)
    }
}
