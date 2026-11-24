package geiler.addons.client.cosmetics;

import geiler.addons.client.module.impl.CosmeticsModule;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The FACE and BACK slots: registration, hosted parsing, models, fit on head and body, colors, animation, settings. */
public final class CosmeticsCChecks {
	private static final CosmeticStyle[] FACE = {CosmeticStyle.SUNGLASSES, CosmeticStyle.FOX_MASK,
		CosmeticStyle.PLAGUE_MASK, CosmeticStyle.BANDANA_MASK};
	private static final CosmeticStyle[] BACK = {CosmeticStyle.BACKPACK, CosmeticStyle.KATANA, CosmeticStyle.JETPACK,
		CosmeticStyle.TURTLE_SHELL, CosmeticStyle.ELECTRIC_GUITAR};

	private CosmeticsCChecks() { }

	/** Stand-alone run (the `cosmeticCChecks` task): the same bootstrap as OfflineChecks, then only these checks. */
	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		geiler.addons.client.FabricCheckEnvironment.initializeConfigDirectory();
		geiler.addons.client.GeilerAddonsClient.registerModules();
		run();
		System.out.println("CosmeticsCChecks OK");
	}

	public static void run() {
		checkRegistration();
		checkCodec();
		checkMeshes();
		checkFit();
		checkSilhouettes();
		checkColors();
		checkAnimation();
		checkSpeedZeroAndSeed();
		checkSettings();
		checkIcons();
	}

	private static List<CosmeticStyle> all() {
		List<CosmeticStyle> styles = new ArrayList<>(List.of(FACE));
		styles.addAll(List.of(BACK));
		return styles;
	}

	private static List<CosmeticRig.Placement> place(CosmeticStyle style, CosmeticRig.Motion motion) {
		return CosmeticRig.place(CosmeticLoadout.of(List.of(CosmeticPiece.of(style))), motion);
	}

	private static CosmeticRig.Motion motion(float seconds, int seed, float moving, float air) {
		float phase = seconds * (1f + moving * 0.8f) * (float) (2 * Math.PI);
		return new CosmeticRig.Motion(seconds, seed, moving, air, phase, phase);
	}

	private static void checkRegistration() {
		check(CosmeticSlot.byId("face") == CosmeticSlot.FACE && CosmeticSlot.byId("back") == CosmeticSlot.BACK,
			"the face and back slots have stable ids");
		check(CosmeticSlot.FACE.styles().size() == FACE.length && CosmeticSlot.BACK.styles().size() == BACK.length,
			"every face and back style belongs to its slot");
		for (CosmeticSlot slot : new CosmeticSlot[]{CosmeticSlot.FACE, CosmeticSlot.BACK}) {
			check(slot.getDefaultSize() == 1f && slot.getDefaultSpeed() == 1f, slot + " defaults to size 1 and speed 1");
		}
		Set<String> ids = new HashSet<>();
		for (CosmeticStyle style : CosmeticStyle.values()) {
			check(ids.add(style.getSlot().getId() + "/" + style.getId()), "style ids are unique per slot: " + style.getId());
			check(style.getId().matches("[a-z][a-z0-9_]*"), "style ids are snake_case: " + style.getId());
		}
	}

	private static void checkCodec() {
		for (CosmeticStyle style : all()) {
			String json = "{\"cosmetics\":{\"" + style.getSlot().getId() + "\":{\"style\":\"" + style.getId() + "\"}}}";
			CosmeticProfileCodec.Result result = CosmeticProfileCodec.parse(json);
			CosmeticPiece piece = result.loadout().piece(style.getSlot());
			check(result.problems().isEmpty() && piece != null && piece.style() == style && piece.size() == 1f
				&& piece.speed() == 1f && piece.color() == style.getColor() && piece.color2() == style.getColor2(),
				style.getId() + " parses from the hosted profile with its default colors");
		}
		CosmeticProfileCodec.Result both = CosmeticProfileCodec.parse(
			"{\"cosmetics\":{\"face\":{\"style\":\"fox_mask\",\"color\":\"#3050FF\"},\"back\":{\"style\":\"jetpack\",\"up\":2,\"speed\":2}}}");
		check(both.problems().isEmpty() && both.loadout().piece(CosmeticSlot.FACE).color() == 0x3050FF
			&& both.loadout().piece(CosmeticSlot.BACK).up() == 2f, "a face and a back piece load together with their overrides");
		check(!CosmeticProfileCodec.parse("{\"cosmetics\":{\"back\":{\"style\":\"wings\"}}}").problems().isEmpty(),
			"a style of another slot is rejected");
	}

	private static void checkMeshes() {
		CosmeticModelsC m = CosmeticModelsC.INSTANCE;
		List<VoxelMesh> meshes = List.of(m.getSUNGLASSES(), m.getGLINT(), m.getFOX_MASK(), m.getFOX_EAR(), m.getPLAGUE_MASK(),
			m.getPLAGUE_BEAK(), m.getBANDANA(), m.getBANDANA_TAIL(), m.getBACKPACK(), m.getBACKPACK_BEDROLL(),
			m.getBACKPACK_FLAP(), m.getHARNESS(), m.getKATANA(), m.getJETPACK(), m.getFLAME_OUTER(), m.getFLAME_CORE(),
			m.getTURTLE_SHELL(), m.getGUITAR(), m.getGUITAR_STRAP());
		for (VoxelMesh mesh : meshes) {
			check(mesh.faceCount() > 8 && mesh.faceCount() < 9000, "every face and back model is non-empty and cheap to draw ("
				+ mesh.faceCount() + ")");
		}
		check(m.getPLAGUE_MASK().smoothNormals != null && m.getPLAGUE_BEAK().smoothNormals != null
			&& m.getTURTLE_SHELL().smoothNormals != null && m.getJETPACK().smoothNormals != null,
			"curved parts light with smooth normals");
		for (CosmeticStyle style : all()) {
			int faces = 0;
			for (CosmeticRig.Placement p : place(style, CosmeticRig.Motion.standing(1f, 3))) faces += p.mesh().faceCount();
			check(faces < 12000, style.getId() + " stays under the per-player face budget (" + faces + ")");
		}
	}

	/** Bounds of every vertex of the style's solid placements, transformed into the anchor's space: minX, minY, minZ, maxX, maxY, maxZ. */
	private static float[] bounds(CosmeticStyle style, CosmeticRig.Motion motion, boolean glow) {
		float[] b = {1e9f, 1e9f, 1e9f, -1e9f, -1e9f, -1e9f};
		Vector3f v = new Vector3f();
		for (CosmeticRig.Placement p : place(style, motion)) {
			if ((p.kind() == CosmeticRig.Kind.GLOW) != glow || p.kind() == CosmeticRig.Kind.TRANSLUCENT) continue;
			float[] pos = p.mesh().positions;
			for (int i = 0; i < pos.length; i += 3) {
				p.transform().transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
				b[0] = Math.min(b[0], v.x); b[1] = Math.min(b[1], v.y); b[2] = Math.min(b[2], v.z);
				b[3] = Math.max(b[3], v.x); b[4] = Math.max(b[4], v.y); b[5] = Math.max(b[5], v.z);
			}
		}
		return b;
	}

	private static List<CosmeticRig.Motion> poses() {
		List<CosmeticRig.Motion> poses = new ArrayList<>();
		for (float t = 0f; t < 4f; t += 0.37f) {
			poses.add(motion(t, 7, 0f, 0f));
			poses.add(motion(t, 7, 1f, 0f));
			poses.add(motion(t, 7, 1f, 1f));
		}
		return poses;
	}

	/** Face pieces stay outside the head's 8 x 8 x 8 box (the hat layer is 0.5 out); back pieces stay behind the body. */
	private static void checkFit() {
		for (CosmeticStyle style : FACE) {
			for (CosmeticRig.Motion pose : poses()) {
				for (CosmeticRig.Placement p : place(style, pose)) {
					if (p.kind() != CosmeticRig.Kind.SOLID) continue;
					Vector3f v = new Vector3f();
					float[] pos = p.mesh().positions;
					for (int i = 0; i < pos.length; i += 3) {
						p.transform().transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
						check(!(Math.abs(v.x) < 4.4f && Math.abs(v.z) < 4.4f && v.y > -8.0f && v.y < 0.4f),
							style.getId() + " does not clip into the head: " + v);
					}
				}
			}
		}
		for (CosmeticStyle style : BACK) {
			for (CosmeticRig.Motion pose : poses()) {
				for (CosmeticRig.Placement p : place(style, pose)) {
					if (p.kind() != CosmeticRig.Kind.SOLID) continue;
					Vector3f v = new Vector3f();
					float[] pos = p.mesh().positions;
					for (int i = 0; i < pos.length; i += 3) {
						p.transform().transformPosition(pos[i], pos[i + 1], pos[i + 2], v);
						check(!(Math.abs(v.x) < 3.95f && Math.abs(v.z) < 1.95f && v.y > 0.05f && v.y < 11.95f),
							style.getId() + " does not clip into the body: " + v);
					}
				}
			}
			float[] b = bounds(style, motion(1f, 7, 1f, 1f), false);
			check(b[2] > -2.6f, style.getId() + " only reaches in front of the body with its straps (" + b[2] + ")");
		}
	}

	private static void checkSilhouettes() {
		CosmeticRig.Motion rest = CosmeticRig.Motion.standing(0.3f, 5);
		float[] glasses = bounds(CosmeticStyle.SUNGLASSES, rest, false);
		check(glasses[3] > 4.9f && glasses[0] < -4.9f && glasses[2] < -5.4f && glasses[5] > 1f,
			"the sunglasses are as wide as the head with temples reaching back");
		float[] fox = bounds(CosmeticStyle.FOX_MASK, rest, false);
		check(fox[1] < -11f && fox[2] < -7.5f, "the fox mask has ears above the head and a snout in front");
		float[] plague = bounds(CosmeticStyle.PLAGUE_MASK, rest, false);
		check(plague[2] < -10f && plague[4] > 2.5f, "the plague beak reaches forward and hangs below the chin");
		float[] bandana = bounds(CosmeticStyle.BANDANA_MASK, rest, false);
		check(bandana[4] > 0.5f && bandana[5] > 5.5f, "the bandana hangs below the chin and ties behind");
		float[] pack = bounds(CosmeticStyle.BACKPACK, rest, false);
		check(pack[3] - pack[0] > 7.5f && pack[3] - pack[0] < 9.5f && pack[4] > 10f && pack[1] < 0.5f && pack[5] > 6f,
			"the backpack is body wide with a bedroll on top and a deep bag");
		float[] katana = bounds(CosmeticStyle.KATANA, rest, false);
		check(katana[1] < -0.5f && katana[4] > 12f && katana[3] > 5f && katana[0] < -4f,
			"the katana runs from above one shoulder to below the opposite hip");
		float[] jet = bounds(CosmeticStyle.JETPACK, rest, false);
		check(Math.abs(jet[0] + jet[3]) < 0.3f && jet[3] - jet[0] > 7f && jet[3] - jet[0] < 9f && jet[4] > 9f,
			"the jetpack is two symmetric tanks with nozzles");
		float idle = bounds(CosmeticStyle.JETPACK, motion(0.3f, 5, 0f, 0f), true)[4];
		float flying = bounds(CosmeticStyle.JETPACK, motion(0.3f, 5, 0f, 1f), true)[4];
		check(flying > idle + 3.5f && idle > 10.5f, "the flames are small at rest and long in the air (" + idle + " vs " + flying + ")");
		float[] shell = bounds(CosmeticStyle.TURTLE_SHELL, rest, false);
		check(shell[5] - shell[2] > 3.2f && shell[4] - shell[1] > 9f && shell[3] - shell[0] > 8f,
			"the shell is a dome over the whole back");
		float[] guitar = bounds(CosmeticStyle.ELECTRIC_GUITAR, rest, false);
		check(guitar[1] < -1f && guitar[3] > 4.5f && guitar[4] > 10f, "the guitar's neck reaches above the shoulder, its body low on the other side");
	}

	/** Area of each channel over the style's solid meshes: {color, color2, fixed}. */
	private static float[] areas(CosmeticStyle style) {
		float[] area = new float[3];
		Set<VoxelMesh> seen = new HashSet<>();
		for (CosmeticRig.Placement p : place(style, CosmeticRig.Motion.standing(0.3f, 5))) {
			if (p.kind() != CosmeticRig.Kind.SOLID || !seen.add(p.mesh())) continue;
			VoxelMesh mesh = p.mesh();
			for (int f = 0; f < mesh.faceCount(); f++) {
				int o = f * 12;
				float ax = mesh.positions[o + 3] - mesh.positions[o], ay = mesh.positions[o + 4] - mesh.positions[o + 1],
					az = mesh.positions[o + 5] - mesh.positions[o + 2];
				float bx = mesh.positions[o + 9] - mesh.positions[o], by = mesh.positions[o + 10] - mesh.positions[o + 1],
					bz = mesh.positions[o + 11] - mesh.positions[o + 2];
				float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
				float a = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
				int channel = mesh.channels[f] & 0xFF;
				area[channel > 1 ? 2 : channel] += a;
			}
		}
		return area;
	}

	/** Colors: both tints show clearly and only small material accents keep their own color. */
	private static void checkColors() {
		for (CosmeticStyle style : all()) {
			float[] a = areas(style);
			float total = a[0] + a[1] + a[2];
			check(a[0] > total * 0.03f && a[1] > total * 0.012f, style.getId() + " shows both the color and the second color ("
				+ a[0] / total + ", " + a[1] / total + ")");
			check(a[2] < total * 0.4f, style.getId() + " keeps fixed colors to accents (" + a[2] / total + ")");
		}
	}

	/** Largest distance any sampled vertex moves between two poses of the same solid placements. */
	private static float step(List<CosmeticRig.Placement> a, List<CosmeticRig.Placement> b, boolean glow) {
		float max = 0f;
		Vector3f p = new Vector3f();
		Vector3f q = new Vector3f();
		for (int i = 0; i < a.size(); i++) {
			CosmeticRig.Placement x = a.get(i);
			CosmeticRig.Placement y = b.get(i);
			if ((x.kind() == CosmeticRig.Kind.GLOW) != glow || x.kind() == CosmeticRig.Kind.TRANSLUCENT) continue;
			float[] pos = x.mesh().positions;
			int stride = Math.max(3, pos.length / 40 / 3 * 3);
			for (int k = 0; k + 2 < pos.length; k += stride) {
				x.transform().transformPosition(pos[k], pos[k + 1], pos[k + 2], p);
				y.transform().transformPosition(pos[k], pos[k + 1], pos[k + 2], q);
				max = Math.max(max, p.distance(q));
			}
		}
		return max;
	}

	/** Walks 8 s at 60 fps: standing, then moving, a jump, then standing again. No frame may jump. */
	private static void checkAnimation() {
		for (CosmeticStyle style : all()) {
			CosmeticMotionTracker tracker = new CosmeticMotionTracker();
			float start = 3000f;
			List<CosmeticRig.Placement> previous = null;
			float maxSolid = 0f;
			float maxGlow = 0f;
			float moved = 0f;
			List<CosmeticRig.Placement> first = null;
			for (int frame = 0; frame < 480; frame++) {
				float seconds = start + frame / 60f;
				float t = frame / 60f;
				CosmeticRig.Motion m = tracker.update(4, seconds, t >= 1.5f && t < 6f ? 1f : 0f, t >= 3.5f && t < 4.5f);
				List<CosmeticRig.Placement> now = new ArrayList<>(place(style, m));
				now.removeIf(p -> p.kind() == CosmeticRig.Kind.TRANSLUCENT || (p.kind() == CosmeticRig.Kind.GLOW && p.mesh() == CosmeticModelsC.INSTANCE.getGLINT()));
				if (first == null) first = now;
				if (previous != null) {
					if (sameKinds(previous, now)) {
						maxSolid = Math.max(maxSolid, step(previous, now, false));
						maxGlow = Math.max(maxGlow, step(previous, now, true));
					}
				}
				if (t > 2.5f && sameKinds(first, now)) moved = Math.max(moved, step(first, now, false));
				previous = now;
			}
			check(maxSolid < 1.0f, style.getId() + " moves smoothly: largest solid step " + maxSolid);
			check(maxGlow < 2.5f, style.getId() + " glows smoothly: largest glow step " + maxGlow);
			if (style != CosmeticStyle.SUNGLASSES) {
				check(moved > 0.15f, style.getId() + " reacts to walking and jumping (moved " + moved + ")");
			}
		}
	}

	private static boolean sameKinds(List<CosmeticRig.Placement> a, List<CosmeticRig.Placement> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) if (a.get(i).mesh() != b.get(i).mesh() || a.get(i).kind() != b.get(i).kind()) return false;
		return true;
	}

	private static boolean differs(List<CosmeticRig.Placement> a, List<CosmeticRig.Placement> b) {
		if (a.size() != b.size()) return true;
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i).alpha() != b.get(i).alpha() || !a.get(i).transform().equals(b.get(i).transform(), 1e-5f)) return true;
		}
		return false;
	}

	private static void checkSpeedZeroAndSeed() {
		for (CosmeticStyle style : all()) {
			CosmeticPiece frozen = new CosmeticPiece(style, style.getColor(), style.getColor2(), 1f, 0f, 0f, 0f);
			CosmeticLoadout loadout = CosmeticLoadout.of(List.of(frozen));
			List<CosmeticRig.Placement> a = CosmeticRig.place(loadout, motion(1f, 3, 0f, 0f));
			List<CosmeticRig.Placement> b = CosmeticRig.place(loadout, motion(7.3f, 3, 1f, 1f));
			check(!differs(a, b), style.getId() + " stands still at speed 0, whatever the player does");
			CosmeticLoadout live = CosmeticLoadout.of(List.of(CosmeticPiece.of(style)));
			boolean desync = false;
			for (float t = 0f; t < 12f && !desync; t += 0.05f) {
				desync = differs(CosmeticRig.place(live, motion(t, 1, 0f, 0f)), CosmeticRig.place(live, motion(t, 2, 0f, 0f)));
			}
			check(desync, style.getId() + " moves differently for different players");
		}
		CosmeticLoadout glasses = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.SUNGLASSES)));
		boolean glint = false;
		boolean dark = false;
		for (float t = 0f; t < 12f; t += 0.05f) {
			boolean on = CosmeticRig.place(glasses, motion(t, 1, 0f, 0f)).stream().anyMatch(p -> p.kind() == CosmeticRig.Kind.GLOW);
			glint |= on;
			dark |= !on;
		}
		check(glint && dark, "the lens glint comes and goes");
	}

	private static void checkSettings() {
		CosmeticsModule module = CosmeticsModule.INSTANCE;
		for (CosmeticSlot slot : new CosmeticSlot[]{CosmeticSlot.FACE, CosmeticSlot.BACK}) {
			CosmeticsModule.SlotSettings settings = module.slot(slot);
			check(settings.getSize().value() == slot.getDefaultSize() && settings.getSpeed().value() == slot.getDefaultSpeed()
				&& settings.getStyle().value().equals(CosmeticsModule.NONE), slot + " has settings with the tuned defaults");
		}
		for (CosmeticStyle style : all()) {
			try {
				check(module.toggle(style) && module.localLoadout().piece(style.getSlot()).style() == style,
					style.getId() + " can be worn from the menu");
			} finally {
				module.slot(style.getSlot()).getStyle().setValue(CosmeticsModule.NONE);
			}
		}
		check(module.localLoadout().isEmpty(), "the menu checks leave nothing worn");
	}

	private static void checkIcons() {
		for (CosmeticStyle style : all()) {
			int[] pixels = CosmeticIcons.pixels(CosmeticPiece.of(style), 48, 0.6f);
			int drawn = 0;
			for (int pixel : pixels) if ((pixel >>> 24) > 0) drawn++;
			check(drawn > 48 * 48 / 25, style.getId() + " draws a visible menu card picture");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
