package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the local avatar's ordinary name tag render when the camera is outside first person. */
@Mixin(AvatarRenderer.class)
public abstract class ThirdPersonPlayerNameMixin {
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z",
		at = @At("RETURN"), cancellable = true)
	private void geileraddons$showLocalNameInThirdPerson(@Coerce Object avatar, double distanceSquared,
		CallbackInfoReturnable<Boolean> cir) {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null || avatar != client.player
			|| !ThirdPersonCameraModule.INSTANCE.showOwnNameInThirdPerson()) return;
		Player player = client.player;
		if (player.isDiscrete() && distanceSquared >= 1024.0) return;
		cir.setReturnValue(true);
	}
}
