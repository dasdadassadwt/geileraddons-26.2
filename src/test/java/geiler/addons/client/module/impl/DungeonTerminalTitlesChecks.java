package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.odin.DungeonTerminalBreakdown;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/** Direct settings, Java interop, and title-format checks for Terminal Titles. */
public final class DungeonTerminalTitlesChecks {
	private DungeonTerminalTitlesChecks() { }

	public static void main(String[] args) {
		run();
		System.out.println("Dungeon Terminal Titles checks passed.");
	}

	public static void run() {
		checkModuleSettingsAndJavaInterop();
		checkBreakdownJavaAbi();
		checkTitleCompletionFormatting();
	}

	private static void checkBreakdownJavaAbi() {
		assertRecord(DungeonTerminalBreakdown.Completion.class,
			new String[] { "player", "action", "type", "current", "total" },
			new Class<?>[] { String.class, String.class, String.class, int.class, int.class });
		assertRecord(DungeonTerminalBreakdown.PlayerSummary.class,
			new String[] { "player", "terminals", "levers", "devices" },
			new Class<?>[] { String.class, int.class, int.class, int.class });
		DungeonTerminalBreakdown.Completion nullableCompletion =
			new DungeonTerminalBreakdown.Completion(null, null, null, 0, 0);
		DungeonTerminalBreakdown.PlayerSummary nullablePlayer =
			new DungeonTerminalBreakdown.PlayerSummary(null, 0, 0, 0);
		check(nullableCompletion.player() == null && nullableCompletion.action() == null
				&& nullableCompletion.type() == null && nullablePlayer.player() == null,
			"Java record constructors retain their prior nullable component behavior");
		assertInstanceMethod("observeTitle", DungeonTerminalBreakdown.Completion.class,
			String.class, String.class, boolean.class);
		assertInstanceMethod("replacement", String.class,
			String.class, DungeonTerminalBreakdown.Completion.class);
		assertInstanceMethod("recent", List.class);
		assertInstanceMethod("counts", java.util.Map.class);
		assertInstanceMethod("perPlayer", List.class);
		assertInstanceMethod("reset", void.class);
		try {
			Method total = DungeonTerminalBreakdown.PlayerSummary.class.getDeclaredMethod("total");
			check(Modifier.isPublic(total.getModifiers()) && total.getReturnType() == int.class,
				"player summaries retain the public total() Java method");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("player summary total() is missing", exception);
		}
		DungeonTerminalBreakdown.PlayerSummary summary =
			new DungeonTerminalBreakdown.PlayerSummary("Alice", 2, 3, 4);
		check(summary.total() == 9
				&& "PlayerSummary[player=Alice, terminals=2, levers=3, devices=4]".equals(summary.toString()),
			"player summaries retain their total and Java record string formatting");
	}

	private static void assertRecord(Class<?> type, String[] expectedNames, Class<?>[] expectedTypes) {
		check(type.isRecord(), type.getSimpleName() + " retains Java record metadata");
		check(Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers()),
			type.getSimpleName() + " remains a public static nested type");
		java.lang.reflect.RecordComponent[] components = type.getRecordComponents();
		check(components.length == expectedNames.length, type.getSimpleName() + " keeps its record component count");
		for (int i = 0; i < components.length; i++) {
			check(expectedNames[i].equals(components[i].getName())
					&& expectedTypes[i] == components[i].getType()
					&& components[i].getAccessor().getReturnType() == expectedTypes[i],
				type.getSimpleName() + " keeps component " + expectedNames[i]);
		}
		try {
			type.getConstructor(expectedTypes);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(type.getSimpleName() + " canonical constructor changed", exception);
		}
	}

	private static void assertInstanceMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = DungeonTerminalBreakdown.class.getDeclaredMethod(name, parameterTypes);
			check(Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() == returnType,
				"breakdown method " + name + " retains its public instance JVM signature");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("breakdown method is missing: " + name, exception);
		}
	}

	private static void checkModuleSettingsAndJavaInterop() {
		DungeonTerminalTitlesModule module = DungeonTerminalTitlesModule.INSTANCE;
		check(module instanceof Module, "the Kotlin module remains a Java Module");
		check("Terminal Titles".equals(module.name()), "the registered module name is unchanged");
		check("Terminal Titles".equals(module.configName()), "the persisted module identity is unchanged");
		check("Replaces terminal completion titles with a configurable short format.".equals(module.description()),
			"the player-facing description is unchanged");
		check(module.category() == Category.F7, "Terminal Titles remains in F7");
		check(!module.defaultEnabled(), "fresh installs keep Terminal Titles disabled");

		check(DungeonTerminalTitlesModule.replaceTitles != null, "Java can access the replaceTitles field");
		check("Replace Titles".equals(DungeonTerminalTitlesModule.replaceTitles.name()),
			"the toggle setting ID is unchanged");
		check(!DungeonTerminalTitlesModule.replaceTitles.rawValue()
				&& !DungeonTerminalTitlesModule.replaceTitles.isDebugOnly(),
			"the replacement toggle remains false by default and is not debug-gated");

		TextSetting template = DungeonTerminalTitlesModule.titleTemplate;
		check("Title Template".equals(template.name()), "the template setting ID is unchanged");
		check("&a{type} {current}/{total}".equals(template.value()),
			"the default title template is unchanged");
		check(template.maxLength() == 80, "the title template keeps its 80-character limit");

		List<SettingGroup> groups = module.groups();
		check(groups.size() == 1 && "Title Format".equals(groups.getFirst().name()),
			"the title settings remain under the Title Format group");
		check(groups.getFirst().toggle() == DungeonTerminalTitlesModule.replaceTitles,
			"Replace Titles remains the Title Format group toggle");
		check(groups.getFirst().settings().equals(List.of(
			DungeonTerminalTitlesModule.titleTemplate)),
			"the title template remains the only content row beneath the group toggle");

		assertStaticFinalField("INSTANCE", DungeonTerminalTitlesModule.class);
		assertStaticFinalField("replaceTitles", DungeonTerminalTitlesModule.class);
		assertStaticFinalField("titleTemplate", DungeonTerminalTitlesModule.class);
		try {
			Method callback = DungeonTerminalTitlesModule.class.getDeclaredMethod(
				"recordTitleDecision", long.class, String.class, String.class);
			check(Modifier.isPublic(callback.getModifiers()) && Modifier.isStatic(callback.getModifiers())
					&& callback.getReturnType() == void.class,
				"the title diagnostic entry point keeps its public static Java signature");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("the title diagnostic entry point is missing", exception);
		}
	}

	private static void checkTitleCompletionFormatting() {
		DungeonTerminalBreakdown breakdown = new DungeonTerminalBreakdown();
		DungeonTerminalBreakdown.Completion completion = breakdown.observeTitle(
			"§aAlice activated a terminal! (4/7)", "LocalPlayer", false);
		check(completion != null
				&& "Alice".equals(completion.player())
				&& "activated".equals(completion.action())
				&& "terminal".equals(completion.type())
				&& completion.current() == 4
				&& completion.total() == 7,
			"colored completion titles still produce the same terminal completion data");
		check("Completion[player=Alice, action=activated, type=terminal, current=4, total=7]"
				.equals(completion.toString()),
			"completion records retain Java record string formatting");
		DungeonTerminalBreakdown.Completion unicodeBoundary = breakdown.observeTitle(
			"\u2003Alice activated a terminal! (1/7)", "LocalPlayer", false);
		check(unicodeBoundary != null && "\u2003Alice".equals(unicodeBoundary.player()),
			"player parsing preserves non-ASCII boundary whitespace as Java trim did");
		check("§aterminal 4/7".equals(
				breakdown.replacement("&a{type} {current}/{total}", completion)),
			"the default template retains its color and terminal progress placeholders");
		check("§aAlice completed device 2/3".equals(
				breakdown.replacement("&a{name} {action} {type} {current}/{total}",
					breakdown.observeTitle("Alice completed a device! (2/3)", "LocalPlayer", false))),
			"all supported title placeholders keep their prior replacement behavior");
		check(breakdown.observeTitle("Alice activated a terminal! (4/7)", "LocalPlayer", true) == null,
			"self-only terminal statistics do not accept a teammate title");
		check(breakdown.observeTitle("Alice activated a terminal! (4/7)", "Alice", true) != null,
			"self-only terminal statistics accept the local player's completion");
		check(breakdown.observeTitle("Alice activated a terminal! (0/7)", "Alice", false) == null,
			"invalid completion progress remains rejected");
		check(breakdown.observeTitle("Alice activated a terminal! (2/1)", "Alice", false) == null
			&& breakdown.observeTitle("Alice activated a terminal! (1/33)", "Alice", false) == null
			&& breakdown.observeTitle("Alice activated a terminal! (1/7)", null, true) == null,
			"invalid totals and self-only titles without a local player remain rejected");

		DungeonTerminalBreakdown history = new DungeonTerminalBreakdown();
		for (int i = 0; i < 14; i++) {
			history.observeTitle("Player" + i + " activated a lever! (1/2)", "Self", false);
		}
		check(history.recent().size() == 12
				&& "Player2".equals(history.recent().getFirst().player())
				&& "Player13".equals(history.recent().getLast().player())
				&& history.counts().get("lever") == 14,
			"recent completions stay capped at 12 while aggregate counts retain the full run");
		boolean immutableRecent = false;
		try {
			history.recent().add(completion);
		} catch (UnsupportedOperationException expected) {
			immutableRecent = true;
		}
		boolean immutableCounts = false;
		try {
			history.counts().put("lever", 0);
		} catch (UnsupportedOperationException expected) {
			immutableCounts = true;
		}
		boolean immutablePlayers = false;
		try {
			history.perPlayer().add(new DungeonTerminalBreakdown.PlayerSummary("Player", 0, 0, 0));
		} catch (UnsupportedOperationException expected) {
			immutablePlayers = true;
		}
		check(immutableRecent && immutableCounts && immutablePlayers,
			"public history, count, and per-player snapshots remain immutable copies");
		List<DungeonTerminalBreakdown.Completion> recentSnapshot = history.recent();
		var countsSnapshot = history.counts();
		List<DungeonTerminalBreakdown.PlayerSummary> playerSnapshot = history.perPlayer();
		history.observeTitle("Player14 activated a lever! (1/2)", "Self", false);
		check(recentSnapshot.size() == 12
				&& "Player2".equals(recentSnapshot.getFirst().player())
				&& countsSnapshot.get("lever") == 14
				&& playerSnapshot.size() == 14,
			"history, aggregate, and per-player results remain independent snapshots");
		history.reset();
		check(history.recent().isEmpty() && history.counts().isEmpty() && history.perPlayer().isEmpty(),
			"reset clears recent completions, aggregate counts, and per-player summaries");
	}

	private static void assertStaticFinalField(String name, Class<?> owner) {
		try {
			Field field = owner.getDeclaredField(name);
			check(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
				name + " remains a static final Java field");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Java interop field is missing: " + name, exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
