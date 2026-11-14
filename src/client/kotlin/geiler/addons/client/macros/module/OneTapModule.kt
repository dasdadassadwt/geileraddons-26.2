package geiler.addons.client.macros.module

import geiler.addons.client.macros.sim.SimGuard
import geiler.addons.client.macros.sim.SimRuntime
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.impl.ModuleDiagnostics

/** The real Strength effect at level 255, with no time limit, in singleplayer only. */
class OneTapModule private constructor() : Module(
    "1 Tap",
    "Singleplayer only. Gives you the real Strength effect at level 255 with no time limit while this is on. It is " +
        "removed when you switch this off or leave singleplayer.",
    Category.SIM,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.SIM, { name() })

    override fun onEnable() = diagnostics.enable("effect=strength level=255 amplifier=254 singleplayerOnly=true")

    override fun onDisable() = diagnostics.disable()

    override fun isActive(): Boolean = isEnabled() && SimGuard.allowedNow()

    override fun inactiveReason(): String? = if (isEnabled() && !SimGuard.allowedNow()) "Singleplayer only" else null

    /** Called every client tick: relays what the server thread did and writes the status heartbeat. */
    fun tick() {
        if (isEnabled()) diagnostics.advance()
        while (true) {
            val line = SimRuntime.pollEvent(SimRuntime.Feature.ONE_TAP) ?: break
            if (isEnabled()) diagnostics.event(line) else diagnostics.record("EVENT $line")
        }
        if (!isEnabled()) return
        diagnostics.status { suppressed ->
            "enabled=true singleplayerOnly=${SimGuard.allowedNow()} applied=${SimRuntime.oneTapApplied} suppressedEvents=$suppressed"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = OneTapModule()
    }
}
