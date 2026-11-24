package geiler.addons.client.rename

import com.google.gson.JsonObject

/**
 * One client-side item override. Text fields use `&` markup (`&6`, `&#RRGGBB`, `&l`, `&r`); an empty field changes nothing.
 * Plain fields (not a data class) so Gson can read and write it directly.
 */
class RenameOverride {
    var name = ""
    /** Hex stops like `FF0000,0000FF`, or `rainbow`; applied to name characters without an explicit colour. */
    var gradient = ""
    var bold = false
    var italic = false
    var underline = false
    var strike = false
    var obfuscated = false
    /** `minecraft:diamond_sword` or any item model id, `head:<player>`, or `texture:<base64>`. */
    var icon = ""
    /** Lore lines separated by `|`. */
    var lore = ""
    /** `Keep`, `Replace` or `Append`. */
    var loreMode = "Keep"
    /** Replaces the last lore line (the rarity line) when non-empty. */
    var rarityLine = ""
    /** Comma separated; lore lines containing any term are removed. */
    var hide = ""
    /** `Keep`, `On` or `Off`. */
    var glint = "Keep"
    /** Dye colour as RGB, or -1 for none. */
    var dye = -1
    /** When true the override applies to every item with the same SkyBlock id instead of one uuid. */
    var allOfType = false

    fun isEmpty(): Boolean = name.isEmpty() && gradient.isEmpty() && !bold && !italic && !underline && !strike &&
        !obfuscated && icon.isEmpty() && lore.isEmpty() && loreMode == "Keep" && rarityLine.isEmpty() && hide.isEmpty() &&
        glint == "Keep" && dye < 0

    fun copy(): RenameOverride = fromJson(toJson())

    fun toJson(): JsonObject {
        val o = JsonObject()
        o.addProperty("name", name)
        o.addProperty("gradient", gradient)
        o.addProperty("bold", bold)
        o.addProperty("italic", italic)
        o.addProperty("underline", underline)
        o.addProperty("strike", strike)
        o.addProperty("obfuscated", obfuscated)
        o.addProperty("icon", icon)
        o.addProperty("lore", lore)
        o.addProperty("loreMode", loreMode)
        o.addProperty("rarityLine", rarityLine)
        o.addProperty("hide", hide)
        o.addProperty("glint", glint)
        o.addProperty("dye", dye)
        o.addProperty("allOfType", allOfType)
        return o
    }

    companion object {
        private const val MAX_TEXT = 256
        private const val MAX_LORE = 4096

        /** Reads one override from JSON, bounding every field; wrong types fall back to the default. */
        @JvmStatic
        fun fromJson(o: JsonObject): RenameOverride {
            fun str(key: String, max: Int, default: String = ""): String {
                val e = o.get(key)
                return if (e != null && e.isJsonPrimitive) e.asString.take(max) else default
            }
            fun flag(key: String): Boolean {
                val e = o.get(key)
                return e != null && e.isJsonPrimitive && e.asJsonPrimitive.isBoolean && e.asBoolean
            }
            val r = RenameOverride()
            r.name = str("name", MAX_TEXT)
            r.gradient = str("gradient", MAX_TEXT)
            r.bold = flag("bold")
            r.italic = flag("italic")
            r.underline = flag("underline")
            r.strike = flag("strike")
            r.obfuscated = flag("obfuscated")
            r.icon = str("icon", 4096)
            r.lore = str("lore", MAX_LORE)
            r.loreMode = str("loreMode", 8, "Keep").let { if (it in LORE_MODES) it else "Keep" }
            r.rarityLine = str("rarityLine", MAX_TEXT)
            r.hide = str("hide", MAX_TEXT)
            r.glint = str("glint", 8, "Keep").let { if (it in GLINT_MODES) it else "Keep" }
            val dye = o.get("dye")
            r.dye = if (dye != null && dye.isJsonPrimitive && dye.asJsonPrimitive.isNumber) dye.asInt and 0xFFFFFF else -1
            r.allOfType = flag("allOfType")
            return r
        }

        val LORE_MODES = listOf("Keep", "Replace", "Append")
        val GLINT_MODES = listOf("Keep", "On", "Off")
    }
}
