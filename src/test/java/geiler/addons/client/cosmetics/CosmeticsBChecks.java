package geiler.addons.client.cosmetics;

import geiler.addons.client.module.impl.CosmeticsModule;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** The HAT slot (top hat, crown, witch hat, propeller beanie, flower crown, headphones) and bunny ears, fox ears, antlers. */
public final class CosmeticsBChecks {
	private static final List<CosmeticStyle> HATS = List.of(CosmeticStyle.TOP_HAT, CosmeticStyle.ROYAL_CROWN,
		CosmeticStyle.WITCH_HAT, CosmeticStyle.PROPELLER_BEANIE, CosmeticStyle.FLOWER_CROWN, CosmeticStyle.HEADPHONES);
	private static final List<CosmeticStyle> EARS = List.of(CosmeticStyle.BUNNY_EARS, CosmeticStyle.FOX_EARS,
		CosmeticStyle.DEER_ANTLERS);

	private CosmeticsBChecks() { }

	public static void run() {
		checkCodec();
		checkModels();
		checkPlacement();
		checkChannels();
		checkAnimation();
		checkSpeedZero();
		checkHeadMotion();
		checkCombos();
		checkSettings();
		checkOwnerFeedback();
	}

	private static List<CosmeticRig.Placement> sized(CosmeticStyle style, float size) {
		CosmeticPiece b = CosmeticPiece.of(style);
		// Speed 0 freezes every twitch and shake, so two sizes compare like for like.
		return CosmeticRig.place(CosmeticLoadout.of(List.of(new CosmeticPiece(style, b.color(), b.color2(), size, b.up(), b.back(), 0f))),
			CosmeticRig.Motion.standing(1.3f, 4));
	}

	/** Owner feedback 2026-11-22: Size grows head pieces where they stand, ears shake now and then, the crown's Size grows its flowers. */
	private static void checkOwnerFeedback() {
		for (CosmeticStyle style : List.of(CosmeticStyle.BUNNY_EARS, CosmeticStyle.FOX_EARS, CosmeticStyle.DEER_ANTLERS,
				CosmeticStyle.CAT_EARS, CosmeticStyle.DEMON_HORNS)) {
			float[] a = bounds(sized(style, 1f));
			float[] b = bounds(sized(style, 1.6f));
			check(Math.abs(a[4] - b[4]) < 1.2f, style + " keeps its base on the head when resized (" + a[4] + " vs " + b[4] + ")");
			check(b[1] < a[1] - 2f, style + " grows taller when resized (" + a[1] + " -> " + b[1] + ")");
		}
		int shaking = 0;
		int samples = 60 * 120;
		float previous = 0f;
		float maxStep = 0f;
		float peak = 0f;
		for (int i = 0; i < samples; i++) {
			float s = CosmeticRigB.shake(i / 60f, 7, 1f);
			if (Math.abs(s) > 0.05f) shaking++;
			maxStep = Math.max(maxStep, Math.abs(s - previous));
			peak = Math.max(peak, Math.abs(s));
			previous = s;
		}
		check(shaking > 0 && shaking < samples * 0.15f, "ears and antlers shake now and then, not all the time (" + shaking + "/" + samples + ")");
		check(peak <= 1.0001f && maxStep < 0.75f, "the shake stays bounded and has no kick (" + peak + ", " + maxStep + ")");
		check(CosmeticRigB.shake(3f, 7, 0f) == 0f, "speed 0 stops the shake");

		VoxelMesh ring = CosmeticModelsB.INSTANCE.getFLOWER_RING();
		VoxelMesh flower = CosmeticModelsB.INSTANCE.getFLOWER();
		float[] ring1 = bounds(sized(CosmeticStyle.FLOWER_CROWN, 1f).stream().filter(p -> p.mesh() == ring).toList());
		float[] ring2 = bounds(sized(CosmeticStyle.FLOWER_CROWN, 1.6f).stream().filter(p -> p.mesh() == ring).toList());
		check(Math.abs((ring2[3] - ring2[0]) - (ring1[3] - ring1[0])) < 0.01f, "the flower crown's Size keeps the ring on the head");
		float s1 = sized(CosmeticStyle.FLOWER_CROWN, 1f).stream().filter(p -> p.mesh() == flower).findFirst().orElseThrow()
			.transform().getScale(new org.joml.Vector3f()).x;
		float s2 = sized(CosmeticStyle.FLOWER_CROWN, 1.6f).stream().filter(p -> p.mesh() == flower).findFirst().orElseThrow()
			.transform().getScale(new org.joml.Vector3f()).x;
		check(Math.abs(s2 / s1 - 1.6f) < 0.01f, "the flower crown's Size grows the flowers (" + s1 + " -> " + s2 + ")");
	}

	private static List<CosmeticRig.Placement> place(CosmeticStyle style, CosmeticRig.Motion motion, float speed) {
		CosmeticPiece base = CosmeticPiece.of(style);
		CosmeticPiece piece = new CosmeticPiece(style, base.color(), base.color2(), base.size(), base.up(), base.back(), speed);
		return CosmeticRig.place(CosmeticLoadout.of(List.of(piece)), motion);
	}

	private static List<CosmeticRig.Placement> place(CosmeticStyle style, CosmeticRig.Motion motion) {
		return place(style, motion, CosmeticPiece.of(style).speed());
	}

	/** Min and max corner of every placement's vertices in head space. */
	private static float[] bounds(List<CosmeticRig.Placement> placements) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		Vector3f v = new Vector3f();
		for (CosmeticRig.Placement p : placements) {
			if (p.kind() != CosmeticRig.Kind.SOLID) continue;
			float[] pos = p.mesh().positions;
			for (int i = 0; i < pos.length; i += 3) {
				p.transform().transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
				b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
				b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
			}
		}
		return b;
	}

	private static void checkCodec() {
		for (CosmeticStyle style : concat(HATS, EARS)) {
			CosmeticProfileCodec.Result result = CosmeticProfileCodec.parse(
				"{\"cosmetics\":{\"" + style.getSlot().getId() + "\":{\"style\":\"" + style.getId() + "\"}}}");
			CosmeticPiece piece = result.loadout().piece(style.getSlot());
			check(result.problems().isEmpty() && piece != null && piece.style() == style
				&& piece.color() == style.getColor() && piece.size() == style.getSlot().getDefaultSize(),
				style.getId() + " parses from the hosted profile with the slot defaults");
		}
		CosmeticProfileCodec.Result both = CosmeticProfileCodec.parse(
			"{\"cosmetics\":{\"head\":{\"style\":\"bunny_ears\"},\"hat\":{\"style\":\"witch_hat\",\"color\":\"#2244FF\",\"speed\":2}}}");
		check(both.problems().isEmpty() && both.loadout().piece(CosmeticSlot.HEAD).style() == CosmeticStyle.BUNNY_EARS
			&& both.loadout().piece(CosmeticSlot.HAT).color() == 0x2244FF && both.loadout().piece(CosmeticSlot.HAT).speed() == 2f,
			"a hat and head ears are worn together");
		check(!CosmeticProfileCodec.parse("{\"cosmetics\":{\"hat\":{\"style\":\"nope\"}}}").problems().isEmpty(),
			"an unknown hat style is reported");
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (CosmeticStyle style : CosmeticStyle.values()) check(ids.add(style.getSlot().getId() + "/" + style.getId()), "style ids are unique per slot");
	}

	private static void checkModels() {
		CosmeticModelsB m = CosmeticModelsB.INSTANCE;
		List<VoxelMesh> all = new ArrayList<>(List.of(m.getTOP_HAT(), m.getROYAL_CROWN(), m.getBEANIE(), m.getPROPELLER(),
			m.getFLOWER_RING(), m.getFLOWER(), m.getSPRIG(), m.getHEADPHONE_BAND(), m.getHEADPHONE_CUP(), m.getSPARKLE()));
		all.addAll(m.getWITCH_HAT());
		all.addAll(m.getCROWN_GEMS());
		all.addAll(m.getBUNNY_EAR());
		all.addAll(m.getFOX_EAR());
		all.addAll(m.getANTLER());
		for (VoxelMesh mesh : all) check(mesh.faceCount() > 0 && mesh.faceCount() < 5000, "every hat model is non-empty and cheap to draw");
		check(m.getWITCH_HAT().size() == 3 && m.getBUNNY_EAR().size() == 2 && m.getFOX_EAR().size() == 2 && m.getANTLER().size() == 2,
			"jointed models come in their rigid pieces");
		float[] hat = extent(m.getWITCH_HAT());
		check(hat[0] <= -11.5f && hat[0] > -15f, "the witch hat is about 11 px tall above the brim (" + hat[0] + ")");
		check(hat[2] > 5.5f && hat[1] > 5.5f, "the witch brim is wide (" + hat[1] + ")");
		float[] top = extent(List.of(m.getTOP_HAT()));
		check(top[0] < -9f && top[0] > -10f && top[1] > 6f, "the top hat is tall with a brim wider than the crown");
		float[] bunny = extent(m.getBUNNY_EAR());
		check(bunny[0] < -10.5f && bunny[0] > -12f, "bunny ears are about 11 px tall (" + bunny[0] + ")");
		float[] prop = extent(List.of(m.getPROPELLER()));
		check(prop[1] > 6f && prop[1] < 7f, "the propeller is about 13 px across");
		int[] fixedPalette = m.getROYAL_CROWN().palette;
		check(fixedPalette.length >= 1, "the crown has its fixed fur color");
	}

	/** {min y, max |x|, max z} of a set of meshes. */
	private static float[] extent(List<VoxelMesh> meshes) {
		float minY = Float.MAX_VALUE;
		float maxX = 0f;
		float maxZ = 0f;
		for (VoxelMesh mesh : meshes) {
			for (int i = 0; i < mesh.positions.length; i += 3) {
				minY = Math.min(minY, mesh.positions[i + 1]);
				maxX = Math.max(maxX, Math.abs(mesh.positions[i]));
				maxZ = Math.max(maxZ, Math.abs(mesh.positions[i + 2]));
			}
		}
		return new float[] {minY, maxX, maxZ};
	}

	private static void checkPlacement() {
		CosmeticRig.Motion rest = CosmeticRig.Motion.standing(1.3f, 4);
		for (CosmeticStyle style : HATS) {
			List<CosmeticRig.Placement> placements = place(style, rest);
			check(!placements.isEmpty() && placements.stream().allMatch(p -> p.anchor() == CosmeticRig.Anchor.HEAD),
				style + " is placed on the head");
			float[] b = bounds(placements);
			// Head space: the head's top is y = -8; the hat layer reaches -8.5.
			switch (style) {
				case FLOWER_CROWN -> check(b[3] > 5f && b[4] < -3f && b[4] > -8.2f && b[1] < -8f,
					style + " wraps the upper head (" + b[3] + ", " + b[1] + ", " + b[4] + ")");
				case HEADPHONES -> check(b[3] > 7f && b[1] < -9.5f && b[4] > -6f,
					style + " arches over the head with cups at the sides (" + b[3] + ", " + b[1] + ", " + b[4] + ")");
				default -> check(b[4] < -7.9f && b[4] > -9.6f && b[1] < -12f,
					style + " sits on top of the head, not inside it (" + b[4] + ", top " + b[1] + ")");
			}
		}
		for (CosmeticStyle style : EARS) {
			float[] b = bounds(place(style, rest));
			check(b[4] < -6.5f && b[4] > -9.5f && b[1] < -10.5f, style + " stands on the head top (" + b[4] + ", " + b[1] + ")");
		}
	}

	/** Area share of faces that take the player's Color or Second Color, over all placements of a style. */
	private static float[] channelShare(CosmeticStyle style) {
		double[] area = new double[3];
		for (CosmeticRig.Placement p : place(style, CosmeticRig.Motion.standing(0.2f, 4))) {
			if (p.kind() != CosmeticRig.Kind.SOLID) continue;
			VoxelMesh mesh = p.mesh();
			for (int f = 0; f < mesh.faceCount(); f++) {
				int base = f * 12;
				float dx1 = mesh.positions[base + 3] - mesh.positions[base], dy1 = mesh.positions[base + 4] - mesh.positions[base + 1], dz1 = mesh.positions[base + 5] - mesh.positions[base + 2];
				float dx2 = mesh.positions[base + 9] - mesh.positions[base], dy2 = mesh.positions[base + 10] - mesh.positions[base + 1], dz2 = mesh.positions[base + 11] - mesh.positions[base + 2];
				double a = Math.sqrt(sq(dy1 * dz2 - dz1 * dy2) + sq(dz1 * dx2 - dx1 * dz2) + sq(dx1 * dy2 - dy1 * dx2));
				int channel = mesh.channels[f] & 0xFF;
				area[Math.min(channel, 2)] += a;
			}
		}
		double total = area[0] + area[1] + area[2];
		return new float[] {(float) (area[0] / total), (float) (area[1] / total), (float) (area[2] / total)};
	}

	private static double sq(double v) {
		return v * v;
	}

	private static void checkChannels() {
		for (CosmeticStyle style : concat(HATS, EARS)) {
			float[] share = channelShare(style);
			check(share[0] + share[1] >= 0.6f, style + " is mostly customizable color (fixed share " + share[2] + ")");
			check(share[0] > 0.04f && share[1] > 0.04f || style == CosmeticStyle.TOP_HAT && share[1] > 0.3f,
				style + " uses both Color and Second Color (" + share[0] + " / " + share[1] + ")");
		}
		float[] crown = channelShare(CosmeticStyle.ROYAL_CROWN);
		check(crown[0] > 0.3f, "the crown's gold ring is the main Color (" + crown[0] + ")");
	}

	private static float[] flatten(List<CosmeticRig.Placement> placements) {
		List<Float> values = new ArrayList<>();
		float[] tmp = new float[16];
		for (CosmeticRig.Placement p : placements) {
			p.transform().get(tmp);
			for (float v : tmp) values.add(v);
		}
		float[] out = new float[values.size()];
		for (int i = 0; i < out.length; i++) out[i] = values.get(i);
		return out;
	}

	private static float maxDelta(float[] a, float[] b) {
		float max = 0f;
		for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
		return max;
	}

	private static void checkAnimation() {
		for (CosmeticStyle style : concat(HATS, EARS)) {
			// Walk the player from standing to sprinting to jumping with the real tracker, frame by frame.
			CosmeticMotionTracker tracker = new CosmeticMotionTracker();
			float[] previous = null;
			float[] before = null;
			float worst = 0f;
			boolean moved = false;
			float[] first = null;
			for (int frame = 0; frame < 360; frame++) {
				float seconds = 800f + frame / 60f;
				float moving = frame < 60 ? 0f : 1f;
				boolean air = frame > 200 && frame < 260;
				CosmeticRig.Motion motion = tracker.update(3, seconds, moving, air);
				List<CosmeticRig.Placement> placements = place(style, motion);
				// The propeller's blades spin on purpose; it is checked separately.
				List<CosmeticRig.Placement> still = new ArrayList<>();
				for (CosmeticRig.Placement p : placements) {
					if (p.mesh() != CosmeticModelsB.INSTANCE.getPROPELLER() && p.kind() != CosmeticRig.Kind.GLOW
						&& p.kind() != CosmeticRig.Kind.TRANSLUCENT) still.add(p);
				}
				float[] now = flatten(still);
				if (first == null) first = now;
				// Second difference: a quick twitch is a fast but smooth swing; a jump shows up as a spike here.
				if (previous != null && before != null && before.length == now.length && previous.length == now.length) {
					for (int i = 0; i < now.length; i++) worst = Math.max(worst, Math.abs(now[i] - 2 * previous[i] + before[i]));
				}
				before = previous;
				if (now.length == first.length && maxDelta(first, now) > 0.01f) moved = true;
				previous = now;
			}
			check(worst < 0.3f, style + " moves smoothly across start, stop and jump (largest second difference " + worst + ")");
			check(moved, style + " animates (idle, walking or jumping changes its pose)");
		}
		float last = 0f;
		float worstSpin = 0f;
		CosmeticMotionTracker tracker = new CosmeticMotionTracker();
		for (int frame = 0; frame < 300; frame++) {
			CosmeticRig.Motion motion = tracker.update(9, 900f + frame / 60f, frame < 100 ? 0f : 1f, frame > 180);
			float spin = CosmeticRigB.propellerSpin(motion, 1f);
			if (frame > 0) {
				worstSpin = Math.max(worstSpin, Math.abs(spin - last));
				check(spin > last, "the propeller never turns backward");
			}
			last = spin;
		}
		check(worstSpin < 1.2f, "the propeller's turn rate changes smoothly, never jumps (largest step " + worstSpin + ")");
		CosmeticMotionTracker ground = new CosmeticMotionTracker();
		CosmeticMotionTracker moving = new CosmeticMotionTracker();
		CosmeticMotionTracker flying = new CosmeticMotionTracker();
		CosmeticRig.Motion a = null, b = null, c = null;
		for (int frame = 0; frame < 300; frame++) {
			a = ground.update(1, frame / 60f, 0f, false);
			b = moving.update(2, frame / 60f, 1f, false);
			c = flying.update(3, frame / 60f, 1f, true);
		}
		check(CosmeticRigB.propellerSpin(b, 1f) > CosmeticRigB.propellerSpin(a, 1f) * 1.2f
			&& CosmeticRigB.propellerSpin(c, 1f) > CosmeticRigB.propellerSpin(b, 1f) * 1.3f,
			"the propeller spins faster when moving and much faster in the air");
		// Flicks and glints happen now and then, not all the time.
		int glints = 0;
		for (int i = 0; i < 3000; i++) if (CosmeticRigB.glint(i / 30f, 5, 2, 10, 1f) > 0.05f) glints++;
		check(glints > 0 && glints < 600, "a crown gem glints now and then (" + glints + " of 3000 frames)");
		check(CosmeticRigB.beat(0.5f) > 0f && CosmeticRigB.beat(2.64f) == 0f, "headphones pulse in beats with rests between");
	}

	private static void checkSpeedZero() {
		CosmeticRig.Motion one = new CosmeticRig.Motion(3.1f, 11, 0.7f, 0.4f, 12.5f, 7.1f);
		CosmeticRig.Motion two = new CosmeticRig.Motion(40.6f, 11, 0.7f, 0.4f, 91.2f, 33.3f);
		for (CosmeticStyle style : concat(HATS, EARS)) {
			List<CosmeticRig.Placement> a = place(style, one, 0f);
			List<CosmeticRig.Placement> b = place(style, two, 0f);
			check(a.size() == b.size() && maxDelta(flatten(a), flatten(b)) < 1e-4f, style + " is frozen at speed 0");
		}
	}

	private static void checkHeadMotion() {
		float worst = 0f;
		float peak = 0f;
		float previous = 0f;
		for (int frame = 0; frame < 600; frame++) {
			float seconds = 50f + frame / 60f;
			// A quick head turn after standing, then walking with a jump.
			float yaw = frame < 100 ? 0f : frame < 130 ? (frame - 100) * 6f : 180f;
			CosmeticHeadMotion.update(77, seconds, yaw, 0f, frame > 300 ? 1f : 0f, frame > 450 && frame < 480 ? 1f : 0f);
			CosmeticHeadMotion.Sway s = CosmeticHeadMotion.sway(77);
			check(Float.isFinite(s.twist) && Float.isFinite(s.fore) && Float.isFinite(s.bob), "head springs stay finite");
			peak = Math.max(peak, Math.abs(s.twist));
			worst = Math.max(worst, Math.abs(s.twist - previous));
			previous = s.twist;
			check(Math.abs(s.twist) <= 0.8f && Math.abs(s.bob) <= 1.5f, "head springs stay bounded");
		}
		check(peak > 0.05f, "a head turn swings the loose parts (" + peak + ")");
		check(worst < 0.08f, "the swing is continuous frame to frame (" + worst + ")");
		check(CosmeticHeadMotion.sway(123456) == CosmeticHeadMotion.REST, "unknown players rest");
	}

	private static void checkCombos() {
		CosmeticLoadout loadout = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.CAT_EARS), CosmeticPiece.of(CosmeticStyle.ROYAL_CROWN)));
		List<CosmeticRig.Placement> placements = CosmeticRig.place(loadout, CosmeticRig.Motion.standing(0.5f, 2));
		check(placements.size() >= 3 && placements.stream().allMatch(p -> p.anchor() == CosmeticRig.Anchor.HEAD),
			"cat ears and a crown are worn together on the head");
		check(CosmeticRigBKt.withoutSlot(loadout, CosmeticSlot.HAT).piece(CosmeticSlot.HAT) == null
			&& CosmeticRigBKt.withoutSlot(loadout, CosmeticSlot.HAT).piece(CosmeticSlot.HEAD) != null && CosmeticRigBKt.withoutSlot(loadout, CosmeticSlot.WINGS) == loadout,
			"a helmet hides only the hat");
	}

	private static void checkSettings() {
		CosmeticsModule.SlotSettings hat = CosmeticsModule.INSTANCE.slot(CosmeticSlot.HAT);
		check(hat.getSpeedLabel().equals("Motion Speed") && hat.getSize().value() == 1f && hat.getSpeed().value() == 1f
			&& hat.getUp().value() == 0f && hat.getBack().value() == 0f, "the hat slot has tuned defaults and a Motion Speed setting");
		try {
			check(CosmeticsModule.INSTANCE.toggle(CosmeticStyle.WITCH_HAT) && hat.selected() == CosmeticStyle.WITCH_HAT
				&& CosmeticsModule.INSTANCE.localLoadout().piece(CosmeticSlot.HAT).style() == CosmeticStyle.WITCH_HAT,
				"a hat is worn from the menu");
			check(CosmeticsModule.INSTANCE.toggle(CosmeticStyle.TOP_HAT) && hat.selected() == CosmeticStyle.TOP_HAT, "another hat replaces it");
			CosmeticsModule.INSTANCE.toggle(CosmeticStyle.BUNNY_EARS);
			check(CosmeticsModule.INSTANCE.localLoadout().piece(CosmeticSlot.HEAD).style() == CosmeticStyle.BUNNY_EARS
				&& CosmeticsModule.INSTANCE.localLoadout().piece(CosmeticSlot.HAT) != null, "ears and a hat are worn together");
		} finally {
			hat.getStyle().setValue(CosmeticsModule.NONE);
			CosmeticsModule.INSTANCE.slot(CosmeticSlot.HEAD).getStyle().setValue(CosmeticsModule.NONE);
		}
		for (CosmeticStyle style : HATS) {
			int[] pixels = CosmeticIcons.pixels(CosmeticPiece.of(style), 64, 0.6f);
			int drawn = 0;
			for (int pixel : pixels) if ((pixel >>> 24) > 0) drawn++;
			check(drawn > 64 * 64 / 8, style + " has a menu card picture");
		}
	}

	private static List<CosmeticStyle> concat(List<CosmeticStyle> a, List<CosmeticStyle> b) {
		List<CosmeticStyle> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
