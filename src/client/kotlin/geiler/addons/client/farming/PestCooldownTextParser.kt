package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale
import java.util.Optional
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Static-call-compatible cooldown parser and immutable observation value. */
object PestCooldownText {
        private val COOLDOWN: Pattern = Pattern.compile(
            "(?i)^Cooldown:\\s*(?:(?<minutes>\\d{1,2})m(?:\\s+(?<seconds>\\d{1,2})s?)?|(?<onlySeconds>\\d{1,2})s|(?<ready>READY)|(?<max>MAX PESTS))$",
        )

        @JvmStatic
        fun parse(orderedLines: List<String?>?): Optional<PestCooldownText.Observation> {
            if (orderedLines == null || orderedLines.isEmpty()) return Optional.empty()
            var header = -1
            for (index in orderedLines.indices) {
                val row = stripForMatch(orderedLines[index]).lowercase(Locale.ROOT)
                if (!row.startsWith("pests:")) continue
                if (header >= 0) return Optional.empty()
                header = index
            }
            if (header < 0) return Optional.empty()
            for (index in header + 1 until orderedLines.size) {
                val raw = orderedLines[index]
                val row = stripForMatch(raw).lowercase(Locale.ROOT)
                if (TabWidgetText.isOtherWidget(row)) break
                val matcher: Matcher = COOLDOWN.matcher(stripForMatch(raw))
                if (!matcher.matches()) continue
                if (matcher.group("ready") != null) {
                    return Optional.of(PestCooldownText.Observation(PestCooldownText.State.READY, 0))
                }
                if (matcher.group("max") != null) {
                    return Optional.of(PestCooldownText.Observation(PestCooldownText.State.MAX_PESTS, 0))
                }
                try {
                    val seconds = if (matcher.group("onlySeconds") != null) {
                        Integer.parseInt(matcher.group("onlySeconds"))
                    } else {
                        Integer.parseInt(matcher.group("minutes")) * 60 +
                            (matcher.group("seconds")?.let { Integer.parseInt(it) } ?: 0)
                    }
                    return Optional.of(
                        PestCooldownText.Observation(PestCooldownText.State.COUNTDOWN, maxOf(0, seconds)),
                    )
                } catch (_: NumberFormatException) {
                    return Optional.empty()
                }
            }
            return Optional.empty()
        }

    private fun stripForMatch(value: String?): String =
        ChatText.stripForMatch(value ?: throw NullPointerException())

    data class Observation(
        private var state: State?,
        private var remainingSeconds: Int,
    ) {
        init {
            state = state ?: State.UNKNOWN
            remainingSeconds = maxOf(0, remainingSeconds)
        }

        fun state(): State = checkNotNull(state)

        fun remainingSeconds(): Int = remainingSeconds

        override fun toString(): String =
            "Observation[state=${state()}, remainingSeconds=$remainingSeconds]"
    }

    enum class State { UNKNOWN, COUNTDOWN, READY, MAX_PESTS }
}
