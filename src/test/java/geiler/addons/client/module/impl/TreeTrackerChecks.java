package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.tree.TreeStats;
import geiler.addons.client.tree.TreeTracker;
import geiler.addons.client.tree.TreeType;
import geiler.addons.client.tree.ChatSuppressor;
import geiler.addons.client.tree.ChatText;
import geiler.addons.client.tree.TreeFelled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

/** Focused parity checks for Tree Tracker's Java API, persisted settings, and module behavior. */
public final class TreeTrackerChecks {
	private TreeTrackerChecks() {
	}

	public static void main(String[] arguments) throws Exception {
		checkIdentityAndJavaEntrypoints();
		checkSettingsAndGroups();
		checkSharedChatTextAndSuppression();
		checkTimeoutPauseResumeBoundaries();
		checkHudAndChatBehavior();
		checkHudTotalsAndPreview();
		checkResetActionExecution();
		checkPersistedSessionInterop();
		checkDurationFormatting();
		checkDiagnosticsLifecycleAndBounds();
		System.out.println("Tree Tracker checks passed");
	}

	private static void checkTimeoutPauseResumeBoundaries() {
		long start = 1_000_000L;
		long timeout = 60_000L;
		TreeTracker tracker = new TreeTracker();
		String gift = "You cut 25% of the Helix Tree.";
		assertEquals(TreeType.HELIX, tracker.onChatMessage(gift, start, timeout),
			"the first gift starts the tracked session");
		TreeStats stats = tracker.stats(TreeType.HELIX);
		if (stats.paused(start + timeout - 1, timeout)) {
			throw new AssertionError("the session must remain active immediately before its timeout");
		}
		if (!stats.paused(start + timeout, timeout)) {
			throw new AssertionError("the session must pause at the exact timeout boundary");
		}
		assertEquals(timeout - 1, stats.sessionMillis(start + timeout - 1, timeout),
			"active time advances up to the pause boundary");
		assertEquals(timeout, stats.sessionMillis(start + timeout, timeout),
			"active time stops at the pause boundary");

		assertEquals(TreeType.HELIX, tracker.onChatMessage(gift, start + timeout, timeout),
			"a gift after the pause resumes the same session");
		if (stats.paused(start + timeout, timeout)) {
			throw new AssertionError("the session must be active again when a new gift resumes it");
		}
		assertEquals(2, stats.sessionCount(), "resuming preserves and increments the session count");
		assertEquals(timeout, stats.rawSessionMillis(), "the paused gap is capped at one timeout");
		assertEquals(timeout + 5_000, stats.sessionMillis(start + timeout + 5_000, timeout),
			"the resumed session accumulates its next active gap");
		if (!stats.paused(start + 2 * timeout, timeout)) {
			throw new AssertionError("the resumed session must pause again at its next timeout boundary");
		}
	}

	private static void checkSharedChatTextAndSuppression() {
		assertEquals("Helix Tree", ChatText.plain("\u00a7aHelix\u00a0Tree"),
			"plain chat cleanup drops legacy formatting and normalizes nonbreaking spaces");
		assertEquals("Fig Tree", ChatText.stripForMatch("\u2007\u00a7cFig\u00a0Tree\ue000"),
			"exact-match cleanup removes invisible glyphs and trims normalized whitespace");
		assertEquals("Fig Tree", ChatText.stripForMatch("\u0001Fig Tree\u0001"),
			"exact-match cleanup preserves the frozen Java String.trim behavior");
		assertEquals(TreeType.FIG, TreeType.fromMessage("\u00a7cYou cut 40% of the Fig Tree."),
			"formatted gift lines still resolve to their tree type");
		if (!TreeFelled.matches("\u00a7cTIMBER! You felled the entire Fig Tree!")) {
			throw new AssertionError("formatted tree-felled lines must remain detectable");
		}
		if (!ChatSuppressor.Decision.class.isRecord()) {
			throw new AssertionError("Java callers retain the chat suppression decision record");
		}

		String rule = "\u25ac".repeat(8);
		ChatSuppressor suppressor = new ChatSuppressor();
		ChatSuppressor.Decision opening = suppressor.accept(rule, true, true);
		assertEquals(ChatSuppressor.Action.HOLD, opening.action(), "gift separators remain held until classified");
		if (!suppressor.holding()) throw new AssertionError("the opening gift separator must be retained");
		ChatSuppressor.Decision header = suppressor.accept("TREE GIFT", true, true);
		if (header.emitHeld() || header.action() != ChatSuppressor.Action.HIDE) {
			throw new AssertionError("a gift header must discard its held separator and be hidden");
		}
		assertEquals(ChatSuppressor.Action.HIDE, suppressor.accept("gift detail", true, true).action(),
			"gift-block lines remain hidden");
		assertEquals(ChatSuppressor.Action.HIDE, suppressor.accept(rule, true, true).action(),
			"the gift-block closing separator remains hidden");
		assertEquals(ChatSuppressor.Action.SHOW, suppressor.accept("ordinary", true, true).action(),
			"chat suppression exits a completed gift block");

		ChatSuppressor release = new ChatSuppressor();
		release.accept(rule, true, true);
		ChatSuppressor.Decision ordinary = release.accept("ordinary", true, false);
		if (!ordinary.emitHeld() || ordinary.action() != ChatSuppressor.Action.SHOW) {
			throw new AssertionError("a non-gift line must release the held separator unchanged");
		}
		release.accept(rule, true, true);
		if (!release.endOfTick() || release.holding()) {
			throw new AssertionError("an unresolved separator must be released and cleared at tick end");
		}
	}

	private static void checkIdentityAndJavaEntrypoints() {
		TreeTrackerModule module = TreeTrackerModule.INSTANCE;
		if (module != TreeTrackerModule.INSTANCE
			|| !module.name().equals("Tree Tracker")
			|| !module.configName().equals("Tree Tracker")
			|| !module.description().equals("Counts Helix, Fig and Mangrove gifts and shows gifts per hour.")
			|| module.category() != Category.FORAGING
			|| module.defaultEnabled()
			|| module.isEnabled()) {
			throw new AssertionError("Tree Tracker identity or fresh-install state changed");
		}
		if (!module.id().equals("tree_tracker") || !module.displayName().equals("Tree Tracker")
			|| !module.previewMode().equals("sample")) {
			throw new AssertionError("Tree Tracker HUD identity or disabled preview changed");
		}
		if (module.tracker() == null || module.tracker() != module.tracker()) {
			throw new AssertionError("The Java-visible tracker() entrypoint is not stable");
		}
	}

	private static void checkSettingsAndGroups() {
		TreeTrackerModule module = TreeTrackerModule.INSTANCE;
		List<TextSetting> texts = module.textSettings();
		assertNames(texts, List.of("Pause Timeout (s)"), "text setting IDs");
		if (!texts.get(0).value().equals("60") || texts.get(0).maxLength() != 6) {
			throw new AssertionError("Pause timeout default or input length changed");
		}

		List<BooleanSetting> booleans = module.booleanSettings();
		assertNames(booleans, List.of("Show Totals", "Background"), "boolean setting IDs");
		if (booleans.get(0).value() || !booleans.get(1).value()) {
			throw new AssertionError("Tree Tracker boolean defaults changed");
		}

		List<ModuleAction> actions = module.actions();
		assertNames(actions, List.of("Reset Session"), "action IDs");
		if (actions.get(0).onClick() == null) throw new AssertionError("Reset Session action is missing");

		List<SettingGroup> groups = module.groups();
		assertGroupNames(groups, List.of("Counting", "Display"));
		assertGroup(groups.get(0), List.of("Pause Timeout (s)", "Show Totals", "Reset Session"), "Counting");
		assertGroup(groups.get(1), List.of("Background"), "Display");
	}

	private static void checkHudAndChatBehavior() throws Exception {
		// Use a separate module instance so a check never clears or increments saved player data
		// held by the registered singleton.
		TreeTrackerModule module = newModuleFixture();
		try {
			module.onChatMessage("You cut 25% of the Helix Tree.");
			assertEquals(0, module.tracker().stats(TreeType.HELIX).sessionCount(),
				"disabled modules must ignore gift chat");
			if (module.visible()) throw new AssertionError("The HUD must be hidden before a gift");

			module.setEnabled(true);
			module.onChatMessage("Nothing about a tree happened.");
			module.onChatMessage("You cut 25% of the Helix Tree.");
			module.onChatMessage("You cut 25% of the Helix Tree.");
			assertEquals(1, module.tracker().stats(TreeType.HELIX).sessionCount(),
				"one gift block must count once despite repeated chat lines");
			if (module.tracker().lastGifted() != TreeType.HELIX || !module.visible()
				|| !module.previewMode().equals("live")) {
				throw new AssertionError("The HUD must follow the most recently gifted tree while active");
			}

			module.onChatMessage("You cut 40% of the Fig Tree.");
			assertEquals(1, module.tracker().stats(TreeType.FIG).sessionCount(),
				"a later gift from another tree must be counted");
			if (module.tracker().lastGifted() != TreeType.FIG) {
				throw new AssertionError("The HUD target must follow the most recent gift type");
			}

			module.onChatMessage("You cut 15% of the Mangrove Tree.");
			assertEquals(1, module.tracker().stats(TreeType.MANGROVE).sessionCount(),
				"Mangrove gifts must be counted by the enabled module");
			if (module.tracker().lastGifted() != TreeType.MANGROVE) {
				throw new AssertionError("The HUD target must support Mangrove as the most recent gift type");
			}
		} finally {
			module.setEnabled(false);
			module.tracker().clearAll();
		}
	}

	private static void checkHudTotalsAndPreview() throws Exception {
		TreeTrackerModule module = newModuleFixture();
		TreeTracker tracker = module.tracker();
		assertEquals(TreeType.FIG, tracker.onChatMessage("You cut 40% of the Fig Tree.",
			System.currentTimeMillis(), 60_000L), "the HUD fixture selects Fig");
		tracker.stats(TreeType.FIG).restore(2, 180_000L, 3, 420_000L);

		String[] sessionLines = (String[]) invokePrivate(module, "lines", new Class<?>[0]);
		assertArrayEquals(new String[]{"Tree Gifts", "Fig  40/h", "Fig  2  03:00"}, sessionLines,
			"session mode displays its deterministic count, duration, and rate");

		module.booleanSettings().get(0).setValue(true);
		String[] totalLines = (String[]) invokePrivate(module, "lines", new Class<?>[0]);
		assertArrayEquals(new String[]{"Tree Gifts (Total)", "Fig  30/h", "Fig  5  10:00"}, totalLines,
			"totals mode combines stored and session counts, durations, and rate");
		String[] preview = (String[]) invokePrivate(module, "previewLines", new Class<?>[0]);
		assertArrayEquals(new String[]{"Tree Gifts (Total)", "Helix  24/h", "Helix  2  04:52"}, preview,
			"sample preview follows the totals header without requiring a Font or renderer");
	}

	private static void checkResetActionExecution() throws Exception {
		TreeTrackerModule module = TreeTrackerModule.INSTANCE;
		TreeTracker tracker = module.tracker();
		TreeType previousLastGifted = tracker.lastGifted();
		EnumMap<TreeType, TreeStatsState> saved = new EnumMap<>(TreeType.class);
		for (TreeType type : TreeType.values()) {
			TreeStats stats = tracker.stats(type);
			saved.put(type, snapshot(stats));
		}

		try {
			for (TreeType type : TreeType.values()) {
				tracker.stats(type).restore(0, 0L, 0, 0L);
			}
			tracker.onChatMessage(giftLine(TreeType.HELIX), 10_000L, 60_000L);
			tracker.stats(TreeType.HELIX).restore(2, 5_000L, 3, 7_000L);
			tracker.stats(TreeType.FIG).restore(4, 9_000L, 6, 11_000L);

			module.actions().get(0).onClick().run();
			for (TreeType type : TreeType.values()) {
				TreeStats stats = tracker.stats(type);
				assertEquals(0, stats.rawSessionCount(), type + " session count after reset");
				assertEquals(0L, stats.rawSessionMillis(), type + " session time after reset");
			}
			assertEquals(5, tracker.stats(TreeType.HELIX).rawStoredCount(),
				"reset credits Helix session gifts to stored totals");
			assertEquals(12_000L, tracker.stats(TreeType.HELIX).rawStoredMillis(),
				"reset credits Helix session time to stored totals");
			assertEquals(10, tracker.stats(TreeType.FIG).rawStoredCount(),
				"reset credits Fig session gifts to stored totals");
			assertEquals(20_000L, tracker.stats(TreeType.FIG).rawStoredMillis(),
				"reset credits Fig session time to stored totals");
			if (tracker.lastGifted() != null) throw new AssertionError("reset clears the HUD's last gifted tree");
		} finally {
			for (TreeType type : TreeType.values()) {
				tracker.stats(type).restore(0, 0L, 0, 0L);
			}
			if (previousLastGifted != null) {
				tracker.onChatMessage(giftLine(previousLastGifted), 0L, 60_000L);
			}
			for (TreeType type : TreeType.values()) restore(tracker.stats(type), saved.get(type));
		}
	}

	private static void checkPersistedSessionInterop() throws Exception {
		TreeTrackerModule module = newModuleFixture();
		TreeStats stats = module.tracker().stats(TreeType.MANGROVE);
		stats.restore(3, 4500, 7, 12000);
		assertEquals(3, stats.rawSessionCount(), "Java restore() must retain the saved session count");
		assertEquals(4500L, stats.rawSessionMillis(), "Java restore() must retain the saved session time");
		assertEquals(7, stats.rawStoredCount(), "Java restore() must retain stored totals");
		assertEquals(12000L, stats.rawStoredMillis(), "Java restore() must retain stored elapsed time");

		module.rollOverLoadedSession();
		assertEquals(0, stats.rawSessionCount(), "loaded-session rollover must clear the active session");
		assertEquals(10, stats.rawStoredCount(), "loaded-session rollover must credit session gifts to totals");
		assertEquals(16500L, stats.rawStoredMillis(), "loaded-session rollover must credit session time to totals");
	}

	private static void checkDurationFormatting() {
		assertEquals("00:00", TreeTrackerModule.formatDuration(0), "zero duration formatting");
		assertEquals("00:59", TreeTrackerModule.formatDuration(59_999), "sub-hour duration formatting");
		assertEquals("01:00", TreeTrackerModule.formatDuration(60_000), "minute duration formatting");
		assertEquals("1:00:00", TreeTrackerModule.formatDuration(3_600_000), "hour duration formatting");
		assertEquals("00:00", TreeTrackerModule.formatDuration(-1), "negative duration clamping");
	}

	private static void checkDiagnosticsLifecycleAndBounds() throws Exception {
		TreeTrackerModule module = newModuleFixture();
		setPrivateField(module, "diagnosticTick", 7L);
		setPrivateField(module, "lastStatusTick", 1L);
		setPrivateField(module, "acceptedGifts", 9);
		setPrivateField(module, "duplicateGiftLines", 8);
		setPrivateField(module, "unmatchedChatLines", 7);
		setPrivateField(module, "liveRenderCalls", 6);
		setPrivateField(module, "suppressedEvents", 5);
		setPrivateField(module, "eventBudgetTick", 6L);
		setPrivateField(module, "eventsThisTick", 4);
		setPrivateField(module, "lastRenderX", 3);
		setPrivateField(module, "lastRenderY", 2);
		setPrivateField(module, "lastRenderWidth", 1);
		setPrivateField(module, "lastRenderHeight", 1);
		setPrivateField(module, "lastRenderLines", "stale");

		module.setEnabled(true);
		try {
			assertEquals(7L, numberField(module, "lastStatusTick").longValue(),
				"enable starts the diagnostics status interval at the current tick");
			assertEquals(0, numberField(module, "acceptedGifts").intValue(), "enable clears gift diagnostics");
			assertEquals(0, numberField(module, "duplicateGiftLines").intValue(), "enable clears repeat diagnostics");
			assertEquals(0, numberField(module, "unmatchedChatLines").intValue(), "enable clears unmatched diagnostics");
			assertEquals(0, numberField(module, "liveRenderCalls").intValue(), "enable clears render diagnostics");
			assertEquals(0, numberField(module, "suppressedEvents").intValue(), "enable clears suppression diagnostics");
			assertEquals(Long.MIN_VALUE, numberField(module, "eventBudgetTick").longValue(),
				"enable resets the per-tick event budget");
			assertEquals(0, numberField(module, "eventsThisTick").intValue(), "enable clears event usage");
			assertEquals("none", privateField(module, "lastRenderLines"), "enable clears saved render text");

			for (int i = 0; i < 99; i++) invokePrivate(module, "clientTick", new Class<?>[0]);
			assertEquals(7L, numberField(module, "lastStatusTick").longValue(),
				"the periodic diagnostic status does not fire before 100 enabled ticks");
			setPrivateField(module, "acceptedGifts", 3);
			setPrivateField(module, "duplicateGiftLines", 4);
			setPrivateField(module, "unmatchedChatLines", 5);
			setPrivateField(module, "liveRenderCalls", 6);
			setPrivateField(module, "suppressedEvents", 7);
			invokePrivate(module, "clientTick", new Class<?>[0]);
			assertEquals(107L, numberField(module, "lastStatusTick").longValue(),
				"the periodic status fires on the 100th enabled tick");
			assertEquals(0, numberField(module, "acceptedGifts").intValue(), "status starts a fresh gift window");
			assertEquals(0, numberField(module, "duplicateGiftLines").intValue(), "status starts a fresh repeat window");
			assertEquals(0, numberField(module, "unmatchedChatLines").intValue(), "status starts a fresh unmatched window");
			assertEquals(0, numberField(module, "liveRenderCalls").intValue(), "status starts a fresh render window");
			assertEquals(0, numberField(module, "suppressedEvents").intValue(), "status reports then clears suppression count");

			String[] longLines = {
				"a".repeat(20) + "\r\n" + "b".repeat(120),
				"c".repeat(120), "d".repeat(120), "e".repeat(120),
			};
			invokePrivate(module, "recordLiveRender",
				new Class<?>[]{String[].class, int.class, int.class, int.class},
				(Object) longLines, 10, 20, 96);
			String renderedText = (String) privateField(module, "lastRenderLines");
			assertEquals(320, renderedText.length(), "diagnostic render text stays within its 320-character cap");
			if (renderedText.indexOf('\r') >= 0 || renderedText.indexOf('\n') >= 0) {
				throw new AssertionError("diagnostic render text flattens line breaks");
			}

			for (int i = 0; i < 17; i++) {
				invokePrivate(module, "diagnosticEvent", new Class<?>[]{String.class}, "CHECK_EVENT");
			}
			assertEquals(16, numberField(module, "eventsThisTick").intValue(),
				"diagnostic event output stops at 16 entries per tick");
			assertEquals(1, numberField(module, "suppressedEvents").intValue(),
				"events beyond the per-tick cap are counted as suppressed");
			assertEquals(1_000_000, ((Number) invokePrivate(module, "saturatedIncrement",
				new Class<?>[]{int.class}, 999_999)).intValue(), "diagnostic counters saturate at one million");
			assertEquals(1_000_000, ((Number) invokePrivate(module, "saturatedIncrement",
				new Class<?>[]{int.class}, 1_000_000)).intValue(), "saturated counters remain capped");

			for (int i = 0; i < 100; i++) invokePrivate(module, "clientTick", new Class<?>[0]);
			assertEquals(207L, numberField(module, "lastStatusTick").longValue(),
				"the next status follows another 100 enabled ticks");
			assertEquals(0, numberField(module, "suppressedEvents").intValue(),
				"the next status window clears previously suppressed events");
		} finally {
			module.setEnabled(false);
		}
		if (module.isEnabled()) throw new AssertionError("diagnostics fixture must finish disabled");
		long lastStatusTick = numberField(module, "lastStatusTick").longValue();
		for (int i = 0; i < 100; i++) invokePrivate(module, "clientTick", new Class<?>[0]);
		assertEquals(lastStatusTick, numberField(module, "lastStatusTick").longValue(),
			"disabled modules do not emit periodic diagnostic status records");
	}

	private static TreeTrackerModule newModuleFixture() throws Exception {
		Class<?> settingsType = Class.forName(TreeTrackerModule.class.getName() + "$Settings");
		Constructor<?> settingsConstructor = settingsType.getDeclaredConstructor();
		settingsConstructor.setAccessible(true);
		Object settings = settingsConstructor.newInstance();
		Constructor<TreeTrackerModule> moduleConstructor = TreeTrackerModule.class.getDeclaredConstructor(settingsType);
		moduleConstructor.setAccessible(true);
		return moduleConstructor.newInstance(settings);
	}

	private static TreeStatsState snapshot(TreeStats stats) throws Exception {
		return new TreeStatsState(stats.rawSessionCount(), stats.rawSessionMillis(), stats.rawStoredCount(),
			stats.rawStoredMillis(), (Boolean) privateField(stats, "started"),
			((Number) privateField(stats, "lastGift")).longValue());
	}

	private static void restore(TreeStats stats, TreeStatsState state) throws Exception {
		stats.restore(state.sessionCount(), state.sessionMillis(), state.storedCount(), state.storedMillis());
		setPrivateField(stats, "started", state.started());
		setPrivateField(stats, "lastGift", state.lastGift());
	}

	private static String giftLine(TreeType type) {
		return "You cut 25% of the " + type.displayName() + " Tree.";
	}

	private static Object privateField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static Number numberField(Object target, String name) throws Exception {
		return (Number) privateField(target, name);
	}

	private static void setPrivateField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object invokePrivate(Object target, String name, Class<?>[] parameterTypes,
		Object... arguments) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
		method.setAccessible(true);
		return method.invoke(target, arguments);
	}

	private static void assertArrayEquals(String[] expected, String[] actual, String label) {
		if (!Arrays.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
		}
	}

	private record TreeStatsState(int sessionCount, long sessionMillis, int storedCount, long storedMillis,
		boolean started, long lastGift) {
	}

	private static void assertGroup(SettingGroup group, List<String> settingNames, String label) {
		assertNames(group.settings(), settingNames, label + " group rows");
		if (group.toggle() != null || group.collapsedByDefault() || !group.children().isEmpty()) {
			throw new AssertionError(label + " group presentation changed");
		}
	}

	private static void assertNames(List<? extends Setting> actual, List<String> expected, String label) {
		List<String> names = new ArrayList<>();
		for (Setting setting : actual) names.add(setting.name());
		if (!names.equals(expected)) throw new AssertionError(label + " changed: " + names);
	}

	private static void assertGroupNames(List<SettingGroup> actual, List<String> expected) {
		List<String> names = new ArrayList<>();
		for (SettingGroup group : actual) names.add(group.name());
		if (!names.equals(expected)) throw new AssertionError("setting group IDs changed: " + names);
	}

	private static void assertEquals(long expected, long actual, String label) {
		if (actual != expected) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
