package geiler.addons.client.dungeon;

import java.util.List;

/** Offline checks for reading a Party Finder listing's floor, member rows and open classes from its lore. */
public final class PartyFinderListingChecks {
	private PartyFinderListingChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		List<String> lore = List.of(
			"Dungeon: The Catacombs", "Floor: Floor VII", "Note: need terms", "",
			"Members:", " Steve: Healer (31)", " Alex: Mage (50)", " Empty", "", "Click to join!");
		PartyFinderListing.Reading reading = PartyFinderListing.read(lore);
		require(reading != null, "a listing with a Members header is read");
		require(reading.getFloor() == DungeonFloor.F7, "the floor comes from the Floor line");
		require(reading.count() == 2 && reading.getMembers().size() == 2, "Empty rows are not counted");
		require(reading.getMembers().get(0).getName().equals("Steve")
			&& reading.getMembers().get(0).getDungeonClass() == DungeonClass.HEALER
			&& reading.getMembers().get(0).getLevel() == 31, "a member row gives name, class and level");
		require(reading.openClasses().equals(List.of(DungeonClass.TANK, DungeonClass.BERSERK, DungeonClass.ARCHER)),
			"open classes are the five minus the taken ones, in enum order");

		PartyFinderListing.Reading master = PartyFinderListing.read(
			List.of("Dungeon: Master Mode Catacombs", "Floor: Floor VII", "Members:", " A: Tank (1)"));
		require(master != null && master.getFloor() == DungeonFloor.M7, "Master Mode reads as M7");

		PartyFinderListing.Reading full = PartyFinderListing.read(List.of(
			"Dungeon: The Catacombs", "Floor: V", "Members:",
			" A: Tank (1)", " B: Healer (2)", " C: Mage (3)", " D: Berserk (4)", " E: Archer (5)", " F: Tank (6)"));
		require(full != null && full.count() == PartyFinderListing.MAX_MEMBERS, "at most five member rows are read");
		require(full.openClasses().isEmpty(), "a full party has no open class");

		PartyFinderListing.Reading unknownClass = PartyFinderListing.read(List.of("Members:", " Zed"));
		require(unknownClass != null && unknownClass.count() == 1 && unknownClass.getFloor() == null
			&& unknownClass.getMembers().get(0).getDungeonClass() == null, "a bare name counts as a member with no class or floor");

		require(PartyFinderListing.read(List.of("Dungeon: The Catacombs", "Floor: VII")) == null, "no Members header is not a listing");
		require(PartyFinderListing.read(List.of()) == null, "empty lore is not a listing");

		require(PartyFinderListing.levelColor(12) == 0xFFFF5555, "below 30 is red");
		require(PartyFinderListing.levelColor(30) == 0xFFFFFF55 && PartyFinderListing.levelColor(39) == 0xFFFFFF55, "30 to 39 is yellow");
		require(PartyFinderListing.levelColor(40) == 0xFF55FF55, "40 to 49 is green");
		require(PartyFinderListing.levelColor(50) == 0xFFFFAA00, "50 is gold");
		System.out.println("Party Finder listing checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
