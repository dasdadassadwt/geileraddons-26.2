package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerPresentationModule;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class PlayerNameTagMixin {
	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void geileraddons$replacePlayerNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (!(entity instanceof Player player) || cir.getReturnValue() == null) return;
		Component replacement = PlayerPresentationModule.INSTANCE.replaceRenderedName(
			player.getName().getString(), cir.getReturnValue());
		if (replacement != cir.getReturnValue()) cir.setReturnValue(replacement);
	}
}
