package geiler.addons.client.chat

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.ArrayList
import java.util.Locale

/** Bounded byte scanner for locating retired JSON members without reserializing user data. */
internal object RetiredChatConfigScanner {
    private const val IO_BUFFER_BYTES = 8 * 1024
    private const val MAX_KEY_CHARS = 256

    @JvmStatic
    @Throws(IOException::class)
    fun scan(input: FileChannel, size: Long): RetiredChatConfigRedactor.ScanResult {
        val scanner = Scanner(input, size)
        return try {
            val sourceJsonValid = scanner.sourceIsValidJson()
            RetiredChatConfigRedactor.ScanResult(sourceJsonValid, scanner.findRetiredProperties(), null)
        } catch (unsafe: UnsafeJsonException) {
            RetiredChatConfigRedactor.ScanResult(false, emptyList(), unsafe.message)
        }
    }

    private class Scanner(private val input: FileChannel, private val size: Long) {
        private val window = ByteArray(IO_BUFFER_BYTES)
        private var windowStart = -1L
        private var windowLength = 0
        private var tokens = 0
        private var validationTokens = 0
        private var depth = 0

        /** Validate original JSON syntax independently from the recoverable property scan. */
        @Throws(IOException::class, UnsafeJsonException::class)
        fun sourceIsValidJson(): Boolean {
            return try {
                val end = parseValue(skipWhitespace(0), 0)
                skipWhitespace(end) == size
            } catch (_: InvalidJsonException) {
                false
            }
        }

        @Throws(IOException::class, UnsafeJsonException::class, InvalidJsonException::class)
        private fun parseValue(position: Long, parentDepth: Int): Long {
            if (position >= size) throw InvalidJsonException()
            val current = byteAt(position)
            if (current == '{'.code) return parseObject(position, parentDepth)
            if (current == '['.code) return parseArray(position, parentDepth)
            if (current == '"'.code) {
                countValidationToken()
                return readString(position, false).end
            }
            countValidationToken()
            return when (current) {
                't'.code -> parseLiteral(position, "true")
                'f'.code -> parseLiteral(position, "false")
                'n'.code -> parseLiteral(position, "null")
                else -> parseNumber(position)
            }
        }

        @Throws(IOException::class, UnsafeJsonException::class, InvalidJsonException::class)
        private fun parseObject(initialPosition: Long, parentDepth: Int): Long {
            val objectDepth = parentDepth + 1
            if (objectDepth > RetiredChatConfigRedactor.MAX_DEPTH) {
                throw UnsafeJsonException("the JSON nesting exceeds the 64-level cleanup limit")
            }
            countValidationToken()
            var position = skipWhitespace(initialPosition + 1)
            if (position < size && byteAt(position) == '}'.code) {
                countValidationToken()
                return position + 1
            }
            while (position < size) {
                if (byteAt(position) != '"'.code) throw InvalidJsonException()
                countValidationToken()
                position = readString(position, false).end
                position = skipWhitespace(position)
                if (position >= size || byteAt(position) != ':'.code) throw InvalidJsonException()
                countValidationToken()
                position = parseValue(skipWhitespace(position + 1), objectDepth)
                position = skipWhitespace(position)
                if (position >= size) throw InvalidJsonException()
                when (byteAt(position)) {
                    '}'.code -> {
                        countValidationToken()
                        return position + 1
                    }
                    ','.code -> {
                        countValidationToken()
                        position = skipWhitespace(position + 1)
                    }
                    else -> throw InvalidJsonException()
                }
            }
            throw InvalidJsonException()
        }

        @Throws(IOException::class, UnsafeJsonException::class, InvalidJsonException::class)
        private fun parseArray(initialPosition: Long, parentDepth: Int): Long {
            val arrayDepth = parentDepth + 1
            if (arrayDepth > RetiredChatConfigRedactor.MAX_DEPTH) {
                throw UnsafeJsonException("the JSON nesting exceeds the 64-level cleanup limit")
            }
            countValidationToken()
            var position = skipWhitespace(initialPosition + 1)
            if (position < size && byteAt(position) == ']'.code) {
                countValidationToken()
                return position + 1
            }
            while (position < size) {
                position = parseValue(position, arrayDepth)
                position = skipWhitespace(position)
                if (position >= size) throw InvalidJsonException()
                when (byteAt(position)) {
                    ']'.code -> {
                        countValidationToken()
                        return position + 1
                    }
                    ','.code -> {
                        countValidationToken()
                        position = skipWhitespace(position + 1)
                    }
                    else -> throw InvalidJsonException()
                }
            }
            throw InvalidJsonException()
        }

        @Throws(IOException::class, InvalidJsonException::class)
        private fun parseLiteral(position: Long, literal: String): Long {
            for (index in literal.indices) {
                if (position + index >= size || byteAt(position + index) != literal[index].code) {
                    throw InvalidJsonException()
                }
            }
            return position + literal.length
        }

        @Throws(IOException::class, InvalidJsonException::class)
        private fun parseNumber(start: Long): Long {
            var position = start
            if (byteAt(position) == '-'.code) position++
            if (position >= size) throw InvalidJsonException()
            val digit = byteAt(position)
            if (digit == '0'.code) {
                position++
                if (position < size && isDigit(byteAt(position))) throw InvalidJsonException()
            } else if (digit in '1'.code..'9'.code) {
                do {
                    position++
                } while (position < size && isDigit(byteAt(position)))
            } else {
                throw InvalidJsonException()
            }
            if (position < size && byteAt(position) == '.'.code) {
                position++
                val fractionStart = position
                while (position < size && isDigit(byteAt(position))) position++
                if (position == fractionStart) throw InvalidJsonException()
            }
            if (position < size && (byteAt(position) == 'e'.code || byteAt(position) == 'E'.code)) {
                position++
                if (position < size && (byteAt(position) == '+'.code || byteAt(position) == '-'.code)) position++
                val exponentStart = position
                while (position < size && isDigit(byteAt(position))) position++
                if (position == exponentStart) throw InvalidJsonException()
            }
            return position
        }

        @Throws(UnsafeJsonException::class)
        private fun countValidationToken() {
            if (++validationTokens > RetiredChatConfigRedactor.MAX_TOKENS) {
                throw UnsafeJsonException("the JSON exceeds the 200,000-token cleanup limit")
            }
        }

        @Throws(IOException::class, UnsafeJsonException::class)
        fun findRetiredProperties(): List<RetiredChatConfigRedactor.Range> {
            val removed = ArrayList<RetiredChatConfigRedactor.Range>()
            var position = 0L
            var lastRemovedEnd = 0L
            while (position < size) {
                val current = byteAt(position)
                if (isWhitespace(current)) {
                    position++
                    continue
                }
                if (current == '"'.code) {
                    countToken()
                    val string = readString(position, true)
                    val colon = skipWhitespace(string.end)
                    if (colon < size && byteAt(colon) == ':'.code && isRetired(string.key)) {
                        countToken()
                        val value = scanValue(skipWhitespace(colon + 1), depth)
                        var removeStart = position
                        var removeEnd = value.end
                        val next = skipWhitespace(removeEnd)
                        if (next < size && byteAt(next) == ','.code) {
                            removeEnd = next + 1
                            countToken()
                        } else {
                            val previous = skipWhitespaceBackwards(position - 1)
                            if (previous >= 0 && byteAt(previous) == ','.code) removeStart = previous
                        }
                        if (removeStart < lastRemovedEnd && removed.isNotEmpty()) {
                            // A final retired property can need the comma already consumed by the preceding
                            // retired property. Merge the run and remove its separator from the last survivor.
                            val previousIndex = removed.lastIndex
                            val previousRange = removed[previousIndex]
                            var runStart = previousRange.start
                            val separator = skipWhitespaceBackwards(runStart - 1)
                            if (separator >= 0 && byteAt(separator) == ','.code) runStart = separator
                            val merged = RetiredChatConfigRedactor.Range(
                                runStart,
                                maxOf(previousRange.end, removeEnd),
                            )
                            removed[previousIndex] = merged
                            lastRemovedEnd = merged.end
                        } else if (removeEnd > removeStart) {
                            removed.add(RetiredChatConfigRedactor.Range(removeStart, removeEnd))
                            lastRemovedEnd = removeEnd
                        }
                        position = maxOf(value.end, removeEnd)
                        continue
                    }
                    position = string.end
                    continue
                }
                if (current == '{'.code || current == '['.code) {
                    countToken()
                    if (++depth > RetiredChatConfigRedactor.MAX_DEPTH) {
                        throw UnsafeJsonException("the JSON nesting exceeds the 64-level cleanup limit")
                    }
                    position++
                    continue
                }
                if (current == '}'.code || current == ']'.code) {
                    countToken()
                    depth = maxOf(0, depth - 1)
                    position++
                    continue
                }
                if (current == ','.code || current == ':'.code) {
                    countToken()
                    position++
                    continue
                }
                countToken()
                position = scanPrimitive(position)
            }
            return java.util.List.copyOf(removed)
        }

        @Throws(IOException::class, UnsafeJsonException::class)
        private fun scanValue(start: Long, baseDepth: Int): ValueEnd {
            if (start >= size) throw UnsafeJsonException("a retired property has no complete value")
            val first = byteAt(start)
            if (first == '"'.code) {
                countToken()
                return ValueEnd(readString(start, false).end)
            }
            if (first == '{'.code || first == '['.code) return ValueEnd(scanContainer(start, baseDepth))
            countToken()
            return ValueEnd(scanPrimitiveValue(start))
        }

        @Throws(IOException::class, UnsafeJsonException::class)
        private fun scanContainer(start: Long, baseDepth: Int): Long {
            val stack = ByteArray(RetiredChatConfigRedactor.MAX_DEPTH)
            var nesting = 1
            val first = byteAt(start)
            stack[0] = if (first == '{'.code) '}'.code.toByte() else ']'.code.toByte()
            countToken()
            if (baseDepth + nesting > RetiredChatConfigRedactor.MAX_DEPTH) {
                throw UnsafeJsonException("the JSON nesting exceeds the 64-level cleanup limit")
            }
            var position = start + 1
            while (position < size) {
                val current = byteAt(position)
                if (isWhitespace(current)) {
                    position++
                    continue
                }
                if (current == '"'.code) {
                    countToken()
                    position = readString(position, false).end
                    continue
                }
                if (current == '{'.code || current == '['.code) {
                    countToken()
                    if (nesting >= stack.size || baseDepth + nesting + 1 > RetiredChatConfigRedactor.MAX_DEPTH) {
                        throw UnsafeJsonException("the JSON nesting exceeds the 64-level cleanup limit")
                    }
                    stack[nesting++] = if (current == '{'.code) '}'.code.toByte() else ']'.code.toByte()
                    position++
                    continue
                }
                if (current == '}'.code || current == ']'.code) {
                    countToken()
                    if (stack[nesting - 1].toInt() != current) {
                        throw UnsafeJsonException("a retired property contains mismatched JSON delimiters")
                    }
                    if (--nesting == 0) return position + 1
                    position++
                    continue
                }
                if (current == ','.code || current == ':'.code) {
                    countToken()
                    position++
                    continue
                }
                countToken()
                position = scanPrimitive(position)
            }
            throw UnsafeJsonException("a retired property contains an incomplete JSON object or array")
        }

        @Throws(IOException::class)
        private fun scanPrimitive(start: Long): Long {
            var position = start
            while (position < size) {
                val value = byteAt(position)
                if (isWhitespace(value) || value == ','.code || value == ']'.code ||
                    value == '}'.code || value == '"'.code
                ) break
                position++
            }
            return position
        }

        /** Validate a target scalar before deleting it; malformed tokens have no safe value boundary. */
        @Throws(IOException::class, UnsafeJsonException::class)
        private fun scanPrimitiveValue(start: Long): Long {
            val first = byteAt(start)
            val end = when (first) {
                't'.code -> matchLiteral(start, "true")
                'f'.code -> matchLiteral(start, "false")
                'n'.code -> matchLiteral(start, "null")
                else -> scanNumber(start)
            }
            if (end < size) {
                val following = byteAt(end)
                if (!isWhitespace(following) && following != ','.code &&
                    following != ']'.code && following != '}'.code
                ) throw UnsafeJsonException("a retired property has an ambiguous scalar boundary")
            }
            return end
        }

        @Throws(IOException::class, UnsafeJsonException::class)
        private fun matchLiteral(start: Long, literal: String): Long {
            for (index in literal.indices) {
                if (start + index >= size || byteAt(start + index) != literal[index].code) {
                    throw UnsafeJsonException("a retired property has an invalid scalar value")
                }
            }
            return start + literal.length
        }

        @Throws(IOException::class, UnsafeJsonException::class)
        private fun scanNumber(start: Long): Long {
            var position = start
            if (byteAt(position) == '-'.code) position++
            if (position >= size) throw UnsafeJsonException("a retired property has an incomplete number")
            val digit = byteAt(position)
            if (digit == '0'.code) {
                position++
                if (position < size && isDigit(byteAt(position))) {
                    throw UnsafeJsonException("a retired property has an invalid number")
                }
            } else if (digit in '1'.code..'9'.code) {
                do {
                    position++
                } while (position < size && isDigit(byteAt(position)))
            } else {
                throw UnsafeJsonException("a retired property has an invalid scalar value")
            }
            if (position < size && byteAt(position) == '.'.code) {
                position++
                val fractionStart = position
                while (position < size && isDigit(byteAt(position))) position++
                if (position == fractionStart) throw UnsafeJsonException("a retired property has an incomplete number")
            }
            if (position < size && (byteAt(position) == 'e'.code || byteAt(position) == 'E'.code)) {
                position++
                if (position < size && (byteAt(position) == '+'.code || byteAt(position) == '-'.code)) position++
                val exponentStart = position
                while (position < size && isDigit(byteAt(position))) position++
                if (position == exponentStart) throw UnsafeJsonException("a retired property has an incomplete number")
            }
            return position
        }

        private fun readString(start: Long, decodeKey: Boolean): StringToken {
            var decoded: StringBuilder? = if (decodeKey) StringBuilder() else null
            var keyTooLong = false
            var position = start + 1
            while (position < size) {
                val value = byteAt(position++ )
                if (value == '"'.code) {
                    return StringToken(position, if (keyTooLong) null else decoded?.toString())
                }
                if (value < 0x20) throw UnsafeJsonException("the chat settings JSON contains an incomplete string token")
                if (value == '\\'.code) {
                    if (position >= size) break
                    val escaped = byteAt(position++)
                    val character = when (escaped) {
                        '"'.code -> '"'
                        '\\'.code -> '\\'
                        '/'.code -> '/'
                        'b'.code -> '\b'
                        'f'.code -> '\u000c'
                        'n'.code -> '\n'
                        'r'.code -> '\r'
                        't'.code -> '\t'
                        'u'.code -> {
                            if (position + 4 > size) {
                                throw UnsafeJsonException("the chat settings JSON contains an incomplete escape")
                            }
                            var code = 0
                            repeat(4) {
                                val digit = Character.digit(byteAt(position++).toChar(), 16)
                                if (digit < 0) throw UnsafeJsonException("the chat settings JSON contains an invalid escape")
                                code = (code shl 4) or digit
                            }
                            code.toChar()
                        }
                        else -> throw UnsafeJsonException("the chat settings JSON contains an invalid escape")
                    }
                    if (decoded != null && !keyTooLong) {
                        decoded.append(character)
                        if (decoded.length > MAX_KEY_CHARS) keyTooLong = true
                    }
                    continue
                }
                if (decoded != null && !keyTooLong) {
                    if (value >= 0x80) {
                        decoded = null
                        continue
                    }
                    decoded.append(value.toChar())
                    if (decoded.length > MAX_KEY_CHARS) keyTooLong = true
                }
            }
            throw UnsafeJsonException("the chat settings JSON contains an incomplete string token")
        }

        @Throws(IOException::class)
        private fun skipWhitespace(position: Long): Long {
            var cursor = position
            while (cursor < size && isWhitespace(byteAt(cursor))) cursor++
            return cursor
        }

        @Throws(IOException::class)
        private fun skipWhitespaceBackwards(position: Long): Long {
            var cursor = position
            while (cursor >= 0 && isWhitespace(byteAt(cursor))) cursor--
            return cursor
        }

        @Throws(IOException::class)
        private fun byteAt(position: Long): Int {
            if (position < 0 || position >= size) return -1
            if (windowStart >= 0 && position >= windowStart && position < windowStart + windowLength) {
                return window[(position - windowStart).toInt()].toInt() and 0xff
            }
            windowStart = position
            windowLength = 0
            val target = ByteBuffer.wrap(window)
            while (target.hasRemaining()) {
                val read = input.read(target, windowStart + target.position())
                if (read < 0) break
                if (read == 0) continue
            }
            windowLength = target.position()
            return if (windowLength == 0) -1 else window[0].toInt() and 0xff
        }

        @Throws(UnsafeJsonException::class)
        private fun countToken() {
            if (++tokens > RetiredChatConfigRedactor.MAX_TOKENS) {
                throw UnsafeJsonException("the JSON exceeds the 200,000-token cleanup limit")
            }
        }
    }

    private fun isRetired(key: String?): Boolean {
        if (key == null) return false
        val normalized = key.lowercase(Locale.ROOT)
        return normalized == "azurekey" || normalized.contains("translat")
    }

    private fun isWhitespace(value: Int): Boolean =
        value == ' '.code || value == '\t'.code || value == '\r'.code || value == '\n'.code

    private fun isDigit(value: Int): Boolean = value in '0'.code..'9'.code

    private data class StringToken(val end: Long, val key: String?)
    private data class ValueEnd(val end: Long)

    private class InvalidJsonException : Exception()
    private class UnsafeJsonException(message: String) : Exception(message)
}
