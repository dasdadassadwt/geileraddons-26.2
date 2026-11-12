package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerSizeModule;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class PlayerAvatarSizeMixin {
	@Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
		at = @At("TAIL"))
	private void geileraddons$applyProfileScale(AvatarRenderState state, PoseStack pose, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (level == null || state == null || pose == null) return;
		Entity entity = level.getEntity(state.id);
		if (!(entity instanceof Player player)) return;
		PlayerSizeModule.SizeScale scale = PlayerSizeModule.INSTANCE.sizeScale(player.getGameProfile().name());
		if (!scale.isUnit()) pose.scale(scale.x(), scale.y(), scale.z());
	}
}
