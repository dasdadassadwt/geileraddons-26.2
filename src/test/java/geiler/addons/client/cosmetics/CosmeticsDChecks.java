package geiler.addons.client.cosmetics;

import geiler.addons.client.module.impl.CosmeticsModule;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Pets (PET slot) and the storm cloud, flame ring and heart auras: codec, models, follow spring, animation. */
public final class CosmeticsDChecks {
	private static final List<CosmeticStyle> PETS = CosmeticSlot.PET.styles();
	private static final List<CosmeticStyle> AURAS = List.of(CosmeticStyle.STORM_CLOUD, CosmeticStyle.FLAME_RING, CosmeticStyle.HEART_AURA);

	private CosmeticsDChecks() { }

	public static void run() {
		checkCatalogAndCodec();
		checkModels();
		checkColorChannels();
		checkFollow();
		checkAnimation();
		checkSpeedZero();
		checkAuras();
		checkIdleLife();
		checkSettings();
		checkOwnerFeedback();
	}

	private static CosmeticLoadout wear(CosmeticStyle style, float speed) {
		CosmeticPiece p = CosmeticPiece.of(style);
		return CosmeticLoadout.of(List.of(new CosmeticPiece(style, p.color(), p.color2(), p.size(), p.up(), p.back(), speed)));
	}

	private static CosmeticRig.Motion motion(float seconds, float moving, float air, PetPose pose, float phase) {
		return new CosmeticRig.Motion(seconds, 9, moving, air, phase, phase, pose);
	}

	private static void checkCatalogAndCodec() {
		check(PETS.size() == 6, "six pets are registered");
		for (CosmeticStyle style : PETS) {
			CosmeticProfileCodec.Result r = CosmeticProfileCodec.parse(
				"{\"cosmetics\":{\"pet\":{\"style\":\"" + style.getId() + "\",\"color\":\"#336699\",\"color2\":\"#FFFFFF\"}}}");
			CosmeticPiece piece = r.loadout().piece(CosmeticSlot.PET);
			check(r.problems().isEmpty() && piece != null && piece.style() == style && piece.color() == 0x336699,
				"hosted pet id " + style.getId() + " parses");
		}
		for (CosmeticStyle style : AURAS) {
			CosmeticProfileCodec.Result r = CosmeticProfileCodec.parse(
				"{\"cosmetics\":{\"aura\":{\"style\":\"" + style.getId() + "\"}}}");
			check(r.problems().isEmpty() && r.loadout().piece(CosmeticSlot.AURA).style() == style, "hosted aura id " + style.getId() + " parses");
		}
		check(CosmeticStyle.byId(CosmeticSlot.PET, "owl") == CosmeticStyle.OWL && CosmeticStyle.byId(CosmeticSlot.AURA, "owl") == null,
			"pet ids belong to the pet slot");
	}

	/** Bounding box (pixels) of a placement list in the body frame. */
	private static float[] bounds(List<CosmeticRig.Placement> placements) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		Vector3f v = new Vector3f();
		for (CosmeticRig.Placement p : placements) {
			float[] pos = p.mesh().positions;
			for (int i = 0; i < pos.length; i += 3) {
				p.transform().transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
				b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
				b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
			}
		}
		return b;
	}

	private static void checkModels() {
		for (CosmeticStyle style : PETS) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(wear(style, 1f), CosmeticRig.Motion.standing(1.3f, 4));
			check(placements.size() >= 3 && placements.size() < 40, style + " is a handful of rigid parts (" + placements.size() + ")");
			int faces = 0;
			for (CosmeticRig.Placement p : placements) {
				check(p.mesh().faceCount() > 0, style + " has no empty part");
				faces += p.mesh().faceCount();
			}
			check(faces < 9000, style + " stays cheap to draw (" + faces + " faces)");
			float[] b = bounds(placements);
			float w = b[3] - b[0];
			float h = b[4] - b[1];
			float d = b[5] - b[2];
			float biggest = Math.max(w, Math.max(h, d));
			check(biggest > 6f && biggest < 24f, style + " is about head-sized (" + w + " x " + h + " x " + d + ")");
			float cx = (b[0] + b[3]) / 2f;
			float cy = (b[1] + b[4]) / 2f;
			check(cx > 5f && cx < 16f && cy > -10f && cy < 3f, style + " sits beside the shoulder (" + cx + ", " + cy + ")");
		}
		// The ghost and the bee match the sheet's proportions: ghost taller than wide, bee longer than tall.
		float[] ghost = bounds(CosmeticRig.place(wear(CosmeticStyle.GHOST, 1f), CosmeticRig.Motion.standing(0f, 4)));
		check(ghost[4] - ghost[1] > (ghost[3] - ghost[0]) * 1.05f, "the ghost is taller than wide");
		VoxelMesh beeBody = CosmeticModelsD.INSTANCE.getBEE().body;
		float minY = 1e9f, maxY = -1e9f, minZ = 1e9f, maxZ = -1e9f;
		for (int i = 0; i < beeBody.positions.length; i += 3) {
			minY = Math.min(minY, beeBody.positions[i + 1]); maxY = Math.max(maxY, beeBody.positions[i + 1]);
			minZ = Math.min(minZ, beeBody.positions[i + 2]); maxZ = Math.max(maxZ, beeBody.positions[i + 2]);
		}
		check(maxZ - minZ > (maxY - minY) * 1.2f, "the bee's body is longer than tall");
		float[] slime = bounds(CosmeticRig.place(wear(CosmeticStyle.SLIME_CUBE, 1f), CosmeticRig.Motion.standing(0f, 4)));
		check(Math.abs((slime[3] - slime[0]) - (slime[4] - slime[1])) < 2.5f, "the slime is a cube");
	}

	private static void checkColorChannels() {
		for (CosmeticStyle style : PETS) {
			double[] area = new double[3]; // channel 0, channel 1, fixed
			for (CosmeticRig.Placement p : CosmeticRig.place(wear(style, 1f), CosmeticRig.Motion.standing(0f, 4))) {
				VoxelMesh m = p.mesh();
				// Fixed-color see-through parts (bee wing blur) are glass, not paint.
				boolean allFixed = true;
				for (int face = 0; face < m.faceCount(); face++) allFixed &= (m.channels[face] & 0xFF) >= 2;
				if (allFixed && p.kind() == CosmeticRig.Kind.TRANSLUCENT) continue;
				for (int face = 0; face < m.faceCount(); face++) {
					int o = face * 12;
					float ax = m.positions[o + 3] - m.positions[o], ay = m.positions[o + 4] - m.positions[o + 1], az = m.positions[o + 5] - m.positions[o + 2];
					float bx = m.positions[o + 9] - m.positions[o], by = m.positions[o + 10] - m.positions[o + 1], bz = m.positions[o + 11] - m.positions[o + 2];
					double a = Math.sqrt(ax * ax + ay * ay + az * az) * Math.sqrt(bx * bx + by * by + bz * bz);
					int ch = m.channels[face] & 0xFF;
					area[ch >= 2 ? 2 : ch] += a;
				}
			}
			double total = area[0] + area[1] + area[2];
			check(area[0] / total > 0.3 && (area[0] + area[1]) / total > 0.6,
				style + " is mostly tinted by the player's colors (ch0 " + area[0] / total + ", ch1 " + area[1] / total + ", fixed " + area[2] / total + ")");
			check(area[1] / total > 0.002, style + " uses the second color somewhere");
			// A blue and white player must get a blue and white pet: both tints appear on visible faces.
			check(area[2] / total < 0.45, style + " keeps fixed colors to small accents");
		}
	}

	private static PetFollow follower() {
		// The older checks measure the follow spring against the shoulder spot, so this follower does not roam.
		return new PetFollow(false);
	}

	private static void checkFollow() {
		PetFollow follow = follower();
		float x = 0f, z = 0f, t = 100f;
		PetPose pose = follow.update(1, t, 0, 64, 0, 0f, 0f, 1f, 0f, 0f);
		check(near(pose.x(), PetPose.REST_X) && near(pose.y(), PetPose.REST_Y) && near(pose.z(), PetPose.REST_Z), "a new pet starts at the rest spot");
		float maxLag = 0f, maxJump = 0f, minZ = 0f;
		PetPose previous = pose;
		for (int frame = 1; frame <= 180; frame++) {
			t += 1f / 60f;
			z += 4.3f / 60f; // walking south (+z) with the body facing south (yaw 0)
			pose = follow.update(1, t, x, 64, z, 0f, 0f, 1f, 1f, 0f);
			maxLag = Math.max(maxLag, pose.z() - PetPose.REST_Z);
			maxJump = Math.max(maxJump, dist(pose, previous));
			previous = pose;
		}
		check(maxLag > 5f && maxLag < 16f, "walking, the pet trails behind the player (lag " + maxLag + " px)");
		check(maxJump < 1.5f, "the follow never jumps between frames (largest step " + maxJump + " px)");
		check(Math.abs(pose.yaw()) < 0.2f, "while walking forward the pet faces the way the player does (" + pose.yaw() + ")");
		for (int frame = 0; frame < 60 * 4; frame++) {
			t += 1f / 60f;
			pose = follow.update(1, t, x, 64, z, 0f, 0f, 1f, 0f, 0f);
			minZ = Math.min(minZ, pose.z() - PetPose.REST_Z);
			maxJump = Math.max(maxJump, dist(pose, previous));
			previous = pose;
		}
		check(near(pose.x(), PetPose.REST_X) && Math.abs(pose.z() - PetPose.REST_Z) < 0.5f && Math.abs(pose.y() - PetPose.REST_Y) < 0.5f,
			"when the player stops, the pet drifts back to its rest spot and settles");
		check(minZ > -6f, "the pet does not overshoot its rest spot wildly (" + minZ + ")");

		// Turning on the spot: the pet swings round behind the body, smoothly.
		float yaw = 0f;
		float maxTurnStep = 0f;
		float maxAngle = 0f;
		for (int frame = 0; frame < 30; frame++) {
			t += 1f / 60f;
			yaw += 6f; // 360 degrees per second
			pose = follow.update(1, t, x, 64, z, yaw, yaw, 1f, 0f, 0f);
			maxTurnStep = Math.max(maxTurnStep, dist(pose, previous));
			previous = pose;
			maxAngle = Math.max(maxAngle, (float) Math.hypot(pose.x() - PetPose.REST_X, pose.z() - PetPose.REST_Z));
		}
		check(maxTurnStep < 3f, "turning, the pet follows without jumping (" + maxTurnStep + ")");
		check(maxAngle > 3f, "turning fast makes the pet swing out behind (" + maxAngle + ")");

		// Teleport: snap, no streak across the map.
		t += 1f / 60f;
		pose = follow.update(1, t, 5000, 90, -3000, yaw, yaw, 1f, 0f, 0f);
		check(near(pose.x(), PetPose.REST_X) && near(pose.y(), PetPose.REST_Y) && near(pose.z(), PetPose.REST_Z) && !Float.isNaN(pose.phase()),
			"a teleport snaps the pet to the rest spot");
		PetPose snapped = pose;
		t += 1f / 60f;
		pose = follow.update(1, t, 5000.02, 90, -3000, yaw, yaw, 1f, 0f, 0f);
		check(dist(pose, snapped) < 2f && Math.abs(pose.x() - PetPose.REST_X) < 2f, "after the snap it keeps following normally");

		// The menu draws the same player at another place in between world frames; each keeps its own pet.
		PetFollow two = follower();
		float worldZ = 0f;
		float worstGui = 0f;
		for (int frame = 0; frame < 120; frame++) {
			float time = 10f + frame / 60f;
			worldZ += 4.3f / 60f;
			PetPose world = two.update(7, time, 0, 64, worldZ, 0f, 0f, 1f, 1f, 0f);
			PetPose gui = two.update(7, time + 0.0001f, 0, 0, 0, 0f, 0f, 1f, 0f, 0f);
			worstGui = Math.max(worstGui, Math.abs(gui.z() - PetPose.REST_Z));
			if (frame > 90) check(world.z() - PetPose.REST_Z > 4f, "the world pet keeps trailing while the menu pet is drawn too");
		}
		check(worstGui < 0.5f, "the menu pet stays at rest while the world pet moves");
		check(two.size() == 2, "two slots per player");

		// Scaled players get a scaled follow.
		PetFollow scaled = follower();
		PetPose big = scaled.update(3, 1f, 0, 64, 0, 0f, 0f, 2f, 0f, 0f);
		check(near(big.x(), PetPose.REST_X) && near(big.z(), PetPose.REST_Z), "player scale does not change the body-frame rest spot");
	}

	private static void checkAnimation() {
		for (CosmeticStyle style : PETS) {
			CosmeticLoadout loadout = wear(style, 1f);
			PetFollow follow = follower();
			CosmeticMotionTracker tracker = new CosmeticMotionTracker();
			float t = 50f;
			float z = 0f;
			List<CosmeticRig.Placement> previous = null;
			float maxDelta = 0f;
			String where = "";
			int frames = 60 * 14;
			int count = -1;
			for (int frame = 0; frame < frames; frame++) {
				t += 1f / 60f;
				boolean walking = frame >= 240 && frame < 600; // stand, walk (0 -> 1 and back), a jump at the end
				boolean air = frame > 700 && frame < 760;
				float rawMoving = walking ? 1f : 0f;
				if (walking) z += 4.3f / 60f;
				float yaw = frame > 420 && frame < 480 ? (frame - 420) * 3f : frame >= 480 ? 180f : 0f;
				CosmeticRig.Motion base = tracker.update(5, t, rawMoving, air);
				PetPose pose = follow.update(5, t, 0, 64 + (air ? 1.2 * Math.sin(Math.PI * (frame - 700) / 60.0) : 0.0), z, yaw, yaw, 1f, base.moving(), base.air());
				List<CosmeticRig.Placement> placements = CosmeticRig.place(loadout,
					new CosmeticRig.Motion(base.seconds(), base.seed(), base.moving(), base.air(), base.wingPhase(), base.swayPhase(), pose));
				if (count < 0) count = placements.size();
				check(placements.size() == count, style + " places a constant number of parts");
				if (previous != null) {
					for (int i = 0; i < placements.size(); i++) {
						// Buzzing bee wings and their blur copies are meant to blur; everything else must be smooth.
						if (placements.get(i).kind() == CosmeticRig.Kind.TRANSLUCENT && style == CosmeticStyle.BEE) continue;
						float delta = matrixDelta(previous.get(i).transform(), placements.get(i).transform());
						if (delta > maxDelta) { maxDelta = delta; where = "part " + i + " frame " + frame; }
					}
				}
				previous = placements;
			}
			check(maxDelta < 3f, style + " animates smoothly through standing, walking, turning and jumping (largest step " + maxDelta + " at " + where + ")");
		}
		// The hop functions are continuous and loop.
		float maxStep = 0f;
		for (int i = 0; i < 1000; i++) {
			float u = i / 1000f;
			maxStep = Math.max(maxStep, Math.abs(CosmeticRigD.hopLift(u + 0.001f) - CosmeticRigD.hopLift(u)));
			maxStep = Math.max(maxStep, Math.abs(CosmeticRigD.hopScale(u + 0.001f) - CosmeticRigD.hopScale(u)));
		}
		check(maxStep < 0.02f, "the slime's hop and squash are continuous (" + maxStep + ")");
		check(Math.abs(CosmeticRigD.hopScale(0f) - CosmeticRigD.hopScale(1f)) < 1e-4f, "the hop loops");
		check(CosmeticRigD.hopScale(0.1f) < 0.85f && CosmeticRigD.hopScale(0.6f) > 1.1f, "the slime squashes before the jump and stretches in the air");
	}

	private static void checkSpeedZero() {
		for (CosmeticStyle style : PETS) {
			CosmeticLoadout loadout = wear(style, 0f);
			PetPose a = PetPose.REST.copy(PetPose.REST.x(), PetPose.REST.y(), PetPose.REST.z(), 0f, 0f, 0f, 0f, 0f, 0f, 1.1f);
			PetPose b = PetPose.REST.copy(PetPose.REST.x(), PetPose.REST.y(), PetPose.REST.z(), 0f, 0f, 0f, 0f, 0f, 0f, 3.7f);
			List<CosmeticRig.Placement> one = CosmeticRig.place(loadout, motion(1f, 0f, 0f, a, 1.1f));
			List<CosmeticRig.Placement> two = CosmeticRig.place(loadout, motion(4.2f, 0f, 0f, b, 3.7f));
			float worst = 0f;
			for (int i = 0; i < one.size(); i++) worst = Math.max(worst, matrixDelta(one.get(i).transform(), two.get(i).transform()));
			check(worst < 1e-4f, style + " cycle animation freezes at speed 0 (" + worst + ")");
		}
	}

	private static void checkAuras() {
		for (CosmeticStyle style : AURAS) {
			int most = 0;
			for (int i = 0; i < 400; i++) {
				List<CosmeticRig.Placement> placements = CosmeticRig.place(wear(style, 1f), CosmeticRig.Motion.standing(i * 0.37f, i * 13 + 1));
				most = Math.max(most, placements.size());
				float[] b = bounds(placements);
				check(b[1] > -45f && b[4] < 30f && b[0] > -30f && b[3] < 30f && b[2] > -30f && b[5] < 30f,
					style + " stays around the player (" + java.util.Arrays.toString(b) + ")");
				for (CosmeticRig.Placement p : placements) {
					check(p.alpha() >= 0 && p.alpha() <= 255, style + " alpha is valid");
					check(p.mesh().faceCount() > 0, style + " parts are not empty");
				}
			}
			check(most >= 8 && most <= 40, style + " keeps its particle count bounded (" + most + ")");
		}
		// The storm cloud flashes: some moment has a glowing bolt, and not every moment does.
		int flashes = 0;
		for (int i = 0; i < 1000; i++) {
			for (CosmeticRig.Placement p : CosmeticRig.place(wear(CosmeticStyle.STORM_CLOUD, 1f), CosmeticRig.Motion.standing(i * 0.02f, 3))) {
				if (p.kind() == CosmeticRig.Kind.GLOW) { flashes++; break; }
			}
		}
		check(flashes > 20 && flashes < 400, "lightning flashes now and then (" + flashes + " of 1000 frames)");
		check(CosmeticRig.place(wear(CosmeticStyle.FLAME_RING, 1f), CosmeticRig.Motion.standing(2f, 3)).stream()
			.allMatch(p -> p.kind() == CosmeticRig.Kind.GLOW), "the flame ring glows");
	}

	private static void checkIdleLife() {
		int blinking = 0;
		float maxStep = 0f;
		float last = CosmeticRigD.blink(0f, 7, 1f);
		for (int i = 1; i < 60 * 30; i++) {
			float v = CosmeticRigD.blink(i / 60f, 7, 1f);
			if (v > 0.5f) blinking++;
			maxStep = Math.max(maxStep, Math.abs(v - last));
			last = v;
		}
		check(blinking > 6 && blinking < 60 * 30 / 5 && maxStep < 0.4f, "pets blink now and then, smoothly (" + blinking + " frames, step " + maxStep + ")");
		check(CosmeticRigD.blink(2.9f, 7, 0f) == 0f && CosmeticRigD.gaze(2.9f, 7, 0f, false) == 0f, "speed 0 stops blinking and looking around");
		float gazeStep = 0f;
		float min = 9f, max = -9f;
		float prev = CosmeticRigD.gaze(0f, 4, 1f, false);
		for (int i = 1; i < 60 * 60; i++) {
			float v = CosmeticRigD.gaze(i / 60f, 4, 1f, false);
			gazeStep = Math.max(gazeStep, Math.abs(v - prev));
			min = Math.min(min, v);
			max = Math.max(max, v);
			prev = v;
		}
		check(gazeStep < 0.05f && min < -0.3f && max > 0.5f, "gaze eases between targets and looks both ways (" + min + " .. " + max + ")");
		check(CosmeticRigD.gaze(5f, 1, 1f, false) != CosmeticRigD.gaze(5f, 2, 1f, false), "players do not look in sync");
	}

	private static void checkSettings() {
		CosmeticsModule module = CosmeticsModule.INSTANCE;
		CosmeticsModule.SlotSettings pet = module.slot(CosmeticSlot.PET);
		check(pet != null && pet.getSpeed().value() == CosmeticSlot.PET.getDefaultSpeed(), "the pet slot has its settings");
		try {
			check(module.toggle(CosmeticStyle.OWL) && pet.selected() == CosmeticStyle.OWL
				&& module.localLoadout().piece(CosmeticSlot.PET).style() == CosmeticStyle.OWL, "a pet can be worn from the menu");
			check(module.toggle(CosmeticStyle.GHOST) && pet.selected() == CosmeticStyle.GHOST, "another pet replaces it");
		} finally {
			pet.getStyle().setValue(CosmeticsModule.NONE);
		}
		for (CosmeticStyle style : PETS) {
			int[] pixels = CosmeticIcons.pixels(CosmeticPiece.of(style), 64, 1.5f);
			int drawn = 0;
			for (int p : pixels) if ((p >>> 24) > 0) drawn++;
			check(drawn > 64 * 64 / 12, style + " has a visible card icon (" + drawn + ")");
		}
		for (CosmeticStyle style : AURAS) {
			int[] pixels = CosmeticIcons.pixels(CosmeticPiece.of(style), 64, 1.5f);
			int drawn = 0;
			for (int p : pixels) if ((p >>> 24) > 0) drawn++;
			check(drawn > 64 * 64 / 12, style + " has a visible card icon (" + drawn + ")");
		}
	}

	/** Owner feedback 2026-11-22: the storm cloud clears tall ears; the pet moves between shoulders, head and floor. */
	private static void checkOwnerFeedback() {
		CosmeticPiece storm = CosmeticPiece.of(CosmeticStyle.STORM_CLOUD);
		VoxelMesh cloud = CosmeticModelsD.INSTANCE.getCLOUD();
		for (float size : new float[]{1f, 2f}) {
			CosmeticPiece e = CosmeticPiece.of(CosmeticStyle.BUNNY_EARS);
			CosmeticPiece ears = new CosmeticPiece(e.style(), e.color(), e.color2(), size, e.up(), e.back(), 0f);
			List<CosmeticRig.Placement> all = CosmeticRig.place(CosmeticLoadout.of(List.of(ears, storm)), CosmeticRig.Motion.standing(2f, 4));
			float earTop = bounds(all.stream().filter(p -> p.anchor() == CosmeticRig.Anchor.HEAD).toList())[1];
			float cloudBottom = bounds(all.stream().filter(p -> p.mesh() == cloud && p.kind() == CosmeticRig.Kind.SOLID).toList())[4];
			check(cloudBottom < earTop, "the storm cloud floats above size " + size + " bunny ears (cloud " + cloudBottom + ", ears " + earTop + ")");
		}
		float alone = bounds(CosmeticRig.place(CosmeticLoadout.of(List.of(storm)), CosmeticRig.Motion.standing(2f, 4)).stream()
			.filter(p -> p.mesh() == cloud && p.kind() == CosmeticRig.Kind.SOLID).toList())[4];
		float withGlasses = bounds(CosmeticRig.place(CosmeticLoadout.of(List.of(storm, CosmeticPiece.of(CosmeticStyle.SUNGLASSES))),
			CosmeticRig.Motion.standing(2f, 4)).stream().filter(p -> p.mesh() == cloud && p.kind() == CosmeticRig.Kind.SOLID).toList())[4];
		check(Math.abs(alone - withGlasses) < 1e-3f, "without tall head pieces the cloud keeps its tuned height");

		PetFollow roam = new PetFollow();
		float t = 0f;
		boolean shoulder = false, head = false, floor = false;
		float maxJump = 0f;
		PetPose previous = roam.update(5, t, 0, 64, 0, 0f, 0f, 1f, 0f, 0f);
		for (int frame = 0; frame < 60 * 240; frame++) {
			t += 1f / 60f;
			PetPose pose = roam.update(5, t, 0, 64, 0, 0f, 0f, 1f, 0f, 0f);
			maxJump = Math.max(maxJump, dist(pose, previous));
			previous = pose;
			if (pose.y() > 18f) floor = true;
			if (pose.y() < -10f && Math.abs(pose.x()) < 2f) head = true;
			if (Math.abs(Math.abs(pose.x()) - PetPose.REST_X) < 0.5f && Math.abs(pose.y() - PetPose.REST_Y) < 0.5f) shoulder = true;
		}
		check(shoulder && head && floor, "the pet visits the shoulders, the head and the floor (" + shoulder + ", " + head + ", " + floor + ")");
		check(maxJump < 1.5f, "moving between spots never jumps (largest step " + maxJump + " px)");
		PetFollow hatted = new PetFollow();
		boolean onHead = false;
		for (int frame = 0; frame < 60 * 240; frame++) {
			PetPose pose = hatted.update(5, frame / 60f, 0, 64, 0, 0f, 0f, 1f, 0f, 0f, false);
			onHead |= pose.y() < -8f;
		}
		check(!onHead, "with a hat or head piece the pet never sits on the head");
		// Airborne, the pet rides on the shoulder whatever spot it had.
		PetPose air = previous;
		for (int frame = 0; frame < 120; frame++) {
			t += 1f / 60f;
			air = roam.update(5, t, 0, 64, 0, 0f, 0f, 1f, 0f, 1f);
		}
		check(Math.abs(Math.abs(air.x()) - PetPose.REST_X) < 1f && Math.abs(air.y() - PetPose.REST_Y) < 1f,
			"in the air the pet holds on to a shoulder (" + air.x() + ", " + air.y() + ")");
	}

	private static float dist(PetPose a, PetPose b) {
		return (float) Math.sqrt((a.x() - b.x()) * (a.x() - b.x()) + (a.y() - b.y()) * (a.y() - b.y()) + (a.z() - b.z()) * (a.z() - b.z()));
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 1e-3f;
	}

	private static float matrixDelta(Matrix4f a, Matrix4f b) {
		float worst = 0f;
		float[] x = new float[16];
		float[] y = new float[16];
		a.get(x);
		b.get(y);
		for (int i = 0; i < 16; i++) worst = Math.max(worst, Math.abs(x[i] - y[i]));
		return worst;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	@SuppressWarnings("unused")
	private static List<String> unused() {
		return new ArrayList<>();
	}
}
