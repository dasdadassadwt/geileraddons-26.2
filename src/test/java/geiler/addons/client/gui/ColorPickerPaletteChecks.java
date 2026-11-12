package geiler.addons.client.gui;

import geiler.addons.client.module.ColorSetting;

/** Checks pointer-to-color behavior used by both Click GUI and the shared picker screen. */
public final class ColorPickerPaletteChecks {
	private ColorPickerPaletteChecks() { }

	public static void run() {
		ColorSetting color = new ColorSetting("picker", "picker", 32, 96, 160, 128);
		ColorPickerPalette.applySquare(color, 30, 50, 10, 20, 41, 61);
		checkClose(color.saturation(), 0.5f, "square saturation");
		checkClose(color.brightness(), 0.5f, "square brightness");

		ColorPickerPalette.applyHue(color, 15, 10, 11);
		checkClose(color.hue(), 0.5f, "hue selection");
		ColorPickerPalette.applySquare(color, 100, -50, 10, 20, 41, 61);
		checkClose(color.saturation(), 1.0f, "square right clamp");
		checkClose(color.brightness(), 1.0f, "square top clamp");
		ColorPickerPalette.applySquare(color, -50, 100, 10, 20, 41, 61);
		checkClose(color.saturation(), 0.0f, "square left clamp");
		checkClose(color.brightness(), 0.0f, "square bottom clamp");

		ColorPickerPalette.applyAlpha(color, 15, 10, 11);
		check(color.alpha() == 128, "alpha midpoint rounds to the nearest integer");
		ColorPickerPalette.applyAlpha(color, -10, 10, 11);
		check(color.alpha() == 0, "alpha lower bound clamps");
		ColorPickerPalette.applyAlpha(color, 20, 10, 11);
		check(color.alpha() == 255, "alpha upper bound clamps");

		ColorPickerPalette.applyHue(color, 7, 7, 1);
		checkClose(color.hue(), 0.0f, "single-pixel hue bar remains bounded");
		ColorPickerPalette.applySquare(color, 7, 7, 7, 7, 1, 1);
		checkClose(color.saturation(), 0.0f, "single-pixel square saturation");
		checkClose(color.brightness(), 1.0f, "single-pixel square brightness");
	}

	private static void checkClose(float actual, float expected, String label) {
		if (Math.abs(actual - expected) > 0.00001f) {
			throw new AssertionError(label + " expected " + expected + " but got " + actual);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
