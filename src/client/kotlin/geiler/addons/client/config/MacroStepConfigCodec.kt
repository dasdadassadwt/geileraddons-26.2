package geiler.addons.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroCondition
import geiler.addons.client.macro.MacroStep
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.macro.MacroTreeRules
import geiler.addons.client.macro.MacroValue
import java.util.ArrayList
import java.util.Locale

/** Converts workflow nodes to and from the macro portion of the user config. */
object MacroStepConfigCodec {
    private const val MAX_DEPTH = MacroTreeRules.MAX_DEPTH
    private const val MAX_STEPS = 512

    @JvmStatic
    fun encode(steps: List<MacroStep?>?): JsonArray = writeSteps(steps, 0)

    @JvmStatic
    fun decode(steps: JsonArray?): MutableList<MacroStep> {
        val result = ArrayList<MacroStep>()
        if (steps == null) return result
        for (entry in steps) {
            if (entry != null && entry.isJsonObject) {
                val step = readStep(entry.asJsonObject, 0)
                if (step != null) result.add(step)
            }
        }
        return result
    }

    private fun writeSteps(source: List<MacroStep?>?, depth: Int): JsonArray {
        val result = JsonArray()
        if (source == null) return result
        for (step in source) {
            if (step == null) continue
            if (depth > MAX_DEPTH && step !is MacroStep.Unknown) {
                throw IllegalStateException("Refusing to save a macro step beyond the supported nesting depth")
            }
            result.add(writeStep(step, depth))
        }
        return result
    }

    private fun writeStep(step: MacroStep, depth: Int): JsonObject {
        if (step is MacroStep.Unknown) return step.serializedData()
        val result = JsonObject()
        result.addProperty("type", step.type())
        result.addProperty("delayMin", step.delayMin())
        result.addProperty("delayMax", step.delayMax())
        if (step is MacroStep.Base) {
            result.addProperty("editorX", step.editorX())
            result.addProperty("editorY", step.editorY())
        }
        if (step is MacroStep.Command) result.addProperty("command", step.command())
        if (step is MacroStep.Chat) result.addProperty("message", step.message())
        if (step is MacroStep.Title) {
            result.addProperty("text", step.text())
            result.addProperty("font", step.font())
            result.addProperty("scale", step.scale())
            result.addProperty("textColor", step.textColor())
            result.addProperty("showBackground", step.showBackground())
            result.addProperty("backgroundColor", step.backgroundColor())
            result.addProperty("backgroundOpacity", step.backgroundOpacity())
            result.addProperty("fadeInMillis", step.fadeInMillis())
            result.addProperty("holdMillis", step.holdMillis())
            result.addProperty("fadeOutMillis", step.fadeOutMillis())
        }
        if (step is MacroStep.Sound) result.addProperty("soundId", step.soundId())
        if (step is MacroStep.Wait) {
            result.addProperty("minMillis", step.minMillis())
            result.addProperty("maxMillis", step.maxMillis())
            result.addProperty("mode", step.mode().name)
            if (step.mode() == MacroStep.Wait.Mode.CONDITION) {
                result.add("condition", writeCondition(step.condition(), depth + 1))
            }
        }
        if (step is MacroStep.Comment) result.addProperty("text", step.text())
        if (step is MacroStep.Scroll) {
            result.addProperty("direction", step.direction().name)
            result.addProperty("amount", step.amount())
        }
        if (step is MacroStep.InventoryClick) {
            result.addProperty("target", step.target().name)
            result.addProperty("slotId", step.slotId())
            result.addProperty("name", step.name())
            result.addProperty("contains", step.contains())
            result.addProperty("scope", step.scope())
            result.addProperty("occurrence", step.occurrence())
            result.addProperty("button", step.button())
            result.addProperty("shift", step.shift())
        }
        if (step is MacroStep.UpdateVariable) {
            result.addProperty("operation", step.operation().name)
            result.addProperty("name", step.name())
            result.addProperty("amount", step.amount())
            if (step.globalVariableId() != null) result.addProperty("globalVariableId", step.globalVariableId())
            result.add("value", writeValue(step.value()))
        }
        if (step is MacroStep.Key) {
            result.addProperty("key", step.key())
            result.addProperty("inputMode", step.inputMode().name)
            result.addProperty("mouseButton", step.mouseButton().name)
            result.addProperty("hotbarSlot", step.hotbarSlot())
            result.addProperty("hold", step.hold())
            result.addProperty("holdMinMillis", step.holdMinMillis())
            result.addProperty("holdMaxMillis", step.holdMaxMillis())
        }
        if (step is MacroStep.SelectHotbarSlot) result.addProperty("slot", step.slot())
        if (step is MacroStep.MouseButton) {
            result.addProperty("button", step.button().name)
            result.addProperty("hold", step.hold())
            result.addProperty("holdMillis", step.holdMillis())
        }
        if (step is MacroStep.BlockPlayerInput) result.addProperty("durationMillis", step.durationMillis())
        if (step is MacroStep.FunctionCall) {
            result.addProperty("functionId", step.functionId())
            val arguments = JsonArray()
            for (argument in step.arguments()) arguments.add(writeValue(argument))
            result.add("arguments", arguments)
        }
        if (step is MacroStep.MacroCall) {
            result.addProperty("macroId", step.macroId())
            // An unconditional call keeps its historical shape and stays unconditional on reload.
            if (step.condition() != null) result.add("condition", writeCondition(step.condition(), depth + 1))
        }
        if (step is MacroStep.WorldSwitch) result.addProperty("island", step.target().name)
        if (step is MacroStep.WaitUntil) result.add("condition", writeCondition(step.condition(), depth + 1))
        if (step is MacroStep.IfElse) {
            result.add("condition", writeCondition(step.condition(), depth + 1))
            result.addProperty("elseEnabled", step.elseEnabled())
            result.add("thenSteps", writeSteps(step.thenSteps(), depth + 1))
            result.add("elseSteps", writeSteps(step.elseSteps(), depth + 1))
        }
        if (step is MacroStep.Repeat) {
            result.addProperty("mode", step.mode().name)
            result.addProperty("forever", step.forever())
            result.addProperty("count", step.count())
            if (step.mode() == MacroStep.Repeat.Mode.UNTIL) {
                result.add("condition", writeCondition(step.condition(), depth + 1))
            }
            result.add("steps", writeSteps(step.steps(), depth + 1))
        }
        if (step is MacroStep.RepeatUntil) {
            result.add("condition", writeCondition(step.condition(), depth + 1))
            result.add("untilSteps", writeSteps(step.steps(), depth + 1))
        }
        if (step is MacroStep.Switch) {
            result.addProperty("name", step.name())
            if (step.globalVariableId() != null) result.addProperty("globalVariableId", step.globalVariableId())
            val cases = JsonArray()
            for (branch in step.cases()) {
                if (cases.size() >= 16) break
                val item = JsonObject()
                item.addProperty("value", branch.value())
                item.add("steps", writeSteps(branch.steps(), depth + 1))
                cases.add(item)
            }
            result.add("cases", cases)
            result.add("defaultSteps", writeSteps(step.defaultSteps(), depth + 1))
        }
        return result
    }

    private fun writeCondition(condition: MacroCondition?, depth: Int): JsonObject {
        val result = JsonObject()
        if (condition == null || depth > MAX_DEPTH) {
            result.addProperty("type", "always")
            result.addProperty("expected", true)
            return result
        }
        when (condition) {
            is MacroCondition.Always -> {
                result.addProperty("type", "always")
                result.addProperty("expected", condition.expected())
            }
            is MacroCondition.Screen -> {
                result.addProperty("type", "screen")
                result.addProperty("title", condition.title())
                result.addProperty("mustBeOpen", condition.mustBeOpen())
                result.addProperty("contains", condition.contains())
            }
            is MacroCondition.Slot -> {
                result.addProperty("type", "slot")
                result.addProperty("slotId", condition.slotId())
                result.addProperty("mustExist", condition.mustExist())
            }
            is MacroCondition.Item -> {
                result.addProperty("type", "item")
                result.addProperty("name", condition.name())
                result.addProperty("mustExist", condition.mustExist())
                result.addProperty("contains", condition.contains())
                result.addProperty("scope", condition.scope().serializedName())
            }
            is MacroCondition.InventoryCount -> {
                result.addProperty("type", "inventory_count")
                result.addProperty("skyblockItemId", condition.skyBlockItemId())
                result.addProperty("vanillaItemId", condition.vanillaItemId())
                result.addProperty("operator", condition.operator().name)
                result.addProperty("threshold", condition.threshold())
            }
            is MacroCondition.Chat -> {
                result.addProperty("type", "chat")
                result.addProperty("text", condition.text())
                result.addProperty("contains", condition.contains())
            }
            is MacroCondition.World -> {
                result.addProperty("type", "world")
                result.addProperty("mustBeInWorld", condition.mustBeInWorld())
            }
            is MacroCondition.All -> {
                result.addProperty("type", "all")
                result.add("children", writeConditions(condition.children(), depth + 1))
            }
            is MacroCondition.Any -> {
                result.addProperty("type", "any")
                result.add("children", writeConditions(condition.children(), depth + 1))
            }
            is MacroCondition.Not -> {
                result.addProperty("type", "not")
                result.add("child", writeCondition(condition.child(), depth + 1))
            }
            is MacroCondition.Variable -> {
                result.addProperty("type", "variable")
                result.addProperty("name", condition.name())
                if (condition.globalVariableId() != null) result.addProperty("globalVariableId", condition.globalVariableId())
                result.addProperty("operator", condition.operator().name)
                result.add("value", writeValue(condition.value()))
            }
            is MacroCondition.Hypixel -> {
                result.addProperty("type", "hypixel")
                result.addProperty("field", condition.field().name)
                result.addProperty("operator", condition.operator().name)
                result.addProperty("expected", condition.expected())
                result.addProperty("argument", condition.argument())
                result.addProperty("plotId", condition.plotId())
            }
        }
        return result
    }

    private fun writeConditions(source: List<MacroCondition?>?, depth: Int): JsonArray {
        val result = JsonArray()
        if (depth > MAX_DEPTH || source == null) return result
        for (condition in source) {
            if (result.size() >= MAX_STEPS) break
            result.add(writeCondition(condition, depth))
        }
        return result
    }

    private fun readStep(objectValue: JsonObject, depth: Int): MacroStep? {
        if (depth > MAX_DEPTH) return MacroStep.Unknown(objectValue, true)
        val result: MacroStep = when (string(objectValue, "type", "")) {
            "command" -> readCommandAsMessage(objectValue)
            "chat" -> MacroStep.Chat(string(objectValue, "message", ""))
            "title" -> readTitle(objectValue)
            "sound" -> MacroStep.Sound(string(objectValue, "soundId", "minecraft:entity.player.levelup"))
            "wait" -> {
                val min = integer(objectValue, "minMillis", 0)
                val wait = MacroStep.Wait(min, integer(objectValue, "maxMillis", min))
                if ("CONDITION".equals(string(objectValue, "mode", "DURATION"), ignoreCase = true)) {
                    wait.setMode(MacroStep.Wait.Mode.CONDITION)
                    wait.setCondition(readCondition(objectValue.get("condition"), depth + 1))
                }
                wait
            }
            "comment" -> MacroStep.Comment(string(objectValue, "text", "Comment"))
            "stop_run" -> MacroStep.StopRun()
            "scroll" -> readScroll(objectValue)
            "inventory_click" -> readInventoryClick(objectValue)
            "update_variable" -> readUpdateVariable(objectValue)
            "key" -> {
                val legacy = integer(objectValue, "holdMillis", 250)
                val minimum = integer(objectValue, "holdMinMillis", legacy)
                val maximum = integer(objectValue, "holdMaxMillis", minimum)
                val key = MacroStep.Key(string(objectValue, "key", ""),
                    bool(objectValue, "hold", false), minimum, maximum)
                key.setInputMode(enumValue(MacroStep.Key.InputMode::class.java,
                    string(objectValue, "inputMode", "KEYBOARD"), MacroStep.Key.InputMode.KEYBOARD))
                key.setMouseButton(enumValue(MacroStep.MouseButton.Button::class.java,
                    string(objectValue, "mouseButton", "LEFT"), MacroStep.MouseButton.Button.LEFT))
                key.setHotbarSlot(integer(objectValue, "hotbarSlot", 1))
                key
            }
            "click_slot" -> readLegacyClickSlot(objectValue)
            "click_item" -> readLegacyClickItem(objectValue)
            "close_screen" -> MacroStep.CloseScreen()
            "select_hotbar_slot" -> readHotbarAsInput(objectValue)
            "mouse_button" -> readMouseAsInput(objectValue)
            "block_player_input" -> MacroStep.BlockPlayerInput(integer(objectValue, "durationMillis", 1_000))
            "start_block_player_input" -> MacroStep.StartBlockPlayerInput()
            "stop_block_player_input" -> MacroStep.StopBlockPlayerInput()
            "set_variable" -> readLegacySetVariable(objectValue)
            "change_variable" -> readLegacyChangeVariable(objectValue)
            "function_call" -> readFunctionCall(objectValue)
            "macro_call" -> readMacroCall(objectValue, depth + 1)
            "world_switch" -> MacroStep.WorldSwitch(selectableIsland(string(objectValue, "island", Island.HUB.name)))
            "wait_until" -> MacroStep.WaitUntil(readCondition(objectValue.get("condition"), depth + 1))
            "if" -> readIf(objectValue, depth + 1)
            "repeat" -> readRepeat(objectValue, depth + 1)
            "repeat_until" -> readRepeatUntil(objectValue, depth + 1)
            "switch" -> readSwitch(objectValue, depth + 1)
            else -> MacroStep.Unknown(objectValue)
        }
        result.setDelay(integer(objectValue, "delayMin", 0), integer(objectValue, "delayMax", 0))
        if (result is MacroStep.Base) {
            result.setEditorPosition(decimal(objectValue, "editorX", 0.0f), decimal(objectValue, "editorY", 0.0f))
        }
        return result
    }

    private fun readTitle(objectValue: JsonObject): MacroStep.Title {
        return MacroStep.Title().apply {
            setText(string(objectValue, "text", "TITLE"))
            setFont(string(objectValue, "font", "minecraft:default"))
            setScale(decimal(objectValue, "scale", 2.0f))
            setTextColor(integer(objectValue, "textColor", 0xFFFFFFFF.toInt()))
            setShowBackground(bool(objectValue, "showBackground", false))
            setBackgroundColor(integer(objectValue, "backgroundColor", 0xFF000000.toInt()))
            setBackgroundOpacity(integer(objectValue, "backgroundOpacity", 160))
            setFadeInMillis(integer(objectValue, "fadeInMillis", 200))
            setHoldMillis(integer(objectValue, "holdMillis", 2_000))
            setFadeOutMillis(integer(objectValue, "fadeOutMillis", 300))
        }
    }

    private fun readIf(objectValue: JsonObject, depth: Int): MacroStep.IfElse {
        val result = MacroStep.IfElse(readCondition(objectValue.get("condition"), depth))
        addSteps(result.thenSteps(), array(objectValue, "thenSteps"), depth)
        addSteps(result.elseSteps(), array(objectValue, "elseSteps"), depth)
        result.setElseEnabled(if (objectValue.has("elseEnabled")) {
            bool(objectValue, "elseEnabled", false)
        } else {
            result.elseSteps().isNotEmpty()
        })
        return result
    }

    private fun readRepeat(objectValue: JsonObject, depth: Int): MacroStep.Repeat {
        val result = MacroStep.Repeat(bool(objectValue, "forever", false), integer(objectValue, "count", 1))
        val savedMode = string(objectValue, "mode", "")
        if (!MacroJavaStrings.isBlank(savedMode)) {
            result.setMode(enumValue(MacroStep.Repeat.Mode::class.java, savedMode, MacroStep.Repeat.Mode.COUNT))
        } else if (bool(objectValue, "forever", false)) {
            result.setMode(MacroStep.Repeat.Mode.FOREVER)
        }
        if (result.mode() == MacroStep.Repeat.Mode.UNTIL) {
            result.setCondition(readCondition(objectValue.get("condition"), depth + 1))
        }
        addSteps(result.steps(), array(objectValue, "steps"), depth)
        return result
    }

    private fun readRepeatUntil(objectValue: JsonObject, depth: Int): MacroStep.Repeat {
        val result = MacroStep.Repeat(false, 1)
        result.setMode(MacroStep.Repeat.Mode.UNTIL)
        result.setCondition(readCondition(objectValue.get("condition"), depth))
        var children = array(objectValue, "untilSteps")
        if (children == null) children = array(objectValue, "steps")
        addSteps(result.steps(), children, depth)
        return result
    }

    private fun readCommandAsMessage(objectValue: JsonObject): MacroStep.Chat {
        var command = MacroJavaStrings.strip(string(objectValue, "command", "")) ?: ""
        if (command.isNotEmpty() && !command.startsWith("/")) command = "/$command"
        return MacroStep.Chat(command)
    }

    private fun readMouseAsInput(objectValue: JsonObject): MacroStep.Key {
        val button = enumValue(MacroStep.MouseButton.Button::class.java,
            string(objectValue, "button", "LEFT"), MacroStep.MouseButton.Button.LEFT)
        val millis = integer(objectValue, "holdMillis", 250)
        return MacroStep.Key("", bool(objectValue, "hold", false), millis).apply {
            setInputMode(MacroStep.Key.InputMode.MOUSE)
            setMouseButton(button)
        }
    }

    private fun readHotbarAsInput(objectValue: JsonObject): MacroStep.Key {
        return MacroStep.Key("", false, 250).apply {
            setInputMode(MacroStep.Key.InputMode.HOTBAR)
            setHotbarSlot(integer(objectValue, "slot", 1))
        }
    }

    private fun readLegacyClickSlot(objectValue: JsonObject): MacroStep.InventoryClick {
        return MacroStep.InventoryClick().apply {
            setTarget(MacroStep.InventoryClick.Target.SLOT)
            setSlotId(integer(objectValue, "slotId", 0))
            setButton(integer(objectValue, "button", 0))
            setShift(bool(objectValue, "shift", false))
        }
    }

    private fun readLegacyClickItem(objectValue: JsonObject): MacroStep.InventoryClick {
        return MacroStep.InventoryClick().apply {
            setTarget(MacroStep.InventoryClick.Target.ITEM)
            setName(string(objectValue, "name", ""))
            setContains(bool(objectValue, "contains", false))
            setScope(string(objectValue, "scope", "container"))
            setOccurrence(integer(objectValue, "occurrence", 0))
            setButton(integer(objectValue, "button", 0))
            setShift(bool(objectValue, "shift", false))
        }
    }

    private fun readLegacySetVariable(objectValue: JsonObject): MacroStep.UpdateVariable {
        var value = readValue(objectValue.get("value"))
        if (!objectValue.has("value") || !objectValue.get("value").isJsonObject) {
            val type = valueType(string(objectValue, "valueType", "TEXT"))
            value = MacroValue.literal(type, "")
        } else {
            val legacyType = valueType(string(objectValue, "valueType", value.type().name))
            value = MacroValue(legacyType, value.variableReference(), value.value(), value.scope(),
                value.skyblockItemId(), value.vanillaItemId())
        }
        return MacroStep.UpdateVariable().apply {
            setOperation(MacroStep.UpdateVariable.Operation.SET)
            setName(string(objectValue, "name", "value"))
            setGlobalVariableId(string(objectValue, "globalVariableId", null))
            setValue(value)
        }
    }

    private fun readLegacyChangeVariable(objectValue: JsonObject): MacroStep.UpdateVariable {
        return MacroStep.UpdateVariable().apply {
            setOperation(MacroStep.UpdateVariable.Operation.ADD)
            setName(string(objectValue, "name", "value"))
            setGlobalVariableId(string(objectValue, "globalVariableId", null))
            setAmount(decimal(objectValue, "amount", 1.0))
        }
    }

    private fun readSwitch(objectValue: JsonObject, depth: Int): MacroStep.Switch {
        val result = MacroStep.Switch()
        result.setName(string(objectValue, "name", "value"))
        result.setGlobalVariableId(string(objectValue, "globalVariableId", null))
        result.cases().clear()
        val cases = array(objectValue, "cases")
        if (cases != null) for (entry in cases) {
            if (result.cases().size >= 16) break
            if (entry == null || !entry.isJsonObject) continue
            val item = entry.asJsonObject
            val branch = MacroStep.SwitchCase(string(item, "value", ""))
            addSteps(branch.steps(), array(item, "steps"), depth)
            result.cases().add(branch)
        }
        if (result.cases().isEmpty()) result.addCase()
        addSteps(result.defaultSteps(), array(objectValue, "defaultSteps"), depth)
        return result
    }

    private fun readScroll(objectValue: JsonObject): MacroStep.Scroll {
        val direction = try {
            MacroStep.Scroll.Direction.valueOf(string(objectValue, "direction", "DOWN") ?: "")
        } catch (_: IllegalArgumentException) {
            MacroStep.Scroll.Direction.DOWN
        }
        return MacroStep.Scroll(direction, integer(objectValue, "amount", 1))
    }

    private fun readInventoryClick(objectValue: JsonObject): MacroStep.InventoryClick {
        val target = try {
            MacroStep.InventoryClick.Target.valueOf(string(objectValue, "target", "SLOT") ?: "")
        } catch (_: IllegalArgumentException) {
            MacroStep.InventoryClick.Target.SLOT
        }
        return MacroStep.InventoryClick().apply {
            setTarget(target)
            setSlotId(integer(objectValue, "slotId", 0))
            setName(string(objectValue, "name", ""))
            setContains(bool(objectValue, "contains", false))
            setScope(string(objectValue, "scope", "container"))
            setOccurrence(integer(objectValue, "occurrence", 0))
            setButton(integer(objectValue, "button", 0))
            setShift(bool(objectValue, "shift", false))
        }
    }

    private fun readUpdateVariable(objectValue: JsonObject): MacroStep.UpdateVariable {
        val operation = try {
            MacroStep.UpdateVariable.Operation.valueOf(string(objectValue, "operation", "SET") ?: "")
        } catch (_: IllegalArgumentException) {
            MacroStep.UpdateVariable.Operation.SET
        }
        return MacroStep.UpdateVariable().apply {
            setOperation(operation)
            setName(string(objectValue, "name", "value"))
            setGlobalVariableId(string(objectValue, "globalVariableId", null))
            setAmount(decimal(objectValue, "amount", 1.0))
            setValue(readValue(objectValue.get("value")))
        }
    }

    private fun addSteps(target: MutableList<MacroStep>, source: JsonArray?, depth: Int) {
        if (source == null) return
        for (entry in source) {
            if (entry != null && entry.isJsonObject) {
                val step = readStep(entry.asJsonObject, depth)
                if (step != null) target.add(step)
            }
        }
    }

    /** An absent Macro Call condition remains absent, preserving its unconditional behavior. */
    private fun readMacroCall(objectValue: JsonObject, depth: Int): MacroStep.MacroCall {
        val call = MacroStep.MacroCall(integer(objectValue, "macroId", 0))
        if (objectValue.has("condition")) call.setCondition(readCondition(objectValue.get("condition"), depth))
        return call
    }

    private fun readCondition(element: JsonElement?, depth: Int): MacroCondition {
        if (depth > MAX_DEPTH || element == null || !element.isJsonObject) return MacroCondition.Always(true)
        val objectValue = element.asJsonObject
        return when (string(objectValue, "type", "always")) {
            "always" -> MacroCondition.Always(bool(objectValue, "expected", true))
            "screen" -> MacroCondition.Screen(string(objectValue, "title", ""),
                bool(objectValue, "mustBeOpen", true), bool(objectValue, "contains", false))
            "slot" -> MacroCondition.Slot(integer(objectValue, "slotId", 0), bool(objectValue, "mustExist", true))
            "item" -> MacroCondition.Item(string(objectValue, "name", ""), bool(objectValue, "mustExist", true),
                bool(objectValue, "contains", false), readItemScope(objectValue, false))
            "inventory_count" -> MacroCondition.InventoryCount(
                limited(string(objectValue, "skyblockItemId", ""), 128),
                limited(string(objectValue, "vanillaItemId", ""), 128),
                variableOperator(string(objectValue, "operator", "GREATER_OR_EQUAL")),
                integer(objectValue, "threshold", 1))
            "chat" -> MacroCondition.Chat(string(objectValue, "text", ""), bool(objectValue, "contains", false))
            "world" -> MacroCondition.World(bool(objectValue, "mustBeInWorld", true))
            "all" -> MacroCondition.All(readConditions(array(objectValue, "children"), depth + 1))
            "any" -> MacroCondition.Any(readConditions(array(objectValue, "children"), depth + 1))
            "not" -> MacroCondition.Not(readCondition(objectValue.get("child"), depth + 1))
            "variable" -> MacroCondition.Variable(string(objectValue, "name", "value"),
                variableOperator(string(objectValue, "operator", "EQUALS")), readValue(objectValue.get("value")),
                string(objectValue, "globalVariableId", null))
            "hypixel" -> readHypixelCondition(objectValue)
            else -> MacroCondition.Always(true)
        }
    }

    private fun writeValue(valueValue: MacroValue?): JsonObject {
        val value = valueValue ?: MacroValue.literal(MacroValue.Type.TEXT, "")
        val result = JsonObject()
        result.addProperty("type", value.type().name)
        result.addProperty("variable", value.variableReference())
        result.addProperty("scope", value.scope().name)
        result.addProperty("value", value.value())
        if (value.type() == MacroValue.Type.ITEM_COUNT) {
            result.addProperty("skyblockItemId", value.skyblockItemId())
            result.addProperty("vanillaItemId", value.vanillaItemId())
        }
        return result
    }

    private fun readValue(element: JsonElement?): MacroValue {
        if (element == null || !element.isJsonObject) return MacroValue.literal(MacroValue.Type.TEXT, "")
        val objectValue = element.asJsonObject
        val variable = bool(objectValue, "variable", false)
        return MacroValue(valueType(string(objectValue, "type", "TEXT")), variable,
            string(objectValue, "value", ""), valueScope(string(objectValue, "scope", if (variable) "LOCAL" else "NONE")),
            string(objectValue, "skyblockItemId", ""), string(objectValue, "vanillaItemId", ""))
    }

    private fun readHypixelCondition(objectValue: JsonObject): MacroCondition.Hypixel {
        val field = try {
            MacroCondition.Hypixel.Field.valueOf(string(objectValue, "field", "ISLAND") ?: "")
        } catch (_: IllegalArgumentException) {
            MacroCondition.Hypixel.Field.ISLAND
        }
        val operator = try {
            MacroCondition.Hypixel.Operator.valueOf(string(objectValue, "operator", "EQUALS") ?: "")
        } catch (_: IllegalArgumentException) {
            MacroCondition.Hypixel.Operator.EQUALS
        }
        return MacroCondition.Hypixel(field, operator, string(objectValue, "expected", ""),
            string(objectValue, "argument", ""), integer(objectValue, "plotId", -1))
    }

    private fun readFunctionCall(objectValue: JsonObject): MacroStep.FunctionCall {
        val result = MacroStep.FunctionCall(string(objectValue, "functionId", ""))
        val arguments = array(objectValue, "arguments")
        if (arguments != null) for (value in arguments) {
            if (result.arguments().size >= 32) break
            result.arguments().add(readValue(value))
        }
        return result
    }

    private fun valueType(value: String?): MacroValue.Type = try {
        MacroValue.Type.valueOf((value ?: "TEXT").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroValue.Type.TEXT
    }

    private fun valueScope(value: String?): MacroValue.Scope = try {
        MacroValue.Scope.valueOf((value ?: "NONE").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroValue.Scope.LOCAL
    }

    private fun variableOperator(value: String?): MacroCondition.Variable.Operator = try {
        MacroCondition.Variable.Operator.valueOf((value ?: "EQUALS").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroCondition.Variable.Operator.EQUALS
    }

    private fun readItemScope(objectValue: JsonObject, missingLegacyDefault: Boolean): MacroCondition.ItemScope {
        if (objectValue.has("scope")) {
            val parsed = MacroCondition.ItemScope.fromSerialized(string(objectValue, "scope", ""), null)
            if (parsed != null) return parsed
        }
        return MacroCondition.ItemScope.fromLegacy(bool(objectValue, "includePlayerInventory", missingLegacyDefault))
    }

    private fun readConditions(source: JsonArray?, depth: Int): MutableList<MacroCondition> {
        val result = ArrayList<MacroCondition>()
        if (source == null || depth > MAX_DEPTH) return result
        for (entry in source) {
            if (result.size >= MAX_STEPS) break
            result.add(readCondition(entry, depth))
        }
        return result
    }

    private fun selectableIsland(value: String?): Island {
        if (value == null) return Island.HUB
        try {
            val island = Island.valueOf(MacroJavaStrings.trim(value)!!.uppercase(Locale.ROOT))
            return if (island.selectable()) island else Island.HUB
        } catch (_: IllegalArgumentException) {
            for (island in Island.values()) {
                if (island.selectable() && island.label().equals(MacroJavaStrings.trim(value), ignoreCase = true)) return island
            }
            return Island.HUB
        }
    }

    private fun array(objectValue: JsonObject, name: String): JsonArray? {
        val value = objectValue.get(name)
        return if (value != null && value.isJsonArray) value.asJsonArray else null
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String?): String? {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asString } catch (_: RuntimeException) { fallback }
    }

    private fun limited(value: String?, max: Int): String {
        if (value == null || max <= 0) return ""
        return if (value.length <= max) value else value.substring(0, max)
    }

    private fun integer(objectValue: JsonObject, name: String, fallback: Int): Int {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asInt } catch (_: RuntimeException) { fallback }
    }

    private fun decimal(objectValue: JsonObject, name: String, fallback: Double): Double {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asDouble } catch (_: RuntimeException) { fallback }
    }

    private fun decimal(objectValue: JsonObject, name: String, fallback: Float): Float {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asFloat } catch (_: RuntimeException) { fallback }
    }

    private fun bool(objectValue: JsonObject, name: String, fallback: Boolean): Boolean {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asBoolean } catch (_: RuntimeException) { fallback }
    }

    private fun <E : Enum<E>> enumValue(type: Class<E>, value: String?, fallback: E): E {
        if (value == null) return fallback
        return try {
            java.lang.Enum.valueOf(type, MacroJavaStrings.trim(value)!!.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
