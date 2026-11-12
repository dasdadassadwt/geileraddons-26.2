package geiler.addons.client.dungeon

import java.util.ArrayList
import java.util.Collections
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Java-compatible facade and immutable route views for the Dungeon Guide progress engine. */
@JvmSuppressWildcards
class DungeonGuideProgress {
    private val engine = DungeonGuideProgressEngine()

    fun setFloor(floor: DungeonFloor?, nowNanos: Long) {
        engine.setFloor(floor, nowNanos)
    }

    fun setPhase(phase: String?, nowNanos: Long): Boolean = engine.setPhase(phase, nowNanos)

    fun setPhase(phase: String?, nowNanos: Long, route: RouteSnapshot?): Boolean =
        engine.setPhase(phase, nowNanos, route)

    fun setRoute(routeName: String?, nowNanos: Long): Boolean = engine.setRoute(routeName, nowNanos)

    fun phase(): String = engine.phase()

    fun floor(): DungeonFloor? = engine.floor()

    fun index(): Int = engine.index()

    fun elapsedNanos(nowNanos: Long): Long = engine.elapsedNanos(nowNanos)

    fun group(nodes: List<DungeonGuideNode>?): List<DungeonGuideNode> = engine.group(nodes)

    fun snapshot(nodes: List<DungeonGuideNode>?, guideRevision: Long): RouteSnapshot =
        engine.snapshot(nodes, guideRevision)

    fun snapshot(nodes: List<DungeonGuideNode>?, guideRevision: Long, nowNanos: Long): RouteSnapshot =
        engine.snapshot(nodes, guideRevision, nowNanos)

    fun cachedGroup(): List<DungeonGuideNode> = engine.cachedGroup()

    fun remaining(nodes: List<DungeonGuideNode>?): Int = engine.remaining(nodes)

    fun next(nodes: List<DungeonGuideNode>?, nowNanos: Long) {
        engine.next(nodes, nowNanos)
    }

    fun advance(nodes: List<DungeonGuideNode>?, nowNanos: Long): DungeonGuideNode? =
        engine.advance(nodes, nowNanos)

    fun advance(snapshot: RouteSnapshot?, nowNanos: Long): RouteStep? = engine.advance(snapshot, nowNanos)

    fun advanceTo(snapshot: RouteSnapshot?, targetIndex: Int, nowNanos: Long): Boolean =
        engine.advanceTo(snapshot, targetIndex, nowNanos)

    fun advanceManually(nodes: List<DungeonGuideNode>?, nowNanos: Long): DungeonGuideNode? =
        engine.advanceManually(nodes, nowNanos)

    fun advanceManually(snapshot: RouteSnapshot?, nowNanos: Long): RouteStep? =
        engine.advanceManually(snapshot, nowNanos)

    fun previous(nowNanos: Long) {
        engine.previous(nowNanos)
    }

    fun observeConfiguredEvent(
        message: String?,
        nodes: List<DungeonGuideNode>?,
        nowNanos: Long,
    ): Boolean = engine.observeConfiguredEvent(message, nodes, nowNanos)

    fun observeConfiguredTrigger(
        message: String?,
        nodes: List<DungeonGuideNode>?,
        nowNanos: Long,
    ): DungeonGuideNode? = engine.observeConfiguredTrigger(message, nodes, nowNanos)

    companion object {
        @JvmStatic
        fun furthestSkipAheadIndexInRange(snapshot: RouteSnapshot?, x: Double, y: Double, z: Double): Int =
            DungeonGuideProgressEngine.furthestSkipAheadIndexInRange(snapshot, x, y, z)

        @JvmStatic
        fun triggerReady(node: DungeonGuideNode?, elapsedNanos: Long, distanceSquared: Double): Boolean =
            DungeonGuideProgressEngine.triggerReady(node, elapsedNanos, distanceSquared)

        @JvmStatic
        fun triggerReady(
            node: DungeonGuideNode?,
            elapsedNanos: Long,
            distanceSquared: Double,
            exactChatMessage: String?,
            detectedPhase: String?,
        ): Boolean = DungeonGuideProgressEngine.triggerReady(
            node,
            elapsedNanos,
            distanceSquared,
            exactChatMessage,
            detectedPhase,
        )

        @JvmStatic
        fun triggerReady(
            node: RouteStep?,
            elapsedNanos: Long,
            distanceSquared: Double,
            exactChatMessage: String?,
            detectedPhase: String?,
        ): Boolean = DungeonGuideProgressEngine.triggerReady(
            node,
            elapsedNanos,
            distanceSquared,
            exactChatMessage,
            detectedPhase,
        )
    }

    /** Immutable published view of one ordered route revision. Identity rejects stale views. */
    @JvmSuppressWildcards
    class RouteSnapshot private constructor(
        floor: DungeonFloor?,
        phase: String?,
        routeName: String?,
        stepIndex: Int,
        guideRevision: Long,
        rebuildReason: String?,
        buildNanos: Long,
        steps: List<RouteStep>?,
        skipAheadIndices: List<Int>?,
    ) {
        private val floorValue = floor
        private val phaseValue = phase
        private val routeNameValue = routeName
        private val stepIndexValue = stepIndex
        private val guideRevisionValue = guideRevision
        private val rebuildReasonValue = rebuildReason
        private val buildNanosValue = buildNanos
        private val stepsValue = immutableList(steps)
        private val skipAheadIndicesValue = immutableList(skipAheadIndices)

        fun floor(): DungeonFloor? = floorValue
        fun phase(): String? = phaseValue
        fun routeName(): String? = routeNameValue
        fun stepIndex(): Int = stepIndexValue
        fun guideRevision(): Long = guideRevisionValue
        fun rebuildReason(): String? = rebuildReasonValue
        fun buildNanos(): Long = buildNanosValue
        fun steps(): List<RouteStep> = stepsValue
        fun skipAheadIndices(): List<Int> = skipAheadIndicesValue

        fun current(): RouteStep? =
            if (stepIndexValue < 0 || stepIndexValue >= stepsValue.size) null else stepsValue[stepIndexValue]

        fun nodeCount(): Int = stepsValue.size

        fun remaining(): Int = maxOf(0, stepsValue.size - stepIndexValue)

        fun complete(): Boolean = stepsValue.isNotEmpty() && stepIndexValue >= stepsValue.size

        companion object {
            @JvmStatic
            fun create(
                floor: DungeonFloor?,
                phase: String?,
                routeName: String?,
                stepIndex: Int,
                guideRevision: Long,
                rebuildReason: String?,
                buildNanos: Long,
                steps: List<RouteStep>?,
                skipAheadIndices: List<Int>?,
            ): RouteSnapshot = RouteSnapshot(
                floor,
                phase,
                routeName,
                stepIndex,
                guideRevision,
                rebuildReason,
                buildNanos,
                steps,
                skipAheadIndices,
            )

            private fun <T> immutableList(values: List<T>?): List<T> =
                Collections.unmodifiableList(ArrayList<T>(values))
        }
    }

    /** Record-style value view; a class is needed to normalize and copy compact-constructor inputs. */
    class RouteStep(
        id: String?,
        phase: String?,
        label: String?,
        x: Int,
        y: Int,
        z: Int,
        visibilityDistance: Int,
        size: Float,
        rotation: Float,
        color: Int,
        fillColor: Int,
        labelColor: Int,
        labelScale: Float,
        shape: DungeonGuideNode.Shape?,
        ringCount: Int,
        ringHeight: Float,
        ringRadius: Float,
        ringSpeed: Float,
        ringWidth: Float,
        ringFill: Boolean,
        conditions: Set<DungeonGuideNode.Condition>?,
        conditionRule: DungeonGuideNode.ConditionRule?,
        triggerRadius: Float,
        triggerSeconds: Float,
        eventText: String?,
        macroId: Int,
        skipAhead: Boolean,
        chatMatch: DungeonGuideNode.ChatMatch?,
    ) {
        private val idValue = id
        private val phaseValue = phase
        private val labelValue = label
        private val xValue = x
        private val yValue = y
        private val zValue = z
        private val visibilityDistanceValue = visibilityDistance
        private val sizeValue = size
        private val rotationValue = rotation
        private val colorValue = color
        private val fillColorValue = fillColor
        private val labelColorValue = labelColor
        private val labelScaleValue = labelScale
        private val shapeValue = shape
        private val ringCountValue = ringCount
        private val ringHeightValue = ringHeight
        private val ringRadiusValue = ringRadius
        private val ringSpeedValue = ringSpeed
        private val ringWidthValue = ringWidth
        private val ringFillValue = ringFill
        private val conditionsValue: Set<DungeonGuideNode.Condition> =
            conditions?.let { java.util.Set.copyOf(it) } ?: emptySet()
        private val conditionRuleValue = conditionRule
        private val triggerRadiusValue = triggerRadius
        private val triggerSecondsValue = triggerSeconds
        private val eventTextValue = eventText ?: ""
        private val macroIdValue = macroId
        private val skipAheadValue = skipAhead
        private val chatMatchValue = chatMatch ?: DungeonGuideNode.ChatMatch.EXACT

        fun id(): String? = idValue
        fun phase(): String? = phaseValue
        fun label(): String? = labelValue
        fun x(): Int = xValue
        fun y(): Int = yValue
        fun z(): Int = zValue
        fun visibilityDistance(): Int = visibilityDistanceValue
        fun size(): Float = sizeValue
        fun rotation(): Float = rotationValue
        fun color(): Int = colorValue
        fun fillColor(): Int = fillColorValue
        fun labelColor(): Int = labelColorValue
        fun labelScale(): Float = labelScaleValue
        fun shape(): DungeonGuideNode.Shape? = shapeValue
        fun ringCount(): Int = ringCountValue
        fun ringHeight(): Float = ringHeightValue
        fun ringRadius(): Float = ringRadiusValue
        fun ringSpeed(): Float = ringSpeedValue
        fun ringWidth(): Float = ringWidthValue
        fun ringFill(): Boolean = ringFillValue
        fun conditions(): Set<DungeonGuideNode.Condition> = conditionsValue
        fun conditionRule(): DungeonGuideNode.ConditionRule? = conditionRuleValue
        fun triggerRadius(): Float = triggerRadiusValue
        fun triggerSeconds(): Float = triggerSecondsValue
        fun eventText(): String = eventTextValue
        fun macroId(): Int = macroIdValue
        fun skipAhead(): Boolean = skipAheadValue
        fun chatMatch(): DungeonGuideNode.ChatMatch = chatMatchValue

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RouteStep) return false
            return idValue == other.idValue &&
                phaseValue == other.phaseValue &&
                labelValue == other.labelValue &&
                xValue == other.xValue &&
                yValue == other.yValue &&
                zValue == other.zValue &&
                visibilityDistanceValue == other.visibilityDistanceValue &&
                java.lang.Float.compare(sizeValue, other.sizeValue) == 0 &&
                java.lang.Float.compare(rotationValue, other.rotationValue) == 0 &&
                colorValue == other.colorValue &&
                fillColorValue == other.fillColorValue &&
                labelColorValue == other.labelColorValue &&
                java.lang.Float.compare(labelScaleValue, other.labelScaleValue) == 0 &&
                shapeValue == other.shapeValue &&
                ringCountValue == other.ringCountValue &&
                java.lang.Float.compare(ringHeightValue, other.ringHeightValue) == 0 &&
                java.lang.Float.compare(ringRadiusValue, other.ringRadiusValue) == 0 &&
                java.lang.Float.compare(ringSpeedValue, other.ringSpeedValue) == 0 &&
                java.lang.Float.compare(ringWidthValue, other.ringWidthValue) == 0 &&
                ringFillValue == other.ringFillValue &&
                conditionsValue == other.conditionsValue &&
                conditionRuleValue == other.conditionRuleValue &&
                java.lang.Float.compare(triggerRadiusValue, other.triggerRadiusValue) == 0 &&
                java.lang.Float.compare(triggerSecondsValue, other.triggerSecondsValue) == 0 &&
                eventTextValue == other.eventTextValue &&
                macroIdValue == other.macroIdValue &&
                skipAheadValue == other.skipAheadValue &&
                chatMatchValue == other.chatMatchValue
        }

        override fun hashCode(): Int {
            var result = idValue?.hashCode() ?: 0
            result = 31 * result + (phaseValue?.hashCode() ?: 0)
            result = 31 * result + (labelValue?.hashCode() ?: 0)
            result = 31 * result + xValue
            result = 31 * result + yValue
            result = 31 * result + zValue
            result = 31 * result + visibilityDistanceValue
            result = 31 * result + java.lang.Float.hashCode(sizeValue)
            result = 31 * result + java.lang.Float.hashCode(rotationValue)
            result = 31 * result + colorValue
            result = 31 * result + fillColorValue
            result = 31 * result + labelColorValue
            result = 31 * result + java.lang.Float.hashCode(labelScaleValue)
            result = 31 * result + (shapeValue?.hashCode() ?: 0)
            result = 31 * result + ringCountValue
            result = 31 * result + java.lang.Float.hashCode(ringHeightValue)
            result = 31 * result + java.lang.Float.hashCode(ringRadiusValue)
            result = 31 * result + java.lang.Float.hashCode(ringSpeedValue)
            result = 31 * result + java.lang.Float.hashCode(ringWidthValue)
            result = 31 * result + java.lang.Boolean.hashCode(ringFillValue)
            result = 31 * result + conditionsValue.hashCode()
            result = 31 * result + (conditionRuleValue?.hashCode() ?: 0)
            result = 31 * result + java.lang.Float.hashCode(triggerRadiusValue)
            result = 31 * result + java.lang.Float.hashCode(triggerSecondsValue)
            result = 31 * result + eventTextValue.hashCode()
            result = 31 * result + macroIdValue
            result = 31 * result + java.lang.Boolean.hashCode(skipAheadValue)
            result = 31 * result + chatMatchValue.hashCode()
            return result
        }

        override fun toString(): String =
            "RouteStep[id=$idValue, phase=$phaseValue, label=$labelValue, x=$xValue, y=$yValue, z=$zValue, " +
                "visibilityDistance=$visibilityDistanceValue, size=$sizeValue, rotation=$rotationValue, " +
                "color=$colorValue, fillColor=$fillColorValue, labelColor=$labelColorValue, " +
                "labelScale=$labelScaleValue, shape=$shapeValue, ringCount=$ringCountValue, " +
                "ringHeight=$ringHeightValue, ringRadius=$ringRadiusValue, ringSpeed=$ringSpeedValue, " +
                "ringWidth=$ringWidthValue, ringFill=$ringFillValue, conditions=$conditionsValue, " +
                "conditionRule=$conditionRuleValue, triggerRadius=$triggerRadiusValue, " +
                "triggerSeconds=$triggerSecondsValue, eventText=$eventTextValue, macroId=$macroIdValue, " +
                "skipAhead=$skipAheadValue, chatMatch=$chatMatchValue]"
    }
}
