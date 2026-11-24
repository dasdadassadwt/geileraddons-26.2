package geiler.addons.client.cosmetics;

import geiler.addons.client.module.impl.CosmeticsModule;

import java.util.ArrayList;
import java.util.List;

/** Skirts (SKIRT slot) and the music note, frost and cherry blossom auras. */
public final class CosmeticsEChecks {
	private CosmeticsEChecks() { }

	public static void run() {
		checkCodecAndSettings();
		checkSkirtMeshes();
		checkWaistband();
		checkLegClearance();
		checkSkirtAnimation();
		checkAuras();
	}

	private static List<CosmeticStyle> skirts() {
		return CosmeticSlot.SKIRT.styles();
	}

	private static float[] copy(VoxelMesh mesh) {
		return mesh.positions.clone();
	}

	private static List<CosmeticRig.Placement> place(CosmeticStyle style, CosmeticRig.Motion motion, BodyShape shape, float speed) {
		CosmeticPiece base = CosmeticPiece.of(style);
		CosmeticPiece piece = new CosmeticPiece(style, base.color(), base.color2(), base.size(), base.up(), base.back(), speed);
		return CosmeticRig.place(CosmeticLoadout.of(List.of(piece), shape), motion);
	}

	private static void checkCodecAndSettings() {
		String[] ids = {"pleated", "ruffled", "long", "tutu", "kilt", "maid"};
		check(skirts().size() == ids.length, "the skirt slot has six styles");
		for (String id : ids) {
			CosmeticProfileCodec.Result result = CosmeticProfileCodec.parse(
				"{\"cosmetics\":{\"skirt\":{\"style\":\"" + id + "\",\"color\":\"#3366CC\",\"color2\":\"#FFFFFF\",\"speed\":0.5}}}");
			CosmeticPiece piece = result.loadout().piece(CosmeticSlot.SKIRT);
			check(result.problems().isEmpty() && piece != null && piece.style().getId().equals(id) && piece.color() == 0x3366CC
				&& piece.size() == CosmeticSlot.SKIRT.getDefaultSize(), "hosted skirt style " + id + " parses");
		}
		for (String id : new String[]{"music_notes", "frost", "cherry_blossom"}) {
			CosmeticProfileCodec.Result result = CosmeticProfileCodec.parse("{\"cosmetics\":{\"aura\":{\"style\":\"" + id + "\"}}}");
			check(result.problems().isEmpty() && result.loadout().piece(CosmeticSlot.AURA).style().getId().equals(id),
				"hosted aura style " + id + " parses");
		}
		check(CosmeticsModule.INSTANCE.slot(CosmeticSlot.SKIRT).getSize().value() == CosmeticSlot.SKIRT.getDefaultSize()
			&& CosmeticsModule.INSTANCE.slot(CosmeticSlot.SKIRT).getSpeed().value() == CosmeticSlot.SKIRT.getDefaultSpeed(),
			"the skirt slot has settings with its tuned defaults");
	}

	/** Area per channel class: [0] = player colors (channels 0 and 1), [1] = fixed colors. */
	private static float[] area(VoxelMesh mesh) {
		float[] out = new float[2];
		for (int f = 0; f < mesh.faceCount(); f++) {
			int b = f * 12;
			float w = dist(mesh.positions, b, b + 3);
			float h = dist(mesh.positions, b, b + 9);
			out[(mesh.channels[f] & 0xFF) < 2 ? 0 : 1] += w * h;
		}
		return out;
	}

	private static float dist(float[] p, int a, int b) {
		float dx = p[a] - p[b], dy = p[a + 1] - p[b + 1], dz = p[a + 2] - p[b + 2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static void checkSkirtMeshes() {
		float[] hems = {15.5f, 17.1f, 22.6f, 13.9f, 16.0f, 16.3f};
		int i = 0;
		for (CosmeticStyle style : skirts()) {
			SkirtModel model = SkirtGeometry.model(style);
			VoxelMesh mesh = model.mesh;
			check(mesh.faceCount() > 200 && mesh.faceCount() < 14000, style + " skirt is non-empty and cheap enough to move every frame");
			float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, widthTop = 0, widthHem = 0;
			for (int p = 0; p < mesh.positions.length; p += 3) {
				minY = Math.min(minY, mesh.positions[p + 1]);
				maxY = Math.max(maxY, mesh.positions[p + 1]);
			}
			for (int p = 0; p < mesh.positions.length; p += 3) {
				float y = mesh.positions[p + 1];
				if (y < minY + 1f) widthTop = Math.max(widthTop, Math.abs(mesh.positions[p]));
				if (y > maxY - 1.5f) widthHem = Math.max(widthHem, Math.abs(mesh.positions[p]));
			}
			check(minY >= 7.9f && minY < 8.6f && Math.abs(maxY - hems[i]) < 1.6f,
				style + " spans the waist to its hem (" + minY + ".." + maxY + ")");
			check(widthTop >= 4.5f && widthTop < 5.6f && widthHem > 5.8f, style + " hugs the waist and flares to the hem ("
				+ widthTop + " -> " + widthHem + ")");
			float[] area = area(mesh);
			check(area[0] > 0.8f * (area[0] + area[1]), style + " keeps most of its surface in the two player colors ("
				+ area[1] / (area[0] + area[1]) + " fixed)");
			boolean accent = style == CosmeticStyle.KILT || style == CosmeticStyle.MAID_SKIRT;
			check(accent == (mesh.palette.length > 0), style + " has fixed-color accents only where the sheet has lace or metal");
			i++;
		}
		boolean blue = false;
		VoxelMesh pleated = SkirtGeometry.model(CosmeticStyle.PLEATED_SKIRT).mesh;
		for (int f = 0; f < pleated.faceCount(); f++) blue |= pleated.tint(f, 0x2040C0, 0xFFFFFF) == 0x2040C0;
		check(blue, "the player's color reaches the plaid");
	}

	private static float maxAbsX(float[] positions, float yFrom, float yTo) {
		float max = 0f;
		for (int p = 0; p < positions.length; p += 3) {
			float y = positions[p + 1];
			if (y >= yFrom && y <= yTo) max = Math.max(max, Math.abs(positions[p]));
		}
		return max;
	}

	private static void checkWaistband() {
		BodyShape shape = new BodyShape(1f, 1f);
		for (CosmeticStyle style : skirts()) {
			CosmeticPiece piece = CosmeticPiece.of(style);
			CosmeticRig.Motion still = CosmeticRig.Motion.standing(0.5f, 3);
			float flat = maxAbsX(SkirtRig.posed(piece, still, null).deformed().positions, 8f, 9f);
			float shaped = maxAbsX(SkirtRig.posed(piece, still, shape).deformed().positions, 8f, 9f);
			float waistIn = BodyShapeGeometry.waistIn(shape, 8.5f);
			// Skin 4 - waistIn, jacket 4.25 - waistIn, leggings 4.5 - waistIn.
			check(shaped < flat - 0.5f * waistIn, style + " waistband narrows with the waist slider (" + flat + " -> " + shaped + ")");
			check(shaped > 4.5f - BodyShapeGeometry.waistIn(shape, 8f) + 0.15f, style + " waistband stays outside shaped leggings (" + shaped + ")");
			float unshaped = maxAbsX(SkirtRig.posed(piece, still, new BodyShape(0.5f, 0f)).deformed().positions, 8f, 9f);
			check(Math.abs(unshaped - flat) < 0.01f, style + " ignores the chest slider at the waist");
		}
	}

	/** Leg front surface z at depth [h] below the hip for a leg pitched by [theta] (negative = foot forward). */
	private static float legFront(float h, float theta) {
		return h * (float) Math.tan(theta) - 2f / (float) Math.cos(theta);
	}

	private static void checkLegClearance() {
		float theta = -0.6f;
		for (CosmeticStyle style : skirts()) {
			SkirtModel model = SkirtGeometry.model(style);
			SkirtRig.setLegs(-theta, theta);
			try {
				SkirtRig.Entry entry = SkirtRig.posed(CosmeticPiece.of(style), new CosmeticRig.Motion(2f, 3, 0.8f, 0f, 0f, 0f), null);
				float yProbe = Math.min(model.yHem - 0.3f, 18f);
				int row = Math.round(yProbe * 2f) - SkirtGeometry.ROW0;
				float[] p = new float[4];
				// A point on the front of the skirt over the left leg (x = 2), where that leg swings forward.
				entry.warp(2f, yProbe, -model.rowB[row] * 0.97f, p);
				float h = p[1] - 12f;
				check(p[2] < legFront(h, theta) + 0.2f, style + " hem clears a leg swung forward by 0.6 rad (z " + p[2] + " vs leg " + legFront(h, theta) + ")");
			} finally {
				SkirtRig.clearLegs();
			}
		}
	}

	private static void checkSkirtAnimation() {
		for (CosmeticStyle style : skirts()) {
			// Walking from a stand: the tracker smooths moving, and no vertex may jump between frames.
			CosmeticMotionTracker tracker = new CosmeticMotionTracker();
			float[] previous = null;
			float worst = 0f;
			for (int frame = 0; frame < 300; frame++) {
				float seconds = 400f + frame / 60f;
				CosmeticRig.Motion motion = tracker.update(4, seconds, frame < 60 ? 0f : 1f, false);
				List<CosmeticRig.Placement> placements = place(style, motion, null, 1f);
				float[] now = copy(placements.get(0).mesh());
				if (previous != null) worst = Math.max(worst, maxMove(previous, now));
				previous = now;
			}
			check(worst < 1.6f, style + " moves smoothly from standing to walking (largest vertex step " + worst + " px)");
			// Real legs swinging: the cloth follows, still without a jump (legs themselves move up to ~1 px per frame at the hem).
			worst = 0f;
			previous = null;
			for (int frame = 0; frame < 240; frame++) {
				float t = frame / 60f;
				SkirtRig.setLegs((float) Math.sin(t * 9.4f) * 0.9f, -(float) Math.sin(t * 9.4f) * 0.9f);
				try {
					float[] now = copy(place(style, CosmeticRig.Motion.standing(t, 4), null, 1f).get(0).mesh());
					if (previous != null) worst = Math.max(worst, maxMove(previous, now));
					previous = now;
				} finally {
					SkirtRig.clearLegs();
				}
			}
			check(worst < 1.6f, style + " follows swinging legs without jumps (largest vertex step " + worst + " px)");
			// Speed 0 freezes everything, even with a walking player.
			float[] a = copy(place(style, new CosmeticRig.Motion(3f, 9, 1f, 0f, 1f, 2f), null, 0f).get(0).mesh());
			List<CosmeticRig.Placement> frozen = place(style, new CosmeticRig.Motion(9f, 9, 0f, 1f, 4f, 7f), null, 0f);
			check(maxMove(a, frozen.get(0).mesh().positions) < 1e-4f, style + " stands still at speed 0");
			// Two calls in a row must not share a buffer: the first is drawn later in the frame.
			List<CosmeticRig.Placement> first = place(style, CosmeticRig.Motion.standing(1f, 11), null, 1f);
			List<CosmeticRig.Placement> second = place(style, CosmeticRig.Motion.standing(2f, 11), null, 1f);
			check(first.get(0).mesh() != second.get(0).mesh(), style + " keeps this frame's vertices apart from the next frame's");
		}
		check(place(CosmeticStyle.KILT, CosmeticRig.Motion.standing(1f, 1), null, 1f).size() == 2, "the kilt hangs a pouch");
		check(place(CosmeticStyle.MAID_SKIRT, CosmeticRig.Motion.standing(1f, 1), null, 1f).size() == 4, "the maid skirt has a bow and two tails");
		check(place(CosmeticStyle.PLEATED_SKIRT, CosmeticRig.Motion.standing(1f, 1), null, 1f).size() == 1, "a plain skirt is one piece");
	}

	private static float maxMove(float[] a, float[] b) {
		float worst = 0f;
		for (int i = 0; i < a.length; i += 3) {
			float dx = a[i] - b[i], dy = a[i + 1] - b[i + 1], dz = a[i + 2] - b[i + 2];
			worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		return worst;
	}

	private static void checkAuras() {
		for (CosmeticStyle style : new CosmeticStyle[]{CosmeticStyle.MUSIC_AURA, CosmeticStyle.FROST_AURA, CosmeticStyle.BLOSSOM_AURA}) {
			int most = 0;
			for (int step = 0; step < 600; step++) {
				float t = 3f + step * 0.05f;
				List<CosmeticRig.Placement> list = CosmeticRig.place(CosmeticLoadout.of(List.of(CosmeticPiece.of(style))),
					CosmeticRig.Motion.standing(t, 5));
				most = Math.max(most, list.size());
				for (CosmeticRig.Placement p : list) {
					check(p.alpha() >= 0 && p.alpha() <= 255 && p.mesh().faceCount() > 0, style + " places only valid parts");
				}
			}
			int cap = style == CosmeticStyle.FROST_AURA ? CosmeticRigE.MAX_PLACEMENTS
				: style == CosmeticStyle.MUSIC_AURA ? CosmeticRigE.NOTES : CosmeticRigE.PETALS;
			check(most > 0 && most <= cap, style + " keeps its particle count bounded (" + most + " of " + cap + ")");
			List<CosmeticRig.Placement> a = place(style, CosmeticRig.Motion.standing(1f, 2), null, 0f);
			List<CosmeticRig.Placement> b = place(style, CosmeticRig.Motion.standing(50f, 2), null, 0f);
			boolean same = a.size() == b.size();
			for (int i = 0; same && i < a.size(); i++) same = a.get(i).transform().equals(b.get(i).transform());
			check(same, style + " stands still at speed 0");
		}
		// Owner feedback 2026-11-22: snowflakes were as big as a forearm; blossom did not read as cherry blossom.
		org.joml.Vector3f scale = new org.joml.Vector3f();
		float widest = 0f;
		for (CosmeticRig.Placement p : CosmeticRig.place(CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.FROST_AURA))),
				CosmeticRig.Motion.standing(4f, 3))) {
			if (p.mesh() != CosmeticModelsE.INSTANCE.getFLAKE_BIG() && p.mesh() != CosmeticModelsE.INSTANCE.getFLAKE_SMALL()) continue;
			float[] pos = p.mesh().positions;
			float reach = 0f;
			for (int i = 0; i < pos.length; i += 3) reach = Math.max(reach, Math.max(Math.abs(pos[i]), Math.abs(pos[i + 1])));
			widest = Math.max(widest, 2f * reach * p.transform().getScale(scale).x);
		}
		check(widest > 1.5f && widest < 5f, "snowflakes are small, a few pixels across (" + widest + " px)");
		int flowers = 0;
		for (CosmeticRig.Placement p : CosmeticRig.place(CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.BLOSSOM_AURA))),
				CosmeticRig.Motion.standing(4f, 3))) {
			if (p.mesh() == CosmeticModelsE.INSTANCE.getBLOSSOM()) flowers++;
		}
		check(flowers >= 3, "the cherry blossom aura carries whole five-petal blossoms, not only loose petals (" + flowers + ")");

		// Smoothness over a frame: a note moves a few tenths of a pixel at most between 60 fps frames.
		List<float[]> before = new ArrayList<>();
		for (CosmeticRig.Placement p : place(CosmeticStyle.MUSIC_AURA, CosmeticRig.Motion.standing(5f, 1), null, 1f)) {
			org.joml.Vector3f v = p.transform().getTranslation(new org.joml.Vector3f());
			before.add(new float[]{v.x, v.y, v.z});
		}
		List<CosmeticRig.Placement> after = place(CosmeticStyle.MUSIC_AURA, CosmeticRig.Motion.standing(5f + 1f / 60f, 1), null, 1f);
		check(after.size() == before.size(), "notes keep their count between frames");
		for (int i = 0; i < after.size(); i++) {
			org.joml.Vector3f v = after.get(i).transform().getTranslation(new org.joml.Vector3f());
			float d = (float) Math.sqrt(Math.pow(v.x - before.get(i)[0], 2) + Math.pow(v.y - before.get(i)[1], 2) + Math.pow(v.z - before.get(i)[2], 2));
			check(d < 1.2f, "a note moves smoothly between frames (" + d + " px)");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
