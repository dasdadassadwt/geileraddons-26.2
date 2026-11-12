package geiler.addons.client.utility

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure formatting rules for the local-system-time HUD. */
object SystemClockFormat {
    const val TWELVE_HOUR = "12-hour"
    const val TWENTY_FOUR_HOUR = "24-hour"
    const val DEFAULT_SHOW_SECONDS = true
    const val DEFAULT_SHOW_BACKGROUND = false
    const val DEFAULT_TEXT_ARGB = -1
    const val DEFAULT_BACKGROUND_ARGB = -2147483648

    private val TWELVE_SECONDS = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ROOT)
    private val TWELVE_MINUTES = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT)
    private val TWENTY_FOUR_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val TWENTY_FOUR_MINUTES = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    @JvmStatic
    fun format(time: LocalTime?, format: String?, showSeconds: Boolean): String {
        val clockTime = requireNotNull(time) { "time is required" }
        val formatter = if (format == TWELVE_HOUR) {
            if (showSeconds) TWELVE_SECONDS else TWELVE_MINUTES
        } else {
            if (showSeconds) TWENTY_FOUR_SECONDS else TWENTY_FOUR_MINUTES
        }
        return formatter.format(clockTime)
    }
}
