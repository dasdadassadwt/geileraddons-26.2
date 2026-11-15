package geiler.addons.client.module.impl;

import geiler.addons.client.inventory.ItemBackgrounds;
import geiler.addons.client.inventory.SkyblockRarity;
import geiler.addons.client.inventory.SlotInfoGrace;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;

import java.util.List;

/** Checks for the slot item overlays: rarity backgrounds, Invincibility fill, and No Cursor Reset. */
public final class SlotItemOverlayChecks {
	private SlotItemOverlayChecks() { }

	public static void run() throws java.io.IOException {
		checkRarityParsing();
		checkRarityColorsAndFill();
		checkJacobAndShapes();
		checkRarityModule();
		checkSlotGrace();
		checkIdMemory();
		checkBatchedDrawing();
		checkInvincibilityFill();
		checkNoCursorReset();
	}

	private static void checkRarityParsing() {
		check(ItemBackgrounds.rarityFromLore(List.of("Some text", "EPIC SWORD")) == SkyblockRarity.EPIC,
			"the rarity word in lore picks the rarity");
		check(ItemBackgrounds.rarityFromLore(List.of("UNCOMMON")) == SkyblockRarity.UNCOMMON
			&& ItemBackgrounds.rarityFromLore(List.of("VERY SPECIAL")) == SkyblockRarity.VERY_SPECIAL
			&& ItemBackgrounds.rarityFromLore(List.of("SPECIAL")) == SkyblockRarity.SPECIAL,
			"overlapping names resolve to the longer rarity (UNCOMMON, VERY SPECIAL)");
		check(ItemBackgrounds.rarityFromLore(List.of("EPIC", "common stuff", "LEGENDARY HELMET")) == SkyblockRarity.LEGENDARY,
			"lore is scanned from the bottom line up");
		check(ItemBackgrounds.rarityFromLore(List.of("nothing here", "just text")) == null
			&& ItemBackgrounds.rarityFromLore(List.of()) == null, "no rarity word means no background");
		check(ItemBackgrounds.petRarity("LEGENDARY", "PET_ITEM_TIER_BOOST") == SkyblockRarity.MYTHIC
			&& ItemBackgrounds.petRarity("EPIC", null) == SkyblockRarity.EPIC
			&& ItemBackgrounds.petRarity("EPIC", "PET_ITEM_ALL_SKILLS_BOOST") == SkyblockRarity.EPIC
			&& ItemBackgrounds.petRarity("nonsense", null) == null && ItemBackgrounds.petRarity(null, null) == null,
			"pets use their stored tier, one tier higher with the tier boost item");
	}

	private static void checkRarityColorsAndFill() {
		check(SkyblockRarity.RARE.color(false) == 0x459BFF && SkyblockRarity.RARE.color(true) == 0x5555FF
			&& SkyblockRarity.EPIC.color(false) == 0xA335EE && SkyblockRarity.EPIC.color(true) == 0xAA00AA
			&& SkyblockRarity.LEGENDARY.color(false) == 0xFF9000 && SkyblockRarity.LEGENDARY.color(true) == 0xFFAA00
			&& SkyblockRarity.COMMON.color(false) == 0xFFFFFF && SkyblockRarity.COMMON.color(true) == 0xFFFFFF
			&& SkyblockRarity.ULTIMATE.color(false) == 0xD13228 && SkyblockRarity.ULTIMATE.color(true) == 0xAA0000,
			"rarity colors and the legacy palette");
		check(ItemBackgrounds.fillArgb(0x459BFF, 50) == 0x80459BFF && ItemBackgrounds.fillArgb(0xFFFFFF, 0) == 0x00FFFFFF
			&& ItemBackgrounds.fillArgb(0xFFFFFF, 100) == 0xFFFFFFFF && ItemBackgrounds.fillArgb(0xFFFFFF, 500) == 0xFFFFFFFF
			&& ItemBackgrounds.fillArgb(0xFFFFFF, -3) == 0x00FFFFFF, "opacity percent becomes an alpha, clamped to 0..100");
	}

	private static void checkJacobAndShapes() {
		check(ItemBackgrounds.jacobColor(List.of("You placed in the GOLD bracket!")) == 0xFFAA00
			&& ItemBackgrounds.jacobColor(List.of("You placed in the DIAMOND bracket!")) == 0x55FFFF
			&& ItemBackgrounds.jacobColor(List.of("You placed in the BRONZE bracket!")) == 0xFF5555
			&& ItemBackgrounds.jacobColor(List.of("something else")) == -1, "Jacob medal brackets map to colors");
		int[] spans = ItemBackgrounds.circleHalfWidths();
		check(spans.length == 16 && spans[0] < spans[7] && spans[7] == spans[8] && spans[0] == spans[15]
			&& spans[7] <= 8 && spans[0] >= 1, "the circle shape is a symmetric 16x16 disc");
	}

	/**
	 * Vanilla places a GUI element above the highest element it overlaps. A background drawn between every slot's
	 * item stacks one layer per slot, so all backgrounds are drawn in one burst before the items.
	 */
	private static void checkBatchedDrawing() throws java.io.IOException {
		java.nio.file.Path mixins = java.nio.file.Path.of("src", "client", "java", "geiler", "addons", "client", "mixin");
		String container = java.nio.file.Files.readString(mixins.resolve("SlotItemOverlayMixin.java"));
		check(container.contains("method = \"extractSlots\"") && container.contains("beforeSlots(")
			&& !container.contains("beforeItem("), "container backgrounds are drawn in one burst before the slots");
		String hotbar = java.nio.file.Files.readString(mixins.resolve("HudHotbarSlotMixin.java"));
		check(hotbar.contains("beforeHotbarSlot(") && !hotbar.contains("beforeItem("),
			"hotbar backgrounds are drawn in one burst on the first slot");
		int[] runs = ItemBackgrounds.circleRuns();
		int[] spans = ItemBackgrounds.circleHalfWidths();
		int row = 0;
		for (int i = 0; i < runs.length; i += 3) {
			check(runs[i] == row && runs[i + 1] > runs[i], "circle runs are contiguous and non-empty");
			for (int r = runs[i]; r < runs[i + 1]; r++) check(spans[r] == runs[i + 2], "a run has one half-width");
			row = runs[i + 1];
		}
		check(row == 16 && runs.length / 3 < 16, "the disc is drawn with fewer than 16 rectangles");
	}

	/** A slot whose stack briefly arrives without SkyBlock data keeps its last background for a short grace. */
	private static void checkSlotGrace() {
		SlotInfoGrace<String> grace = new SlotInfoGrace<>(30L);
		ItemBackgrounds.StackInfo epic = new ItemBackgrounds.StackInfo("HELM", SkyblockRarity.EPIC, -1, false);
		ItemBackgrounds.StackInfo blank = new ItemBackgrounds.StackInfo("", null, -1, false);
		check(grace.resolve("head", "helmet", epic, 100L) == epic, "a stack with a background is used as is");
		check(grace.resolve("head", "helmet", blank, 110L) == epic && grace.getUsed() == 1,
			"the same item arriving without data keeps the old background");
		check(grace.resolve("head", "helmet", blank, 130L) == epic, "the grace runs from the last good sighting");
		check(grace.resolve("head", "helmet", blank, 131L) == blank, "after the grace the blank stack shows no background");
		check(grace.resolve("head", "helmet", blank, 132L) == blank, "an expired memo is not revived");
		grace.resolve("chest", "plate", epic, 200L);
		check(grace.resolve("chest", "boots", blank, 201L) == blank, "a different item never inherits the memo");
		grace.resolve("legs", "pants", epic, 300L);
		check(grace.resolve("other", "pants", blank, 301L) == blank, "a memo belongs to its slot");
		grace.clear();
		check(grace.resolve("legs", "pants", blank, 302L) == blank, "clear forgets every memo");
	}

	/** A stripped stack (SkyBlock id, no lore) takes the rarity last seen on a full stack of the same id. */
	private static void checkIdMemory() {
		ItemBackgrounds.clearMemory();
		ItemBackgrounds.StackInfo stripped = new ItemBackgrounds.StackInfo("LEATHER_HELM", null, -1, false);
		check(ItemBackgrounds.rememberRarity("LEATHER_HELM", false, stripped) == stripped
			&& ItemBackgrounds.isUnresolved("LEATHER_HELM", false, stripped), "an unknown id stays blank and unresolved");
		ItemBackgrounds.StackInfo full = new ItemBackgrounds.StackInfo("LEATHER_HELM", SkyblockRarity.EPIC, -1, false);
		check(ItemBackgrounds.rememberRarity("LEATHER_HELM", true, full) == full && ItemBackgrounds.idMemorySize() == 1,
			"a full stack is returned as is and its rarity is remembered");
		ItemBackgrounds.StackInfo filled = ItemBackgrounds.rememberRarity("LEATHER_HELM", false,
			new ItemBackgrounds.StackInfo("LEATHER_HELM", null, -1, true));
		check(filled.getRarity() == SkyblockRarity.EPIC && filled.getHasAttributes() && filled.getSkyblockId().equals("LEATHER_HELM")
			&& ItemBackgrounds.idFallbackUsed() == 1, "a stripped stack gets the remembered rarity and keeps its own data");
		check(ItemBackgrounds.rememberRarity("LEATHER_HELM", true, stripped) == stripped,
			"a stack with lore but no rarity word never takes the remembered rarity");
		check(!ItemBackgrounds.isUnresolved("LEATHER_HELM", false, filled) && !ItemBackgrounds.isUnresolved("LEATHER_HELM", true, stripped)
			&& !ItemBackgrounds.isUnresolved("", false, stripped), "only a stripped stack with an id counts as unresolved");
		ItemBackgrounds.StackInfo pet = new ItemBackgrounds.StackInfo("PET", SkyblockRarity.LEGENDARY, -1, false);
		ItemBackgrounds.rememberRarity("PET", true, pet);
		ItemBackgrounds.StackInfo blankPet = new ItemBackgrounds.StackInfo("PET", null, -1, false);
		check(ItemBackgrounds.rememberRarity("PET", false, blankPet) == blankPet
			&& ItemBackgrounds.rememberRarity("", true, full) == full && ItemBackgrounds.idMemorySize() == 1,
			"pets and blank ids are never remembered by id");
		for (int i = 0; i < 1500; i++) {
			ItemBackgrounds.rememberRarity("ID_" + i, true, new ItemBackgrounds.StackInfo("ID_" + i, SkyblockRarity.RARE, -1, false));
		}
		check(ItemBackgrounds.idMemorySize() <= 512, "the id memory is bounded");
		ItemBackgrounds.clearMemory();
		check(ItemBackgrounds.idMemorySize() == 0 && ItemBackgrounds.idFallbackUsed() == 0, "clearMemory forgets every id");
	}

	private static void checkRarityModule() {
		ItemRarityBackgroundsModule module = ItemRarityBackgroundsModule.INSTANCE;
		check(module.name().equals("Item Rarity Backgrounds") && module.category() == Category.VISUAL
			&& !module.defaultEnabled() && module.hasSettings(), "Item Rarity Backgrounds is a disabled-by-default Visual module");
		check(names(module.booleanSettings()).equals(List.of(
			"Rarity Backgrounds", "Legacy Rarity Colors", "Jacob Medal Backgrounds", "Attribute Item Backgrounds"))
			&& module.booleanSettings().stream().map(BooleanSetting::rawValue).toList()
				.equals(List.of(true, false, true, true)), "background toggles match Skyblocker's defaults");
		NumberSetting opacity = module.numberSettings().getFirst();
		check(names(module.numberSettings()).equals(List.of("Background Opacity")) && Float.compare(opacity.value(), 50f) == 0
			&& opacity.isInteger(), "opacity defaults to 50%");
		ChoiceSetting style = module.choiceSettings().getFirst();
		check(names(module.choiceSettings()).equals(List.of("Background Style")) && style.value().equals("Square")
			&& style.choices().equals(List.of("Square", "Circular")), "style defaults to Square, Circular is the alternative");
	}

	private static void checkInvincibilityFill() {
		check(DungeonInvincibilityTimerModule.fillHeight(90, 180, 16) == 8
			&& DungeonInvincibilityTimerModule.fillHeight(180, 180, 16) == 16
			&& DungeonInvincibilityTimerModule.fillHeight(1, 180, 16) == 1
			&& DungeonInvincibilityTimerModule.fillHeight(0, 180, 16) == 0
			&& DungeonInvincibilityTimerModule.fillHeight(10, 0, 16) == 0
			&& DungeonInvincibilityTimerModule.fillHeight(500, 180, 16) == 16,
			"the green fill shrinks with the cooldown and is gone when the mask is ready");
		check(DungeonInvincibilityTimerModule.procForItemId("BONZO_MASK") != null
			&& DungeonInvincibilityTimerModule.procForItemId("STARRED_BONZO_MASK") != null
			&& DungeonInvincibilityTimerModule.procForItemId("SPIRIT_MASK") != null
			&& DungeonInvincibilityTimerModule.procForItemId("STARRED_SPIRIT_MASK") != null
			&& DungeonInvincibilityTimerModule.procForItemId("HYPERION") == null
			&& DungeonInvincibilityTimerModule.procForItemId("") == null, "only the Bonzo and Spirit masks are matched");
		check(names(DungeonInvincibilityTimerModule.INSTANCE.booleanSettings()).contains("Show On Item")
			&& DungeonInvincibilityTimerModule.INSTANCE.booleanSettings().stream()
				.filter(setting -> setting.name().equals("Show On Item")).findFirst().orElseThrow().rawValue() == false,
			"Show On Item exists and is off by default");
	}

	private static void checkNoCursorReset() {
		check(NoCursorResetModule.shouldKeepCursor(true, 100L, 150), "inside the timeout the cursor is kept");
		check(!NoCursorResetModule.shouldKeepCursor(true, 150L, 150) && !NoCursorResetModule.shouldKeepCursor(true, 900L, 150),
			"after the timeout the cursor resets as usual");
		check(!NoCursorResetModule.shouldKeepCursor(false, 10L, 150), "only container screens keep the cursor");
		NoCursorResetModule module = NoCursorResetModule.INSTANCE;
		check(module.name().equals("No Cursor Reset") && !module.defaultEnabled() && module.category() == Category.MISCELLANEOUS,
			"No Cursor Reset is a disabled-by-default Miscellaneous module");
		NumberSetting timeout = module.numberSettings().getFirst();
		check(names(module.numberSettings()).equals(List.of("Unhook Timeout")) && Float.compare(timeout.min(), 0f) == 0
			&& Float.compare(timeout.max(), 1000f) == 0 && Float.compare(timeout.value(), 150f) == 0 && timeout.isInteger(),
			"Unhook Timeout is 0-1000 ms, default 150");
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
