package geiler.addons.client.dungeon;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Offline checks for Bigger Secret Hitboxes: which skulls count, and the button shape that grows only sideways. */
public final class SecretHitboxChecks {
	private SecretHitboxChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkSkulls();
		checkButtonNormals();
		checkButtonShapes();
	}

	private static void checkSkulls() {
		require(SecretHitboxRules.skullKind("e0f3e929-869e-3dca-9504-54c666ee6f23") == SecretHitboxRules.Kind.ESSENCE,
			"the wither essence skull is recognised");
		require(SecretHitboxRules.skullKind("FED95410-ABA1-39DF-9B95-1D4F361EB66E") == SecretHitboxRules.Kind.REDSTONE_KEY,
			"the redstone key skull is recognised in any case");
		String essenceTextures = java.util.Base64.getEncoder().encodeToString(
			"{\"timestamp\":1,\"profileId\":\"e0f3e929869e3dca950454c666ee6f23\",\"profileName\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		require(SecretHitboxRules.skullKindFromTextures(essenceTextures) == SecretHitboxRules.Kind.ESSENCE,
			"a head without its id is still found by the profile id in its texture JSON");
		require(SecretHitboxRules.skullKindFromTextures("not base64!") == null
			&& SecretHitboxRules.skullKindFromTextures(null) == null, "bad texture data keeps the vanilla hitbox");
		require(SecretHitboxRules.skullKind("00000000-0000-0000-0000-000000000000") == null
			&& SecretHitboxRules.skullKind(null) == null, "decorative heads keep their vanilla hitbox");
	}

	private static void checkButtonNormals() {
		require(SecretHitboxRules.buttonNormal(AttachFace.WALL, Direction.NORTH) == Direction.Axis.Z
			&& SecretHitboxRules.buttonNormal(AttachFace.WALL, Direction.EAST) == Direction.Axis.X
			&& SecretHitboxRules.buttonNormal(AttachFace.FLOOR, Direction.EAST) == Direction.Axis.Y
			&& SecretHitboxRules.buttonNormal(AttachFace.CEILING, Direction.SOUTH) == Direction.Axis.Y,
			"a wall button sticks out along its facing, floor and ceiling buttons along Y");
	}

	private static void checkButtonShapes() {
		// A wall button facing north sits against the block's south side: 2 px deep, z 14..16.
		VoxelShape wall = Shapes.box(5 / 16.0, 6 / 16.0, 14 / 16.0, 11 / 16.0, 10 / 16.0, 1.0);
		AABB grown = SecretHitboxRules.buttonShape(wall, Direction.Axis.Z).bounds();
		require(near(grown.minX, 0) && near(grown.maxX, 1) && near(grown.minY, 0) && near(grown.maxY, 1),
			"a wall button fills its face sideways and up/down");
		require(near(grown.minZ, 14 / 16.0) && near(grown.maxZ, 1), "a wall button keeps its depth (not forward)");

		VoxelShape floor = Shapes.box(5 / 16.0, 0, 6 / 16.0, 11 / 16.0, 2 / 16.0, 10 / 16.0);
		AABB floorGrown = SecretHitboxRules.buttonShape(floor, Direction.Axis.Y).bounds();
		require(near(floorGrown.minX, 0) && near(floorGrown.maxZ, 1) && near(floorGrown.maxY, 2 / 16.0),
			"a floor button fills the floor face and keeps its height");
		require(SecretHitboxRules.buttonShape(Shapes.empty(), Direction.Axis.Y).isEmpty(), "an empty shape stays empty");
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) < 1e-9;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
