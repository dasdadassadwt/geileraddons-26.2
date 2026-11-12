package geiler.addons.client.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.macro.MacroScript
import geiler.addons.client.macro.MacroWorldRegion
import geiler.addons.client.module.ModuleKeybind
import java.util.Locale

/** Bounded codec for macro event stacks, workspace positions, and world trigger settings. */
object MacroScriptConfigCodec {
    private const val MAX_SCRIPTS = 32

    @JvmStatic
    fun encode(scripts: List<MacroScript?>?): JsonArray {
        val result = JsonArray()
        if (scripts == null) return result
        for (script in scripts) {
            if (result.size() >= MAX_SCRIPTS) break
            if (script == null) continue
            val objectValue = JsonObject()
            objectValue.addProperty("id", script.id())
            objectValue.addProperty("trigger", script.trigger().name)
            objectValue.addProperty("key", if (script.keybind().isBound()) script.keybind().key().getName() else "")
            objectValue.addProperty("modifiers", script.keybind().modifiers())
            objectValue.addProperty("canvasX", script.canvasX())
            objectValue.addProperty("canvasY", script.canvasY())
            objectValue.addProperty("chatPattern", script.chatPattern())
            objectValue.addProperty("chatContains", script.chatContains())
            objectValue.addProperty("chatCooldownMillis", script.chatCooldownMillis())
            objectValue.add("steps", MacroStepConfigCodec.encode(script.steps()))
            objectValue.add("worldRegion", encodeRegion(script.worldRegion()))
            result.add(objectValue)
        }
        return result
    }

    @JvmStatic
    fun decode(source: JsonArray?): MutableList<MacroScript> {
        val result = ArrayList<MacroScript>()
        if (source == null) return result
        for (entry in source) {
            if (result.size >= MAX_SCRIPTS) break
            if (entry == null || !entry.isJsonObject) continue
            val objectValue = entry.asJsonObject
            val script = MacroScript(string(objectValue, "id", ""), trigger(string(objectValue, "trigger", "KEY_PRESS")))
            val key = string(objectValue, "key", "")
            if (!MacroJavaStrings.isBlank(key)) {
                try {
                    script.setKeybind(ModuleKeybind(InputConstants.getKey(key), integer(objectValue, "modifiers", 0)))
                } catch (_: RuntimeException) {
                    script.setKeybind(ModuleKeybind.NONE)
                }
            }
            script.setCanvasPosition(decimal(objectValue, "canvasX", 0.0).toFloat(),
                decimal(objectValue, "canvasY", 0.0).toFloat())
            script.setChatPattern(string(objectValue, "chatPattern", ""))
            script.setChatContains(bool(objectValue, "chatContains", true))
            script.setChatCooldownMillis(integer(objectValue, "chatCooldownMillis", 500))
            script.steps().addAll(MacroStepConfigCodec.decode(array(objectValue, "steps")))
            script.setWorldRegion(decodeRegion(objectValue.get("worldRegion")))
            result.add(script)
        }
        return result
    }

    private fun encodeRegion(regionValue: MacroWorldRegion?): JsonObject {
        val region = regionValue ?: MacroWorldRegion()
        return JsonObject().apply {
            addProperty("placed", region.placed())
            addProperty("worldKey", region.worldKey())
            addProperty("x", region.x())
            addProperty("y", region.y())
            addProperty("z", region.z())
            addProperty("shape", region.shape().name)
            addProperty("size", region.size())
            addProperty("innerSize", region.innerSize())
            addProperty("yTolerance", region.yTolerance())
            addProperty("repeatDelayMillis", region.repeatDelayMillis())
            addProperty("oncePerWorld", region.oncePerWorld())
            addProperty("color", region.color())
            addProperty("fillOpacity", region.fillOpacity())
            addProperty("lineWidth", region.lineWidth())
        }
    }

    private fun decodeRegion(element: JsonElement?): MacroWorldRegion {
        val region = MacroWorldRegion()
        if (element == null || !element.isJsonObject) return region
        val objectValue = element.asJsonObject
        val worldKey = string(objectValue, "worldKey", "")
        region.restorePlacement(bool(objectValue, "placed", false), worldKey,
            decimal(objectValue, "x", 0.0), decimal(objectValue, "y", 0.0), decimal(objectValue, "z", 0.0))
        region.setShape(shape(string(objectValue, "shape", "CIRCLE")))
        region.setSize(decimal(objectValue, "size", 8.0))
        region.setInnerSize(decimal(objectValue, "innerSize", 4.0))
        region.setYTolerance(decimal(objectValue, "yTolerance", 3.0))
        region.setRepeatDelayMillis(integer(objectValue, "repeatDelayMillis", 1_000))
        region.setOncePerWorld(bool(objectValue, "oncePerWorld", false))
        region.setColor(integer(objectValue, "color", 0xFF43D9C4.toInt()))
        region.setFillOpacity(integer(objectValue, "fillOpacity", 22))
        region.setLineWidth(decimal(objectValue, "lineWidth", 2.5).toFloat())
        return region
    }

    private fun trigger(raw: String?): MacroScript.Trigger = try {
        MacroScript.Trigger.valueOf((raw ?: "").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroScript.Trigger.KEY_PRESS
    }

    private fun shape(raw: String?): MacroWorldRegion.Shape = try {
        MacroWorldRegion.Shape.valueOf((raw ?: "").uppercase(Locale.ROOT))
    } catch (_: IllegalArgumentException) {
        MacroWorldRegion.Shape.CIRCLE
    }

    private fun array(objectValue: JsonObject, name: String): JsonArray? {
        val value = objectValue.get(name)
        return if (value != null && value.isJsonArray) value.asJsonArray else null
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String): String {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asString } catch (_: RuntimeException) { fallback }
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

    private fun bool(objectValue: JsonObject, name: String, fallback: Boolean): Boolean {
        val value = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try { value.asBoolean } catch (_: RuntimeException) { fallback }
    }
}
