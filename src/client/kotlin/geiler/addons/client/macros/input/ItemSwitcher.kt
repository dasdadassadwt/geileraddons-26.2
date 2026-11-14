package geiler.addons.client.macros.input

import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Util
import net.minecraft.world.entity.player.Inventory
import java.util.concurrent.ThreadLocalRandom

/**
 * Changes the held hotbar slot after a pause.
 *
 * Whoever wants a slot says so every tick; the pause is counted from the first time a different slot was asked for, not
 * from every tick it is asked for again, so repeating the request is free. Asking for another slot while one is pending
 * starts the pause over. The game sends the change itself - from `MultiPlayerGameMode.tick` and before any use - so
 * nothing is sent from here.
 */
class ItemSwitcher {
    private var wanted = -1
    private var dueAtMillis = 0L

    /**
     * Asks for a 0-based hotbar slot. A slot that is already held is taken at once, without the pause; for any other the
     * pause is drawn anew between the bounds (in milliseconds), so no two changes wait the same.
     */
    fun want(player: LocalPlayer, slot: Int, delayMinMillis: Int, delayMaxMillis: Int) {
        if (!Inventory.isHotbarSlot(slot) || slot == wanted) return
        wanted = slot
        dueAtMillis = if (player.inventory.selectedSlot == slot) 0L else Util.getMillis() + drawDelay(delayMinMillis, delayMaxMillis)
    }

    /** Moves to the wanted slot once its pause is over. Call once per tick. */
    fun tick(player: LocalPlayer) {
        if (wanted >= 0 && Util.getMillis() >= dueAtMillis && player.inventory.selectedSlot != wanted) {
            player.inventory.selectedSlot = wanted
        }
    }

    /** Whether the wanted slot is the one held now. */
    fun ready(player: LocalPlayer): Boolean = wanted >= 0 && player.inventory.selectedSlot == wanted

    /** Whether this slot is held now, whatever was asked for. */
    fun holding(player: LocalPlayer, slot: Int): Boolean = player.inventory.selectedSlot == slot

    fun clear() {
        wanted = -1
        dueAtMillis = 0L
    }

    companion object {
        private fun drawDelay(delayMinMillis: Int, delayMaxMillis: Int): Long {
            val low = maxOf(0, delayMinMillis)
            val high = maxOf(low, delayMaxMillis)
            return low + (ThreadLocalRandom.current().nextDouble() * (high - low)).toLong()
        }
    }
}
