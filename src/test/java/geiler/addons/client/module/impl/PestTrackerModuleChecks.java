package geiler.addons.client.module.impl;

import geiler.addons.client.hud.HudElement;
import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.farming.GardenCurrentPlotParser;
import geiler.addons.client.farming.InfestedPlotTracker;
import geiler.addons.client.farming.PestCooldownText;
import geiler.addons.client.farming.PestSpawnChatParser;
import geiler.addons.client.farming.PestWidgetReader;
import geiler.addons.client.farming.PersistentHourlyStatsStore;
import geiler.addons.client.farming.TabWidgetText;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.IntStream;

/** Direct registration, config-surface, and Java ABI checks for the Pest Tracker Kotlin port. */
public final class PestTrackerModuleChecks {
	private PestTrackerModuleChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkCurrentPlotParserParity();
		checkIdentityAndInterop();
		checkWidgetAndStoreAbi();
		checkCooldownValueAbi();
		checkSpawnTitleText();
		checkSettingsAndGroups();
		checkWarpKeyAndActions();
		checkInfestedPlotTrackerParity();
		checkDisconnectClearsSessionPlots();
	}

	private static void checkCurrentPlotParserParity() {
		try {
			Method parse = GardenCurrentPlotParser.class.getMethod("parse", String.class);
			Method normalize = GardenCurrentPlotParser.class.getMethod("normalize", String.class);
			check(parse.getReturnType() == java.util.Optional.class
					&& Modifier.isPublic(parse.getModifiers()) && Modifier.isStatic(parse.getModifiers())
					&& normalize.getReturnType() == java.util.Optional.class
					&& Modifier.isPublic(normalize.getModifiers()) && Modifier.isStatic(normalize.getModifiers()),
				"current-plot parser preserves its public static Optional Java API");
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Current-plot parser Java API changed", missing);
		}

		check(GardenCurrentPlotParser.parse(null).isEmpty()
				&& GardenCurrentPlotParser.parse("").isEmpty()
				&& GardenCurrentPlotParser.parse("\u2003\t\n").isEmpty()
				&& GardenCurrentPlotParser.normalize(null).isEmpty()
				&& GardenCurrentPlotParser.normalize(" \t\n").isEmpty(),
			"null and blank parser inputs remain rejected");
		check("The Barn".equals(GardenCurrentPlotParser.parse(
			"§aCurrent\u00a0Plot: tHe\u00a0bArN").orElseThrow()),
			"scoreboard parsing still strips formatting and special spaces and canonicalizes The Barn");
		check("East Plot".equals(GardenCurrentPlotParser.parse(
			"\u0001Plot - East Plot\u0001").orElseThrow())
				&& "East Plot".equals(GardenCurrentPlotParser.normalize("\u0001East Plot\u0001").orElseThrow()),
			"current-plot parsing keeps Java String.trim behavior for U+0001 boundaries");
		check("East Plot".equals(GardenCurrentPlotParser.parse(
			"\u00a0Plot - East Plot\u00a0").orElseThrow())
				&& "East Plot".equals(GardenCurrentPlotParser.normalize("\u00a0East Plot\u00a0").orElseThrow()),
			"NBSP boundaries are normalized by ChatText before current-plot parsing");
		check("East Plot".equals(GardenCurrentPlotParser.parse(
			"Plot - East Plot\nCurrent Plot: West Plot").orElseThrow())
				&& "South Plot".equals(GardenCurrentPlotParser.parse(
					"Plot - South! Plot\ncurrent plot : South Plot").orElseThrow()),
			"scoreboard parsing returns the first matching line and skips rejected lines");
		String maxName = "A".repeat(32);
		check("North_Plot-1".equals(GardenCurrentPlotParser.parse(
			"cUrReNt\tPlOt : North_Plot-1").orElseThrow())
				&& maxName.equals(GardenCurrentPlotParser.parse("Plot - " + maxName).orElseThrow())
				&& GardenCurrentPlotParser.parse("Plot - " + maxName + "a").isEmpty()
				&& GardenCurrentPlotParser.parse("Plot - North/West").isEmpty()
				&& GardenCurrentPlotParser.parse("Plot - caf\u00e9").isEmpty(),
			"scoreboard label case, 32-character name bound, and accepted name characters stay exact");

		check(maxName.equals(GardenCurrentPlotParser.normalize(maxName).orElseThrow())
				&& GardenCurrentPlotParser.normalize(maxName + "a").isEmpty()
				&& "east plot".equals(GardenCurrentPlotParser.normalize("§aPLOT - east plot").orElseThrow())
				&& "The Barn".equals(GardenCurrentPlotParser.normalize("tHe bArN").orElseThrow())
				&& "Plot -East".equals(GardenCurrentPlotParser.normalize("Plot -East").orElseThrow())
				&& GardenCurrentPlotParser.normalize("South/West").isEmpty()
				&& GardenCurrentPlotParser.normalize("_North Plot").isEmpty(),
			"normalization retains exact prefix handling, ordinary name casing, length bounds, and rejection rules");
	}

	private static void checkIdentityAndInterop() {
		PestTrackerModule module = PestTrackerModule.INSTANCE;
		check(module instanceof Module && module instanceof HudElement,
			"module retains its Java-visible Module and HUD interfaces");
		check("Pest Tracker".equals(module.name())
				&& "Pest Tracker".equals(module.configName())
				&& "Pest Tracker".equals(module.displayName())
				&& "Shows pest cooldowns, spawn timing, kill rates, and recent infested plots.".equals(module.description())
				&& "pest_tracker".equals(module.id())
				&& module.category() == Category.FARMING
				&& !module.defaultEnabled()
				&& !module.isEnabled()
				&& module.isWorkInProgress(),
			"module identity, default state, category, and work-in-progress status remain stable");

		check(PestTrackerModule.INSTANCE == module && module.stats() != null
				&& module.spawnAverage() != null && module.infestedPlots() != null,
			"singleton and state accessors remain available to Java callers");
		module.onChatMessage(null); // A null callback remains an ignored message and needs no game state.
		checkPublicMethod("tick");
		checkPublicMethod("onChatMessage", String.class);
		checkPublicMethod("onCropAction");
		checkPublicMethod("onDisconnect");
		checkPublicMethod("warpToLatestInfestedPlot");
		checkPublicMethod("persistFinalTotalsSnapshot");
		try {
			Method keybind = PestTrackerModule.class.getMethod("warpToInfestedPlotKeybind");
			check(keybind.getReturnType() == net.minecraft.client.KeyMapping.class
					&& java.lang.reflect.Modifier.isStatic(keybind.getModifiers()),
				"configurable warp key keeps its static Java ABI");
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Missing Java-visible warp key method", missing);
		}
	}

	private static void checkWidgetAndStoreAbi() {
		try {
			PestWidgetReader.class.getConstructor();
			PestWidgetReader.class.getConstructor(java.util.function.Supplier.class);
			Method read = PestWidgetReader.class.getMethod("read");
			Method readCooldown = PestWidgetReader.class.getMethod("readCooldown");
			Method parseWidget = TabWidgetText.class.getMethod("parse", List.class);
			Method widgetBoundary = TabWidgetText.class.getDeclaredMethod("isOtherWidget", String.class);
			check(read.getReturnType() == java.util.Optional.class
					&& readCooldown.getReturnType() == java.util.Optional.class
					&& !Modifier.isStatic(read.getModifiers())
					&& !Modifier.isStatic(readCooldown.getModifiers())
					&& parseWidget.getReturnType() == java.util.Optional.class
					&& Modifier.isPublic(parseWidget.getModifiers()) && Modifier.isStatic(parseWidget.getModifiers())
					&& widgetBoundary.getReturnType() == boolean.class && Modifier.isStatic(widgetBoundary.getModifiers()),
				"tab widget reader and parser preserve their Java entry point descriptors");
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Pest widget reader Java ABI changed", missing);
		}

		List<List<String>> snapshots = List.of(
			List.of("Pests:", "Plots: 4"),
			List.of("Pests:", "Plots: None"),
			List.of("Pests:", "Cooldown: 2m 3s"));
		int[] suppliedSnapshots = {0};
		PestWidgetReader reader = new PestWidgetReader(() -> snapshots.get(suppliedSnapshots[0]++));
		check(reader.read().equals(java.util.Optional.of(java.util.Set.of(4))),
			"the injected supplier provides an ordered infested-plot snapshot");
		check(reader.read().equals(java.util.Optional.of(java.util.Set.of())),
			"an explicit empty plot row remains a decisive clear observation");
		check(reader.read().isEmpty() && suppliedSnapshots[0] == 3,
			"a partial widget remains unknown and each read requests a fresh ordered snapshot");
		PestWidgetReader cooldownReader = new PestWidgetReader(() -> snapshots.get(2));
		PestCooldownText.Observation cooldown = cooldownReader.readCooldown().orElseThrow();
		check(cooldown.state() == PestCooldownText.State.COUNTDOWN && cooldown.remainingSeconds() == 123,
			"the reader's injectable ordered rows still feed the cooldown parser");

		checkPublicStaticMethod("loadPests", PersistentHourlyStatsStore.Snapshot.class);
		checkPublicStaticMethod("loadSlayer", PersistentHourlyStatsStore.Snapshot.class);
		checkPublicStaticMethod("savePests", void.class, int.class, long.class);
		checkPublicStaticMethod("saveSlayer", void.class, int.class, long.class);
		try {
			Constructor<PersistentHourlyStatsStore.Snapshot> constructor =
				PersistentHourlyStatsStore.Snapshot.class.getConstructor();
			Field kills = PersistentHourlyStatsStore.Snapshot.class.getField("kills");
			Field activeNanos = PersistentHourlyStatsStore.Snapshot.class.getField("activeNanos");
			check(Modifier.isPublic(constructor.getModifiers())
					&& Modifier.isPublic(kills.getModifiers()) && kills.getType() == int.class
					&& Modifier.isPublic(activeNanos.getModifiers()) && activeNanos.getType() == long.class,
				"hourly snapshot retains Gson's public no-arg DTO contract");

			PersistentHourlyStatsStore.Snapshot snapshot = constructor.newInstance();
			snapshot.kills = 7;
			snapshot.activeNanos = 11L;
			com.google.gson.JsonObject json = com.google.gson.JsonParser
				.parseString(ClientJsonFile.GSON.toJson(snapshot)).getAsJsonObject();
			check(json.size() == 2 && json.has("kills") && json.has("activeNanos")
					&& json.get("kills").getAsInt() == 7 && json.get("activeNanos").getAsLong() == 11L,
				"hourly stats JSON keeps the legacy kills and activeNanos fields");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Hourly stats snapshot Java/Gson ABI changed", exception);
		}
	}

	private static void checkCooldownValueAbi() {
		try {
			Method parse = PestCooldownText.class.getMethod("parse", List.class);
			Constructor<PestCooldownText.Observation> constructor =
				PestCooldownText.Observation.class.getConstructor(PestCooldownText.State.class, int.class);
			Method state = PestCooldownText.Observation.class.getMethod("state");
			Method remainingSeconds = PestCooldownText.Observation.class.getMethod("remainingSeconds");
			check(parse.getReturnType() == java.util.Optional.class
					&& Modifier.isPublic(parse.getModifiers()) && Modifier.isStatic(parse.getModifiers())
					&& Modifier.isPublic(constructor.getModifiers())
					&& state.getReturnType() == PestCooldownText.State.class
					&& remainingSeconds.getReturnType() == int.class,
				"cooldown parser and observation keep their public Java API");
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Cooldown parser Java value API changed", missing);
		}

		check(PestCooldownText.parse(null).isEmpty() && PestCooldownText.parse(List.of()).isEmpty(),
			"null and empty widget snapshots remain unavailable");
		PestCooldownText.Observation countdown = PestCooldownText.parse(
			List.of("Pests:", "Cooldown: 2m 3s")).orElseThrow();
		check(countdown.state() == PestCooldownText.State.COUNTDOWN && countdown.remainingSeconds() == 123,
			"minute and second cooldown rows keep their parsed duration");
		check(PestCooldownText.parse(List.of("Pests:", "Cooldown: 9s")).orElseThrow().remainingSeconds() == 9
				&& PestCooldownText.parse(List.of("Pests:", "Cooldown: READY")).orElseThrow().state()
					== PestCooldownText.State.READY
				&& PestCooldownText.parse(List.of("Pests:", "Cooldown: MAX PESTS")).orElseThrow().state()
					== PestCooldownText.State.MAX_PESTS,
			"seconds, ready, and max-pests cooldown rows retain their states");
		check(PestCooldownText.parse(List.of("Pests:", "Cooldown: 3s", "Pests:")).isEmpty(),
			"duplicate Pests headers remain ambiguous");
		try {
			PestCooldownText.parse(java.util.Arrays.asList("Pests:", null));
			throw new AssertionError("null widget rows must retain the prior null failure");
		} catch (NullPointerException expected) {
			// The Java parser passed null rows through ChatText.stripForMatch, which throws.
		}

		PestCooldownText.Observation normalized = new PestCooldownText.Observation(null, -4);
		PestCooldownText.Observation equalValue =
			new PestCooldownText.Observation(PestCooldownText.State.UNKNOWN, 0);
		check(normalized.equals(equalValue) && normalized.hashCode() == equalValue.hashCode()
				&& normalized.state() == PestCooldownText.State.UNKNOWN && normalized.remainingSeconds() == 0
				&& normalized.toString().equals("Observation[state=UNKNOWN, remainingSeconds=0]"),
			"Observation preserves null-state normalization, clamping, equality, hash code, and record text");
	}

	private static void checkSpawnTitleText() {
		try {
			Method formatter = PestTrackerModule.class.getDeclaredMethod(
				"formatSpawnAlert", PestSpawnChatParser.Spawn.class);
			formatter.setAccessible(true);
			PestTrackerModule module = PestTrackerModule.INSTANCE;
			String single = (String) formatter.invoke(module, PestSpawnChatParser.parseSpawn(
				"GROSS! A Pest has appeared in Plot - 4!").orElseThrow());
			check("1 pest spawned in Plot 4.".equals(single),
				"spawn-title text names a single pest and resolves its numbered plot");
			String multiple = (String) formatter.invoke(module, PestSpawnChatParser.parseSpawn(
				"YUCK! 4 Pests have spawned in Plot - West Plot!").orElseThrow());
			check("4 pests spawned in West Plot.".equals(multiple),
				"spawn-title text uses plural wording and preserves named plots");
			String offline = (String) formatter.invoke(module, PestSpawnChatParser.parseSpawn(
				"GROSS! While you were offline, Pests spawned in Plots Plot - 4, Plot - 8 and The Barn!")
				.orElseThrow());
			check("Pests spawned while you were offline in Plot 4, Plot 8, and The Barn.".equals(offline),
				"offline spawn-title text lists readable locations without implying an exact count");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Pest spawn title formatter changed", exception);
		}
	}

	private static void checkPublicStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = PersistentHourlyStatsStore.class.getMethod(name, parameterTypes);
			check(method.getReturnType() == returnType
					&& Modifier.isPublic(method.getModifiers())
					&& Modifier.isStatic(method.getModifiers()),
				"hourly store method descriptor remains public static: " + name);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Missing hourly store method: " + name, missing);
		}
	}

	private static void checkSettingsAndGroups() {
		PestTrackerModule module = PestTrackerModule.INSTANCE;
		check(names(module.booleanSettings()).equals(List.of(
				"Show Totals", "Background", "Filter HUD by Held Tool", "Farming Tools", "Pest Vacuum",
				"Pest Lasso", "Exclude AFK Gaps", "Use Custom Cooldown", "Finnegan Perk", "Pest Spawn Alert",
				"Cooldown Warning Alert", "Cooldown Ready Alert", "Chat Alert", "Title Alert", "Sound Alert"))
				&& module.booleanSettings().stream().map(BooleanSetting::rawValue).toList().equals(List.of(
					false, true, true, true, true, false, true, false, false, false, false, false, true, false, false)),
			"boolean setting IDs, order, and fresh-install defaults remain stable");

		List<NumberSetting> numbers = module.numberSettings();
		check(names(numbers).equals(List.of(
					"AFK Gap Limit (s)", "Custom Cooldown (s)", "Cooldown Warning Lead (s)"))
				&& numbers.stream().map(NumberSetting::value).toList().equals(List.of(30.0f, 135.0f, 5.0f))
				&& numbers.get(0).min() == 5.0f && numbers.get(0).max() == 3600.0f
				&& numbers.get(1).min() == 30.0f && numbers.get(1).max() == 600.0f
				&& numbers.get(2).min() == 1.0f && numbers.get(2).max() == 30.0f
				&& numbers.stream().allMatch(NumberSetting::isInteger),
			"number setting IDs, ranges, integer behavior, and defaults remain stable");
		check(module.colorSettings().isEmpty() && module.choiceSettings().isEmpty() && module.textSettings().isEmpty(),
			"module keeps its original setting types");

		List<SettingGroup> groups = module.groups();
		check(groups.stream().map(SettingGroup::name).toList().equals(List.of(
					"Rates", "Pest Timer", "HUD Filter", "Alerts", "Infested Plots"))
				&& names(groups.get(0).settings()).equals(List.of("Show Totals", "Reset Session Stats"))
				&& names(groups.get(1).settings()).equals(List.of(
					"Use Custom Cooldown", "Custom Cooldown (s)", "Cooldown Warning Lead (s)",
					"Finnegan Perk", "Exclude AFK Gaps", "AFK Gap Limit (s)"))
				&& names(groups.get(2).settings()).equals(List.of(
					"Filter HUD by Held Tool", "Farming Tools", "Pest Vacuum", "Pest Lasso", "Background"))
				&& names(groups.get(3).settings()).equals(List.of(
					"Pest Spawn Alert", "Cooldown Warning Alert", "Cooldown Ready Alert",
					"Chat Alert", "Title Alert", "Sound Alert"))
				&& names(groups.get(4).settings()).equals(List.of(
					"Warp to Latest Infested Plot", "Warp Key")),
			"setting group labels and row order remain stable");
	}

	private static void checkWarpKeyAndActions() {
		PestTrackerModule module = PestTrackerModule.INSTANCE;
		check("key.keyboard.g".equals(PestTrackerModule.warpToInfestedPlotKeybind().getDefaultKey().getName()),
			"warp key remains registered as G by default");
		check(module.actions().stream().map(ModuleAction::label).toList().equals(List.of(
					"Reset Session Stats", "Warp to Latest Infested Plot")),
			"reset and warp actions keep their labels; the warp key is an inline key row");
	}

	private static void checkDisconnectClearsSessionPlots() {
		PestTrackerModule module = PestTrackerModule.INSTANCE;
		check(module.infestedPlots().observeSpawn(
				new PestSpawnChatParser.Spawn(2, List.of("Plot 1"), false), 1L),
			"a Pest spawn adds a session infested plot before disconnect");
		module.onDisconnect();
		check(module.infestedPlots().snapshot().isEmpty() && module.infestedPlots().latest().isEmpty(),
			"disconnect clears all session infested-plot warp targets");
	}

	private static void checkInfestedPlotTrackerParity() {
		try {
			check(InfestedPlotTracker.class.getConstructor() != null
					&& InfestedPlotTracker.class
						.getMethod("observeSpawn", PestSpawnChatParser.Spawn.class, long.class)
						.getReturnType() == boolean.class
					&& InfestedPlotTracker.class.getMethod("observeCleanPlot", String.class)
						.getReturnType() == boolean.class
					&& InfestedPlotTracker.class.getMethod("newestWarpTarget", String.class)
						.getReturnType() == java.util.Optional.class
					&& InfestedPlotTracker.class.getMethod("contains", String.class).getReturnType() == boolean.class
					&& InfestedPlotTracker.class.getMethod("latest").getReturnType() == java.util.Optional.class
					&& InfestedPlotTracker.class.getMethod("snapshot").getReturnType() == List.class
					&& InfestedPlotTracker.class.getMethod("clear").getReturnType() == void.class
					&& InfestedPlotTracker.class.getMethod("onDisconnect").getReturnType() == void.class,
				"tracker constructor and Java method ABI remain stable");
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Missing InfestedPlotTracker Java ABI", missing);
		}

		Class<?> record = InfestedPlotTracker.InfestedPlot.class;
		check(record.isRecord()
				&& java.util.Arrays.stream(record.getRecordComponents())
					.map(java.lang.reflect.RecordComponent::getName).toList()
					.equals(List.of("name", "lastSeenNanos", "pestCount")),
			"nested infested-plot value remains a Java record with the same components");
		try {
			check(record.getDeclaredConstructor(String.class, long.class, int.class) != null
					&& record.getMethod("name").getReturnType() == String.class
					&& record.getMethod("lastSeenNanos").getReturnType() == long.class
					&& record.getMethod("pestCount").getReturnType() == int.class
					&& new InfestedPlotTracker.InfestedPlot("Plot 1", 10L, 2).toString()
						.equals("InfestedPlot[name=Plot 1, lastSeenNanos=10, pestCount=2]"),
				"nested infested-plot Java constructor and component accessors remain stable");
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Missing nested infested-plot Java record ABI", missing);
		}

		InfestedPlotTracker tracker = new InfestedPlotTracker();
		check(!tracker.observeSpawn(null, 1L)
				&& !tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, List.of(), false), 1L)
				&& !tracker.observeCleanPlot(null)
				&& !tracker.observeCleanPlot(" "),
			"null, empty, or normalized-empty observations are ignored");
		InfestedPlotTracker trimParityTracker = new InfestedPlotTracker();
		String controlPaddedPlot = "\u0001Plot 4\u0001";
		check(trimParityTracker.observeSpawn(
				new PestSpawnChatParser.Spawn(1, List.of(controlPaddedPlot), false), 1L)
				&& trimParityTracker.contains("Plot 4"),
			"plot-key trimming removes leading and trailing characters through U+0020 like Java String.trim");
		check(tracker.observeSpawn(new PestSpawnChatParser.Spawn(-4, List.of("§aPlot - East Plot"), false), 10L)
				&& tracker.observeSpawn(new PestSpawnChatParser.Spawn(2, List.of("West Plot"), false), 20L),
			"spawn observations preserve formatting-insensitive plot keys and accept later evidence");
		check(tracker.contains("plot east plot") && tracker.latest().orElseThrow().name().equals("West Plot")
				&& tracker.latest().orElseThrow().pestCount() == 2,
			"latest observation and case-insensitive prefixed lookup remain correct");
		check(tracker.snapshot().stream().map(InfestedPlotTracker.InfestedPlot::name).toList()
				.equals(List.of("West Plot", "§aPlot - East Plot"))
				&& tracker.newestWarpTarget("Plot - East Plot").orElseThrow().name().equals("West Plot")
				&& tracker.newestWarpTarget("West Plot").orElseThrow().name().equals("§aPlot - East Plot"),
			"snapshots sort newest-first and warp selection prefers another infested plot with a single-plot fallback");
		check(tracker.observeCleanPlot("PLOT EAST PLOT")
				&& !tracker.contains("East Plot")
				&& tracker.latest().orElseThrow().name().equals("West Plot"),
			"clean confirmation removes normalized names and refreshes latest state");
		try {
			tracker.snapshot().clear();
			throw new AssertionError("snapshot must be immutable");
		} catch (UnsupportedOperationException expected) {
			// Snapshot is a defensive immutable copy, matching List.copyOf in the Java implementation.
		}

		tracker.clear();
		List<String> firstBatch = IntStream.range(0, 25).mapToObj(i -> "Plot " + i).toList();
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(1, firstBatch, false), 30L);
		tracker.observeSpawn(new PestSpawnChatParser.Spawn(3, List.of("Plot 25"), false), 40L);
		check(tracker.snapshot().size() == 25
				&& !tracker.contains("Plot 0")
				&& tracker.contains("Plot 1")
				&& tracker.contains("Plot 25")
				&& tracker.latest().orElseThrow().name().equals("Plot 25"),
			"bounded plot history evicts the oldest observation and retains the latest target");
		check(tracker.observeSpawn(new PestSpawnChatParser.Spawn(5, List.of("Plot 1"), false), 50L)
				&& tracker.latest().orElseThrow().name().equals("Plot 1")
				&& tracker.latest().orElseThrow().lastSeenNanos() == 50L
				&& tracker.latest().orElseThrow().pestCount() == 5,
			"a repeated plot observation replaces the value while retaining its key");
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static void checkPublicMethod(String name, Class<?>... parameterTypes) {
		try {
			PestTrackerModule.class.getMethod(name, parameterTypes);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError("Missing Java-visible Pest Tracker method: " + name, missing);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
