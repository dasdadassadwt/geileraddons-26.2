package geiler.addons.client.macros.module

import geiler.addons.client.macros.sim.SimGuard
import geiler.addons.client.macros.sim.SimMapping
import geiler.addons.client.macros.sim.SimRuntime
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.impl.ModuleDiagnostics

/** Scales ground movement speed by Speed / 100 in singleplayer; see [SimMapping] and [SimRuntime]. */
class SpeedSimModule private constructor(private val speed: NumberSetting) : Module(
    "Speed Sim",
    "Singleplayer only. Multiplies your ground speed by Speed / 100: at 100 you walk 4.317, sprint 5.612 and sneak " +
        "1.295 blocks per second, so 200 is twice as fast and 400 is four times. There is no upper limit.",
    Category.SIM,
    false,
    speed,
) {
    private val diagnostics = ModuleDiagnostics(Category.SIM, { name() })

    /** The Speed stat; 100 is vanilla. */
    fun speedStat(): Double = speed.value().toDouble()

    override fun onEnable() = diagnostics.enable("speed=${speed.value()} singleplayerOnly=true")

    override fun onDisable() = diagnostics.disable()

    override fun isActive(): Boolean = isEnabled() && SimGuard.allowedNow()

    override fun inactiveReason(): String? = if (isEnabled() && !SimGuard.allowedNow()) "Singleplayer only" else null

    /** Called every client tick: relays what the server thread did and writes the status heartbeat. */
    fun tick() {
        if (isEnabled()) diagnostics.advance()
        while (true) {
            val line = SimRuntime.pollEvent(SimRuntime.Feature.SPEED) ?: break
            if (isEnabled()) diagnostics.event(line) else diagnostics.record("EVENT $line")
        }
        if (!isEnabled()) return
        diagnostics.status { suppressed ->
            "enabled=true singleplayerOnly=${SimGuard.allowedNow()} applied=${SimRuntime.speedApplied} " +
                "speed=${speed.value()} suppressedEvents=$suppressed"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = SpeedSimModule(
            NumberSetting(
                "Speed", SimMapping.MIN_SPEED_STAT.toFloat(), SimMapping.MAX_SPEED_STAT.toFloat(),
                SimMapping.DEFAULT_SPEED_STAT.toFloat(),
            ),
        )
    }
}
