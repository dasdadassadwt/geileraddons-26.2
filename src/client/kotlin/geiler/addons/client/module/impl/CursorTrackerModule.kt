package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/**
 * Debug recorder for Auto Terms: while on, every terminal Terminal Solver shows (real or simulator) is written to the Analyse
 * Terms file with the raw mouse stream (every move event with its time, every button down and up) and the slot the solver
 * wants next, so `tools/analyze_terminals.py` can measure how a person's cursor really moves: speed profile, corrections,
 * overshoots, tremor, shake while spam clicking, and where the cursor waits for a melody button. Terminal Solver does the
 * recording; this module is the switch, the mouse hooks' entry point and the diagnostics.
 */
class CursorTrackerModule private constructor() : Module(
    "Cursor Tracker",
    "Debug: records your raw mouse movement and clicks in terminals, so Auto Terms can be fitted to how you really move.",
    Category.DEV,
) {
    private val diagnostics = ModuleDiagnostics(Category.DEV, { name() })
    private var runs = 0
    private var moves = 0L

    override fun onEnable() {
        runs = 0
        moves = 0L
        diagnostics.enable("solverEnabled=${TerminalSolverModule.INSTANCE.isEnabled()} file=recordings/terminals")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    /** Called by the mouse mixin for every move event, in window pixels. */
    fun onMove(x: Double, y: Double) {
        if (isEnabled()) TerminalSolverModule.INSTANCE.trackMove(x, y)
    }

    /** Called by the mouse mixin for a button going down (true) or up (false). */
    fun onButton(button: Int, down: Boolean) {
        if (isEnabled()) TerminalSolverModule.INSTANCE.trackButton(button, down)
    }

    /** Terminal Solver finished writing a run. */
    fun runFinished(type: String, number: Int, moves: Int, ms: Long) {
        if (!isEnabled()) return
        runs++
        this.moves += moves
        diagnostics.event("TRACK_RUN type=$type n=$number moves=$moves ms=$ms")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "runs=$runs moves=$moves suppressedEvents=$it" }
    }

    companion object {
        @JvmField
        val INSTANCE = CursorTrackerModule()
    }
}
