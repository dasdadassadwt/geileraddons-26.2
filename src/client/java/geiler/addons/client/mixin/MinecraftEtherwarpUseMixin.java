package geiler.addons.client.mixin;

import geiler.addons.client.macros.module.EtherwarpModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click hook of the Etherwarp module: a sneaking right click with an etherwarp item is taken and the game's own use
 * of the item is cancelled. It returns at once when the module is off.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftEtherwarpUseMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void geileraddons$etherwarpUse(CallbackInfo info) {
		if (geiler.addons.client.macros.area.PaintBrush.onUse() || EtherwarpModule.INSTANCE.onUse()) info.cancel();
	}
}
