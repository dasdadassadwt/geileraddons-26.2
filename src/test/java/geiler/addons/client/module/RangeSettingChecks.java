package geiler.addons.client.module;

import java.util.List;
import java.util.Random;

/** Covers the two-thumb range setting: ordering, clamping, persistence keys, drawing and the module's number list. */
public final class RangeSettingChecks {
	private RangeSettingChecks() { }

	private static final class Probe extends Module {
		static final RangeSetting CPS = new RangeSetting("Left CPS", "Left CPS min", "Left CPS max", 1.0f, 20.0f, 8.0f, 12.0f);
		static final NumberSetting PLAIN = new NumberSetting("Plain", 0.0f, 10.0f, 5.0f);
		final RangeSetting cps = CPS;
		final NumberSetting plain = PLAIN;

		Probe() {
			super("RangeProbe", "test module", Category.SIM, false, CPS, PLAIN);
		}
	}

	public static void run() {
		checkOrdering();
		checkClamping();
		checkKeys();
		checkRandom();
		checkReset();
		checkFractions();
		checkModuleLists();
	}

	private static RangeSetting range() {
		return new RangeSetting("Speed", "Speed min", "Speed max", 0.0f, 10.0f, 2.0f, 6.0f);
	}

	private static void checkOrdering() {
		RangeSetting r = range();
		check(r.low() == 2.0f && r.high() == 6.0f, "defaults are taken as given");
		r.setLow(9.0f);
		check(r.low() == 6.0f && r.high() == 6.0f, "the low end cannot pass the high end");
		r.setHigh(1.0f);
		check(r.low() == 6.0f && r.high() == 6.0f, "the high end cannot go under the low end");
		r.setHigh(8.0f);
		r.setLow(3.0f);
		check(r.low() == 3.0f && r.high() == 8.0f, "both ends move independently inside the order");
		// A file that loads the parts out of order: the high end, the old single key, is the one that was last set, so it wins.
		r.getLowPart().setValue(9.0f);
		r.getHighPart().setValue(4.0f);
		check(r.low() == 4.0f && r.high() == 4.0f, "parts loaded out of order are read as the high end alone");
	}

	private static void checkClamping() {
		RangeSetting r = range();
		r.setLow(-5.0f);
		r.setHigh(500.0f);
		check(r.low() == 0.0f && r.high() == 10.0f, "values are clamped into the bounds");
		r.setLow(Float.NaN);
		r.setHigh(Float.POSITIVE_INFINITY);
		check(r.low() == 0.0f && r.high() == 10.0f, "non-finite values are ignored");
		RangeSetting whole = new RangeSetting("Delay", "Delay min", "Delay max", 0.0f, 1000.0f, 100.0f, 200.0f, true);
		whole.setLow(10.4f);
		check(whole.low() == 10.0f && "10 – 200".equals(whole.display()), "integer ranges round and print without decimals");
	}

	private static void checkKeys() {
		RangeSetting r = range();
		check("Speed".equals(r.name()) && "Speed".equals(r.displayName()), "the row is named by its label");
		check("Speed min".equals(r.getLowPart().name()) && "Speed max".equals(r.getHighPart().name()),
			"the parts keep the saved keys");
		check("2.00 – 6.00".equals(r.display()), "the read-out shows both ends");
	}

	private static void checkRandom() {
		RangeSetting r = range();
		Random rng = new Random(7);
		double min = 99.0;
		double max = -99.0;
		for (int i = 0; i < 2000; i++) {
			double v = r.random(rng);
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		check(min >= 2.0 && max <= 6.0, "draws stay inside the range");
		check(min < 2.3 && max > 5.7, "draws cover the range");
		r.setLow(6.0f);
		check(r.random(rng) == 6.0, "a single-point range always draws that point");
	}

	private static void checkReset() {
		RangeSetting r = range();
		r.setLow(4.0f);
		r.setHigh(5.0f);
		r.reset();
		check(r.low() == 2.0f && r.high() == 6.0f, "reset restores both defaults");
		RangeSetting swapped = new RangeSetting("X", "X min", "X max", 0.0f, 10.0f, 9.0f, 3.0f);
		check(swapped.low() == 3.0f && swapped.high() == 9.0f, "swapped defaults are put in order");
		// A saved low end above the high end is a leftover of the old single setting: the high end wins, the range is the point.
		RangeSetting stale = new RangeSetting("S", "S min", "S max", 0.0f, 100.0f, 15.0f, 25.0f);
		stale.getLowPart().setValue(15.0f);
		stale.getHighPart().setValue(0.0f);
		check(stale.low() == 0.0f && stale.high() == 0.0f, "a saved low end above the high end gives the high end as a point (" + stale.low() + ".." + stale.high() + ")");
	}

	private static void checkFractions() {
		RangeSetting r = range();
		check(Math.abs(r.lowFraction() - 0.2f) < 1e-6 && Math.abs(r.highFraction() - 0.6f) < 1e-6, "thumbs sit at their fractions");
		r.setHighFraction(0.9f);
		check(Math.abs(r.high() - 9.0f) < 1e-5, "a fraction moves the high end");
		r.setLowFraction(1.0f);
		check(r.low() == r.high(), "the low thumb stops at the high thumb");
		r.setLowFraction(-3.0f);
		check(r.low() == 0.0f, "a fraction below 0 lands on the minimum");
	}

	private static void checkModuleLists() {
		Probe probe = new Probe();
		List<NumberSetting> numbers = probe.numberSettings();
		check(numbers.contains(probe.plain) && numbers.contains(probe.cps.getLowPart()) && numbers.contains(probe.cps.getHighPart()),
			"the module saves a range through its two parts");
		check(probe.groups().get(0).settings().contains(probe.cps), "the range is one row in the panel");
		check(!probe.groups().get(0).settings().contains(probe.cps.getLowPart()), "the parts are not rows of their own");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
