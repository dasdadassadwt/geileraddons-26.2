package geiler.addons.client.module.impl

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * Plays a registered UI sound by its id string. Returns false when the id does not parse or is not
 * registered; callers keep their own diagnostics. Call on the client thread.
 */
object ModuleSounds {
    @JvmStatic
    fun play(id: String?, volume: Float = 1f, pitch: Float = 1f): Boolean {
        val identifier = Identifier.tryParse(id ?: return false) ?: return false
        val event = BuiltInRegistries.SOUND_EVENT.getValue(identifier) ?: return false
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
        return true
    }
}
