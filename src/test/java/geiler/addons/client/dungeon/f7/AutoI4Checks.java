package geiler.addons.client.dungeon.f7;

import java.util.List;
import java.util.Random;

/** Offline checks for Auto i4: bow cooldown parsing and shot timing. */
public final class AutoI4Checks {
	private AutoI4Checks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		require(AutoI4Rules.cooldownMs(List.of("Damage: +310", "Shot Cooldown: 0.5s")) == 500, "a 0.5 s cooldown is 500 ms");
		require(AutoI4Rules.cooldownMs(List.of("Shot Cooldown: 1s")) == 1000, "a whole second parses");
		require(AutoI4Rules.cooldownMs(List.of("nothing here")) == AutoI4Rules.DEFAULT_COOLDOWN_MS, "no line uses the default");
		require(AutoI4Rules.cooldownMs(List.of("Shot Cooldown: 0.01s")) == 50, "a tiny cooldown is clamped");

		require(AutoI4Rules.prefireKey(0, 0) >= 9, "a prefire key never equals a lit panel index");
		require(AutoI4Rules.prefireKey(4, 0) != AutoI4Rules.prefireKey(4, 1), "the two spots of a middle panel differ");
		require(AutoI4Rules.prefireKey(4, 1) != AutoI4Rules.prefireKey(5, 0), "spots of neighbouring panels differ");

		AutoI4Rules.Shooter shooter = new AutoI4Rules.Shooter(new Random(3));
		require(!shooter.update(-1, true, 0, 100, 100, 300), "no target never shoots");
		require(!shooter.update(4, true, 1_000, 100, 100, 300), "a new target waits its reaction delay");
		require(!shooter.update(4, false, 1_200, 100, 100, 300), "not aimed never shoots");
		require(shooter.update(4, true, 1_200, 100, 100, 300), "aimed after the delay shoots");
		require(!shooter.update(4, true, 1_350, 100, 100, 300), "the bow cooldown holds the next shot");
		require(shooter.update(4, true, 1_500, 100, 100, 300), "a miss is shot again after the cooldown");
		require(!shooter.update(5, true, 1_550, 100, 100, 300), "a new target draws a new delay");
		require(shooter.update(5, true, 1_800, 100, 100, 300), "then it is shot");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
