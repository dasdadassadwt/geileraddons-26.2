package geiler.addons.client.macros.input

import net.minecraft.client.Minecraft
import java.util.function.DoubleSupplier

/**
 * Clicks one mouse button at a rate inside a configured range, through [SimulatedMouse].
 *
 * A click here is a press and, a tick later, its release: the button is held for the rest of the tick it was pressed in,
 * like a finger that has not come up yet. When more than one click is due in a tick the ones before the last are released
 * again at once, so each of them is a press of its own.
 *
 * It never decides whether clicking is a good idea. The caller says so every tick, and has to say no the moment it is not
 * - an attack on whatever the crosshair has drifted onto is a block broken in creative.
 */
class CpsClicker(
    private val button: SimulatedMouse.Button,
    lowerCps: DoubleSupplier,
    upperCps: DoubleSupplier,
    randomness: DoubleSupplier,
) {
    private val schedule = ClickSchedule(lowerCps, upperCps, randomness)
    private var held = false

    /** The button is kept down by [hold], not clicked. */
    private var holding = false

    /**
     * Call once per tick.
     *
     * @param wanted whether to be clicking right now
     * @return how many clicks went out this tick
     */
    fun tick(client: Minecraft, wanted: Boolean): Int {
        if (holding) {
            SimulatedMouse.release(client, button)
            holding = false
        }
        if (held) {
            SimulatedMouse.release(client, button)
            held = false
        }
        if (!wanted) {
            schedule.reset()
            return 0
        }

        val clicks = schedule.due(System.nanoTime(), MAX_CLICKS_PER_TICK)
        for (i in 0 until clicks) {
            if (held) SimulatedMouse.release(client, button)
            SimulatedMouse.press(client, button)
            held = true
        }
        return clicks
    }

    /**
     * Keeps the button down instead of clicking it, call once per tick for as long as it is to stay down: one press, and
     * the game repeats the use of the item on its own, as it does for a finger kept on the button. [tick] and [release]
     * let go of it.
     */
    fun hold(client: Minecraft) {
        if (held) {
            SimulatedMouse.release(client, button)
            held = false
        }
        if (!holding) {
            SimulatedMouse.press(client, button)
            holding = true
        }
    }

    /** Lets go of the button and forgets the schedule. */
    fun release(client: Minecraft) {
        tick(client, false)
    }

    companion object {
        /** Most clicks one tick may carry, however far behind the schedule is. */
        private const val MAX_CLICKS_PER_TICK = 3
    }
}
