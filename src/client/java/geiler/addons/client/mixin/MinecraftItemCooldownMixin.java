package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ItemCooldownsModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Cooldowns: every right click with an item starts that item's ability cooldown. */
@Mixin(Minecraft.class)
public abstract class MinecraftItemCooldownMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void geileraddons$itemUsed(CallbackInfo ci) {
		ItemCooldownsModule.INSTANCE.onUse();
	}
}
