package geiler.addons.client.mixin;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the stored, unmodified chat component for message identity checks and layout refreshes. */
@Mixin(GuiMessage.class)
public interface GuiMessagePresentationAccessor {
	@Accessor("content")
	Component geileraddons$rawContent();
}
