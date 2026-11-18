package geiler.addons.client.module.impl

import geiler.addons.client.farming.VisitorRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module

/**
 * Locks the view: while on, the mouse cannot turn the player, so a farming run keeps its angle. Turn it on from the
 * module page, with its keybind, or with `/ga mouselock` (after SkyHanni's mouse lock).
 */
class MouseLockModule private constructor() : Module(
    "Mouse Lock",
    "Stops the mouse from turning your view, so a farming run keeps its angle. Also /ga mouselock.",
    Category.FARMING,
    false,
) {
    private var blocked = 0L
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)

    override fun onEnable() {
        blocked = 0L
        diagnostics.enable("locks mouse turning")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE blockedTurns=$blocked")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true blockedTurns=$blocked suppressedEvents=$it" }
    }

    /** A mouse turn; true means it was swallowed. */
    fun captureTurn(): Boolean {
        if (!isEnabled()) return false
        blocked++
        return true
    }

    companion object {
        @JvmField
        val INSTANCE = MouseLockModule()
    }
}

/** Hides the `NEW VISITOR ... has arrived on your Garden!` chat line, for people who read the visitor list instead. */
class VisitorMessageHiderModule private constructor() : Module(
    "Visitor Message Hider",
    "Hides the chat line announcing a new Garden visitor.",
    Category.FARMING,
    false,
) {
    private var hidden = 0L
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)

    override fun onEnable() {
        hidden = 0L
        diagnostics.enable("hides new visitor lines")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE hidden=$hidden")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true hidden=$hidden suppressedEvents=$it" }
    }

    /** True when [plain] is the arrival line and the module is on; the caller then drops the message. */
    fun shouldHide(plain: String): Boolean {
        if (!isEnabled() || !VisitorRules.isArrivalLine(plain)) return false
        hidden++
        diagnostics.event("HIDDEN count=$hidden")
        return true
    }

    companion object {
        @JvmField
        val INSTANCE = VisitorMessageHiderModule()
    }
}
