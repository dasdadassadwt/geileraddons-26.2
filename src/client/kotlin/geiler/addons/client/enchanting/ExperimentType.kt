package geiler.addons.client.enchanting

import java.util.Locale
import java.util.Optional

/** The three add-on games shown by the Experimentation Table. */
enum class ExperimentType(private val label: String) {
    CHRONOMATRON("Chronomatron"),
    ULTRASEQUENCER("Ultrasequencer"),
    SUPERPAIRS("Superpairs");

    fun displayName(): String = label

    companion object {
        /** Matches a complete experiment title and fails closed for unrelated menus. */
        @JvmStatic
        fun fromTitle(title: String?): Optional<ExperimentType> {
            val normalized = normalize(title)
            for (type in values()) {
                val prefix = type.label.lowercase(Locale.ROOT)
                if (normalized == prefix) return Optional.of(type)
                if (!normalized.startsWith("$prefix (") || !normalized.endsWith(")")) continue
                val tier = normalized.substring(prefix.length + 2, normalized.length - 1)
                    .trim { it <= ' ' }
                if (tier.isNotEmpty() && ExperimentTier.fromName(tier).isPresent) {
                    return Optional.of(type)
                }
            }
            return Optional.empty()
        }

        private fun normalize(value: String?): String {
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
                result.append(character)
            }
            return result.toString().trim { it <= ' ' }.lowercase(Locale.ROOT)
        }
    }
}
