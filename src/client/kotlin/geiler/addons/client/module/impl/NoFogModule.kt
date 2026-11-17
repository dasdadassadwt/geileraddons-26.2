package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.world.level.material.FogType

/**
 * Removes fog: underwater, lava, the air fog of every dimension (overworld, nether, end, blindness, darkness, powder
 * snow) and the render-distance fog. Each part has its own toggle, all on by default.
 */
class NoFogModule private constructor(settings: Settings) : Module(
    "No Fog",
    "Removes underwater, lava, nether, world and render-distance fog.",
    Category.VISUAL,
    false,
    settings.underwater,
    settings.lava,
    settings.air,
    settings.distance,
) {
    private val underwater = settings.underwater
    private val lava = settings.lava
    private val air = settings.air
    private val distance = settings.distance
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 200)
    private var clearedFrames = 0L
    private var lastType: FogType? = null

    override fun onEnable() {
        clearedFrames = 0L
        lastType = null
        diagnostics.enable("underwater=${underwater.value()} lava=${lava.value()} air=${air.value()} distance=${distance.value()}")
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
            "enabled=true underwater=${underwater.value()} lava=${lava.value()} air=${air.value()} " +
                "distance=${distance.value()} clearedFrames=$clearedFrames lastType=$lastType suppressedEvents=$it"
        }
    }

    /** True when the fog of [type] must be pushed out to the render distance. */
    fun shouldClear(type: FogType): Boolean {
        if (!isEnabled()) return false
        return shouldClear(type, underwater.value(), lava.value(), air.value())
    }

    /** Pushes the enabled fog parts of one frame out of sight, after vanilla has filled [data] in. */
    fun apply(type: FogType, data: FogData) {
        if (!isEnabled()) return
        var cleared = false
        if (shouldClear(type, underwater.value(), lava.value(), air.value())) {
            data.environmentalStart = FAR_START
            data.environmentalEnd = FAR_END
            data.skyEnd = FAR_END
            data.cloudEnd = FAR_END
            cleared = true
        }
        if (distance.value()) {
            data.renderDistanceStart = FAR_START
            data.renderDistanceEnd = FAR_END
            data.skyEnd = FAR_END
            data.cloudEnd = FAR_END
            cleared = true
        }
        if (!cleared) return
        clearedFrames++
        if (type != lastType) diagnostics.event("clearFog type=$type distance=${distance.value()}")
        lastType = type
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("No Fog")
        val underwater = spec.toggle("underwater")
        val lava = spec.toggle("lava")
        val air = spec.toggle("air")
        val distance = spec.toggle("distance")
    }

    companion object {
        @JvmField
        val INSTANCE = NoFogModule(Settings())

        /** Water and lava follow their own toggle; every other fog type is air fog. */
        @JvmStatic
        fun shouldClear(type: FogType, underwater: Boolean, lava: Boolean, air: Boolean): Boolean = when (type) {
            FogType.WATER -> underwater
            FogType.LAVA -> lava
            else -> air
        }

        /** Start and end far past any render distance, so the fog is out of sight. */
        const val FAR_START = 1.0E6f
        const val FAR_END = 2.0E6f
    }
}
