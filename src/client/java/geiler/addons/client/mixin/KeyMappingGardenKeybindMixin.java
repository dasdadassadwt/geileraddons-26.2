package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.module.impl.GardenKeybindsModule;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets Garden Keybinds replace which key drives a control while it is active. */
@Mixin(KeyMapping.class)
public abstract class KeyMappingGardenKeybindMixin {
	@Shadow @Final private String name;
	@Shadow protected InputConstants.Key key;

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void geileraddons$gardenIsDown(CallbackInfoReturnable<Boolean> cir) {
		Boolean override = GardenKeybindsModule.INSTANCE.override(name, key == null ? null : key.getName());
		if (override != null) cir.setReturnValue(override);
	}

	@Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
	private void geileraddons$gardenConsumeClick(CallbackInfoReturnable<Boolean> cir) {
		Boolean override = GardenKeybindsModule.INSTANCE.overrideClick(name, key == null ? null : key.getName());
		if (override != null) cir.setReturnValue(override);
	}
}
