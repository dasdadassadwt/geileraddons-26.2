package geiler.addons.client.mixin;

import geiler.addons.client.chat.ChatChannelsBackend;
import geiler.addons.client.module.impl.PlayerPresentationModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Refreshes only viewport messages with profile names when their rendered presentation changes. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentPresentationMixin {
	private static final int MAX_TRIMMED_LINES = 100;
	private static final int MAX_REMEMBERED_MESSAGES = 128;
	private long geileraddons$lastPresentationRevision = Long.MIN_VALUE;
	private final Set<GuiMessage> geileraddons$processedMessages = Collections.newSetFromMap(new IdentityHashMap<>());
	private final ArrayDeque<GuiMessage> geileraddons$processedOrder = new ArrayDeque<>();
	private final Set<GuiMessage> geileraddons$presentedMessages = Collections.newSetFromMap(new IdentityHashMap<>());
	private final ArrayDeque<GuiMessage> geileraddons$presentedOrder = new ArrayDeque<>();

	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V",
		at = @At("HEAD"))
	private void geileraddons$refreshPresentedChatLines(GuiGraphicsExtractor graphics, Font font,
		int x, int y, int width, ChatComponent.DisplayMode displayMode, boolean focused, CallbackInfo ci) {
		ChatChannelsBackend.refreshVisibleMessageFilter((ChatComponent) (Object) this);
		if (ChatChannelsBackend.isWindowOpen()
			|| Minecraft.getInstance().options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
		PlayerPresentationModule presentation = PlayerPresentationModule.INSTANCE;
		long revision = presentation.chatComponentPresentationRevision() * 31L
			+ ChatChannelsBackend.nativeFilterRevision();
		if (revision != geileraddons$lastPresentationRevision) {
			geileraddons$lastPresentationRevision = revision;
			geileraddons$processedMessages.clear();
			geileraddons$processedOrder.clear();
		}
		if (!presentation.hasActiveNamePresentationProfiles() && geileraddons$presentedMessages.isEmpty()) return;

		List<GuiMessage.Line> lines = ((ChatComponentPresentationAccessor) (Object) this)
			.geileraddons$trimmedMessages();
		if (lines.isEmpty() || font == null) return;

		ChatComponentPresentationAccessor access = (ChatComponentPresentationAccessor) (Object) this;
		int scroll = focused ? Math.max(0, access.geileraddons$scrollbarPosition()) : 0;
		int pageStart = Math.min(scroll, lines.size());
		int pageEnd = Math.min(lines.size(), pageStart + Math.max(0, access.geileraddons$linesPerPage()));
		Set<GuiMessage> affected = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<GuiMessage> currentlyPresented = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int index = pageStart; index < pageEnd; index++) {
			GuiMessage message = lines.get(index).parent();
			if (geileraddons$processedMessages.contains(message)) continue;
			geileraddons$rememberMessage(message, geileraddons$processedMessages, geileraddons$processedOrder);

			Component raw = ((GuiMessagePresentationAccessor) (Object) message).geileraddons$rawContent();
			boolean hasPresentation = presentation.chatMessageHasNamePresentation(raw);
			boolean wasPresented = geileraddons$presentedMessages.contains(message);
			if (hasPresentation) {
				geileraddons$rememberMessage(message, geileraddons$presentedMessages, geileraddons$presentedOrder);
				currentlyPresented.add(message);
				affected.add(message);
			} else if (wasPresented) {
				affected.add(message);
			}
		}
		if (affected.isEmpty()) return;

		double scale = access.geileraddons$scale();
		if (!Double.isFinite(scale) || scale <= 0.0) return;
		int lineWidth = Math.max(1, (int) Math.floor(access.geileraddons$width() / scale));
		List<GuiMessage.Line> refreshed = new ArrayList<>(lines.size());
		Set<GuiMessage> added = Collections.newSetFromMap(new IdentityHashMap<>());
		for (GuiMessage.Line oldLine : lines) {
			GuiMessage message = oldLine.parent();
			if (!affected.contains(message)) {
				refreshed.add(oldLine);
				continue;
			}
			if (!added.add(message)) continue;
			List<FormattedCharSequence> wrapped = message.splitLines(font, lineWidth);
			for (int index = wrapped.size() - 1; index >= 0; index--) {
				refreshed.add(new GuiMessage.Line(message, wrapped.get(index), index == wrapped.size() - 1));
			}
		}
		while (refreshed.size() > MAX_TRIMMED_LINES) refreshed.removeLast();
		lines.clear();
		lines.addAll(refreshed);
		int maxScroll = Math.max(0, lines.size() - access.geileraddons$linesPerPage());
		if (scroll > maxScroll) access.geileraddons$setScrollbarPosition(maxScroll);
		for (GuiMessage message : affected) {
			if (!currentlyPresented.contains(message)) {
				geileraddons$forgetMessage(message, geileraddons$presentedMessages, geileraddons$presentedOrder);
			}
		}
	}

	private static void geileraddons$rememberMessage(GuiMessage message, Set<GuiMessage> messages,
		ArrayDeque<GuiMessage> order) {
		if (!messages.add(message)) return;
		order.addLast(message);
		while (order.size() > MAX_REMEMBERED_MESSAGES) messages.remove(order.removeFirst());
	}

	private static void geileraddons$forgetMessage(GuiMessage message, Set<GuiMessage> messages,
		ArrayDeque<GuiMessage> order) {
		messages.remove(message);
		order.removeIf(candidate -> candidate == message);
	}
}
