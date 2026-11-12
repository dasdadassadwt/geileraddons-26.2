package geiler.addons.client.macro

import geiler.addons.client.module.ModuleKeybind
import java.util.UUID

/** One independently-triggered Scratch-style stack inside a macro. */
class MacroScript {
    enum class Trigger {
        KEY_PRESS,
        WORLD_REGION,
        ON_CALL,
        /** Fires from a received chat line; the pattern below is its whole configuration. */
        CHAT,
        /** Fires once when Garden pest-spawn chat is parsed. */
        PEST_SPAWNED,
        /** Fires once when the Pests widget is READY and the configured custom delay has elapsed. */
        PEST_COOLDOWN_READY,
    }

    private val idValue: String
    private var triggerValue: Trigger
    private var keybindValue = ModuleKeybind.NONE
    private val stepsValue = ArrayList<MacroStep>()
    private var worldRegionValue = MacroWorldRegion()
    /** Chat trigger configuration. Blank means inert, so a new stack cannot fire before configuration. */
    private var chatPatternValue = ""
    private var chatContainsValue = true
    private var chatCooldownMillisValue = 500
    private var canvasXValue = 0.0f
    private var canvasYValue = 0.0f

    constructor(trigger: Trigger?) : this(UUID.randomUUID().toString(), trigger)

    constructor(id: String?, trigger: Trigger?) {
        idValue = if (MacroJavaStrings.isBlank(id)) UUID.randomUUID().toString() else id!!
        triggerValue = trigger ?: Trigger.KEY_PRESS
    }

    fun id(): String = idValue
    fun trigger(): Trigger = triggerValue
    fun setTrigger(value: Trigger?) {
        triggerValue = value ?: Trigger.KEY_PRESS
    }

    fun keybind(): ModuleKeybind = keybindValue
    fun setKeybind(value: ModuleKeybind?) {
        keybindValue = value ?: ModuleKeybind.NONE
    }

    fun chatPattern(): String = chatPatternValue
    fun setChatPattern(value: String?) {
        chatPatternValue = value?.substring(0, minOf(MacroChatTrigger.MAX_PATTERN_LENGTH, value.length)) ?: ""
    }

    /** Whether the pattern may match anywhere in the line (default) or has to be the whole line. */
    fun chatContains(): Boolean = chatContainsValue
    fun setChatContains(value: Boolean) {
        chatContainsValue = value
    }

    fun chatCooldownMillis(): Int = chatCooldownMillisValue
    fun setChatCooldownMillis(value: Int) {
        chatCooldownMillisValue = value.coerceIn(0, 60_000)
    }

    fun steps(): MutableList<MacroStep> = stepsValue
    fun worldRegion(): MacroWorldRegion = worldRegionValue
    fun setWorldRegion(value: MacroWorldRegion?) {
        worldRegionValue = value ?: MacroWorldRegion()
    }

    fun canvasX(): Float = canvasXValue
    fun canvasY(): Float = canvasYValue
    fun setCanvasPosition(x: Float, y: Float) {
        canvasXValue = if (x.isFinite()) x.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
        canvasYValue = if (y.isFinite()) y.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
    }
}
