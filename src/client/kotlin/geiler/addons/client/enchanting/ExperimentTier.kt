package geiler.addons.client.enchanting

import java.util.Locale
import java.util.Optional

/** Experiment difficulty and the server's bonus-click thresholds. */
enum class ExperimentTier(
    private val label: String,
    private val chronomatronThresholdValue: Int,
    private val ultrasequencerThresholdValue: Int,
    private val maximumExtraClicksValue: Int,
) {
    BEGINNER("Beginner", -1, -1, 0),
    UNKNOWN("Unknown", -1, -1, 0),
    HIGH("High", 9, 7, 2),
    GRAND("Grand", 9, 7, 2),
    SUPREME("Supreme", 9, 7, 2),
    TRANSCENDENT("Transcendent", 9, 7, 2),
    METAPHYSICAL("Metaphysical", 12, 9, 3);

    fun displayName(): String = label

    fun chronomatronThreshold(): Int = chronomatronThresholdValue

    fun ultrasequencerThreshold(): Int = ultrasequencerThresholdValue

    fun maximumExtraClicks(): Int = maximumExtraClicksValue

    companion object {
        @JvmStatic
        fun fromTitle(title: String?): Optional<ExperimentTier> {
            if (title == null) return Optional.empty()
            val stripped = stripFormatting(title)
            val open = stripped.indexOf('(')
            val close = stripped.lastIndexOf(')')
            if (open < 0 || close <= open || close != stripped.length - 1
                || stripped.indexOf('(', open + 1) >= 0
                || stripped.indexOf(')', open + 1) != close
            ) {
                return Optional.empty()
            }
            return fromName(stripped.substring(open + 1, close))
        }

        @JvmStatic
        fun fromName(name: String?): Optional<ExperimentTier> {
            val normalized = name?.trim { it <= ' ' }?.lowercase(Locale.ROOT) ?: ""
            for (tier in values()) {
                if (tier != UNKNOWN && tier.label.lowercase(Locale.ROOT) == normalized) {
                    return Optional.of(tier)
                }
            }
            return Optional.empty()
        }

        private fun stripFormatting(value: String): String {
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
                result.append(character)
            }
            return result.toString()
        }
    }
}
