package geiler.addons.client.macros.module

import geiler.addons.client.macros.sim.EtherwarpMath
import geiler.addons.client.macros.sim.InstantTransmission
import geiler.addons.client.macros.sim.SimGuard
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.impl.ModuleDiagnostics

/**
 * Makes a diamond shovel the etherwarp item of singleplayer, like the Aspect of the Void on Hypixel SkyBlock: range 57
 * blocks plus one per Transmission Tuner, up to four. The Etherwarp module does the preview, the click and the cooldown;
 * this module is the switch and the tuners.
 */
class EtherwarpSimModule private constructor(private val tuners: NumberSetting) : Module(
    "Etherwarp Sim",
    "Singleplayer only. Turns a diamond shovel into the Aspect of the Void; the Etherwarp module need not be on. Sneak right click lands in the middle of the block you look at, up to 57 blocks away, if there is room to stand. " +
        "Right click alone is Instant Transmission: 8 blocks along the view, stopping short of what is in the way. Transmission " +
        "Tuners add one block of range to each, up to 4.",
    Category.SIM,
    false,
    tuners,
) {
    private val diagnostics = ModuleDiagnostics(Category.SIM, { name() })

    fun tuners(): Int = tuners.intValue()

    override fun onEnable() {
        diagnostics.enable(
            "tuners=${tuners.intValue()} range=${EtherwarpMath.range(tuners.intValue())} " +
                "instant=${InstantTransmission.distance(tuners.intValue())} item=diamond_shovel singleplayerOnly=true",
        )
    }

    override fun onDisable() = diagnostics.disable()

    override fun isActive(): Boolean = isEnabled() && SimGuard.allowedNow()

    override fun inactiveReason(): String? = if (isEnabled() && !SimGuard.allowedNow()) "Singleplayer only" else null

    /** Called every client tick: the status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { suppressed ->
            "enabled=true singleplayerOnly=${SimGuard.allowedNow()} tuners=${tuners.intValue()} suppressedEvents=$suppressed"
        }
    }

    companion object {
        @JvmField
        val INSTANCE = EtherwarpSimModule(NumberSetting("Transmission Tuners", 0.0f, EtherwarpMath.MAX_TUNERS.toFloat(), 0.0f, true))
    }
}
