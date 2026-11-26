package geiler.addons.client.module.impl

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The Invincibility Timers easter egg: a totem pop where the flying item is a poop and the sound is a squish plus a toot.
 * It runs vanilla's own totem path (`ClientPacketListener.handleEntityEvent`, event 35): the same tracking particle burst
 * and `GameRenderer.displayItemActivation`, so the path, scale curve and 40-tick timing are vanilla's.
 */
object PoopPop {
    /** Item definition `assets/geileraddons/items/poop.json`, drawn from the SVG-sourced `textures/item/poop.png`. */
    const val ITEM_MODEL_PATH = "poop"
    private const val NAMESPACE = "geileraddons"
    private const val TOTEM_PARTICLES = 30
    private val SOUND_PATHS = listOf("poop.pop", "poop.toot")

    @JvmStatic
    fun shouldPlay(poopPopOn: Boolean, procTracked: Boolean): Boolean = poopPopOn && procTracked

    @JvmStatic
    fun soundNames(): List<String> = SOUND_PATHS

    private val stack: ItemStack by lazy {
        ItemStack(Items.TOTEM_OF_UNDYING).also {
            it.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(NAMESPACE, ITEM_MODEL_PATH))
        }
    }

    /** Plays the cutscene for the local player; returns a short result for the diagnostics log. */
    fun play(volume: Float): String {
        val client = Minecraft.getInstance()
        val player = client.player ?: return "no_player"
        client.particleEngine.createTrackingEmitter(player, ParticleTypes.TOTEM_OF_UNDYING, TOTEM_PARTICLES)
        for (name in SOUND_PATHS) {
            val event = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(NAMESPACE, name))
            client.soundManager.play(SimpleSoundInstance.forUI(event, 1.0f, volume))
        }
        client.gameRenderer.displayItemActivation(stack)
        return "played"
    }
}
