package geiler.addons.client.bazaar;

import java.util.List;

/** Offline checks for the Bazaar amount sign presets. */
public final class BazaarPresetChecks {
	private BazaarPresetChecks() {
	}

	public static void run() {
		check(BazaarPresetRules.isAmountSign(new String[] {"", "^^^^^^^^^^^^^^^", "Enter amount", "to order"}), "buy amount sign");
		check(!BazaarPresetRules.isAmountSign(new String[] {"", "^^^^^^^^^^^^^^^", "Enter price", "per unit"}), "a price sign");
		check(!BazaarPresetRules.isAmountSign(new String[] {"hello", "world", "Enter amount", "to order"}), "no input marker");
		check(!BazaarPresetRules.isAmountSign(new String[] {""}), "too few lines");

		check(BazaarPresetRules.isAmountMenu("How many do you want?"), "buy order menu");
		check(BazaarPresetRules.isAmountMenu("Enchanted Diamond \u279c Instant Buy"), "instant buy menu");
		check(!BazaarPresetRules.isAmountMenu("How much do you want to pay?"), "price menu");

		eq("64,160,max", keys(BazaarPresetRules.parsePresets("64, 160, Max")), "defaults");
		eq("64,max", keys(BazaarPresetRules.parsePresets("64,abc,-3,0, MAX ,64")), "junk and duplicates are dropped");
		eq("1,2,3,4,5", keys(BazaarPresetRules.parsePresets("1,2,3,4,5,6,7")), "at most five");

		eq(71680, BazaarPresetRules.limitFromLore(List.of("Buy Order Quantity", "", "Buy up to 71,680x.", "Click to specify!")), "limit from lore");
		eq(256, BazaarPresetRules.limitFromLore(List.of("Buy up to 256x.")), "unstackable limit");
		eq(0, BazaarPresetRules.limitFromLore(List.of("Buy only one!")), "no limit line");
		eq(256, BazaarPresetRules.limitFromStackSize(1), "unstackable fallback");
		eq(71680, BazaarPresetRules.limitFromStackSize(64), "stackable fallback");
		eq(71680, BazaarPresetRules.limitFromStackSize(16), "16-stacks use the normal limit");

		check(Integer.valueOf(160).equals(BazaarPresetRules.amountOf(160, 0)), "a number needs no limit");
		check(BazaarPresetRules.amountOf(null, 0) == null, "Max is hidden without a limit");
		check(Integer.valueOf(256).equals(BazaarPresetRules.amountOf(null, 256)), "Max uses the limit");
		check(BazaarPresetRules.amountOf(300, 256) == null, "a number above the limit is hidden");
		System.out.println("Bazaar preset checks passed");
	}

	private static String keys(List<BazaarPresetRules.Preset> presets) {
		return presets.stream().map(preset -> preset.getAmount() == null ? "max" : preset.getAmount().toString())
			.collect(java.util.stream.Collectors.joining(","));
	}

	private static void eq(Object expected, Object actual, String what) {
		if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
	}

	private static void check(boolean condition, String what) {
		if (!condition) throw new AssertionError(what);
	}
}
