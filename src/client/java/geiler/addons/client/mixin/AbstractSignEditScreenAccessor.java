package geiler.addons.client.mixin;

import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The typed lines of a sign editor, so Bazaar Buy Presets can fill in an amount. */
@Mixin(AbstractSignEditScreen.class)
public interface AbstractSignEditScreenAccessor {
	@Accessor("messages")
	String[] geiler$messages();

	@Accessor("signField")
	TextFieldHelper geiler$signField();
}
