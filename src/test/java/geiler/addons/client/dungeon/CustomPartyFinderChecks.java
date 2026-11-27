package geiler.addons.client.dungeon;

import java.util.ArrayList;
import java.util.List;

public final class CustomPartyFinderChecks {
	private CustomPartyFinderChecks() { }

	private static final List<String> LORE = List.of(
		"Dungeon: The Catacombs",
		"Floor: Floor VII",
		"Note: s+ fast",
		"Class Level Required: 40",
		"Dungeon Level Required: 35",
		"",
		"Members: ",
		" Steve: Mage (45)",
		" Alex: Berserk (42)",
		" Empty",
		" Empty",
		" Empty",
		"",
		"Click to join!");

	public static void run() {
		equal(Boolean.TRUE, PartyFinderEntries.isDungeonMenu("Catacombs Gate", null), "the Catacombs Gate gets the themed view");
		equal(Boolean.TRUE, PartyFinderEntries.isDungeonMenu("Whatever", "To Party Finder"), "a page leading back to the Party Finder");
		equal(Boolean.FALSE, PartyFinderEntries.isDungeonMenu("Party Finder", null), "the finder itself has its own view");
		equal(Boolean.FALSE, PartyFinderEntries.isDungeonMenu("SkyBlock Menu", "To SkyBlock Menu"), "other menus stay vanilla");
		equal("F7", PartyFinderEntries.searchedFloor(java.util.List.of("Dungeon: The Catacombs", "Floor: Floor VII")), "searched floor");
		equal("M7", PartyFinderEntries.searchedFloor(java.util.List.of("Dungeon: Master Mode The Catacombs", "Floor: Floor 7")), "master floor");
		equal("F7", PartyFinderEntries.shortLabel("The Catacombs - Floor VII"), "gate floor item");
		equal("M1", PartyFinderEntries.shortLabel("Master Mode The Catacombs - Floor I"), "gate master floor item");
		equal("E", PartyFinderEntries.shortLabel("The Catacombs - Entrance"), "entrance");
		equal("F3", PartyFinderEntries.shortLabel("Floor III"), "floor picker item");
		equal("Master Mode", PartyFinderEntries.shortLabel("Master Mode"), "other gate buttons keep their name");
		equal("Catacombs Profile", PartyFinderEntries.shortLabel("Catacombs Profile"), "no floor, no change");
		equal("M7", PartyFinderEntries.shortFloor("Master Mode The Catacombs Floor VII"), "party list floor");
		equal("", PartyFinderEntries.searchedFloor(java.util.List.of("Click to open!")), "no floor in the lore");
		PartyFinderEntries.Party party = PartyFinderEntries.party(12, "Steve's Party", LORE);
		equal(12, party.getSlot(), "slot");
		equal("Steve", party.getLeader(), "leader from the title");
		equal("s+ fast", party.getNote(), "note");
		equal("Floor VII", party.getFloor(), "floor");
		equal("The Catacombs", party.getDungeon(), "dungeon");
		equal(40, party.getClassRequired(), "class requirement");
		equal(35, party.getDungeonRequired(), "dungeon requirement");
		equal(2, party.getMembers().size(), "two members");
		equal("Alex", party.getMembers().get(1).getName(), "second member");
		equal(Boolean.TRUE, party.getJoinable(), "click to join");
		equal(null, party.getBlocked(), "no reason");
		equal(3, party.openClasses().size(), "three classes open");

		List<String> blockedLore = new ArrayList<>(LORE);
		blockedLore.set(blockedLore.size() - 1, "Requires a Class at Level 40!");
		PartyFinderEntries.Party blocked = PartyFinderEntries.party(13, "Alex's Party", blockedLore);
		equal(Boolean.FALSE, blocked.getJoinable(), "red line blocks");
		equal("Requires a Class at Level 40!", blocked.getBlocked(), "reason kept");

		List<PartyFinderEntries.SlotInfo> slots = new ArrayList<>();
		for (int i = 0; i < 54; i++) slots.add(new PartyFinderEntries.SlotInfo(i, "", "", List.of()));
		slots.set(10, new PartyFinderEntries.SlotInfo(10, "minecraft:player_head", "Steve's Party", LORE));
		slots.set(11, new PartyFinderEntries.SlotInfo(11, "minecraft:player_head", "Alex's Party", blockedLore));
		slots.set(45, new PartyFinderEntries.SlotInfo(45, "minecraft:arrow", "Previous Page", List.of()));
		slots.set(53, new PartyFinderEntries.SlotInfo(53, "minecraft:arrow", "Next Page", List.of()));
		slots.set(46, new PartyFinderEntries.SlotInfo(46, "minecraft:emerald_block", "Refresh", List.of()));
		slots.set(48, new PartyFinderEntries.SlotInfo(48, "minecraft:nether_star", "Search Settings", List.of()));
		slots.set(50, new PartyFinderEntries.SlotInfo(50, "minecraft:redstone_block", "Create Party", List.of()));
		PartyFinderEntries.Page page = PartyFinderEntries.page(slots, 6);
		equal(2, page.getParties().size(), "two parties");
		equal(45, page.getPrevious(), "previous");
		equal(53, page.getNext(), "next");
		equal(46, page.getRefresh(), "refresh");
		equal(48, page.getSettings(), "settings");
		equal(50, page.getCreate(), "create");
		equal(-1, page.getDelist(), "no delist");
		equal(Boolean.FALSE, page.getEmpty(), "not empty");

		slots.set(22, new PartyFinderEntries.SlotInfo(22, "minecraft:bedrock", "No Parties!", List.of()));
		equal(Boolean.TRUE, PartyFinderEntries.page(slots, 6).getEmpty(), "bedrock means no parties");

		equal(Boolean.TRUE, PartyFinderEntries.matches(party, "alex"), "search finds a member");
		equal(Boolean.TRUE, PartyFinderEntries.matches(party, "S+ FAST"), "search finds the note");
		equal(Boolean.FALSE, PartyFinderEntries.matches(party, "healer"), "no match");
		equal(Boolean.TRUE, PartyFinderEntries.matches(party, ""), "empty search matches");

		equal(Boolean.TRUE, PartyFinderEntries.isFinderTitle("Party Finder"), "title");
		equal(Boolean.FALSE, PartyFinderEntries.isFinderTitle("Search Settings"), "other menus stay vanilla");
	}

	private static void equal(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
