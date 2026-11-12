package geiler.addons.client.utility

import java.util.OptionalLong
import java.util.regex.Pattern

/** Parses and formats timer durations in a normalized h/m/s form. */
class TimerDurationParser private constructor() {
    companion object {
        /** Keeps nanosecond arithmetic within a signed long for monotonic elapsed-time accounting. */
        @JvmField
        val MAX_SECONDS: Long = Long.MAX_VALUE / 1_000_000_000L

        private val PART = Pattern.compile("(\\d+)\\s*([hms])", Pattern.CASE_INSENSITIVE)

        /** Accepts forms such as `90s`, `1h 20m`, and `1h20m10s`. */
        @JvmStatic
        fun parseSeconds(input: String?): OptionalLong {
            if (input == null) return OptionalLong.empty()
            val text = input.trim { Character.isWhitespace(it) }
            if (text.isEmpty()) return OptionalLong.empty()

            if (Pattern.matches("\\d+", text)) {
                try {
                    val seconds = java.lang.Long.parseLong(text)
                    return if (seconds <= MAX_SECONDS) OptionalLong.of(seconds) else OptionalLong.empty()
                } catch (_: NumberFormatException) {
                    return OptionalLong.empty()
                }
            }

            val matcher = PART.matcher(text)
            var total = 0L
            var cursor = 0
            var found = false
            var hoursSeen = false
            var minutesSeen = false
            var secondsSeen = false
            while (matcher.find()) {
                if (!text.substring(cursor, matcher.start()).isBlank()) return OptionalLong.empty()
                found = true
                cursor = matcher.end()
                val amount = try {
                    java.lang.Long.parseLong(matcher.group(1))
                } catch (_: NumberFormatException) {
                    return OptionalLong.empty()
                }
                val unit = Character.toLowerCase(matcher.group(2)[0])
                val multiplier = when (unit) {
                    'h' -> {
                        if (hoursSeen) return OptionalLong.empty()
                        hoursSeen = true
                        3_600L
                    }
                    'm' -> {
                        if (minutesSeen) return OptionalLong.empty()
                        minutesSeen = true
                        60L
                    }
                    's' -> {
                        if (secondsSeen) return OptionalLong.empty()
                        secondsSeen = true
                        1L
                    }
                    else -> return OptionalLong.empty()
                }
                try {
                    total = Math.addExact(total, Math.multiplyExact(amount, multiplier))
                } catch (_: ArithmeticException) {
                    return OptionalLong.empty()
                }
                if (total > MAX_SECONDS) return OptionalLong.empty()
            }
            return if (found && text.substring(cursor).isBlank()) OptionalLong.of(total) else OptionalLong.empty()
        }

        /** Omits zero units and never formats an hour or minute count above its normal range. */
        @JvmStatic
        fun formatSeconds(totalSeconds: Long): String {
            val value = Math.max(0L, totalSeconds)
            val hours = value / 3_600L
            val minutes = value % 3_600L / 60L
            val seconds = value % 60L
            val result = StringBuilder()
            if (hours > 0L) result.append(hours).append('h')
            if (minutes > 0L) appendPart(result, minutes, 'm')
            if (seconds > 0L || result.length == 0) appendPart(result, seconds, 's')
            return result.toString()
        }

        @JvmStatic
        private fun appendPart(result: StringBuilder, amount: Long, unit: Char) {
            if (result.length != 0) result.append(' ')
            result.append(amount).append(unit)
        }
    }
}
