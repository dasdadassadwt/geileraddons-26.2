package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText

/** Small chat-driven clock for the one-minute Crop Fever effect. */
class CropFeverTimerState {
    private var deadlineNanos = 0L
    private var active = false

    fun onChatMessage(message: String, nowNanos: Long): Event {
        val line = ChatText.stripForMatch(message)
        if (START_MESSAGE == line) {
            deadlineNanos = nowNanos + DURATION_NANOS
            active = true
            return Event.STARTED
        }
        if (END_MESSAGE == line) {
            val wasActive = active && deadlineNanos - nowNanos > 0L
            deadlineNanos = 0L
            active = false
            return if (wasActive) Event.ENDED else Event.NONE
        }
        return Event.NONE
    }

    fun remainingSeconds(nowNanos: Long): Double {
        val remainingNanos = deadlineNanos - nowNanos
        if (!active || remainingNanos <= 0L) {
            deadlineNanos = 0L
            active = false
            return 0.0
        }
        return remainingNanos / 1_000_000_000.0
    }

    fun active(nowNanos: Long): Boolean = remainingSeconds(nowNanos) > 0.0

    fun reset() {
        deadlineNanos = 0L
        active = false
    }

    enum class Event { NONE, STARTED, ENDED }

    companion object {
        const val DURATION_NANOS = 60_000_000_000L

        private const val START_MESSAGE = "WOAH! You caught a case of the CROP FEVER for 60 seconds!"
        private const val END_MESSAGE = "GONE! Your CROP FEVER has been cured!"
    }
}
