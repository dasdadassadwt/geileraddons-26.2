package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.TrailPath
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft

/** Draws a fading line behind the player's feet while they move. */
class TrailModule private constructor(
    private val settings: Settings,
) : Module(
    "Trail",
    "Draws a trail at your feet while you move, with color, range, decay and density settings.",
    Category.VISUAL,
    false,
    settings.color,
    settings.range,
    settings.decay,
    settings.density,
    settings.width,
    settings.firstPerson,
) {
    companion object {
        @JvmField
        val INSTANCE = TrailModule(Settings())
        private const val FOOT_LIFT = 0.03
    }

    private val path = TrailPath()
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() })
    private var lastLevel: Any? = null
    private var drawnSegments = 0

    init {
        group(
            SettingGroup("Appearance", settings.color, settings.width, settings.firstPerson),
            SettingGroup("Shape", settings.range, settings.decay, settings.density),
        )
    }

    override fun onEnable() {
        path.clear()
        diagnostics.enable(
            "range=${settings.range.value()},decayTicks=${settings.decay.intValue()},density=${settings.density.intValue()}," +
                "width=${settings.width.value()},color=${settings.color.hex()},firstPerson=${settings.firstPerson.value()}",
        )
    }

    override fun onDisable() {
        path.clear()
        diagnostics.disable()
    }

    /** Samples the player's feet once per client tick and drops points that expired or fell out of range. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null) {
            path.clear()
            return
        }
        if (level !== lastLevel) {
            lastLevel = level
            path.clear()
        }
        val now = level.gameTime
        path.add(player.x, player.y + FOOT_LIFT, player.z, now, TrailPath.spacing(settings.density.intValue()))
        path.prune(now, settings.decay.intValue(), settings.range.value().toDouble())
        diagnostics.status { suppressed ->
            "points=${path.size()} segmentsDrawnSinceLastStatus=$drawnSegments suppressedEvents=$suppressed".also { drawnSegments = 0 }
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || path.size() == 0) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera()
        if (!camera.isDetached && !settings.firstPerson.value()) return
        val cameraPosition = camera.position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val now = level.gameTime
        val decay = settings.decay.intValue()
        val color = settings.color.argb()
        val width = settings.width.value()
        val points = path.points()
        var drew = false
        for (index in 0 until points.size - 1) {
            val from = points[index]
            val to = points[index + 1]
            val faded = TrailPath.fadedArgb(color, TrailPath.alpha(now, from.tick, decay))
            if (faded ushr 24 == 0) continue
            EspRenderer.renderLine(
                pose, buffers, from.x - cameraPosition.x, from.y - cameraPosition.y, from.z - cameraPosition.z,
                to.x - cameraPosition.x, to.y - cameraPosition.y, to.z - cameraPosition.z, faded, width, true,
            )
            drew = true
            drawnSegments++
        }
        // Close the gap between the newest sample and the player's current feet.
        val newest = points.last()
        val feetY = player.y + FOOT_LIFT
        EspRenderer.renderLine(
            pose, buffers, newest.x - cameraPosition.x, newest.y - cameraPosition.y, newest.z - cameraPosition.z,
            player.x - cameraPosition.x, feetY - cameraPosition.y, player.z - cameraPosition.z,
            TrailPath.fadedArgb(color, TrailPath.alpha(now, newest.tick, decay)), width, true,
        )
        if (drew || points.isNotEmpty()) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private class Settings {
        val color = ColorSetting("Trail Color", 85, 200, 255, 220)
        val range = NumberSetting("Trail Range", 1f, 32f, 8f)
        val decay = NumberSetting("Trail Decay (ticks)", 5f, 200f, 40f, true)
        val density = NumberSetting("Trail Density", 1f, 10f, 4f, true)
        val width = NumberSetting("Trail Width", 0.5f, 10f, 3f)
        val firstPerson = BooleanSetting("Show In First Person", true)
    }
}
