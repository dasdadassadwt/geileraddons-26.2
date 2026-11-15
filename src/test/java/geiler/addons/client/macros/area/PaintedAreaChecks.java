package geiler.addons.client.macros.area;

import geiler.addons.client.macros.config.AreaKillerConfig;

/**
 * Painted areas: where the macro may walk, set by brush. An area with allowed or preferred ground only lets the macro stand on that
 * ground; forbidden ground and walls are shut whatever else is painted; preferred ground is cheaper to walk. An area with no gate is a
 * region with no wall of its own.
 */
public final class PaintedAreaChecks {
	private PaintedAreaChecks() { }

	public static void run() {
		checkAllowedOnly();
		checkForbiddenAlone();
		checkWallsShutTheColumn();
		checkPreferredCost();
		checkEraseAndOverwrite();
		checkRoundTrip();
		checkGateIsOptional();
		checkRest();
		checkOldDefaultGate();
	}

	private static void checkRest() {
		PaintedArea area = new PaintedArea("rest");
		area.put(4, 64, 4, Paint.REST, 0);
		check(area.restCount() == 1 && area.allows(4, 64, 4), "a rest cell is walkable");
		check(area.restrictive() && !area.allows(9, 64, 9), "rest paint counts as allowed ground: the macro stands on painted ground only");
		check(area.preferredCount() == 1 && area.costFactor(4, 64, 4) < 1.0 && area.minCostFactor() == area.costFactor(4, 64, 4), "and as preferred ground: it is cheaper");
		area.put(5, 64, 4, Paint.REST, 0);
		int[] first = area.restCell(0);
		int[] second = area.restCell(1);
		check(first != null && second != null && first[0] != second[0], "the rest cells can be picked by index");
		check(area.restCell(2) == null, "past the last there is none");
		PaintedArea read = PaintedArea.parse("rest", area.serialize());
		check(read != null && read.restCount() == 2 && read.typeAt(4, 64, 4) == Paint.REST, "rest cells survive the file");
		area.put(4, 64, 4, Paint.ALLOWED, 0);
		check(area.restCount() == 1 && area.restrictive(), "painting over one takes it out of the rest cells");
		area.erase(5, 64, 4);
		check(area.restCount() == 0, "so does erasing");
		check(area.preferredCount() == 0 && area.walkableCount() == 1, "and the rest cell is counted out of the preferred and allowed ground");
	}

	private static void checkAllowedOnly() {
		PaintedArea area = new PaintedArea("test");
		check(area.allows(0, 64, 0), "an area with nothing painted shuts nothing");
		area.put(5, 64, 5, Paint.ALLOWED, 0);
		check(area.allows(5, 64, 5), "painted allowed ground is walkable");
		check(!area.allows(6, 64, 5), "once ground is painted allowed, the rest of the world is out");
		check(!area.allows(5, 65, 5), "and so is another height of the same column that was not painted");
	}

	private static void checkForbiddenAlone() {
		PaintedArea area = new PaintedArea("test");
		area.put(1, 10, 1, Paint.FORBIDDEN, 0);
		check(!area.allows(1, 10, 1), "forbidden ground is shut");
		check(area.allows(2, 10, 1), "and with no allowed paint the rest is open");
		area.put(2, 10, 1, Paint.ALLOWED, 0);
		check(area.allows(2, 10, 1) && !area.allows(1, 10, 1), "next to allowed paint it stays shut");
	}

	private static void checkWallsShutTheColumn() {
		PaintedArea area = new PaintedArea("test");
		area.put(0, 10, 0, Paint.ALLOWED, 0);
		area.put(0, 20, 0, Paint.ALLOWED, 0);
		check(area.allows(0, 10, 0) && area.allows(0, 20, 0), "two floors painted allowed");
		area.put(0, 10, 0, Paint.WALL, 0);
		check(!area.allows(0, 10, 0), "a wall is shut");
		check(!area.allows(0, 20, 0), "at every height of its column");
		area.erase(0, 10, 0);
		check(area.allows(0, 20, 0), "taking the wall away opens the column again");
	}

	private static void checkPreferredCost() {
		PaintedArea area = new PaintedArea("test");
		area.put(0, 5, 0, Paint.PREFERRED, 0);
		area.put(1, 5, 0, Paint.ALLOWED, 0);
		check(area.costFactor(0, 5, 0) < 1.0 && area.costFactor(0, 5, 0) > 0.0, "preferred ground is cheaper to walk");
		check(area.costFactor(1, 5, 0) == 1.0 && area.costFactor(9, 5, 9) == 1.0, "allowed and unpainted ground cost the plain amount");
		check(area.allows(0, 5, 0), "and preferred ground is walkable");
		check(area.preferredCount() == 1 && area.minCostFactor() == area.costFactor(0, 5, 0), "the cheapest step is the preferred one");
		area.put(0, 5, 0, Paint.ALLOWED, 0);
		check(area.preferredCount() == 0 && area.minCostFactor() == 1.0, "with no preferred ground left the cheapest step is the plain one");
		area.put(0, 5, 0, Paint.PREFERRED, 0);
		area.erase(0, 5, 0);
		check(area.preferredCount() == 0, "erasing preferred ground counts it out");
		area.put(2, 5, 0, Paint.PREFERRED, 0);
		area.clear();
		check(area.preferredCount() == 0 && area.minCostFactor() == 1.0, "clearing counts it out");
	}

	private static void checkEraseAndOverwrite() {
		PaintedArea area = new PaintedArea("test");
		int before = area.revision();
		area.put(3, 3, 3, Paint.ALLOWED, 0);
		check(area.revision() > before, "painting changes the revision, so a map of the ground made for the old one is not reused");
		area.put(3, 3, 3, Paint.FORBIDDEN, 0);
		check(!area.allows(3, 3, 3) && area.size() == 1, "painting over a cell replaces it");
		int mid = area.revision();
		area.put(3, 3, 3, Paint.FORBIDDEN, 0);
		check(area.revision() == mid, "painting the same thing again changes nothing");
		area.erase(3, 3, 3);
		check(area.size() == 0 && area.allows(3, 3, 3), "erased is unpainted");
	}

	private static void checkRoundTrip() {
		PaintedArea area = new PaintedArea("test");
		area.put(-120, 205, 60, Paint.ALLOWED, 0);
		area.put(-121, 205, 60, Paint.PREFERRED, 4);
		area.put(-122, 90, -3, Paint.WALL, 0);
		area.put(7, -60, 7, Paint.FORBIDDEN, 8);
		PaintedArea read = PaintedArea.parse("test", area.serialize());
		check(read != null && read.size() == 4, "all cells come back");
		check(read.typeAt(-120, 205, 60) == Paint.ALLOWED && read.typeAt(-121, 205, 60) == Paint.PREFERRED, "with their types");
		check(read.typeAt(-122, 90, -3) == Paint.WALL && read.typeAt(7, -60, 7) == Paint.FORBIDDEN, "negative coordinates too");
		check(read.heightOffset(-121, 205, 60) == 4 && read.heightOffset(7, -60, 7) == 8, "and the height of the floor in each");
		check(!read.allows(-122, 40, -3), "and the wall's column");
		check(PaintedArea.parse("x", "not an area file") == null, "a file that is not an area is refused");
		PaintedArea tolerant = PaintedArea.parse("x", "geileraddons-area 1\n1 2 3 A 0\nnonsense\n4 5 6 Q 0\n7 8 9 P 3\n");
		check(tolerant != null && tolerant.size() == 2, "bad lines are skipped, good ones kept (" + (tolerant == null ? -1 : tolerant.size()) + ")");
	}

	private static void checkGateIsOptional() {
		GateArea none = GateArea.create(new int[0], new int[0], 1, null, null);
		check(!none.hasGate(), "no gate coordinates: no gate");
		check(!none.breached(new net.minecraft.world.phys.Vec3(-120.5, 206.0, -60.0)), "so the old gate's place is not a breach");
		check(none.contains(-120, 206, -60), "and not shut");
		check(!none.throughGate(new net.minecraft.world.phys.Vec3(-130.5, 206.0, -60.0), new net.minecraft.world.phys.Vec3(-110.5, 206.0, -60.0)),
			"no line goes through it");
		PaintedArea paint = new PaintedArea("p");
		paint.put(1, 1, 1, Paint.ALLOWED, 0);
		GateArea painted = GateArea.create(new int[0], new int[0], 1, null, paint);
		check(painted.contains(1, 1, 1) && !painted.contains(2, 1, 1), "the paint is the region");
		GateArea gated = GateArea.create(new int[] {-120, 206, -63}, new int[] {-120, 206, -56}, 1, null, null);
		check(gated.hasGate() && !gated.contains(-120, 206, -60), "with coordinates the gate is there as before");
		check(!painted.equals(GateArea.create(new int[0], new int[0], 1, null, new PaintedArea("p"))), "areas with different paint are different regions");
	}

	private static void checkOldDefaultGate() {
		AreaKillerConfig config = new AreaKillerConfig();
		check(config.gate.from.length == 0 && config.gate.to.length == 0, "a new config has no gate");
		config.gate.from = new int[] {-120, 206, -63};
		config.gate.to = new int[] {-120, 206, -56};
		config.normalize();
		check(config.gate.from.length == 0, "the gate the source shipped as a default is dropped from a saved config");
		config.gate.from = new int[] {10, 70, 10};
		config.gate.to = new int[] {10, 70, 20};
		config.normalize();
		check(config.gate.from.length == 3, "a gate somebody set is kept");
		config.gate.from = new int[] {1, 2};
		config.normalize();
		check(config.gate.from.length == 0 && config.gate.to.length == 0, "a broken one is cleared");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
