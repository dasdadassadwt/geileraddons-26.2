package geiler.addons.client.dungeon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

public final class CataCalculatorChecks {
	private CataCalculatorChecks() { }

	public static void run() throws IOException {
		CataCalculator.Boosts none = new CataCalculator.Boosts(false, 0, 0, 0.0);
		CataCalculator.Boosts ring = new CataCalculator.Boosts(true, 0, 0, 0.0);
		CataCalculator.Boosts full = new CataCalculator.Boosts(true, 10, 10, 0.0);
		CataCalculator.Floor m7 = CataCalculator.Floor.M7;
		equal(450_000, CataCalculator.xpPerRun(m7, 25, none, false), "M7 at the completion cap");
		equal(495_000, CataCalculator.xpPerRun(m7, 25, ring, false), "ring adds 10% and a bigger step");
		equal(495_000, CataCalculator.xpPerRun(m7, 900, ring, false), "completions stop counting at the cap");
		equal(534_000, CataCalculator.xpPerRun(m7, 25, full, false), "Hecatomb X and Explorer 10");
		equal(720_000, CataCalculator.xpPerRun(m7, 25, new CataCalculator.Boosts(true, 0, 0, 0.5), false), "Derpy");
		equal(630_000, CataCalculator.xpPerRun(m7, 25, none, true), "daily bonus multiplies by 1.4");
		equal(285_000, CataCalculator.xpPerRun(m7, 0, none, false), "no completions yet");
		equal(42_000, CataCalculator.xpPerRun(CataCalculator.Floor.F7, 25, none, false), "F7 caps at 25");
		equal(286, CataCalculator.xpPerRun(CataCalculator.Floor.F1, 75, none, false), "F1 caps at 75");
		equal(10_004, CataCalculator.xpPerRun(CataCalculator.Floor.F6, 50, none, false), "F6 caps at 50");

		equal(0, CataCalculator.runs(m7, 25, 0, ring, 0), "nothing needed");
		equal(1, CataCalculator.runs(m7, 25, 1, ring, 0), "one run");
		equal(2, CataCalculator.runs(m7, 25, 495_001, ring, 0), "rounds up");
		equal(1, CataCalculator.runs(m7, 25, 600_000, ring, 1), "a daily run is bigger");
		equal(2, CataCalculator.runs(m7, 0, 285_000 + 1, none, 0), "completion bonus grows while simulating");
		equal(1_000, CataCalculator.runs(m7, 25, 495_000L * 1_000, ring, 0), "long plans stay exact");

		equal(569_809_640L, CataCalculator.totalXpFor(50), "level 50");
		equal(769_809_640L, CataCalculator.totalXpFor(51), "overflow levels cost 200M");
		equal(50, CataCalculator.totalXpFor(1), "level 1");

		equal(0.5, CataCalculator.mayorBonus("derpy", java.util.List.of()), "Derpy");
		equal(0.59, CataCalculator.mayorBonus("aura", java.util.List.of()), "Aura");
		equal(0.5, CataCalculator.mayorBonus("jerry", java.util.List.of("MOAR SKILLZ")), "the perk counts");
		equal(0.0, CataCalculator.mayorBonus("economist", java.util.List.of("Shopping Spree")), "other mayors");

		JsonObject member = JsonParser.parseString("{\"dungeons\":{\"dungeon_types\":{\"catacombs\":{\"experience\":1000,"
			+ "\"tier_completions\":{\"7\":12}},\"master_catacombs\":{\"tier_completions\":{\"7\":3}}},"
			+ "\"daily_runs\":{\"current_day_stamp\":20000,\"completed_runs_count\":2}},"
			+ "\"attributes\":{\"stacks\":{\"catacombs_explorer\":16}},"
			+ "\"inventory\":{\"bag_contents\":{\"talisman_bag\":{\"data\":\"" + item("CATACOMBS_EXPERT_RING", 0) + "\"}},"
			+ "\"inv_armor\":{\"data\":\"" + item("DIAMOND_HEAD", 7) + "\"},"
			+ "\"ender_chest_contents\":{\"data\":\"" + item("CATACOMBS_EXPERT_RING", 9) + "\"}}}").getAsJsonObject();
		CataCalculator.Profile profile = DungeonStatsServiceCore.INSTANCE.parseCataForChecks(member, 20000L);
		equal(1000, profile.getExperience(), "experience");
		equal(12, profile.completions(CataCalculator.Floor.F7), "normal completions");
		equal(3, profile.completions(m7), "master completions");
		equal(3, profile.getDailyRunsLeft(), "two of five daily runs used");
		equal(Boolean.TRUE, profile.getExpertRing(), "ring in the accessory bag");
		equal(9, profile.getHecatomb(), "highest Hecatomb on any item");
		equal(7, profile.getExplorerLevel(), "16 shards are level 7");
		equal(5, DungeonStatsServiceCore.INSTANCE.parseCataForChecks(member, 20001L).getDailyRunsLeft(), "a new day resets");

		CataCalculator.Profile bare = DungeonStatsServiceCore.INSTANCE.parseCataForChecks(
			JsonParser.parseString("{\"dungeons\":{\"dungeon_types\":{\"catacombs\":{\"experience\":5}}}}").getAsJsonObject(), 1L);
		equal(null, bare.getExpertRing(), "inventory API off is unknown, not no");
		equal(null, bare.getHecatomb(), "Hecatomb unknown");
		equal(null, bare.getExplorerLevel(), "attributes unknown");
	}

	/** One gzipped, base64 inventory with a single item, as the profile API sends it. */
	private static String item(String id, int hecatomb) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			named(out, 10, "");
			named(out, 9, "i");
			out.writeByte(10);
			out.writeInt(1);
			named(out, 10, "tag");
			named(out, 10, "ExtraAttributes");
			named(out, 8, "id");
			out.writeUTF(id);
			if (hecatomb > 0) {
				named(out, 10, "enchantments");
				named(out, 3, "hecatomb");
				out.writeInt(hecatomb);
				out.writeByte(0);
			}
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(0);
		}
		return Base64.getEncoder().encodeToString(bytes.toByteArray());
	}

	private static void named(DataOutputStream out, int type, String name) throws IOException {
		out.writeByte(type);
		out.writeUTF(name);
	}

	private static void equal(Object expected, Object actual, String label) {
		boolean same = expected instanceof Number a && actual instanceof Number b
			? Math.abs(a.doubleValue() - b.doubleValue()) < 1e-9
			: java.util.Objects.equals(expected, actual);
		if (!same) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
