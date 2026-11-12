package geiler.addons.client.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.HashMap
import kotlin.math.abs

/** Kotlin JSON/Base64 codec behind the Java-compatible clipboard API. */
object DungeonRoomMapStyleCodecLogic {
    private const val MAX_BYTES = 32 * 1024
    private const val MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4
    private const val MAX_SETTINGS = 128
    private const val FORMAT = "geileraddons-dungeon-room-map-style"

    @JvmStatic
    fun encode(settings: List<Setting?>?): String? {
        if (settings == null || settings.size > MAX_SETTINGS || !hasUniqueTypedKeys(settings)) return null
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", 1)
        val toggles = JsonObject()
        val numbers = JsonObject()
        val choices = JsonObject()
        val colors = JsonObject()
        for (setting in settings) {
            when (setting) {
                is BooleanSetting -> toggles.addProperty(setting.name(), setting.value())
                is NumberSetting -> numbers.addProperty(setting.name(), setting.value())
                is ChoiceSetting -> choices.addProperty(setting.name(), setting.value())
                is ColorSetting -> {
                    val rgba = JsonArray()
                    rgba.add(setting.red())
                    rgba.add(setting.green())
                    rgba.add(setting.blue())
                    rgba.add(setting.alpha())
                    colors.add(setting.name(), rgba)
                }
                else -> Unit
            }
        }
        root.add("toggles", toggles)
        root.add("numbers", numbers)
        root.add("choices", choices)
        root.add("colors", colors)
        val json = ClientJsonFile.GSON.toJson(root)
        return if (json.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) json else null
    }

    @JvmStatic
    fun encodeBase64(settings: List<Setting?>?): String? {
        val json = encode(settings) ?: return null
        return Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    /** Accepts exported Base64 or editable JSON. Invalid values never partially apply. */
    @JvmStatic
    fun decodeClipboard(clipboard: String?): DungeonRoomMapStyleCodec.DecodeResult {
        if (clipboard == null) return failure("Clipboard is empty.")
        // Check the original length before strip or decoding can allocate another large buffer.
        if (clipboard.length > MAX_BASE64_CHARS) return failure("Map style exceeds the 32 KiB limit.")
        if (clipboard.isBlank()) return failure("Clipboard is empty.")

        var first = 0
        while (first < clipboard.length && Character.isWhitespace(clipboard[first])) first++
        val jsonInput = first < clipboard.length && clipboard[first] == '{'
        if (jsonInput && clipboard.length > MAX_BYTES) return failure("Map style exceeds the 32 KiB limit.")

        var source = stripJavaWhitespace(clipboard)
        if (!jsonInput) {
            try {
                val decoded = Base64.getDecoder().decode(source)
                if (decoded.size > MAX_BYTES) return failure("Map style exceeds the 32 KiB limit.")
                source = String(decoded, StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return failure("Clipboard must contain map-style JSON or its Base64 export.")
            }
        }
        if (exceedsUtf8ByteLimit(source, MAX_BYTES)) return failure("Map style exceeds the 32 KiB limit.")
        try {
            val parsed = BoundedDungeonJsonParser.parse(source, 5, 10_000)
            if (!parsed.isJsonObject) return failure("Map style must be a JSON object.")
            val root = parsed.asJsonObject
            val format = root.get("format")
            val version = root.get("version")
            val toggleObject = root.get("toggles")
            val numberObject = root.get("numbers")
            val choiceObject = root.get("choices")
            val colorObject = root.get("colors")
            if (root.size() != 6 || !isString(format) || FORMAT != format.asString ||
                !isInteger(version) || version.asInt != 1 ||
                !isObject(toggleObject) || !isObject(numberObject) || !isObject(choiceObject) || !isObject(colorObject)
            ) return failure("Map style has an unsupported format or version.")
            val typedCount = toggleObject.asJsonObject.size() + numberObject.asJsonObject.size() +
                choiceObject.asJsonObject.size() + colorObject.asJsonObject.size()
            if (typedCount > MAX_SETTINGS) return failure("Map style contains too many settings.")

            val toggles = readToggles(toggleObject.asJsonObject)
            val numbers = readNumbers(numberObject.asJsonObject)
            val choices = readChoices(choiceObject.asJsonObject)
            val colors = readColors(colorObject.asJsonObject)
            if (toggles == null || numbers == null || choices == null || colors == null) {
                return failure("Map style contains an invalid setting value.")
            }
            return DungeonRoomMapStyleCodec.DecodeResult(
                true,
                DungeonRoomMapStyleCodec.Decoded(
                    java.util.Map.copyOf(toggles), java.util.Map.copyOf(numbers),
                    java.util.Map.copyOf(choices), java.util.Map.copyOf(colors),
                ),
                null,
            )
        } catch (_: IOException) {
            return failure("Map style JSON is invalid.")
        } catch (_: JsonParseException) {
            return failure("Map style JSON is invalid.")
        } catch (_: IllegalStateException) {
            return failure("Map style JSON is invalid.")
        } catch (_: NullPointerException) {
            return failure("Map style JSON is invalid.")
        }
    }

    private fun exceedsUtf8ByteLimit(value: String, maxBytes: Int): Boolean {
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val addition: Int
            if (current.code <= 0x7F) addition = 1
            else if (current.code <= 0x7FF) addition = 2
            else if (Character.isHighSurrogate(current) && index + 1 < value.length &&
                Character.isLowSurrogate(value[index + 1])
            ) {
                addition = 4
                index++
            } else if (Character.isSurrogate(current)) addition = 1
            else addition = 3
            if (bytes > maxBytes - addition) return true
            bytes += addition
            index++
        }
        return false
    }

    @JvmStatic
    fun appliesTo(settings: List<Setting?>?, decoded: DungeonRoomMapStyleCodec.Decoded?): Boolean {
        if (settings.isNullOrEmpty() || settings.size > MAX_SETTINGS || decoded == null ||
            decoded.toggles == null || decoded.numbers == null || decoded.choices == null || decoded.colors == null
        ) return false
        val toggles = HashMap<String, BooleanSetting>()
        val numbers = HashMap<String, NumberSetting>()
        val choices = HashMap<String, ChoiceSetting>()
        val colors = HashMap<String, ColorSetting>()
        for (setting in settings) {
            if (setting == null) return false
            when (setting) {
                is BooleanSetting -> if (!validKey(setting.name()) || toggles.putIfAbsent(setting.name(), setting) != null) return false
                is NumberSetting -> if (!validKey(setting.name()) || numbers.putIfAbsent(setting.name(), setting) != null) return false
                is ChoiceSetting -> if (!validKey(setting.name()) || choices.putIfAbsent(setting.name(), setting) != null) return false
                is ColorSetting -> if (!validKey(setting.name()) || colors.putIfAbsent(setting.name(), setting) != null) return false
                else -> Unit
            }
        }
        for ((key, value) in decoded.toggles!!.entries) {
            if (!toggles.containsKey(key) || value == null) return false
        }
        for ((key, value) in decoded.numbers!!.entries) {
            val number = numbers[key] ?: return false
            if (value == null || !value.isFinite() || value < number.min() || value > number.max() ||
                number.isInteger() && value != java.lang.Math.round(value).toFloat()
            ) return false
        }
        for ((key, value) in decoded.choices!!.entries) {
            val choice = choices[key] ?: return false
            if (value == null || !choice.choices().contains(value)) return false
        }
        for ((key, value) in decoded.colors!!.entries) {
            val color = colors[key] ?: return false
            if (value == null || !channel(value.red) || !channel(value.green) || !channel(value.blue) ||
                !channel(value.alpha) || !color.alphaEditable() && value.alpha != 255
            ) return false
        }
        return !decoded.isEmpty()
    }

    @JvmStatic
    fun apply(settings: List<Setting?>?, decoded: DungeonRoomMapStyleCodec.Decoded?) {
        if (!appliesTo(settings, decoded)) throw IllegalArgumentException("Map style does not match this mod version.")
        for (setting in settings!!) {
            when (setting) {
                is BooleanSetting -> decoded!!.toggles!!.get(setting.name())?.let(setting::setValue)
                is NumberSetting -> decoded!!.numbers!!.get(setting.name())?.let(setting::setValue)
                is ChoiceSetting -> decoded!!.choices!!.get(setting.name())?.let(setting::setValue)
                is ColorSetting -> decoded!!.colors!!.get(setting.name())?.let {
                    setting.set(it.red, it.green, it.blue, it.alpha)
                }
                else -> Unit
            }
        }
    }

    private fun hasUniqueTypedKeys(settings: List<Setting?>): Boolean {
        val toggles = HashMap<String, Setting>()
        val numbers = HashMap<String, Setting>()
        val choices = HashMap<String, Setting>()
        val colors = HashMap<String, Setting>()
        for (setting in settings) {
            if (setting == null) return false
            val keys = when (setting) {
                is BooleanSetting -> toggles
                is NumberSetting -> numbers
                is ChoiceSetting -> choices
                is ColorSetting -> colors
                else -> continue
            }
            if (!validKey(setting.name()) || keys.putIfAbsent(setting.name(), setting) != null) return false
        }
        return true
    }

    @JvmStatic
    fun reset(settings: List<Setting?>?): Boolean {
        if (settings.isNullOrEmpty() || settings.size > MAX_SETTINGS) return false
        for (setting in settings) {
            if (setting !is BooleanSetting && setting !is NumberSetting &&
                setting !is ChoiceSetting && setting !is ColorSetting
            ) return false
        }
        for (setting in settings) {
            when (setting) {
                is BooleanSetting -> setting.reset()
                is NumberSetting -> setting.reset()
                is ChoiceSetting -> setting.reset()
                is ColorSetting -> setting.reset()
                else -> Unit
            }
        }
        return true
    }

    private fun channel(value: Int): Boolean = value in 0..255

    private fun readToggles(obj: JsonObject): Map<String, Boolean>? {
        val result = HashMap<String, Boolean>()
        for ((key, value) in obj.entrySet()) {
            if (!validKey(key) || value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) return null
            result[key] = value.asBoolean
        }
        return result
    }

    private fun readNumbers(obj: JsonObject): Map<String, Float>? {
        val result = HashMap<String, Float>()
        for ((key, element) in obj.entrySet()) {
            if (!validKey(key) || !isNumber(element)) return null
            val parsed = element.asDouble
            if (!parsed.isFinite() || abs(parsed) > 100_000.0) return null
            result[key] = parsed.toFloat()
        }
        return result
    }

    private fun readChoices(obj: JsonObject): Map<String, String>? {
        val result = HashMap<String, String>()
        for ((key, element) in obj.entrySet()) {
            if (!validKey(key) || !isString(element)) return null
            val value = element.asString
            if (value.length > 64) return null
            result[key] = value
        }
        return result
    }

    private fun readColors(obj: JsonObject): Map<String, DungeonRoomMapStyleCodec.Rgba>? {
        val result = HashMap<String, DungeonRoomMapStyleCodec.Rgba>()
        for ((key, element) in obj.entrySet()) {
            if (!validKey(key) || !element.isJsonArray) return null
            val values: JsonArray = element.asJsonArray
            if (values.size() != 4) return null
            val rgba = IntArray(4)
            for (index in 0 until 4) {
                val value = values[index]
                if (!isInteger(value)) return null
                rgba[index] = value.asInt
                if (rgba[index] !in 0..255) return null
            }
            result[key] = DungeonRoomMapStyleCodec.Rgba(rgba[0], rgba[1], rgba[2], rgba[3])
        }
        return result
    }

    private fun validKey(key: String?): Boolean = !key.isNullOrBlank() && key.length <= 80

    private fun stripJavaWhitespace(value: String): String = value.trim { Character.isWhitespace(it) }
    private fun isObject(element: JsonElement?): Boolean = element != null && element.isJsonObject
    private fun isString(element: JsonElement?): Boolean =
        element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString
    private fun isNumber(element: JsonElement?): Boolean =
        element != null && element.isJsonPrimitive && element.asJsonPrimitive.isNumber

    private fun isInteger(element: JsonElement?): Boolean {
        if (!isNumber(element)) return false
        return try {
            element!!.asBigDecimal.stripTrailingZeros().scale() <= 0
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun failure(error: String): DungeonRoomMapStyleCodec.DecodeResult =
        DungeonRoomMapStyleCodec.DecodeResult(false, null, error)
}
