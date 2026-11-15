package geiler.addons.client.module.impl;

/** Focused behavior checks for Hide Players' pure hide rule and defaults. */
public final class HidePlayersChecks {
	private HidePlayersChecks() { }

	public static void main(String[] args) {
		check(HidePlayersModule.shouldHide(false, false, false, 4.0, 3f, true),
			"a real player inside the distance is hidden");
		check(!HidePlayersModule.shouldHide(false, false, false, 10.0, 3f, true),
			"a real player outside the distance stays visible (3 blocks = 9 squared)");
		check(HidePlayersModule.shouldHide(false, false, false, 9.0, 3f, true),
			"a player exactly at the distance is hidden");
		check(HidePlayersModule.shouldHide(false, false, true, 900.0, 3f, true),
			"Hide all ignores the distance");
		check(!HidePlayersModule.shouldHide(false, false, true, 1.0, 3f, false),
			"NPCs, yourself and non-players are never hidden");
		check(!HidePlayersModule.shouldHide(true, false, true, 1.0, 3f, true),
			"Only in Dungeons keeps players visible outside a dungeon");
		check(HidePlayersModule.shouldHide(true, true, true, 1.0, 3f, true),
			"Only in Dungeons hides inside a dungeon");
		check(HidePlayersModule.shouldHide(false, false, false, 0.0, 0f, true)
			&& !HidePlayersModule.shouldHide(false, false, false, 0.01, 0f, true),
			"distance 0 hides only a player at the exact same position");

		HidePlayersModule module = HidePlayersModule.INSTANCE;
		check(module.booleanSettings().size() == 2
			&& !module.booleanSettings().get(0).value() && !module.booleanSettings().get(1).value()
			&& module.numberSettings().size() == 1 && module.numberSettings().get(0).value() == 3f,
			"defaults: Only in Dungeons off, Hide all off, distance 3");
		check(!module.isEnabled(), "Hide Players is off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
