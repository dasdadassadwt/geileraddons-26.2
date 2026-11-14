package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonClassTab;
import java.util.List;

/** Offline checks for Item Cooldowns' parsing and timers, and the tab-list class parsing behind the Mage reduction. */
public final class ItemCooldownChecks {
	private ItemCooldownChecks() { }

	public static void run() {
		check("Instant Transmission".equals(ItemCooldownTracker.abilityFromActionBar(
			"❤ 1000/1000     -50 Mana (Instant Transmission)     ✎ 200/300")),
			"the ability name is read from the mana-cost action bar text");
		check(ItemCooldownTracker.abilityFromActionBar("❤ 1000/1000     ✎ 200/300") == null
			&& ItemCooldownTracker.abilityFromActionBar(null) == null, "an action bar without a mana cost starts nothing");

		List<String> lore = List.of("Ability: Instant Transmission  RIGHT CLICK", "Teleport 12 blocks ahead.", "Mana Cost: 50",
			"Cooldown: 5s", "Ability: Other  RIGHT CLICK", "Cooldown: 30s");
		check(Double.valueOf(5.0).equals(ItemCooldownTracker.cooldownSeconds(lore, "Instant Transmission")),
			"the cooldown after the named ability line is used");
		check(Double.valueOf(30.0).equals(ItemCooldownTracker.cooldownSeconds(lore, "Other")), "a second ability reads its own cooldown");
		check(ItemCooldownTracker.cooldownSeconds(lore, "Missing") == null
			&& ItemCooldownTracker.cooldownSeconds(List.of("Ability: A  RIGHT CLICK", "Ability: B  RIGHT CLICK", "Cooldown: 3s"), "A") == null,
			"an ability without its own cooldown line yields none");
		check(Double.valueOf(0.5).equals(ItemCooldownTracker.cooldownSeconds(List.of("Ability: Quick  RIGHT CLICK", "Cooldown: 0.5s"), "Quick")),
			"decimal cooldowns are read");

		check("4.2".equals(ItemCooldownTracker.format(4200L)) && "42".equals(ItemCooldownTracker.format(41_100L))
			&& "10".equals(ItemCooldownTracker.format(10_000L)) && "0.0".equals(ItemCooldownTracker.format(-5L)),
			"seconds show whole from ten up and with one decimal below");

		check("Instant Transmission".equals(ItemCooldownTracker.abilityFromActionBar("-50 Mana (Instant Transmission)")),
			"the mana-cost text is matched without its leading minus");
		List<ItemCooldownTracker.Companion.Ability> abilities = ItemCooldownTracker.rightClickAbilities(lore);
		check(abilities.size() == 2 && "Instant Transmission".equals(abilities.get(0).getName()) && abilities.get(0).getSeconds() == 5.0
			&& "Other".equals(abilities.get(1).getName()) && abilities.get(1).getSeconds() == 30.0,
			"every right-click ability with its own cooldown is listed in lore order");
		check(ItemCooldownTracker.rightClickAbilities(List.of("Ability: Sneak  SNEAK", "Cooldown: 5s", "Ability: NoCd  RIGHT CLICK", "Mana Cost: 5")).isEmpty()
			&& ItemCooldownTracker.rightClickAbilities(List.of()).isEmpty(), "sneak abilities and abilities without a cooldown are skipped");
		check(Double.valueOf(5.0).equals(ItemCooldownTracker.cooldownFromChat("This ability is on cooldown for 5s."))
			&& Double.valueOf(2.5).equals(ItemCooldownTracker.cooldownFromChat("This item is currently on cooldown for 2.5s!"))
			&& ItemCooldownTracker.cooldownFromChat("Nothing here") == null && ItemCooldownTracker.cooldownFromChat(null) == null,
			"the server's cooldown message gives the remaining seconds");

		ItemCooldownTracker tracker = new ItemCooldownTracker();
		check(tracker.start("AOTV", "Instant Transmission", 5000L, 1000L), "a first use starts the cooldown");
		check(!tracker.start("AOTV", "Instant Transmission", 5000L, 3000L) && !tracker.start("AOTV", "Instant Transmission", 5000L, 5900L),
			"a use while the cooldown runs is ignored, so holding right click never restarts it");
		ItemCooldownTracker.Reading running = tracker.reading("AOTV", 3000L, 3000L);
		check(running != null && !running.getReady() && running.getRemainingMs() == 3000L, "two seconds in, three remain");
		ItemCooldownTracker.Reading ready = tracker.reading("AOTV", 7000L, 3000L);
		check(ready != null && ready.getReady() && ready.getRemainingMs() == 0L, "after the cooldown the ready check shows");
		check(tracker.start("AOTV", "Instant Transmission", 5000L, 7500L), "a use during the ready check starts it again");
		check(tracker.reading("AOTV", 20_000L, 3000L) == null && tracker.size() == 0, "the ready check expires after its stay");
		check(tracker.start("AOTV", "Instant Transmission", 5000L, 20_000L) && tracker.reading("AOTV", 25_000L, -1L) == null,
			"with the ready check off an expired timer disappears at once");
		tracker.clear();
		tracker.start("W", "Wand", 30_000L, 0L);
		tracker.setRemaining("W", 12_000L, 10_000L);
		ItemCooldownTracker.Reading synced = tracker.reading("W", 10_000L, 0L);
		check(synced != null && synced.getRemainingMs() == 12_000L && synced.getTotalMs() == 30_000L, "the server's remaining time replaces the guess");
		tracker.setRemaining("N", 4_000L, 0L);
		check(tracker.reading("N", 0L, 0L) != null && tracker.reading("N", 0L, 0L).getRemainingMs() == 4_000L, "a synced item that was never seen still gets a timer");
		tracker.replace("W", "Other", 5_000L, 11_000L);
		check("Other".equals(tracker.reading("W", 12_000L, 0L).getLabel()) && tracker.reading("W", 12_000L, 0L).getRemainingMs() == 4_000L,
			"replace restarts a running cooldown with another ability");
		tracker.cancel("W");
		check(tracker.reading("W", 12_000L, 0L) == null && !tracker.isRunning("W", 12_000L), "cancel removes the timer");
		check(!tracker.start("", "x", 1000L, 0L) && !tracker.start("A", "x", 0L, 0L), "an empty key or zero cooldown starts nothing");
		for (int i = 0; i < ItemCooldownTracker.MAX_ENTRIES + 10; i++) tracker.start("K" + i, "x", 60_000L, 0L);
		check(tracker.size() <= ItemCooldownTracker.MAX_ENTRIES, "the tracker stays bounded");

		DungeonClassTab.Row row = DungeonClassTab.parse("[320] SupDi ♲ (Mage XLV)");
		check(row != null && "SupDi".equals(row.getName()) && row.getDungeonClass() == DungeonClass.MAGE && row.getLevel() == 45,
			"a dungeon tab row yields name, class and roman class level");
		check(DungeonClassTab.parse("[320] SupDi (DEAD)") == null && DungeonClassTab.parse("Players (4)") == null
			&& DungeonClassTab.parse(null) == null, "dead and unrelated rows are not parsed");
		check(DungeonClassTab.romanToInt("L") == 50 && DungeonClassTab.romanToInt("XLIX") == 49 && DungeonClassTab.romanToInt("IV") == 4
			&& DungeonClassTab.romanToInt("") == 0 && DungeonClassTab.romanToInt("Q") == 0, "roman class levels convert");
		check(Math.abs(DungeonClassTab.mageCooldownMultiplier(DungeonClass.MAGE, 45, 1) - 0.31) < 1e-9
			&& Math.abs(DungeonClassTab.mageCooldownMultiplier(DungeonClass.MAGE, 45, 2) - 0.53) < 1e-9
			&& DungeonClassTab.mageCooldownMultiplier(DungeonClass.TANK, 45, 1) == 1.0,
			"the Mage reduction doubles its level part for a lone Mage and is 1 for other classes");
		List<DungeonClassTab.Row> rows = DungeonClassTab.parseAll(java.util.Arrays.asList(
			"[320] SupDi (Mage XLV)", "[300] Other (Mage L)", "[200] Third (Tank XX)"));
		check(Math.abs(DungeonClassTab.mageCooldownMultiplier(rows, "supdi") - 0.53) < 1e-9
			&& DungeonClassTab.mageCooldownMultiplier(rows, "Third") == 1.0 && DungeonClassTab.mageCooldownMultiplier(rows, "Nobody") == 1.0,
			"the multiplier is read from the local player's own row");

		ItemCooldownsModule module = ItemCooldownsModule.INSTANCE;
		check(!module.defaultEnabled() && module.hasSettings() && module.choiceSettings().size() == 1
			&& "ON_ITEM".equals(module.choiceSettings().get(0).value()) && "item_cooldowns".equals(module.id()),
			"Item Cooldowns is off by default with a mode dropdown that starts On Item");
		module.onActionBar("-50 Mana (Instant Transmission)");
		module.onUse();
		module.onChatMessage("This ability is on cooldown for 5s.");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
