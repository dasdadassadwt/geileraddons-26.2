package geiler.addons.client.utility

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/** Client-side alerts shared by feature modules: a title with a pling, and a local chat line. */
object PlayerAlerts {
    private const val FADE_IN_TICKS = 5
    private const val STAY_TICKS = 40
    private const val FADE_OUT_TICKS = 10

    /** Shows [title] as a screen title and plays the pling; ignored off the client thread or before the HUD exists. */
    @JvmStatic
    fun alert(title: String, playSound: Boolean = true) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui == null) return
        client.gui.hud.setTimes(FADE_IN_TICKS, STAY_TICKS, FADE_OUT_TICKS)
        client.gui.hud.setTitle(Component.literal(title))
        if (playSound) client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f))
    }

    /** Adds a line to the local chat only; nothing is sent to the server. */
    @JvmStatic
    fun chat(message: String) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui == null) return
        client.gui.hud.chat.addClientSystemMessage(Component.literal(message))
    }

    /** Sends a slashless command such as `pc Mimic Killed!` as the player. */
    @JvmStatic
    fun command(command: String) {
        val client = Minecraft.getInstance()
        val connection = client.player?.connection ?: return
        connection.sendCommand(command)
    }
}
