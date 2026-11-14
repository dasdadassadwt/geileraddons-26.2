package geiler.addons.client.macros.area;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Parity checks of the gate: the original's arithmetic, copied as written, against the port for seeded random gates,
 * margins, bounds and points.
 */
public final class GateAreaChecks {
	private static final int PLANNING_BUFFER = 1;

	private GateAreaChecks() { }

	/** The original gate, reduced to its fields and the methods that need no world. */
	private static final class Original {
		final boolean axisX;
		final int plane;
		final int lowEnd;
		final int highEnd;
		final int gateY;
		final int margin;
		final int[] boundsMin;
		final int[] boundsMax;

		Original(int[] a, int[] b, int margin, int[] boundsA, int[] boundsB) {
			if (a[0] == b[0]) {
				this.axisX = true;
				this.plane = a[0];
				this.lowEnd = Math.min(a[2], b[2]);
				this.highEnd = Math.max(a[2], b[2]);
			} else if (a[2] == b[2]) {
				this.axisX = false;
				this.plane = a[2];
				this.lowEnd = Math.min(a[0], b[0]);
				this.highEnd = Math.max(a[0], b[0]);
			} else {
				throw new IllegalArgumentException("The gate must run along the X or the Z axis: one coordinate has to be equal at both ends");
			}
			int[] min = null;
			int[] max = null;
			if (boundsA != null) {
				min = new int[3];
				max = new int[3];
				for (int i = 0; i < 3; i++) {
					min[i] = Math.min(boundsA[i], boundsB[i]);
					max[i] = Math.max(boundsA[i], boundsB[i]);
				}
			}
			this.gateY = Math.min(a[1], b[1]);
			this.margin = margin;
			this.boundsMin = min;
			this.boundsMax = max;
		}

		boolean inSlab(int x, int z, int reach) {
			int across = this.axisX ? x : z;
			int along = this.axisX ? z : x;
			return Math.abs(across - this.plane) <= reach && along >= this.lowEnd - reach && along <= this.highEnd + reach;
		}

		boolean insideBounds(int x, int y, int z) {
			return this.boundsMin == null
				|| (x >= this.boundsMin[0] && x <= this.boundsMax[0]
					&& y >= this.boundsMin[1] && y <= this.boundsMax[1]
					&& z >= this.boundsMin[2] && z <= this.boundsMax[2]);
		}

		boolean contains(int x, int y, int z) {
			return !this.inSlab(x, z, this.margin + PLANNING_BUFFER) && this.insideBounds(x, y, z);
		}

		boolean containsPoint(Vec3 position) {
			return this.contains(Mth.floor(position.x), Mth.floor(position.y + 1.0E-4), Mth.floor(position.z));
		}

		boolean breached(Vec3 position) {
			int x = Mth.floor(position.x);
			int y = Mth.floor(position.y + 1.0E-4);
			int z = Mth.floor(position.z);
			return this.inSlab(x, z, this.margin) || !this.insideBounds(x, y, z);
		}

		boolean throughGate(Vec3 from, Vec3 to) {
			double middle = this.plane + 0.5;
			double fromAcross = (this.axisX ? from.x : from.z) - middle;
			double toAcross = (this.axisX ? to.x : to.z) - middle;
			if (fromAcross * toAcross >= 0.0) {
				return false;
			}
			double t = fromAcross / (fromAcross - toAcross);
			double along = this.axisX ? from.z + t * (to.z - from.z) : from.x + t * (to.x - from.x);
			int reach = this.margin + PLANNING_BUFFER;
			return along >= this.lowEnd - reach && along <= this.highEnd + 1 + reach;
		}

		AABB gateBox() {
			return this.axisX
				? new AABB(this.plane, this.gateY, this.lowEnd, this.plane + 1, this.gateY + 3.0, this.highEnd + 1)
				: new AABB(this.lowEnd, this.gateY, this.plane, this.highEnd + 1, this.gateY + 3.0, this.plane + 1);
		}

		String describe() {
			return "gate " + (this.axisX
				? "x=" + this.plane + " z=" + this.lowEnd + ".." + this.highEnd
				: "z=" + this.plane + " x=" + this.lowEnd + ".." + this.highEnd) + ", margin " + this.margin;
		}
	}

	public static void run() {
		checkDefaultGate();
		checkCrookedGateRefused();
		checkRandomParity();
		System.out.println("Gate area checks passed.");
	}

	private static void checkDefaultGate() {
		GateArea gate = GateArea.of(new int[] {-120, 206, -63}, new int[] {-120, 206, -56}, 1);
		check(gate.describe().equals("gate x=-120 z=-63..-56, margin 1"), "the default gate describes itself as the original does");
		check(!gate.contains(-120, 206, -60), "the gate row is shut");
		check(!gate.contains(-118, 206, -60), "the margin and the planning block are shut");
		check(gate.contains(-117, 206, -60), "three blocks off the gate is open with margin 1");
		check(gate.breached(new Vec3(-118.5, 206.0, -60.0)), "inside the margin is the hard limit");
		check(!gate.breached(new Vec3(-117.5, 206.0, -60.0)), "the planning block is not the hard limit");
		check(gate.throughGate(new Vec3(-110.0, 206.0, -60.0), new Vec3(-130.0, 206.0, -60.0)), "a line across the gate goes through it");
		check(!gate.throughGate(new Vec3(-110.0, 206.0, -60.0), new Vec3(-111.0, 206.0, -20.0)), "a line on one side does not");
		check(gate.equals(GateArea.of(new int[] {-120, 206, -56}, new int[] {-120, 206, -63}, 1)), "the ends may be given either way round");
	}

	private static void checkCrookedGateRefused() {
		try {
			GateArea.of(new int[] {1, 2, 3}, new int[] {4, 2, 6}, 1);
			throw new AssertionError("a gate that is not a straight row must be refused");
		} catch (IllegalArgumentException expected) {
			check("The gate must run along the X or the Z axis: one coordinate has to be equal at both ends".equals(expected.getMessage()),
				"the refusal keeps the original's text");
		}
	}

	private static void checkRandomParity() {
		Random random = new Random(20261113L);
		for (int gateIndex = 0; gateIndex < 400; gateIndex++) {
			int[] a = {random.nextInt(81) - 40, random.nextInt(41) - 20, random.nextInt(81) - 40};
			int[] b = {random.nextInt(81) - 40, random.nextInt(41) - 20, random.nextInt(81) - 40};
			if (random.nextBoolean()) b[0] = a[0]; else b[2] = a[2];
			int margin = random.nextInt(4);
			int[] boundsA = null;
			int[] boundsB = null;
			if (random.nextInt(3) == 0) {
				boundsA = new int[] {random.nextInt(121) - 60, random.nextInt(61) - 30, random.nextInt(121) - 60};
				boundsB = new int[] {random.nextInt(121) - 60, random.nextInt(61) - 30, random.nextInt(121) - 60};
			}
			Original original = new Original(a, b, margin, boundsA, boundsB);
			GateArea port = GateArea.of(a, b, margin, boundsA == null ? null : new GateBounds(boundsA, boundsB));

			check(port.describe().equals(original.describe()), "describe matches");
			check(port.gateBox().equals(original.gateBox()), "the gate box matches");
			check(port.slabBox().equals(original.gateBox().inflate(margin, 0.0, margin)), "the slab box matches");
			for (int i = 0; i < 200; i++) {
				int x = random.nextInt(141) - 70;
				int y = random.nextInt(81) - 40;
				int z = random.nextInt(141) - 70;
				check(port.contains(x, y, z) == original.contains(x, y, z), "contains matches at " + x + " " + y + " " + z);
				Vec3 from = point(random);
				Vec3 to = point(random);
				check(port.containsPoint(from) == original.containsPoint(from), "containsPoint matches at " + from);
				check(port.breached(from) == original.breached(from), "breached matches at " + from);
				check(port.throughGate(from, to) == original.throughGate(from, to), "throughGate matches from " + from + " to " + to);
			}
		}
	}

	private static Vec3 point(Random random) {
		// Whole and half coordinates are the edges the floor() calls turn on, so some points sit exactly on them.
		double x = random.nextInt(4) == 0 ? random.nextInt(141) - 70 : random.nextDouble() * 140.0 - 70.0;
		double y = random.nextInt(4) == 0 ? random.nextInt(81) - 40 : random.nextDouble() * 80.0 - 40.0;
		double z = random.nextInt(4) == 0 ? random.nextInt(141) - 70 + 0.5 : random.nextDouble() * 140.0 - 70.0;
		return new Vec3(x, y, z);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
