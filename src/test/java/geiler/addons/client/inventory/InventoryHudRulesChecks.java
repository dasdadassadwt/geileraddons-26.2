package geiler.addons.client.inventory;

/** Offline checks for the Inventory HUD's grid size and slot mapping. */
public final class InventoryHudRulesChecks {
	private InventoryHudRulesChecks() { }

	public static void run() {
		check(InventoryHudRules.rows(false) == 3 && InventoryHudRules.rows(true) == 4, "the HUD shows three rows, four with the hotbar");
		check(InventoryHudRules.width() == 9 * InventoryHudRules.SLOT + 2 * InventoryHudRules.PAD, "nine slots plus padding make the width");
		check(InventoryHudRules.height(true) == InventoryHudRules.height(false) + InventoryHudRules.SLOT + InventoryHudRules.HOTBAR_GAP,
			"the hotbar adds one row and a gap");
		check(InventoryHudRules.slotIndex(0, 0) == 9 && InventoryHudRules.slotIndex(2, 8) == 35 && InventoryHudRules.slotIndex(3, 0) == 0
			&& InventoryHudRules.slotIndex(3, 8) == 8, "rows map to the main inventory slots 9-35 and the hotbar to 0-8");
		check(InventoryHudRules.cellX(0) == InventoryHudRules.PAD + 1 && InventoryHudRules.cellX(8) - InventoryHudRules.cellX(7) == InventoryHudRules.SLOT,
			"cells are one slot apart");
		check(InventoryHudRules.cellY(3) - InventoryHudRules.cellY(2) == InventoryHudRules.SLOT + InventoryHudRules.HOTBAR_GAP
			&& InventoryHudRules.cellY(1) - InventoryHudRules.cellY(0) == InventoryHudRules.SLOT, "the hotbar row sits below a gap");
		check(InventoryHudRules.opacity(100f) == 1f && InventoryHudRules.opacity(0f) == 0f && InventoryHudRules.opacity(250f) == 1f
			&& InventoryHudRules.opacity(-5f) == 0f && InventoryHudRules.opacity(50f) == 0.5f, "opacity is a percentage clamped to 0-1");
		check(InventoryHudRules.showsHotbar(false, true) && InventoryHudRules.showsHotbar(true, false)
			&& !InventoryHudRules.showsHotbar(false, false), "Disable Hotbar forces the hotbar row on");

		// 480x270 screen: vanilla bars are centred at x=240 with the XP bar bottom at y=246.
		int[] offset = InventoryHudRules.statusBarOffset(480, 270, 100, 200, 174);
		check(offset[0] == 100 + 87 - 240 && offset[1] == 200 - InventoryHudRules.BAR_GAP - (270 - InventoryHudRules.XP_BOTTOM),
			"bars centre on the panel and the XP bar sits just above its top");
		offset = InventoryHudRules.statusBarOffset(480, 270, 0, 0, 174);
		check(offset[1] == 60 - 270, "a panel at the very top keeps the bar stack on screen");
		check(offset[0] == -(240 - 91), "a panel at the far left keeps the 182-wide bars on screen");
		offset = InventoryHudRules.statusBarOffset(480, 270, 480, 300, 174);
		check(offset[0] == 240 - 91 && offset[1] == InventoryHudRules.XP_BOTTOM, "a panel off the bottom right is clamped on screen");
		offset = InventoryHudRules.statusBarOffset(480, 270, 240 - 87, 246 + 2, 174);
		check(offset[0] == 0 && offset[1] == 0, "a panel sitting where the vanilla hotbar is leaves the bars where they are");
		check(InventoryHudRules.statusBarOffset(100, 20, 0, 0, 50).length == 2, "tiny screens still return an offset");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
