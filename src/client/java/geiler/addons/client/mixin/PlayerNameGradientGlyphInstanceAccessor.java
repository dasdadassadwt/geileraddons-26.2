package geiler.addons.client.mixin;

import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the style and render colors retained by a prepared glyph. */
@Mixin(targets = "net.minecraft.client.gui.font.glyphs.BakedSheetGlyph$GlyphInstance")
public interface PlayerNameGradientGlyphInstanceAccessor {
	@Accessor("style")
	Style geileraddons$getStyle();

	@Accessor("shadowColor")
	int geileraddons$getShadowColor();
}
