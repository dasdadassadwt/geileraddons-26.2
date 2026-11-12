package geiler.addons.client.presentation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.regex.Pattern
import geiler.addons.client.chat.ChatStringSemantics

/** Kotlin implementation of the bounded, validated hosted player profile parser. */
object PlayerPresentationProfilesParser {
    private val PLAYER_NAME = ChatStringSemantics.MINECRAFT_USERNAME
    private val HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}")

    @JvmStatic
    fun find(players: Map<String, PlayerPresentationProfiles.Entry>, playerName: String?): PlayerPresentationProfiles.Entry? {
        if (playerName == null) return null
        return players[normalize(playerName)]
    }

    @JvmStatic
    fun hasAnimatedNameStyles(players: Map<String, PlayerPresentationProfiles.Entry>): Boolean =
        players.values.stream().anyMatch { entry -> entry.animation() != null && entry.hasNameStyle() }

    @JvmStatic
    @Throws(IOException::class)
    fun decodeUtf8(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty() || bytes.size > PlayerPresentationProfiles.MAX_JSON_BYTES) {
            throw IOException("Profile is empty or exceeds the byte limit")
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (invalidUtf8: CharacterCodingException) {
            throw IOException("Profile is not valid UTF-8", invalidUtf8)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun parse(json: String?): Map<String, PlayerPresentationProfiles.Entry> {
        if (json == null || json.isBlank() || json.length > PlayerPresentationProfiles.MAX_JSON_CHARS) {
            throw IOException("Profile is empty or exceeds the character limit")
        }
        preflightJson(json)
        try {
            JsonReader(StringReader(json)).use { reader ->
                reader.setLenient(false)
                // Our explicit depth counts the root value at zero; Gson counts the root container as one.
                reader.setNestingLimit(PlayerPresentationProfiles.MAX_JSON_DEPTH + 1)
                val parsed = JsonParser.parseReader(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw IOException("Profile contains trailing JSON data")
                }
                if (!parsed.isJsonObject) throw IOException("Profile root must be an object")
                val root = parsed.asJsonObject
                requireOnlyKeys(root, "schemaVersion", "players")
                if (!integer(root.get("schemaVersion"), PlayerPresentationProfiles.SCHEMA_VERSION)) {
                    throw IOException("Unsupported profile schema version")
                }
                val playersValue = root.get("players")
                if (playersValue == null || !playersValue.isJsonArray) {
                    throw IOException("Profile players must be an array")
                }
                val array = playersValue.asJsonArray
                if (array.size() > PlayerPresentationProfiles.MAX_PLAYERS) {
                    throw IOException("Profile contains too many players")
                }
                val entries = LinkedHashMap<String, PlayerPresentationProfiles.Entry>()
                for (value in array) {
                    if (!value.isJsonObject) throw IOException("Player entry must be an object")
                    val entry = parseEntry(value.asJsonObject)
                    if (entries.putIfAbsent(normalize(entry.playerName()!!), entry) != null) {
                        throw IOException("Profile contains a duplicate player name")
                    }
                }
                return entries
            }
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("Profile JSON is invalid", error)
        }
    }

    /** Walks every value/member without creating JsonElement nodes before Gson allocates the tree. */
    @Throws(IOException::class)
    private fun preflightJson(json: String) {
        JsonReader(StringReader(json)).use { reader ->
            reader.setLenient(false)
            reader.setNestingLimit(PlayerPresentationProfiles.MAX_JSON_DEPTH + 1)
            val budget = JsonBudget()
            preflightJsonValue(reader, 0, budget)
            budget.visitToken()
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw IOException("Profile contains trailing JSON data")
            }
        }
    }

    @Throws(IOException::class)
    private fun preflightJsonValue(reader: JsonReader, depth: Int, budget: JsonBudget) {
        if (depth > PlayerPresentationProfiles.MAX_JSON_DEPTH) {
            throw IOException("Profile JSON exceeds the nesting limit")
        }
        budget.visitNode()
        budget.visitToken()
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) preflightJsonValue(reader, depth + 1, budget)
                budget.visitToken()
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                val names = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    budget.visitToken()
                    val name = reader.nextName()
                    if (!names.add(name)) throw IOException("Profile contains a duplicate JSON member")
                    preflightJsonValue(reader, depth + 1, budget)
                }
                budget.visitToken()
                reader.endObject()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IOException("Profile contains an unexpected JSON token")
        }
    }

    private class JsonBudget {
        private var tokens = 0
        private var nodes = 0

        @Throws(IOException::class)
        fun visitToken() {
            tokens++
            if (tokens > PlayerPresentationProfiles.MAX_JSON_TOKENS) {
                throw IOException("Profile JSON exceeds the token limit")
            }
        }

        @Throws(IOException::class)
        fun visitNode() {
            nodes++
            if (nodes > PlayerPresentationProfiles.MAX_JSON_NODES) {
                throw IOException("Profile JSON exceeds the node limit")
            }
        }
    }

    @Throws(IOException::class)
    private fun parseEntry(jsonObject: JsonObject): PlayerPresentationProfiles.Entry {
        requireOnlyKeys(
            jsonObject,
            "name", "alias", "size", "sizeX", "sizeY", "sizeZ", "effect", "color", "gradient", "animation",
        )
        val name = string(jsonObject.get("name"), "name", true)!!
        if (!PLAYER_NAME.matcher(name).matches()) {
            throw IOException("Player name is not a Minecraft username")
        }
        val alias = string(jsonObject.get("alias"), "alias", false)
        if (alias != null && (alias.length > 64 || alias.isBlank() || hasControl(alias) || alias.indexOf('\u00a7') >= 0)) {
            throw IOException("Player alias is empty or contains unsupported characters")
        }
        var effectMetadata: String? = null
        val effect = jsonObject.get("effect")
        if (effect != null && !effect.isJsonNull) {
            effectMetadata = effect.toString()
            if (effectMetadata.length > PlayerPresentationProfiles.MAX_EFFECT_METADATA_CHARS) {
                throw IOException("Effect metadata exceeds the size limit")
            }
        }
        var sizeX: Float? = null
        var sizeY: Float? = null
        var sizeZ: Float? = null
        val scalarSize = jsonObject.has("size") && !jsonObject.get("size").isJsonNull
        val axisSize = jsonObject.has("sizeX") || jsonObject.has("sizeY") || jsonObject.has("sizeZ")
        if (scalarSize && axisSize) {
            throw IOException("Player size must use either size or sizeX/sizeY/sizeZ")
        }
        if (scalarSize) {
            sizeX = size(jsonObject.get("size"), "size")
            sizeY = sizeX
            sizeZ = sizeX
        } else if (axisSize) {
            if (!jsonObject.has("sizeX") || !jsonObject.has("sizeY") || !jsonObject.has("sizeZ")) {
                throw IOException("Player size requires all of sizeX, sizeY, and sizeZ")
            }
            sizeX = size(jsonObject.get("sizeX"), "sizeX")
            sizeY = size(jsonObject.get("sizeY"), "sizeY")
            sizeZ = size(jsonObject.get("sizeZ"), "sizeZ")
        }
        val color = color(jsonObject.get("color"), "color")
        val gradientValue = jsonObject.get("gradient")
        if (color != null && gradientValue != null && !gradientValue.isJsonNull) {
            throw IOException("Player color and gradient cannot both be set")
        }
        val gradient = gradient(gradientValue)
        val animation = animation(jsonObject.get("animation"))
        if (animation != null && gradient.isEmpty()) {
            throw IOException("Player animation requires a gradient")
        }
        return PlayerPresentationProfiles.Entry(
            name, alias, effectMetadata, sizeX, sizeY, sizeZ, color, gradient, animation,
        )
    }

    @Throws(IOException::class)
    private fun size(value: JsonElement?, field: String): Float {
        if (value == null || value.isJsonNull || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
            throw IOException("Player $field must be numeric")
        }
        val parsed = value.asDouble
        if (!parsed.isFinite() || parsed < 0.1 || parsed > 4.0) {
            throw IOException("Player $field must be between 0.1 and 4.0")
        }
        return parsed.toFloat()
    }

    @Throws(IOException::class)
    private fun color(value: JsonElement?, field: String): Int? {
        if (value == null) return null
        if (value.isJsonNull) throw IOException("Player $field cannot be null")
        val text = string(value, field, false)
            ?: throw IOException("Player $field must be text")
        if (!HEX_COLOR.matcher(text).matches()) {
            throw IOException("Player $field must be a six-digit #RRGGBB color")
        }
        try {
            return text.substring(1).toInt(16)
        } catch (invalid: NumberFormatException) {
            throw IOException("Player $field is invalid", invalid)
        }
    }

    @Throws(IOException::class)
    private fun gradient(value: JsonElement?): List<Int> {
        if (value == null) return emptyList()
        if (value.isJsonNull) throw IOException("Player gradient cannot be null")
        if (!value.isJsonArray) throw IOException("Player gradient must be an array of colors")
        val array: JsonArray = value.asJsonArray
        if (array.size() < PlayerPresentationProfiles.MIN_GRADIENT_STOPS ||
            array.size() > PlayerPresentationProfiles.MAX_GRADIENT_STOPS
        ) {
            throw IOException("Player gradient must have 2 to 8 colors")
        }
        val colors = ArrayList<Int>(array.size())
        for (stop in array) {
            val parsed = color(stop, "gradient stop")
                ?: throw IOException("Player gradient stops cannot be null")
            colors.add(parsed)
        }
        return java.util.List.copyOf(colors)
    }

    @Throws(IOException::class)
    private fun animation(value: JsonElement?): PlayerPresentationProfiles.Animation? {
        if (value == null) return null
        if (value.isJsonNull) throw IOException("Player animation cannot be null")
        if (!value.isJsonObject) throw IOException("Player animation must be an object")
        val jsonObject = value.asJsonObject
        requireOnlyKeys(jsonObject, "mode", "speed")
        val mode = string(jsonObject.get("mode"), "animation mode", true)!!
        if (mode != "scroll") throw IOException("Player animation mode must be scroll")
        var speed = 0.25
        val speedValue = jsonObject.get("speed")
        if (speedValue != null) {
            if (speedValue.isJsonNull) throw IOException("Player animation speed cannot be null")
            if (!speedValue.isJsonPrimitive || !speedValue.asJsonPrimitive.isNumber) {
                throw IOException("Player animation speed must be numeric")
            }
            speed = speedValue.asDouble
        }
        if (!speed.isFinite() || speed < PlayerPresentationProfiles.MIN_ANIMATION_SPEED ||
            speed > PlayerPresentationProfiles.MAX_ANIMATION_SPEED
        ) {
            throw IOException("Player animation speed must be between 0.05 and 2.0 cycles per second")
        }
        return PlayerPresentationProfiles.Animation(speed.toFloat())
    }

    private fun integer(value: JsonElement?, expected: Int): Boolean {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return try {
            value.asInt == expected && value.asDouble == expected.toDouble()
        } catch (_: RuntimeException) {
            false
        }
    }

    @Throws(IOException::class)
    private fun string(value: JsonElement?, field: String, required: Boolean): String? {
        if (value == null || value.isJsonNull) {
            if (required) throw IOException("Player entry is missing $field")
            return null
        }
        if (value !is JsonPrimitive || !value.isString) throw IOException("Player $field must be text")
        return value.asString
    }

    @Throws(IOException::class)
    private fun requireOnlyKeys(jsonObject: JsonObject, vararg supported: String) {
        for (key in jsonObject.keySet()) {
            var known = false
            for (candidate in supported) {
                if (candidate == key) {
                    known = true
                    break
                }
            }
            if (!known) throw IOException("Profile contains an unsupported field: $key")
        }
    }

    private fun hasControl(text: String): Boolean {
        for (character in text) {
            if (Character.isISOControl(character)) return true
        }
        return false
    }

    private fun normalize(name: String): String = name.lowercase(Locale.ROOT)
}
