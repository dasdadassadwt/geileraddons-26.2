package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import java.util.EnumSet
import java.util.Locale
import java.util.UUID

/** One player-authored step in a floor and phase-specific guide. */
class DungeonGuideNode {
    enum class Shape { BOX, BEACON, RING, TEXT_ONLY }
    enum class Trigger { MANUAL, ENTER_RADIUS, AFTER_SECONDS, CHAT_EVENT }
    enum class Condition { MANUAL, ENTER_RADIUS, AFTER_SECONDS, CHAT_EVENT, DETECTED_PHASE }
    enum class ConditionRule { ALL, ANY }
    enum class ChatMatch { EXACT, CONTAINS }

    @JvmField var id: String = UUID.randomUUID().toString()
    @JvmField var floor: String = "F1"
    @JvmField var phase: String = "ENTRY"
    @JvmField var routeName: String = "Default"
    @JvmField var order: Int = 0
    @JvmField var label: String = "Guide step"
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
    @JvmField var z: Int = 0
    @JvmField var size: Float = 1.0f

    /** Null means an older route file has no per-node ring appearance yet. */
    @JvmField var ringCount: Int? = null
    @JvmField var ringHeight: Float? = null
    @JvmField var ringRadius: Float? = null
    @JvmField var ringSpeed: Float? = null
    @JvmField var ringWidth: Float? = null
    @JvmField var ringFill: Boolean? = null

    /** Sentinel lets Gson migration preserve the former size-as-radius behavior for old files. */
    @JvmField var triggerRadius: Float = -1.0f
    @JvmField var rotation: Float = 0.0f
    @JvmField var color: Int = 0xFFFFD24A.toInt()
    @JvmField var fillColor: Int = 0x33FFD24A
    @JvmField var labelColor: Int = 0xFFFFD24A.toInt()
    @JvmField var labelScale: Float = 1.0f

    /** Zero means to use the module-wide render distance. */
    @JvmField var visibilityDistance: Int = 0
    @JvmField var shape: Shape? = Shape.BOX
    @JvmField var trigger: Trigger? = Trigger.MANUAL

    /** Null is reserved for pre-condition-format files so sanitize can migrate their legacy trigger. */
    @JvmField var conditions: MutableSet<Condition>? = null
    @JvmField var conditionRule: ConditionRule? = ConditionRule.ALL
    @JvmField var chatMatch: ChatMatch? = ChatMatch.EXACT
    @JvmField var skipAhead: Boolean = false
    @JvmField var triggerSeconds: Float = 0.0f
    @JvmField var eventText: String = ""
    @JvmField var macroId: Int = -1

    constructor()

    constructor(floor: DungeonFloor?, phase: String?, order: Int, x: Int, y: Int, z: Int) : this() {
        this.floor = floor?.displayName() ?: "F1"
        this.phase = clean(phase, 32)
        this.order = maxOf(0, order)
        this.triggerRadius = 2.0f
        this.x = x
        this.y = y
        this.z = z
        this.shape = Shape.BOX
        this.conditions = EnumSet.of(Condition.MANUAL)
    }

    fun sanitize() {
        id = clean(id, 64)
        if (ChatStringSemantics.isBlank(id)) id = UUID.randomUUID().toString()
        floor = DungeonFloor.parse(floor)?.displayName() ?: "F1"
        phase = clean(phase, 32).uppercase(Locale.ROOT)
        if (ChatStringSemantics.isBlank(phase)) phase = "ENTRY"
        routeName = clean(routeName, 48)
        if (ChatStringSemantics.isBlank(routeName)) routeName = "Default"
        label = clean(label, 64)
        if (ChatStringSemantics.isBlank(label)) label = "Guide step"
        size = if (size.isFinite()) size.coerceIn(0.25f, 16.0f) else 1.0f
        ringCount = ringCount?.coerceIn(1, 8)
        ringHeight = clampOptional(ringHeight, 0.25f, 8.0f)
        ringRadius = clampOptional(ringRadius, 0.25f, 8.0f)
        ringSpeed = clampOptional(ringSpeed, 0.05f, 2.0f)
        ringWidth = clampOptional(ringWidth, 0.5f, 5.0f)
        triggerRadius = if (triggerRadius.isFinite() && triggerRadius > 0) {
            triggerRadius.coerceIn(0.5f, 64.0f)
        } else {
            size.coerceIn(0.5f, 64.0f)
        }
        rotation = if (rotation.isFinite()) rotation % 360.0f else 0.0f
        triggerSeconds = if (triggerSeconds.isFinite()) triggerSeconds.coerceIn(0.0f, 3600.0f) else 0.0f
        labelScale = if (labelScale.isFinite()) labelScale.coerceIn(0.5f, 3.0f) else 1.0f
        visibilityDistance = visibilityDistance.coerceIn(0, 256)
        eventText = clean(eventText, 96)
        if (shape == null) shape = Shape.BEACON
        if (trigger == null) trigger = Trigger.MANUAL
        if (chatMatch == null) chatMatch = ChatMatch.EXACT
        if (conditions == null || conditions == EnumSet.of(Condition.MANUAL) && trigger != Trigger.MANUAL) {
            conditions = EnumSet.of(
                when (trigger) {
                    Trigger.MANUAL -> Condition.MANUAL
                    Trigger.ENTER_RADIUS -> Condition.ENTER_RADIUS
                    Trigger.AFTER_SECONDS -> Condition.AFTER_SECONDS
                    Trigger.CHAT_EVENT -> Condition.CHAT_EVENT
                    null -> Condition.MANUAL
                },
            )
        }
        conditions?.removeIf { it == null }
        if (conditions.isNullOrEmpty()) conditions = EnumSet.of(Condition.MANUAL)
        if (conditionRule == null) conditionRule = ConditionRule.ALL
        updateLegacyTrigger()
        macroId = maxOf(-1, macroId)
        order = maxOf(0, order)
    }

    fun hasCondition(condition: Condition?): Boolean = conditions?.contains(condition) == true

    fun ringCountValue(): Int = ringCount ?: DEFAULT_RING_COUNT
    fun ringHeightValue(): Float = ringHeight ?: DEFAULT_RING_HEIGHT
    fun ringRadiusValue(): Float = ringRadius ?: DEFAULT_RING_RADIUS
    fun ringSpeedValue(): Float = ringSpeed ?: DEFAULT_RING_SPEED
    fun ringWidthValue(): Float = ringWidth ?: DEFAULT_RING_WIDTH
    fun ringFillEnabled(): Boolean = ringFill == true

    /** Give a newly selected ring the current defaults without changing its siblings. */
    fun useDefaultRingAppearanceIfUnset() {
        if (ringCount == null) ringCount = DEFAULT_RING_COUNT
        if (ringHeight == null) ringHeight = DEFAULT_RING_HEIGHT
        if (ringRadius == null) ringRadius = DEFAULT_RING_RADIUS
        if (ringSpeed == null) ringSpeed = DEFAULT_RING_SPEED
        if (ringWidth == null) ringWidth = DEFAULT_RING_WIDTH
        if (ringFill == null) ringFill = false
    }

    /** Fills only absent legacy fields; explicit per-node values always win. */
    fun migrateMissingRingAppearance(
        legacyCount: Int,
        legacyHeight: Float,
        legacyRadius: Float,
        legacySpeed: Float,
        legacyWidth: Float,
    ): Boolean {
        if (shape != Shape.RING) return false
        var changed = false
        if (ringCount == null) { ringCount = legacyCount.coerceIn(1, 8); changed = true }
        if (ringHeight == null) { ringHeight = clampFinite(legacyHeight, 0.25f, 8.0f, DEFAULT_RING_HEIGHT); changed = true }
        if (ringRadius == null) { ringRadius = clampFinite(legacyRadius, 0.25f, 8.0f, DEFAULT_RING_RADIUS); changed = true }
        if (ringSpeed == null) { ringSpeed = clampFinite(legacySpeed, 0.05f, 2.0f, DEFAULT_RING_SPEED); changed = true }
        if (ringWidth == null) { ringWidth = clampFinite(legacyWidth, 0.5f, 5.0f, DEFAULT_RING_WIDTH); changed = true }
        if (ringFill == null) { ringFill = false; changed = true }
        return changed
    }

    fun updateLegacyTrigger() {
        val current = conditions
        if (current.isNullOrEmpty()) {
            trigger = Trigger.MANUAL
            return
        }
        trigger = when {
            Condition.CHAT_EVENT in current -> Trigger.CHAT_EVENT
            Condition.AFTER_SECONDS in current -> Trigger.AFTER_SECONDS
            Condition.ENTER_RADIUS in current -> Trigger.ENTER_RADIUS
            else -> Trigger.MANUAL
        }
    }

    companion object {
        const val DEFAULT_RING_COUNT = 3
        const val DEFAULT_RING_HEIGHT = 2.5f
        const val DEFAULT_RING_RADIUS = 1.2f
        const val DEFAULT_RING_SPEED = 0.35f
        const val DEFAULT_RING_WIDTH = 1.5f

        private fun clean(value: String?, limit: Int): String {
            if (value == null) return ""
            val cleaned = ChatStringSemantics.trim(value.replace(Regex("[\\p{Cntrl}]"), ""))
            return if (cleaned.length > limit) cleaned.substring(0, limit) else cleaned
        }

        private fun clampOptional(value: Float?, min: Float, max: Float): Float? =
            if (value == null || !value.isFinite()) null else value.coerceIn(min, max)

        private fun clampFinite(value: Float, min: Float, max: Float, fallback: Float): Float =
            if (value.isFinite()) value.coerceIn(min, max) else fallback
    }
}
