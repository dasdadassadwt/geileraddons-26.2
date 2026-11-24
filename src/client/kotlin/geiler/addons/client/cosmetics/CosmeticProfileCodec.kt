package geiler.addons.client.cosmetics

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.regex.Pattern

/**
 * Reads hosted cosmetics from a player entry's `effect` object:
 * `"effect": {"cosmetics": {"wings": {"style": "angel", "color": "#FFFFFF"}, "halo": {}, "body": {"chest": 0.5}}}`.
 * `effect` is opaque to older mod versions, so adding cosmetics never breaks their profile parsing.
 * A bad piece is skipped and reported; the rest of the loadout still applies.
 */
object CosmeticProfileCodec {
    const val MAX_EFFECT_CHARS = 2 * 1024
    private val HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}")
    private val PIECE_KEYS = setOf("style", "color", "color2", "size", "up", "back", "speed")

    @JvmRecord
    data class Result(val loadout: CosmeticLoadout, val problems: List<String>)

    private val NONE = Result(CosmeticLoadout.EMPTY, emptyList())

    @JvmStatic
    fun parse(effectJson: String?): Result {
        if (effectJson.isNullOrBlank()) return NONE
        if (effectJson.length > MAX_EFFECT_CHARS) return Result(CosmeticLoadout.EMPTY, listOf("effect is too long"))
        val root = try {
            JsonParser.parseString(effectJson)
        } catch (_: RuntimeException) {
            return Result(CosmeticLoadout.EMPTY, listOf("effect is not valid JSON"))
        }
        if (!root.isJsonObject) return NONE
        val cosmetics = root.asJsonObject.get("cosmetics") ?: return NONE
        if (!cosmetics.isJsonObject) return Result(CosmeticLoadout.EMPTY, listOf("cosmetics must be an object"))
        val problems = ArrayList<String>()
        val pieces = ArrayList<CosmeticPiece>()
        var body: BodyShape? = null
        for ((key, value) in cosmetics.asJsonObject.entrySet()) {
            if (key == "body") {
                try {
                    body = body(value)
                } catch (invalid: IllegalArgumentException) {
                    problems.add("body: ${invalid.message}")
                }
                continue
            }
            val slot = CosmeticSlot.byId(key)
            if (slot == null) {
                problems.add("unknown slot $key")
                continue
            }
            if (!value.isJsonObject) {
                problems.add("$key must be an object")
                continue
            }
            try {
                pieces.add(piece(slot, value.asJsonObject))
            } catch (invalid: IllegalArgumentException) {
                problems.add("$key: ${invalid.message}")
            }
        }
        return Result(CosmeticLoadout.of(pieces, body), problems)
    }

    /** `"body": {"chest": 0.5, "waist": 0.3}`, both 0..1 and optional. */
    private fun body(value: JsonElement): BodyShape {
        require(value.isJsonObject) { "must be an object" }
        val json = value.asJsonObject
        for (key in json.keySet()) require(key == "chest" || key == "waist") { "unknown field $key" }
        return BodyShape(number(json.get("chest"), 0f, 1f, 0f, "chest"), number(json.get("waist"), 0f, 1f, 0f, "waist"))
    }

    private fun piece(slot: CosmeticSlot, json: JsonObject): CosmeticPiece {
        for (key in json.keySet()) require(key in PIECE_KEYS) { "unknown field $key" }
        val styles = slot.styles()
        val styleId = text(json.get("style"))
        val style = if (styleId == null) {
            require(styles.size == 1) { "style is required" }
            styles[0]
        } else {
            CosmeticStyle.byId(slot, styleId) ?: throw IllegalArgumentException("unknown style $styleId")
        }
        return CosmeticPiece(
            style,
            color(json.get("color")) ?: style.color,
            color(json.get("color2")) ?: style.color2,
            number(json.get("size"), CosmeticPiece.MIN_SIZE, CosmeticPiece.MAX_SIZE, slot.defaultSize, "size"),
            number(json.get("up"), -CosmeticPiece.MAX_OFFSET, CosmeticPiece.MAX_OFFSET, slot.defaultUp, "up"),
            number(json.get("back"), -CosmeticPiece.MAX_OFFSET, CosmeticPiece.MAX_OFFSET, slot.defaultBack, "back"),
            number(json.get("speed"), 0f, CosmeticPiece.MAX_SPEED, slot.defaultSpeed, "speed"),
        )
    }

    private fun text(value: JsonElement?): String? {
        if (value == null || value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "style must be text" }
        return value.asString
    }

    private fun color(value: JsonElement?): Int? {
        val text = text(value) ?: return null
        require(HEX_COLOR.matcher(text).matches()) { "color must be #RRGGBB" }
        return text.substring(1).toInt(16)
    }

    private fun number(value: JsonElement?, min: Float, max: Float, fallback: Float, field: String): Float {
        if (value == null || value.isJsonNull) return fallback
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$field must be a number" }
        val parsed = value.asDouble
        require(parsed.isFinite() && parsed >= min && parsed <= max) { "$field must be between $min and $max" }
        return parsed.toFloat()
    }
}
