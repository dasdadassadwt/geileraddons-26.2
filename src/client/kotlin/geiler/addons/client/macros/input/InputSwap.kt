package geiler.addons.client.macros.input

import net.minecraft.client.player.ClientInput
import net.minecraft.client.player.LocalPlayer

/**
 * Puts [WalkInput] in place of the player's keyboard input while a macro walks, and puts the real input back afterwards.
 * Only one macro may hold it at a time; whoever starts second stops the first.
 */
class InputSwap {
    private var controlled: LocalPlayer? = null
    private var saved: ClientInput? = null
    private var input: WalkInput? = null

    /** The walk input of this player, installing it first if it is not in place. */
    fun attach(player: LocalPlayer): WalkInput {
        val installed = input
        if (installed != null && controlled === player && player.input === installed) return installed
        release()
        controlled = player
        saved = player.input
        val created = WalkInput(player)
        input = created
        player.input = created
        return created
    }

    fun release() {
        val player = controlled
        val walk = input
        val original = saved
        if (player != null && walk != null && player.input === walk && original != null) player.input = original
        controlled = null
        saved = null
        input = null
    }
}
