package geiler.addons.client.module.impl;

import net.minecraft.network.chat.Component;

/** Focused behavior checks for the boss bar percent text and its gate. */
public final class BossBarPercentChecks {
	private BossBarPercentChecks() { }

	public static void main(String[] args) {
		check("25.0%".equals(BossBarPercentModule.format(0.25f)), "0.25 formats as 25.0%");
		check("100.0%".equals(BossBarPercentModule.format(1f)) && "0.0%".equals(BossBarPercentModule.format(0f)),
			"full and empty bars format as 100.0% and 0.0%");
		check("100.0%".equals(BossBarPercentModule.format(1.5f)) && "0.0%".equals(BossBarPercentModule.format(-1f)),
			"out of range progress is clamped");
		check("33.3%".equals(BossBarPercentModule.format(1f / 3f)), "one decimal place, dot separator");

		check(BossBarPercentModule.shouldApply(true, true, true)
			&& !BossBarPercentModule.shouldApply(false, true, true)
			&& !BossBarPercentModule.shouldApply(true, false, true)
			&& !BossBarPercentModule.shouldApply(true, true, false),
			"the percent shows only while enabled, in a dungeon, and past the clear phase");

		Component original = Component.literal("Maxor");
		Component applied = BossBarPercentModule.withPercent(original, "25.0%");
		check("Maxor - 25.0%".equals(applied.getString()) && "Maxor".equals(original.getString()),
			"the percent is appended to a copy and the original name stays untouched");

		check(!BossBarPercentModule.INSTANCE.isEnabled(), "Boss Bar Percent is off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
