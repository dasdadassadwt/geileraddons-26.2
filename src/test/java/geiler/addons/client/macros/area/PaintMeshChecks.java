package geiler.addons.client.macros.area;

import java.util.ArrayList;
import java.util.List;

/** Painted cells that touch are drawn as one plane. */
public final class PaintMeshChecks {
	private PaintMeshChecks() { }

	public static void run() {
		List<PaintMesh.Cell> block = new ArrayList<>();
		for (int x = 0; x < 4; x++) for (int z = 0; z < 3; z++) block.add(new PaintMesh.Cell(x, 64, z, Paint.PREFERRED, 0));
		List<PaintMesh.Rect> one = PaintMesh.merge(block);
		check(one.size() == 1 && one.get(0).cells() == 12, "a 4 by 3 block is one rectangle");

		List<PaintMesh.Cell> ell = new ArrayList<>();
		for (int x = 0; x < 3; x++) ell.add(new PaintMesh.Cell(x, 64, 0, Paint.ALLOWED, 0));
		ell.add(new PaintMesh.Cell(0, 64, 1, Paint.ALLOWED, 0));
		ell.add(new PaintMesh.Cell(0, 64, 2, Paint.ALLOWED, 0));
		List<PaintMesh.Rect> two = PaintMesh.merge(ell);
		check(two.size() == 2 && total(two) == 5, "an L shape is two rectangles that hold every cell once");

		List<PaintMesh.Cell> mixed = new ArrayList<>();
		mixed.add(new PaintMesh.Cell(0, 64, 0, Paint.ALLOWED, 0));
		mixed.add(new PaintMesh.Cell(1, 64, 0, Paint.PREFERRED, 0));
		mixed.add(new PaintMesh.Cell(2, 64, 0, Paint.ALLOWED, 4));
		mixed.add(new PaintMesh.Cell(3, 65, 0, Paint.ALLOWED, 0));
		check(PaintMesh.merge(mixed).size() == 4, "another paint, another slab height or another floor does not join");

		List<PaintMesh.Cell> gap = new ArrayList<>();
		gap.add(new PaintMesh.Cell(0, 64, 0, Paint.ALLOWED, 0));
		gap.add(new PaintMesh.Cell(2, 64, 0, Paint.ALLOWED, 0));
		check(PaintMesh.merge(gap).size() == 2, "cells with a gap between are two");

		List<PaintMesh.Cell> negative = new ArrayList<>();
		negative.add(new PaintMesh.Cell(-1, 64, -1, Paint.REST, 0));
		negative.add(new PaintMesh.Cell(0, 64, -1, Paint.REST, 0));
		negative.add(new PaintMesh.Cell(-1, 64, 0, Paint.REST, 0));
		negative.add(new PaintMesh.Cell(0, 64, 0, Paint.REST, 0));
		List<PaintMesh.Rect> square = PaintMesh.merge(negative);
		check(square.size() == 1 && square.get(0).x0 == -1 && square.get(0).z1 == 1, "joining works across zero");
	}

	private static int total(List<PaintMesh.Rect> rects) {
		int sum = 0;
		for (PaintMesh.Rect rect : rects) sum += rect.cells();
		return sum;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
