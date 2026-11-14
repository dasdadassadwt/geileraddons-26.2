package geiler.addons.client.mixin;

import geiler.addons.client.macros.module.EtherwarpModule;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps the server's etherwarp sound for the Etherwarp module's own; a packet it does not know passes untouched. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerEtherwarpMixin {
	@Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
	private void geileraddons$etherwarpSound(ClientboundSoundPacket packet, CallbackInfo info) {
		if (net.minecraft.client.Minecraft.getInstance().isSameThread() && EtherwarpModule.INSTANCE.onSound(packet)) info.cancel();
	}
}
