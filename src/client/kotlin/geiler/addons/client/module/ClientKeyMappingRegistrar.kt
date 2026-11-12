package geiler.addons.client.module

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/** Registers feature keys normally while allowing module-catalog checks to run without a client. */
object ClientKeyMappingRegistrar {
    @JvmStatic
    fun register(mapping: KeyMapping): KeyMapping =
        if (Minecraft.getInstance() == null) mapping else KeyMappingHelper.registerKeyMapping(mapping)
}
