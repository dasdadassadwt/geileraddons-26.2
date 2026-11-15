package geiler.addons.client.module.impl;

import geiler.addons.client.module.impl.HideF7BossSoundsModule.Action;

/** Focused behavior checks for the F7 boss sound filter rule. */
public final class HideF7BossSoundsChecks {
	private HideF7BossSoundsChecks() { }

	private static final String HURT = "minecraft:entity.wither.hurt";
	private static final String DEATH = "minecraft:entity.wither.death";

	public static void main(String[] args) {
		check(HideF7BossSoundsModule.decide("minecraft:entity.generic.explode", 2f, 1f, false, false) == Action.CANCEL
			&& HideF7BossSoundsModule.decide("minecraft:entity.generic.explode", 8f, 1f, false, false) == Action.CANCEL,
			"sounds at volume 2 or louder are cancelled");
		check(HideF7BossSoundsModule.decide("minecraft:entity.arrow.hit", 1.4f, 1f, false, false) == Action.PASS,
			"a 1.4 volume sound passes with both extra switches off");
		check(HideF7BossSoundsModule.decide("minecraft:entity.arrow.hit", 1.4f, 1f, true, false) == Action.CANCEL,
			"Cancel Above Cap cancels anything louder than 1");
		check(HideF7BossSoundsModule.decide("minecraft:entity.arrow.hit", 1.4f, 1f, true, true) == Action.CAP
			&& HideF7BossSoundsModule.decide("minecraft:entity.generic.explode", 8f, 1f, false, true) == Action.CAP,
			"Cap Volume replays loud sounds at volume 1 instead of cancelling");
		check(HideF7BossSoundsModule.decide("minecraft:entity.arrow.hit", 1f, 1f, true, true) == Action.PASS,
			"a sound at exactly volume 1 is never capped or cancelled");
		for (float pitch : new float[] {1.0f, 0.93650794f, 1.0158731f, 1.1111112f}) {
			check(HideF7BossSoundsModule.decide(HURT, 0.5f, pitch, false, false) == Action.CANCEL,
				"wither hurt at volume 0.5 and pitch " + pitch + " is cancelled");
		}
		check(HideF7BossSoundsModule.decide(HURT, 0.5f, 0.7f, false, false) == Action.PASS
			&& HideF7BossSoundsModule.decide(HURT, 1.0f, 1.0f, false, false) == Action.PASS,
			"wither hurt with another pitch or volume passes");
		check(HideF7BossSoundsModule.decide(DEATH, 0.5f, 0.9f, false, false) == Action.CANCEL
			&& HideF7BossSoundsModule.decide(DEATH, 0.5f, 1.1587301f, false, false) == Action.CANCEL
			&& HideF7BossSoundsModule.decide(DEATH, 0.5f, 0.8f, false, false) == Action.PASS
			&& HideF7BossSoundsModule.decide(DEATH, 0.5f, 1.2f, false, false) == Action.PASS,
			"wither death is cancelled only inside its pitch band");
		check(HideF7BossSoundsModule.decide("minecraft:block.anvil.land", 0.5f, 1f, true, true) == Action.PASS,
			"ordinary quiet sounds pass");

		HideF7BossSoundsModule module = HideF7BossSoundsModule.INSTANCE;
		check(module.booleanSettings().size() == 2 && !module.booleanSettings().get(0).value()
			&& !module.booleanSettings().get(1).value(), "both extra switches default off");
		check(!module.isEnabled(), "Hide F7 Boss Sounds is off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
