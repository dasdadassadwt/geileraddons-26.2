package geiler.addons.client.macros.sim;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Covers the Sim features: effect levels, the Speed Sim formula from its specification, and the singleplayer-only rules. */
public final class SimChecks {
	private SimChecks() { }

	public static void run() {
		checkJumpBoost();
		checkOneTap();
		checkSpeedFormula();
		checkSpeedAttribute();
		checkGuard();
		checkActionTable();
		checkModules();
	}

	private static void checkJumpBoost() {
		check(SimMapping.jumpAmplifier(1) == 0, "Jump Boost level 1 is amplifier 0");
		check(SimMapping.jumpAmplifier(3) == 2, "Jump Boost level 3 is amplifier 2, not 3");
		check(SimMapping.jumpAmplifier(255) == 254, "Jump Boost level 255 is amplifier 254");
		check(SimMapping.jumpAmplifier(0) == 0 && SimMapping.jumpAmplifier(-9) == 0 && SimMapping.jumpAmplifier(999) == 254,
			"a level outside 1-255 is clamped before it becomes an amplifier");
		for (int level : new int[] {1, 2, 3, 10, 128, 255}) {
			MobEffectInstance effect = SimEffects.jumpEffect(level);
			check(effect.is(MobEffects.JUMP_BOOST), "the Jump Boost feature uses the real Jump Boost effect");
			check(effect.getAmplifier() + 1 == level, "the displayed effect level equals the selected level " + level);
			check(effect.isInfiniteDuration() && effect.getDuration() == MobEffectInstance.INFINITE_DURATION,
				"the Jump Boost effect never runs out");
		}
	}

	private static void checkOneTap() {
		check(SimMapping.ONE_TAP_AMPLIFIER == 254, "1 Tap uses amplifier 254");
		MobEffectInstance effect = SimEffects.oneTapEffect();
		check(effect.is(MobEffects.STRENGTH), "1 Tap uses the real Strength effect");
		check(effect.getAmplifier() == 254 && effect.getAmplifier() + 1 == 255,
			"1 Tap is Strength level 255, not 256");
		check(effect.isInfiniteDuration() && effect.getDuration() == MobEffectInstance.INFINITE_DURATION,
			"the 1 Tap effect never runs out");
	}

	/** The specification's table, in blocks per second on flat ground. */
	private static void checkSpeedFormula() {
		double[][] table = {
			{100, 4.317, 5.612, 1.295}, {150, 6.476, 8.418, 1.943}, {200, 8.634, 11.224, 2.590},
			{250, 10.793, 14.030, 3.238}, {300, 12.951, 16.836, 3.885}, {350, 15.110, 19.642, 4.533},
			{400, 17.268, 22.448, 5.180},
		};
		for (double[] row : table) {
			check(near(SimMapping.groundSpeed(SimMapping.Gait.WALK, row[0]), row[1], 0.0006),
				"walking at Speed " + row[0] + " is " + row[1] + " blocks/s");
			check(near(SimMapping.groundSpeed(SimMapping.Gait.SPRINT, row[0]), row[2], 0.0006),
				"sprinting at Speed " + row[0] + " is " + row[2] + " blocks/s");
			check(near(SimMapping.groundSpeed(SimMapping.Gait.SNEAK, row[0]), row[3], 0.0006),
				"sneaking at Speed " + row[0] + " is " + row[3] + " blocks/s");
		}
		check(SimMapping.speedIncreasePercent(100) == 0.0 && SimMapping.speedIncreasePercent(150) == 50.0
			&& SimMapping.speedIncreasePercent(400) == 300.0, "the increase over 100 Speed is (S - 100) percent");
		check(near(SimMapping.groundSpeed(SimMapping.Gait.SPRINT, 100) / SimMapping.groundSpeed(SimMapping.Gait.WALK, 100), 1.3, 0.0005),
			"sprinting is the separate 1.3x of walking at every Speed");
		check(SimMapping.speedModifierValue(100) == 0.0 && SimMapping.speedModifierValue(400) == 3.0,
			"the modifier adds (S/100 - 1) to the total multiplier");
	}

	/** The real attribute maths: a vanilla sprint boost and the Speed Sim modifier multiply, so S scales every gait. */
	private static void checkSpeedAttribute() {
		for (double stat : new double[] {1, 100, 150, 200, 400, 1000, 100_000, 700_000}) {
			AttributeInstance walk = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> { });
			walk.setBaseValue(0.1);
			walk.addOrUpdateTransientModifier(SimEffects.speedModifier(stat));
			AttributeInstance sprint = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> { });
			sprint.setBaseValue(0.1);
			sprint.addOrUpdateTransientModifier(new AttributeModifier(
				net.minecraft.resources.Identifier.withDefaultNamespace("sprinting"), 0.3,
				AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			sprint.addOrUpdateTransientModifier(SimEffects.speedModifier(stat));
			check(near(walk.getValue(), 0.1 * stat / 100.0, 1.0e-9 * Math.max(1.0, stat)),
				"the attribute value at Speed " + stat + " is the vanilla 0.1 times S/100");
			check(near(sprint.getValue() / walk.getValue(), 1.3, 1.0e-9),
				"vanilla's sprint boost stays a separate 1.3x on top of Speed " + stat);
		}
		AttributeInstance top = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> { });
		top.setBaseValue(0.1);
		top.addOrUpdateTransientModifier(SimEffects.speedModifier(SimMapping.MAX_SPEED_STAT));
		check(near(top.getValue(), 1000.0, 1.0e-6), "the largest Speed is not cut down by the attribute's own ceiling when walking");
		// Vanilla clamps the movement-speed attribute at 1024; only sprinting at a Speed above about 787 000 reaches it.
		AttributeInstance topSprint = new AttributeInstance(Attributes.MOVEMENT_SPEED, ignored -> { });
		topSprint.setBaseValue(0.1);
		topSprint.addOrUpdateTransientModifier(new AttributeModifier(
			net.minecraft.resources.Identifier.withDefaultNamespace("sprinting"), 0.3,
			AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		topSprint.addOrUpdateTransientModifier(SimEffects.speedModifier(SimMapping.MAX_SPEED_STAT));
		check(near(topSprint.getValue(), 1024.0, 1.0e-6),
			"sprinting at the very largest Speed is held at Minecraft's own attribute ceiling of 1024");
		check(SimEffects.speedModifier(250).operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
			&& SimEffects.speedModifier(250).id().equals(SimEffects.SPEED_MODIFIER_ID),
			"the Speed modifier is one fixed, multiplicative-total modifier");
	}

	private static void checkGuard() {
		check(SimGuard.allows(true, false), "a plain singleplayer world is allowed");
		check(!SimGuard.allows(true, true), "a singleplayer world opened to LAN is not singleplayer any more");
		check(!SimGuard.allows(false, false) && !SimGuard.allows(false, true),
			"a multiplayer connection is never allowed");
	}

	private static void checkActionTable() {
		// action(allowed, enabled, present, matches, appliedByUs)
		check(SimAction.of(true, true, false, false, false) == SimAction.APPLY, "enabled and missing: apply");
		check(SimAction.of(true, true, true, false, true) == SimAction.APPLY, "enabled but changed (level edited, milk): apply again");
		check(SimAction.of(true, true, true, true, true) == SimAction.NONE, "enabled and already right: leave it alone");
		check(SimAction.of(true, false, true, true, true) == SimAction.REMOVE, "disabled after applying: remove");
		check(SimAction.of(false, true, true, true, true) == SimAction.REMOVE, "no longer singleplayer after applying: remove");
		check(SimAction.of(false, true, false, false, false) == SimAction.NONE,
			"not allowed and never applied: never touch anything, and never apply");
		check(SimAction.of(true, false, true, true, false) == SimAction.NONE,
			"an effect the player had before the feature was enabled is never removed");
		check(SimAction.of(false, false, true, true, false) == SimAction.NONE, "idle stays idle");
	}

	private static void checkModules() {
		Module speed = module("Speed Sim");
		Module jump = module("Jump Boost");
		Module tap = module("1 Tap");
		check(speed.category() == Category.SIM && jump.category() == Category.SIM && tap.category() == Category.SIM,
			"the three Sim features are registered in the Sim category");
		check(!speed.defaultEnabled() && !jump.defaultEnabled() && !tap.defaultEnabled(), "every Sim feature starts off");
		NumberSetting speedSetting = speed.numberSettings().get(0);
		check(speed.numberSettings().size() == 1 && speedSetting.value() == 400.0f && speedSetting.min() == 1.0f
			&& speedSetting.max() >= 1_000_000.0f && speedSetting.prefersTextInput(),
			"Speed Sim has one Speed setting, default 400, with no practical cap");
		NumberSetting level = jump.numberSettings().get(0);
		check(jump.numberSettings().size() == 1 && level.value() == 3.0f && level.min() == 1.0f && level.max() == 255.0f
			&& level.isInteger(), "Jump Boost has one whole-number Level setting, default 3, from 1 to 255");
		check(tap.numberSettings().isEmpty() && tap.booleanSettings().isEmpty() && tap.choiceSettings().isEmpty()
			&& tap.textSettings().isEmpty(), "1 Tap has no settings");
	}

	private static Module module(String name) {
		for (Module module : ModuleManager.modules(Category.SIM)) {
			if (module.name().equals(name)) return module;
		}
		throw new AssertionError("Sim module is not registered: " + name);
	}

	private static boolean near(double actual, double expected, double tolerance) {
		return Math.abs(actual - expected) <= tolerance;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
