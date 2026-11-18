package geiler.addons.client.macro;

import com.google.gson.JsonParser;
import geiler.addons.client.config.MacroStepConfigCodec;

import java.util.List;

/** Offline checks for the Player condition (view angle, coordinates, blocks) and the Set View step. */
public final class MacroViewChecks {
	private static final MacroCondition.Hypixel.Operator EQ = MacroCondition.Hypixel.Operator.EQUALS;
	private static final MacroCondition.Hypixel.Operator NE = MacroCondition.Hypixel.Operator.NOT_EQUALS;
	private static final MacroCondition.Hypixel.Operator GT = MacroCondition.Hypixel.Operator.GREATER_THAN;
	private static final MacroCondition.Hypixel.Operator LT = MacroCondition.Hypixel.Operator.LESS_THAN;

	private MacroViewChecks() {
	}

	public static void run() {
		checkAngleRules();
		checkNumberAndTextRules();
		checkBlockPositionParsing();
		checkPlayerConditionModel();
		checkSetViewStep();
		checkCodecRoundTrip();
	}

	private static void checkAngleRules() {
		assertTrue(PlayerConditionRules.angleMatches(179.0, -179.0, 5.0, EQ, true),
			"yaw equality wraps around +-180 degrees");
		assertFalse(PlayerConditionRules.angleMatches(10.0, 20.0, 5.0, EQ, true), "yaw outside tolerance is not equal");
		assertTrue(PlayerConditionRules.angleMatches(10.0, 20.0, 5.0, NE, true), "not-equal is the inverse of equal");
		assertTrue(PlayerConditionRules.angleMatches(-90.0, 0.0, 0.0, LT, true), "less-than compares wrapped yaw");
		assertTrue(PlayerConditionRules.angleMatches(-179.0, 179.0, 0.0, GT, true),
			"greater-than uses the shortest turn: -179 is 2 degrees past 179");
		assertTrue(PlayerConditionRules.angleMatches(45.0, 40.0, 5.0, EQ, false), "pitch equality uses plain difference");
		assertFalse(PlayerConditionRules.angleMatches(-80.0, 80.0, 5.0, EQ, false), "pitch never wraps");
		assertEquals(5.0, PlayerConditionRules.toleranceOf(""), "blank tolerance falls back to the default");
		assertEquals(5.0, PlayerConditionRules.toleranceOf("abc"), "invalid tolerance falls back to the default");
		assertEquals(180.0, PlayerConditionRules.toleranceOf("999"), "tolerance is clamped to 180");
		assertEquals(0.0, PlayerConditionRules.toleranceOf("-3"), "tolerance is never negative");
		assertEquals(2.5, PlayerConditionRules.toleranceOf(" 2.5 "), "tolerance parses a decimal");
	}

	private static void checkNumberAndTextRules() {
		assertTrue(PlayerConditionRules.numberMatches(64.0, 64.0, EQ), "block coordinates compare exactly");
		assertTrue(PlayerConditionRules.numberMatches(70.0, 64.0, GT), "greater-than on coordinates");
		assertFalse(PlayerConditionRules.numberMatches(64.0, 64.0, MacroCondition.Hypixel.Operator.CONTAINS),
			"contains never matches a number");
		assertTrue(PlayerConditionRules.textMatches("minecraft:stone", "stone", EQ),
			"the minecraft namespace is optional on either side");
		assertTrue(PlayerConditionRules.textMatches("minecraft:Stone_Bricks", "STONE", MacroCondition.Hypixel.Operator.CONTAINS),
			"block names match ignoring case");
		assertFalse(PlayerConditionRules.textMatches("minecraft:stone", "stone_bricks", EQ), "different blocks differ");
		assertTrue(PlayerConditionRules.textMatches("minecraft:stone", "dirt", NE), "not-equal on text");
		assertFalse(PlayerConditionRules.textMatches("minecraft:stone", "stone", GT), "ordering is not defined for text");
	}

	private static void checkBlockPositionParsing() {
		assertEquals(List.of(1, -2, 3), asList(PlayerConditionRules.parseBlockPos("1 -2 3")), "space separated");
		assertEquals(List.of(1, -2, 3), asList(PlayerConditionRules.parseBlockPos("1,-2,3")), "comma separated");
		assertEquals(List.of(1, -2, 3), asList(PlayerConditionRules.parseBlockPos(" 1, -2 ,3 ")), "mixed whitespace");
		assertTrue(PlayerConditionRules.parseBlockPos("1 2") == null, "two numbers are not a position");
		assertTrue(PlayerConditionRules.parseBlockPos("a b c") == null, "text is not a position");
		assertTrue(PlayerConditionRules.parseBlockPos(null) == null, "null is not a position");
		assertTrue(PlayerConditionRules.parseBlockPos("1 2 3 4") == null, "four numbers are not a position");
	}

	private static void checkPlayerConditionModel() {
		MacroCondition.Player defaults = new MacroCondition.Player(null, null, null, null);
		assertEquals(MacroCondition.Player.Field.YAW, defaults.field(), "a missing field defaults to yaw");
		assertEquals(EQ, defaults.operator(), "a missing operator defaults to equals");
		assertEquals("", defaults.expected(), "a missing expected value is empty");
		MacroCondition.Player longValues = new MacroCondition.Player(MacroCondition.Player.Field.BLOCK_AT, EQ,
			"x".repeat(500), "y".repeat(500));
		assertTrue(longValues.expected().length() <= 128 && longValues.argument().length() <= 64,
			"text fields are bounded");
	}

	private static void checkSetViewStep() {
		MacroStep.SetView view = new MacroStep.SetView(540.0f, 200.0f, 5);
		assertEquals(-180.0f, view.yaw(), "yaw wraps into Minecraft's -180..180 range (540 is -180)");
		assertEquals(90.0f, view.pitch(), "pitch is clamped to 90");
		assertEquals(50, view.maxMillis(), "the time limit has a floor");
		assertEquals(-90.0f, new MacroStep.SetView(-90.0f, -200.0f, 999_999).pitch(), "pitch is clamped to -90");
		assertEquals(10_000, new MacroStep.SetView(0.0f, 0.0f, 999_999).maxMillis(), "the time limit has a ceiling");
		assertEquals(0.0f, new MacroStep.SetView(Float.NaN, Float.NaN, 500).yaw(), "NaN yaw becomes 0");
		assertEquals("set_view", view.type(), "stable step type");
	}

	private static void checkCodecRoundTrip() {
		MacroStep.IfElse branch = new MacroStep.IfElse(new MacroCondition.Player(
			MacroCondition.Player.Field.BLOCK_AT, EQ, "minecraft:stone", "10 64 -5"));
		MacroStep.IfElse yawBranch = new MacroStep.IfElse(new MacroCondition.Player(
			MacroCondition.Player.Field.YAW, EQ, "90", "3"));
		MacroStep.SetView view = new MacroStep.SetView(-45.5f, 12.25f, 800);
		List<MacroStep> steps = List.of(branch, yawBranch, view);
		List<MacroStep> back = MacroStepConfigCodec.decode(
			JsonParser.parseString(MacroStepConfigCodec.encode(steps).toString()).getAsJsonArray());
		assertEquals(3, back.size(), "all three steps survive the codec");
		MacroCondition.Player first = (MacroCondition.Player) ((MacroStep.IfElse) back.get(0)).condition();
		assertEquals(MacroCondition.Player.Field.BLOCK_AT, first.field(), "field round-trips");
		assertEquals("minecraft:stone", first.expected(), "expected round-trips");
		assertEquals("10 64 -5", first.argument(), "argument round-trips");
		MacroCondition.Player second = (MacroCondition.Player) ((MacroStep.IfElse) back.get(1)).condition();
		assertEquals("3", second.argument(), "tolerance argument round-trips");
		MacroStep.SetView restored = (MacroStep.SetView) back.get(2);
		assertEquals(-45.5f, restored.yaw(), "set view yaw round-trips");
		assertEquals(12.25f, restored.pitch(), "set view pitch round-trips");
		assertEquals(800, restored.maxMillis(), "set view time round-trips");
	}

	private static List<Integer> asList(int[] values) {
		if (values == null) return null;
		return List.of(values[0], values[1], values[2]);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
