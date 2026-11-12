package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DungeonOdinAssistModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the server ping packet stream separately from local END_CLIENT_TICK events. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	@Inject(method = "handlePing", at = @At("TAIL"))
	private void geileraddons$countServerPingPacket(ClientboundPingPacket packet, CallbackInfo ci) {
		DungeonOdinAssistModule.INSTANCE.onServerPingPacket(packet.getId());
	}
}
