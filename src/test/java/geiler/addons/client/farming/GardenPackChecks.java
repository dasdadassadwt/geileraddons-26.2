package geiler.addons.client.farming;

import geiler.addons.client.module.impl.GardenKeybindsModule;
import geiler.addons.client.module.impl.GreenhouseHelperModule;
import geiler.addons.client.module.impl.VisitorShoppingListModule;
import java.util.List;
import java.util.Map;

/** Offline checks for Garden keybinds, the visitor shopping list and the greenhouse helper rules. */
public final class GardenPackChecks {
	private GardenPackChecks() { }

	public static void run() {
		checkKeybindRules();
		checkVisitorRules();
		checkGreenhouseRules();
		checkBazaar();
		checkModules();
	}

	private static void checkKeybindRules() {
		check(GardenKeybindRules.isFarmingTool("THEORETICAL_HOE_WHEAT_3") && GardenKeybindRules.isFarmingTool("PUMPKIN_DICER_2")
			&& GardenKeybindRules.isFarmingTool("COCO_CHOPPER") && GardenKeybindRules.isFarmingTool("CACTUS_KNIFE")
			&& GardenKeybindRules.isFarmingTool("FUNGI_CUTTER") && GardenKeybindRules.isFarmingTool("SQUEAKY_MOUSEMAT")
			&& GardenKeybindRules.isFarmingTool("SKYMART_VACUUM"), "farming tools, mousemat and vacuum are recognised");
		check(!GardenKeybindRules.isFarmingTool("DIAMOND_SWORD") && !GardenKeybindRules.isFarmingTool("SHOE_OF_LUCK") && !GardenKeybindRules.isFarmingTool(null),
			"a sword, a word merely containing HOE and no item are not farming tools");
		check(GardenKeybindRules.isExcludedPlot("The Barn") && GardenKeybindRules.isExcludedPlot("Greenhouse") && !GardenKeybindRules.isExcludedPlot("12") && !GardenKeybindRules.isExcludedPlot(null),
			"the barn and the greenhouse are the excluded plots");
		check(GardenKeybindRules.edge(false, true) && !GardenKeybindRules.edge(true, true) && !GardenKeybindRules.edge(true, false), "a click is a rise from released to held");
		check(GardenKeybindRules.isControl("key.forward") && GardenKeybindRules.isControl("key.attack") && GardenKeybindRules.isControl("key.sneak") && !GardenKeybindRules.isControl("key.sprint"),
			"the eight remappable controls");
	}

	private static void checkVisitorRules() {
		Map<String, Integer> needed = VisitorRules.parseRequired(List.of("Items Required:", " Enchanted Carrot x5", " Wheat x1,200", " Cocoa Beans", "", "Rewards:", " +20 Copper"));
		check(needed.size() == 3 && needed.get("Enchanted Carrot") == 5 && needed.get("Wheat") == 1200 && needed.get("Cocoa Beans") == 1,
			"required items are read up to the rewards, with an amount of one when none is written");
		check(VisitorRules.parseRequired(List.of("Rewards:", " Wheat x3")).isEmpty() && VisitorRules.parseRequired(List.of()).isEmpty(), "no required section means no items");
		check(VisitorRules.isVisitorInfo(List.of("a", "b", "c", "Offers Accepted: 12")) && !VisitorRules.isVisitorInfo(List.of("a", "Offers Accepted: 12"))
			&& !VisitorRules.isVisitorInfo(List.of("a", "b", "c", "Something else")), "the visitor info item has four lore lines ending in the offers accepted count");
		List<String> tab = List.of("Area: Garden", "Visitors: (2)", " §aEmissary Carlton", " §9Lazy Miner", " §fNot A Visitor", "Composter:");
		List<String> names = VisitorRules.visitorsInTab(tab);
		check(names != null && names.equals(List.of("Emissary Carlton", "Lazy Miner")), "the visitor names are the rows under the count");
		check(VisitorRules.visitorsInTab(List.of("Area: Garden")) == null && VisitorRules.visitorsInTab(List.of("Visitors: (0)")).isEmpty(), "no widget is unknown, an empty widget is empty");
		check(VisitorRules.have(List.of(new VisitorRules.Held("Wheat", 64), new VisitorRules.Held("Wheat", 36), new VisitorRules.Held("Carrot", 5)), "Wheat") == 100, "held stacks of one name add up");
		java.util.LinkedHashMap<String, Map<String, Integer>> needs = new java.util.LinkedHashMap<>();
		needs.put("Ryan", Map.of("Carrot", 5));
		needs.put("Nyko", new java.util.LinkedHashMap<>(Map.of("Wheat", 100)));
		needs.get("Nyko").put("Carrot", 20);
		List<VisitorRules.Row> rows = VisitorRules.rows(needs, name -> name.equals("Wheat") ? 100 : 10);
		check(rows.get(0).getText().contains("2") && rows.get(0).getText().contains("1 ready") && rows.get(0).getBuyItem() == null, "the header counts visitors and ready ones");
		VisitorRules.Row ryan = rows.stream().filter(r -> r.getText().contains("Ryan")).findFirst().orElseThrow();
		VisitorRules.Row nyko = rows.stream().filter(r -> r.getText().contains("Nyko")).findFirst().orElseThrow();
		check(ryan.getText().startsWith("§a") && nyko.getText().startsWith("§e"), "a visitor you can serve is green, one half served is yellow");
		check(rows.indexOf(ryan) < rows.indexOf(nyko), "visitors keep the order they were read in");
		List<VisitorRules.Row> buy = rows.stream().filter(r -> r.getBuyItem() != null).toList();
		check(buy.size() == 1 && buy.get(0).getBuyItem().equals("Carrot") && buy.get(0).getBuyAmount() == 15,
			"only the short item is clickable, with the missing amount (25 needed, 10 held, the stock spent on Ryan first)");
		List<VisitorRules.Row> none = VisitorRules.rows(Map.of("Ryan", Map.of("Carrot", 5)), name -> 5);
		check(none.stream().allMatch(r -> r.getBuyItem() == null) && none.stream().anyMatch(r -> r.getText().contains("in hand")), "nothing short means nothing to buy");
		check(VisitorRules.rows(Map.of(), name -> 0).isEmpty(), "no visitors means no rows");
	}

	private static void checkGreenhouseRules() {
		check(GreenhouseRules.isDiagnosticsTitle("Crop Diagnostics") && !GreenhouseRules.isDiagnosticsTitle("Crop Diagnostic Tools"), "the diagnostics menu title");
		check(Long.valueOf(6020).equals(GreenhouseRules.nextStageSeconds(List.of("Growth", "Next Stage: 1h 40m 20s"))) && Long.valueOf(20).equals(GreenhouseRules.nextStageSeconds(List.of("Next Stage: 20s")))
			&& GreenhouseRules.nextStageSeconds(List.of("Next Stage: soon")) == null && GreenhouseRules.nextStageSeconds(List.of()) == null, "the next stage time becomes seconds");
		check(GreenhouseRules.harvestColor(List.of("Status: Harvestable")) == GreenhouseRules.GREEN && GreenhouseRules.harvestColor(List.of("Drops: x", "Status: Growing")) == GreenhouseRules.YELLOW
			&& GreenhouseRules.harvestColor(List.of("Status: Growing")) == GreenhouseRules.RED, "harvestable is green, drops yellow, otherwise red");
		check(GreenhouseRules.waterOk(List.of("This crop has enough water to", "grow")) && GreenhouseRules.waterOk(List.of("This crop doesn't need water!")) && !GreenhouseRules.waterOk(List.of("Needs water")), "water status");
		check(GreenhouseRules.timerText(0).contains("OVERDUE"), "a passed timer reads overdue");
		check(GreenhouseRules.timerText(6020).contains("1h 40m"), "a running timer reads hours and minutes");
	}

	private static void checkBazaar() {
		check("bz Enchanted Carrot".equals(GardenBazaarRules.command("Enchanted Carrot")) && "bz Wheat".equals(GardenBazaarRules.command("  Wheat ")), "the bazaar command carries the item name");
		check(GardenBazaarRules.lineAt(12, 30, 10, 20, 100, 9, 1.0f, 3) == 1 && GardenBazaarRules.lineAt(12, 20, 10, 20, 100, 9, 1.0f, 3) == 0 && GardenBazaarRules.lineAt(12, 50, 10, 20, 100, 9, 1.0f, 3) == -1
			&& GardenBazaarRules.lineAt(5, 30, 10, 20, 100, 9, 1.0f, 3) == -1 && GardenBazaarRules.lineAt(12, 30, 10, 20, 100, 9, 2.0f, 3) == 0, "a click maps to a list row, scaled, or to none outside it");
	}

	private static void checkModules() {
		check(!GardenKeybindsModule.INSTANCE.defaultEnabled() && GardenKeybindsModule.INSTANCE.override("key.forward") == null,
			"Garden Keybinds is off by default and overrides nothing while inactive");
		check(!VisitorShoppingListModule.INSTANCE.defaultEnabled() && !GreenhouseHelperModule.INSTANCE.defaultEnabled(), "the other two are off by default");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("GardenPackChecks: " + message);
	}
}
