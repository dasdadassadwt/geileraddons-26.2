package geiler.addons.client.cosmetics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Cosmetics batch A: dragon, fairy, phoenix, mechanical and crystal wings; dragon, fox and devil tails; unicorn horn. */
public final class CosmeticsAChecks {
	private static final CosmeticStyle[] STYLES = {
		CosmeticStyle.DRAGON_WINGS, CosmeticStyle.FAIRY_WINGS, CosmeticStyle.PHOENIX_WINGS,
		CosmeticStyle.MECHANICAL_WINGS, CosmeticStyle.CRYSTAL_WINGS, CosmeticStyle.UNICORN_HORN,
		CosmeticStyle.DRAGON_TAIL, CosmeticStyle.FOX_TAIL, CosmeticStyle.DEVIL_TAIL};

	private CosmeticsAChecks() { }

	public static void run() {
		checkCodec();
		checkMeshes();
		checkChannels();
		checkContinuity();
		checkSpeedZero();
		checkSeed();
		checkIcons();
		checkPhoenixTiers();
	}

	/** Average vertex height (body frame, y down) of the placements drawing [mesh]. */
	private static float meanY(List<CosmeticRig.Placement> placements, VoxelMesh mesh) {
		Vector3f v = new Vector3f();
		double sum = 0;
		int n = 0;
		for (CosmeticRig.Placement p : placements) {
			if (p.mesh() != mesh) continue;
			for (int i = 0; i < mesh.positions.length; i += 3) {
				p.transform().transformPosition(mesh.positions[i], mesh.positions[i + 1], mesh.positions[i + 2], v);
				sum += v.y;
				n++;
			}
		}
		return (float) (sum / n);
	}

	/** Owner feedback 2026-11-22: the phoenix's two feather sets hid behind each other; the primaries now fan out above. */
	private static void checkPhoenixTiers() {
		CosmeticPiece d = CosmeticPiece.of(CosmeticStyle.PHOENIX_WINGS);
		CosmeticPiece piece = new CosmeticPiece(d.style(), d.color(), d.color2(), d.size(), d.up(), d.back(), 0f);
		List<CosmeticRig.Placement> placements = place(piece, CosmeticRig.Motion.standing(1f, 2));
		VoxelMesh[] parts = CosmeticModelsA.INSTANCE.getPHOENIX_WING();
		float lower = meanY(placements, parts[0]);
		float upper = meanY(placements, parts[1]);
		check(upper < lower - 3f, "the phoenix primaries form an upper tier above the lower set (" + upper + " vs " + lower + ")");
	}

	private static List<CosmeticRig.Placement> place(CosmeticPiece piece, CosmeticRig.Motion motion) {
		return CosmeticRig.place(CosmeticLoadout.of(List.of(piece)), motion);
	}

	private static void checkCodec() {
		for (CosmeticStyle style : STYLES) {
			String json = "{\"cosmetics\":{\"" + style.getSlot().getId() + "\":{\"style\":\"" + style.getId() + "\"}}}";
			CosmeticProfileCodec.Result result = CosmeticProfileCodec.parse(json);
			CosmeticPiece piece = result.loadout().piece(style.getSlot());
			check(result.problems().isEmpty() && piece != null && piece.style() == style && piece.color() == style.getColor(),
				style + " parses from hosted JSON with its default colors");
			check(CosmeticStyle.byId(style.getSlot(), style.getId()) == style && style.getSlot().styles().contains(style),
				style + " is listed in its slot for the menu");
		}
		check(CosmeticProfileCodec.parse("{\"cosmetics\":{\"head\":{\"style\":\"fox\"}}}").problems().size() == 1,
			"a tail id in the head slot is rejected");
		java.util.Set<String> labels = new java.util.HashSet<>();
		for (CosmeticStyle style : CosmeticStyle.values()) check(labels.add(style.getLabel()), "style labels stay unique: " + style.getLabel());
	}

	private static float[] bounds(VoxelMesh mesh) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (int i = 0; i < mesh.positions.length; i += 3) {
			for (int a = 0; a < 3; a++) {
				b[a] = Math.min(b[a], mesh.positions[i + a]);
				b[a + 3] = Math.max(b[a + 3], mesh.positions[i + a]);
			}
		}
		return b;
	}

	private static float[] union(List<VoxelMesh> meshes) {
		float[] all = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (VoxelMesh mesh : meshes) {
			float[] b = bounds(mesh);
			for (int a = 0; a < 3; a++) {
				all[a] = Math.min(all[a], b[a]);
				all[a + 3] = Math.max(all[a + 3], b[a + 3]);
			}
		}
		return all;
	}

	private static void checkMeshes() {
		CosmeticModelsA wings = CosmeticModelsA.INSTANCE;
		CosmeticModelsA2 wings2 = CosmeticModelsA2.INSTANCE;
		CosmeticModelsATails tails = CosmeticModelsATails.INSTANCE;
		List<VoxelMesh> all = new ArrayList<>();
		for (VoxelMesh m : wings.getDRAGON_WING()) all.add(m);
		for (VoxelMesh m : wings.getFAIRY_WING()) all.add(m);
		for (VoxelMesh m : wings.getPHOENIX_WING()) all.add(m);
		for (VoxelMesh m : wings2.getMECH_BLADES()) all.add(m);
		for (VoxelMesh m : wings2.getCRYSTAL_SHARDS()) all.add(m);
		all.addAll(List.of(wings.getSPARK(), wings2.getMECH_GEAR(), wings2.getMECH_ARM(), wings2.getMECH_SMALL(),
			wings2.getCRYSTAL_ROD(), wings2.getCRYSTAL_CORE(), wings2.getCRYSTAL_CORE_HALO(), tails.getUNICORN_HORN(),
			tails.getUNICORN_TIP(), tails.getUNICORN_TIP_SHELL()));
		all.addAll(tails.getDRAGON_TAIL());
		all.addAll(tails.getDEVIL_TAIL());
		all.addAll(tails.getFOX_TAIL());
		for (VoxelMesh mesh : all) check(mesh.faceCount() > 0 && mesh.faceCount() < 8000, "every new mesh is non-empty and cheap to draw");

		float[] dragon = union(List.of(wings.getDRAGON_WING()));
		check(dragon[0] > -3f && dragon[3] > 18f && dragon[3] < 28f && dragon[4] > 22f && dragon[4] < 32f,
			"the dragon wing is a bit bigger than the devil wing and grows outward from its root (" + java.util.Arrays.toString(dragon) + ")");
		float[] fairy = union(List.of(wings.getFAIRY_WING()));
		check(fairy[3] > 15f && fairy[3] < 22f && fairy[4] > 22f && fairy[4] < 28f, "the fairy wing has a tall upper and a lower lobe " + java.util.Arrays.toString(fairy));
		float[] phoenix = union(List.of(wings.getPHOENIX_WING()));
		check(phoenix[3] > 17f && phoenix[3] < 38f && phoenix[4] > 22f, "the phoenix wing fans out wide and long " + java.util.Arrays.toString(phoenix));
		float[] longest = bounds(wings2.getMECH_BLADES()[3]);
		check(longest[4] > 21f && longest[4] < 26f, "the longest mechanical blade is about as long as a wing " + java.util.Arrays.toString(longest));
		float[] shard = bounds(wings2.getCRYSTAL_SHARDS()[0]);
		check(shard[1] < -13f && shard[1] > -15f, "the big shard is about 14 px long and points up " + java.util.Arrays.toString(shard));
		float[] tail = union(tails.getDRAGON_TAIL());
		check(tail[1] <= -8f, "the dragon tail spearhead reaches beyond its segment " + java.util.Arrays.toString(tail));
		float[] horn = bounds(tails.getUNICORN_HORN());
		check(horn[1] < -9f && horn[1] > -12.5f && horn[3] < 2.5f, "the unicorn horn is a tall thin cone " + java.util.Arrays.toString(horn));
		check(tails.getDRAGON_TAIL().size() == CosmeticModelsATails.DRAGON_SEGMENTS + 1
			&& tails.getDEVIL_TAIL().size() == CosmeticModelsATails.DEVIL_SEGMENTS + 1
			&& tails.getFOX_TAIL().size() == CosmeticModelsATails.FOX_PUFFS, "every chain has its parts");
	}

	/** Visible face area per channel group: [color, color2, fixed]. */
	private static float[] areas(List<CosmeticRig.Placement> placements) {
		float[] area = new float[3];
		for (CosmeticRig.Placement p : placements) {
			if (p.kind() == CosmeticRig.Kind.GLOW) continue; // glows (cores, sparks, fire) are accents by nature
			VoxelMesh mesh = p.mesh();
			for (int face = 0; face < mesh.faceCount(); face++) {
				int o = face * 12;
				float ax = mesh.positions[o + 3] - mesh.positions[o], ay = mesh.positions[o + 4] - mesh.positions[o + 1],
					az = mesh.positions[o + 5] - mesh.positions[o + 2];
				float bx = mesh.positions[o + 9] - mesh.positions[o], by = mesh.positions[o + 10] - mesh.positions[o + 1],
					bz = mesh.positions[o + 11] - mesh.positions[o + 2];
				float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
				float size = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
				int channel = mesh.channels[face] & 0xFF;
				area[Math.min(channel, 2)] += size;
			}
		}
		return area;
	}

	private static void checkChannels() {
		for (CosmeticStyle style : STYLES) {
			float[] area = areas(place(CosmeticPiece.of(style), CosmeticRig.Motion.standing(1f, 3)));
			float total = area[0] + area[1] + area[2];
			check(total > 0f && (area[0] + area[1]) / total >= 0.75f,
				style + " takes the player's colors on most of its surface (fixed share " + area[2] / total + ")");
			check(area[0] > 0f, style + " uses the first color");
		}
		// recoloring must reach every style: a blue/white piece draws no red/black body color
		for (CosmeticStyle style : STYLES) {
			CosmeticPiece blue = new CosmeticPiece(style, 0x2040E0, 0xF0F0F0, 1f, 0f, 0f, 1f);
			for (CosmeticRig.Placement p : place(blue, CosmeticRig.Motion.standing(1f, 3))) {
				check(p.color() == 0x2040E0 || p.color() == 0xFFFFFF || p.color() == 0xFFF1B8 || p.color() == 0xF0F0F0,
					style + " hands the player's colors to its meshes");
			}
		}
	}

	private static final float STEP = 1f / 120f;

	private static float[][] corners(VoxelMesh mesh, Map<VoxelMesh, float[][]> cache) {
		return cache.computeIfAbsent(mesh, m -> {
			float[] b = bounds(m);
			float[][] c = new float[8][];
			for (int i = 0; i < 8; i++) c[i] = new float[] {(i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5]};
			return c;
		});
	}

	private static boolean steady(CosmeticRig.Placement p) {
		return p.kind() != CosmeticRig.Kind.GLOW && p.mesh() != CosmeticModelsA.INSTANCE.getSPARK();
	}

	private static List<Vector3f> probes(List<CosmeticRig.Placement> placements, Map<VoxelMesh, float[][]> cache) {
		List<Vector3f> out = new ArrayList<>();
		for (CosmeticRig.Placement p : placements) {
			if (!steady(p)) continue;
			for (float[] c : corners(p.mesh(), cache)) out.add(p.transform().transformPosition(c[0], c[1], c[2], new Vector3f()));
		}
		return out;
	}

	private static void checkContinuity() {
		Map<VoxelMesh, float[][]> cache = new IdentityHashMap<>();
		for (CosmeticStyle style : STYLES) {
			for (float speed : new float[] {CosmeticPiece.of(style).speed(), 1f}) {
				CosmeticPiece piece = new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, speed);
				CosmeticMotionTracker tracker = new CosmeticMotionTracker();
				List<List<Vector3f>> frames = new ArrayList<>();
				float t = 100f;
				int steady = -1;
				// stand, then start moving, then jump, then stop: every transition must stay smooth
				for (int frame = 0; frame < 1200; frame++) {
					boolean moving = frame >= 200 && frame < 800;
					boolean air = frame >= 500 && frame < 620;
					CosmeticRig.Motion motion = tracker.update(1, t + frame * STEP, moving ? 1f : 0f, air);
					List<CosmeticRig.Placement> placements = place(piece, motion);
					int count = (int) placements.stream().filter(CosmeticsAChecks::steady).count();
					if (steady < 0) steady = count;
					check(count == steady, style + " keeps the same solid parts every frame");
					frames.add(probes(placements, cache));
				}
				float worst = 0f;
				for (int f = 1; f < frames.size() - 1; f++) {
					List<Vector3f> a = frames.get(f - 1), b = frames.get(f), c = frames.get(f + 1);
					for (int i = 0; i < b.size(); i++) {
						float dx = a.get(i).x - 2 * b.get(i).x + c.get(i).x, dy = a.get(i).y - 2 * b.get(i).y + c.get(i).y,
							dz = a.get(i).z - 2 * b.get(i).z + c.get(i).z;
						worst = Math.max(worst, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
					}
				}
				float ratio = speed / CosmeticPiece.of(style).speed(); // acceleration grows with the square of the speed
				check(worst < 2.0f * Math.max(1f, ratio * ratio), style + " at speed " + speed + " moves without jumps (largest second difference " + worst + " px)");
			}
		}
	}

	private static void checkSpeedZero() {
		for (CosmeticStyle style : STYLES) {
			CosmeticPiece still = new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 0f);
			List<CosmeticRig.Placement> a = place(still, CosmeticRig.Motion.standing(1f, 4));
			List<CosmeticRig.Placement> b = place(still, CosmeticRig.Motion.standing(7.3f, 4));
			boolean same = a.size() == b.size();
			for (int i = 0; same && i < a.size(); i++) same = a.get(i).transform().equals(b.get(i).transform()) && a.get(i).alpha() == b.get(i).alpha();
			check(same, style + " stands still at speed 0");
			List<CosmeticRig.Placement> live = place(new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 1f), CosmeticRig.Motion.standing(1f, 4));
			List<CosmeticRig.Placement> later = place(new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 1f), CosmeticRig.Motion.standing(1.9f, 4));
			boolean moves = false;
			for (int i = 0; i < Math.min(live.size(), later.size()); i++) moves |= !live.get(i).transform().equals(later.get(i).transform());
			check(moves, style + " animates at speed 1");
		}
	}

	private static void checkSeed() {
		for (CosmeticStyle style : new CosmeticStyle[] {CosmeticStyle.FAIRY_WINGS, CosmeticStyle.CRYSTAL_WINGS, CosmeticStyle.UNICORN_HORN,
			CosmeticStyle.MECHANICAL_WINGS}) {
			CosmeticPiece piece = new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 1f);
			List<CosmeticRig.Placement> a = place(piece, CosmeticRig.Motion.standing(2f, 3));
			List<CosmeticRig.Placement> b = place(piece, CosmeticRig.Motion.standing(2f, 9));
			boolean differs = a.size() != b.size();
			for (int i = 0; !differs && i < a.size(); i++) differs = !a.get(i).transform().equals(b.get(i).transform());
			check(differs, style + " desynchronises between players");
		}
	}

	private static void checkIcons() {
		for (CosmeticStyle style : STYLES) {
			for (float seconds : new float[] {0f, 1.3f, 2.9f}) {
				int[] pixels = CosmeticIcons.pixels(CosmeticPiece.of(style), 64, seconds);
				int drawn = 0;
				for (int pixel : pixels) if ((pixel >>> 24) > 0) drawn++;
				check(drawn > 64 * 64 / 14, style + " draws a visible card picture at " + seconds + " s");
			}
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
