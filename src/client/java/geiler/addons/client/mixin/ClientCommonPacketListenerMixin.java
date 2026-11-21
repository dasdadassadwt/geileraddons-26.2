package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DungeonAssistModule;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the server ping packet stream separately from local END_CLIENT_TICK events. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
	/**
	 * Terminal Simulator firewall: while a simulator screen is open, container packets are dropped before they are
	 * sent, so the server can never see a simulator click or close.
	 */
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void geileraddons$termSimFirewall(net.minecraft.network.protocol.Packet<?> packet, CallbackInfo ci) {
		if (geiler.addons.client.termsim.TermSimGuard.shouldDrop(packet)) ci.cancel();
	}

	@Inject(method = "handlePing", at = @At("TAIL"))
	private void geileraddons$countServerPingPacket(ClientboundPingPacket packet, CallbackInfo ci) {
		DungeonAssistModule.INSTANCE.onServerPingPacket(packet.getId());
	}
}
