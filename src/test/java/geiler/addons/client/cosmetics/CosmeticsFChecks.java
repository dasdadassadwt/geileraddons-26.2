package geiler.addons.client.cosmetics;

import geiler.addons.client.module.impl.CosmeticsModule;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Trail and cape cosmetics: world history, world-to-model placement, sprites, ribbon, cape geometry, physics and settings. */
public final class CosmeticsFChecks {
	private static final List<CosmeticStyle> TRAILS = CosmeticSlot.TRAIL.styles();
	private static final List<CosmeticStyle> CAPES = CosmeticSlot.CAPE.styles();

	private CosmeticsFChecks() { }

	public static void run() {
		checkCatalogAndCodec();
		checkSprites();
		checkRibbonColors();
		checkWorldToModel();
		checkHistory();
		checkLiveTrails();
		checkTrailAnimation();
		checkCapeMeshes();
		checkCapePhysics();
		checkCapeColors();
		checkCapeAnimation();
		checkPreviewsAndCards();
		checkSettings();
	}

	private static CosmeticPiece piece(CosmeticStyle style) {
		return CosmeticPiece.of(style);
	}

	private static CosmeticRig.Motion motion(float seconds) {
		return CosmeticRig.Motion.standing(seconds, 11);
	}

	private static void checkCatalogAndCodec() {
		check(TRAILS.size() == 6 && CAPES.size() == 3, "six trail styles and three capes");
		for (CosmeticStyle style : CosmeticStyle.values()) {
			if (style.getSlot() != CosmeticSlot.TRAIL && style.getSlot() != CosmeticSlot.CAPE) continue;
			CosmeticProfileCodec.Result parsed = CosmeticProfileCodec.parse("{\"cosmetics\":{\"" + style.getSlot().getId()
				+ "\":{\"style\":\"" + style.getId() + "\",\"color\":\"#102030\",\"speed\":2}}}");
			CosmeticPiece worn = parsed.loadout().piece(style.getSlot());
			check(parsed.problems().isEmpty() && worn != null && worn.style() == style && worn.color() == 0x102030
				&& worn.color2() == style.getColor2() && worn.speed() == 2f, style.getId() + " parses from the hosted JSON");
			check(style.getId().matches("[a-z_]+"), style.getId() + " is a stable snake_case id");
		}
		CosmeticProfileCodec.Result both = CosmeticProfileCodec.parse(
			"{\"cosmetics\":{\"trail\":{\"style\":\"rainbow_ribbon\"},\"cape\":{\"style\":\"royal\",\"size\":1.2}}}");
		check(both.problems().isEmpty() && both.loadout().piece(CosmeticSlot.CAPE).size() == 1.2f
			&& both.loadout().piece(CosmeticSlot.TRAIL).style() == CosmeticStyle.RAINBOW_RIBBON,
			"a trail and a cape are worn together");
		check(!CosmeticProfileCodec.parse("{\"cosmetics\":{\"cape\":{\"style\":\"flame_footprints\"}}}").problems().isEmpty(),
			"a style of another slot is rejected");
	}

	private static void checkSprites() {
		for (VoxelMesh[] frames : List.of(TrailSprites.INSTANCE.getFLAME(), TrailSprites.INSTANCE.getFLOWER(),
			TrailSprites.INSTANCE.getSPARKLE(), TrailSprites.INSTANCE.getBUBBLE(), TrailSprites.INSTANCE.getENDER())) {
			check(frames.length == TrailSprites.FRAMES, "every sprite has six frames");
			for (int i = 0; i < frames.length; i++) {
				check(frames[i].faceCount() > 0 && frames[i].faceCount() < 1500, "sprite frame " + i + " is non-empty and cheap");
			}
			check(frames[0].faceCount() > frames[frames.length - 1].faceCount(),
				"the first frame has more art than the last (the sprite fades out)");
		}
		VoxelMesh flame = TrailSprites.INSTANCE.getFLAME()[0];
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		for (int i = 0; i < flame.positions.length; i += 3) {
			minY = Math.min(minY, flame.positions[i + 1]);
			maxY = Math.max(maxY, flame.positions[i + 1]);
			minZ = Math.min(minZ, flame.positions[i + 2]);
			maxZ = Math.max(maxZ, flame.positions[i + 2]);
		}
		check(maxY <= 0.001f && minY < -7f && minY > -10f, "a flame stands on its anchor and is about 8 px tall (" + minY + ")");
		check(Math.abs(maxZ - minZ - TrailSprites.VOXEL) < 1e-4f, "sprites are one half-pixel voxel thick");
		check(usesChannel(flame, 0) && usesChannel(flame, 1), "the flame uses both color channels");
		VoxelMesh flower = TrailSprites.INSTANCE.getFLOWER()[0];
		boolean gold = false;
		for (int i = 0; i < flower.faceCount(); i++) gold |= flower.tint(i, 1, 2) == 0xFFC82A;
		check(gold && usesChannel(flower, 0) && usesChannel(flower, 1), "flowers: petals, stem and a fixed gold center");
		check(!usesChannel(TrailSprites.INSTANCE.getENDER()[0], 7), "ender sparks only use the two channels");
	}

	private static boolean usesChannel(VoxelMesh mesh, int channel) {
		for (byte value : mesh.channels) if ((value & 0xFF) == channel) return true;
		return false;
	}

	private static void checkRibbonColors() {
		int[] rainbow = TrailSprites.bandColors(CosmeticStyle.RAINBOW_RIBBON.getColor(), CosmeticStyle.RAINBOW_RIBBON.getColor2());
		check(rainbow.length == TrailSprites.BANDS, "six bands");
		check(red(rainbow[0]) > 200 && green(rainbow[0]) < 90 && blue(rainbow[0]) < 90, "the top band is red");
		check(red(rainbow[2]) > 200 && green(rainbow[2]) > 190 && blue(rainbow[2]) < 100, "the third band is yellow");
		check(green(rainbow[3]) > 150 && red(rainbow[3]) < 100, "the fourth band is green");
		check(blue(rainbow[4]) > 180 && red(rainbow[4]) < 90, "the fifth band is blue");
		check(blue(rainbow[5]) > 180 && red(rainbow[5]) > 110 && green(rainbow[5]) < 90, "the last band is violet");
		int[] blueWhite = TrailSprites.bandColors(0x2040E0, 0xFFFFFF);
		check(blue(blueWhite[0]) > 200 && red(blueWhite[0]) < 60 && red(blueWhite[5]) > 235 && green(blueWhite[5]) > 235,
			"blue and white give a blue-to-white ribbon");
		boolean monotone = true;
		for (int i = 1; i < blueWhite.length; i++) monotone &= red(blueWhite[i]) >= red(blueWhite[i - 1]) - 1;
		check(monotone, "a blue-to-white ribbon never wanders through other hues");
		int[] solid = TrailSprites.bandColors(0x336699, 0x336699);
		check(solid[0] == solid[5], "two equal colors give a solid ribbon");
		for (int mask = 1; mask < 64; mask++) check(TrailSprites.ribbonCell(0xE8262B, 0x9B30E8, mask).faceCount() >= 3,
			"every band mask bakes");
		check(TrailSprites.ribbonCell(1, 2, 63) == TrailSprites.ribbonCell(1, 2, 63), "ribbon cells are cached");
		for (int i = 0; i < 400; i++) TrailSprites.ribbonCell(i * 977, i, 63);
		check(TrailSprites.cachedCells() <= 192, "the ribbon cell cache is bounded");
		check(TrailSprites.ribbonCell(0xE8262B, 0x9B30E8, 63).faceCount() > TrailSprites.ribbonCell(0xE8262B, 0x9B30E8, 1).faceCount(),
			"a ribbon that lost bands has fewer faces");
	}

	private static int red(int rgb) { return rgb >> 16 & 0xFF; }

	private static int green(int rgb) { return rgb >> 8 & 0xFF; }

	private static int blue(int rgb) { return rgb & 0xFF; }

	private static void checkWorldToModel() {
		TrailRig.Frame frame = new TrailRig.Frame();
		float[] model = new float[3];
		double[] world = new double[3];
		double[][] points = {{10.5, 64.0, -3.25}, {12.0, 65.5, -1.0}, {-30000.5, 70.0, 29999.5}};
		for (double[] p : points) {
			for (float rot : new float[]{0f, 37f, 90f, 180f, -123f}) {
				for (float scale : new float[]{1f, 0.5f, 2f}) {
					frame.set(p[0] + 1.5, p[1] - 0.25, p[2] + 2.0, rot, scale, 1f, 1.5f, 0.75f, 0.0);
					TrailRig.toModel(p[0], p[1], p[2], frame, model);
					TrailRig.toWorld(model[0], model[1], model[2], frame, world);
					check(Math.abs(world[0] - p[0]) < 2e-3 && Math.abs(world[1] - p[1]) < 2e-3 && Math.abs(world[2] - p[2]) < 2e-3,
						"toModel and toWorld are inverses (rot " + rot + ", scale " + scale + ")");
				}
			}
		}
		// The player's own origin is the model origin, and the feet are 24 px below the neck.
		frame.set(5.0, 70.0, 5.0, 123f, 1f, 1f, 1f, 1f, 0.0);
		TrailRig.toModel(5.0, 70.0, 5.0, frame, model);
		check(Math.abs(model[0]) < 1e-4f && Math.abs(model[1] - 24.016f) < 0.01f && Math.abs(model[2]) < 1e-4f,
			"the player's feet are at the model origin's 24 px below the neck");
		// Facing south (body yaw 0): a point behind the player (north, -z) is on the model's +z side, a point on the
		// player's left (east, +x) on the model's +x side.
		frame.set(0.0, 0.0, 0.0, 0f, 1f, 1f, 1f, 1f, 0.0);
		TrailRig.toModel(0.0, 0.0, -2.0, frame, model);
		check(model[2] > 31f && Math.abs(model[0]) < 1e-3f, "behind the player is +z in model space (" + model[2] + ")");
		TrailRig.toModel(2.0, 0.0, 0.0, frame, model);
		check(model[0] > 31f, "the player's left is +x in model space (" + model[0] + ")");
		// Walking forward: a point left behind drifts further back in the model frame.
		frame.set(0.0, 0.0, 0.0, 0f, 1f, 1f, 1f, 1f, 0.0);
		TrailRig.toModel(0.0, 0.0, 0.0, frame, model);
		float before = model[2];
		frame.set(0.0, 0.0, 1.0, 0f, 1f, 1f, 1f, 1f, 0.0);
		TrailRig.toModel(0.0, 0.0, 0.0, frame, model);
		check(model[2] - before > 15.9f && model[2] - before < 16.1f, "one block walked is 16 px behind");
	}

	/** Walks the player straight along +z (body yaw 0) at [speed] blocks per second; returns the final clock. */
	private static double walk(TrailHistory history, int id, CosmeticStyle style, double from, double to, double speed, boolean ground) {
		double t = from;
		while (t <= to + 1e-9) {
			history.update(id, style, t, 0.0, 64.0, speed * t, ground, 7, 0f);
			t += 1.0 / 60.0;
		}
		return t;
	}

	private static void checkHistory() {
		for (CosmeticStyle style : TRAILS) {
			TrailHistory history = new TrailHistory();
			TrailHistory.Track standing = null;
			for (int frame = 0; frame < 600; frame++) standing = history.update(1, style, 100 + frame / 60.0, 3.0, 64.0, 3.0, true, 7, 0f);
			check(standing.getSize() == 0, style.getId() + " spawns nothing while standing");

			walk(history, 1, style, 110, 112, 4.3, true);
			int walking = history.update(1, style, 112.02, 0.0, 64.0, 4.3 * 112.02, true, 7, 0f).getSize();
			check(walking >= 3, style.getId() + " spawns while walking (" + walking + ")");
			// Stop: no new events, and every old one expires.
			double stopX = 4.3 * 112.02;
			int stoppedSize = -1;
			for (int frame = 0; frame < 6 * 60; frame++) {
				TrailHistory.Track track = history.update(1, style, 112.03 + frame / 60.0, 0.0, 64.0, stopX, true, 7, 0f);
				if (frame == 1) stoppedSize = track.getSize();
				check(frame < 2 || track.getSize() <= stoppedSize, style.getId() + " adds nothing once the player stops");
			}
			check(history.update(1, style, 125, 0.0, 64.0, stopX, true, 7, 0f).getSize() == 0, style.getId() + " events expire");

			// A long sprint stays inside the cap.
			TrailHistory sprint = new TrailHistory();
			int most = 0;
			double t = 0;
			for (int frame = 0; frame < 60 * 60; frame++) {
				t = frame / 60.0;
				most = Math.max(most, sprint.update(2, style, t, 0.0, 64.0, 8.0 * t, true, 7, 0f).getSize());
			}
			check(most <= TrailHistory.CAPACITY && most > 3, style.getId() + " history is capped (" + most + ")");

			// Teleports and clock jumps start over.
			sprint.update(2, style, t + 0.02, 500.0, 64.0, 500.0, true, 7, 0f);
			check(sprint.update(2, style, t + 0.04, 500.0, 64.0, 500.0, true, 7, 0f).getSize() == 0, style.getId() + " forgets a teleport");

			// Switching the style starts a fresh track.
			TrailHistory other = new TrailHistory();
			walk(other, 3, style, 0, 3, 5.0, true);
			CosmeticStyle next = TRAILS.get((TRAILS.indexOf(style) + 1) % TRAILS.size());
			check(other.update(3, next, 3.02, 0.0, 64.0, 5.0 * 3.02, true, 7, 0f).getSize() <= 1, style.getId() + " resets when the style changes");
		}
		// Footsteps only on the ground, alternating sides.
		TrailHistory air = new TrailHistory();
		walk(air, 1, CosmeticStyle.FLAME_FOOTPRINTS, 0, 3, 4.3, false);
		check(air.update(1, CosmeticStyle.FLAME_FOOTPRINTS, 3.02, 0.0, 64.0, 4.3 * 3.02, false, 7, 0f).getSize() == 0,
			"no footprints while airborne");
		TrailHistory feet = new TrailHistory();
		walk(feet, 1, CosmeticStyle.FLAME_FOOTPRINTS, 0, 3, 4.3, true);
		TrailHistory.Track steps = feet.update(1, CosmeticStyle.FLAME_FOOTPRINTS, 3.02, 0.0, 64.0, 4.3 * 3.02, true, 7, 0f);
		boolean alternates = steps.getSize() >= 3;
		for (int i = 1; i < steps.getSize(); i++) alternates &= steps.side[steps.at(i)] != steps.side[steps.at(i - 1)];
		double spacing = steps.z[steps.at(1)] - steps.z[steps.at(0)];
		check(alternates && Math.abs(spacing - TrailHistory.spec(CosmeticStyle.FLAME_FOOTPRINTS).spacing()) < 0.02,
			"footprints alternate feet one stride apart (" + spacing + ")");
		check(steps.x[steps.at(0)] != 0.0 && steps.x[steps.at(0)] * steps.x[steps.at(1)] < 0, "feet are left and right of the walk line");
		// Players that are no longer rendered are forgotten.
		TrailHistory crowd = new TrailHistory();
		crowd.update(1, CosmeticStyle.SPARKLE_TRAIL, 0.0, 0.0, 64.0, 0.0, true, 1, 0f);
		crowd.update(2, CosmeticStyle.SPARKLE_TRAIL, 10.0, 0.0, 64.0, 0.0, true, 2, 0f);
		check(crowd.players() == 1, "a player not rendered for a while is dropped");
		for (int id = 0; id < 100; id++) crowd.update(1000 + id, CosmeticStyle.SPARKLE_TRAIL, 10.5 + id * 0.001, 0.0, 64.0, 0.0, true, id, 0f);
		check(crowd.players() <= TrailHistory.MAX_PLAYERS + 100 && crowd.events() <= (TrailHistory.MAX_PLAYERS + 100) * TrailHistory.CAPACITY,
			"the store is bounded");
		crowd.update(1, CosmeticStyle.SPARKLE_TRAIL, 14.0, 0.0, 64.0, 0.0, true, 1, 0f);
		check(crowd.players() <= TrailHistory.MAX_PLAYERS, "a crowd is trimmed to the player cap");
	}

	private static List<CosmeticRig.Placement> live(CosmeticPiece piece, TrailHistory.Track track, double seconds, double z, float rot,
		CosmeticRig.Motion motion) {
		TrailRig.Frame frame = new TrailRig.Frame().set(0.0, 64.0, z, rot, 1f, 1f, 1f, 1f, seconds);
		List<CosmeticRig.Placement> out = new ArrayList<>();
		TrailRig.live(piece, track, motion, frame, out);
		return out;
	}

	private static void checkLiveTrails() {
		for (CosmeticStyle style : TRAILS) {
			TrailHistory history = new TrailHistory();
			double t = walk(history, 1, style, 0, 3, 4.3, true);
			double now = t - 1.0 / 60.0;
			TrailHistory.Track track = history.update(1, style, now, 0.0, 64.0, 4.3 * now, true, 7, 0f);
			List<CosmeticRig.Placement> placements = live(piece(style), track, now, 4.3 * now, 0f, motion(3f));
			check(placements.size() > 3 && placements.size() <= TrailRig.MAX_PLACEMENTS + 8, style.getId() + " draws its trail (" + placements.size() + ")");
			boolean world = true;
			float farthest = 0f;
			for (CosmeticRig.Placement p : placements) {
				world &= p.anchor() == CosmeticRig.Anchor.WORLD && p.mesh().faceCount() > 0;
				Vector3f at = p.transform().getTranslation(new Vector3f());
				check(Float.isFinite(at.x) && Float.isFinite(at.y) && Float.isFinite(at.z), "finite placement");
				farthest = Math.max(farthest, at.z);
			}
			check(world, style.getId() + " placements are in world space");
			check(farthest > 12f, style.getId() + " reaches back behind the player (" + farthest + " px)");

			// Standing still: the trail finishes fading and nothing is left.
			double later = now + 3.0;
			TrailHistory.Track stopped = history.update(1, style, later, 0.0, 64.0, 4.3 * now, true, 7, 0f);
			List<CosmeticRig.Placement> gone = live(piece(style), stopped, later, 4.3 * now, 0f, motion(3f));
			check(style == CosmeticStyle.RAINBOW_RIBBON ? gone.size() <= 1 : gone.isEmpty(), style.getId() + " leaves nothing after fading");
		}
		check(TrailRig.lifeOf(CosmeticStyle.FLAME_FOOTPRINTS, 0f) >= 1f && TrailRig.lifeOf(CosmeticStyle.FLOWER_STEPS, 0.5f) <= 2.5f,
			"lifetimes are about one to two seconds");
		for (CosmeticStyle style : TRAILS) {
			for (float r : new float[]{0f, 0.5f, 0.999f}) {
				check(TrailRig.lifeOf(style, r) <= TrailHistory.spec(style).maxLife(), style.getId() + " lives no longer than the history keeps it");
			}
		}

		// A point left in the world stays fixed while the player walks away and turns.
		CosmeticStyle style = CosmeticStyle.FLAME_FOOTPRINTS;
		TrailHistory history = new TrailHistory();
		double t = walk(history, 1, style, 0, 2, 4.3, true);
		double now = t - 1.0 / 60.0;
		TrailHistory.Track track = history.update(1, style, now, 0.0, 64.0, 4.3 * now, true, 7, 0f);
		TrailRig.Frame a = new TrailRig.Frame().set(0.0, 64.0, 4.3 * now, 0f, 1f, 1f, 1f, 1f, now);
		TrailRig.Frame b = new TrailRig.Frame().set(0.0, 64.0, 4.3 * now, 0f, 1f, 1f, 1f, 1f, now);
		List<CosmeticRig.Placement> out = new ArrayList<>();
		TrailRig.live(piece(style), track, motion(2f), a, out);
		// Same moment, but the player now stands elsewhere and has turned: the same events, converted for the new pose.
		TrailRig.Frame moved = new TrailRig.Frame().set(3.0, 64.0, 4.3 * now + 5.0, 110f, 1f, 1f, 1f, 1f, now);
		List<CosmeticRig.Placement> out2 = new ArrayList<>();
		TrailRig.live(piece(style), track, motion(2f), moved, out2);
		check(out.size() == out2.size() && out.size() > 2, "the same events are drawn for any pose");
		double[] w1 = new double[3];
		double[] w2 = new double[3];
		for (int i = 0; i < out.size(); i++) {
			Vector3f m1 = out.get(i).transform().getTranslation(new Vector3f());
			Vector3f m2 = out2.get(i).transform().getTranslation(new Vector3f());
			TrailRig.toWorld(m1.x, m1.y, m1.z, a, w1);
			TrailRig.toWorld(m2.x, m2.y, m2.z, moved, w2);
			check(Math.abs(w1[0] - w2[0]) < 2e-3 && Math.abs(w1[1] - w2[1]) < 2e-3 && Math.abs(w1[2] - w2[2]) < 2e-3,
				"a trail particle stays fixed in the world while the player moves and turns");
		}
		check(b != null, "frames are reusable objects");
		// And it does move in model space as the player walks on.
		Vector3f first = out.get(out.size() - 1).transform().getTranslation(new Vector3f());
		double later = now + 0.5;
		TrailRig.Frame ahead = new TrailRig.Frame().set(0.0, 64.0, 4.3 * later, 0f, 1f, 1f, 1f, 1f, later);
		List<CosmeticRig.Placement> out3 = new ArrayList<>();
		TrailRig.live(piece(style), track, motion(2f), ahead, out3);
		Vector3f shifted = out3.get(out3.size() - 1).transform().getTranslation(new Vector3f());
		check(shifted.z - first.z > 30f, "walking on moves the particle back in the player's frame (" + (shifted.z - first.z) + ")");
	}

	private static void checkTrailAnimation() {
		for (CosmeticStyle style : TRAILS) {
			TrailHistory history = new TrailHistory();
			double t = walk(history, 1, style, 0, 3, 4.3, true);
			double now = 3.0;
			double z = 4.3 * now;
			TrailHistory.Track track = history.update(1, style, now, 0.0, 64.0, z, true, 7, 0f);
			// Smooth: one rendered frame later every particle has moved only a little (the player stands still).
			List<CosmeticRig.Placement> a = live(piece(style), track, now, z, 0f, motion(3f));
			List<CosmeticRig.Placement> b = live(piece(style), track, now + 1.0 / 60.0, z, 0f, motion(3f + 1f / 60f));
			if (a.size() == b.size()) {
				float step = 0f;
				for (int i = 0; i < a.size(); i++) {
					Vector3f p = a.get(i).transform().getTranslation(new Vector3f());
					Vector3f q = b.get(i).transform().getTranslation(new Vector3f());
					step = Math.max(step, p.distance(q));
				}
				check(step < 0.6f, style.getId() + " moves smoothly from frame to frame (" + step + " px)");
			}
			// Speed 0 freezes the twinkle, flicker and wobble.
			CosmeticPiece frozen = new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 0f);
			List<CosmeticRig.Placement> f1 = live(frozen, track, now, z, 0f, CosmeticRig.Motion.standing(3f, 11));
			List<CosmeticRig.Placement> f2 = live(frozen, track, now, z, 0f, CosmeticRig.Motion.standing(3.37f, 11));
			boolean same = f1.size() == f2.size();
			for (int i = 0; same && i < f1.size(); i++) {
				same = f1.get(i).transform().equals(f2.get(i).transform()) && f1.get(i).mesh() == f2.get(i).mesh()
					&& f1.get(i).alpha() == f2.get(i).alpha();
			}
			check(same, style.getId() + " speed 0 freezes the cycle animations");
			check(t > 0, "walked");
		}
		// Different players do not blink in step.
		TrailHistory history = new TrailHistory();
		walk(history, 1, CosmeticStyle.SPARKLE_TRAIL, 0, 3, 4.3, true);
		TrailHistory.Track track = history.update(1, CosmeticStyle.SPARKLE_TRAIL, 3.0, 0.0, 64.0, 12.9, true, 7, 0f);
		List<CosmeticRig.Placement> one = live(piece(CosmeticStyle.SPARKLE_TRAIL), track, 3.0, 12.9, 0f, CosmeticRig.Motion.standing(3f, 1));
		check(!one.isEmpty(), "sparkles drawn");
	}

	private static void checkCapeMeshes() {
		for (CosmeticStyle style : CAPES) {
			VoxelMesh mesh = CapeRig.mesh(style);
			check(mesh.faceCount() > 100 && mesh.faceCount() < 2500, style.getId() + " cape is detailed but cheap (" + mesh.faceCount() + ")");
			float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
			for (int i = 0; i < mesh.positions.length; i += 3) {
				minX = Math.min(minX, mesh.positions[i]);
				maxX = Math.max(maxX, mesh.positions[i]);
				minY = Math.min(minY, mesh.positions[i + 1]);
				maxY = Math.max(maxY, mesh.positions[i + 1]);
				minZ = Math.min(minZ, mesh.positions[i + 2]);
				maxZ = Math.max(maxZ, mesh.positions[i + 2]);
			}
			check(minX == -5f && maxX == 5f && minY == 0f && maxY == 16f, style.getId() + " cape is 10 x 16 px like vanilla");
			check(maxZ == 0f && minZ <= -1f && minZ >= -1.5f, style.getId() + " cape is one pixel thick (plus half-pixel relief): " + minZ + ".." + maxZ);
			check(mesh.palette.length >= 1, style.getId() + " has fixed accent colors");
			check(!CapeRig.overlays(style).isEmpty() || style == CosmeticStyle.ROYAL_CAPE, style.getId() + " has animated overlays");
		}
		check(CapeRig.mesh(CosmeticStyle.DRAGON_SCALE_CAPE) == CapeRig.mesh(CosmeticStyle.DRAGON_SCALE_CAPE), "capes are baked once");
	}

	private static void checkCapePhysics() {
		CosmeticPiece cape = piece(CosmeticStyle.ROYAL_CAPE);
		// At rest vanilla tilts the cape 6 degrees: its outer face looks straight back and a hair down.
		Vector3f normal = CapeRig.matrix(cape, 0f, 0f, 0f).transformDirection(0f, 0f, -1f, new Vector3f());
		check(Math.abs(normal.x) < 1e-5f && Math.abs(normal.y + Math.sin(Math.toRadians(6))) < 1e-4f && normal.z > 0.99f,
			"the resting cape faces back, tilted 6 degrees like vanilla (" + normal + ")");
		Vector3f hem0 = CapeRig.matrix(cape, 0f, 0f, 0f).transformPosition(0f, 16f, -1f, new Vector3f());
		Vector3f hem1 = CapeRig.matrix(cape, 40f, 0f, 0f).transformPosition(0f, 16f, -1f, new Vector3f());
		Vector3f hem2 = CapeRig.matrix(cape, 0f, 20f, 0f).transformPosition(0f, 16f, -1f, new Vector3f());
		check(hem0.z > 2.5f && hem0.y > 15f && hem0.y < 16f, "the hem hangs behind the body (" + hem0 + ")");
		check(hem1.z > hem0.z + 3f && hem2.z > hem0.z + 3f, "lean and flap swing the hem backward");
		Vector3f top = CapeRig.matrix(cape, 30f, 10f, 20f).transformPosition(0f, 0f, 0f, new Vector3f());
		check(top.distance(new Vector3f(0f, 0f, 2f)) < 1e-4f, "the hinge stays two pixels behind the neck");
		Vector3f side = CapeRig.matrix(cape, 0f, 0f, 40f).transformPosition(0f, 16f, -1f, new Vector3f());
		check(Math.abs(side.x) > 3f, "lean2 swings the cape sideways (" + side.x + ")");
		float x0 = CapeRig.matrix(cape, 12f, 3f, 8f).transformPosition(5f, 8f, -1f, new Vector3f()).x;
		float x1 = CapeRig.matrix(new CosmeticPiece(cape.style(), 0, 0, 2f, 0f, 0f, 1f), 12f, 3f, 8f).transformPosition(5f, 8f, -1f, new Vector3f()).x;
		check(Math.abs(x1 - 2 * x0) < 1e-3f, "Size scales the cape about its hinge");
		Vector3f raised = CapeRig.matrix(new CosmeticPiece(cape.style(), 0, 0, 1f, 3f, 2f, 1f), 0f, 0f, 0f)
			.transformPosition(0f, 0f, 0f, new Vector3f());
		check(Math.abs(raised.y + 3f) < 1e-4f && Math.abs(raised.z - 4f) < 1e-4f, "Height and Depth move the hinge");
		List<CosmeticRig.Placement> out = new ArrayList<>();
		CapeRig.place(cape, motion(2f), 5f, 0f, 0f, CosmeticRig.Anchor.CAPE_ARMOR, out);
		check(!out.isEmpty() && out.get(0).anchor() == CosmeticRig.Anchor.CAPE_ARMOR && out.get(0).mesh() == CapeRig.mesh(cape.style()),
			"the cape is the first placement, in the anchor it was given");
	}

	private static void checkCapeColors() {
		float[] dragon = CapeRig.channelShare(CosmeticStyle.DRAGON_SCALE_CAPE);
		check(dragon[0] > 0.3f && dragon[1] > 0.2f && dragon[2] < 0.1f,
			"dragon scale: scales are color, gaps are second color, only the clasp is fixed (" + dragon[0] + "/" + dragon[1] + "/" + dragon[2] + ")");
		float[] star = CapeRig.channelShare(CosmeticStyle.STARFIELD_CAPE);
		check(star[1] > 0.7f && star[0] > 0.01f && star[2] < 0.25f,
			"starfield: the sky is second color, red stars are color (" + star[0] + "/" + star[1] + "/" + star[2] + ")");
		float[] royal = CapeRig.channelShare(CosmeticStyle.ROYAL_CAPE);
		check(royal[0] > 0.35f && royal[1] > 0.04f, "royal: velvet is color, collar and ermine tails are second color");
		for (CosmeticStyle style : CAPES) {
			int blue = 0;
			int light = 0;
			for (int y = 0; y < CapeRig.HEIGHT; y++) {
				for (int x = 0; x < CapeRig.WIDTH; x++) {
					int argb = CapeRig.colorAt(style, false, x, y, 0x2040D0, 0xFFFFFF);
					if (blue(argb) > red(argb) + 40 && blue(argb) > green(argb) + 25) blue++;
					if (red(argb) > 120 && green(argb) > 120 && blue(argb) > 120) light++;
				}
			}
			float total = CapeRig.WIDTH * CapeRig.HEIGHT;
			check(blue / total > 0.02f && light / total > 0.04f,
				style.getId() + " with blue and white is clearly blue and white (" + blue / total + ", " + light / total + ")");
		}
		int dark = 0;
		for (int y = 0; y < CapeRig.HEIGHT; y++) {
			for (int x = 0; x < CapeRig.WIDTH; x++) {
				int argb = CapeRig.colorAt(CosmeticStyle.STARFIELD_CAPE, false, x, y, 0xFF3030, 0xFFFFFF);
				if (red(argb) > 200 && green(argb) > 200 && blue(argb) > 200) dark++;
			}
		}
		check(dark > CapeRig.WIDTH * CapeRig.HEIGHT / 2, "a white second color makes the starfield sky white");
	}

	private static void checkCapeAnimation() {
		CosmeticPiece star = piece(CosmeticStyle.STARFIELD_CAPE);
		CosmeticPiece scales = piece(CosmeticStyle.DRAGON_SCALE_CAPE);
		for (CosmeticPiece piece : List.of(star, scales)) {
			int overlayCount = CapeRig.overlays(piece.style()).size();
			check(overlayCount >= 2, piece.style().getId() + " has animated overlays");
			// Smooth: alphas change a little per frame.
			int worst = 0;
			List<CosmeticRig.Placement> previous = null;
			for (int frame = 0; frame < 400; frame++) {
				CosmeticRig.Motion m = CosmeticRig.Motion.standing(frame / 60f, 3);
				List<CosmeticRig.Placement> now = new ArrayList<>();
				CapeRig.place(piece, m, 0f, 0f, 0f, CosmeticRig.Anchor.CAPE, now);
				if (previous != null && previous.size() == now.size()) {
					for (int i = 1; i < now.size(); i++) worst = Math.max(worst, Math.abs(now.get(i).alpha() - previous.get(i).alpha()));
				}
				previous = now;
			}
			check(worst < 12, piece.style().getId() + " overlay brightness changes smoothly (" + worst + ")");
			CosmeticPiece frozen = new CosmeticPiece(piece.style(), piece.color(), piece.color2(), 1f, 0f, 0f, 0f);
			List<CosmeticRig.Placement> a = new ArrayList<>();
			List<CosmeticRig.Placement> b = new ArrayList<>();
			CapeRig.place(frozen, CosmeticRig.Motion.standing(1f, 3), 0f, 0f, 0f, CosmeticRig.Anchor.CAPE, a);
			CapeRig.place(frozen, CosmeticRig.Motion.standing(9.3f, 3), 0f, 0f, 0f, CosmeticRig.Anchor.CAPE, b);
			boolean same = a.size() == b.size();
			for (int i = 0; same && i < a.size(); i++) same = a.get(i).alpha() == b.get(i).alpha();
			check(same, piece.style().getId() + " speed 0 freezes the shimmer");
		}
	}

	private static void checkPreviewsAndCards() {
		for (CosmeticStyle style : TRAILS) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(CosmeticLoadout.of(List.of(piece(style))), motion(0.6f));
			check(placements.size() > 3, style.getId() + " has a synthetic walk for its picture (" + placements.size() + ")");
			check(CosmeticRig.place(CosmeticLoadout.of(List.of(piece(style))), motion(0.6f), true).isEmpty(),
				style.getId() + " is added by the live path in game, not by the generic place");
		}
		for (CosmeticStyle style : CAPES) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(CosmeticLoadout.of(List.of(piece(style))), motion(0.6f));
			check(!placements.isEmpty() && placements.get(0).mesh() == CapeRig.mesh(style), style.getId() + " has a resting cape for its picture");
		}
		int[] bare = CosmeticIconRenderer.render(List.of(), null, CosmeticIconRenderer.Stand.FULL, 96, CosmeticIcons.YAW, CosmeticIcons.PITCH);
		for (CosmeticStyle style : new ArrayList<>(TRAILS) {{ addAll(CAPES); }}) {
			int[] card = CosmeticIcons.pixels(piece(style), 96, 0.6f);
			int drawn = 0;
			int different = 0;
			for (int i = 0; i < card.length; i++) {
				if ((card[i] >>> 24) > 0) drawn++;
				if (card[i] != bare[i]) different++;
			}
			check(drawn > 96 * 96 / 20 && different > 150, style.getId() + " has a visible card picture (" + drawn + " drawn, " + different + " changed)");
		}
	}

	private static void checkSettings() {
		CosmeticsModule module = CosmeticsModule.INSTANCE;
		for (CosmeticSlot slot : List.of(CosmeticSlot.TRAIL, CosmeticSlot.CAPE)) {
			CosmeticsModule.SlotSettings settings = module.slot(slot);
			check(settings.getStyle() != null && settings.getSize().value() == slot.getDefaultSize()
				&& settings.getSpeed().value() == slot.getDefaultSpeed(), slot.getId() + " has style, size and speed settings");
			CosmeticStyle first = slot.styles().get(0);
			try {
				check(module.toggle(first) && module.localLoadout().piece(slot).style() == first, slot.getId() + " can be worn from the menu");
				check(module.slot(slot).piece(first).color() == first.getColor(), slot.getId() + " uses the style's colors by default");
			} finally {
				settings.getStyle().setValue(CosmeticsModule.NONE);
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
