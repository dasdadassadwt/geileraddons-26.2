package geiler.addons.client.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Narrow access to the current vanilla chat viewport so animated profile lines can be refreshed in place. */
@Mixin(ChatComponent.class)
public interface ChatComponentPresentationAccessor {
	@Accessor("trimmedMessages")
	List<GuiMessage.Line> geileraddons$trimmedMessages();

	@Accessor("allMessages")
	List<GuiMessage> geileraddons$allMessages();

	@Accessor("chatScrollbarPos")
	int geileraddons$scrollbarPosition();

	@Accessor("chatScrollbarPos")
	void geileraddons$setScrollbarPosition(int position);

	@Accessor("newMessageSinceScroll")
	boolean geileraddons$newMessageSinceScroll();

	@Accessor("newMessageSinceScroll")
	void geileraddons$setNewMessageSinceScroll(boolean value);

	@Invoker("getLinesPerPage")
	int geileraddons$linesPerPage();

	@Invoker("getWidth")
	int geileraddons$width();

	@Invoker("getScale")
	double geileraddons$scale();
}
