package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category

/** Receives one bounded diagnostic line; the default sink is the per-module session log file. */
fun interface DiagnosticSink {
    fun write(category: Category, module: String, tick: Long, line: String)
}

/** Marks the module's log source active so its writer stays open while the feature is enabled. */
fun interface DiagnosticActivation {
    fun set(category: Category, module: String, on: Boolean)
}

/**
 * The shared bounded-diagnostics state every feature needs: a tick counter, a per-tick event budget,
 * a suppressed-event counter, a status heartbeat, and the enable/disable bookends. Modules keep only
 * their own status text. Log prefixes (`EVENT`, `STATUS`, `EVENTS_SUPPRESSED`, `FEATURE ENABLED/DISABLED`)
 * stay stable for `docs/debugging.md` evidence.
 */
class ModuleDiagnostics @JvmOverloads constructor(
    private val category: Category,
    private val moduleName: () -> String,
    private val maxEventsPerTick: Int = 8,
    private val statusIntervalTicks: Long = 100L,
    private val maxLineChars: Int = 384,
    private val sink: DiagnosticSink = DiagnosticSink { c, m, t, l -> GeilerAddonsLog.write(c, m, t, l) },
    private val activation: DiagnosticActivation =
        DiagnosticActivation { c, m, on -> GeilerAddonsLog.setModuleDiagnosticsEnabled(c, m, on) },
) {
    var tick = 0L
        private set
    private var active = false
    private var statusTick = Long.MIN_VALUE
    private var budgetTick = Long.MIN_VALUE
    /** Events written in the current tick (the FEATURE ENABLED bookend counts as one); read by offline checks. */
    var eventsThisTick = 0
        private set
    private var suppressed = 0
    private var lastState: String? = null

    fun enable(settings: String) {
        tick = 0L
        statusTick = Long.MIN_VALUE
        budgetTick = Long.MIN_VALUE
        eventsThisTick = 0
        suppressed = 0
        lastState = null
        active = true
        activation.set(category, moduleName(), true)
        event("FEATURE ENABLED $settings")
    }

    fun disable() {
        event("FEATURE DISABLED")
        flush()
        active = false
        activation.set(category, moduleName(), false)
    }

    /** Advances the diagnostic clock; call once per client tick while the module is enabled. */
    fun advance() {
        if (tick < Long.MAX_VALUE) tick++
    }

    /** Writes one `EVENT` line, or counts it as suppressed once the per-tick budget is spent. */
    fun event(text: String) {
        if (!active) return
        if (budgetTick != tick) {
            budgetTick = tick
            eventsThisTick = 0
        }
        if (eventsThisTick >= maxEventsPerTick) {
            if (suppressed < MAX_SUPPRESSED) suppressed++
            return
        }
        eventsThisTick++
        record("EVENT $text")
    }

    /** Writes a `STATUS` heartbeat at most once per interval; [line] receives the suppressed-event count. */
    fun status(line: (suppressedEvents: Int) -> String) {
        if (!active || (statusTick != Long.MIN_VALUE && tick - statusTick < statusIntervalTicks)) return
        record("STATUS ${line(suppressed)}")
        statusTick = tick
        suppressed = 0
    }

    /**
     * Writes `STATUS <state> suppressedEvents=<n>` when [state] changed (at most once per [minGapTicks]) or at the
     * heartbeat. [state] is only invoked once the gap has passed, so no string is built on the throttled ticks.
     */
    fun statusOnChange(minGapTicks: Long = 20L, state: () -> String) = statusOnChangeBy(minGapTicks, state, state)

    /**
     * Like [statusOnChange], but change detection compares [signature] while the written line is [state]: use it
     * when the line also carries counters that change every tick and must not count as a state change.
     */
    fun statusOnChangeBy(minGapTicks: Long, signature: () -> String, state: () -> String) {
        if (!active) return
        val sinceLast = if (statusTick == Long.MIN_VALUE) Long.MAX_VALUE else tick - statusTick
        if (sinceLast < minGapTicks) return
        val current = signature()
        if (current == lastState && sinceLast < statusIntervalTicks) return
        record("STATUS ${state()} suppressedEvents=$suppressed")
        lastState = current
        statusTick = tick
        suppressed = 0
    }

    fun flush() {
        if (suppressed <= 0) return
        record("EVENTS_SUPPRESSED count=$suppressed")
        suppressed = 0
    }

    fun record(line: String) = sink.write(category, moduleName(), tick, line.take(maxLineChars))

    private companion object {
        const val MAX_SUPPRESSED = 1_000_000
    }
}
