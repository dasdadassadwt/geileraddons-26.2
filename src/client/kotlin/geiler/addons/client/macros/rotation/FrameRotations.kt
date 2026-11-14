package geiler.addons.client.macros.rotation

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer

/** A rotation that is stepped once per frame by [FrameRotations]. */
interface FrameSteered {
    /** Turns the view by one frame's share; [deltaSeconds] is the real time the frame took. */
    fun advance(player: LocalPlayer, deltaSeconds: Float)
}

/**
 * Pumps every [FrameSteered] rotation once per frame.
 *
 * `Minecraft.runTick` is the frame loop: it runs whatever game ticks are due, then calls
 * `MouseHandler.handleAccumulatedMovement()` and renders. Real mouse input therefore reaches the player once per frame,
 * immediately before the frame is drawn. A rotation written once per tick instead moves the view in twenty steps a
 * second however fast the game is running, which shows as stepping.
 *
 * What this does not change is the rate the server sees: `LocalPlayer` sends its rotation from its own tick, so twenty
 * samples a second is all anyone ever transmits. The difference is in what is on screen.
 */
object FrameRotations {
    /** Longest frame delta to honour, so a stall or a paused game can't carry a turn to its end in one go. */
    private const val MAX_FRAME_SECONDS = 0.1f

    private val ACTIVE = ArrayList<FrameSteered>()
    private var lastNanos = 0L

    /** Registered once per rotation, for the lifetime of the game - there is nothing to unregister. */
    @JvmStatic
    fun register(rotation: FrameSteered) {
        ACTIVE.add(rotation)
    }

    /** Called once per frame, from the same point in the frame at which vanilla applies mouse movement. */
    @JvmStatic
    fun onFrame() {
        val now = System.nanoTime()
        val previous = lastNanos
        lastNanos = now
        if (previous == 0L) return

        val delta = Math.min((now - previous) / 1.0E9f, MAX_FRAME_SECONDS)
        val player = Minecraft.getInstance().player
        if (delta <= 0.0f || player == null) return
        for (rotation in ACTIVE) rotation.advance(player, delta)
    }
}
