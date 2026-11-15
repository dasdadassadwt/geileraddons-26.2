package geiler.addons.client.gui;

/** Offline checks for the close lifecycle curve, panel scale, and configured durations. */
public final class ClickGuiMotionChecks {
	private ClickGuiMotionChecks() { }

	public static void run() {
		checkSettingResolution();
		checkProfile(ClickGuiMotion.NONE, 0, 0, 0, 1.0f);
		checkProfile(ClickGuiMotion.REDUCED, 140, 220, 120, 0.90f);
		checkProfile(ClickGuiMotion.EXPRESSIVE, 280, 420, 200, 0.72f);
		checkOpeningCurves();
		checkAnimationHelpers();
		checkCardEntrance();
		check(closeNear(ClickGuiMotion.EXPRESSIVE.closeEase(0.5f), 0.75f),
			"closing uses the specified quadratic ease-out");
	}

	/** Cards enter with a stagger that outlasts the category slide, so the entrance clock must outlive the slide. */
	private static void checkCardEntrance() {
		ClickGuiMotion expressive = ClickGuiMotion.EXPRESSIVE;
		check(expressive.cardEntrance(0, -5L) == 0.0f && expressive.cardEntrance(5, 0L) == 0.0f,
			"a card waiting for its stagger delay stays at the start of its entrance");
		check(expressive.cardEntrance(0, expressive.transitionMillis()) == 1.0f,
			"the first card has finished when the category slide ends");
		float lateCard = expressive.cardEntrance(5, expressive.transitionMillis());
		check(lateCard > 0.0f && lateCard < 1.0f,
			"the last staggered card is still entering when the slide ends, so snapping its clock would make it jump");
		check(expressive.cardEntrance(9, 10_000L) == 1.0f && expressive.cardEntrance(99, 10_000L) == 1.0f,
			"every card index finishes, staggering only the first six");
		check(ClickGuiMotion.NONE.cardEntrance(5, 0L) == 1.0f && ClickGuiMotion.REDUCED.cardEntrance(5, 0L) == 0.0f,
			"None has no entrance; Reduced has no stagger but still eases in");
	}

	private static void checkAnimationHelpers() {
		check(ClickGuiMotion.elapsedMillis(1_000_000L, 5_999_999L) == 4
			&& ClickGuiMotion.elapsedMillis(5_000_000L, 1_000_000L) == 0,
			"elapsed animation time remains floored to milliseconds and nonnegative");
		check(ClickGuiMotion.smoothToggle(-1.0f) == 0.0f
			&& ClickGuiMotion.smoothToggle(0.5f) == 0.5f
			&& ClickGuiMotion.smoothToggle(2.0f) == 1.0f,
			"toggle animation keeps its clamped smoothstep curve");

		double reducedExpected = 100.0 * (1.0 - Math.exp(-60.0 / 60.0));
		double expressiveExpected = 100.0 * (1.0 - Math.exp(-60.0 / 95.0));
		check(Math.abs(ClickGuiMotion.REDUCED.settleScrollVisual(0.0, 100.0, 60) - reducedExpected) < 0.0000001
			&& Math.abs(ClickGuiMotion.EXPRESSIVE.settleScrollVisual(0.0, 100.0, 60) - expressiveExpected) < 0.0000001,
			"each profile keeps its current frame-rate-independent scroll settling time constant");
		check(ClickGuiMotion.REDUCED.settleScrollVisual(99.99, 100.0, 0) == 100.0
			&& ClickGuiMotion.NONE.settleScrollVisual(0.0, 100.0, 0) == 100.0,
			"scroll animation snaps near its target and None applies the target immediately");
	}

	private static void checkSettingResolution() {
		check(ClickGuiMotion.fromSetting("None") == ClickGuiMotion.NONE,
			"the saved None display name resolves to the immediate profile");
		check(ClickGuiMotion.fromSetting("Reduced") == ClickGuiMotion.REDUCED,
			"the saved Reduced display name resolves to its profile");
		check(ClickGuiMotion.fromSetting("Expressive") == ClickGuiMotion.EXPRESSIVE,
			"the saved Expressive display name resolves to its profile");
		check(ClickGuiMotion.fromSetting(null) == ClickGuiMotion.EXPRESSIVE
			&& ClickGuiMotion.fromSetting("unknown") == ClickGuiMotion.EXPRESSIVE,
			"missing or unknown config values keep the safe Expressive fallback");
		check(!ClickGuiMotion.NONE.animated() && ClickGuiMotion.REDUCED.animated()
			&& ClickGuiMotion.EXPRESSIVE.animated(), "only None disables animation");
		check(ClickGuiMotion.NONE.cardStaggerMillis() == 0 && ClickGuiMotion.NONE.hoverMillis() == 0
			&& ClickGuiMotion.NONE.hoverAmplitude() == 0.0f
			&& ClickGuiMotion.REDUCED.cardStaggerMillis() == 0
			&& ClickGuiMotion.REDUCED.hoverMillis() == 180
			&& ClickGuiMotion.REDUCED.hoverAmplitude() == 0.65f
			&& ClickGuiMotion.EXPRESSIVE.cardStaggerMillis() == 28
			&& ClickGuiMotion.EXPRESSIVE.hoverMillis() == 280
			&& ClickGuiMotion.EXPRESSIVE.hoverAmplitude() == 1.35f,
			"card stagger and hover settings retain all profile-specific values");
	}

	private static void checkOpeningCurves() {
		check(ClickGuiMotion.NONE.ease(-1.0f) == 1.0f
			&& ClickGuiMotion.NONE.spring(2.0f) == 1.0f,
			"None remains at unit progress regardless of input");
		check(closeNear(ClickGuiMotion.REDUCED.ease(0.5f), 0.875f)
			&& closeNear(ClickGuiMotion.EXPRESSIVE.ease(0.5f), 0.96875f),
			"opening keeps the configured cubic and quintic easing curves");
		check(ClickGuiMotion.REDUCED.spring(0.0f) == 0.0f
			&& ClickGuiMotion.REDUCED.spring(1.0f) == 1.0f
			&& closeNear(ClickGuiMotion.EXPRESSIVE.spring(0.0f), 0.0f)
			&& closeNear(ClickGuiMotion.EXPRESSIVE.spring(1.0f), 1.0f),
			"spring profiles start and end at the original panel scale");
		check(ClickGuiMotion.EXPRESSIVE.spring(0.5f) > 1.08f
			&& ClickGuiMotion.EXPRESSIVE.spring(0.5f) < 1.10f,
			"Expressive keeps its small entrance overshoot");
		check(Float.isNaN(ClickGuiMotion.EXPRESSIVE.ease(Float.NaN)),
			"non-finite progress preserves the original NaN curve result");
	}

	private static void checkProfile(ClickGuiMotion motion, int transitionMillis,
		int lifecycleMillis, int closeMillis, float closedScale) {
		check(motion.transitionMillis() == transitionMillis, motion + " keeps its transition duration");
		check(motion.lifecycleMillis() == lifecycleMillis, motion + " keeps its opening duration");
		check(motion.closeMillis() == closeMillis, motion + " uses its configured close duration");
		if (motion == ClickGuiMotion.NONE) {
			check(motion.closeProgress(0) == 1.0f && motion.closeScale(1) == 1.0f,
				"None still closes immediately at unit scale");
			return;
		}

		check(motion.closeProgress(0) == 0.0f, motion + " begins at the open state");
		check(motion.closeProgress(-50) == 0.0f, motion + " clamps a negative elapsed duration");
		check(motion.closeProgress(closeMillis - 1) < 1.0f,
			motion + " does not finish before its close endpoint");
		check(motion.closeProgress(closeMillis) == 1.0f,
			motion + " reaches completion at its configured close endpoint");
		check(motion.closeProgress(closeMillis + 50) == 1.0f,
			motion + " clamps after its close endpoint");

		float lastProgress = -1.0f;
		float lastScale = Float.POSITIVE_INFINITY;
		for (int step = 0; step <= 1000; step++) {
			float input = step / 1000.0f;
			float progress = motion.closeEase(input);
			float scale = motion.closeScale(progress);
			check(progress >= lastProgress, motion + " close progress is monotonic");
			check(scale <= lastScale, motion + " close panel scale is monotonic");
			lastProgress = progress;
			lastScale = scale;
		}
		check(closeNear(motion.closeScale(1.0f), closedScale),
			motion + " ends at its configured closed scale");
	}

	private static boolean closeNear(float actual, float expected) {
		return Math.abs(actual - expected) < 0.00001f;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
