package geiler.addons.client.presentation;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;

/**
 * Drives the glyph render state the way {@code BakedSheetGlyph.renderChar} does (begin glyph, one pass per
 * {@code render} call, shadow passes first) and checks that every main pass gets a real gradient color, never the
 * marker color that {@code gradientStyle} puts in the style.
 */
public final class PlayerNameGradientRenderStateChecks {
	private static final List<Integer> RAINBOW = List.of(0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF);

	private PlayerNameGradientRenderStateChecks() { }

	public static void run() {
		for (boolean cyclic : new boolean[] {false, true}) {
			for (boolean shadow : new boolean[] {false, true}) {
				for (boolean bold : new boolean[] {false, true}) checkName(cyclic, shadow, bold);
			}
		}
		checkCopiedMarkerStillResolves();
		checkUnresolvedMarkersAreCounted();
	}

	private static void checkName(boolean cyclic, boolean shadow, boolean bold) {
		int glyphs = 8;
		int base = 0xFFFFFFFF;
		int previous = -1;
		for (int i = 0; i < glyphs; i++) {
			Style source = Style.EMPTY.withBold(bold);
			Style styled = PlayerNameGradientRenderState.gradientStyle(source, RAINBOW, i, glyphs, cyclic, 1.9f, 0.0, 0L);
			TextColor marker = styled.getColor();
			check(marker != null, "a gradient glyph carries a marker color");
			Integer shadowColor = PlayerNameGradientRenderState.shadowColorFor(styled, base, shadow);
			check(shadowColor != null, "a gradient glyph supplies its own shadow color");
			int shadowArgb = shadowColor;
			int passes = (shadowArgb == 0 ? 0 : bold ? 2 : 1) + (bold ? 2 : 1);
			PlayerNameGradientRenderState.beginGlyph(styled, shadowArgb);
			int mainColor = 0;
			for (int pass = 0; pass < passes; pass++) {
				PlayerNameGradientRenderState.beginRenderPass();
				mainColor = PlayerNameGradientRenderState.vertexColor(base, false);
				int shadowPasses = shadowArgb == 0 ? 0 : bold ? 2 : 1;
				if (pass < shadowPasses) {
					check(mainColor == base, "shadow passes keep the original color");
				} else {
					check((mainColor & 0xFFFFFF) != marker.getValue() && (mainColor >>> 24) == 0xFF,
						"a main pass gets a gradient color, not the marker (cyclic=" + cyclic + ",shadow=" + shadow
							+ ",bold=" + bold + ",glyph=" + i + ")");
				}
			}
			PlayerNameGradientRenderState.endGlyph();
			check(i == 0 || mainColor != previous, "neighbouring glyphs get different colors");
			previous = mainColor;
		}
	}

	/** Another mod or a codec round trip may rebuild a TextColor from its value; the gradient must still resolve. */
	private static void checkCopiedMarkerStillResolves() {
		Style styled = PlayerNameGradientRenderState.gradientStyle(Style.EMPTY, RAINBOW, 2, 5, false, 0f, 0.0, 0L);
		Style copied = Style.EMPTY.withColor(styled.getColor().getValue());
		check(copied.getColor() != styled.getColor(), "the copy is a different TextColor instance");
		PlayerNameGradientRenderState.beginGlyph(copied, 0);
		PlayerNameGradientRenderState.beginRenderPass();
		int color = PlayerNameGradientRenderState.vertexColor(0xFFFFFFFF, false);
		PlayerNameGradientRenderState.endGlyph();
		check((color & 0xFFFFFF) != styled.getColor().getValue() && (color & 0xFFFFFF) != 0xFFFFFF,
			"a marker rebuilt from its value still resolves to the gradient");
	}

	private static void checkUnresolvedMarkersAreCounted() {
		long before = PlayerNameGradientRenderState.unresolvedMarkerGlyphs();
		PlayerNameGradientRenderState.beginGlyph(Style.EMPTY.withColor(0x123456), 0);
		PlayerNameGradientRenderState.endGlyph();
		check(PlayerNameGradientRenderState.unresolvedMarkerGlyphs() == before,
			"an ordinary color outside the marker range is not counted as unresolved");
		PlayerNameGradientRenderState.beginGlyph(Style.EMPTY.withColor(0xF0FFF0), 0);
		PlayerNameGradientRenderState.endGlyph();
		check(PlayerNameGradientRenderState.unresolvedMarkerGlyphs() == before + 1,
			"a glyph that still carries an unregistered marker color is counted");
		long[] drained = PlayerNameGradientRenderState.drainCounts();
		check(drained.length == 3 && drained[0] > 0 && drained[1] == before + 1 && drained[2] > 0
			&& PlayerNameGradientRenderState.drainCounts()[1] == 0, "diagnostic counts drain to zero");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
