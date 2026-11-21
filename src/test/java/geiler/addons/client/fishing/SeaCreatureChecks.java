package geiler.addons.client.fishing;

/** Offline checks for the rare sea creature catalog, alert text, own-catch classification and dedupe. */
public final class SeaCreatureChecks {
	private static final long SECOND = 1_000_000_000L;

	private SeaCreatureChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkCatalog();
		checkChatLookup();
		checkNametagLookup();
		checkAlertText();
		checkOwnClassification();
		checkSeenSet();
		checkCap();
		System.out.println("Sea creature checks passed");
	}

	private static void checkCap() {
		require(SeaCreatureCapRules.isLabel("[Lv600] Lord Jawbus 100M❤") && SeaCreatureCapRules.isLabel("[Lv5] Squid 200❤")
				&& !SeaCreatureCapRules.isLabel("Lord Jawbus") && !SeaCreatureCapRules.isLabel("Squid [Lv5]") && !SeaCreatureCapRules.isLabel(""),
			"a mob name tag starts with its level");
		SeaCreatureCapRules.Tracker tracker = new SeaCreatureCapRules.Tracker();
		require(tracker.add(1) && tracker.add(2) && !tracker.add(1) && tracker.count() == 2, "an entity is counted once");
		require(tracker.retain(java.util.Set.of(2, 9)) == 1 && tracker.count() == 1, "an entity that is gone stops counting");
		tracker.clear();
		require(tracker.count() == 0, "clear empties the tracker");
		for (int id = 0; id < SeaCreatureCapRules.MAX_TRACKED + 10; id++) tracker.add(id);
		require(tracker.count() == SeaCreatureCapRules.MAX_TRACKED, "the tracker is bounded");
		require("Sea Creatures: 3/5".equals(SeaCreatureCapRules.text(3, 5)) && "Sea Creatures: 3".equals(SeaCreatureCapRules.text(3, 0)),
			"the label shows alive over the cap, or only alive when no cap is set");
		require(SeaCreatureCapRules.atCap(5, 5) && SeaCreatureCapRules.atCap(6, 5) && !SeaCreatureCapRules.atCap(4, 5) && !SeaCreatureCapRules.atCap(9, 0),
			"at the cap means alive reached a positive cap");
	}

	private static void checkCatalog() {
		require(SeaCreatureRules.rare().size() == 23, "the rare catalog holds all 23 rare sea creatures");
		SeaCreatureRules.Creature jawbus = SeaCreatureRules.rare().stream()
			.filter(c -> c.name().equals("Lord Jawbus")).findFirst().orElseThrow();
		require("§d".equals(jawbus.rarityColor()) && "§c".equals(jawbus.chatColor()),
			"Lord Jawbus is mythic (pink rarity) with the red lava chat color");
		SeaCreatureRules.Creature hydra = SeaCreatureRules.rare().stream()
			.filter(c -> c.name().equals("Water Hydra")).findFirst().orElseThrow();
		require("§6".equals(hydra.rarityColor()) && "§b".equals(hydra.chatColor()),
			"Water Hydra is legendary (gold rarity) with the aqua water chat color");
	}

	private static void checkChatLookup() {
		SeaCreatureRules.Creature hydra = SeaCreatureRules.byCatchMessage("The Water Hydra has come to test your strength.");
		require(hydra != null && hydra.name().equals("Water Hydra"), "an exact catch line finds its creature");
		require(SeaCreatureRules.byCatchMessage("A Squid appeared.") == null, "a common creature is not in the rare catalog");
		require(SeaCreatureRules.byCatchMessage("The Water Hydra has come to test your strength. ") == null,
			"matching is exact, not a substring or trimmed match");
		require(SeaCreatureRules.isDoubleHook("It's a Double Hook!") && SeaCreatureRules.isDoubleHook("It's a Double Hook! Woot woot!")
				&& !SeaCreatureRules.isDoubleHook("It's a Double Hook!!"),
			"both double hook lines are recognised");
	}

	private static void checkNametagLookup() {
		SeaCreatureRules.Creature found = SeaCreatureRules.byNametag("[Lv600] Lord Jawbus 100M❤");
		require(found != null && found.name().equals("Lord Jawbus"), "a level-prefixed name tag finds its creature");
		SeaCreatureRules.Creature old = SeaCreatureRules.byNametag("[Lv500] Sea Emperor 2M❤");
		require(old != null && old.name().equals("The Loch Emperor"), "an old name still finds the renamed creature");
		require(SeaCreatureRules.byNametag("Lord Jawbus") == null, "a bare name without the level prefix is ignored");
		require(SeaCreatureRules.byNametag("[Lv100] Squid 100❤") == null, "a common creature tag is ignored");
		require(SeaCreatureRules.byNametag("[Lv600] Thunderstruck 5M❤") == null, "a longer word does not match");
	}

	private static void checkAlertText() {
		SeaCreatureRules.Creature jawbus = SeaCreatureRules.byCatchMessage(
			"You have angered a legendary creature... Lord Jawbus has arrived.");
		require(jawbus != null, "Lord Jawbus is catalogued");
		require("§cLord Jawbus!".equals(SeaCreatureRules.ownTitle(jawbus, true)),
			"own catch title names the creature in its chat color");
		require("§dRARE CATCH!".equals(SeaCreatureRules.ownTitle(jawbus, false)),
			"own catch title falls back to the rarity color");
		require("§cLord Jawbus NEARBY!".equals(SeaCreatureRules.nearbyTitle(jawbus, true)),
			"nearby title names the creature");
		require("§dRARE SEA CREATURE!".equals(SeaCreatureRules.nearbyTitle(jawbus, false)),
			"nearby title falls back to the rarity color");
		require("I caught a Lord Jawbus!".equals(SeaCreatureRules.partyMessage(jawbus, false)),
			"party message uses a / an and the creature name");
		require("DOUBLE HOOK: I caught a Lord Jawbus!".equals(SeaCreatureRules.partyMessage(jawbus, true)),
			"a double hook is flagged in the party message");
		SeaCreatureRules.Creature yeti = SeaCreatureRules.byCatchMessage("What is this creature!?");
		require(yeti != null && "I caught a Yeti!".equals(SeaCreatureRules.partyMessage(yeti, false)),
			"consonant names take a");
		SeaCreatureRules.Creature abyssal = SeaCreatureRules.byCatchMessage("An Abyssal Miner breaks out of the water!");
		require(abyssal != null && "I caught an Abyssal Miner!".equals(SeaCreatureRules.partyMessage(abyssal, false)),
			"vowel names take an");
	}

	private static void checkOwnClassification() {
		long catchTime = 10 * SECOND;
		require(SeaCreatureRules.isOwn(catchTime, catchTime + 500_000_000L, 2.0),
			"a creature seen right after your catch and next to your bobber is yours");
		require(!SeaCreatureRules.isOwn(catchTime, catchTime + 3 * SECOND, 2.0),
			"a creature seen long after your catch is someone else's");
		require(!SeaCreatureRules.isOwn(catchTime, catchTime + 500_000_000L, 20.0),
			"a creature far from your bobber is someone else's");
		require(!SeaCreatureRules.isOwn(Long.MIN_VALUE, catchTime, 1.0), "with no recent catch nothing is yours");
	}

	private static void checkSeenSet() {
		SeaCreatureRules.SeenSet seen = new SeaCreatureRules.SeenSet(6 * 60 * SECOND);
		require(seen.firstSight(7, 0L), "the first sight of an entity is new");
		require(!seen.firstSight(7, 5 * 60 * SECOND), "a second sight inside the window is not new");
		require(seen.firstSight(7, 7 * 60 * SECOND), "after the window the entity counts as new again");
		seen.clear();
		require(seen.firstSight(7, 7 * 60 * SECOND), "clearing forgets every entity");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
