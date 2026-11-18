package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/** Keeps the cursor where it was when a container is reopened right after the previous screen closed. */
class NoCursorResetModule private constructor(
    private val timeout: NumberSetting,
) : Module(
    "No Cursor Reset",
    "Keeps the cursor in place when a container opens right after another screen closed.",
    Category.MISCELLANEOUS,
    false,
    timeout,
) {
    companion object {
        @JvmField
        val INSTANCE = NoCursorResetModule(NumberSetting("Unhook Timeout", 0f, 1000f, 150f, true))

        /** Only a container screen opened within [timeoutMs] of the last screen activity keeps the cursor. */
        @JvmStatic
        fun shouldKeepCursor(containerScreen: Boolean, sinceMs: Long, timeoutMs: Int): Boolean =
            containerScreen && sinceMs < timeoutMs
    }

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() })
    private var clock = System.currentTimeMillis()
    private var wasOpen = false
    private var kept = 0

    override fun onEnable() {
        diagnostics.enable("timeoutMs=${timeout.intValue()}")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    /** Refreshes the "last screen activity" clock while a screen is open and when it closes. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (Minecraft.getInstance().gui.screen() != null) {
            wasOpen = true
            clock = System.currentTimeMillis()
        } else if (wasOpen) {
            wasOpen = false
            clock = System.currentTimeMillis()
        }
        diagnostics.status { suppressed -> "timeoutMs=${timeout.intValue()} keptSinceLastStatus=$kept suppressedEvents=$suppressed".also { kept = 0 } }
    }

    /** Called by the mouse mixin when a screen takes the cursor; true keeps the previous cursor position. */
    fun keepCursor(screen: Screen?): Boolean {
        if (!isEnabled()) return false
        val since = System.currentTimeMillis() - clock
        val keep = shouldKeepCursor(screen is AbstractContainerScreen<*>, since, timeout.intValue())
        if (keep) {
            kept++
            diagnostics.event("kept cursor sinceMs=$since screen=${screen?.javaClass?.simpleName}")
        } else {
            // Every release the game makes is recorded, so a menu that still recentres shows why (not a container, or too long since the last screen).
            diagnostics.event("cursor reset sinceMs=$since timeoutMs=${timeout.intValue()} screen=${screen?.javaClass?.simpleName ?: "none"} container=${screen is AbstractContainerScreen<*>}")
        }
        return keep
    }
}
