package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;

/** Offline checks for Scrollable Tooltip scroll math, reset rules and Devonian-matching defaults. */
public final class ScrollableTooltipChecks {
	private ScrollableTooltipChecks() { }

	public static void run() {
		ScrollableTooltipModule module = ScrollableTooltipModule.INSTANCE;
		check("Scrollable Tooltip".equals(module.name()) && module.category() == Category.MISCELLANEOUS
			&& !module.isEnabled(), "Scrollable Tooltip is a default-off module");
		var toggles = module.booleanSettings();
		check(toggles.size() == 7 && toggles.get(0).value() && toggles.get(1).value() && toggles.get(2).value()
			&& !toggles.get(3).value() && !toggles.get(4).value() && toggles.get(5).value() && !toggles.get(6).value(),
			"toggle defaults match Devonian: allow h/v/scale and reset on; lock, ignore scale and start top off");
		check(module.actions().size() == 1, "a Reset action is offered");
		check(!module.startsAtTop(), "a disabled module never starts at the top");

		ScrollableTooltipState state = new ScrollableTooltipState();
		check(state.getScale() == 1.0f && state.getX() == 0f && state.getY() == 0f, "state starts neutral");
		check(state.scroll(1.0, false, false, false, true, true, true) && state.getY() == 9f, "wheel up moves y by 9");
		check(state.scroll(-1.0, false, false, false, true, true, true) && state.getY() == 0f, "wheel down moves y back");
		check(state.scroll(1.0, false, true, false, true, true, true) && state.getX() == 9f, "shift+wheel moves x by 9");
		check(state.scroll(1.0, false, false, true, true, true, true) && state.getY() == 4f, "alt shortens the step to 4");
		check(state.scroll(1.0, true, false, false, true, true, true) && Math.abs(state.getScale() - 1.1f) < 1e-5,
			"ctrl+wheel scales by 10 percent");
		check(state.scroll(1.0, true, true, false, true, true, true) && state.getX() == 9f,
			"ctrl wins over shift and the x offset is untouched");
		check(!state.scroll(0.0, false, false, false, true, true, true), "a zero wheel changes nothing");
		check(!state.scroll(1.0, false, true, false, false, true, true) && state.getX() == 9f,
			"horizontal scrolling is gated by its allow flag");
		check(!state.scroll(1.0, false, false, false, true, false, true), "vertical scrolling is gated by its allow flag");
		float scale = state.getScale();
		check(!state.scroll(1.0, true, false, false, true, true, false) && state.getScale() == scale,
			"scaling is gated by its allow flag");

		Object a = new Object();
		Object b = new Object();
		state.hover(a, true);
		state.scroll(1.0, false, false, false, true, true, true);
		float keptScale = state.getScale();
		state.hover(a, true);
		check(state.getY() != 0f, "hovering the same slot keeps the offset");
		state.hover(b, false);
		check(state.getY() != 0f, "a new slot keeps the offset when reset is off");
		state.hover(a, true);
		check(state.getX() == 0f && state.getY() == 0f && state.getScale() == keptScale,
			"a new slot resets the offset but not the scale when reset is on");

		state.reset();
		check(state.getScale() == 1f && state.getX() == 0f && state.getY() == 0f, "reset restores scale 1 and zero offset");
		check(state.effectiveScale() == 1f, "effective scale of a neutral state is 1");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ScrollableTooltipChecks: " + message);
	}
}
