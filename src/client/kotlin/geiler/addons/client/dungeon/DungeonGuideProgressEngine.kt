package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonGuideProgress.RouteSnapshot
import geiler.addons.client.dungeon.DungeonGuideProgress.RouteStep
import geiler.addons.client.tree.ChatText
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.Locale
import kotlin.jvm.JvmStatic

/** Pure ordered-group state machine used by Dungeon Guide. */
class DungeonGuideProgressEngine {
    private var floorValue: DungeonFloor? = null
    private var phaseValue = "ENTRY"
    private var routeNameValue = "Default"
    private var indexValue = 0
    private var stepStartedNanos = 0L
    private var cachedSnapshot: RouteSnapshot? = null
    private var cachedGroup: List<DungeonGuideNode> = emptyList()
    private var routeDataCached = false
    private var cachedGuideRevision = Long.MIN_VALUE
    private var cachedFloor: DungeonFloor? = null
    private var cachedPhase: String? = null
    private var cachedRouteName: String? = null
    private var cachedSteps: List<RouteStep> = emptyList()

    fun setFloor(floor: DungeonFloor?, nowNanos: Long) {
        if (floorValue === floor) return
        floorValue = floor
        phaseValue = "ENTRY"
        indexValue = 0
        stepStartedNanos = nowNanos
    }

    fun setPhase(phase: String?, nowNanos: Long): Boolean = setPhase(phase, nowNanos, null)

    /** Updates the detected phase and silently skips steps from phases that have already passed. */
    fun setPhase(phase: String?, nowNanos: Long, route: RouteSnapshot?): Boolean {
        if (phase == null || !PHASE_PATTERN.matches(phase)) return false
        val activeFloor = floorValue
        val oldPhaseOrder = activeFloor?.let {
            DungeonGuideStore.phaseOrder(it.displayName(), phaseValue)
        } ?: Int.MAX_VALUE
        val nextPhaseOrder = activeFloor?.let {
            DungeonGuideStore.phaseOrder(it.displayName(), phase)
        } ?: Int.MAX_VALUE
        phaseValue = phase
        if (nextPhaseOrder <= oldPhaseOrder || route == null || route !== cachedSnapshot ||
            route.stepIndex() != indexValue || route.floor() !== floorValue
        ) return true

        var nextIndex = indexValue
        while (nextIndex < route.steps().size &&
            DungeonGuideStore.phaseOrder(
                activeFloor!!.displayName(),
                route.steps()[nextIndex].phase(),
            ) < nextPhaseOrder
        ) {
            nextIndex++
        }
        if (nextIndex > indexValue) {
            indexValue = nextIndex
            stepStartedNanos = nowNanos
        }
        return true
    }

    fun setRoute(routeName: String?, nowNanos: Long): Boolean {
        if (routeName == null || ChatStringSemantics.isBlank(routeName) || routeNameValue == routeName) return false
        routeNameValue = routeName
        indexValue = 0
        stepStartedNanos = nowNanos
        return true
    }

    fun phase(): String = phaseValue
    fun floor(): DungeonFloor? = floorValue
    fun index(): Int = indexValue
    fun elapsedNanos(nowNanos: Long): Long = maxOf(0L, nowNanos - stepStartedNanos)

    fun group(nodes: List<DungeonGuideNode>?): List<DungeonGuideNode> {
        val activeFloor = floorValue ?: return emptyList()
        if (nodes == null) return emptyList()
        val ordered = nodes.filter { node ->
            activeFloor.displayName() == node.floor && routeNameValue == node.routeName
        }.toMutableList()
        ordered.sortWith { left, right ->
            var comparison = Integer.compare(
                DungeonGuideStore.phaseOrder(left.floor, left.phase),
                DungeonGuideStore.phaseOrder(right.floor, right.phase),
            )
            if (comparison == 0) comparison = left.phase.compareTo(right.phase)
            if (comparison == 0) comparison = Integer.compare(left.order, right.order)
            comparison
        }
        return Collections.unmodifiableList(ordered)
    }

    /** Reuses the ordered route and immutable step values until an input to rendering/progress changes. */
    fun snapshot(nodes: List<DungeonGuideNode>?, guideRevision: Long): RouteSnapshot =
        snapshot(nodes, guideRevision, System.nanoTime())

    fun snapshot(nodes: List<DungeonGuideNode>?, guideRevision: Long, nowNanos: Long): RouteSnapshot {
        val guideChanged = routeDataCached && cachedGuideRevision != guideRevision
        val rebuildRouteData = !routeDataCached || cachedGuideRevision != guideRevision ||
            cachedFloor !== floorValue || cachedPhase != phaseValue || cachedRouteName != routeNameValue
        val previousSnapshot = cachedSnapshot
        if (!rebuildRouteData && previousSnapshot != null && previousSnapshot.stepIndex() == indexValue) {
            return previousSnapshot
        }

        var rebuildReason = rebuildReason(guideRevision, rebuildRouteData)
        val startNanos = System.nanoTime()
        if (rebuildRouteData) {
            val nextGroup = group(nodes)
            val nextSteps = nextGroup.map(::toRouteStep)
            if (guideChanged) {
                val activeId = activeStepId()
                val oldActiveIndex = uniqueIndex(cachedSteps, activeId)
                val nextActiveIndex = uniqueIndex(nextSteps, activeId)
                if (oldActiveIndex == indexValue && nextActiveIndex >= 0) {
                    // Keep the same waypoint active across removal, insertion, or reordering elsewhere.
                    indexValue = nextActiveIndex
                } else {
                    // The active waypoint was removed, ambiguous, or the route had no active waypoint.
                    // Restart from the beginning and restart its timer instead of silently activating
                    // whatever shifted into the old numeric slot (or treating an empty route as complete).
                    indexValue = 0
                    stepStartedNanos = nowNanos
                    rebuildReason = "guide-data-reset"
                }
            }
            cachedGroup = nextGroup
            cachedSteps = Collections.unmodifiableList(ArrayList(nextSteps))
            cachedGuideRevision = guideRevision
            cachedFloor = floorValue
            cachedPhase = phaseValue
            cachedRouteName = routeNameValue
            routeDataCached = true
        }
        val buildNanos = maxOf(0L, System.nanoTime() - startNanos)
        val skipAheadIndices = Collections.unmodifiableList(
            cachedSteps.indices.filter { cachedSteps[it].skipAhead() },
        )
        cachedSnapshot = RouteSnapshot.create(
            floorValue,
            phaseValue,
            routeNameValue,
            indexValue,
            guideRevision,
            rebuildReason,
            buildNanos,
            cachedSteps,
            skipAheadIndices,
        )
        return cachedSnapshot!!
    }

    private fun toRouteStep(node: DungeonGuideNode): RouteStep = RouteStep(
        node.id,
        node.phase,
        node.label,
        node.x,
        node.y,
        node.z,
        node.visibilityDistance,
        node.size,
        node.rotation,
        node.color,
        node.fillColor,
        node.labelColor,
        node.labelScale,
        node.shape,
        node.ringCountValue(),
        node.ringHeightValue(),
        node.ringRadiusValue(),
        node.ringSpeedValue(),
        node.ringWidthValue(),
        node.ringFillEnabled(),
        Collections.unmodifiableSet(HashSet(node.conditions ?: emptySet())),
        node.conditionRule,
        node.triggerRadius,
        node.triggerSeconds,
        node.eventText ?: "",
        node.macroId,
        node.skipAhead,
        node.chatMatch ?: DungeonGuideNode.ChatMatch.EXACT,
    )

    private fun activeStepId(): String? =
        if (indexValue >= 0 && indexValue < cachedSteps.size) cachedSteps[indexValue].id() else null

    private fun uniqueIndex(steps: List<RouteStep>, id: String?): Int {
        if (id == null || ChatStringSemantics.isBlank(id)) return -1
        var found = -1
        for (i in steps.indices) {
            if (id != steps[i].id()) continue
            if (found >= 0) return -1
            found = i
        }
        return found
    }

    /** Ordered source nodes are retained for the existing non-render API; renderers use the deep immutable steps. */
    fun cachedGroup(): List<DungeonGuideNode> = cachedGroup

    private fun rebuildReason(guideRevision: Long, rebuildRouteData: Boolean): String {
        if (!routeDataCached) return "initial"
        if (cachedGuideRevision != guideRevision) return "guide-data"
        if (cachedFloor !== floorValue) return "floor"
        if (cachedRouteName != routeNameValue) return "route"
        if (cachedPhase != phaseValue) return "phase"
        if (!rebuildRouteData && cachedSnapshot != null && cachedSnapshot!!.stepIndex() != indexValue) return "step"
        return "refresh"
    }

    fun remaining(nodes: List<DungeonGuideNode>?): Int = maxOf(0, group(nodes).size - indexValue)

    fun next(nodes: List<DungeonGuideNode>?, nowNanos: Long) {
        advance(nodes, nowNanos)
    }

    /** Advances once and returns the step whose configured trigger has just fired. */
    fun advance(nodes: List<DungeonGuideNode>?, nowNanos: Long): DungeonGuideNode? {
        val ordered = group(nodes)
        if (indexValue < ordered.size) {
            val fired = ordered[indexValue]
            indexValue++
            stepStartedNanos = nowNanos
            return fired
        }
        return null
    }

    /** Advances the published route without filtering or sorting the source node list again. */
    fun advance(snapshot: RouteSnapshot?, nowNanos: Long): RouteStep? {
        if (snapshot == null || snapshot !== cachedSnapshot || snapshot.stepIndex() != indexValue) return null
        val fired = snapshot.current() ?: return null
        indexValue++
        stepStartedNanos = nowNanos
        return fired
    }

    /** Makes a later route step active without treating any bypassed step as triggered. */
    fun advanceTo(snapshot: RouteSnapshot?, targetIndex: Int, nowNanos: Long): Boolean {
        if (snapshot == null || snapshot !== cachedSnapshot || snapshot.stepIndex() != indexValue ||
            targetIndex <= indexValue || targetIndex >= snapshot.nodeCount()
        ) return false
        indexValue = targetIndex
        stepStartedNanos = nowNanos
        return true
    }

    /** The user's Next Step bind intentionally overrides any automatic trigger. */
    fun advanceManually(nodes: List<DungeonGuideNode>?, nowNanos: Long): DungeonGuideNode? =
        advance(nodes, nowNanos)

    fun advanceManually(snapshot: RouteSnapshot?, nowNanos: Long): RouteStep? = advance(snapshot, nowNanos)

    fun previous(nowNanos: Long) {
        val previous = maxOf(0, indexValue - 1)
        if (previous != indexValue) {
            indexValue = previous
            stepStartedNanos = nowNanos
        }
    }

    /**
     * A phase transition requires exactly one configured exact phrase for the current floor.
     * Duplicate phrases are ambiguous and do not move the route.
     */
    fun observeConfiguredEvent(message: String?, nodes: List<DungeonGuideNode>?, nowNanos: Long): Boolean {
        if (!hasUniqueConfiguredEventMatch(message, nodes)) return false
        observeConfiguredTrigger(message, nodes, nowNanos)
        return true
    }

    fun observeConfiguredTrigger(
        message: String?,
        nodes: List<DungeonGuideNode>?,
        nowNanos: Long,
    ): DungeonGuideNode? {
        if (message == null || ChatStringSemantics.isBlank(message)) return null
        val ordered = group(nodes)
        if (indexValue >= ordered.size) return null
        val active = ordered[indexValue]
        return if (triggerReady(active, elapsedNanos(nowNanos), Double.POSITIVE_INFINITY, message, phaseValue)) {
            advance(nodes, nowNanos)
        } else {
            null
        }
    }

    private fun hasUniqueConfiguredEventMatch(message: String?, nodes: List<DungeonGuideNode>?): Boolean {
        val activeFloor = floorValue ?: return false
        if (message == null || nodes == null) return false
        val candidate = normalizeChat(message)
        var match: DungeonGuideNode? = null
        for (node in nodes) {
            val eventText = node.eventText
            if (activeFloor.displayName() != node.floor || eventText == null ||
                ChatStringSemantics.isBlank(eventText) ||
                !chatMatches(candidate, eventText, node.chatMatch)
            ) continue
            val previousMatch = match
            if (previousMatch != null && previousMatch.id != node.id) return false
            match = node
        }
        return match != null
    }

    companion object {
        @JvmStatic
        fun furthestSkipAheadIndexInRange(snapshot: RouteSnapshot?, x: Double, y: Double, z: Double): Int {
            if (snapshot == null) return -1
            var targetIndex = -1
            val candidates = snapshot.skipAheadIndices()
            for (candidateIndex in candidates) {
                if (candidateIndex <= snapshot.stepIndex() || candidateIndex >= snapshot.nodeCount() ||
                    candidateIndex <= targetIndex
                ) continue
                val candidate = snapshot.steps()[candidateIndex]
                val dx = x - (candidate.x() + 0.5)
                val dy = y - (candidate.y() + 0.5)
                val dz = z - (candidate.z() + 0.5)
                val radius = candidate.triggerRadius()
                if (dx * dx + dy * dy + dz * dz <= radius * radius) targetIndex = candidateIndex
            }
            return targetIndex
        }

        @JvmStatic
        fun triggerReady(node: DungeonGuideNode?, elapsedNanos: Long, distanceSquared: Double): Boolean =
            triggerReady(node, elapsedNanos, distanceSquared, "", node?.phase ?: "")

        @JvmStatic
        fun triggerReady(
            node: DungeonGuideNode?,
            elapsedNanos: Long,
            distanceSquared: Double,
            exactChatMessage: String?,
            detectedPhase: String?,
        ): Boolean = node != null && triggerReady(
            node.conditions,
            node.conditionRule,
            node.phase,
            node.eventText,
            node.chatMatch,
            node.triggerRadius,
            node.triggerSeconds,
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
        ): Boolean = node != null && triggerReady(
            node.conditions(),
            node.conditionRule(),
            node.phase(),
            node.eventText(),
            node.chatMatch(),
            node.triggerRadius(),
            node.triggerSeconds(),
            elapsedNanos,
            distanceSquared,
            exactChatMessage,
            detectedPhase,
        )

        private fun triggerReady(
            conditions: Set<DungeonGuideNode.Condition>?,
            conditionRule: DungeonGuideNode.ConditionRule?,
            phase: String?,
            eventText: String?,
            chatMatch: DungeonGuideNode.ChatMatch?,
            triggerRadius: Float,
            triggerSeconds: Float,
            elapsedNanos: Long,
            distanceSquared: Double,
            exactChatMessage: String?,
            detectedPhase: String?,
        ): Boolean {
            if (conditions.isNullOrEmpty()) return false
            val chat = normalizeChat(exactChatMessage)
            val configuredEvent = eventText ?: ""
            val hasAutomaticCondition = conditions.any { it != DungeonGuideNode.Condition.MANUAL }
            if (!hasAutomaticCondition) return false
            var any = false
            var all = true
            for (condition in conditions) {
                if (condition == DungeonGuideNode.Condition.MANUAL) continue
                val passed = when (condition) {
                    DungeonGuideNode.Condition.MANUAL -> false
                    DungeonGuideNode.Condition.ENTER_RADIUS -> distanceSquared <= triggerRadius * triggerRadius
                    DungeonGuideNode.Condition.AFTER_SECONDS ->
                        elapsedNanos >= (triggerSeconds * 1_000_000_000L).toLong()
                    DungeonGuideNode.Condition.CHAT_EVENT ->
                        !ChatStringSemantics.isBlank(configuredEvent) && chatMatches(chat, configuredEvent, chatMatch)
                    DungeonGuideNode.Condition.DETECTED_PHASE -> phase!!.equals(detectedPhase)
                }
                any = any || passed
                all = all && passed
            }
            return if (conditionRule == DungeonGuideNode.ConditionRule.ANY) any else all
        }

        private fun chatMatches(
            normalizedChat: String,
            configuredEvent: String,
            chatMatch: DungeonGuideNode.ChatMatch?,
        ): Boolean {
            val expected = normalizeChat(configuredEvent)
            if (expected.isEmpty()) return false
            return if (chatMatch == DungeonGuideNode.ChatMatch.CONTAINS) {
                normalizedChat.contains(expected)
            } else {
                normalizedChat == expected
            }
        }

        private fun normalizeChat(value: String?): String =
            ChatText.stripForMatch(value ?: "").lowercase(Locale.ROOT)

        private val PHASE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}
