package geiler.addons.client.misc;

import java.util.ArrayList;
import java.util.List;

/** Offline checks for the Inventory Capture text format and its bounds. */
public final class InventoryCaptureChecks {
	private InventoryCaptureChecks() { }

	public static void run() {
		check("slot=3 container=player item=minecraft:diamond count=2 name=Shiny".equals(
				InventoryCaptureRules.slotLine(3, true, "minecraft:diamond", 2, "Shiny", List.of())),
			"a slot line names the slot, container, item, count and name");
		check("slot=12 container=menu item=minecraft:stone count=1 name=Rock | lore: A / B".equals(
				InventoryCaptureRules.slotLine(12, false, "minecraft:stone", 1, "Rock", List.of("A", "B"))),
			"lore lines are joined after the slot");
		List<String> longLore = new ArrayList<>();
		for (int i = 0; i < 100; i++) longLore.add("line" + i);
		String bounded = InventoryCaptureRules.slotLine(0, false, "x", 1, "y", longLore);
		check(bounded.contains("line23") && !bounded.contains("line24"), "lore is cut at 24 lines");
		check(InventoryCaptureRules.slotLine(0, false, "x", 1, "n".repeat(1000), List.of()).length() < 400, "names are cut");
		check(InventoryCaptureRules.slotLine(0, false, "x", 1, "a\nb\rc", List.of("d\ne")).indexOf('\n') < 0
			&& InventoryCaptureRules.slotLine(0, false, "x", 1, "a\nb\rc", List.of()).indexOf('\r') < 0,
			"line breaks in text cannot split a record");
		check("inventory-20261118-101500.txt".equals(InventoryCaptureRules.fileName("20261118-101500")), "capture file name");
		check(InventoryCaptureRules.MAX_SLOTS == 256, "a capture is bounded to 256 slots");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("InventoryCaptureChecks: " + message);
	}
}
