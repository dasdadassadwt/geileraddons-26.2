package geiler.addons.client.itemlist

import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.FloatTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.ShortTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

/**
 * Reads the SNBT of 1.8, as found in the NEU `nbttag` field. It differs from today's SNBT where it matters: list elements may
 * carry an index prefix (`Lore:[0:"a",1:"b"]`, `textures:[0:{...}]`), which the game's own parser rejects, and that made nearly
 * every item fail before. The same job as Skyblocker's `LegacyStringNbtReader`, written as a small recursive reader.
 * Throws [IllegalArgumentException] on malformed input.
 */
object LegacySnbt {
    private const val MAX_DEPTH = 32
    private val BYTE = Regex("[-+]?\\d+b", RegexOption.IGNORE_CASE)
    private val SHORT = Regex("[-+]?\\d+s", RegexOption.IGNORE_CASE)
    private val LONG = Regex("[-+]?\\d+l", RegexOption.IGNORE_CASE)
    private val FLOAT = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[-+]?\\d+)?f", RegexOption.IGNORE_CASE)
    private val DOUBLE = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:e[-+]?\\d+)?d", RegexOption.IGNORE_CASE)
    private val INT = Regex("[-+]?\\d+")
    private val DECIMAL = Regex("[-+]?(?:\\d+\\.\\d*|\\.\\d+)(?:e[-+]?\\d+)?", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun parse(text: String): CompoundTag {
        val reader = Reader(text)
        val tag = reader.compound(0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("trailing text")
        return tag
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(message: String): Nothing = throw IllegalArgumentException("$message at $i")

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char {
            skipWhitespace()
            return if (i < s.length) s[i] else '\u0000'
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("expected '$c'")
            i++
        }

        private fun unquoted(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "_-.+")) i++
            if (i == start) fail("expected a value")
            return s.substring(start, i)
        }

        private fun quoted(): String {
            val quote = s[i++]
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '\\' && i < s.length -> out.append(s[i++])
                    c == quote -> return out.toString()
                    else -> out.append(c)
                }
            }
            fail("unterminated string")
        }

        private fun string(): String = if (peek() == '"' || peek() == '\'') quoted() else unquoted()

        fun compound(depth: Int): CompoundTag {
            if (depth > MAX_DEPTH) fail("nested too deep")
            expect('{')
            val tag = CompoundTag()
            if (peek() == '}') {
                i++
                return tag
            }
            while (true) {
                val key = string()
                expect(':')
                tag.put(key, value(depth + 1))
                when (peek()) {
                    ',' -> {
                        i++
                        if (peek() == '}') {
                            i++
                            return tag
                        }
                    }
                    '}' -> {
                        i++
                        return tag
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun value(depth: Int): Tag = when (peek()) {
            '{' -> compound(depth)
            '[' -> list(depth)
            '"', '\'' -> StringTag.valueOf(quoted())
            else -> primitive(unquoted())
        }

        /** Whether the cursor is on `digits:` in front of a list element. */
        private fun indexPrefix(): Boolean {
            var j = i
            while (j < s.length && s[j].isDigit()) j++
            return j > i && j < s.length && s[j] == ':'
        }

        private fun list(depth: Int): Tag {
            if (depth > MAX_DEPTH) fail("nested too deep")
            expect('[')
            if (peek() in "BIL" && i + 1 < s.length && s[i + 1] == ';') return array(s[i].also { i += 2 })
            val list = ListTag()
            while (true) {
                if (peek() == ']') {
                    i++
                    return list
                }
                if (indexPrefix()) {
                    while (s[i] != ':') i++
                    i++
                }
                list.add(value(depth + 1))
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun array(kind: Char): Tag {
            val numbers = ArrayList<String>()
            while (peek() != ']') {
                numbers.add(unquoted())
                if (peek() == ',') i++
            }
            i++
            fun digits(text: String, suffix: Char) = text.removeSuffix(suffix.toString()).removeSuffix(suffix.lowercaseChar().toString())
            return when (kind) {
                'B' -> ByteArrayTag(ByteArray(numbers.size) { digits(numbers[it], 'B').toByte() })
                'I' -> IntArrayTag(IntArray(numbers.size) { numbers[it].toInt() })
                else -> LongArrayTag(LongArray(numbers.size) { digits(numbers[it], 'L').toLong() })
            }
        }

        private fun primitive(token: String): Tag = try {
            when {
                BYTE.matches(token) -> ByteTag.valueOf(token.dropLast(1).toByte())
                SHORT.matches(token) -> ShortTag.valueOf(token.dropLast(1).toShort())
                LONG.matches(token) -> LongTag.valueOf(token.dropLast(1).toLong())
                FLOAT.matches(token) -> FloatTag.valueOf(token.dropLast(1).toFloat())
                DOUBLE.matches(token) -> DoubleTag.valueOf(token.dropLast(1).toDouble())
                INT.matches(token) -> IntTag.valueOf(token.toInt())
                DECIMAL.matches(token) -> DoubleTag.valueOf(token.toDouble())
                token.equals("true", true) -> ByteTag.ONE
                token.equals("false", true) -> ByteTag.ZERO
                else -> StringTag.valueOf(token)
            }
        } catch (_: NumberFormatException) {
            StringTag.valueOf(token)
        }
    }
}
