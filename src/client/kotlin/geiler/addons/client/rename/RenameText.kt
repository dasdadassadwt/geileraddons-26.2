package geiler.addons.client.rename

import kotlin.math.roundToInt

/** Pure `&` markup parsing for Any Rename: colour runs, `&#RRGGBB`, formatting flags and gradients. No Minecraft types. */
object RenameText {
    class Seg(
        val text: String,
        val rgb: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val obfuscated: Boolean,
    )

    private val LEGACY = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )
    private val RAINBOW = intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF)

    private class Cell(val ch: Char, val rgb: Int, val flags: Int)

    private const val BOLD = 1
    private const val ITALIC = 2
    private const val UNDERLINE = 4
    private const val STRIKE = 8
    private const val OBFUSCATED = 16

    /** Parses [markup] into runs. [gradient] colours characters that have no explicit colour; the flags apply to every character. */
    @JvmStatic
    fun segments(
        markup: String,
        gradient: String,
        bold: Boolean,
        italic: Boolean,
        underline: Boolean,
        strike: Boolean,
        obfuscated: Boolean,
    ): List<Seg> {
        val forced = (if (bold) BOLD else 0) or (if (italic) ITALIC else 0) or (if (underline) UNDERLINE else 0) or
            (if (strike) STRIKE else 0) or (if (obfuscated) OBFUSCATED else 0)
        val cells = parse(markup)
        if (cells.isEmpty()) return emptyList()
        val stops = stops(gradient)
        val colourless = cells.count { it.rgb < 0 }
        var seen = 0
        val out = ArrayList<Seg>()
        var run = StringBuilder()
        var runRgb = 0
        var runFlags = 0
        fun flush() {
            if (run.isEmpty()) return
            out.add(
                Seg(
                    run.toString(), runRgb, runFlags and BOLD != 0, runFlags and ITALIC != 0, runFlags and UNDERLINE != 0,
                    runFlags and STRIKE != 0, runFlags and OBFUSCATED != 0,
                ),
            )
            run = StringBuilder()
        }
        for (cell in cells) {
            var rgb = cell.rgb
            var split = false
            if (rgb < 0 && stops.isNotEmpty()) {
                rgb = sample(stops, if (colourless <= 1) 0.0 else seen.toDouble() / (colourless - 1))
                split = true
            }
            if (cell.rgb < 0) seen++
            val flags = cell.flags or forced
            if (run.isNotEmpty() && (split || rgb != runRgb || flags != runFlags)) flush()
            if (run.isEmpty()) {
                runRgb = rgb
                runFlags = flags
            }
            run.append(cell.ch)
        }
        flush()
        return out
    }

    /** The visible text with every markup code removed. */
    @JvmStatic
    fun plain(markup: String): String = parse(markup).joinToString("") { it.ch.toString() }

    /** `RRGGBB` or `#RRGGBB` to an RGB int, or -1. */
    @JvmStatic
    fun parseHex(text: String): Int {
        val s = text.trim().removePrefix("#")
        if (s.length != 6 || s.any { Character.digit(it, 16) < 0 }) return -1
        return s.toInt(16)
    }

    private fun stops(gradient: String): IntArray {
        val g = gradient.trim()
        if (g.isEmpty()) return IntArray(0)
        if (g.equals("rainbow", ignoreCase = true)) return RAINBOW
        return g.split(',').map { parseHex(it) }.filter { it >= 0 }.toIntArray()
    }

    private fun sample(stops: IntArray, t: Double): Int {
        if (stops.size == 1) return stops[0]
        val scaled = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = minOf(scaled.toInt(), stops.size - 2)
        val f = scaled - i
        val a = stops[i]
        val b = stops[i + 1]
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * f).roundToInt()
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun parse(markup: String): List<Cell> {
        val cells = ArrayList<Cell>(markup.length)
        var rgb = -1
        var flags = 0
        var i = 0
        while (i < markup.length) {
            val c = markup[i]
            if ((c == '&' || c == '§') && i + 1 < markup.length) {
                val code = markup[i + 1].lowercaseChar()
                if (code == '#' && i + 8 <= markup.length &&parseHex(markup.substring(i + 2, i + 8)) >= 0) {
                    rgb = parseHex(markup.substring(i + 2, i + 8))
                    flags = 0
                    i += 8
                    continue
                }
                val digit = Character.digit(code, 16)
                if (digit >= 0) {
                    rgb = LEGACY[digit]
                    flags = 0
                    i += 2
                    continue
                }
                val flag = when (code) {
                    'l' -> BOLD
                    'o' -> ITALIC
                    'n' -> UNDERLINE
                    'm' -> STRIKE
                    'k' -> OBFUSCATED
                    else -> 0
                }
                if (flag != 0) {
                    flags = flags or flag
                    i += 2
                    continue
                }
                if (code == 'r') {
                    rgb = -1
                    flags = 0
                    i += 2
                    continue
                }
            }
            cells.add(Cell(c, rgb, flags))
            i++
        }
        return cells
    }
}
