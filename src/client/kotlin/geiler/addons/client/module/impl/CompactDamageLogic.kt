package geiler.addons.client.module.impl

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import java.util.Locale
import java.util.regex.Pattern

/**
 * Damage number shortening for Compact Damage. The rules, the precision handling and the damage-name pattern follow
 * Skyblocker's `CompactDamage` (LGPL-3.0) and its unit tests; the code is written independently.
 */
object CompactDamageLogic {
    /** Group 1: crit symbol. Group 2: trailing symbol (Ring of Love, Tara armor, End Stone Sword, Voodoo Doll, Guardian pet). */
    private val DAMAGE = Pattern.compile("([✧✯]?)[\\d,]+[✧✯]?([❤+⚔☄♞]?)")
    private val POWERS = LongArray(19).also { p ->
        p[0] = 1L
        for (i in 1 until p.size) p[i] = p[i - 1] * 10L
    }

    /** `floor(log10(x)) + 1`, 0 for non-positive values. */
    @JvmStatic
    fun baseTenDigits(x: Long): Int = if (x <= 0L) 0 else x.toString().length

    /** Formats [number] with [precision] significant digits, rounding whole digits away when it has more. */
    @JvmStatic
    fun formatToPrecision(number: Double, precision: Int): String {
        val used = baseTenDigits(number.toLong())
        val remaining = precision - used
        if (remaining <= 0) {
            val power = POWERS[used - precision]
            return (Math.round(number / power) * power).toString()
        }
        return String.format(Locale.ROOT, "%.${remaining}f", number)
    }

    @JvmStatic
    fun prettifyDamageNumber(damage: Long, maxPrecision: Int): String {
        var target = damage
        var precision = maxPrecision
        val used = baseTenDigits(target)
        if (used > precision) {
            val power = POWERS[used - maxPrecision].toDouble()
            target = (Math.round(target.toDouble() / power) * power).toLong()
        } else if (precision > used) {
            precision = used
        }
        return when {
            target < 1_000L -> target.toString()
            target < 1_000_000L -> formatToPrecision(target / 1_000.0, precision) + "k"
            target < 1_000_000_000L -> formatToPrecision(target / 1_000_000.0, precision) + "M"
            target < 1_000_000_000_000L -> formatToPrecision(target / 1_000_000_000.0, precision) + "B"
            target < 1_000_000_000_000_000L -> formatToPrecision(target / 1_000_000_000_000.0, precision) + "T"
            else -> formatToPrecision(target / 1_000_000_000_000_000.0, precision) + "Q"
        }
    }

    /**
     * Rewrites a damage armor stand name, or returns null when [name] is not a damage number.
     * Colors are 0xRRGGBB. Normal hits keep their own color unless it is gray.
     */
    @JvmStatic
    fun compact(name: Component, maxPrecision: Int, normalColor: Int, gradientStart: Int, gradientEnd: Int): Component? {
        val text = name.string
        val matcher = DAMAGE.matcher(text)
        if (!matcher.matches()) return null
        val siblings = name.siblings
        if (siblings.isEmpty()) return null
        val crit = matcher.group(1).isNotEmpty()
        val digits = text.replace(Regex("\\D"), "")
        val damage = digits.toLongOrNull() ?: return null
        val sourceColor = siblings.first().style.color

        var result: MutableComponent = Component.empty()
        var pretty = prettifyDamageNumber(damage, maxPrecision)
        if (crit) {
            val symbol = matcher.group(1)
            pretty = symbol + pretty + symbol
            val length = pretty.length
            for (i in 0 until length) {
                result.append(
                    Component.literal(pretty.substring(i, i + 1))
                        .withColor(OkLab.interpolate(gradientStart and 0xFFFFFF, gradientEnd and 0xFFFFFF, i / (length - 1.0f))),
                )
            }
            result.setStyle(name.style)
        } else {
            val color = if (sourceColor == null || sourceColor == TextColor.fromLegacyFormat(ChatFormatting.GRAY)) {
                normalColor and 0xFFFFFF
            } else sourceColor.value
            result = Component.literal(pretty).setStyle(name.style).withColor(color)
        }
        if (matcher.group(2).isNotEmpty()) {
            result.append(Component.literal(matcher.group(2)).setStyle(siblings.last().style))
        }
        return result
    }
}

/** Color interpolation in the OkLab space (constants from Björn Ottosson's published OkLab definition). */
object OkLab {
    private fun linearize(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun delinearize(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else (1.055 * Math.pow(c.toDouble(), 1.0 / 2.4) - 0.055).toFloat()

    private fun toLab(rgb: Int): FloatArray {
        val r = linearize(((rgb shr 16) and 0xFF) / 255f)
        val g = linearize(((rgb shr 8) and 0xFF) / 255f)
        val b = linearize((rgb and 0xFF) / 255f)
        val l = Math.cbrt((0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b).toDouble()).toFloat()
        val m = Math.cbrt((0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b).toDouble()).toFloat()
        val s = Math.cbrt((0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b).toDouble()).toFloat()
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** [progress] 0 gives [first], 1 gives [second]; both and the result are 0xRRGGBB. */
    @JvmStatic
    fun interpolate(first: Int, second: Int, progress: Float): Int {
        val a = toLab(first)
        val b = toLab(second)
        val lightness = a[0] + progress * (b[0] - a[0])
        val aa = a[1] + progress * (b[1] - a[1])
        val bb = a[2] + progress * (b[2] - a[2])
        val l = cube(lightness + 0.3963377774f * aa + 0.2158037573f * bb)
        val m = cube(lightness - 0.1055613458f * aa - 0.0638541728f * bb)
        val s = cube(lightness - 0.0894841775f * aa - 1.2914855480f * bb)
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(bl)
    }

    private fun cube(v: Float) = v * v * v

    private fun channel(linear: Float): Int = Math.round((delinearize(linear) * 255f).coerceIn(0f, 255f))
}
