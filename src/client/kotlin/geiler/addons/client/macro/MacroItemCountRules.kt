package geiler.addons.client.macro

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Pure item-id matching and carried-stack count rules used by macro values and offline checks. */
object MacroItemCountRules {
    private val ID_FIELD: Pattern = Pattern.compile("(?i)(?:^|[,{\\s])id\\s*[:=]\\s*[\\\"']?([a-z0-9_:-]+)")
    private const val CUSTOM_DATA_SCAN_LIMIT = 4096

    @JvmStatic
    fun count(stacks: Collection<StackIdentity?>?, wantedSkyBlockId: String?, wantedVanillaId: String?): Int {
        if (stacks == null) return 0
        var total = 0
        for (stack in stacks) {
            if (stack == null || stack.count <= 0 ||
                !matches(stack.skyBlockId, stack.vanillaId, wantedSkyBlockId, wantedVanillaId)
            ) continue
            total = minOf(Int.MAX_VALUE.toLong(), total.toLong() + stack.count).toInt()
        }
        return total
    }

    /** Prefer SkyBlock IDs when both sides have them; use the vanilla ID when either is absent. */
    @JvmStatic
    fun matches(
        actualSkyBlockId: String?,
        actualVanillaId: String?,
        wantedSkyBlockId: String?,
        wantedVanillaId: String?,
    ): Boolean {
        val wantedSkyBlock = normalize(actualSafe(wantedSkyBlockId))
        val wantedVanilla = normalize(actualSafe(wantedVanillaId))
        val actualSkyBlock = normalize(actualSafe(actualSkyBlockId))
        if (wantedSkyBlock.isNotEmpty() && actualSkyBlock.isNotEmpty()) {
            return actualSkyBlock == wantedSkyBlock
        }
        return wantedVanilla.isNotEmpty() && normalize(actualSafe(actualVanillaId)) == wantedVanilla
    }

    /** Extracts the item id only from the ExtraAttributes compound of serialized custom NBT. */
    @JvmStatic
    fun skyBlockIdFromCustomData(encoded: String?): String {
        if (encoded == null || MacroJavaStrings.isBlank(encoded)) return ""
        val bounded = if (encoded.length > CUSTOM_DATA_SCAN_LIMIT) encoded.substring(0, CUSTOM_DATA_SCAN_LIMIT) else encoded
        val attributes = bounded.lowercase(Locale.ROOT).indexOf("extraattributes")
        if (attributes < 0) return topLevelId(bounded)
        val end = bounded.indexOf('}', attributes)
        val region = bounded.substring(attributes, if (end < 0) bounded.length else end)
        val matcher: Matcher = ID_FIELD.matcher(region)
        return if (matcher.find()) matcher.group(1) else ""
    }

    /** 26.x Hypixel items carry `id` directly in custom data: `CustomData[{id:"X",...}]`. Only the outer compound counts. */
    private fun topLevelId(bounded: String): String {
        val start = bounded.indexOf('{')
        if (start < 0) return ""
        var depth = 0
        val top = StringBuilder()
        for (i in start until bounded.length) {
            val c = bounded[i]
            if (c == '{' || c == '[') depth++
            if (depth == 1) top.append(c)
            if (c == '}' || c == ']') depth--
            if (depth == 0) break
        }
        val matcher: Matcher = ID_FIELD.matcher(top)
        return if (matcher.find()) matcher.group(1) else ""
    }

    private fun actualSafe(value: String?): String = MacroJavaStrings.strip(value) ?: ""
    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    @JvmRecord
    data class StackIdentity(val skyBlockId: String?, val vanillaId: String?, val count: Int)
}
