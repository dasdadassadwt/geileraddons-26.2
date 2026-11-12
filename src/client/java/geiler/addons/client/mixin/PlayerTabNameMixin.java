package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.PlayerPresentationModule;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabNameMixin {
	@Redirect(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"))
	private Component geileraddons$replaceTabPlayerName(PlayerTabOverlay overlay, PlayerInfo info) {
		Component renderedName = overlay.getNameForDisplay(info);
		if (info == null || renderedName == null) return renderedName;
		return PlayerPresentationModule.INSTANCE.replaceRenderedName(info.getProfile().name(), renderedName);
	}
}
