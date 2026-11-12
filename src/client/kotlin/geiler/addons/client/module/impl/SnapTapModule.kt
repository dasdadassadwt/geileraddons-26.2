package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/** Local last-input-priority (SOCD) movement: the most recently pressed of two opposite keys wins. */
class SnapTapModule private constructor() : Module(
    "Snap Tap",
    "Opposite movement keys (A/D, W/S): the last pressed key wins while both are held, and the other " +
        "resumes when it is released. Many servers treat this as an unfair input advantage; check the " +
        "server rules before enabling.",
    Category.MISCELLANEOUS,
    false,
) {
    private val forwardAxis = SnapTapAxis()
    private val strafeAxis = SnapTapAxis()
    private var lastClientTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var overrideCount = 0L
    private var eventWindowStart = Long.MIN_VALUE
    private var eventsInWindow = 0
    private var suppressedEvents = 0L
    private var forwardWinner = SnapTapAxis.NONE
    private var strafeWinner = SnapTapAxis.NONE

    override fun onEnable() {
        resetState()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), true)
        writeDiagnostic(lastClientTick, "ENABLE mode=last_input_priority axes=forward_back,left_right")
    }

    override fun onDisable() {
        writeDiagnostic(lastClientTick, "DISABLE overrides=$overrideCount suppressedEvents=$suppressedEvents")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), false)
        resetState()
    }

    /** Called by the shared client tick registration for the status heartbeat. */
    fun tick(clientTick: Long) {
        lastClientTick = clientTick
        if (!isEnabled()) return
        if (lastStatusTick != Long.MIN_VALUE && clientTick - lastStatusTick < DIAGNOSTIC_STATUS_INTERVAL_TICKS) return
        lastStatusTick = clientTick
        writeDiagnostic(
            clientTick,
            "STATUS enabled=true overrides=$overrideCount suppressedEvents=$suppressedEvents " +
                "forwardOverride=${forwardAxis.overriding} strafeOverride=${strafeAxis.overriding}",
        )
    }

    /**
     * Resolves raw movement keys into the keys the player input should see, once per input tick.
     * Returns a bit mask of [FORWARD], [BACKWARD], [LEFT], [RIGHT]; the raw state when disabled.
     */
    fun resolveMovement(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean): Int {
        if (!isEnabled()) {
            return mask(forward, backward, left, right)
        }
        val fb = forwardAxis.resolve(forward, backward)
        val lr = strafeAxis.resolve(left, right)
        forwardWinner = recordOverride("forward_back", forwardAxis, forwardWinner, "forward", "back")
        strafeWinner = recordOverride("left_right", strafeAxis, strafeWinner, "left", "right")
        return mask(fb and SnapTapAxis.POSITIVE != 0, fb and SnapTapAxis.NEGATIVE != 0,
            lr and SnapTapAxis.POSITIVE != 0, lr and SnapTapAxis.NEGATIVE != 0)
    }

    private fun recordOverride(axisName: String, axis: SnapTapAxis, previousWinner: Int, positive: String, negative: String): Int {
        val winner = if (axis.overriding) axis.winner else SnapTapAxis.NONE
        if (winner == previousWinner) return winner
        if (winner != SnapTapAxis.NONE) {
            overrideCount++
            val win = if (winner == SnapTapAxis.POSITIVE) positive else negative
            val lose = if (winner == SnapTapAxis.POSITIVE) negative else positive
            writeEvent("OVERRIDE axis=$axisName winner=$win suppressed=$lose")
        } else {
            writeEvent("OVERRIDE_END axis=$axisName")
        }
        return winner
    }

    /** At most [MAX_EVENTS_PER_WINDOW] event lines per [EVENT_WINDOW_TICKS] ticks; the rest are counted. */
    private fun writeEvent(line: String) {
        if (eventWindowStart == Long.MIN_VALUE || lastClientTick - eventWindowStart >= EVENT_WINDOW_TICKS) {
            if (suppressedEvents > 0 && eventsInWindow >= MAX_EVENTS_PER_WINDOW) {
                writeDiagnostic(lastClientTick, "EVENTS_SUPPRESSED total=$suppressedEvents limit=$MAX_EVENTS_PER_WINDOW/${EVENT_WINDOW_TICKS}t")
            }
            eventWindowStart = lastClientTick
            eventsInWindow = 0
        }
        if (eventsInWindow >= MAX_EVENTS_PER_WINDOW) {
            suppressedEvents++
            return
        }
        eventsInWindow++
        writeDiagnostic(lastClientTick, line)
    }

    private fun resetState() {
        forwardAxis.reset()
        strafeAxis.reset()
        forwardWinner = SnapTapAxis.NONE
        strafeWinner = SnapTapAxis.NONE
        lastStatusTick = Long.MIN_VALUE
        overrideCount = 0L
        eventWindowStart = Long.MIN_VALUE
        eventsInWindow = 0
        suppressedEvents = 0L
    }

    private fun writeDiagnostic(clientTick: Long, line: String) {
        GeilerAddonsLog.write(Category.MISCELLANEOUS, name(), clientTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    companion object {
        @JvmField
        val INSTANCE = SnapTapModule()

        const val FORWARD = 1
        const val BACKWARD = 2
        const val LEFT = 4
        const val RIGHT = 8

        @JvmStatic
        fun mask(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean): Int =
            (if (forward) FORWARD else 0) or (if (backward) BACKWARD else 0) or
                (if (left) LEFT else 0) or (if (right) RIGHT else 0)

        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 160
        private const val EVENT_WINDOW_TICKS = 20L
        private const val MAX_EVENTS_PER_WINDOW = 16
    }
}
