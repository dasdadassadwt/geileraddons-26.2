package geiler.addons.client.mining;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Offline checks for the Crystal Hollows chunk scan: block columns, quarters, duplicates and the per-chunk try budget. */
public final class CrystalHollowsScanChecks {
	private CrystalHollowsScanChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		CrystalHollowsScan scan = CrystalHollowsScan.INSTANCE;

		CrystalHollowsScan.Signature divan = signature(CrystalHollowsRules.Structure.MINES_OF_DIVAN);
		Map<String, String> world = new HashMap<>();
		column(world, 600, 64, 300, divan.getColumn());
		CrystalHollowsScan.BlockLookup lookup = (x, y, z) -> world.get(x + "," + y + "," + z);
		require(scan.matches(divan, 600, 64, 300, lookup), "a full Divan column matches");
		require(!scan.matches(divan, 600, 65, 300, lookup), "a column one block up does not match");
		world.put("600,66,300", "stone");
		require(!scan.matches(divan, 600, 64, 300, lookup), "one wrong block breaks the column");
		int[] mark = scan.markAt(divan, 600, 64, 300);
		require(mark[0] == 600 && mark[1] == 69 && mark[2] == 300, "the marker sits at the signature offset");

		require(scan.quarterAllows(divan, 600, 64, 300), "Divan is in the mithril quarter");
		require(!scan.quarterAllows(divan, 300, 64, 700), "Divan is not in the precursor quarter");
		CrystalHollowsScan.Signature bal = signature(CrystalHollowsRules.Structure.KHAZAD_DUM);
		require(scan.quarterAllows(bal, 400, 50, 400) && !scan.quarterAllows(bal, 400, 100, 400), "Khazad-dum is only below y 80");

		CrystalHollowsScan.Signature grotto = signature(CrystalHollowsRules.Structure.FAIRY_GROTTO);
		require(grotto.getMultiple() && !divan.getMultiple(), "only grottos can repeat");
		require(scan.sameFind(divan, 600, 300, 900, 900), "a single structure is one find anywhere");
		require(scan.sameFind(grotto, 600, 300, 620, 340), "grotto blocks within four chunks are one grotto");
		require(!scan.sameFind(grotto, 600, 300, 800, 300), "a grotto twelve chunks away is another");

		long a = scan.chunkKey(40, 41);
		long b = scan.chunkKey(41, 40);
		require(a != b, "chunk keys differ by axis");
		CrystalHollowsScan.ChunkTries tries = new CrystalHollowsScan.ChunkTries();
		require(tries.claim(a, 0L), "a new chunk is scanned");
		require(!tries.claim(a, 10L), "it is not scanned again at once");
		require(tries.claim(a, CrystalHollowsScan.RETRY_TICKS), "it gets one retry later");
		require(!tries.claim(a, CrystalHollowsScan.RETRY_TICKS * 10), "and no more than the budget");
		require(tries.claim(b, 5L), "another chunk has its own budget");
		tries.clear();
		require(tries.claim(a, 0L), "clearing starts a new lobby");

		require(scan.chunkInHollows(40, 40) && !scan.chunkInHollows(2, 2) && !scan.chunkInHollows(60, 40), "only chunks overlapping the Hollows are scanned");

		List<String> firsts = scan.firstBlocks();
		require(firsts.contains("stone") && firsts.contains("quartz_pillar") && firsts.contains("magenta_stained_glass"), "first blocks are listed for the cheap filter");

		System.out.println("Crystal Hollows scan checks passed");
	}

	private static CrystalHollowsScan.Signature signature(CrystalHollowsRules.Structure structure) {
		for (CrystalHollowsScan.Signature s : CrystalHollowsScan.INSTANCE.getSignatures()) {
			if (s.getStructure() == structure) return s;
		}
		throw new AssertionError("no signature for " + structure);
	}

	private static void column(Map<String, String> world, int x, int y, int z, List<String> column) {
		for (int i = 0; i < column.size(); i++) {
			if (column.get(i) != null) world.put(x + "," + (y + i) + "," + z, column.get(i));
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
