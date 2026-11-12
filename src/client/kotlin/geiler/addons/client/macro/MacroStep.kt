package geiler.addons.client.macro

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.client.location.Island

/** Mutable workflow nodes; configuration persistence is handled explicitly by ModConfig. */
sealed interface MacroStep {
    /** Delay in milliseconds before this node runs. Both values are explicit per-node settings. */
    fun delayMin(): Int
    fun delayMax(): Int
    fun setDelay(min: Int, max: Int)
    /** Raises the maximum when the new minimum exceeds it, instead of silently clamping the minimum. */
    fun setDelayMin(value: Int) = setDelay(value, delayMax())
    fun setDelayMax(value: Int) = setDelay(delayMin(), value)
    fun type(): String

    abstract class Base : MacroStep {
        private var delayMinValue = 0
        private var delayMaxValue = 0
        private var editorXValue = 0.0f
        private var editorYValue = 0.0f

        /** Canvas position used only while the node is stored outside an executable stack. */
        open fun editorX(): Float = editorXValue
        open fun editorY(): Float = editorYValue
        open fun setEditorPosition(x: Float, y: Float) {
            editorXValue = if (x.isFinite()) x.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
            editorYValue = if (y.isFinite()) y.coerceIn(-100_000.0f, 100_000.0f) else 0.0f
        }

        override fun delayMin(): Int = delayMinValue
        override fun delayMax(): Int = delayMaxValue

        override fun setDelay(min: Int, max: Int) {
            if (min < 0 || max < 0) {
                delayMinValue = 0
                delayMaxValue = 0
                return
            }
            delayMinValue = minOf(60_000, min)
            delayMaxValue = minOf(60_000, maxOf(delayMinValue, max))
        }
    }

    class Command(command: String?) : Base() {
        private var commandValue = command ?: ""
        fun command(): String = commandValue
        fun setCommand(value: String?) { commandValue = value ?: "" }
        override fun type(): String = "command"
    }

    class Chat(message: String?) : Base() {
        private var messageValue = message ?: ""
        fun message(): String = messageValue
        fun setMessage(value: String?) { messageValue = value ?: "" }
        override fun type(): String = "chat"
    }

    class Title : Base() {
        private var textValue = "TITLE"
        private var fontValue = "minecraft:default"
        private var scaleValue = 2.0f
        private var textColorValue = -1
        private var showBackgroundValue = false
        private var backgroundColorValue = 0xFF000000.toInt()
        private var backgroundOpacityValue = 160
        private var fadeInMillisValue = 200
        private var holdMillisValue = 2_000
        private var fadeOutMillisValue = 300

        fun text(): String = textValue
        fun font(): String = fontValue
        fun scale(): Float = scaleValue
        fun textColor(): Int = textColorValue
        fun showBackground(): Boolean = showBackgroundValue
        fun backgroundColor(): Int = backgroundColorValue
        fun backgroundOpacity(): Int = backgroundOpacityValue
        fun fadeInMillis(): Int = fadeInMillisValue
        fun holdMillis(): Int = holdMillisValue
        fun fadeOutMillis(): Int = fadeOutMillisValue
        fun setText(value: String?) { textValue = stepClampText(value, 256) }
        fun setFont(value: String?) {
            fontValue = if (MacroJavaStrings.isBlank(value)) "minecraft:default" else value!!.substring(0, minOf(128, value.length))
        }
        fun setScale(value: Float) { scaleValue = if (value.isFinite()) stepClamp(value, 0.5f, 6.0f) else 2.0f }
        fun setTextColor(value: Int) { textColorValue = value or 0xFF000000.toInt() }
        fun setShowBackground(value: Boolean) { showBackgroundValue = value }
        fun setBackgroundColor(value: Int) { backgroundColorValue = value or 0xFF000000.toInt() }
        fun setBackgroundOpacity(value: Int) { backgroundOpacityValue = stepClamp(value, 0, 255) }
        fun setFadeInMillis(value: Int) { fadeInMillisValue = stepClamp(value, 0, 10_000) }
        fun setHoldMillis(value: Int) { holdMillisValue = stepClamp(value, 0, 60_000) }
        fun setFadeOutMillis(value: Int) { fadeOutMillisValue = stepClamp(value, 0, 10_000) }
        override fun type(): String = "title"
    }

    class Sound : Base {
        private var soundIdValue = "minecraft:entity.player.levelup"
        constructor() : super()
        constructor(soundId: String?) : this() { setSoundId(soundId) }
        fun soundId(): String = soundIdValue
        fun setSoundId(value: String?) { soundIdValue = value?.substring(0, minOf(128, value.length)) ?: "" }
        override fun type(): String = "sound"
    }

    class Wait(minMillis: Int, maxMillis: Int) : Base() {
        enum class Mode { DURATION, CONDITION }
        private var minMillisValue = 0
        private var maxMillisValue = 0
        private var modeValue = Mode.DURATION
        private var conditionValue: MacroCondition = MacroCondition.Always(true)

        init { setRange(minMillis, maxMillis) }

        fun minMillis(): Int = minMillisValue
        fun maxMillis(): Int = maxMillisValue
        fun mode(): Mode = modeValue
        fun condition(): MacroCondition = conditionValue
        fun setMode(value: Mode?) { modeValue = value ?: Mode.DURATION }
        fun setCondition(value: MacroCondition?) { conditionValue = value ?: MacroCondition.Always(true) }
        fun setMinMillis(value: Int) = setRange(value, maxMillisValue)
        fun setMaxMillis(value: Int) = setRange(minMillisValue, value)
        fun setRange(min: Int, max: Int) {
            minMillisValue = stepClamp(min, 0, 300_000)
            maxMillisValue = stepClamp(maxOf(min, max), minMillisValue, 300_000)
        }
        override fun type(): String = "wait"
    }

    /** A visual separator that never executes or consumes a delay in the runner. */
    class Comment : Base {
        private var textValue = "Comment"
        constructor() : super()
        constructor(text: String?) : this() { setText(text) }
        fun text(): String = textValue
        fun setText(value: String?) { textValue = stepClampText(value, 256) }
        override fun type(): String = "comment"
    }

    /** Unknown data is retained and only common fields deliberately edited in the UI are overlaid. */
    class Unknown(rawData: JsonObject?, depthLimited: Boolean) : Base() {
        private val rawDataValue = rawData?.deepCopy() ?: JsonObject()
        private val originalTypeValue = typeName(rawDataValue)
        private val depthLimitedValue = depthLimited
        private var rawJsonValue: String? = null
        private var initialDelayMin = 0
        private var initialDelayMax = 0
        private var initialEditorX = 0.0f
        private var initialEditorY = 0.0f

        constructor(rawData: JsonObject?) : this(rawData, false)

        init {
            setDelay(integer(rawDataValue, "delayMin", 0), integer(rawDataValue, "delayMax", 0))
            setEditorPosition(decimal(rawDataValue, "editorX", 0.0f), decimal(rawDataValue, "editorY", 0.0f))
            initialDelayMin = delayMin()
            initialDelayMax = delayMax()
            initialEditorX = editorX()
            initialEditorY = editorY()
        }

        fun originalType(): String = originalTypeValue
        fun depthLimited(): Boolean = depthLimitedValue

        /** Returns a defensive copy with any deliberately edited common fields applied. */
        fun serializedData(): JsonObject {
            val result = rawDataValue.deepCopy()
            if (delayMin() != initialDelayMin) result.addProperty("delayMin", delayMin())
            if (delayMax() != initialDelayMax) result.addProperty("delayMax", delayMax())
            if (java.lang.Float.compare(editorX(), initialEditorX) != 0) result.addProperty("editorX", editorX())
            if (java.lang.Float.compare(editorY(), initialEditorY) != 0) result.addProperty("editorY", editorY())
            return result
        }

        /** Compact read-only preview for the editor's raw-data panel. */
        fun rawJson(): String {
            if (rawJsonValue == null) rawJsonValue = rawDataValue.toString()
            return rawJsonValue!!
        }

        override fun type(): String = originalTypeValue
    }

    /** Explicitly ends this macro run and releases held inputs. */
    class StopRun : Base() {
        override fun type(): String = "stop_run"
    }

    /** Scrolls an open screen or the world hotbar by a bounded number of wheel steps. */
    class Scroll : Base {
        enum class Direction { UP, DOWN }
        private var directionValue = Direction.DOWN
        private var amountValue = 1

        constructor() : super()
        constructor(direction: Direction?, amount: Int) : this() { setDirection(direction); setAmount(amount) }
        fun direction(): Direction = directionValue
        fun amount(): Int = amountValue
        fun setDirection(value: Direction?) { directionValue = value ?: Direction.DOWN }
        fun setAmount(value: Int) { amountValue = stepClamp(value, 1, 32) }
        override fun type(): String = "scroll"
    }

    /** New combined inventory action; legacy slot/item nodes remain readable and executable. */
    class InventoryClick : Base() {
        enum class Target { SLOT, ITEM }
        private var targetValue = Target.SLOT
        private var slotIdValue = 0
        private var nameValue = ""
        private var containsValue = false
        private var scopeValue = "container"
        private var occurrenceValue = 0
        private var buttonValue = 0
        private var shiftValue = false

        fun target(): Target = targetValue
        fun slotId(): Int = slotIdValue
        fun name(): String = nameValue
        fun contains(): Boolean = containsValue
        fun scope(): String = scopeValue
        fun occurrence(): Int = occurrenceValue
        fun button(): Int = buttonValue
        fun shift(): Boolean = shiftValue
        fun setTarget(value: Target?) { targetValue = value ?: Target.SLOT }
        fun setSlotId(value: Int) { slotIdValue = maxOf(0, value) }
        fun setName(value: String?) { nameValue = value ?: "" }
        fun setContains(value: Boolean) { containsValue = value }
        fun setScope(value: String?) { scopeValue = value ?: "container" }
        fun setOccurrence(value: Int) { occurrenceValue = maxOf(0, value) }
        fun setButton(value: Int) { buttonValue = stepClamp(value, 0, 2) }
        fun setShift(value: Boolean) { shiftValue = value }
        override fun type(): String = "inventory_click"
    }

    /** New combined local/global variable action; legacy set/change nodes remain compatible. */
    class UpdateVariable : Base() {
        enum class Operation { SET, ADD, SUBTRACT }
        private var operationValue = Operation.SET
        private var nameValue = "value"
        private var globalVariableIdValue: String? = null
        private var valueValue = MacroValue.literal(MacroValue.Type.TEXT, "")
        private var amountValue = 1.0

        fun operation(): Operation = operationValue
        fun name(): String = nameValue
        fun globalVariableId(): String? = globalVariableIdValue
        fun targetsGlobal(): Boolean = globalVariableIdValue != null
        fun value(): MacroValue = valueValue
        fun amount(): Double = amountValue
        fun setOperation(value: Operation?) { operationValue = value ?: Operation.SET }
        fun setName(value: String?) {
            val clean = if (MacroJavaStrings.isBlank(value)) "" else MacroJavaStrings.strip(value)!!
            nameValue = if (clean.isEmpty()) "value" else clean.substring(0, minOf(32, clean.length))
        }
        fun setGlobalVariableId(value: String?) {
            globalVariableIdValue = if (MacroJavaStrings.isBlank(value)) null else value!!.substring(0, minOf(64, value.length))
        }
        fun setValue(value: MacroValue?) { valueValue = value ?: MacroValue.literal(MacroValue.Type.TEXT, "") }
        fun setAmount(value: Double) { amountValue = if (value.isFinite()) value.coerceIn(-1_000_000.0, 1_000_000.0) else 0.0 }
        override fun type(): String = "update_variable"

        companion object {
            /** A Set step: writes [value] to a local variable, or to the global [globalVariableId] when given. */
            @JvmStatic
            @JvmOverloads
            fun set(name: String?, value: MacroValue?, globalVariableId: String? = null) = UpdateVariable().apply {
                setOperation(Operation.SET); setName(name); setValue(value); setGlobalVariableId(globalVariableId)
            }

            /** An Add step: adds [amount] to a local variable, or to the global [globalVariableId] when given. */
            @JvmStatic
            @JvmOverloads
            fun add(name: String?, amount: Double, globalVariableId: String? = null) = UpdateVariable().apply {
                setOperation(Operation.ADD); setName(name); setAmount(amount); setGlobalVariableId(globalVariableId)
            }
        }
    }

    class Key : Base {
        enum class InputMode { KEYBOARD, MOUSE, HOTBAR }
        private var keyValue: String
        private var inputModeValue = InputMode.KEYBOARD
        private var mouseButtonValue = MouseButton.Button.LEFT
        private var hotbarSlotValue = 1
        private var holdValue: Boolean
        private var holdMinMillisValue = 0
        private var holdMaxMillisValue = 0

        constructor(key: String?, hold: Boolean, holdMillis: Int) : this(key, hold, holdMillis, holdMillis)
        constructor(key: String?, hold: Boolean, holdMinMillis: Int, holdMaxMillis: Int) : super() {
            keyValue = key ?: ""
            holdValue = hold
            setHoldRange(holdMinMillis, holdMaxMillis)
        }

        fun key(): String = keyValue
        fun inputMode(): InputMode = inputModeValue
        fun mouseButton(): MouseButton.Button = mouseButtonValue
        fun hotbarSlot(): Int = hotbarSlotValue
        fun hold(): Boolean = holdValue
        fun holdMinMillis(): Int = holdMinMillisValue
        fun holdMaxMillis(): Int = holdMaxMillisValue
        /** Legacy accessor retained for callers that treated hold time as a fixed duration. */
        fun holdMillis(): Int = holdMinMillisValue
        fun setKey(value: String?) { keyValue = value ?: "" }
        fun setInputMode(value: InputMode?) { inputModeValue = value ?: InputMode.KEYBOARD }
        fun setMouseButton(value: MouseButton.Button?) { mouseButtonValue = value ?: MouseButton.Button.LEFT }
        fun setHotbarSlot(value: Int) { hotbarSlotValue = stepClamp(value, 1, 9) }
        fun setHold(value: Boolean) { holdValue = value }
        fun setHoldMillis(value: Int) { setHoldRange(value, value) }
        fun setHoldRange(min: Int, max: Int) {
            holdMinMillisValue = stepClamp(min, 1, 60_000)
            holdMaxMillisValue = stepClamp(maxOf(holdMinMillisValue, max), holdMinMillisValue, 60_000)
        }
        override fun type(): String = "key"
    }

    class CloseScreen : Base() {
        override fun type(): String = "close_screen"
    }

    class SelectHotbarSlot(slot: Int) : Base() {
        private var slotValue = 0
        init { setSlot(slot) }
        fun slot(): Int = slotValue
        fun setSlot(value: Int) { slotValue = stepClamp(value, 1, 9) }
        override fun type(): String = "select_hotbar_slot"
    }

    class MouseButton(button: Button?, hold: Boolean, holdMillis: Int) : Base() {
        enum class Button { LEFT, RIGHT, MIDDLE, BUTTON_4, BUTTON_5, BUTTON_6, BUTTON_7, BUTTON_8 }
        private var buttonValue = button ?: Button.LEFT
        private var holdValue = hold
        private var holdMillisValue = 0
        init { setHoldMillis(holdMillis) }
        fun button(): Button = buttonValue
        fun hold(): Boolean = holdValue
        fun holdMillis(): Int = holdMillisValue
        fun setButton(value: Button?) { buttonValue = value ?: Button.LEFT }
        fun setHold(value: Boolean) { holdValue = value }
        fun setHoldMillis(value: Int) { holdMillisValue = stepClamp(value, 1, 60_000) }
        override fun type(): String = "mouse_button"
    }

    class BlockPlayerInput(durationMillis: Int) : Base() {
        private var durationMillisValue = 0
        init { setDurationMillis(durationMillis) }
        fun durationMillis(): Int = durationMillisValue
        fun setDurationMillis(value: Int) { durationMillisValue = stepClamp(value, 1, 60_000) }
        override fun type(): String = "block_player_input"
    }

    class StartBlockPlayerInput : Base() {
        override fun type(): String = "start_block_player_input"
    }

    class StopBlockPlayerInput : Base() {
        override fun type(): String = "stop_block_player_input"
    }

    class FunctionCall(functionId: String?) : Base() {
        private var functionIdValue = functionId ?: ""
        private val argumentsValue = ArrayList<MacroValue>()
        fun functionId(): String = functionIdValue
        fun arguments(): MutableList<MacroValue> = argumentsValue
        fun setFunctionId(value: String?) { functionIdValue = value ?: "" }
        override fun type(): String = "function_call"
    }

    class MacroCall(macroId: Int) : Base() {
        private var macroIdValue = -1
        private var conditionValue: MacroCondition? = null
        init { setMacroId(macroId) }
        fun macroId(): Int = macroIdValue
        fun setMacroId(value: Int) { macroIdValue = if (value < 0) -1 else value }
        fun condition(): MacroCondition? = conditionValue
        fun setCondition(value: MacroCondition?) { conditionValue = value }
        override fun type(): String = "macro_call"
    }

    /** A world switch continues only after the location API confirms its configured destination. */
    class WorldSwitch(target: Island?) : Base() {
        private var targetValue = Island.HUB
        init { setTarget(target) }
        fun target(): Island = targetValue
        fun setTarget(value: Island?) { targetValue = if (value != null && value.selectable()) value else Island.HUB }
        override fun type(): String = "world_switch"
    }

    class WaitUntil(condition: MacroCondition?) : Base() {
        private var conditionValue = condition ?: MacroCondition.Always(true)
        fun condition(): MacroCondition = conditionValue
        fun setCondition(value: MacroCondition?) { conditionValue = value ?: MacroCondition.Always(true) }
        override fun type(): String = "wait_until"
    }

    class IfElse(condition: MacroCondition?) : Base() {
        private var conditionValue = condition ?: MacroCondition.Always(true)
        private var elseEnabledValue = false
        private val thenStepsValue = ArrayList<MacroStep>()
        private val elseStepsValue = ArrayList<MacroStep>()
        fun condition(): MacroCondition = conditionValue
        fun elseEnabled(): Boolean = elseEnabledValue
        fun setCondition(value: MacroCondition?) { conditionValue = value ?: MacroCondition.Always(true) }
        fun setElseEnabled(value: Boolean) { elseEnabledValue = value }
        fun thenSteps(): MutableList<MacroStep> = thenStepsValue
        fun elseSteps(): MutableList<MacroStep> = elseStepsValue
        override fun type(): String = "if"
    }

    class Repeat(forever: Boolean, count: Int) : Base() {
        enum class Mode { COUNT, FOREVER, UNTIL }
        private var modeValue = if (forever) Mode.FOREVER else Mode.COUNT
        private var countValue = 1
        private var conditionValue: MacroCondition = MacroCondition.Always(false)
        private val stepsValue = ArrayList<MacroStep>()
        init { setCount(count) }
        fun mode(): Mode = modeValue
        fun forever(): Boolean = modeValue == Mode.FOREVER
        fun count(): Int = countValue
        fun condition(): MacroCondition = conditionValue
        fun steps(): MutableList<MacroStep> = stepsValue
        fun setMode(value: Mode?) { modeValue = value ?: Mode.COUNT }
        fun setForever(value: Boolean) { modeValue = if (value) Mode.FOREVER else Mode.COUNT }
        fun setCount(value: Int) { countValue = stepClamp(value, 1, 1_000_000) }
        fun setCondition(value: MacroCondition?) { conditionValue = value ?: MacroCondition.Always(false) }
        override fun type(): String = "repeat"
    }

    class RepeatUntil(condition: MacroCondition?) : Base() {
        private var conditionValue = condition ?: MacroCondition.Always(true)
        private val stepsValue = ArrayList<MacroStep>()
        fun condition(): MacroCondition = conditionValue
        fun setCondition(value: MacroCondition?) { conditionValue = value ?: MacroCondition.Always(true) }
        fun steps(): MutableList<MacroStep> = stepsValue
        override fun type(): String = "repeat_until"
    }

    /** Multi-case value branch. Every case and the fallback own an independent nested stack. */
    class Switch : Base() {
        private var nameValue = "value"
        private var globalVariableIdValue: String? = null
        private val casesValue = ArrayList<SwitchCase>()
        private val defaultStepsValue = ArrayList<MacroStep>()

        init {
            casesValue.add(SwitchCase("case 1"))
            casesValue.add(SwitchCase("case 2"))
        }

        fun name(): String = nameValue
        fun globalVariableId(): String? = globalVariableIdValue
        fun targetsGlobal(): Boolean = globalVariableIdValue != null
        fun cases(): MutableList<SwitchCase> = casesValue
        fun defaultSteps(): MutableList<MacroStep> = defaultStepsValue
        fun setName(value: String?) {
            val clean = if (MacroJavaStrings.isBlank(value)) "" else MacroJavaStrings.strip(value)!!
            nameValue = if (clean.isEmpty()) "value" else clean.substring(0, minOf(32, clean.length))
        }
        fun setGlobalVariableId(value: String?) {
            globalVariableIdValue = if (MacroJavaStrings.isBlank(value)) null else value!!.substring(0, minOf(64, value.length))
        }
        fun addCase(): SwitchCase? {
            if (casesValue.size >= 16) return null
            val value = SwitchCase("case ${casesValue.size + 1}")
            casesValue.add(value)
            return value
        }
        override fun type(): String = "switch"
    }

    class SwitchCase(value: String?) {
        private var valueValue = ""
        private val stepsValue = ArrayList<MacroStep>()
        init { setValue(value) }
        fun value(): String = valueValue
        fun steps(): MutableList<MacroStep> = stepsValue
        fun setValue(value: String?) { valueValue = stepClampText(value, 128) }
    }
}

private fun stepClamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))
private fun stepClamp(value: Float, min: Float, max: Float): Float = maxOf(min, minOf(max, value))
private fun stepClampText(value: String?, maximum: Int): String = value?.substring(0, minOf(maximum, value.length)) ?: ""

private fun typeName(obj: JsonObject): String {
    val value: JsonElement? = obj.get("type")
    if (value == null || !value.isJsonPrimitive) return "(missing type)"
    return try {
        val type = value.asString
        if (MacroJavaStrings.isBlank(type)) "(blank type)" else type
    } catch (_: RuntimeException) {
        "(invalid type)"
    }
}

private fun integer(obj: JsonObject, name: String, fallback: Int): Int {
    val value: JsonElement? = obj.get(name)
    if (value == null || !value.isJsonPrimitive) return fallback
    return try { value.asInt } catch (_: RuntimeException) { fallback }
}

private fun decimal(obj: JsonObject, name: String, fallback: Float): Float {
    val value: JsonElement? = obj.get(name)
    if (value == null || !value.isJsonPrimitive) return fallback
    return try { value.asFloat } catch (_: RuntimeException) { fallback }
}

private fun cleanVariableName(value: String?): String {
    val clean = MacroJavaStrings.strip(value) ?: ""
    val limited = clean.substring(0, minOf(32, clean.length))
    return limited.ifEmpty { "value" }
}
