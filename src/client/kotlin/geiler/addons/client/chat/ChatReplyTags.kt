package geiler.addons.client.chat

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/** Tags a Text Reply response may contain; unknown tags are left as plain text. */
object ChatReplyTags {
    const val HELP = "Tags: {sender} {me} {time} {rng:1-10} {pick:a|b|c}"

    private const val MAX_RNG = 1_000_000
    private const val MAX_PICK_OPTIONS = 16

    private val SIMPLE = Regex("\\{(sender|me|time)}")
    private val RNG_OR_PICK = Regex("\\{(rng|pick):([^{}]*)}")
    private val RNG_BODY = Regex("(\\d{1,7})-(\\d{1,7})")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)

    /** False when a `{rng:..}` or `{pick:..}` tag is present but cannot be expanded. */
    @JvmStatic
    fun validate(template: String): Boolean =
        RNG_OR_PICK.findAll(template).all { resolve(it, Random(0)) != null }

    @JvmStatic
    fun expand(template: String, sender: String, me: String, now: ZonedDateTime, random: Random): String {
        val withValues = RNG_OR_PICK.replace(template) { resolve(it, random) ?: it.value }
        return SIMPLE.replace(withValues) {
            when (it.groupValues[1]) {
                "sender" -> sender
                "me" -> me
                else -> now.format(TIME_FORMAT)
            }
        }
    }

    private fun resolve(match: MatchResult, random: Random): String? {
        val body = match.groupValues[2]
        if (match.groupValues[1] == "rng") {
            val range = RNG_BODY.matchEntire(body) ?: return null
            val low = range.groupValues[1].toInt()
            val high = range.groupValues[2].toInt()
            if (low > high || high > MAX_RNG) return null
            return (low + random.nextInt(high - low + 1)).toString()
        }
        val options = body.split('|').map { it.trim() }
        if (options.size > MAX_PICK_OPTIONS || options.any { it.isEmpty() }) return null
        return options[random.nextInt(options.size)]
    }
}
