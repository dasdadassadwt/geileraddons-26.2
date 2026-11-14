package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft

/**
 * Detached camera. Only the render camera moves: the camera entity stays the player, so block selection, attacks and
 * item use keep the player's own position and aim, and no packet is sent or changed. While it is on, the movement keys
 * and the mouse fly the camera and the player receives no movement or look input.
 */
class FreecamModule private constructor(settings: Settings) : Module(
    "Freecam",
    "Detaches the camera. W/A/S/D, Space, Shift and the mouse fly the camera; your player stands still, the server " +
        "sees nothing, and clicks still use your player's aim.",
    Category.VISUAL,
    false,
    settings.speed,
) {
    private val speed = settings.speed
    private val state = FreecamState()
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 320)
    private var frameTicks = 0f
    private var frames = 0L

    override fun onEnable() {
        state.clear()
        frames = 0L
        diagnostics.enable("speedBlocksPerTick=${speed.value()} input=camera_only playerInput=none server=untouched")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE frames=$frames cameraSeeded=${state.seeded}")
        diagnostics.disable()
        state.clear()
    }

    /** Called by the shared client tick registration: drops the pose when the world goes away and writes the status. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null || client.level == null) {
            if (state.seeded) diagnostics.event("worldGone reseedOnJoin=true")
            state.clear()
            return
        }
        diagnostics.status { suppressed ->
            "enabled=true seeded=${state.seeded} cam=${fmt(state.x)},${fmt(state.y)},${fmt(state.z)} " +
                "camYawPitch=${fmt(state.yaw.toDouble())},${fmt(state.pitch.toDouble())} " +
                "player=${fmt(player.x)},${fmt(player.y)},${fmt(player.z)} " +
                "playerYawPitch=${fmt(player.yRot.toDouble())},${fmt(player.xRot.toDouble())} " +
                "keys=${state.heldKeys()} frames=$frames suppressedEvents=$suppressed"
        }
    }

    /** Mouse turn: moves the camera instead of the player. Returns true when the turn was consumed. */
    fun captureTurn(yawDelta: Double, pitchDelta: Double): Boolean {
        if (!isEnabled()) return false
        if (state.seeded) state.turn(yawDelta, pitchDelta)
        return true
    }

    /** Records the movement keys of this input tick; the caller then passes an empty input to the player. */
    fun captureKeys(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean, jump: Boolean, shift: Boolean) {
        state.setKeys(forward, backward, left, right, jump, shift)
    }

    /** Remembers how many game ticks the coming frame covers, read from the frame's delta tracker. */
    fun beginFrame(realtimeDeltaTicks: Float) {
        frameTicks = realtimeDeltaTicks
    }

    /** Called right after vanilla aligned [camera] with the player: replaces its pose with the flying camera. */
    fun applyToCamera(camera: Camera, setPose: PoseSetter) {
        if (!isEnabled()) return
        if (!state.seeded) {
            val position = camera.position()
            state.seed(position.x, position.y, position.z, camera.yRot(), camera.xRot())
            diagnostics.event(
                "seed cam=${fmt(position.x)},${fmt(position.y)},${fmt(position.z)} " +
                    "yawPitch=${fmt(camera.yRot().toDouble())},${fmt(camera.xRot().toDouble())}",
            )
        }
        state.advance(frameTicks, speed.value())
        frames++
        setPose.set(state.x, state.y, state.z, state.yaw, state.pitch)
    }

    /** Lets the camera mixin hand over its protected pose setters. */
    fun interface PoseSetter {
        fun set(x: Double, y: Double, z: Double, yaw: Float, pitch: Float)
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Freecam")
        val speed = spec.slider("speed")
    }

    companion object {
        @JvmField
        val INSTANCE = FreecamModule(Settings())
    }
}
