package geiler.addons.client.mixin;

import geiler.addons.client.macros.etw.EtwController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SHELVED: not listed in geileraddons.client.mixins.json, so it does not apply. The /etw recorder is parked; this is its
 * packet hook, kept so it can be switched on again. It observes the teleport and rotation packets on the game thread
 * before and after they are applied, and never changes them.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerEtwMixin {
	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void geileraddons$etwBeforePosition(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
		if (Minecraft.getInstance().isSameThread()) EtwController.beforePosition(packet);
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void geileraddons$etwAfterPosition(ClientboundPlayerPositionPacket packet, CallbackInfo info) {
		if (Minecraft.getInstance().isSameThread()) EtwController.afterPosition(packet);
	}

	@Inject(method = "handleRotatePlayer", at = @At("HEAD"))
	private void geileraddons$etwBeforeRotation(ClientboundPlayerRotationPacket packet, CallbackInfo info) {
		if (Minecraft.getInstance().isSameThread()) EtwController.beforeRotation(packet);
	}

	@Inject(method = "handleRotatePlayer", at = @At("TAIL"))
	private void geileraddons$etwAfterRotation(ClientboundPlayerRotationPacket packet, CallbackInfo info) {
		if (Minecraft.getInstance().isSameThread()) EtwController.afterRotation(packet);
	}
}
