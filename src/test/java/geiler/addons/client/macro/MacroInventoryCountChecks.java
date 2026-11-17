package geiler.addons.client.macro;

import geiler.addons.client.config.MacroStepConfigCodec;

import java.util.List;

/** Isolated acceptance checks for item-id precedence, stack accumulation, and condition persistence. */
public final class MacroInventoryCountChecks {
	private MacroInventoryCountChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		List<MacroItemCountRules.StackIdentity> inventory = List.of(
			new MacroItemCountRules.StackIdentity("ENCHANTED_CARROT", "minecraft:carrot", 8),
			new MacroItemCountRules.StackIdentity("OTHER_SKYBLOCK_ITEM", "minecraft:carrot", 3),
			new MacroItemCountRules.StackIdentity("", "minecraft:carrot", 2),
			new MacroItemCountRules.StackIdentity("ENCHANTED_CARROT", "minecraft:carrot", 4),
			new MacroItemCountRules.StackIdentity("ENCHANTED_CARROT", "minecraft:chest", 64));
		check(MacroItemCountRules.count(inventory, "enchanted_carrot", "minecraft:carrot") == 78,
			"matching SkyBlock IDs take precedence while absent IDs use vanilla fallback and stacks accumulate");
		check(MacroItemCountRules.count(inventory, "missing_id", "minecraft:carrot") == 2,
			"vanilla ID is used only when the actual stack has no SkyBlock ID");
		check(MacroItemCountRules.count(inventory, "", "minecraft:chest") == 64,
			"vanilla-only matching works");
		check(!MacroItemCountRules.matches("OTHER_SKYBLOCK_ITEM", "minecraft:carrot",
			"ENCHANTED_CARROT", "minecraft:carrot"), "different SkyBlock IDs do not fall through to vanilla");
		check(MacroItemCountRules.skyBlockIdFromCustomData(
			"CustomData[{ExtraAttributes:{id:\"ENCHANTED_CARROT\",rarity:\"RARE\"}}]")
			.equals("ENCHANTED_CARROT"), "SkyBlock id parser reads only the ExtraAttributes item id");

		check(MacroItemCountRules.skyBlockIdFromCustomData(
			"CustomData[{id:\"HOTSPOT_RADAR\",uuid:\"abc\",ability:{id:\"NESTED\"}}]").equals("HOTSPOT_RADAR"),
			"26.x custom data keeps the id at the top level and a nested id is ignored");
		check(MacroItemCountRules.skyBlockIdFromCustomData(
			"CustomData[{ability:{id:\"NESTED\"},uuid:\"abc\"}]").isEmpty(),
			"a nested id alone is not an item id");

		MacroValue value = MacroValue.itemCount("ENCHANTED_CARROT", "minecraft:carrot");
		check(value.type() == MacroValue.Type.ITEM_COUNT && value.skyblockItemId().equals("ENCHANTED_CARROT"),
			"item count value retains its preferred and fallback ids");
		MacroCondition condition = new MacroCondition.InventoryCount("ENCHANTED_CARROT",
			"minecraft:carrot", MacroCondition.Variable.Operator.GREATER_OR_EQUAL, 12);
		MacroStep.WaitUntil wait = new MacroStep.WaitUntil(condition);
		MacroStep decoded = MacroStepConfigCodec.decode(MacroStepConfigCodec.encode(List.of(wait))).getFirst();
		check(decoded instanceof MacroStep.WaitUntil restored
			&& restored.condition().equals(condition), "local macro config round-trips the count threshold");
		MacroStep.UpdateVariable setCount = MacroStep.UpdateVariable.set("count", value);
		MacroStep restoredValue = MacroStepConfigCodec.decode(
			MacroStepConfigCodec.encode(List.of(setCount))).getFirst();
		check(restoredValue instanceof MacroStep.UpdateVariable restored
			&& restored.value().type() == MacroValue.Type.ITEM_COUNT
			&& restored.value().skyblockItemId().equals("ENCHANTED_CARROT")
			&& restored.value().vanillaItemId().equals("minecraft:carrot"),
			"macro config round-trips dynamic inventory count values");
		MacroDefinition transfer = new MacroDefinition(4);
		transfer.steps().add(wait);
		MacroTransfer.ImportResult imported = MacroTransfer.decode(MacroTransfer.encode(List.of(transfer)), 20);
		check(imported.success() && imported.macros().getFirst().steps().getFirst() instanceof MacroStep.WaitUntil transferred
			&& transferred.condition().equals(condition), "macro transfer codec round-trips count thresholds");
		MacroDefinition valueTransfer = new MacroDefinition(5);
		valueTransfer.steps().add(setCount);
		MacroTransfer.ImportResult importedValue = MacroTransfer.decode(
			MacroTransfer.encode(List.of(valueTransfer)), 30);
		check(importedValue.success()
			&& importedValue.macros().getFirst().steps().getFirst() instanceof MacroStep.UpdateVariable transferredValue
			&& transferredValue.value().equals(value), "macro transfer codec round-trips item-count values");

		MacroCondition legacy = MacroStepConfigCodec.decode(com.google.gson.JsonParser.parseString(
			"[{\"type\":\"wait_until\",\"condition\":{\"type\":\"item\",\"name\":\"carrot\",\"includePlayerInventory\":true}}]")
			.getAsJsonArray()).getFirst() instanceof MacroStep.WaitUntil restored ? restored.condition() : null;
		check(legacy instanceof MacroCondition.Item item
			&& item.scope() == MacroCondition.ItemScope.CONTAINER_AND_PLAYER,
			"legacy item-name conditions retain their carried inventory default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
