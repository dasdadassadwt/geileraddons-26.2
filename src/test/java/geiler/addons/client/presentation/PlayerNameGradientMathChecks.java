package geiler.addons.client.presentation;

import java.util.List;

/** Focused checks for continuous gradient math, boundary behavior, and animation timing. */
public final class PlayerNameGradientMathChecks {
	private PlayerNameGradientMathChecks() { }

	public static void main(String[] args) {
		List<Integer> palette = List.of(0xFF0000, 0x00FF00, 0x0000FF);
		int left = PlayerNameGradientMath.colorAtGlyph(palette, 0, 1, 0.0, 0.0, false);
		int middle = PlayerNameGradientMath.colorAtGlyph(palette, 0, 1, 0.5, 0.0, false);
		int right = PlayerNameGradientMath.colorAtGlyph(palette, 0, 1, 1.0, 0.0, false);
		check(left == 0xFF0000 && middle == 0x00FF00 && right == 0x0000FF,
			"one-character names receive distinct left, middle, and right colors");

		int firstGlyphRight = PlayerNameGradientMath.colorAtGlyph(palette, 0, 2, 1.0, 0.0, false);
		int secondGlyphLeft = PlayerNameGradientMath.colorAtGlyph(palette, 1, 2, 0.0, 0.0, false);
		check(firstGlyphRight == secondGlyphLeft && firstGlyphRight == 0x00FF00,
			"adjacent glyphs meet at the same smoothly interpolated boundary color");

		double phase = PlayerNameGradientMath.animatedPhase(0.0, 1_000_000_000L, 1_500_000_000L, 1.0f);
		check(Math.abs(phase - 0.5) < 0.000001,
			"animation phase advances smoothly from the time the transformed name was created");

		check(PlayerNameGradientMath.glyphStart(8, 4) == 0.75
			&& PlayerNameGradientMath.glyphEnd(-1, 4) == 0.25
			&& PlayerNameGradientMath.glyphStart(8, 0) == 0.0
			&& PlayerNameGradientMath.glyphEnd(8, 0) == 1.0,
			"glyph positions clamp to the name bounds and handle an empty name");
		check(PlayerNameGradientMath.sampleRgb(null, 0.5, false) == 0xFFFFFF
			&& PlayerNameGradientMath.sampleRgb(List.of(), 0.5, false) == 0xFFFFFF
			&& PlayerNameGradientMath.sampleRgb(List.of(0xFF123456), 0.5, false) == 0x123456,
			"empty palettes fall back to white and a single palette color drops alpha");

		List<Integer> blackAndWhite = List.of(0x000000, 0xFFFFFF);
		check(PlayerNameGradientMath.sampleRgb(blackAndWhite, -1.0, false) == 0x000000
			&& PlayerNameGradientMath.sampleRgb(blackAndWhite, 2.0, false) == 0xFFFFFF
			&& PlayerNameGradientMath.sampleRgb(blackAndWhite, 1.0, true) == 0x000000
			&& PlayerNameGradientMath.sampleRgb(blackAndWhite, 0.75, true) == 0x808080,
			"static gradients clamp while cyclic gradients wrap and interpolate across their seam");
		check(PlayerNameGradientMath.colorAtGlyph(blackAndWhite, 0, 1, -2.0, 0.0, false) == 0x000000
			&& PlayerNameGradientMath.colorAtGlyph(blackAndWhite, 0, 1, 2.0, 0.0, false) == 0xFFFFFF,
			"within-glyph positions clamp to the left and right edge");

		double reversedTimePhase = PlayerNameGradientMath.animatedPhase(1.25, 2_000_000_000L,
			1_000_000_000L, 1.0f);
		check(reversedTimePhase == 0.25
			&& PlayerNameGradientMath.animatedPhase(1.25, 0L, 10_000_000_000L, 0.0f) == 1.25
			&& PlayerNameGradientMath.animatedPhase(1.25, 0L, 10_000_000_000L, Float.NaN) == 1.25,
			"reversed time clamps elapsed to zero before phase normalization; invalid speed preserves the raw phase");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
