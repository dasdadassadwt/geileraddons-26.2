package geiler.addons.client.module.impl;

import geiler.addons.client.enchanting.AutoExperimentAutomation;
import geiler.addons.client.enchanting.AutoExperimentDelayRange;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentMilestone;
import geiler.addons.client.enchanting.ExperimentMenuUpdates;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SequenceStep;
import geiler.addons.client.enchanting.SolverView;
import geiler.addons.client.enchanting.SuperpairsBoard;
import geiler.addons.client.enchanting.UltrasequencerSequenceExecutor;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/** Direct regression checks for Auto Experiments' persisted module and Java interop contract. */
public final class AutoExperimentsModuleChecks {
	private AutoExperimentsModuleChecks() {
	}

	public static void run() {
		checkIdentityAndDefaults();
		checkSettingIdsDefaultsAndGroups();
		checkSolverSelection();
		checkJavaInteropSurface();
		checkSupportJavaInteropSurface();
		checkValueConstructorNormalization();
		checkJavaValueSemantics();
		checkKotlinBackedApiSurface();
		checkKotlinBackedBehavior();
	}

	private static void checkIdentityAndDefaults() {
		AutoExperimentsModule module = AutoExperimentsModule.INSTANCE;
		check(module == AutoExperimentsModule.INSTANCE, "the singleton module identity is stable");
		check("Auto Experiments".equals(module.name())
			&& "Auto Experiments".equals(module.configName()), "the persisted module ID is unchanged");
		check("Automatically solves Chronomatron and Ultrasequencer.".equals(module.description()),
			"the module description remains available to the GUI");
		check(module.category() == Category.ENCHANTING, "the module remains in Enchanting");
		check(!module.defaultEnabled(), "a fresh config keeps automation disabled");
		check(!module.isEnabled(), "the module does not enable itself during construction");
	}

	private static void checkSettingIdsDefaultsAndGroups() {
		AutoExperimentsModule module = AutoExperimentsModule.INSTANCE;
		check(module.booleanSettings().stream().map(setting -> setting.name()).toList()
			.equals(List.of("Chronomatron", "Ultrasequencer", "Debug Automation")),
			"boolean setting IDs and declaration order remain stable for config loading");
		check(module.numberSettings().stream().map(setting -> setting.name()).toList()
			.equals(List.of("First Click Delay (ms)", "Minimum Click Delay (ms)", "Maximum Click Delay (ms)")),
			"delay setting IDs and order remain stable for config loading");

		check(module.chronomatron().value() && module.ultrasequencer().value(),
			"both supported experiment types remain enabled within the module by default");
		check(!module.debugAutomation(), "automation diagnostics keep their user-facing opt-in setting off");
		check(module.firstClickDelay().intValue() == 500
			&& module.minimumClickDelay().intValue() == 250
			&& module.maximumClickDelay().intValue() == 350,
			"the first-click and randomized-delay defaults are unchanged");
		for (NumberSetting setting : module.numberSettings()) {
			check(Float.compare(setting.min(), 0f) == 0 && Float.compare(setting.max(), 2_000f) == 0
				&& setting.isInteger(), "each delay keeps its integer 0-2000 ms range: " + setting.name());
		}
		check(module.groups().stream().map(SettingGroup::name).toList()
			.equals(List.of("Experiments", "Timing", "Diagnostics")),
			"the settings GUI keeps its three sections and their order");
		check(settingNames(module.groups().get(0)).equals(List.of("Chronomatron", "Ultrasequencer"))
			&& settingNames(module.groups().get(1)).equals(List.of(
				"First Click Delay (ms)", "Minimum Click Delay (ms)", "Maximum Click Delay (ms)"))
			&& settingNames(module.groups().get(2)).equals(List.of("Debug Automation")),
			"settings stay in their original UI sections");
	}

	private static void checkSolverSelection() {
		AutoExperimentsModule module = AutoExperimentsModule.INSTANCE;
		check(module.supports(ExperimentType.CHRONOMATRON), "Chronomatron remains supported by default");
		check(module.supports(ExperimentType.ULTRASEQUENCER), "Ultrasequencer remains supported by default");
		check(!module.supports(ExperimentType.SUPERPAIRS), "Auto continues to leave Superpairs to its solver");

		BooleanSetting chronomatron = module.chronomatron();
		BooleanSetting ultrasequencer = module.ultrasequencer();
		boolean chronomatronWasSelected = chronomatron.rawValue();
		boolean ultrasequencerWasSelected = ultrasequencer.rawValue();
		try {
			chronomatron.setValue(false);
			check(!module.supports(ExperimentType.CHRONOMATRON),
				"the Chronomatron setting still controls solver ownership");
			ultrasequencer.setValue(false);
			check(!module.supports(ExperimentType.ULTRASEQUENCER),
				"the Ultrasequencer setting still controls solver ownership");
		} finally {
			chronomatron.setValue(chronomatronWasSelected);
			ultrasequencer.setValue(ultrasequencerWasSelected);
		}

		check(AutoExperimentsModule.hintedSequenceType("Chronomatron (Unrecognized Tier)")
			== ExperimentType.CHRONOMATRON, "malformed Chronomatron titles remain safe to classify");
		check(AutoExperimentsModule.hintedSequenceType("Ultrasequencer (Unrecognized Tier)")
			== ExperimentType.ULTRASEQUENCER, "malformed Ultrasequencer titles remain safe to classify");
		check(AutoExperimentsModule.hintedSequenceType("Superpairs (Unrecognized Tier)") == null,
			"malformed Superpairs titles remain outside Auto Experiments");
	}

	private static void checkJavaInteropSurface() {
		Class<AutoExperimentsModule> moduleClass = AutoExperimentsModule.class;
		try {
			check(Modifier.isStatic(moduleClass.getField("INSTANCE").getModifiers()),
				"Java callers retain the static singleton field");
			check(moduleClass.getMethod("supports", ExperimentType.class).getReturnType() == boolean.class,
				"the solver controller retains the Java supports API");
			check(moduleClass.getMethod("chronomatron").getReturnType() == BooleanSetting.class
				&& moduleClass.getMethod("ultrasequencer").getReturnType() == BooleanSetting.class,
				"the config loader retains both setting accessors");
			check(moduleClass.getMethod("firstClickDelay").getReturnType() == NumberSetting.class
				&& moduleClass.getMethod("minimumClickDelay").getReturnType() == NumberSetting.class
				&& moduleClass.getMethod("maximumClickDelay").getReturnType() == NumberSetting.class,
				"the config loader retains all delay accessors");
			check(moduleClass.getMethod("ultrasequencerPresentationProgress",
				AbstractContainerScreen.class, SolverView.class).getReturnType() == Optional.class,
				"the solver renderer retains the Auto cursor presentation API");
			moduleClass.getMethod("pauseAfterManualInput", AbstractContainerScreen.class);
			moduleClass.getMethod("handleVanillaSlotClick", AbstractContainerScreen.class, Slot.class,
				int.class, int.class, ContainerInput.class, ExperimentSolverModule.SlotClickDispatcher.class);
			moduleClass.getMethod("tick");
			moduleClass.getMethod("debugAutomation");
			var hintedType = moduleClass.getMethod("hintedSequenceType", String.class);
			check(Modifier.isStatic(hintedType.getModifiers()),
				"the existing Java test and diagnostic helper remains a static method");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Auto Experiments Java interoperability changed", exception);
		}
	}

	private static void checkSupportJavaInteropSurface() {
		try {
			Class<AutoExperimentAutomation> automation = AutoExperimentAutomation.class;
			Class<?> snapshot = AutoExperimentAutomation.Snapshot.class;
			Class<?> decision = AutoExperimentAutomation.Decision.class;
			checkKotlinValueClass(snapshot, "Snapshot");
			checkKotlinValueClass(decision, "Decision");
			snapshot.getConstructor(boolean.class, boolean.class, boolean.class, Object.class, Object.class,
				ExperimentType.class, ExperimentTier.class, ExperimentPhase.class, String.class,
					boolean.class, int.class, int.class, int.class, int.class, boolean.class);
			for (String accessor : List.of("enabled", "gameEnabled", "contextValid", "sequenceAvailable",
				"milestoneReached")) {
				check(snapshot.getMethod(accessor).getReturnType() == boolean.class,
					"the automation snapshot retains its Java boolean accessor: " + accessor);
			}
			for (String accessor : List.of("currentIndex", "sequenceLength", "expectedSlotId", "completedRounds")) {
				check(snapshot.getMethod(accessor).getReturnType() == int.class,
					"the automation snapshot retains its Java integer accessor: " + accessor);
			}
			check(snapshot.getMethod("screenIdentity").getReturnType() == Object.class
				&& snapshot.getMethod("menuIdentity").getReturnType() == Object.class
				&& snapshot.getMethod("type").getReturnType() == ExperimentType.class
				&& snapshot.getMethod("tier").getReturnType() == ExperimentTier.class
				&& snapshot.getMethod("phase").getReturnType() == ExperimentPhase.class,
				"the automation snapshot retains its Java reference accessors");
			check(snapshot.getMethod("status").getReturnType() == String.class,
				"the automation snapshot retains its Java status accessor");

			decision.getConstructor(AutoExperimentAutomation.Action.class, int.class, int.class, String.class);
			check(decision.getMethod("action").getReturnType() == AutoExperimentAutomation.Action.class
				&& decision.getMethod("sequenceIndex").getReturnType() == int.class
				&& decision.getMethod("slotId").getReturnType() == int.class
				&& decision.getMethod("explanation").getReturnType() == String.class,
				"the automation decision retains its Java record-style accessors");
			check(automation.getField("STAGE_TRANSITION_WATCHDOG_NANOS").getLong(null) == 15_000_000_000L,
				"the stage watchdog remains a Java-visible constant");
			check(automation.getMethod("tick", snapshot, long.class, long.class).getReturnType() == decision
				&& automation.getMethod("tick", snapshot, long.class, long.class, long.class).getReturnType() == decision
				&& automation.getMethod("synchronizeAfterManualProgress", snapshot, long.class, long.class)
					.getReturnType() == decision
				&& automation.getMethod("clickResult", boolean.class, snapshot, long.class, long.class)
					.getReturnType() == decision,
				"the automation tick and dispatch-result overloads keep their Java signatures");
			automation.getMethod("reset");
			check(automation.getMethod("pauseForManualInput").getReturnType() == String.class,
				"manual takeover keeps its Java return type");

			Class<AutoExperimentDelayRange> range = AutoExperimentDelayRange.class;
			checkKotlinValueClass(range, "AutoExperimentDelayRange");
			range.getConstructor(int.class, int.class);
			check(range.getMethod("minimumMillis").getReturnType() == int.class
				&& range.getMethod("maximumMillis").getReturnType() == int.class
				&& range.getMethod("sampleMillis", java.util.function.LongUnaryOperator.class).getReturnType()
					== int.class
				&& range.getMethod("sampleNanos", java.util.function.LongUnaryOperator.class).getReturnType()
					== long.class,
				"the Java config and caller accessors retain delay-range signatures");
			check(Modifier.isStatic(range.getMethod("fromLegacyDelay", float.class).getModifiers())
				&& range.getMethod("fromLegacyDelay", float.class).getReturnType() == range
				&& Modifier.isStatic(range.getMethod("restore", Float.class, Float.class, Float.class,
					int.class, int.class).getModifiers())
				&& range.getMethod("restore", Float.class, Float.class, Float.class,
					int.class, int.class).getReturnType() == range,
				"delay migration helpers remain Java static methods");
			check(range.getField("MINIMUM_MILLIS").getInt(null) == 0
				&& range.getField("MAXIMUM_MILLIS").getInt(null) == 2_000
				&& range.getField("DEFAULT_MINIMUM_MILLIS").getInt(null) == 250
				&& range.getField("DEFAULT_MAXIMUM_MILLIS").getInt(null) == 350,
				"delay bounds and defaults remain Java-visible constants");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Auto Experiments support Java interoperability changed", exception);
		}
	}

	private static void checkValueConstructorNormalization() {
		AutoExperimentAutomation.Snapshot snapshot = new AutoExperimentAutomation.Snapshot(
			false, false, false, null, null, null, null, null, null, false, -3, -2, -1, -4, false);
		check(snapshot.status().isEmpty() && snapshot.currentIndex() == 0
			&& snapshot.sequenceLength() == 0 && snapshot.completedRounds() == -1,
			"the Java-facing Kotlin snapshot constructor keeps null-status and negative-counter normalization");

		AutoExperimentAutomation.Snapshot trimmedSnapshot = new AutoExperimentAutomation.Snapshot(
			false, false, false, null, null, null, null, null, " \tRound Complete!\r", false, 0, 0, -1, -1, false);
		check("Round Complete!".equals(trimmedSnapshot.status()),
			"the Java-facing Kotlin snapshot constructor trims status using its existing boundary rule");

		AutoExperimentAutomation.Decision decision = new AutoExperimentAutomation.Decision(
			AutoExperimentAutomation.Action.WAIT, -1, -1, null);
		check(decision.explanation().isEmpty(), "the Java-facing Kotlin decision constructor normalizes a null explanation");
		AutoExperimentAutomation.Decision whitespaceExplanation = new AutoExperimentAutomation.Decision(
			AutoExperimentAutomation.Action.WAIT, -1, -1, " \t");
		check(" \t".equals(whitespaceExplanation.explanation()),
			"the Java-facing Kotlin decision constructor preserves non-null explanation whitespace");
		boolean nullActionRejected = false;
		try {
			new AutoExperimentAutomation.Decision(null, -1, -1, "");
		} catch (IllegalArgumentException expected) {
			nullActionRejected = true;
		}
		check(nullActionRejected, "the Java-facing Kotlin decision constructor rejects a null action");

		AutoExperimentDelayRange clamped = new AutoExperimentDelayRange(-1, 2_001);
		check(clamped.minimumMillis() == AutoExperimentDelayRange.MINIMUM_MILLIS
			&& clamped.maximumMillis() == AutoExperimentDelayRange.MAXIMUM_MILLIS,
			"the Java-facing Kotlin delay-range constructor clamps values before storing them");
		AutoExperimentDelayRange sampled = new AutoExperimentDelayRange(250, 350);
		check(sampled.sampleNanos(bound -> bound - 1L) == 350_000_000L,
			"the Java nanosecond sampler preserves the inclusive upper endpoint");
	}

	private static void checkJavaValueSemantics() {
		AutoExperimentAutomation.Snapshot normalizedSnapshot = new AutoExperimentAutomation.Snapshot(
			false, false, false, null, null, null, null, null, null, false, -3, -2, -1, -4, false);
		AutoExperimentAutomation.Snapshot equivalentSnapshot = new AutoExperimentAutomation.Snapshot(
			false, false, false, null, null, null, null, null, "", false, 0, 0, -1, -1, false);
		check(normalizedSnapshot.equals(equivalentSnapshot)
			&& normalizedSnapshot.hashCode() == equivalentSnapshot.hashCode()
			&& normalizedSnapshot.toString().equals("Snapshot[enabled=false, gameEnabled=false, contextValid=false, "
				+ "screenIdentity=null, menuIdentity=null, type=null, tier=null, phase=null, status=, "
				+ "sequenceAvailable=false, currentIndex=0, sequenceLength=0, expectedSlotId=-1, "
				+ "completedRounds=-1, milestoneReached=false]"),
			"the Kotlin snapshot preserves Java record value equality, hash contract, and string layout");

		AutoExperimentAutomation.Decision emptyDecision = new AutoExperimentAutomation.Decision(
			AutoExperimentAutomation.Action.WAIT, -1, -1, null);
		AutoExperimentAutomation.Decision equivalentDecision = new AutoExperimentAutomation.Decision(
			AutoExperimentAutomation.Action.WAIT, -1, -1, "");
		check(emptyDecision.equals(equivalentDecision)
			&& emptyDecision.hashCode() == equivalentDecision.hashCode()
			&& emptyDecision.toString().equals("Decision[action=WAIT, sequenceIndex=-1, slotId=-1, explanation=]"),
			"the Kotlin automation decision preserves Java record value behavior and string layout");

		AutoExperimentDelayRange normalizedRange = new AutoExperimentDelayRange(350, 250);
		AutoExperimentDelayRange equivalentRange = new AutoExperimentDelayRange(250, 350);
		check(normalizedRange.equals(equivalentRange)
			&& normalizedRange.hashCode() == equivalentRange.hashCode()
			&& normalizedRange.toString().equals(
				"AutoExperimentDelayRange[minimumMillis=250, maximumMillis=350]"),
			"the Kotlin delay range preserves Java record value behavior and string layout");

		SequenceStep step = new SequenceStep(0, "1", List.of(30));
		var mutableSteps = new java.util.ArrayList<>(List.of(step));
		var solution = new UltrasequencerSequenceExecutor.Solution(null, 0, 1, -1, mutableSteps);
		mutableSteps.clear();
		var equivalentSolution = new UltrasequencerSequenceExecutor.Solution(
			ExperimentTier.UNKNOWN, 0, 1, -1, List.of(step));
		check(solution.equals(equivalentSolution)
			&& solution.hashCode() == equivalentSolution.hashCode()
			&& solution.toString().equals("Solution[tier=UNKNOWN, completedRounds=0, sequenceLength=1, "
				+ "milestoneSequenceLength=-1, sequence=[SequenceStep[index=0, value=1, slotIds=[30]]]]")
			&& solution.sequence().equals(List.of(step)),
			"the Kotlin solution preserves record value behavior and defensive sequence copies");
		boolean sequenceIsImmutable = false;
		try {
			solution.sequence().clear();
		} catch (UnsupportedOperationException expected) {
			sequenceIsImmutable = true;
		}
		check(sequenceIsImmutable, "the Kotlin solution retains the immutable Java sequence view");

		var progress = new UltrasequencerSequenceExecutor.ExecutionProgress(solution, 5, false, true, null);
		var equivalentProgress = new UltrasequencerSequenceExecutor.ExecutionProgress(
			equivalentSolution, 1, false, true, "none");
		check(progress.equals(equivalentProgress)
			&& progress.hashCode() == equivalentProgress.hashCode()
			&& progress.toString().equals("ExecutionProgress[solution=" + solution
				+ ", cursor=1, dispatchPending=false, paused=true, pauseReason=none]"),
			"the Kotlin execution progress preserves Java record normalization and value formatting");
		boolean nullSolutionRejected = false;
		try {
			new UltrasequencerSequenceExecutor.ExecutionProgress(null, 0, false, false, null);
		} catch (IllegalArgumentException expected) {
			nullSolutionRejected = true;
		}
		check(nullSolutionRejected, "the Kotlin execution progress still rejects a null solution");

		var ultraDecision = new UltrasequencerSequenceExecutor.Decision(null, -1, -1, null);
		var equivalentUltraDecision = new UltrasequencerSequenceExecutor.Decision(
			UltrasequencerSequenceExecutor.Action.WAIT, -1, -1, "");
		check(ultraDecision.equals(equivalentUltraDecision)
			&& ultraDecision.hashCode() == equivalentUltraDecision.hashCode()
			&& ultraDecision.toString().equals("Decision[action=WAIT, sequenceIndex=-1, slotId=-1, explanation=]"),
			"the Kotlin Ultrasequencer decision preserves Java record value behavior and string layout");
	}

	private static void checkKotlinBackedApiSurface() {
		try {
			check(Modifier.isStatic(ExperimentType.class.getMethod("fromTitle", String.class).getModifiers())
				&& ExperimentType.class.getMethod("displayName").getReturnType() == String.class,
				"Kotlin experiment types retain their Java enum and static-parser API");
			check(Modifier.isStatic(ExperimentTier.class.getMethod("fromTitle", String.class).getModifiers())
				&& Modifier.isStatic(ExperimentTier.class.getMethod("fromName", String.class).getModifiers())
				&& ExperimentTier.class.getMethod("maximumExtraClicks").getReturnType() == int.class,
				"Kotlin tiers retain Java static parsers and threshold accessors");
			check(Modifier.isStatic(ExperimentPhase.class.getMethod("detect", ExperimentType.class,
				String.class).getModifiers())
				&& Modifier.isStatic(ExperimentPhase.class.getMethod("isKnownStatus", ExperimentType.class,
					String.class).getModifiers())
				&& Modifier.isStatic(ExperimentPhase.class.getMethod("normalizeStatus", String.class).getModifiers()),
				"Kotlin phase rules retain their Java static API");

			Class<ExperimentBoardGeometry> geometry = ExperimentBoardGeometry.class;
			checkKotlinValueClass(geometry, "ExperimentBoardGeometry");
			geometry.getConstructor(int.class, int.class, List.class);
			check(Modifier.isStatic(geometry.getMethod("forExperiment", ExperimentType.class,
				ExperimentTier.class).getModifiers())
				&& geometry.getMethod("columns").getReturnType() == int.class
				&& geometry.getMethod("rows").getReturnType() == int.class
				&& geometry.getMethod("slotIds").getReturnType() == List.class
				&& geometry.getMethod("containsSlot", int.class).getReturnType() == boolean.class
				&& geometry.getMethod("column", int.class).getReturnType() == int.class
				&& geometry.getMethod("row", int.class).getReturnType() == int.class
				&& geometry.getMethod("slotId", int.class, int.class).getReturnType() == int.class,
				"board geometry keeps its Kotlin class metadata and Java constructor, accessors, factory, and lookup methods");

			Class<ExperimentMilestone> milestone = ExperimentMilestone.class;
			checkKotlinValueClass(milestone, "ExperimentMilestone");
			milestone.getConstructor(ExperimentType.class, ExperimentTier.class, int.class,
				int.class, int.class, int.class);
			check(Modifier.isStatic(milestone.getMethod("forExperiment", ExperimentType.class,
				ExperimentTier.class, int.class).getModifiers())
				&& milestone.getMethod("type").getReturnType() == ExperimentType.class
				&& milestone.getMethod("tier").getReturnType() == ExperimentTier.class
				&& milestone.getMethod("serumsConsumed").getReturnType() == int.class
				&& milestone.getMethod("displayedSequenceLength").getReturnType() == int.class
				&& milestone.getMethod("completedRoundThreshold").getReturnType() == int.class
				&& milestone.getMethod("maximumExtraClicks").getReturnType() == int.class
				&& milestone.getMethod("reached", int.class, int.class).getReturnType() == boolean.class
				&& milestone.getMethod("reached", int.class, int.class, ExperimentPhase.class)
					.getReturnType() == boolean.class,
				"milestones retain their Kotlin class metadata and Java constructor, accessors, factory, and stop checks");

			Class<SolverView> view = SolverView.class;
			checkKotlinValueClass(view, "SolverView");
			view.getConstructor(ExperimentType.class, ExperimentTier.class, ExperimentPhase.class,
				int.class, int.class, List.class, int.class, int.class, int.class, Optional.class,
				Optional.class, Optional.class, SuperpairsBoard.View.class, Optional.class, boolean.class);
			check(Modifier.isStatic(view.getMethod("idle").getModifiers())
				&& view.getMethod("type").getReturnType() == ExperimentType.class
				&& view.getMethod("tier").getReturnType() == ExperimentTier.class
				&& view.getMethod("phase").getReturnType() == ExperimentPhase.class
				&& view.getMethod("currentSequenceLength").getReturnType() == int.class
				&& view.getMethod("completedRounds").getReturnType() == int.class
				&& view.getMethod("sequence").getReturnType() == List.class
				&& view.getMethod("authoritativeIndex").getReturnType() == int.class
				&& view.getMethod("predictedIndex").getReturnType() == int.class
				&& view.getMethod("visualIndex").getReturnType() == int.class
				&& view.getMethod("current").getReturnType() == Optional.class
				&& view.getMethod("next").getReturnType() == Optional.class
				&& view.getMethod("nextNext").getReturnType() == Optional.class
				&& view.getMethod("superpairs").getReturnType() == SuperpairsBoard.View.class
				&& view.getMethod("milestone").getReturnType() == Optional.class
				&& view.getMethod("milestoneReached").getReturnType() == boolean.class
				&& view.getMethod("upcoming", int.class).getReturnType() == List.class,
				"SolverView retains its Kotlin class metadata and Java constructor, accessors, factory, and sequence-window API");

			Class<ExperimentClickGate> gate = ExperimentClickGate.class;
			check(gate.getMethod("dispatchSequenceClick", ExperimentSolverEngine.class, int.class,
				boolean.class, IntConsumer.class).getReturnType() == boolean.class
				&& gate.getMethod("dispatchSequenceClick", ExperimentSolverEngine.class, int.class,
					int.class, boolean.class, IntConsumer.class).getReturnType() == boolean.class
				&& gate.getMethod("dispatchSequenceClickResult", ExperimentSolverEngine.class, int.class,
					int.class, boolean.class, IntConsumer.class).getReturnType()
					== ExperimentClickGate.SequenceDispatchResult.class
				&& gate.getMethod("dispatchVanilla", Runnable.class).getReturnType() == void.class,
				"the Kotlin click gate retains both Java overloads and dispatch result types");
			check(gate.getMethod("dispatching").getReturnType() == boolean.class
				&& ExperimentClickGate.SequenceDispatchResult.class.getMethod("dispatched").getReturnType()
					== boolean.class
				&& ExperimentClickGate.SequenceDispatchResult.class.getMethod("progressAdvanced")
					.getReturnType() == boolean.class,
				"the click guard retains its Java state and result accessors");
			check(Modifier.isStatic(ExperimentMenuUpdates.class.getMethod("afterServerUpdate",
				AbstractContainerMenu.class, Runnable.class).getModifiers()),
				"the menu update hook remains callable as a Java static method");

			Class<UltrasequencerSequenceExecutor> executor = UltrasequencerSequenceExecutor.class;
			Class<?> action = UltrasequencerSequenceExecutor.Action.class;
			Class<?> decision = UltrasequencerSequenceExecutor.Decision.class;
			Class<?> progress = UltrasequencerSequenceExecutor.ExecutionProgress.class;
			Class<?> solution = UltrasequencerSequenceExecutor.Solution.class;
			check(action.isEnum(), "executor actions remain a Java enum");
			checkKotlinValueClass(decision, "Ultrasequencer Decision");
			checkKotlinValueClass(progress, "Ultrasequencer ExecutionProgress");
			checkKotlinValueClass(solution, "Ultrasequencer Solution");
			decision.getConstructor(action, int.class, int.class, String.class);
			progress.getConstructor(solution, int.class, boolean.class, boolean.class, String.class);
			solution.getConstructor(ExperimentTier.class, int.class, int.class, int.class, List.class);
			check(decision.getMethod("action").getReturnType() == action
				&& decision.getMethod("sequenceIndex").getReturnType() == int.class
				&& decision.getMethod("slotId").getReturnType() == int.class
				&& decision.getMethod("explanation").getReturnType() == String.class
				&& progress.getMethod("solution").getReturnType() == solution
				&& progress.getMethod("cursor").getReturnType() == int.class
				&& progress.getMethod("dispatchPending").getReturnType() == boolean.class
				&& progress.getMethod("paused").getReturnType() == boolean.class
				&& progress.getMethod("pauseReason").getReturnType() == String.class
				&& solution.getMethod("tier").getReturnType() == ExperimentTier.class
				&& solution.getMethod("completedRounds").getReturnType() == int.class
				&& solution.getMethod("sequenceLength").getReturnType() == int.class
				&& solution.getMethod("milestoneSequenceLength").getReturnType() == int.class
				&& progress.getMethod("sequenceLength").getReturnType() == int.class
				&& solution.getMethod("sequence").getReturnType() == List.class,
				"Kotlin executor values retain their Java constructors and accessor signatures");
			check(Modifier.isStatic(solution.getMethod("from", SolverView.class).getModifiers())
				&& executor.getMethod("tick", Object.class, Object.class, long.class, boolean.class,
					SolverView.class, long.class, long.class).getReturnType() == decision
				&& executor.getMethod("dispatchFinished", boolean.class, long.class, long.class)
					.getReturnType() == decision
				&& executor.getMethod("presentationProgress", Object.class, Object.class, long.class,
					SolverView.class).getReturnType() == Optional.class
				&& executor.getMethod("reset").getReturnType() == void.class,
				"the executor keeps its Java-facing value adapters and method signatures");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Auto Experiments Kotlin-backed API changed", exception);
		}
	}

	private static void checkKotlinBackedBehavior() {
		check(ExperimentType.fromTitle("§aChronomatron (§bHigh)").orElseThrow()
			== ExperimentType.CHRONOMATRON, "formatting is removed before experiment title matching");
		check(ExperimentTier.fromTitle("Chronomatron (§bSupreme)").orElseThrow()
			== ExperimentTier.SUPREME, "tier parsing preserves formatting removal and exact tier names");
		check(ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "§eRemember the pattern!")
			== ExperimentPhase.MEMORIZE
			&& ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "Round Complete!")
			== ExperimentPhase.ROUND_COMPLETE,
			"phase rules preserve memorize and round-boundary classification");

		ExperimentBoardGeometry shortChrono = ExperimentBoardGeometry.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH);
		check(shortChrono.columns() == 9 && shortChrono.rows() == 1
			&& shortChrono.slotIds().equals(List.of(17, 18, 19, 20, 21, 22, 23, 24, 25))
			&& shortChrono.slotId(8, 0) == 25 && shortChrono.column(25) == 8,
			"the cached short Chronomatron board retains its ordered live-slot mapping");
		ExperimentBoardGeometry ultra = ExperimentBoardGeometry.forExperiment(
			ExperimentType.ULTRASEQUENCER, ExperimentTier.UNKNOWN);
		check(ultra.columns() == 9 && ultra.rows() == 4 && ultra.slotId(0, 0) == 9
			&& ultra.slotId(8, 3) == 44,
			"Ultrasequencer retains its full 9-to-44 board geometry");

		ExperimentMilestone chrono = ExperimentMilestone.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, 1).orElseThrow();
		check(chrono.displayedSequenceLength() == 8 && chrono.reached(8, 7,
			ExperimentPhase.ROUND_COMPLETE),
			"serum-adjusted Chronomatron stopping still counts the just-finished round");
		check(SolverView.idle().phase() == ExperimentPhase.IDLE && SolverView.idle().sequence().isEmpty(),
			"idle render snapshots keep their original empty state");
		AutoExperimentDelayRange delay = new AutoExperimentDelayRange(350, 250);
		check(delay.minimumMillis() == 250 && delay.maximumMillis() == 350
			&& delay.sampleMillis(bound -> bound - 1) == 350,
			"delay normalization and inclusive bounded sampling retain their endpoints");

		SequenceStep step = new SequenceStep(0, "1", List.of(30));
		SolverView solutionView = new SolverView(ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, 1, 0, List.of(step), 0, 0, 0, Optional.of(step), Optional.empty(),
			Optional.empty(), SuperpairsBoard.View.empty(), Optional.empty(), false);
		Object screen = new Object();
		Object menu = new Object();
		UltrasequencerSequenceExecutor executor = new UltrasequencerSequenceExecutor();
		check(executor.tick(screen, menu, 1, true, solutionView, 100, 5).action()
			== UltrasequencerSequenceExecutor.Action.WAIT
			&& executor.tick(screen, menu, 1, true, solutionView, 105, 5).action()
			== UltrasequencerSequenceExecutor.Action.CLICK,
			"the executor captures a complete solution and dispatches after its configured first delay");
		executor.dispatchFinished(true, 105, 20);
		var progress = executor.presentationProgress(screen, menu, 1, solutionView).orElseThrow();
		check(progress.cursor() == 1 && !progress.dispatchPending()
			&& executor.pendingDelay().equals("none"),
			"a completed one-step sequence remains presentation-only and has no pending delay");
		executor.reset();
		check(executor.presentationProgress(screen, menu, 1, solutionView).isEmpty(),
			"executor reset discards the captured identity and sequence snapshot");
	}

	private static void checkKotlinValueClass(Class<?> type, String label) {
		check(Modifier.isFinal(type.getModifiers()) && !type.isRecord()
			&& Arrays.stream(type.getDeclaredAnnotations()).anyMatch(annotation ->
				annotation.annotationType().getName().equals("kotlin.Metadata")),
			label + " is a final Kotlin value class with explicit Java accessors and Kotlin metadata");
	}

	private static List<String> settingNames(SettingGroup group) {
		return group.settings().stream().map(setting -> setting.name()).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
