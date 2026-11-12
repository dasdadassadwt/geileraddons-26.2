package geiler.addons.client.utility

import kotlin.jvm.JvmRecord

/** Pure completion-output selection shared by the timer module and its offline checks. */
@JvmRecord
data class TimerCompletionPresentation(
    val showChat: Boolean,
    val showTitle: Boolean,
    val playSound: Boolean,
    val message: String,
    val titleDurationTicks: Int
) {
    companion object {
        const val DEFAULT_MASTER_ALERT: Boolean = true
        const val DEFAULT_CHAT_ENABLED: Boolean = true
        const val DEFAULT_TITLE_ENABLED: Boolean = false
        const val DEFAULT_SOUND_ENABLED: Boolean = true
        const val DEFAULT_MESSAGE: String = "Countdown complete."
        const val DEFAULT_TITLE_DURATION_TICKS: Int = 40
        const val MIN_TITLE_DURATION_TICKS: Int = 1
        const val MAX_TITLE_DURATION_TICKS: Int = 600
        private const val MAX_MESSAGE_LENGTH: Int = 128

        @JvmStatic
        fun defaults(): TimerCompletionPresentation = resolve(
            DEFAULT_MASTER_ALERT,
            DEFAULT_CHAT_ENABLED,
            DEFAULT_TITLE_ENABLED,
            DEFAULT_SOUND_ENABLED,
            DEFAULT_MESSAGE,
            DEFAULT_TITLE_DURATION_TICKS
        )

        @JvmStatic
        fun resolve(
            masterAlert: Boolean,
            showChat: Boolean,
            showTitle: Boolean,
            playSound: Boolean,
            message: String?,
            titleDurationTicks: Int
        ): TimerCompletionPresentation {
            var resolvedMessage = message?.trim { Character.isWhitespace(it) } ?: ""
            if (resolvedMessage.isEmpty()) resolvedMessage = DEFAULT_MESSAGE
            if (resolvedMessage.length > MAX_MESSAGE_LENGTH) {
                resolvedMessage = resolvedMessage.substring(0, MAX_MESSAGE_LENGTH)
            }
            val duration = Math.max(
                MIN_TITLE_DURATION_TICKS,
                Math.min(MAX_TITLE_DURATION_TICKS, titleDurationTicks)
            )
            return TimerCompletionPresentation(
                masterAlert && showChat,
                masterAlert && showTitle,
                masterAlert && playSound,
                resolvedMessage,
                duration
            )
        }
    }
}
