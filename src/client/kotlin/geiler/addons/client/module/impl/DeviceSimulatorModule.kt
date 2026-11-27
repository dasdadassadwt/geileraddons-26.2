package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.sim.DeviceSimService

/**
 * Settings of the i4 and Simon Says simulators, started with `/ga sim`. Turning the module off stops a running sim.
 * The simulator writes its own diagnostics to `Device_Simulator.log` whenever it runs.
 */
class DeviceSimulatorModule private constructor(private val s: Settings) : Module(
    "Device Simulator",
    "Practice i4 and Simon Says with /ga sim setpos, /ga sim i4 and /ga sim ss. Shot cooldown: 5 ticks with " +
        "Terror armor, 7 without.",
    Category.F7,
    false,
    s.useOnHypixel, s.terminator, s.cooldown,
) {
    init {
        group(
            SettingGroup("Where", s.useOnHypixel),
            SettingGroup("i4 Bow", s.terminator, s.cooldown),
        )
    }

    override fun onDisable() {
        DeviceSimService.stop("module turned off", announce = true)
    }

    fun useOnHypixel(): Boolean = s.useOnHypixel.value()
    fun terminator(): Boolean = s.terminator.value()
    fun cooldownTicks(): Int = s.cooldown.intValue()

    private class Settings {
        val useOnHypixel = BooleanSetting("Use On Hypixel", "Use On Hypixel (Your Own Private Island Only)", false)
        val terminator = BooleanSetting("Terminator", "Shoot Three Arrows Like A Terminator", true)
        val cooldown = NumberSetting("Shot Cooldown (Ticks)", 1f, 20f, 5f, true)
    }

    companion object {
        @JvmField
        val INSTANCE = DeviceSimulatorModule(Settings())
    }
}
