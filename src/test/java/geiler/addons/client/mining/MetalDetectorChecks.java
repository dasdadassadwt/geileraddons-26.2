package geiler.addons.client.mining;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Offline checks for the metal detector solver rules, with the shipped data file. */
public final class MetalDetectorChecks {
	private MetalDetectorChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() throws RuntimeException {
		require(MetalDetectorRules.parseDistance("5,453/5,078 927 Defense TREASURE: 79.2m") == 79.2, "the action bar distance is read");
		require(MetalDetectorRules.parseDistance("TREASURE: 1,234.5m") == 1234.5, "thousands separators are accepted");
		require(MetalDetectorRules.parseDistance("no treasure here") == null, "a bar without the field has no distance");
		require(MetalDetectorRules.isTreasureFound("You found Flawed Jade Gemstone x4 with your Metal Detector!"), "a find message is recognised");
		require(!MetalDetectorRules.isTreasureFound("You found a chest"), "other finds are not detector finds");

		String data;
		try (var in = MetalDetectorChecks.class.getResourceAsStream("/geileraddons/mining/metal_detector_chests.json")) {
			require(in != null, "the chest data ships as a resource");
			data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (java.io.IOException failure) {
			throw new RuntimeException(failure);
		}
		List<MetalDetectorRules.Pos> offsets = MetalDetectorRules.parseOffsets(data);
		require(offsets.size() == 39, "all 39 chest offsets are read, got " + offsets.size());
		require(MetalDetectorRules.parseOffsets("{\"locations\":[\"1:2\",\"a:b:c\",\"4:5:6\"]}").size() == 1, "malformed offsets are dropped");

		MetalDetectorRules.Pos base = new MetalDetectorRules.Pos(100, 60, 200);
		MetalDetectorRules.Pos spot = new MetalDetectorRules.Pos(100 - offsets.get(0).getX(), 60 - offsets.get(0).getY(), 200 - offsets.get(0).getZ());
		double px = spot.getX() + 3, py = spot.getY(), pz = spot.getZ() + 4;
		double distance = MetalDetectorRules.round1(Math.sqrt(9 + 1 + 16));
		List<MetalDetectorRules.Pos> found = MetalDetectorRules.candidates(base, offsets, null, distance, px, py, pz);
		require(found.contains(spot), "the real spot is a candidate at its own distance");
		require(MetalDetectorRules.candidates(base, offsets, null, 0.5, px, py, pz).isEmpty(), "no spot is half a block away");
		require(!MetalDetectorRules.candidates(base, offsets, spot, distance, px, py, pz).contains(spot), "an ignored spot is skipped");

		// A synthetic world: quartz stairs with a barrier 13 above, and a barrier column stepping +x and +z.
		Set<String> stairs = Set.of("10,20,10");
		Set<String> barriers = Set.of("10,33,10", "11,33,10", "11,33,11", "11,34,11");
		MetalDetectorRules.Pos baseFound = MetalDetectorRules.findBase(12, 20, 12, (x, y, z) -> {
			String key = x + "," + y + "," + z;
			if (stairs.contains(key)) return MetalDetectorRules.Block.QUARTZ_STAIRS;
			if (barriers.contains(key)) return MetalDetectorRules.Block.BARRIER;
			return MetalDetectorRules.Block.OTHER;
		});
		require(baseFound != null && baseFound.equals(new MetalDetectorRules.Pos(11, 34, 11)), "the base is the highest barrier, got " + baseFound);
		require(MetalDetectorRules.findBase(0, 0, 0, (x, y, z) -> MetalDetectorRules.Block.OTHER) == null, "no stairs means no base");
		require(MetalDetectorRules.hasAllTools(List.of("DWARVEN_LAPIS_SWORD", "DWARVEN_EMERALD_HAMMER", "DWARVEN_GOLD_HAMMER", "DWARVEN_DIAMOND_AXE", "X")), "four tools are all tools");
		require(!MetalDetectorRules.hasAllTools(List.of("DWARVEN_LAPIS_SWORD")), "one tool is not all");
		System.out.println("Metal detector checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
