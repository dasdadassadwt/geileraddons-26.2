package geiler.addons.client.farming

/** Combines chat spawn times, the Pests widget cooldown, and an optional custom cooldown. */
class PestCooldownTimer {
    private var spawnNanos = 0L
    private var widgetDeadlineNanos = 0L
    private var widgetRemainingSeconds = 0
    private var widgetState = PestCooldownText.State.UNKNOWN
    private var hasWidgetDeadline = false
    private var armed = false
    private var awaitingWidgetCountdown = false
    private var readyEventFired = false
    private var warningEventFired = false
    private var hasSpawn = false

    fun onSpawn(nowNanos: Long) {
        spawnNanos = nowNanos
        hasSpawn = true
        widgetDeadlineNanos = 0L
        hasWidgetDeadline = false
        widgetRemainingSeconds = 0
        widgetState = PestCooldownText.State.UNKNOWN
        armed = true
        awaitingWidgetCountdown = true
        readyEventFired = false
        warningEventFired = false
    }

    fun observeWidget(observation: PestCooldownText.Observation?, nowNanos: Long) {
        if (observation == null) return
        val state = observation.state()
        if (state == null) return
        widgetState = state
        when (state) {
            PestCooldownText.State.COUNTDOWN -> {
                armed = true
                awaitingWidgetCountdown = false
                widgetRemainingSeconds = observation.remainingSeconds()
                widgetDeadlineNanos = safeDeadline(nowNanos, widgetRemainingSeconds)
                hasWidgetDeadline = true
            }
            PestCooldownText.State.READY -> {
                widgetRemainingSeconds = 0
                widgetDeadlineNanos = nowNanos
                hasWidgetDeadline = true
            }
            PestCooldownText.State.MAX_PESTS -> {
                widgetRemainingSeconds = 0
                widgetDeadlineNanos = 0L
                hasWidgetDeadline = false
            }
            PestCooldownText.State.UNKNOWN -> Unit
        }
    }

    /** Returns true once per spawn cycle when the selected deadline enters the warning window. */
    fun consumeWarningEvent(
        nowNanos: Long,
        customCooldownEnabled: Boolean,
        customSeconds: Int,
        leadSeconds: Int,
    ): Boolean {
        if (!armed || warningEventFired || readyEventFired) return false
        val remaining = remainingSeconds(nowNanos, customCooldownEnabled, customSeconds)
        if (remaining <= 0.0 || remaining > maxOf(0, leadSeconds).toDouble()) return false
        warningEventFired = true
        return true
    }

    /** Returns true once per spawn/cooldown cycle when the widget is READY and the custom deadline has passed. */
    fun consumeReadyEvent(nowNanos: Long, customCooldownEnabled: Boolean, customSeconds: Int): Boolean {
        if (!armed || awaitingWidgetCountdown || readyEventFired ||
            widgetState != PestCooldownText.State.READY
        ) return false
        if (customCooldownEnabled && (!hasSpawn ||
                nowNanos < safeDeadline(spawnNanos, maxOf(0, customSeconds)))
        ) return false
        readyEventFired = true
        return true
    }

    fun remainingSeconds(nowNanos: Long, customCooldownEnabled: Boolean, customSeconds: Int): Double {
        val deadline = when {
            customCooldownEnabled && hasSpawn -> safeDeadline(spawnNanos, maxOf(0, customSeconds))
            widgetState == PestCooldownText.State.COUNTDOWN && hasWidgetDeadline -> widgetDeadlineNanos
            widgetState == PestCooldownText.State.READY -> return 0.0
            else -> return -1.0
        }
        return remainingNanos(deadline, nowNanos) / 1_000_000_000.0
    }

    fun armed(): Boolean = armed

    fun readyEventFired(): Boolean = readyEventFired

    fun spawnNanos(): Long = spawnNanos

    fun widgetState(): PestCooldownText.State = widgetState

    fun reset() {
        spawnNanos = 0L
        hasSpawn = false
        widgetDeadlineNanos = 0L
        hasWidgetDeadline = false
        widgetRemainingSeconds = 0
        widgetState = PestCooldownText.State.UNKNOWN
        armed = false
        awaitingWidgetCountdown = false
        readyEventFired = false
        warningEventFired = false
    }

    companion object {
        private fun safeDeadline(start: Long, seconds: Int): Long {
            val duration = maxOf(0L, seconds.toLong()) * 1_000_000_000L
            return if (start > Long.MAX_VALUE - duration) Long.MAX_VALUE else start + duration
        }

        private fun remainingNanos(deadline: Long, now: Long): Long {
            if (deadline <= now) return 0L
            if (now < 0L && deadline > Long.MAX_VALUE + now) return Long.MAX_VALUE
            return deadline - now
        }
    }
}
