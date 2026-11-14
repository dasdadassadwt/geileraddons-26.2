package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/** Holds the sprint input while the player is in the world, so the sprint key is never needed. */
class AutoSprintModule private constructor() : Module(
    "Auto Sprint",
    "Keeps sprint held while you play. Vanilla still stops sprinting when you are hungry, blind or sneaking.",
    Category.MISCELLANEOUS,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 160)
    private var forcedInputs = 0L
    private var lastForced = false

    override fun onEnable() {
        forcedInputs = 0L
        lastForced = false
        diagnostics.enable("mode=sprint_input_held")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE forcedInputs=$forcedInputs")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true forcedInputs=$forcedInputs lastForced=$lastForced suppressedEvents=$it" }
    }

    /** Resolves the sprint flag of one player input tick; [sprint] is what the keyboard produced. */
    fun resolveSprint(sprint: Boolean, screenOpen: Boolean, freecamActive: Boolean): Boolean {
        val result = resolve(isEnabled(), screenOpen, freecamActive, sprint)
        if (result && !sprint) forcedInputs++
        if (result != lastForced && isEnabled()) {
            lastForced = result
            diagnostics.event("sprintInput=$result screenOpen=$screenOpen freecam=$freecamActive")
        }
        return result
    }

    companion object {
        @JvmField
        val INSTANCE = AutoSprintModule()

        /** Pure rule: force sprint only while enabled, no screen is open and Freecam is not feeding the input. */
        @JvmStatic
        fun resolve(enabled: Boolean, screenOpen: Boolean, freecamActive: Boolean, sprint: Boolean): Boolean =
            sprint || (enabled && !screenOpen && !freecamActive)
    }
}
