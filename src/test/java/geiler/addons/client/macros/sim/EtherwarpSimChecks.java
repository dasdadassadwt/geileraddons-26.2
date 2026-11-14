package geiler.addons.client.macros.sim;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;

/** Covers the Etherwarp sim: range, the voxel ray, the landing rules and the module's settings. */
public final class EtherwarpSimChecks {
	private EtherwarpSimChecks() { }

	/** A flat world: solid ground up to and including y = 0, everything above is air, plus whatever a check sets. */
	private static final class World implements EtherwarpMath.Probe {
		private final java.util.Map<Long, Integer> set = new java.util.HashMap<>();

		void put(int x, int y, int z, int flags) {
			set.put(key(x, y, z), flags);
		}

		@Override
		public int flags(int x, int y, int z) {
			Integer set = this.set.get(key(x, y, z));
			if (set != null) return set;
			return y <= 0 ? EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT) : EtherwarpMath.PASSABLE;
		}

		private static long key(int x, int y, int z) {
			return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
		}
	}

	public static void run() {
		checkRange();
		checkLanding();
		checkRay();
		checkInvalid();
		checkModule();
	}

	private static void checkRange() {
		check(EtherwarpMath.range(0) == 57.0, "base range is 57 blocks");
		check(EtherwarpMath.range(4) == 61.0, "four tuners reach 61 blocks");
		check(EtherwarpMath.range(-3) == 57.0 && EtherwarpMath.range(99) == 61.0, "the tuner count is clamped to 0 to 4");
	}

	private static void checkLanding() {
		World world = new World();
		// Looking straight down at the ground from 5 blocks up: lands on the block centre, 1.05 above its top.
		EtherwarpMath.Result hit = EtherwarpMath.trace(world, 0.5, 4.73, 0.5, 0.0, -1.0, 0.0, 57.0);
		check(hit.getValid() && hit.getBlockY() == 0, "straight down hits the ground block");
		check(hit.getLandX() == 0.5 && near(hit.getLandY(), 1.05) && hit.getLandZ() == 0.5,
			"the landing is the block centre and 1.05 above the surface (got " + hit.getLandY() + ")");
	}

	private static void checkRay() {
		World world = new World();
		world.put(10, 1, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		// Eye in cell y = 1 looking along +x and a little down; hits the pillar's side only if it is in the way.
		EtherwarpMath.Result level = EtherwarpMath.trace(world, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, 57.0);
		check(level.getValid() && level.getBlockX() == 10 && level.getBlockY() == 1, "a level ray hits the pillar at x = 10");
		check(near(level.getLandY(), 2.05), "landing on a one-block pillar is at 2.05");
		// A ray over the top of everything runs out of range: nothing to land on.
		EtherwarpMath.Result sky = EtherwarpMath.trace(world, 0.5, 30.0, 0.5, 0.0, 1.0, 0.0, 57.0);
		check(!sky.getValid(), "a ray into the sky finds nothing");
		// Range: a wall at distance 60 is out of reach with no tuners and inside with four.
		World far = new World();
		far.put(60, 1, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		check(!EtherwarpMath.trace(far, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, EtherwarpMath.range(0)).getValid(), "60 blocks is out of base range");
		check(EtherwarpMath.trace(far, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, EtherwarpMath.range(4)).getBlockX() == 60, "60 blocks is in range with 4 tuners");
	}

	private static void checkInvalid() {
		World world = new World();
		// A block with a solid block directly above the landing cell: no room, no teleport.
		world.put(3, 1, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		world.put(3, 2, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		world.put(3, 3, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		EtherwarpMath.Result hit = EtherwarpMath.trace(world, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, 57.0);
		check(!hit.getValid() && hit.getBlockX() == 3, "a ray that hits a pillar with a block over the landing cells is not valid");
		// A passable block (flowers, carpet-free) is skipped by the ray.
		World passable = new World();
		passable.put(2, 1, 0, EtherwarpMath.PASSABLE);
		passable.put(5, 1, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		EtherwarpMath.Result through = EtherwarpMath.trace(passable, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, 57.0);
		check(through.getBlockX() == 5, "passable blocks do not stop the ray");
		// A feet-blocking block (ladder, skull) at the landing cell is passable for the ray but blocks standing there.
		World ladder = new World();
		ladder.put(4, 1, 0, EtherwarpMath.SOLID | (1 << EtherwarpMath.STAND_SHIFT));
		ladder.put(4, 2, 0, EtherwarpMath.PASSABLE | EtherwarpMath.OBSTRUCTS);
		check(!EtherwarpMath.trace(ladder, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, 57.0).getValid(),
			"a feet-blocking block above the landing block makes it invalid");
		// A taller block raises the landing: a 1.5-high fence-like block lands at y + 2.05.
		World fence = new World();
		fence.put(6, 1, 0, EtherwarpMath.SOLID | (2 << EtherwarpMath.STAND_SHIFT));
		EtherwarpMath.Result tall = EtherwarpMath.trace(fence, 0.5, 1.5, 0.5, 1.0, 0.0, 0.0, 57.0);
		check(tall.getValid() && near(tall.getLandY(), 3.05), "a block with collision up to 1.5 lands one block higher (got " + tall.getLandY() + ")");
	}

	private static void checkModule() {
		Module module = null;
		for (Module candidate : ModuleManager.modules(Category.SIM)) {
			if (candidate.name().equals("Etherwarp Sim")) module = candidate;
		}
		check(module != null, "Etherwarp Sim is registered in the Sim category");
		check(!module.defaultEnabled(), "Etherwarp Sim starts off");
		NumberSetting tuners = module.numberSettings().get(0);
		check(module.numberSettings().size() == 1 && tuners.min() == 0.0f && tuners.max() == 4.0f && tuners.value() == 0.0f
			&& tuners.isInteger(), "Etherwarp Sim has one whole-number Transmission Tuners setting from 0 to 4, default 0");
	}

	private static boolean near(double actual, double expected) {
		return Math.abs(actual - expected) <= 1.0E-9;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
