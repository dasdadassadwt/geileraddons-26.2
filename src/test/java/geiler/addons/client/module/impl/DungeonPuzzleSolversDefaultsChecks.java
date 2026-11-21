package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

/** Focused offline checks for pinned Odin 0.3.6 solver setting defaults. */
public final class DungeonPuzzleSolversDefaultsChecks {
	private DungeonPuzzleSolversDefaultsChecks() { }

	public static void run() {
		DungeonPuzzleSolversModule module = DungeonPuzzleSolversModule.INSTANCE;
		checkJavaInteropAndGates(module);
		checkPuzzleRecordAbi();
		checkWaterBoardValueAbiAndSemantics();
		check(!module.defaultEnabled(), "Puzzle Solvers is off by default");
		checkBooleanDefault(module, "Water Board Solver", true);
		checkBooleanDefault(module, "Optimized Solutions", false);
		checkBooleanDefault(module, "Water Tracer", true);
		checkBooleanDefault(module, "TP Maze Solver", true);
		checkBooleanDefault(module, "Maze Tracer", true);
		checkBooleanDefault(module, "Ice Path Solver", true);
		checkBooleanDefault(module, "Ice Fill Solver", true);
		checkBooleanDefault(module, "Use Optimized Patterns", false);
		checkBooleanDefault(module, "Blaze Solver", true);
		checkBooleanDefault(module, "Blaze Solver Next Line", true);
		checkBooleanDefault(module, "Send Complete", false);
		checkBooleanDefault(module, "Creeper Beams Solver", true);
		checkBooleanDefault(module, "Beams Tracer", false);
		checkBooleanDefault(module, "Beam Hit Sound", true);
		checkBooleanDefault(module, "Three Weirdos", true);
		checkBooleanDefault(module, "Quiz Solver", true);
		checkBooleanDefault(module, "Quiz Depth", false);
		checkBooleanDefault(module, "Boulder Solver", true);
		checkBooleanDefault(module, "Show All Boulder Clicks", false);
		checkBooleanDefault(module, "Tic Tac Toe Solver", true);
		checkBooleanDefault(module, "Predict Machine Moves", false);
		checkBooleanDefault(module, "Puzzle Timers", true);
		checkBooleanDefault(module, "Draft Prompt", true);

		checkNumberDefault(DungeonPuzzleSolversModule.blazeLineCount, 1.0f, 10.0f, 1.0f, true,
			"Blaze line count");
		checkNumberDefault(DungeonPuzzleSolversModule.blazeLineWidth, 0.5f, 5.0f, 2.0f, false,
			"Blaze line width");
		checkNumberDefault(DungeonPuzzleSolversModule.creeperBeamAlpha, 0.0f, 1.0f, 0.70f, false,
			"Creeper beam alpha");

		checkColor(DungeonPuzzleSolversModule.tpMazeSingleCandidateColor, 85, 255, 85, 128,
			"TP Maze single-candidate color matches Odin's half-alpha Minecraft green");
		checkColor(DungeonPuzzleSolversModule.tpMazeMultipleCandidateColor, 255, 170, 0, 128,
			"TP Maze multiple-candidate color matches Odin's half-alpha Minecraft gold");
		checkColor(DungeonPuzzleSolversModule.tpMazeVisitedColor, 255, 85, 85, 128,
			"TP Maze visited color matches Odin's half-alpha Minecraft red");
		checkColor(DungeonPuzzleSolversModule.tpMazeTracerColor, 85, 255, 255, 255,
			"TP Maze tracer color matches Odin's opaque Minecraft aqua");
		checkColor(DungeonPuzzleSolversModule.waterBoardTracerFirstColor, 85, 255, 85, 255,
			"Water Board first tracer color defaults to opaque Minecraft green");
		checkColor(DungeonPuzzleSolversModule.waterBoardTracerSecondColor, 255, 170, 0, 255,
			"Water Board second tracer color defaults to opaque Minecraft gold");
		checkColor(DungeonPuzzleSolversModule.icePathColor, 255, 85, 255, 255,
			"Ice Path color default is retained");
		checkColor(DungeonPuzzleSolversModule.iceFillColor, 255, 85, 255, 255,
			"Ice Fill color default is retained");
		checkColor(DungeonPuzzleSolversModule.blazeFirstColor, 85, 255, 85, 191,
			"Blaze first color default is retained");
		checkColor(DungeonPuzzleSolversModule.blazeSecondColor, 255, 170, 0, 191,
			"Blaze second color default is retained");
		checkColor(DungeonPuzzleSolversModule.blazeThirdColor, 255, 85, 85, 191,
			"Blaze third color default is retained");
		checkColor(DungeonPuzzleSolversModule.blazeOtherColor, 255, 255, 255, 76,
			"Blaze other-target color default is retained");
		checkColor(DungeonPuzzleSolversModule.weirdosCorrectColor, 85, 255, 85, 178,
			"Three Weirdos correct color default is retained");
		checkColor(DungeonPuzzleSolversModule.weirdosWrongColor, 255, 85, 85, 178,
			"Three Weirdos wrong color default is retained");
		checkColor(DungeonPuzzleSolversModule.quizColor, 85, 255, 85, 191,
			"Quiz color default is retained");
		checkChoiceDefault(DungeonPuzzleSolversModule.blazeStyle, "FILLED_OUTLINE", "Filled Outline",
			"Blaze style");
		checkChoiceDefault(DungeonPuzzleSolversModule.creeperBeamStyle, "FILLED_OUTLINE", "Filled Outline",
			"Creeper Beams style");
		checkChoiceDefault(DungeonPuzzleSolversModule.weirdosStyle, "FILLED_OUTLINE", "Filled Outline",
			"Three Weirdos style");
		checkChoiceDefault(DungeonPuzzleSolversModule.boulderStyle, "OUTLINE", "Outline", "Boulder style");

		check(!hasSetting(module, "Quiz Timer Hud"),
			"Quiz Timer Hud has no manual visibility toggle and appears while Quiz is active");
		checkColor(DungeonPuzzleSolversModule.boulderColor, 85, 255, 85, 255,
			"Boulder color matches Odin's opaque Minecraft green");
		check(module.textSettings().isEmpty(), "Puzzle Solvers has no text settings or text defaults");
		checkActions(module);
		for (String removed : new String[]{"Creeper Fuse Sound", "Beam Pair Sound", "Creeper Sound ID",
			"Mute Creeper Hurt", "Mute Explosions", "Show Pad Boxes", "Show Observed Links", "Infer Final Pad"}) {
			check(!hasSetting(module, removed),
				"removed puzzle setting is absent: " + removed);
		}
	}

	private static void checkJavaInteropAndGates(DungeonPuzzleSolversModule module) {
		check(module == DungeonPuzzleSolversModule.INSTANCE, "the Kotlin module retains the Java INSTANCE field");
		check("Puzzle Solvers".equals(module.name()), "the registered module name remains stable");
		check("Puzzle Solvers".equals(module.configName()), "the persisted module key remains stable");
		check(module.category() == Category.F7, "the registered module category remains stable");
		check(List.of("Water Board", "TP Maze", "Ice Path", "Ice Fill", "Blaze", "Creeper Beams",
			"Three Weirdos", "Quiz", "Boulder", "Tic Tac Toe", "General").equals(
			module.groups().stream().map(SettingGroup::name).toList()),
			"the settings group order remains stable");
		check(List.of("Water Board Solver", "Optimized Solutions", "Water Tracer", "TP Maze Solver",
			"Maze Tracer", "Ice Path Solver", "Ice Fill Solver", "Use Optimized Patterns", "Blaze Solver",
			"Blaze Solver Next Line", "Send Complete", "Creeper Beams Solver", "Beams Tracer", "Beam Hit Sound",
			"Three Weirdos", "Quiz Solver", "Quiz Depth", "Boulder Solver", "Show All Boulder Clicks",
			"Tic Tac Toe Solver", "Predict Machine Moves", "Puzzle Timers", "Draft Prompt").equals(
			module.booleanSettings().stream().map(Setting::name).toList()),
			"Java callers retain boolean setting names and declaration order");
		checkSettingNames(module.numberSettings(), List.of("Blaze Solver Lines", "Blaze Solver Lines Width",
			"Beams Color Alpha"), "number");
		checkSettingNames(module.colorSettings(), List.of("Tracer Color First", "Tracer Color Second",
			"Color for one", "Color for multiple", "Color for visited", "Tracer Color", "Ice Path Color",
			"Ice Fill Color", "First Color", "Second Color", "Third Color", "Other Color",
			"Weirdos Correct Color", "Weirdos Wrong Color", "Quiz Color", "Boulder Color"), "color");
		checkSettingNames(module.choiceSettings(), List.of("Blaze Style", "Beams Style", "Weirdos Style",
			"Boulder Style"), "choice");
		checkSettingNames(module.textSettings(), List.of(), "text");
		checkSettingNames(module.actions(), List.of("Reset Water Board", "Reset TP Maze", "Reset Ice Fill",
			"Reset Blaze", "Reset Creeper Beams", "Reset Three Weirdos", "Reset Quiz"), "action");
		check(totalSettingCount(module) == 53,
			"all declared settings, including actions, are represented in their typed Java lists");

		checkGroupSettingNames(module, 0, DungeonPuzzleSolversModule.waterBoard, List.of("Optimized Solutions",
			"Water Tracer", "Tracer Color First", "Tracer Color Second", "Reset Water Board"));
		checkGroupSettingNames(module, 1, DungeonPuzzleSolversModule.tpMaze, List.of("Color for one",
			"Color for multiple", "Color for visited", "Maze Tracer", "Tracer Color", "Reset TP Maze"));
		checkGroupSettingNames(module, 2, DungeonPuzzleSolversModule.icePath, List.of("Ice Path Color"));
		checkGroupSettingNames(module, 3, DungeonPuzzleSolversModule.iceFill, List.of("Ice Fill Color",
			"Use Optimized Patterns", "Reset Ice Fill"));
		checkGroupSettingNames(module, 4, DungeonPuzzleSolversModule.blaze, List.of("Blaze Solver Next Line",
			"Blaze Solver Lines", "Blaze Solver Lines Width", "Blaze Style", "First Color", "Second Color",
			"Third Color", "Other Color", "Send Complete", "Reset Blaze"));
		checkGroupSettingNames(module, 5, DungeonPuzzleSolversModule.creeperBeams, List.of("Beams Style",
			"Beams Tracer", "Beams Color Alpha", "Beam Hit Sound", "Reset Creeper Beams"));
		checkGroupSettingNames(module, 6, DungeonPuzzleSolversModule.threeWeirdos, List.of("Weirdos Style",
			"Weirdos Correct Color", "Weirdos Wrong Color", "Reset Three Weirdos"));
		checkGroupSettingNames(module, 7, DungeonPuzzleSolversModule.quiz,
			List.of("Quiz Color", "Quiz Depth", "Reset Quiz"));
		checkGroupSettingNames(module, 8, DungeonPuzzleSolversModule.boulder,
			List.of("Show All Boulder Clicks", "Boulder Style", "Boulder Color"));
		checkGroupSettingNames(module, 9, DungeonPuzzleSolversModule.ticTacToe,
			List.of("Predict Machine Moves"));
		checkGroupSettingNames(module, 10, null, List.of("Puzzle Timers", "Draft Prompt"));

		Set<String> expectedIds = Set.of("WATER_BOARD", "TP_MAZE", "ICE_PATH", "ICE_FILL", "BLAZE",
			"CREEPER_BEAMS", "THREE_WEIRDOS", "QUIZ", "BOULDER", "TIC_TAC_TOE");
		boolean wasEnabled = module.isEnabled();
		try {
			module.setEnabled(false);
			check(module.anySolverEnabled(), "child defaults are independent of the parent module toggle");
			check(!module.hasEnabledSolvers(), "the aggregate gate remains off with its parent disabled");
			check(!module.solverEnabled("WATER_BOARD"), "a solver is gated while the parent module is disabled");
			check(!module.solverEnabled(null), "null solver IDs remain disabled");
			check(module.enabledSolverIds().isEmpty(), "the enabled ID set is empty while the parent is disabled");

			module.setEnabled(true);
			check(module.hasEnabledSolvers(), "the aggregate gate opens for enabled child solvers");
			check(expectedIds.equals(module.enabledSolverIds()), "all enabled solver IDs remain available to Java callers");
			for (String id : expectedIds) check(module.solverEnabled(id), "solver ID remains enabled: " + id);
			check(!module.solverEnabled(null) && !module.solverEnabled("UNKNOWN"),
				"null and unknown solver IDs remain disabled");
		} finally {
			module.setEnabled(wasEnabled);
		}
		checkIndependentSolverGates(module);
	}

	private static void checkIndependentSolverGates(DungeonPuzzleSolversModule module) {
		BooleanSetting[] settings = {
			DungeonPuzzleSolversModule.waterBoard,
			DungeonPuzzleSolversModule.tpMaze,
			DungeonPuzzleSolversModule.icePath,
			DungeonPuzzleSolversModule.iceFill,
			DungeonPuzzleSolversModule.blaze,
			DungeonPuzzleSolversModule.creeperBeams,
			DungeonPuzzleSolversModule.threeWeirdos,
			DungeonPuzzleSolversModule.quiz,
			DungeonPuzzleSolversModule.boulder,
			DungeonPuzzleSolversModule.ticTacToe,
		};
		String[] ids = {
			"WATER_BOARD", "TP_MAZE", "ICE_PATH", "ICE_FILL", "BLAZE", "CREEPER_BEAMS",
			"THREE_WEIRDOS", "QUIZ", "BOULDER", "TIC_TAC_TOE",
		};
		boolean[] previousValues = new boolean[settings.length];
		for (int index = 0; index < settings.length; index++) previousValues[index] = settings[index].rawValue();
		boolean wasEnabled = module.isEnabled();
		try {
			for (BooleanSetting setting : settings) setting.setValue(false);
			module.setEnabled(true);
			check(!module.anySolverEnabled() && !module.hasEnabledSolvers() && module.enabledSolverIds().isEmpty(),
				"the parent module stays idle when all independently selectable solvers are disabled");

			for (int enabledIndex = 0; enabledIndex < settings.length; enabledIndex++) {
				settings[enabledIndex].setValue(true);
				check(module.anySolverEnabled() && module.hasEnabledSolvers(),
					"one enabled child opens the aggregate gate: " + ids[enabledIndex]);
				check(Set.of(ids[enabledIndex]).equals(module.enabledSolverIds()),
					"each child publishes only its matching enabled solver ID: " + ids[enabledIndex]);
				for (int candidateIndex = 0; candidateIndex < ids.length; candidateIndex++) {
					check(module.solverEnabled(ids[candidateIndex]) == (candidateIndex == enabledIndex),
						"each child gate maps to its own solver ID: " + ids[candidateIndex]);
				}
				settings[enabledIndex].setValue(false);
				check(!module.anySolverEnabled() && !module.hasEnabledSolvers()
					&& module.enabledSolverIds().isEmpty(),
					"disabling the only child closes the aggregate gate: " + ids[enabledIndex]);
			}

			settings[0].setValue(true);
			module.setEnabled(false);
			check(module.anySolverEnabled() && !module.hasEnabledSolvers()
				&& !module.solverEnabled(ids[0]) && module.enabledSolverIds().isEmpty(),
				"the parent module still gates child solver IDs independently of child values");
		} finally {
			for (int index = 0; index < settings.length; index++) settings[index].setValue(previousValues[index]);
			module.setEnabled(wasEnabled);
		}
	}

	private static void checkBooleanDefault(DungeonPuzzleSolversModule module, String name, boolean expected) {
		BooleanSetting setting = module.booleanSettings().stream().filter(value -> name.equals(value.name()))
			.findFirst().orElseThrow(() -> new AssertionError("Missing boolean setting: " + name));
		boolean previous = setting.rawValue();
		try {
			setting.reset();
			check(setting.rawValue() == expected, name + " has the expected default");
		} finally {
			setting.setValue(previous);
		}
	}

	private static void checkNumberDefault(NumberSetting setting, float min, float max, float expected,
		boolean integer, String label) {
		float previous = setting.value();
		try {
			setting.reset();
			check(Float.compare(setting.min(), min) == 0 && Float.compare(setting.max(), max) == 0,
				label + " keeps its range");
			check(Float.compare(setting.value(), expected) == 0, label + " keeps its default");
			check(setting.isInteger() == integer, label + " keeps its integer mode");
		} finally {
			setting.setValue(previous);
		}
	}

	private static void checkChoiceDefault(ChoiceSetting setting, String expected, String display, String label) {
		String previous = setting.value();
		try {
			setting.reset();
			check("FILLED".equals(setting.choices().get(0))
				&& "FILLED_OUTLINE".equals(setting.choices().get(1))
				&& "OUTLINE".equals(setting.choices().get(2)), label + " keeps choice order");
			check(expected.equals(setting.value()), label + " keeps its default");
			check(display.equals(setting.displayValue()), label + " keeps its display label");
		} finally {
			setting.setValue(previous);
		}
	}

	private static void checkActions(DungeonPuzzleSolversModule module) {
		List<ModuleAction> actions = module.actions();
		check(List.of("Reset Water Board", "Reset TP Maze", "Reset Ice Fill", "Reset Blaze",
			"Reset Creeper Beams", "Reset Three Weirdos", "Reset Quiz").equals(
			actions.stream().map(ModuleAction::name).toList()), "reset actions keep their order and names");
		check(List.of("Reset tracked lever progress for this room.",
			"Clear visited pads and observed links for this room.",
			"Clear the detected route and progress for this room.",
			"Clear the detected Blaze targets for this room.",
			"Clear endpoint progress for this room.",
			"Clear the clue results for this room.",
			"Clear the active Oruo question and answer.").equals(
			actions.stream().map(ModuleAction::description).toList()), "reset action descriptions remain stable");
		check(actions.stream().allMatch(action -> action.onClick() != null),
			"every reset action retains a Java-callable callback");
		check(actions.stream().noneMatch(action -> "Reset Boulder".equals(action.name())),
			"Boulder still has no manual reset action");
	}

	private static void checkGroupSettingNames(DungeonPuzzleSolversModule module, int index,
		BooleanSetting expectedToggle, List<String> expectedRows) {
		var group = module.groups().get(index);
		check(group.toggle() == expectedToggle,
			"header toggle remains separate from the rows in group " + group.name());
		check(expectedRows.equals(group.settings().stream().map(Setting::name).toList()),
			"setting rows remain ordered in group " + group.name());
	}

	private static void checkSettingNames(List<? extends Setting> settings, List<String> expected, String type) {
		check(expected.equals(settings.stream().map(Setting::name).toList()),
			type + " setting names retain declaration order");
	}

	private static int totalSettingCount(DungeonPuzzleSolversModule module) {
		return module.booleanSettings().size() + module.numberSettings().size() + module.colorSettings().size()
			+ module.choiceSettings().size() + module.textSettings().size() + module.actions().size();
	}

	private static void checkColor(ColorSetting setting, int red, int green, int blue, int alpha, String label) {
		float hue = setting.hue();
		float saturation = setting.saturation();
		float brightness = setting.brightness();
		int previousAlpha = setting.alpha();
		try {
			setting.reset();
			check(setting.alphaEditable(), label + " remains editable");
			check(setting.red() == red && setting.green() == green && setting.blue() == blue
				&& setting.alpha() == alpha, label);
		} finally {
			setting.setHsv(hue, saturation, brightness);
			if (setting.alphaEditable()) setting.setChannel(ColorSetting.Channel.ALPHA, previousAlpha);
		}
	}

	private static boolean hasSetting(DungeonPuzzleSolversModule module, String name) {
		return module.booleanSettings().stream().anyMatch(setting -> name.equals(setting.name()))
			|| module.choiceSettings().stream().anyMatch(setting -> name.equals(setting.name()))
			|| module.colorSettings().stream().anyMatch(setting -> name.equals(setting.name()))
			|| module.numberSettings().stream().anyMatch(setting -> name.equals(setting.name()))
			|| module.textSettings().stream().anyMatch(setting -> name.equals(setting.name()));
	}

	private static void checkPuzzleRecordAbi() {
		Class<?> boulderState = nestedRecord("geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic$BoulderState");
		Class<?> previousPush = nestedRecord("geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic$PreviousPush");
		Class<?> blockTarget = nestedRecord("geiler.addons.client.dungeon.helpers.DungeonPuzzleTimingTracker$BlockTarget");
		Class<?> waterTracer = nestedRecord(
			"geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer$WaterTracerSegment");
		Class<?> visiblePair = nestedRecord(
			"geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer$VisibleCreeperPair");
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.BlazeTarget.class,
			"stableId", String.class, "health", int.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class,
			"x", int.class, "y", int.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Portal.class,
			"id", int.class, "group", int.class, "x", double.class, "z", double.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Pair.class,
			"firstEndpointId", int.class, "secondEndpointId", int.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.PushStep.class,
			"playerBeforePush", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class,
			"boulderBeforePush", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class,
			"boulderAfterPush", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class);
		checkRecord(boulderState, "player", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class,
			"boulder", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class);
		checkRecord(previousPush, "previous", boulderState,
			"boulderAfterPush", geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.Cell.class);

		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleTimingTracker.Completion.class,
			"puzzleName", String.class, "elapsedSeconds", float.class, "minimumSeconds", int.class,
			"previousBestSeconds", float.class, "bestSeconds", float.class, "personalBest", boolean.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleTimingTracker.DraftPrompt.class,
			"playerName", String.class, "text", String.class, "hoverText", String.class, "runCommand", String.class);
		checkRecord(blockTarget, "x", int.class, "y", int.class, "z", int.class);

		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings.class,
			"water", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings.class,
			"creeper", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings.class,
			"weirdos", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings.class,
			"quiz", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings.class,
			"showTracer", boolean.class, "firstColor", int.class, "secondColor", int.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings.class,
			"style", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.class,
			"showTracer", boolean.class, "alpha", float.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings.class,
			"style", geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.class,
			"correctColor", int.class, "wrongColor", int.class);
		checkRecord(geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings.class,
			"color", int.class, "depthTested", boolean.class);
		checkRecord(waterTracer, "from", Vec3.class, "to", Vec3.class, "color", int.class);
		checkRecord(visiblePair, "catalogIndex", int.class,
			"pair", geiler.addons.client.dungeon.helpers.DungeonPuzzleObserver.CreeperPairVisual.class,
			"color", int.class);

		checkNullableRecordConstructor(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.BlazeTarget.class,
			"BlazeTarget keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(geiler.addons.client.dungeon.helpers.DungeonPuzzleLogic.PushStep.class,
			"PushStep keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(geiler.addons.client.dungeon.helpers.DungeonPuzzleTimingTracker.Completion.class,
			"Completion keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(geiler.addons.client.dungeon.helpers.DungeonPuzzleTimingTracker.DraftPrompt.class,
			"DraftPrompt keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(boulderState,
			"BoulderState keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(previousPush,
			"PreviousPush keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(waterTracer,
			"WaterTracerSegment keeps Java's nullable reference component behavior");
		checkNullableRecordConstructor(visiblePair,
			"VisibleCreeperPair keeps Java's nullable reference component behavior");

		var water = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings(true, 11, 22);
		var creeper = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			null, true, Float.NaN);
		var weirdos = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings(null, 33, 44);
		var quiz = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings(55, true);
		var settings = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings(
			water, creeper, weirdos, quiz);
		check(settings.water() == water && settings.creeper() == creeper
			&& settings.weirdos() == weirdos && settings.quiz() == quiz,
			"Settings record accessors expose their canonical component values");
		check(creeper.style() == geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE
			&& creeper.showTracer() && Float.compare(creeper.alpha(), 0.70f) == 0,
			"CreeperSettings normalizes null style and non-finite alpha in its canonical constructor");
		check(new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false, -1.0f).alpha() == 0.0f
			&& new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
				geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false, 2.0f).alpha() == 1.0f,
			"CreeperSettings clamps finite alpha to the inclusive range");
		check(weirdos.style() == geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE
			&& weirdos.correctColor() == 33 && weirdos.wrongColor() == 44,
			"WeirdosSettings normalizes null style and retains color components");
		var defaults = geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings.defaults();
		check(defaults.water().showTracer() && defaults.water().firstColor() == 0xFF55FF55
			&& defaults.water().secondColor() == 0xFFFFAA00
			&& Float.compare(defaults.creeper().alpha(), 0.70f) == 0
			&& defaults.weirdos().correctColor() == 0xB255FF55
			&& defaults.quiz().color() == 0xBF55FF55 && !defaults.quiz().depthTested(),
			"Settings.defaults retains the puzzle renderer defaults through its Kotlin delegate");
		checkSettingsRejectsNull(null, creeper, weirdos, quiz);
		checkSettingsRejectsNull(water, null, weirdos, quiz);
		checkSettingsRejectsNull(water, creeper, null, quiz);
		checkSettingsRejectsNull(water, creeper, weirdos, null);
		checkPuzzleRendererRecordValueSemantics();
	}

	private static void checkPuzzleRendererRecordValueSemantics() {
		var water = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings(true, 11, 22);
		var sameWater = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings(true, 11, 22);
		check(water.equals(sameWater) && water.hashCode() == sameWater.hashCode()
			&& water.toString().equals("WaterSettings[showTracer=true, firstColor=11, secondColor=22]"),
			"WaterSettings retains record equality, hash, and component string form");

		var normalizedCreeper = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			null, true, Float.NaN);
		var sameCreeper = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, true, 0.70f);
		check(normalizedCreeper.equals(sameCreeper)
			&& normalizedCreeper.hashCode() == sameCreeper.hashCode()
			&& normalizedCreeper.toString().equals(
				"CreeperSettings[style=FILLED_OUTLINE, showTracer=true, alpha=0.7]"),
			"CreeperSettings retains record value semantics after null-style and NaN normalization");
		var negativeZeroAlpha = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false, -0.0f).alpha();
		check(Float.floatToIntBits(negativeZeroAlpha) == Float.floatToIntBits(0.0f)
			&& new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
				geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false, -1.0f).alpha() == 0.0f
			&& new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
				geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false, 2.0f).alpha() == 1.0f,
			"CreeperSettings clamps finite alpha exactly at both bounds, including signed zero");
		check(new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
			geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false,
			Float.POSITIVE_INFINITY).alpha() == 0.70f
			&& new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings(
				geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE, false,
				Float.NEGATIVE_INFINITY).alpha() == 0.70f,
			"CreeperSettings applies the default alpha to both infinities");

		var weirdos = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings(null, 33, 44);
		var sameWeirdos = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings(
			geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE, 33, 44);
		check(weirdos.equals(sameWeirdos) && weirdos.hashCode() == sameWeirdos.hashCode()
			&& weirdos.toString().equals(
				"WeirdosSettings[style=FILLED_OUTLINE, correctColor=33, wrongColor=44]"),
			"WeirdosSettings retains record value semantics after null-style normalization");

		var quiz = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings(55, true);
		var sameQuiz = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings(55, true);
		check(quiz.equals(sameQuiz) && quiz.hashCode() == sameQuiz.hashCode()
			&& quiz.toString().equals("QuizSettings[color=55, depthTested=true]"),
			"QuizSettings retains record equality, hash, and component string form");

		var settings = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings(
			water, normalizedCreeper, weirdos, quiz);
		var sameSettings = new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings(
			sameWater, sameCreeper, sameWeirdos, sameQuiz);
		check(settings.equals(sameSettings) && settings.hashCode() == sameSettings.hashCode()
			&& settings.toString().equals("Settings[water=WaterSettings[showTracer=true, firstColor=11, secondColor=22], "
				+ "creeper=CreeperSettings[style=FILLED_OUTLINE, showTracer=true, alpha=0.7], "
				+ "weirdos=WeirdosSettings[style=FILLED_OUTLINE, correctColor=33, wrongColor=44], "
				+ "quiz=QuizSettings[color=55, depthTested=true]]"),
			"Settings retains ordered component equality, hash, and nested record string form");

	}

	private static void checkWaterBoardValueAbiAndSemantics() {
		Class<?> candidate = geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Candidate.class;
		Class<?> detection = geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Detection.class;
		Class<?> routeKeyType = geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.RouteKey.class;
		Class<?> clickType = geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click.class;
		Class<?> catalogResult = geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.CatalogResult.class;
		checkValueType(candidate, new Class<?>[] {int.class, List.class, String.class, boolean.class},
			"rotation", int.class, "patternIds", List.class, "extendedSlots", String.class, "loaded", boolean.class);
		checkValueType(detection, new Class<?>[] {int.class, int.class, String.class,
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.class},
			"rotation", int.class, "patternId", int.class, "extendedSlots", String.class,
			"unknownReason", geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.class);
		checkRecord(routeKeyType, "optimized", boolean.class, "patternId", int.class, "extendedSlots", String.class);
		checkRecord(clickType, "leverId", String.class, "seconds", double.class);
		checkValueType(catalogResult, new Class<?>[] {boolean.class, java.util.Map.class, String.class},
			"available", boolean.class, "routes", java.util.Map.class, "reason", String.class);

		checkPublicStaticMethod(geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.class,
			"detect", detection, List.class);
		checkPublicStaticMethod(geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.class,
			"validSlots", boolean.class, String.class);
		checkPublicStaticMethod(geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.class,
			"valveOrder", int.class, String.class);
		checkPublicStaticMethod(geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.class,
			"bundled", catalogResult);
		checkPublicStaticMethod(geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.class,
			"route", List.class, boolean.class, int.class, String.class);
		try {
			var detect = geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.class
				.getMethod("detect", List.class);
			var route = geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.class
				.getMethod("route", boolean.class, int.class, String.class);
			var candidateConstructor = candidate.getConstructor(int.class, List.class, String.class, boolean.class);
			var routesAccessor = catalogResult.getMethod("routes");
			check(detect.getGenericParameterTypes()[0].getTypeName().equals(
				"java.util.List<geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules$Candidate>")
				&& route.getGenericReturnType().getTypeName().equals(
					"java.util.List<geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog$Click>")
				&& candidateConstructor.getGenericParameterTypes()[1].getTypeName()
					.equals("java.util.List<java.lang.Integer>")
				&& routesAccessor.getGenericReturnType().getTypeName().equals(
					"java.util.Map<geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog$RouteKey, java.util.List<geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog$Click>>"),
				"Water Board Kotlin facades retain invariant Java generic signatures");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("Water Board generic ABI methods remain available", exception);
		}

		check(List.of(geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.values()).equals(List.of(
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.INCOMPLETE_ROTATIONS,
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.ACTIVE_ORIENTATION_UNAVAILABLE,
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES,
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.NO_CONFIRMED_SIGNATURE,
			geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.AMBIGUOUS_ROTATION)),
			"Water Board unknown-reason enum order remains stable");
		check(!candidate.isRecord() && !detection.isRecord() && !catalogResult.isRecord(),
			"normalized Kotlin value classes expose their Java component accessors without record reflection metadata");

		var sourceIds = new java.util.ArrayList<>(List.of(2, 4));
		var copiedCandidate = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Candidate(
			1, sourceIds, null, true);
		sourceIds.add(3);
		var equivalentCandidate = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Candidate(
			1, List.of(2, 4), "", true);
		check(copiedCandidate.patternIds().equals(List.of(2, 4))
			&& copiedCandidate.extendedSlots().isEmpty(),
			"Candidate keeps defensive list copies and null-to-empty normalization");
		check(copiedCandidate.equals(equivalentCandidate)
			&& copiedCandidate.hashCode() == equivalentCandidate.hashCode()
			&& copiedCandidate.toString().equals(
				"Candidate[rotation=1, patternIds=[2, 4], extendedSlots=, loaded=true]"),
			"Candidate retains Java record value equality, hash, and string form");
		boolean candidateListImmutable = false;
		try {
			copiedCandidate.patternIds().add(0);
		} catch (UnsupportedOperationException expected) {
			candidateListImmutable = true;
		}
		check(candidateListImmutable, "Candidate exposes an immutable pattern ID snapshot");

		var knownDetection = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Detection(
			1, 2, null, null);
		var sameDetection = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Detection(
			1, 2, "", null);
		check(knownDetection.known() && knownDetection.equals(sameDetection)
			&& knownDetection.hashCode() == sameDetection.hashCode()
			&& knownDetection.extendedSlots().isEmpty()
			&& knownDetection.toString().equals(
				"Detection[rotation=1, patternId=2, extendedSlots=, unknownReason=null]"),
			"Detection retains known-state and null-normalized record semantics");
		check(!new geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.Detection(
			-1, -1, "", geiler.addons.client.dungeon.helpers.DungeonWaterBoardRules.UnknownReason.UNLOADED_PROBES).known(),
			"Detection does not treat unknown records as known");

		var routeKey = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.RouteKey(false, 2, "014");
		var click = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click("water", 0.0);
		check(routeKey.equals(new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.RouteKey(
			false, 2, "014")) && click.equals(new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click(
			"water", 0.0)) && routeKey.toString().equals(
				"RouteKey[optimized=false, patternId=2, extendedSlots=014]")
			&& click.toString().equals("Click[leverId=water, seconds=0.0]"),
			"route keys and clicks retain record value and string semantics");
		check(!new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click("water", -0.0)
			.equals(new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click("water", 0.0)),
			"Click retains Java record signed-zero equality");

		var sourceRoutes = new java.util.HashMap<geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.RouteKey,
			List<geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.Click>>();
		sourceRoutes.put(routeKey, List.of(click));
		var result = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.CatalogResult(
			true, sourceRoutes, null);
		sourceRoutes.clear();
		var sameResult = new geiler.addons.client.dungeon.helpers.DungeonWaterBoardCatalog.CatalogResult(
			true, java.util.Map.of(routeKey, List.of(click)), "");
		check(result.routes().equals(sameResult.routes()) && result.reason().isEmpty()
			&& result.equals(sameResult) && result.hashCode() == sameResult.hashCode()
			&& result.toString().equals(
				"CatalogResult[available=true, routes={RouteKey[optimized=false, patternId=2, extendedSlots=014]=[Click[leverId=water, seconds=0.0]]}, reason=]"),
			"CatalogResult retains defensive map copy, null normalization, and record value semantics");
		boolean routesImmutable = false;
		try {
			result.routes().clear();
		} catch (UnsupportedOperationException expected) {
			routesImmutable = true;
		}
		check(routesImmutable, "CatalogResult exposes an immutable route map");
	}

	private static void checkPublicStaticMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) {
		try {
			var method = owner.getMethod(name, parameterTypes);
			check(java.lang.reflect.Modifier.isPublic(method.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				owner.getSimpleName() + "." + name + " retains its public static Java signature");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(owner.getSimpleName() + "." + name + " retains its Java method", exception);
		}
	}

	private static void checkValueType(Class<?> type, Class<?>[] constructorTypes, Object... expectedComponents) {
		check(expectedComponents.length % 2 == 0, type.getSimpleName() + " value component expectations are paired");
		try {
			var constructor = type.getConstructor(constructorTypes);
			check(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
				type.getSimpleName() + " retains its public constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " retains its public constructor", exception);
		}
		for (int index = 0; index < expectedComponents.length; index += 2) {
			String name = (String) expectedComponents[index];
			Class<?> returnType = (Class<?>) expectedComponents[index + 1];
			try {
				var accessor = type.getMethod(name);
				check(java.lang.reflect.Modifier.isPublic(accessor.getModifiers())
					&& !java.lang.reflect.Modifier.isStatic(accessor.getModifiers())
					&& accessor.getReturnType() == returnType,
					type.getSimpleName() + "." + name + " retains its Java component accessor");
			} catch (NoSuchMethodException exception) {
				throw new AssertionError(type.getSimpleName() + "." + name + " retains its Java component accessor",
					exception);
			}
		}
	}

	private static void checkSettingsRejectsNull(
		geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WaterSettings water,
		geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.CreeperSettings creeper,
		geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.WeirdosSettings weirdos,
		geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.QuizSettings quiz) {
		try {
			new geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRenderer.Settings(water, creeper, weirdos, quiz);
			throw new AssertionError("Settings rejects null record components");
		} catch (IllegalArgumentException expected) {
			check("Puzzle render settings are required".equals(expected.getMessage()),
				"Settings preserves its explicit IllegalArgumentException");
		}
	}

	private static void checkRecord(Class<?> type, Object... expectedComponents) {
		check(type.isRecord(), type.getSimpleName() + " remains a Java record");
		RecordComponent[] components = type.getRecordComponents();
		check(expectedComponents.length % 2 == 0 && components.length == expectedComponents.length / 2,
			type.getSimpleName() + " record component count remains stable");
		Class<?>[] parameterTypes = new Class<?>[components.length];
		for (int index = 0; index < components.length; index++) {
			String expectedName = (String) expectedComponents[index * 2];
			Class<?> expectedType = (Class<?>) expectedComponents[index * 2 + 1];
			check(expectedName.equals(components[index].getName()),
				type.getSimpleName() + " record component name/order remains stable");
			parameterTypes[index] = components[index].getType();
			check(parameterTypes[index] == expectedType,
				type.getSimpleName() + " record component type remains stable");
		}
		try {
			type.getDeclaredConstructor(parameterTypes);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " retains its canonical constructor", exception);
		}
	}

	private static void checkNullableRecordConstructor(Class<?> type, String label) {
		RecordComponent[] components = type.getRecordComponents();
		Class<?>[] parameterTypes = new Class<?>[components.length];
		Object[] arguments = new Object[components.length];
		for (int index = 0; index < components.length; index++) {
			parameterTypes[index] = components[index].getType();
			arguments[index] = zeroValue(parameterTypes[index]);
		}
		try {
			Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
			constructor.setAccessible(true);
			constructor.newInstance(arguments);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(label, exception);
		}
		check(true, label);
	}

	private static Object zeroValue(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == char.class) return '\0';
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0.0f;
		if (type == double.class) return 0.0;
		throw new AssertionError("unsupported primitive record component: " + type.getName());
	}

	private static Class<?> nestedRecord(String name) {
		try {
			return Class.forName(name);
		} catch (ClassNotFoundException exception) {
			throw new AssertionError("missing record ABI type: " + name, exception);
		}
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
