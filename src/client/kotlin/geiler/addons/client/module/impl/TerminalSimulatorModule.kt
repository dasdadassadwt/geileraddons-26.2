package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.termsim.TermSimService
import net.minecraft.client.Minecraft

/**
 * Odin's Terminal Simulator: enabling it (or pressing its key) opens the simulator menu and switches the module off
 * again. Everything runs on this client; no packet is sent. `/ga termsim` opens the same menu.
 */
class TerminalSimulatorModule private constructor(private val s: Settings) : Module(
    "Terminal Simulator",
    "Simulates a floor 7 terminal from phase 3.",
    Category.F7,
    false,
    s.ping, s.firstClickProt,
) {
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 6, 100L, 240)

    override fun onEnable() {
        diagnostics.enable("ping=${s.ping.intValue()} firstClickProt=${s.firstClickProt.value()}")
        diagnostics.event("OPEN ping=${s.ping.intValue()} source=module")
        Minecraft.getInstance().execute {
            setEnabled(false)
            TermSimService.openStart(s.ping.intValue().toLong())
        }
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    /** Odin's First Click Prot setting: no first-click protection while solving a simulator. */
    fun disableFirstClickProtection(): Boolean = s.firstClickProt.value()

    fun pingMillis(): Long = s.ping.intValue().toLong()

    class Settings {
        val ping = NumberSetting("Ping", 0f, 500f, 0f, true)
        val firstClickProt = BooleanSetting("First Click Prot", false)
    }

    companion object {
        @JvmField
        val INSTANCE = TerminalSimulatorModule(Settings())
    }
}
