package geiler.addons.client.storage;

import java.util.List;

/** Offline checks for the Storage Overlay title, command, search and layout rules. */
public final class StorageOverlayRulesChecks {
	private StorageOverlayRulesChecks() { }

	public static void run() {
		titles();
		commands();
		home();
		search();
		layout();
		store();
	}

	private static void store() {
		require(StorageOverlayRules.validPageIndex(0) && StorageOverlayRules.validPageIndex(26), "saved index range is 0..26");
		require(!StorageOverlayRules.validPageIndex(-1) && !StorageOverlayRules.validPageIndex(27), "saved index outside the range is dropped");
		require(StorageOverlayRules.validPageItems(0) && StorageOverlayRules.validPageItems(45) && StorageOverlayRules.validPageItems(54),
			"a saved page holds up to a double chest of items");
		require(!StorageOverlayRules.validPageItems(-1) && !StorageOverlayRules.validPageItems(55), "an oversized saved page is dropped");
		require(!StorageOverlayRules.storeSizeOk(0L) && StorageOverlayRules.storeSizeOk(1_000_000L)
			&& !StorageOverlayRules.storeSizeOk(StorageOverlayRules.MAX_STORE_BYTES + 1), "saved file size is bounded");
		require("12345678-aaaa-bbbb-cccc-1234567890ab.nbt".equals(StorageOverlayRules.storeFileName("12345678-aaaa-bbbb-cccc-1234567890ab")),
			"store file is named after the player id");
		String hostile = StorageOverlayRules.storeFileName("../..\\x");
		require(!hostile.contains("/") && !hostile.contains("\\") && !hostile.contains(".."), "a hostile id cannot leave the store folder");
	}

	private static void titles() {
		require(StorageOverlayRules.indexFromTitle("Ender Chest (1/9)") == 0, "echest 1 is index 0");
		require(StorageOverlayRules.indexFromTitle("Ender Chest (9/9)") == 8, "echest 9 is index 8");
		require(StorageOverlayRules.indexFromTitle("Backpack (Slot #1)") == 9, "backpack 1 is index 9");
		require(StorageOverlayRules.indexFromTitle("Jumbo Backpack (Slot #18)") == 26, "backpack 18 is index 26");
		require(StorageOverlayRules.indexFromTitle("ender chest (3/9)") == 2, "title match ignores case");
		require(StorageOverlayRules.indexFromTitle("Ender Chest (10/9)") == -1, "echest past 9 is not a page");
		require(StorageOverlayRules.indexFromTitle("Backpack (Slot #19)") == -1, "backpack past 18 is not a page");
		require(StorageOverlayRules.indexFromTitle("Chest") == -1, "other titles are not pages");
		require(StorageOverlayRules.isHomeTitle("Storage") && StorageOverlayRules.isHomeTitle(" storage "),
			"home title ignores case and padding");
		require(!StorageOverlayRules.isHomeTitle("Storage Upgrades") && !StorageOverlayRules.isHomeTitle("Ender Chest (1/9)"),
			"only the bare title is the home page");
	}

	private static void commands() {
		require("echest 1".equals(StorageOverlayRules.commandFor(0)), "index 0 opens echest 1");
		require("echest 9".equals(StorageOverlayRules.commandFor(8)), "index 8 opens echest 9");
		require("backpack 1".equals(StorageOverlayRules.commandFor(9)), "index 9 opens backpack 1");
		require("backpack 18".equals(StorageOverlayRules.commandFor(26)), "index 26 opens backpack 18");
		require(StorageOverlayRules.commandFor(27) == null && StorageOverlayRules.commandFor(-1) == null,
			"out of range has no command");
		require("Ender Chest 4".equals(StorageOverlayRules.pageName(3)), "echest name");
		require("Backpack 2".equals(StorageOverlayRules.pageName(10)), "backpack name");
		for (int i = 0; i < StorageOverlayRules.PAGE_COUNT; i++) {
			String command = StorageOverlayRules.commandFor(i);
			String name = StorageOverlayRules.pageName(i);
			require(command != null && name.toLowerCase().replace(" ", "").replace("enderchest", "echest")
				.equals(command.replace(" ", "")), "command and name agree for index " + i);
		}
	}

	private static void home() {
		require(StorageOverlayRules.homeIndex(9) == 0 && StorageOverlayRules.homeIndex(17) == 8, "home echest slots 9..17");
		require(StorageOverlayRules.homeIndex(27) == 9 && StorageOverlayRules.homeIndex(44) == 26, "home backpack slots 27..44");
		require(StorageOverlayRules.homeIndex(18) == -1 && StorageOverlayRules.homeIndex(45) == -1
			&& StorageOverlayRules.homeIndex(8) == -1, "other home slots are not pages");
		require(StorageOverlayRules.backpackSlots("Has 27 slots") == 27, "backpack size from lore");
		require(StorageOverlayRules.backpackSlots("Not a size") == -1, "no size in lore");
	}

	private static void search() {
		List<String> lore = List.of("Right-click to open", "Sells for 4,000 coins");
		require(StorageOverlayRules.matches("", "Diamond", lore), "empty filter matches everything");
		require(StorageOverlayRules.matches("diam", "Enchanted Diamond", lore), "name match ignores case");
		require(StorageOverlayRules.matches("COINS", "Diamond", lore), "lore line match");
		require(!StorageOverlayRules.matches("emerald", "Diamond", lore), "no match");
		require(StorageOverlayRules.matches("  diam ", "Diamond", lore), "filter is trimmed");
	}

	private static void layout() {
		require(StorageOverlayRules.fittingColumns(9, 300) == 9, "wide room keeps nine columns");
		require(StorageOverlayRules.fittingColumns(9, 100) == 4, "narrow room drops columns until it fits");
		require(StorageOverlayRules.fittingColumns(9, 10) == 1, "never fewer than one column");
		require(StorageOverlayRules.pageRows(54, 9) == 5, "echest has five item rows");
		require(StorageOverlayRules.pageRows(36, 9) == 3, "36-slot backpack has three rows");
		require(StorageOverlayRules.pageRows(9, 9) == 0 && StorageOverlayRules.pageRows(0, 9) == 0, "no item rows");
		require(StorageOverlayRules.pageRows(54, 4) == 12, "narrower page needs more rows");
		require(StorageOverlayRules.cardHeight(5) == StorageOverlayRules.HEADER + 5 * StorageOverlayRules.SLOT
			+ StorageOverlayRules.FOOTER, "card height from rows");
		require(StorageOverlayRules.clampScroll(-5.0, 300, 100) == 0.0, "scroll floor");
		require(StorageOverlayRules.clampScroll(500.0, 300, 100) == 200.0, "scroll stops at the end");
		require(StorageOverlayRules.clampScroll(50.0, 80, 100) == 0.0, "content shorter than the view never scrolls");
		int[] offsets = StorageOverlayRules.rowOffsets(List.of(114, 114, 114, 114, 96, 60, 50), 3, 4);
		require(offsets.length == 3 && offsets[0] == 0 && offsets[1] == 118 && offsets[2] == 118 + 114 + 4,
			"rows stack by their tallest card");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError("StorageOverlayRulesChecks: " + message);
	}
}
