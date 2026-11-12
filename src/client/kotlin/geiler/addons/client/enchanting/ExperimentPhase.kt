package geiler.addons.client.enchanting

import java.util.Locale

/** Server-visible phase of an experimentation add-on. */
enum class ExperimentPhase {
    IDLE,
    MEMORIZE,
    WAITING,
    SOLVE,
    ROUND_COMPLETE,
    COMPLETE;

    companion object {
        /** Exposes the same status normalization to the Minecraft/container adapter. */
        @JvmStatic
        fun detect(type: ExperimentType?, status: String?): ExperimentPhase {
            if (type == null) return IDLE
            val normalized = normalizeStatus(status)
            if (type == ExperimentType.SUPERPAIRS) {
                return if (isTerminalStatusNormalized(normalized)) COMPLETE else SOLVE
            }
            if (normalized.isEmpty()) return WAITING
            // Check the specific round boundary before the broader "complete" terminal wording.
            if (isRoundCompleteStatusNormalized(normalized)) return ROUND_COMPLETE
            if (isTerminalStatusNormalized(normalized)) return COMPLETE
            if (isRememberStatusNormalized(normalized)) return MEMORIZE
            if (isSolveStatusNormalized(normalized)) return SOLVE
            return WAITING
        }

        /** Returns a formatting-free, case-insensitive status suitable for matching. */
        @JvmStatic
        fun normalizeStatus(value: String?): String {
            if (value == null) return ""
            val result = StringBuilder(value.length)
            var formatting = false
            for (character in value) {
                if (formatting) {
                    formatting = false
                    continue
                }
                if (character == '\u00A7') {
                    formatting = true
                    continue
                }
                if (!Character.isISOControl(character)) result.append(character)
            }
            return result.toString().trim { it <= ' ' }.lowercase(Locale.ROOT)
        }

        /** Whether the status contains enough information to be trusted by the solver. */
        @JvmStatic
        fun isKnownStatus(type: ExperimentType?, status: String?): Boolean {
            val normalized = normalizeStatus(status)
            if (type == ExperimentType.SUPERPAIRS) return true
            if (normalized.isEmpty()) return false
            return isRoundCompleteStatusNormalized(normalized)
                || isTerminalStatusNormalized(normalized)
                || isRememberStatusNormalized(normalized)
                || isSolveStatusNormalized(normalized)
        }

        @JvmStatic
        fun isTimerStatus(status: String?): Boolean = isTimerStatusNormalized(normalizeStatus(status))

        @JvmStatic
        fun isRememberStatus(status: String?): Boolean = isRememberStatusNormalized(normalizeStatus(status))

        @JvmStatic
        fun isRoundCompleteStatus(status: String?): Boolean =
            isRoundCompleteStatusNormalized(normalizeStatus(status))

        @JvmStatic
        fun isTerminalStatus(status: String?): Boolean =
            isTerminalStatusNormalized(normalizeStatus(status))

        private fun isTimerStatusNormalized(normalized: String): Boolean = normalized.startsWith("timer:")

        private fun isRememberStatusNormalized(normalized: String): Boolean =
            normalized.startsWith("remember the pattern") || normalized.contains("watch the pattern")
                || normalized.contains("memorize")

        private fun isRoundCompleteStatusNormalized(normalized: String): Boolean {
            // Correct-click feedback can appear before the remaining sequence is complete.
            return normalized.startsWith("round complete")
        }

        private fun isTerminalStatusNormalized(normalized: String): Boolean {
            if (isRoundCompleteStatusNormalized(normalized)) return false
            return containsAny(
                normalized,
                "game over",
                "max clicks",
                "complete",
                "finished",
                "claim",
                "mistake",
                "wrong",
                "failed",
                "failure",
            )
        }

        private fun isSolveStatusNormalized(normalized: String): Boolean =
            isTimerStatusNormalized(normalized) || normalized.contains("click a second")
                || normalized.contains("click the") || normalized.contains("select the")
                || normalized.contains("next button") || normalized.contains("click a button")

        private fun containsAny(value: String, vararg needles: String): Boolean {
            for (needle in needles) if (value.contains(needle)) return true
            return false
        }
    }
}
