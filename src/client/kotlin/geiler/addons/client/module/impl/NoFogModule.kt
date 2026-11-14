package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.world.level.material.FogType

/** Removes the underwater and lava fog, leaving only the normal render-distance fog. */
class NoFogModule private constructor(settings: Settings) : Module(
    "No Fog",
    "Removes the thick fog underwater and in lava. Normal distance fog is unchanged.",
    Category.VISUAL,
    false,
    settings.underwater,
    settings.lava,
) {
    private val underwater = settings.underwater
    private val lava = settings.lava
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 200)
    private var clearedFrames = 0L
    private var lastType: FogType? = null

    override fun onEnable() {
        clearedFrames = 0L
        lastType = null
        diagnostics.enable("underwater=${underwater.value()} lava=${lava.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE clearedFrames=$clearedFrames")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "enabled=true underwater=${underwater.value()} lava=${lava.value()} clearedFrames=$clearedFrames " +
                "lastType=$lastType suppressedEvents=$it"
        }
    }

    /** True when the fog of [type] must be pushed out to the render distance. */
    fun shouldClear(type: FogType): Boolean {
        if (!isEnabled()) return false
        val clear = shouldClear(type, underwater.value(), lava.value())
        if (clear) {
            clearedFrames++
            if (type != lastType) diagnostics.event("clearFog type=$type")
            lastType = type
        }
        return clear
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("No Fog")
        val underwater = spec.toggle("underwater")
        val lava = spec.toggle("lava")
    }

    companion object {
        @JvmField
        val INSTANCE = NoFogModule(Settings())

        /** Environmental fog is replaced only for the fluid the matching toggle covers. */
        @JvmStatic
        fun shouldClear(type: FogType, underwater: Boolean, lava: Boolean): Boolean =
            (type == FogType.WATER && underwater) || (type == FogType.LAVA && lava)

        /** Start and end far past any render distance, so only the render-distance fog is left. */
        const val FAR_START = 1.0E6f
        const val FAR_END = 2.0E6f
    }
}
