package geiler.addons.client.module.impl;

import geiler.addons.client.tiki.TikiSolver;
import geiler.addons.client.tiki.TikiStacks;
import geiler.addons.client.config.TikiCoords;
import geiler.addons.client.config.TikiFingerprints;
import geiler.addons.client.gui.TikiCoordManagerScreen;
import geiler.addons.client.gui.TikiCoordinateParser;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import kotlin.Unit;
import kotlin.jvm.functions.Function5;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline regression checks for the Tiki Helper screen API, waypoint data, and solver behavior. */
public final class TikiHelperChecks {
	private TikiHelperChecks() {
	}

	public static void main(String[] args) throws ReflectiveOperationException, IOException {
		checkCoordinateManagerScreenCompatibility();
		checkPlanRecordCompatibility();
		checkSettingsAndWaypointDefaults();
		checkFingerprintPersistence();
		checkRuleSemantics();
		checkEveryReadableState();
		checkHiddenSlotPlans();
		checkSolverLabelOutput();
		checkResearchTracePayloadBound();
		System.out.println("Tiki Helper checks passed");
	}

	private static void checkResearchTracePayloadBound() throws IOException {
		String source = Files.readString(Path.of("src", "client", "kotlin", "geiler", "addons", "client",
			"config", "TikiDebugLog.kt")).replace("\r\n", "\n");
		if (!source.contains("MAX_PAYLOAD_LENGTH = 4_096")
			|| !source.contains("line.take(MAX_PAYLOAD_LENGTH)")) {
			throw new AssertionError("Opt-in Tiki research trace payloads must remain capped at 4,096 characters");
		}
	}

	private static void checkCoordinateManagerScreenCompatibility() throws ReflectiveOperationException {
		Class<TikiCoordManagerScreen> screenClass = TikiCoordManagerScreen.class;
		if (!Modifier.isPublic(screenClass.getModifiers()) || Modifier.isFinal(screenClass.getModifiers())
			|| screenClass.getSuperclass() != Screen.class) {
			throw new AssertionError("Coordinate manager must retain its public, extensible Screen type");
		}

		Constructor<TikiCoordManagerScreen> constructor = screenClass.getDeclaredConstructor(Screen.class);
		if (!Modifier.isPublic(constructor.getModifiers())) {
			throw new AssertionError("Coordinate manager must retain its public Screen constructor");
		}
		if (constructor.getParameterCount() != 1 || constructor.getParameterTypes()[0] != Screen.class) {
			throw new AssertionError("Coordinate manager must retain its nullable Screen constructor signature");
		}
		if (!Integer.valueOf(-42).equals(TikiCoordinateParser.parse(" \t-42\r"))
			|| TikiCoordinateParser.parse("\u2003-42") != null
			|| TikiCoordinateParser.parse("2147483648") != null
			|| TikiCoordinateParser.parse(null) != null) {
			throw new AssertionError("Coordinate parsing must match Java trim and integer bounds");
		}

		assertScreenMethod(screenClass, "init", void.class, Modifier.PROTECTED);
		assertScreenMethod(screenClass, "isPauseScreen", boolean.class, Modifier.PUBLIC);
		assertScreenMethod(screenClass, "extractRenderState", void.class, Modifier.PUBLIC,
			GuiGraphicsExtractor.class, int.class, int.class, float.class);
		assertScreenMethod(screenClass, "mouseClicked", boolean.class, Modifier.PUBLIC,
			MouseButtonEvent.class, boolean.class);
		assertScreenMethod(screenClass, "mouseScrolled", boolean.class, Modifier.PUBLIC,
			double.class, double.class, double.class, double.class);
		assertScreenMethod(screenClass, "keyPressed", boolean.class, Modifier.PUBLIC, KeyEvent.class);
		assertScreenMethod(screenClass, "onClose", void.class, Modifier.PUBLIC);
	}

	private static void assertScreenMethod(Class<?> screenClass, String name, Class<?> returnType,
		int visibility, Class<?>... parameterTypes) throws ReflectiveOperationException {
		var method = screenClass.getDeclaredMethod(name, parameterTypes);
		int modifiers = method.getModifiers();
		int actualVisibility = modifiers & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
		if (method.getReturnType() != returnType || actualVisibility != visibility || Modifier.isFinal(modifiers)) {
			throw new AssertionError("Coordinate manager Java method signature changed: " + method);
		}
	}

	private static void checkPlanRecordCompatibility() throws ReflectiveOperationException {
		Class<TikiSolver.Plan> planClass = TikiSolver.Plan.class;
		if (!planClass.isRecord()) throw new AssertionError("Solver plans must keep the Java record ABI");

		String[] names = {"index", "direction", "clicks", "remaining"};
		RecordComponent[] components = planClass.getRecordComponents();
		if (components.length != names.length) throw new AssertionError("Solver plan component count changed");
		for (int i = 0; i < names.length; i++) {
			RecordComponent component = components[i];
			if (!component.getName().equals(names[i]) || component.getType() != int.class
				|| !component.getAccessor().getName().equals(names[i])
				|| component.getAccessor().getReturnType() != int.class) {
				throw new AssertionError("Solver plan record component changed at index " + i + ": " + component);
			}
		}

		Constructor<TikiSolver.Plan> constructor = planClass.getDeclaredConstructor(
			int.class, int.class, int.class, int.class);
		if (!Modifier.isPublic(constructor.getModifiers())) {
			throw new AssertionError("Solver plan canonical constructor must remain public");
		}
		var parameters = constructor.getParameters();
		for (int i = 0; i < names.length; i++) {
			if (parameters[i].getType() != int.class ||
				(parameters[i].isNamePresent() && !parameters[i].getName().equals(names[i]))) {
				throw new AssertionError("Solver plan constructor parameter changed at index " + i);
			}
		}

		TikiSolver.Plan plan = new TikiSolver.Plan(2, -1, 3, 5);
		TikiSolver.Plan equalPlan = new TikiSolver.Plan(2, -1, 3, 5);
		if (plan.index() != 2 || plan.direction() != -1 || plan.clicks() != 3 || plan.remaining() != 5
			|| !plan.equals(equalPlan) || plan.hashCode() != equalPlan.hashCode()
			|| !plan.toString().equals("Plan[index=2, direction=-1, clicks=3, remaining=5]")) {
			throw new AssertionError("Solver plan constructor/accessor/value semantics changed: " + plan);
		}
		if (plan.equals(new TikiSolver.Plan(2, -1, 3, 4))) {
			throw new AssertionError("Solver plans with different components compared equal");
		}
		TikiSolver.Plan unnormalized = new TikiSolver.Plan(-7, 9, -3, -11);
		if (unnormalized.index() != -7 || unnormalized.direction() != 9
			|| unnormalized.clicks() != -3 || unnormalized.remaining() != -11) {
			throw new AssertionError("Solver plan constructor must preserve raw component values");
		}

		List<TikiSolver.Plan> invalidSequence = TikiSolver.solveSequence(null, TikiSolver.Rule.OBSERVED);
		try {
			invalidSequence.add(plan);
		} catch (UnsupportedOperationException immutable) {
			throw new AssertionError("Invalid solveSequence results must remain mutable like the Java ArrayList", immutable);
		}
	}

	private static void checkSettingsAndWaypointDefaults() {
		TikiHelperModule module = TikiHelperModule.INSTANCE;
		if (!module.name().equals("Tiki Helper") || module.category() != Category.HUNTING || module.defaultEnabled()) {
			throw new AssertionError("Tiki Helper identity or fresh-install enabled state changed");
		}
		assertNames(module.booleanSettings().stream().map(BooleanSetting::name).toList(), List.of(
			"Waypoints", "Tracer Line", "Solver", "Show Locked", "Show Unknown", "World Labels",
			"Hypixel Rule", "Show Future Clicks", "Debug Logging", "Track Sounds", "Track Chat",
			"Verify Solver", "Echo To Chat"), "boolean settings");
		boolean[] booleanDefaults = {true, true, true, true, true, false, false, true, false, true, true, true, false};
		for (int i = 0; i < booleanDefaults.length; i++) {
			if (module.booleanSettings().get(i).rawValue() != booleanDefaults[i]) {
				throw new AssertionError("Boolean default changed: " + module.booleanSettings().get(i).name());
			}
		}

		assertNames(module.numberSettings().stream().map(NumberSetting::name).toList(), List.of(
			"Scan Interval", "Tracer Width", "Solver Range", "Label Height", "Text Size", "Track Radius"), "number settings");
		float[] numberDefaults = {20.0f, 2.0f, 8.0f, 0.35f, 1.0f, 8.0f};
		for (int i = 0; i < numberDefaults.length; i++) {
			if (Math.abs(module.numberSettings().get(i).value() - numberDefaults[i]) > 0.0001f) {
				throw new AssertionError("Number default changed: " + module.numberSettings().get(i).name());
			}
		}

		assertNames(module.colorSettings().stream().map(ColorSetting::name).toList(), List.of(
			"Valid Color", "Invalid Color", "Tracer Color", "Left Color", "Right Color", "Locked Color", "Future Color"), "color settings");
		int[][] colors = {{0, 255, 0, 19}, {255, 0, 0, 20}, {0, 255, 0, 255}, {85, 255, 85, 255},
			{255, 60, 60, 255}, {160, 160, 160, 200}, {200, 200, 200, 130}};
		for (int i = 0; i < colors.length; i++) {
			ColorSetting setting = module.colorSettings().get(i);
			if (setting.red() != colors[i][0] || setting.green() != colors[i][1]
				|| setting.blue() != colors[i][2] || setting.alpha() != colors[i][3]) {
				throw new AssertionError("Color default changed: " + setting.name());
			}
		}
		assertNames(module.groups().stream().map(SettingGroup::name).toList(),
			List.of("Waypoints", "Solver", "Appearance", "Debug Logging", "Coordinates"), "setting groups");
		if (module.actions().size() != 1 || !((ModuleAction) module.actions().get(0)).label().equals("Manage Tiki Coords")) {
			throw new AssertionError("Coordinate manager action changed");
		}

		List<BlockPos> saved = new ArrayList<>(TikiCoords.all());
		try {
			TikiCoords.seedDefaults();
			List<BlockPos> expectedDefaults = List.of(
				new BlockPos(-731, 134, 153), new BlockPos(-731, 130, 172), new BlockPos(-729, 143, 179),
				new BlockPos(-742, 136, 231), new BlockPos(-759, 125, 245), new BlockPos(-735, 128, 268),
				new BlockPos(-714, 135, 290), new BlockPos(-700, 132, 288), new BlockPos(-667, 133, 263),
				new BlockPos(-661, 125, 245), new BlockPos(-625, 133, 283), new BlockPos(-614, 124, 281),
				new BlockPos(-596, 128, 284), new BlockPos(-687, 131, 219), new BlockPos(-637, 142, 161),
				new BlockPos(-615, 143, 187), new BlockPos(-591, 139, 175), new BlockPos(-573, 136, 184),
				new BlockPos(-560, 144, 191), new BlockPos(-544, 139, 208), new BlockPos(-545, 131, 213),
				new BlockPos(-600, 137, 235), new BlockPos(-587, 152, 239), new BlockPos(-603, 172, 231),
				new BlockPos(-674, 143, 146));
			if (!TikiCoords.all().equals(expectedDefaults)) {
				throw new AssertionError("Default waypoint coordinates changed");
			}
			try {
				TikiCoords.all().clear();
				throw new AssertionError("Tiki waypoint view must remain read-only");
			} catch (UnsupportedOperationException expected) {
				// Config and UI changes go through the explicit mutation methods.
			}
			if (TikiCoords.add(TikiCoords.all().get(0))) throw new AssertionError("Duplicate waypoint was accepted");
			if (!TikiCoords.add(new BlockPos(1, 2, 3))) throw new AssertionError("New waypoint was rejected");
			TikiCoords.remove(TikiCoords.size() - 1);
		} finally {
			TikiCoords.replaceAll(saved);
		}
	}

	private static void checkRuleSemantics() {
		TikiSolver.Rule observed = TikiSolver.Rule.OBSERVED;
		assertMove(observed, new int[]{0, 2, 0}, 0, 1, new int[]{0, 4, 0}, "locked clicked head, unlocked companion");
		assertMove(observed, new int[]{0, 0, 2}, 1, 1, new int[]{0, 0, 4}, "locked clicked head, movable top companion");
		assertMove(observed, new int[]{0, 2, 0}, 2, 1, new int[]{0, 2, 0}, "locked clicked and companion heads");
		assertMove(observed, new int[]{0, 4, 8}, 0, 1, new int[]{2, 6, 8}, "unlocked clicked and companion heads");
		assertMove(observed, new int[]{0, 14, 8}, 0, -1, new int[]{14, 12, 8}, "negative rotation wraps at zero");
		assertLocked(observed, new int[]{0, 2, 0}, 0, true);
		assertLocked(observed, new int[]{0, 2, 0}, 1, false);
		assertLocked(observed, new int[]{0, 2, 0}, 2, true);

		TikiSolver.Rule wiki = TikiSolver.Rule.WIKI;
		assertMove(wiki, new int[]{0, 4, 4}, 1, 1, new int[]{0, 4, 4}, "wiki freezes the matching top pair when clicked");
		assertMove(wiki, new int[]{0, 4, 4}, 0, 1, new int[]{2, 4, 4}, "wiki lets the bottom turn while the top pair stays frozen");
		assertMove(wiki, new int[]{0, 2, 4}, 2, 1, new int[]{2, 2, 6}, "wiki only freezes a matching top pair");
		assertLocked(wiki, new int[]{0, 4, 4}, 0, false);
		assertLocked(wiki, new int[]{0, 4, 4}, 1, true);
		assertLocked(wiki, new int[]{0, 4, 4}, 2, true);
	}

	private static void assertMove(TikiSolver.Rule rule, int[] before, int index, int direction,
		int[] expected, String label) {
		int[] original = before.clone();
		int[] actual = TikiSolver.applyMove(before, index, direction, rule);
		if (!Arrays.equals(actual, expected)) {
			throw new AssertionError(rule + " move changed for " + label + ": " + Arrays.toString(actual));
		}
		if (!Arrays.equals(before, original) || actual == before) {
			throw new AssertionError(rule + " move must return a separate state array for " + label);
		}
	}

	private static void assertLocked(TikiSolver.Rule rule, int[] rotations, int index, boolean expected) {
		if (TikiSolver.isLocked(rotations, index, rule) != expected) {
			throw new AssertionError(rule + " lock state changed for " + Arrays.toString(rotations) + " at " + index);
		}
	}

	private static void assertNames(List<String> actual, List<String> expected, String label) {
		if (!actual.equals(expected)) throw new AssertionError(label + " changed: " + actual);
	}

	private static void checkFingerprintPersistence() {
		Map<BlockPos, Block> saved = new HashMap<>(TikiFingerprints.all());
		try {
			TikiFingerprints.replaceAll(Map.of());
			BlockPos coord = new BlockPos(-600, 137, 235);
			TikiFingerprints.learn(coord, Blocks.STONE);
			TikiFingerprints.learn(coord, Blocks.DIRT);
			if (TikiFingerprints.get(coord) != Blocks.STONE) {
				throw new AssertionError("A learned Tiki fingerprint was replaced");
			}
			if (!TikiFingerprints.idOf(Blocks.STONE).equals("minecraft:stone") ||
				TikiFingerprints.byId("minecraft:stone") != Blocks.STONE || TikiFingerprints.byId("not a block id") != null) {
				throw new AssertionError("Tiki fingerprint registry id conversion changed");
			}
			try {
				TikiFingerprints.all().clear();
				throw new AssertionError("Tiki fingerprint data view is mutable");
			} catch (UnsupportedOperationException expected) {
				// The config layer must use replaceAll() to mutate saved fingerprints.
			}
			TikiFingerprints.pruneTo(List.of());
			if (!TikiFingerprints.all().isEmpty()) throw new AssertionError("Removed Tiki fingerprints were retained");
		} finally {
			TikiFingerprints.replaceAll(saved);
		}
	}

	private static void checkEveryReadableState() {
		for (TikiSolver.Rule rule : TikiSolver.Rule.values()) {
			for (int bottom = 0; bottom < TikiSolver.SLOTS; bottom += TikiSolver.STEP) {
				for (int middle = 0; middle < TikiSolver.SLOTS; middle += TikiSolver.STEP) {
					for (int top = 0; top < TikiSolver.SLOTS; top += TikiSolver.STEP) {
						int[] state = {bottom, middle, top};
						List<TikiSolver.Plan> sequence = TikiSolver.solveSequence(state, rule);
						TikiSolver.Plan first = TikiSolver.solve(state, rule);
						if ((first == null) != sequence.isEmpty()) {
							throw new AssertionError(rule + " plan/sequence disagreement for " + Arrays.toString(state));
						}
						if (first != null && !same(first, sequence.get(0))) {
							throw new AssertionError(rule + " sequence did not begin with solve() for " + Arrays.toString(state));
						}

						int[] current = state;
						int previousRemaining = Integer.MAX_VALUE;
						for (TikiSolver.Plan plan : sequence) {
							if (plan.clicks() < 1 || plan.remaining() >= previousRemaining) {
								throw new AssertionError(rule + " non-decreasing plan for " + Arrays.toString(current));
							}
							if (plan.clicks() > plan.remaining()) {
								throw new AssertionError(rule + " run exceeds remaining clicks for " + Arrays.toString(current));
							}
							previousRemaining = plan.remaining();
							for (int click = 0; click < plan.clicks(); click++) {
								current = TikiSolver.applyMove(current, plan.index(), plan.direction(), rule);
							}
						}
						if (!sequence.isEmpty() && !TikiSolver.isSolved(current)) {
							throw new AssertionError(rule + " sequence did not solve " + Arrays.toString(state)
								+ "; reached " + Arrays.toString(current));
						}
					}
				}
			}
		}
	}

	private static void checkHiddenSlotPlans() {
		for (int hidden = 0; hidden < 3; hidden++) {
			int companion = (hidden + 1) % 3;
			int feeder = (hidden + 2) % 3;
			for (int companionRotation = 0; companionRotation < TikiSolver.SLOTS; companionRotation += TikiSolver.STEP) {
				for (int feederRotation = 0; feederRotation < TikiSolver.SLOTS; feederRotation += TikiSolver.STEP) {
					int[] visiblePair = new int[3];
					visiblePair[hidden] = -1;
					visiblePair[companion] = companionRotation;
					visiblePair[feeder] = feederRotation;
					TikiSolver.Plan actual = TikiSolver.solveWithHidden(visiblePair, hidden);
					TikiSolver.Plan expected;
					if (companionRotation == feederRotation) {
						expected = new TikiSolver.Plan(hidden, 1, TikiSolver.UNKNOWN_CLICKS,
							TikiSolver.UNKNOWN_CLICKS);
					} else {
						int forward = Math.floorMod(companionRotation - feederRotation, TikiSolver.SLOTS) / TikiSolver.STEP;
						int backward = TikiSolver.SLOTS / TikiSolver.STEP - forward;
						boolean left = forward <= backward;
						int clicks = left ? forward : backward;
						expected = new TikiSolver.Plan(feeder, left ? 1 : -1, clicks, clicks);
					}
					if (actual == null || !same(expected, actual)) {
						throw new AssertionError("Hidden-slot plan changed for hidden=" + hidden + " visible=" +
							companionRotation + "," + feederRotation + ": " + actual);
					}
				}
			}
		}
		if (TikiSolver.solveWithHidden(null, 0) != null ||
			TikiSolver.solveWithHidden(new int[]{0, 4}, 0) != null ||
			TikiSolver.solveWithHidden(new int[]{-1, 4, 8}, -1) != null ||
			TikiSolver.solveWithHidden(new int[]{-1, 4, 8}, 3) != null ||
			TikiSolver.solveWithHidden(new int[]{-1, -1, 8}, 0) != null ||
			TikiSolver.solveWithHidden(new int[]{-1, 3, 8}, 0) != null ||
			TikiSolver.solveWithHidden(new int[]{-1, 4, 16}, 0) != null) {
			throw new AssertionError("Invalid hidden-slot inputs must be rejected");
		}
		TikiSolver.Plan alreadyAligned = TikiSolver.solveWithHidden(new int[]{-1, 4, 4}, 0);
		if (alreadyAligned == null || alreadyAligned.index() != 0 ||
			alreadyAligned.clicks() != TikiSolver.UNKNOWN_CLICKS ||
			alreadyAligned.remaining() != TikiSolver.UNKNOWN_CLICKS) {
			throw new AssertionError("Aligned visible heads must point at the unknown slot");
		}
	}

	private static void checkSolverLabelOutput() throws ReflectiveOperationException {
		TikiHelperModule module = TikiHelperModule.INSTANCE;
		Field slotBase = moduleField("slotBase");
		Field rotations = moduleField("rotations");
		Field hiddenIndex = moduleField("hiddenIndex");
		Field plan = moduleField("plan");
		Field futurePlans = moduleField("futurePlans");
		Method emitLabels = TikiHelperModule.class.getDeclaredMethod("eachSolverLabel", Function5.class);
		emitLabels.setAccessible(true);

		Object previousBase = slotBase.get(module);
		Object previousRotations = rotations.get(module);
		int previousHiddenIndex = hiddenIndex.getInt(module);
		Object previousPlan = plan.get(module);
		Object previousFuturePlans = futurePlans.get(module);
		try {
			slotBase.set(module, new BlockPos(0, 0, 0));

			int[] hiddenState = {-1, 4, 4};
			rotations.set(module, hiddenState);
			hiddenIndex.setInt(module, 0);
			plan.set(module, TikiSolver.solveWithHidden(hiddenState, 0));
			futurePlans.set(module, List.of());
			List<SolverLabel> hiddenLabels = emitSolverLabels(module, emitLabels);
			if (hiddenLabels.size() != 1) {
				throw new AssertionError("An aligned hidden slot must emit only its own unknown-count label: " + hiddenLabels);
			}
			assertLabel(hiddenLabels.get(0), 0, "+?", 0xFF55FF55, 0.35f, 0.0,
				"hidden-slot advice");

			int[] lockedState = {0, 0, 2};
			List<TikiSolver.Plan> lockedSequence = TikiSolver.solveSequence(lockedState, TikiSolver.Rule.OBSERVED);
			if (lockedSequence.size() != 1 || !same(lockedSequence.get(0), new TikiSolver.Plan(2, -1, 1, 1))) {
				throw new AssertionError("Expected the top head to solve the locked-pair state in one click: " + lockedSequence);
			}
			rotations.set(module, lockedState);
			hiddenIndex.setInt(module, -1);
			plan.set(module, lockedSequence.get(0));
			futurePlans.set(module, List.of());
			List<SolverLabel> lockedLabels = emitSolverLabels(module, emitLabels);
			if (lockedLabels.size() != 3) {
				throw new AssertionError("Both locked heads and the solver advice must be emitted: " + lockedLabels);
			}
			assertLabel(lockedLabels.get(0), 0, ".", 0xC8A0A0A0, 0.35f * 0.6f, 0.0,
				"bottom locked-head label");
			assertLabel(lockedLabels.get(1), 1, ".", 0xC8A0A0A0, 0.35f * 0.6f, 0.0,
				"middle locked-head label");
			assertLabel(lockedLabels.get(2), 2, "-1", 0xFFFF3C3C, 0.35f, 0.0,
				"solver advice label");

			int[] multiRunState = readableStateWithFuturePlans();
			List<TikiSolver.Plan> sequence = TikiSolver.solveSequence(multiRunState, TikiSolver.Rule.OBSERVED);
			TikiSolver.Plan first = sequence.get(0);
			List<TikiSolver.Plan> future = sequence.subList(1, sequence.size());
			rotations.set(module, multiRunState);
			plan.set(module, first);
			futurePlans.set(module, future);
			List<SolverLabel> sequenceLabels = emitSolverLabels(module, emitLabels);
			int lockedCount = 0;
			for (int index = 0; index < TikiStacks.STACK_HEIGHT; index++) {
				if (index != first.index() && TikiSolver.isLocked(multiRunState, index, TikiSolver.Rule.OBSERVED)) {
					lockedCount++;
					assertContainsLabel(sequenceLabels, index, ".", 0xC8A0A0A0, 0.35f * 0.6f, 0.0,
						"locked-head output with future plans");
				}
			}
			if (sequenceLabels.size() != 1 + lockedCount + future.size()) {
				throw new AssertionError("Rendered plan labels do not match current, locked, and future runs: " + sequenceLabels);
			}
			assertContainsLabel(sequenceLabels, first.index(), signedCount(first),
				first.direction() > 0 ? 0xFF55FF55 : 0xFFFF3C3C, 0.35f, 0.0,
				"current solver run");
			for (int i = 0; i < future.size(); i++) {
				TikiSolver.Plan next = future.get(i);
				int stack = first.index() == next.index() ? 1 : 0;
				if (next.index() != first.index() && TikiSolver.isLocked(multiRunState, next.index(), TikiSolver.Rule.OBSERVED)) {
					stack++;
				}
				for (int earlier = 0; earlier < i; earlier++) {
					if (future.get(earlier).index() == next.index()) stack++;
				}
				SolverLabel label = sequenceLabels.get(sequenceLabels.size() - future.size() + i);
				assertLabel(label, next.index(), signedCount(next), 0x82C8C8C8, 0.35f, -stack * 0.18,
					"future solver run " + i);
			}
		} finally {
			slotBase.set(module, previousBase);
			rotations.set(module, previousRotations);
			hiddenIndex.setInt(module, previousHiddenIndex);
			plan.set(module, previousPlan);
			futurePlans.set(module, previousFuturePlans);
		}
	}

	private static int[] readableStateWithFuturePlans() {
		for (int bottom = 0; bottom < TikiSolver.SLOTS; bottom += TikiSolver.STEP) {
			for (int middle = 0; middle < TikiSolver.SLOTS; middle += TikiSolver.STEP) {
				for (int top = 0; top < TikiSolver.SLOTS; top += TikiSolver.STEP) {
					int[] state = {bottom, middle, top};
					if (TikiSolver.solveSequence(state, TikiSolver.Rule.OBSERVED).size() > 1) return state;
				}
			}
		}
		throw new AssertionError("Expected at least one readable state with future solver runs");
	}

	private static List<SolverLabel> emitSolverLabels(TikiHelperModule module, Method method)
		throws ReflectiveOperationException {
		List<SolverLabel> labels = new ArrayList<>();
		Function5<Integer, String, Integer, Float, Double, Unit> sink =
			(index, text, color, height, verticalOffset) -> {
				labels.add(new SolverLabel(index, text, color, height, verticalOffset));
				return Unit.INSTANCE;
			};
		if (!Boolean.TRUE.equals(method.invoke(module, sink))) {
			throw new AssertionError("Expected Tiki Helper to emit solver labels");
		}
		return labels;
	}

	private static Field moduleField(String name) throws NoSuchFieldException {
		Field field = TikiHelperModule.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static String signedCount(TikiSolver.Plan plan) {
		return (plan.direction() > 0 ? "+" : "-") + plan.clicks();
	}

	private static void assertContainsLabel(List<SolverLabel> labels, int index, String text, int color,
		float height, double verticalOffset, String label) {
		for (SolverLabel actual : labels) {
			if (actual.index() == index && actual.text().equals(text) && actual.color() == color &&
				Math.abs(actual.height() - height) <= 0.0001f && Math.abs(actual.verticalOffset() - verticalOffset) <= 0.0001) {
				return;
			}
		}
		throw new AssertionError(label + " changed: " + labels);
	}

	private static void assertLabel(SolverLabel actual, int index, String text, int color,
		float height, double verticalOffset, String label) {
		if (actual.index() != index || !actual.text().equals(text) || actual.color() != color ||
			Math.abs(actual.height() - height) > 0.0001f || Math.abs(actual.verticalOffset() - verticalOffset) > 0.0001) {
			throw new AssertionError(label + " changed: " + actual);
		}
	}

	private record SolverLabel(int index, String text, int color, float height, double verticalOffset) {
	}

	private static boolean same(TikiSolver.Plan left, TikiSolver.Plan right) {
		return left.index() == right.index() && left.direction() == right.direction()
			&& left.clicks() == right.clicks() && left.remaining() == right.remaining();
	}
}
