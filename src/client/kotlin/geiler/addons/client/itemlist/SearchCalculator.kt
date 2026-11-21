package geiler.addons.client.itemlist

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The search bar's calculator: `+ - * / ^ %`, parentheses, `k m b t` suffixes (1.5m, 2b, 3t) and `bz(x) lb(x) npc(x)` price
 * lookups by item id or name. Lenient on purpose: `x`, `X`, `×`, `*` all multiply, `÷` and `:` divide, `**` is a power, and a
 * missing closing parenthesis is allowed. Anything that is not a whole valid expression returns null, so ordinary searches never turn into maths.
 */
object SearchCalculator {
    fun interface Prices {
        /** The price of [arg] (an id or a name) for [function] (`bz`, `lb` or `npc`), or null when unknown. */
        fun price(function: String, arg: String): Double?
    }

    private val FUNCTIONS = setOf("bz", "lb", "npc")
    private const val OPERATORS = "*xX×·/÷:%"
    private const val MAX_LENGTH = 200
    private const val MAX_DEPTH = 40

    /** The value of [text], or null when it is not a calculation. A bare number is not one. */
    @JvmStatic
    fun evaluate(text: String, prices: Prices): Double? {
        val source = text.trim().replace("**", "^")
        if (source.isEmpty() || source.length > MAX_LENGTH) return null
        val parser = Parser(source, prices)
        val value = try {
            parser.expression(0)
        } catch (_: Failure) {
            return null
        }
        if (parser.pos != source.length || !parser.interesting || !value.isFinite()) return null
        return value
    }

    @JvmStatic
    fun format(value: Double): String {
        val pattern = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.ROOT))
        return pattern.format(value)
    }

    /** The number as plain digits without grouping, for the clipboard. */
    @JvmStatic
    fun plain(value: Double): String = BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private class Failure : RuntimeException(null, null, false, false)

    private class Parser(val s: String, val prices: Prices) {
        var pos = 0
        var interesting = false

        fun skip() {
            while (pos < s.length && s[pos] == ' ') pos++
        }

        fun peek(): Char {
            skip()
            return if (pos < s.length) s[pos] else '\u0000'
        }

        fun expression(depth: Int): Double {
            if (depth > MAX_DEPTH) throw Failure()
            var value = term(depth)
            while (true) {
                val c = peek()
                if (c != '+' && c != '-') return value
                pos++
                interesting = true
                val right = term(depth)
                value = if (c == '+') value + right else value - right
            }
        }

        fun term(depth: Int): Double {
            var value = power(depth)
            while (true) {
                val c = peek()
                if (c !in OPERATORS) return value
                pos++
                interesting = true
                val right = power(depth)
                if (c in "/÷:%" && right == 0.0) throw Failure()
                value = when (c) {
                    '/', '÷', ':' -> value / right
                    '%' -> value % right
                    else -> value * right
                }
            }
        }

        fun power(depth: Int): Double {
            val base = unary(depth)
            if (peek() != '^') return base
            pos++
            interesting = true
            return Math.pow(base, power(depth + 1))
        }

        fun unary(depth: Int): Double {
            if (peek() == '-') {
                pos++
                interesting = true
                return -unary(depth + 1)
            }
            return atom(depth)
        }

        fun atom(depth: Int): Double {
            val c = peek()
            if (c == '(') {
                pos++
                val value = expression(depth + 1)
                if (peek() == ')') pos++ else if (pos < s.length) throw Failure()
                return value
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter()) return function()
            throw Failure()
        }

        fun number(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == ',')) pos++
            var value = s.substring(start, pos).replace(",", "").toDoubleOrNull() ?: throw Failure()
            if (pos < s.length) {
                val factor = when (s[pos].lowercaseChar()) {
                    'k' -> 1e3
                    'm' -> 1e6
                    'b' -> 1e9
                    't' -> 1e12
                    else -> 1.0
                }
                if (factor != 1.0) {
                    value *= factor
                    pos++
                    interesting = true
                    if (pos < s.length && s[pos].isLetter() && s[pos] !in OPERATORS) throw Failure()
                }
            }
            return value
        }

        fun function(): Double {
            val start = pos
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos).lowercase()
            if (name !in FUNCTIONS || peek() != '(') throw Failure()
            pos++
            val argStart = pos
            var level = 1
            while (pos < s.length && level > 0) {
                if (s[pos] == '(') level++ else if (s[pos] == ')') level--
                pos++
            }
            if (level != 0) throw Failure()
            interesting = true
            return prices.price(name, s.substring(argStart, pos - 1).trim()) ?: throw Failure()
        }
    }
}
