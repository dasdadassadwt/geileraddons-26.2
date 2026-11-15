package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonFloor;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestBook;
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStore;
import geiler.addons.client.dungeon.DungeonSplitTimeMode;
import geiler.addons.client.dungeon.odin.DungeonSplitEntryBuffer;
import geiler.addons.client.dungeon.odin.DungeonSplitTracker;
import geiler.addons.client.hud.HudElement;
import geiler.addons.client.config.ModConfigMigrations;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Focused checks for the Run Splits module identity, saved settings, Java ABI, and lifecycle. */
public final class DungeonRunSplitsModuleChecks {
	private DungeonRunSplitsModuleChecks() { }

	public static void run() {
		checkIdentityAndJavaAbi();
		checkSplitHelpersJavaAbi();
		checkSettingsAndGroups();
		checkHudLifecycleAndSnapshotCopies();
		checkLiveSplitOrderingAndVisibility();
		checkLocalPersonalBestColumnOutput();
		checkProfessorSplitAggregation();
		checkLegacySplitTimingMigration();
	}

	private static void checkLegacySplitTimingMigration() {
		String prefix = DungeonRunSplitsModule.INSTANCE.configName() + ".";
		var oldTicks = new java.util.HashMap<String, Boolean>();
		var oldTickChoices = new java.util.HashMap<String, String>();
		oldTickChoices.put("Dungeon Odin Helpers.Split Timing Mode", "CLIENT_TICKS");
		oldTicks.put("Dungeon Odin Helpers.Show Tick Timing", false);
		require(ModConfigMigrations.migrateLegacyDungeonSplitTiming(oldTicks, oldTickChoices)
				&& "CLIENT_TICKS".equals(oldTickChoices.get(prefix + "Count Source"))
				&& Boolean.FALSE.equals(oldTicks.get(prefix + "Show Count Value"))
				&& Boolean.TRUE.equals(oldTicks.get(prefix + "Show Tick Time"))
				&& Boolean.FALSE.equals(oldTicks.get(prefix + "Show Real Time")),
			"legacy client-tick mode preserves the old count choice and selects only its matching display");

		var currentTicks = new java.util.HashMap<String, Boolean>();
		var priorChoices = new java.util.HashMap<String, String>();
		priorChoices.put("Dungeon Odin Helpers.Split Timing Mode", "REAL_TIME");
		priorChoices.put("Dungeon Odin Helpers.Tick Count Source", "SERVER_PING_PACKETS");
		currentTicks.put(prefix + "Show Tick Time", true);
		currentTicks.put(prefix + "Show Real Time", false);
		currentTicks.put(prefix + "Show Count Value", true);
		require(ModConfigMigrations.migrateLegacyDungeonSplitTiming(currentTicks, priorChoices)
				&& "SERVER_PING_PACKETS".equals(priorChoices.get(prefix + "Count Source"))
				&& Boolean.TRUE.equals(currentTicks.get(prefix + "Show Tick Time"))
				&& Boolean.FALSE.equals(currentTicks.get(prefix + "Show Real Time"))
				&& Boolean.TRUE.equals(currentTicks.get(prefix + "Show Count Value")),
			"an interim saved count source is retained and current split-display choices take precedence");

		var unknownChoices = new java.util.HashMap<String, String>();
		unknownChoices.put("Dungeon Odin Helpers.Split Timing Mode", "UNKNOWN");
		require(!ModConfigMigrations.migrateLegacyDungeonSplitTiming(new java.util.HashMap<>(), unknownChoices)
				&& unknownChoices.size() == 1,
			"an unrecognized legacy timing mode is not guessed or written");
	}

	private static void checkIdentityAndJavaAbi() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		require(module != null && module == DungeonRunSplitsModule.INSTANCE,
			"Run Splits keeps its singleton module identity");
		require(module instanceof Module && module instanceof HudElement
				&& module.id().equals("dungeon_run_splits")
				&& module.name().equals("Run Splits")
				&& module.displayName().equals("Run Splits")
				&& module.description().equals("Shows the dungeon clear, floor boss phases, and total run time.")
				&& module.category() == Category.F7 && !module.defaultEnabled(),
			"the persisted module identity, HUD identity, category, and fresh-install state stay stable");
		try {
			int singletonModifiers = DungeonRunSplitsModule.class.getField("INSTANCE").getModifiers();
			require(Modifier.isPublic(singletonModifiers) && Modifier.isStatic(singletonModifiers)
					&& Modifier.isFinal(singletonModifiers),
				"the Kotlin singleton retains a Java-visible static INSTANCE field");
			for (String setting : List.of(
				"showSplits", "showCurrentSplit", "secondTimeDisplay", "showTickTime", "showRealTime",
				"showPbPace", "showCountValue", "countSource", "localRunSummary")) {
				var field = DungeonRunSplitsModule.class.getField(setting);
				require(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
					"the Kotlin settings retain Java static field access: " + setting);
			}
			DungeonRunSplitsModule.class.getMethod("publishSplitRows", DungeonFloor.class, List.class);
			DungeonRunSplitsModule.class.getMethod(
				"publishSplitRows", DungeonFloor.class, List.class, long.class, boolean.class, String.class);
			DungeonRunSplitsModule.class.getMethod(
				"publishSplitRows", DungeonFloor.class, List.class, long.class, boolean.class, String.class, long.class);
			DungeonRunSplitsModule.class.getMethod(
				"recordEntryStart", long.class, DungeonFloor.class, String.class, String.class);
			DungeonRunSplitsModule.class.getMethod("publishSupplementalRows", List.class);
			DungeonRunSplitsModule.class.getMethod("clearSnapshots");
			DungeonRunSplitsModule.class.getMethod("formatTimeLost", DungeonSplitTracker.Split.class);
			DungeonRunSplitsModule.class.getMethod("timeLostNanos", long.class, long.class);
			DungeonRunSplitsModule.class.getMethod("serverTickEstimateNanos", long.class);

			require(DungeonRunSplitsModule.DisplayRow.class.isRecord()
					&& Arrays.stream(DungeonRunSplitsModule.DisplayRow.class.getRecordComponents())
						.map(component -> component.getName()).toList()
						.equals(List.of("label", "primaryValue", "secondaryValue", "labelColor", "primaryColor",
							"secondaryColor")),
				"the Kotlin HUD row retains the original Java record component ABI");
			var row = new DungeonRunSplitsModule.DisplayRow("Blood", "1.00s", "[0.90s]", 1, 2, 3);
			require(row.label().equals("Blood") && row.primaryValue().equals("1.00s")
					&& row.secondaryValue().equals("[0.90s]") && row.labelColor() == 1
					&& row.primaryColor() == 2 && row.secondaryColor() == 3,
				"Java consumers retain direct DisplayRow record accessors");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Run Splits Java-facing API changed during the Kotlin port", exception);
		}
	}

	private static void checkSplitHelpersJavaAbi() {
		try {
			var version = DungeonSplitPersonalBestBook.class.getField("VERSION");
			var maxEntries = DungeonSplitPersonalBestBook.class.getField("MAX_ENTRIES");
			var maxJsonBytes = DungeonSplitPersonalBestBook.class.getField("MAX_JSON_BYTES");
			require(Modifier.isPublic(version.getModifiers()) && Modifier.isStatic(version.getModifiers())
					&& Modifier.isFinal(version.getModifiers()) && version.getInt(null) == 1
					&& Modifier.isPublic(maxEntries.getModifiers()) && Modifier.isStatic(maxEntries.getModifiers())
					&& Modifier.isFinal(maxEntries.getModifiers()) && maxEntries.getInt(null) == 256
					&& Modifier.isPublic(maxJsonBytes.getModifiers()) && Modifier.isStatic(maxJsonBytes.getModifiers())
					&& Modifier.isFinal(maxJsonBytes.getModifiers()) && maxJsonBytes.getInt(null) == 64 * 1024,
				"PB version and storage limits keep their Java-visible constant fields");
			requireStatic(DungeonSplitPersonalBestBook.class.getMethod("empty"), "PB empty factory stays static");
			requireStatic(DungeonSplitPersonalBestBook.class.getMethod("decode", String.class),
				"PB decoder stays static");
			DungeonSplitPersonalBestBook.class.getMethod("find", DungeonFloor.class, String.class);
			DungeonSplitPersonalBestBook.class.getMethod("entries");
			DungeonSplitPersonalBestBook.class.getMethod(
				"record", DungeonFloor.class, DungeonSplitTracker.Split.class, long.class);
			DungeonSplitPersonalBestBook.class.getMethod("encode");

			require(DungeonSplitPersonalBestBook.Entry.class.isRecord()
					&& Arrays.stream(DungeonSplitPersonalBestBook.Entry.class.getRecordComponents())
						.map(component -> component.getName()).toList().equals(List.of(
							"floor", "splitId", "attempts", "bestRealTimeNanos", "bestClientTicks",
							"bestServerPingPackets", "lastRealTimeNanos", "lastClientTicks",
							"lastServerPingPackets", "updatedAtMillis"))
					&& DungeonSplitPersonalBestBook.Update.class.isRecord()
					&& Arrays.stream(DungeonSplitPersonalBestBook.Update.class.getRecordComponents())
						.map(component -> component.getName()).toList().equals(List.of(
							"book", "entry", "firstForSplit", "realTimePersonalBest", "clientTicksPersonalBest",
							"serverPingPacketsPersonalBest"))
					&& DungeonSplitPersonalBestBook.DecodeResult.class.isRecord()
					&& Arrays.stream(DungeonSplitPersonalBestBook.DecodeResult.class.getRecordComponents())
						.map(component -> component.getName()).toList().equals(List.of("valid", "book", "error")),
				"PB entries, updates, and decode results retain their Java record components");
			DungeonSplitPersonalBestBook.Update.class.getMethod("isPersonalBest", DungeonSplitTimeMode.class);

			Method initialize = DungeonSplitPersonalBestStore.class.getMethod("initialize");
			Method record = DungeonSplitPersonalBestStore.class.getMethod(
				"record", DungeonFloor.class, DungeonSplitTracker.Split.class, long.class);
			Method find = DungeonSplitPersonalBestStore.class.getMethod("find", DungeonFloor.class, String.class);
			requireStatic(initialize, "PB store initialization stays static");
			requireStatic(record, "PB store recording stays static");
			requireStatic(find, "PB store lookup stays static");

			Method pbPace = DungeonSplitTimeMode.class.getMethod("formatPbPace", long.class, long.class);
			requireStatic(pbPace, "PB pace formatting stays a Java static method");
			DungeonSplitTimeMode.class.getMethod("value", DungeonSplitTracker.Split.class);
			DungeonSplitTimeMode.class.getMethod("value", DungeonSplitPersonalBestBook.Entry.class, boolean.class);
			DungeonSplitTimeMode.class.getMethod("improves", long.class, long.class);
			DungeonSplitTimeMode.class.getMethod("format", long.class);

			var pendingLimit = DungeonSplitEntryBuffer.class.getField("MAX_PENDING_NANOS");
			require(Modifier.isPublic(pendingLimit.getModifiers()) && Modifier.isStatic(pendingLimit.getModifiers())
					&& Modifier.isFinal(pendingLimit.getModifiers())
					&& pendingLimit.getLong(null) == 15_000_000_000L,
				"the buffered entry timeout keeps its Java-visible constant value");
			DungeonSplitEntryBuffer.class.getMethod(
				"capture", DungeonFloor.class, Object.class, Object.class, long.class, long.class, long.class);
			DungeonSplitEntryBuffer.class.getMethod(
				"capture", DungeonFloor.class, Object.class, Object.class, long.class, long.class, long.class, long.class);
			DungeonSplitEntryBuffer.class.getMethod(
				"discardIfStale", boolean.class, DungeonFloor.class, Object.class, Object.class, long.class);
			DungeonSplitEntryBuffer.class.getMethod(
				"consumeIfReady", boolean.class, DungeonFloor.class, Object.class, Object.class, long.class);
			DungeonSplitEntryBuffer.class.getMethod("clear");
			DungeonSplitEntryBuffer.class.getMethod("isPending");
			require(DungeonSplitEntryBuffer.Pending.class.isRecord()
					&& Arrays.stream(DungeonSplitEntryBuffer.Pending.class.getRecordComponents())
						.map(component -> component.getName()).toList().equals(List.of(
							"floor", "level", "connection", "capturedAtNanos", "clientTick",
							"serverPingPackets", "serverTickCount")),
				"the buffered run cue keeps its Java record component ABI");
			DungeonSplitEntryBuffer.Pending.class.getConstructor(
				DungeonFloor.class, Object.class, Object.class, long.class, long.class, long.class);
			DungeonSplitEntryBuffer.Pending.class.getConstructor(
				DungeonFloor.class, Object.class, Object.class, long.class, long.class, long.class, long.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Run Splits helper Java ABI changed during the Kotlin port", exception);
		}
	}

	private static void requireStatic(Method method, String message) {
		require(Modifier.isStatic(method.getModifiers()), message);
	}

	private static void checkSettingsAndGroups() {
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldShowCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		boolean oldShowTickTime = DungeonRunSplitsModule.showTickTime.value();
		boolean oldShowRealTime = DungeonRunSplitsModule.showRealTime.value();
		boolean oldShowPbPace = DungeonRunSplitsModule.showPbPace.value();
		boolean oldShowCountValue = DungeonRunSplitsModule.showCountValue.value();
		String oldCountSource = DungeonRunSplitsModule.countSource.value();
		boolean oldLocalRunSummary = DungeonRunSplitsModule.localRunSummary.value();
		try {
			DungeonRunSplitsModule.showSplits.reset();
			DungeonRunSplitsModule.showCurrentSplit.reset();
			DungeonRunSplitsModule.secondTimeDisplay.reset();
			DungeonRunSplitsModule.showTickTime.reset();
			DungeonRunSplitsModule.showRealTime.reset();
			DungeonRunSplitsModule.showPbPace.reset();
			DungeonRunSplitsModule.showCountValue.reset();
			DungeonRunSplitsModule.countSource.reset();
			DungeonRunSplitsModule.localRunSummary.reset();
			require(DungeonRunSplitsModule.showSplits.value()
					&& DungeonRunSplitsModule.showCurrentSplit.value()
					&& DungeonRunSplitsModule.secondTimeDisplay.value().equals("LOCAL_PB")
					&& DungeonRunSplitsModule.showTickTime.value()
					&& DungeonRunSplitsModule.showRealTime.value()
					&& !DungeonRunSplitsModule.showPbPace.value()
					&& DungeonRunSplitsModule.showCountValue.value()
					&& DungeonRunSplitsModule.countSource.value().equals("CLIENT_TICKS")
					&& DungeonRunSplitsModule.localRunSummary.value(),
				"all saved setting defaults remain unchanged");
			require(DungeonRunSplitsModule.showSplits.name().equals("Show Splits")
					&& DungeonRunSplitsModule.showCurrentSplit.name().equals("Show Current Split")
					&& DungeonRunSplitsModule.secondTimeDisplay.name().equals("Second Time Display")
					&& DungeonRunSplitsModule.showTickTime.name().equals("Show Tick Time")
					&& DungeonRunSplitsModule.showRealTime.name().equals("Show Real Time")
					&& DungeonRunSplitsModule.showPbPace.name().equals("Show PB Pace")
					&& DungeonRunSplitsModule.showCountValue.name().equals("Show Count Value")
					&& DungeonRunSplitsModule.countSource.name().equals("Count Source")
					&& DungeonRunSplitsModule.localRunSummary.name().equals("Local Run Summary"),
				"all persisted setting IDs remain stable");
			require(DungeonRunSplitsModule.secondTimeDisplay.choices().equals(
					List.of("NONE", "CLIENT_TICK_ESTIMATE", "LOCAL_PB", "TIME_LOST"))
					&& DungeonRunSplitsModule.countSource.choices().equals(
						List.of("CLIENT_TICKS", "SERVER_PING_PACKETS")),
				"choice values and order remain stable for existing configs");
			require(DungeonRunSplitsModule.secondTimeDisplay.displayValue().equals("Local PB"),
				"the default choice keeps its existing player-facing label");

			DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
			List<SettingGroup> groups = module.groups();
			require(groups.stream().map(SettingGroup::name).toList()
					.equals(List.of("Split Display", "Completion Summary")),
				"the visible settings group names and order remain stable");
			require(groups.get(0).toggle() == DungeonRunSplitsModule.showSplits
					&& groups.get(0).settings().equals(List.of(
						DungeonRunSplitsModule.showCurrentSplit,
						DungeonRunSplitsModule.secondTimeDisplay))
					&& groups.get(1).toggle() == DungeonRunSplitsModule.localRunSummary
					&& groups.get(1).settings().isEmpty(),
				"split, boss timer, and completion summary sections retain their toggles and settings");
			require(groups.stream().flatMap(group -> group.settings().stream()).noneMatch(setting ->
					setting == DungeonRunSplitsModule.showTickTime
						|| setting == DungeonRunSplitsModule.showRealTime
						|| setting == DungeonRunSplitsModule.showPbPace
						|| setting == DungeonRunSplitsModule.showCountValue
						|| setting == DungeonRunSplitsModule.countSource),
				"legacy completion-summary settings stay persisted but hidden from the Run Splits groups");
			require(module.booleanSettings().stream().map(Setting::name).toList().equals(List.of(
					"Show Splits", "Show Current Split", "Show Tick Time", "Show Real Time", "Show PB Pace",
					"Show Count Value", "Local Run Summary"))
					&& module.choiceSettings().stream().map(Setting::name).toList().equals(
						List.of("Second Time Display", "Count Source")),
				"the flat persisted setting lists keep their original declaration order");
			require(groups.stream().flatMap(group -> group.settings().stream()).noneMatch(Setting::isDebugOnly),
				"the standard Run Splits setting groups remain available without Dev Debug");
		} finally {
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldShowCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
			DungeonRunSplitsModule.showTickTime.setValue(oldShowTickTime);
			DungeonRunSplitsModule.showRealTime.setValue(oldShowRealTime);
			DungeonRunSplitsModule.showPbPace.setValue(oldShowPbPace);
			DungeonRunSplitsModule.showCountValue.setValue(oldShowCountValue);
			DungeonRunSplitsModule.countSource.setValue(oldCountSource);
			DungeonRunSplitsModule.localRunSummary.setValue(oldLocalRunSummary);
		}
	}

	private static void checkHudLifecycleAndSnapshotCopies() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean wasEnabled = module.isEnabled();
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.secondTimeDisplay.setValue("NONE");
			module.setEnabled(false);
			module.publishSplitRows(DungeonFloor.F7, List.of(
				new DungeonSplitTracker.Split("BLOOD", "Blood", 1_000_000_000L, 20L, 0L, 20L, true),
				new DungeonSplitTracker.Split("RUN", "Run", 2_000_000_000L, 40L, 0L, 40L, true)));
			module.publishSupplementalRows(Arrays.asList("Boss timer", " ", null));
			module.setEnabled(true);
			require(module.buildRows(false).isEmpty(),
				"enabling the HUD clears any stale adapter snapshot");

			module.publishSplitRows(DungeonFloor.F7, List.of(
				new DungeonSplitTracker.Split("BLOOD", "Blood", 1_000_000_000L, 20L, 0L, 20L, true),
				new DungeonSplitTracker.Split("RUN", "Run", 2_000_000_000L, 40L, 0L, 40L, true)));
			module.publishSupplementalRows(Arrays.asList("Boss timer", " ", null));
			var rows = module.buildRows(false);
			require(rows.size() == 3 && rows.get(0).label().equals("Blood")
					&& rows.get(1).label().equals("Run") && rows.get(2).label().equals("Boss timer"),
				"the HUD keeps ordered split rows and appends only nonblank supplemental timers");
			try {
				rows.clear();
				throw new AssertionError("the exposed HUD snapshot must remain immutable");
			} catch (UnsupportedOperationException expected) {
				// Immutable snapshots prevent callers from mutating the render state.
			}

			module.setEnabled(false);
			require(module.buildRows(false).isEmpty(),
				"disabling the module clears split and supplemental HUD snapshots");
		} finally {
			module.clearSnapshots();
			module.setEnabled(wasEnabled);
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
		}
	}

	private static void checkLiveSplitOrderingAndVisibility() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldShowCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.showCurrentSplit.setValue(true);
			DungeonRunSplitsModule.secondTimeDisplay.setValue("NONE");
			module.publishSplitRows(DungeonFloor.F7, List.of(
				new DungeonSplitTracker.Split("NECRON", "Necron", 4_000_000_000L, 80L, 0L, 80L, true),
				new DungeonSplitTracker.Split("MAXOR", "Maxor", 2_000_000_000L, 40L, 0L, 40L, false),
				new DungeonSplitTracker.Split("RUN", "Run", 8_000_000_000L, 160L, 0L, 160L, false),
				new DungeonSplitTracker.Split("BLOOD", "Blood", 1_000_000_000L, 20L, 0L, 20L, true)));
			var visibleRows = module.buildRows(false);
			require(visibleRows.stream().map(DungeonRunSplitsModule.DisplayRow::label).toList()
					.equals(List.of("Blood", "Maxor", "Necron", "Run")),
				"live split rows follow the floor milestone order instead of tracker publication order");

			DungeonRunSplitsModule.showCurrentSplit.setValue(false);
			var completedOnly = module.buildRows(false);
			require(completedOnly.stream().map(DungeonRunSplitsModule.DisplayRow::label).toList()
					.equals(List.of("Blood", "Necron", "Run")),
				"hiding the current split keeps completed milestones and the run-total row");

			module.publishSupplementalRows(List.of("Professor Freeze · 10s"));
			DungeonRunSplitsModule.showSplits.setValue(false);
			var timersOnly = module.buildRows(false);
			require(timersOnly.size() == 1 && timersOnly.getFirst().label().equals("Professor Freeze · 10s"),
				"turning off split rows preserves enabled supplemental boss timers");
		} finally {
			module.clearSnapshots();
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldShowCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
		}
	}

	private static void checkProfessorSplitAggregation() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldShowCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.showCurrentSplit.setValue(true);
			DungeonRunSplitsModule.secondTimeDisplay.setValue("NONE");
			module.publishSplitRows(DungeonFloor.F3, List.of(
				new DungeonSplitTracker.Split("PROFESSOR_TRANSFORMED", "Transformed Professor",
					2_000_000_000L, 40L, 0L, 40L, false),
				new DungeonSplitTracker.Split("RUN", "Run", 8_000_000_000L, 160L, 0L, 160L, false),
				new DungeonSplitTracker.Split("PROFESSOR", "The Professor",
					1_000_000_000L, 20L, 0L, 20L, true)));
			var rows = module.buildRows(false);
			require(rows.stream().map(DungeonRunSplitsModule.DisplayRow::label).toList()
					.equals(List.of("Professor", "Run"))
				&& rows.getFirst().primaryValue().equals("3.00s"),
				"F3 displays the original and transformed Professor phases as one accumulated split");

			DungeonRunSplitsModule.showCurrentSplit.setValue(false);
			require(module.buildRows(false).stream().map(DungeonRunSplitsModule.DisplayRow::label).toList()
					.equals(List.of("Run")),
				"an aggregate Professor split remains active until both observed phases complete");
		} finally {
			module.clearSnapshots();
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldShowCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
		}
	}

	private static void checkLocalPersonalBestColumnOutput() {
		DungeonRunSplitsModule module = DungeonRunSplitsModule.INSTANCE;
		boolean oldShowSplits = DungeonRunSplitsModule.showSplits.value();
		boolean oldShowCurrent = DungeonRunSplitsModule.showCurrentSplit.value();
		String oldSecondDisplay = DungeonRunSplitsModule.secondTimeDisplay.value();
		try {
			DungeonRunSplitsModule.showSplits.setValue(true);
			DungeonRunSplitsModule.showCurrentSplit.setValue(true);
			DungeonRunSplitsModule.secondTimeDisplay.setValue("LOCAL_PB");
			module.publishSplitRows(DungeonFloor.F7, List.of(new DungeonSplitTracker.Split(
				"BLOOD", "Blood", 2_550_000_000L, 51L, 8L, 30L, true)));

			var row = module.buildRows(false).getFirst();
			var best = DungeonSplitPersonalBestStore.find(DungeonFloor.F7, "BLOOD");
			String expectedBest = best == null ? "[--]" : "[" + formatModuleTime(best.bestRealTimeNanos()) + "]";
			require(row.label().equals("Blood") && row.primaryValue().equals("2.55s")
				&& row.secondaryValue().equals(expectedBest)
				&& row.secondaryColor() == 0xFFAAAAAA,
				"the live Local PB column renders the matching floor/split best or its unavailable marker");
		} finally {
			module.clearSnapshots();
			DungeonRunSplitsModule.showSplits.setValue(oldShowSplits);
			DungeonRunSplitsModule.showCurrentSplit.setValue(oldShowCurrent);
			DungeonRunSplitsModule.secondTimeDisplay.setValue(oldSecondDisplay);
		}
	}

	private static String formatModuleTime(long nanos) {
		long centiseconds = Math.max(0L, nanos) / 10_000_000L;
		long seconds = centiseconds / 100L;
		long fraction = centiseconds % 100L;
		return seconds < 60L
			? String.format(java.util.Locale.ROOT, "%d.%02ds", seconds, fraction)
			: String.format(java.util.Locale.ROOT, "%dm %02d.%02ds", seconds / 60L, seconds % 60L, fraction);
	}

	public static void main(String[] args) {
		run();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
