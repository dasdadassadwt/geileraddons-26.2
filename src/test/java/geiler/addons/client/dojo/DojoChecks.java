package geiler.addons.client.dojo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Offline checks for the Skyblocker Dojo port: test detection, zombie tags, Mastery timing, Control and Stamina. */
public final class DojoChecks {
	private DojoChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		DojoRules r = DojoRules.INSTANCE;
		require(DojoRules.testFrom("Test of Force OBJECTIVES") == DojoRules.Test.FORCE
			&& DojoRules.testFrom("        Test of Tenacity OBJECTIVES") == DojoRules.Test.TENACITY, "test announcements are read, trimmed or not");
		require(DojoRules.testFrom("Test of Nonsense OBJECTIVES") == DojoRules.Test.NONE && DojoRules.testFrom("hello") == null,
			"unknown tests and other lines start nothing");
		require(DojoRules.finished("CHALLENGE COMPLETED") && DojoRules.finished("   CHALLENGE FAILED") && !DojoRules.finished("CHALLENGE"),
			"the end lines finish a test");
		require(DojoRules.forceNegative("-12") && !DojoRules.forceNegative("+5"), "negative Force zombies are marked");
		require(DojoRules.disciplineMatches("IRON_SWORD", " Iron") && !DojoRules.disciplineMatches("IRON_SWORD", " Gold")
			&& !DojoRules.disciplineMatches("STICK", " Iron"), "Discipline matches the held sword's material only");
		require(DojoRules.disciplineColor("DIAMOND_SWORD") == 0xFF00FFFF && DojoRules.disciplineColor("STICK") == 0, "sword colors");
		require(DojoRules.masteryEndMs(1_000L, 100L, 60.0) == 1_000L + 6_550L - 100L - 1_000L,
			"Mastery subtracts ping and a 60 blocks/s arrow flight");
		Vec3 offset = DojoRules.controlOffset(new Vec3(0, 0, 0), new Vec3(1, 1, 0), 0L);
		require(Math.abs(offset.x - 1.15) < 1e-9 && Math.abs(offset.y - 0.115) < 1e-9, "Control scales one update ahead, vertical damped");

		// A 15-block wall along x at y=10..15 with a 1-wide, 2-high hole at x=5, y=11..12.
		List<BlockPos> bottom = new ArrayList<>();
		for (int x = 0; x < 15; x++) bottom.add(new BlockPos(x, 10, 0));
		List<AABB> walls = DojoRules.walls(bottom);
		require(walls.size() == 1 && walls.get(0).getXsize() == 15 && walls.get(0).getYsize() == 6, "a floor line of 13+ blocks is a wall");
		Set<BlockPos> air = new HashSet<>();
		air.add(new BlockPos(5, 11, 0));
		air.add(new BlockPos(5, 12, 0));
		List<AABB> holes = DojoRules.holes(walls.get(0), (x, y, z) -> z != 0 || y < 10 || y > 15 || x < 0 || x > 14 || air.contains(new BlockPos(x, y, z)));
		require(holes.size() == 1 && holes.get(0).minX == 5 && holes.get(0).minY == 11 && holes.get(0).maxY == 13, "the hole is found: " + holes);
		AABB hole = holes.get(0);
		require(DojoRules.direction(hole, List.of(hole)) == DojoRules.HoleDirection.UNCHANGED
			&& DojoRules.direction(hole, List.of(hole.move(1, 0, 0))) == DojoRules.HoleDirection.POSITIVE_X
			&& DojoRules.direction(hole, List.of()) == DojoRules.HoleDirection.NEW, "hole movement is read against the last scan");
		require(DojoRules.incoming(hole, DojoRules.HoleDirection.POSITIVE_X, 0, 0) && !DojoRules.incoming(hole, DojoRules.HoleDirection.POSITIVE_X, 10, 0),
			"holes are incoming only from the side they approach");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
