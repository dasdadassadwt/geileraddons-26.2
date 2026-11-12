package geiler.addons.client.mixin;

import geiler.addons.client.chat.ChatChannelsBackend;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops the in-memory channel log on disconnect so another server cannot inherit old chat. */
@Mixin(ClientPacketListener.class)
public abstract class ChatChannelsDisconnectMixin {
	@Inject(method = "close()V", at = @At("TAIL"))
	private void geileraddons$clearLocalChannelHistory(CallbackInfo ci) {
		ChatChannelsBackend.clearForDisconnect();
	}
}
