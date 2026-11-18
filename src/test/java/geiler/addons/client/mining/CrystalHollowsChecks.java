package geiler.addons.client.mining;

import java.util.List;

/** Offline checks for the Crystal Hollows structure, chat coordinate and area rules. */
public final class CrystalHollowsChecks {
	private CrystalHollowsChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		CrystalHollowsRules rules = CrystalHollowsRules.INSTANCE;

		require(rules.inBounds(513, 107, 513), "the nucleus is inside the Hollows");
		require(!rules.inBounds(100, 107, 513) && !rules.inBounds(513, 20, 513) && !rules.inBounds(513, 107, 900), "outside the Hollows is rejected");

		int[] plain = rules.parseCoordinates("Jungle Temple: 612 78 411");
		require(plain != null && plain[0] == 612 && plain[1] == 78 && plain[2] == 411, "space separated coordinates");
		int[] commas = rules.parseCoordinates("x: 300, y: 64, z: 701 divan");
		require(commas != null && commas[0] == 300 && commas[1] == 64 && commas[2] == 701, "comma and axis letters");
		require(rules.parseCoordinates("I have 12 apples and 4 pears") == null, "plain numbers are not coordinates");
		require(rules.parseCoordinates("1234 5678 9012") == null, "four digit numbers are not coordinates");

		require(rules.structureIn("jungle temple 612 78 411") == CrystalHollowsRules.Structure.JUNGLE_TEMPLE, "temple by name");
		require(rules.structureIn("mines of divan here 300 64 701") == CrystalHollowsRules.Structure.MINES_OF_DIVAN, "divan by name");
		require(rules.structureIn("lost city 400 50 400") == CrystalHollowsRules.Structure.LOST_PRECURSOR_CITY, "lost city by keyword");
		require(rules.structureIn("khazad dum 400 50 400") == CrystalHollowsRules.Structure.KHAZAD_DUM, "khazad dum without the accent");
		require(rules.structureIn("fairy 400 50 400") == CrystalHollowsRules.Structure.FAIRY_GROTTO, "fairy by keyword");
		require(rules.structureIn("the of is 400 50 400") == null, "filler words name nothing");

		List<String> sidebar = List.of("SKYBLOCK", "Coins: 1", "⏣ Goblin Queen's Den", "Objective");
		require(rules.structureForArea(sidebar) == CrystalHollowsRules.Structure.GOBLIN_QUEENS_DEN, "the sidebar area names the structure");
		require(rules.structureForArea(List.of("⏣ Khazad-dûm")) == CrystalHollowsRules.Structure.KHAZAD_DUM, "the accented sidebar area matches");
		require(rules.structureForArea(List.of("⏣ Magma Fields")) == null, "a plain area has no structure");

		require(CrystalHollowsRules.CHEST_MESSAGE.equals("You uncovered a treasure chest!"), "the chest spawn message");
		require(rules.sameSpot(500, 100, 500, 520, 100, 520), "close points are one spot");
		require(!rules.sameSpot(500, 100, 500, 600, 100, 500), "far points are two spots");

		require(rules.structureForKey("Jungle_Temple") == CrystalHollowsRules.Structure.JUNGLE_TEMPLE, "command keys ignore case");
		require(rules.structureForKey("nowhere") == null, "an unknown key names nothing");
		String shared = rules.shareText("Mines of Divan", 300, 64, 701);
		int[] back = rules.parseCoordinates(shared.substring(shared.indexOf(':') + 1));
		require(back != null && back[0] == 300 && back[1] == 64 && back[2] == 701, "shared text parses back to the coordinates");
		require(rules.structureIn(shared) == CrystalHollowsRules.Structure.MINES_OF_DIVAN, "shared text names the structure");

		require(rules.structureIn("king yolkar 400 80 500") == CrystalHollowsRules.Structure.KING, "king by name");
		require(rules.structureIn("odawa 300 70 300") == CrystalHollowsRules.Structure.ODAWA, "odawa by name");
		require(rules.npcStructure("King Yolkar") == CrystalHollowsRules.Structure.KING, "the king's name tag");
		require(rules.npcStructure("§6Odawa") == CrystalHollowsRules.Structure.ODAWA, "a coloured Odawa name tag");
		require(rules.npcStructure("Xalx") == null, "other NPCs are not structures");
		require(rules.npcStructure(null) == null, "no name names nothing");
		require(rules.mapPixel(202, 100) == 0 && rules.mapPixel(823, 100) == 99, "the Hollows' edges are the map's edges");
		require(Math.abs(rules.mapPixel(513, 100) - 50) <= 1, "the Nucleus is mid-map");
		require(rules.mapPixel(0, 100) == 0 && rules.mapPixel(2000, 100) == 99, "outside points clamp to the edge");
		System.out.println("Crystal Hollows checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
