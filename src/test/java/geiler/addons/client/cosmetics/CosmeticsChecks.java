package geiler.addons.client.cosmetics;

import geiler.addons.client.gui.ClickGuiScreenCategoryBehavior;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.impl.CosmeticsModule;

import java.util.List;

/** Cosmetics: hosted parsing, meshing, models, rig, smoothed animation, body shape, menu icons and the module. */
public final class CosmeticsChecks {
	private CosmeticsChecks() { }

	public static void run() {
		checkCodec();
		checkMeshing();
		checkModels();
		checkRig();
		checkAnimation();
		checkBodyShape();
		checkArmorWarp();
		checkDefaults();
		checkIcons();
		checkModule();
		checkHostedOverride();
	}

	/** A player with hosted cosmetics can swap a hosted piece for another, or switch a hosted slot off, on their side. */
	private static void checkHostedOverride() {
		CosmeticLoadout hosted = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.BUNNY_EARS), CosmeticPiece.of(CosmeticStyle.HALO)));
		CosmeticLoadout none = CosmeticLoadout.EMPTY;
		CosmeticLoadout fox = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.FOX_EARS)));
		check(CosmeticsModule.merge(none, hosted, java.util.Set.of()).piece(CosmeticSlot.HEAD).style() == CosmeticStyle.BUNNY_EARS,
			"an untouched slot shows the hosted piece");
		check(CosmeticsModule.merge(fox, hosted, java.util.Set.of()).piece(CosmeticSlot.HEAD).style() == CosmeticStyle.FOX_EARS,
			"the player's own pick replaces the hosted piece");
		CosmeticLoadout off = CosmeticsModule.merge(none, hosted, java.util.Set.of(CosmeticSlot.HEAD));
		check(off.piece(CosmeticSlot.HEAD) == null && off.piece(CosmeticSlot.HALO) != null,
			"switching a slot off hides only that hosted piece");
		CosmeticsModule.SlotSettings head = CosmeticsModule.INSTANCE.slot(CosmeticSlot.HEAD);
		head.getStyle().setValue(CosmeticsModule.OFF);
		check(head.getStyle().value().equals(CosmeticsModule.OFF) && head.selected() == null && head.isOff(),
			"the slot setting stores the off choice");
		head.getStyle().setValue(CosmeticsModule.NONE);
		check(!head.isOff(), "None means no own pick (the hosted piece may show)");
	}

	private static void checkCodec() {
		CosmeticProfileCodec.Result full = CosmeticProfileCodec.parse("""
			{"cosmetics": {
			  "wings": {"style": "devil", "color": "#101010", "color2": "#FF0000", "size": 1.5, "up": 2, "back": -1, "speed": 2},
			  "halo": {},
			  "tail": {"style": "fluffy"},
			  "aura": {"style": "stink"},
			  "body": {"chest": 0.5, "waist": 0.25}
			}}""");
		CosmeticPiece wings = full.loadout().piece(CosmeticSlot.WINGS);
		check(full.problems().isEmpty() && wings != null && wings.style() == CosmeticStyle.DEVIL_WINGS
			&& wings.color() == 0x101010 && wings.color2() == 0xFF0000 && wings.size() == 1.5f
			&& wings.up() == 2f && wings.back() == -1f && wings.speed() == 2f,
			"a hosted wing piece keeps its style, colors, size, offsets and speed");
		CosmeticPiece halo = full.loadout().piece(CosmeticSlot.HALO);
		check(halo != null && halo.style() == CosmeticStyle.HALO && halo.color() == CosmeticStyle.HALO.getColor(),
			"a slot with one style needs no style field and falls back to the style's colors");
		check(full.loadout().piece(CosmeticSlot.AURA).style() == CosmeticStyle.STINK_AURA
			&& full.loadout().piece(CosmeticSlot.HEAD) == null, "only the listed slots are worn");
		check(new BodyShape(0.5f, 0.25f).equals(full.loadout().body()), "a hosted body shape is read");

		CosmeticProfileCodec.Result partial = CosmeticProfileCodec.parse("""
			{"cosmetics": {"head": {"style": "cat_ears", "color": "red"}, "tail": {"style": "straight", "size": 9},
			 "cape": {}, "wings": {"style": "angel"}, "body": {"chest": 2}}}""");
		check(partial.problems().size() == 4 && partial.loadout().piece(CosmeticSlot.WINGS) != null
			&& partial.loadout().piece(CosmeticSlot.HEAD) == null && partial.loadout().piece(CosmeticSlot.TAIL) == null
			&& partial.loadout().body() == null,
			"a bad color, out-of-range size or chest and an unknown slot are reported; valid pieces still apply");
		check(CosmeticProfileCodec.parse("{\"tag\":\"visual-only\"}").loadout().isEmpty()
			&& CosmeticProfileCodec.parse("{\"tag\":\"visual-only\"}").problems().isEmpty()
			&& CosmeticProfileCodec.parse(null).loadout().isEmpty(),
			"effects without cosmetics stay silent");
		check(!CosmeticProfileCodec.parse("{broken").problems().isEmpty()
			&& !CosmeticProfileCodec.parse("{\"x\":\"" + "a".repeat(3000) + "\"}").problems().isEmpty(),
			"invalid or oversized effect JSON is reported, never thrown");
		check(!CosmeticProfileCodec.parse("{\"cosmetics\":{\"wings\":{\"style\":\"angel\",\"glow\":1}}}").problems().isEmpty(),
			"unknown piece fields are rejected so typos in the hosted file are visible");
	}

	private static void checkMeshing() {
		VoxelGrid one = new VoxelGrid();
		one.set(0, 0, 0, 0, 1f);
		check(one.mesh().faceCount() == 6, "a single voxel has six faces");
		VoxelGrid two = new VoxelGrid();
		two.set(0, 0, 0, 0, 1f);
		two.set(1, 0, 0, 1, 0.5f);
		VoxelMesh mesh = two.mesh();
		check(mesh.faceCount() == 10 && mesh.positions.length == 10 * 12,
			"the shared face is dropped and differently colored faces stay apart");
		boolean channels = false;
		for (int i = 0; i < mesh.faceCount(); i++) channels |= mesh.channels[i] == 1 && Math.abs(mesh.shades[i] - 0.5f) < 0.01f;
		VoxelGrid gold = new VoxelGrid();
		int fixed = gold.fixed(0xFFC83A);
		gold.box(0, 0, 0, 1, 1, 1, fixed, 1f);
		gold.box(1, 0, 0, 2, 1, 1, 0, 1f);
		VoxelMesh goldMesh = gold.mesh();
		boolean keepsFixed = false;
		boolean tintsRest = false;
		for (int i = 0; i < goldMesh.faceCount(); i++) {
			int tint = goldMesh.tint(i, 0x123456, 0x654321);
			keepsFixed |= tint == 0xFFC83A;
			tintsRest |= tint == 0x123456;
		}
		check(keepsFixed && tintsRest, "fixed-color parts keep their color while the rest takes the player's color");
		check(gold.fixed(0xFFC83A) == fixed, "the same fixed color reuses its channel");
		check(channels, "faces keep their voxel's channel and shade");
		VoxelGrid block = new VoxelGrid();
		block.box(0, 0, 0, 4, 4, 4, 0, 1f);
		VoxelMesh merged = block.mesh(0.5f);
		float max = 0f;
		for (float value : merged.positions) max = Math.max(max, value);
		check(merged.faceCount() == 6 && max == 2f, "a same-colored block merges into six faces, scaled by the voxel size");
	}

	private static void checkModels() {
		for (VoxelMesh mesh : List.of(CosmeticModels.INSTANCE.getANGEL_WING(), CosmeticModels.INSTANCE.getDEVIL_WING(),
			CosmeticModels.INSTANCE.getCAT_EAR(), CosmeticModels.INSTANCE.getDEMON_HORN(),
			CosmeticModels.INSTANCE.getCUTE_HORN(), CosmeticModels.INSTANCE.getRAM_HORN(),
			CosmeticModels.INSTANCE.getHALO(), CosmeticModels.INSTANCE.getFLY())) {
			check(mesh.faceCount() > 0 && mesh.faceCount() < 6000, "every model is non-empty and stays cheap to draw");
		}
		float minX = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float minZ = Float.MAX_VALUE;
		float maxZ = -Float.MAX_VALUE;
		VoxelMesh wing = CosmeticModels.INSTANCE.getANGEL_WING();
		for (int i = 0; i < wing.positions.length; i += 3) {
			minX = Math.min(minX, wing.positions[i]);
			maxY = Math.max(maxY, wing.positions[i + 1]);
			minZ = Math.min(minZ, wing.positions[i + 2]);
			maxZ = Math.max(maxZ, wing.positions[i + 2]);
		}
		check(minX >= -2f && maxY > 20f, "the wing grows outward from its root and hangs below the shoulders (minX " + minX + ", maxY " + maxY + ")");
		check(maxZ - minZ > 3f && maxZ - minZ < 8f, "the wing is layered and bowed (3D) but not a block");
		float ramMaxY = -Float.MAX_VALUE;
		float ramMinZ = Float.MAX_VALUE;
		VoxelMesh ram = CosmeticModels.INSTANCE.getRAM_HORN();
		for (int i = 0; i < ram.positions.length; i += 3) {
			ramMaxY = Math.max(ramMaxY, ram.positions[i + 1]);
			ramMinZ = Math.min(ramMinZ, ram.positions[i + 2]);
		}
		check(ramMaxY > 4f && ramMinZ < -1f, "the ram horn spirals down below its base and ends pointing forward");
		float demonMaxZ = -Float.MAX_VALUE;
		VoxelMesh demon = CosmeticModels.INSTANCE.getDEMON_HORN();
		for (int i = 0; i < demon.positions.length; i += 3) demonMaxZ = Math.max(demonMaxZ, demon.positions[i + 2]);
		check(demonMaxZ > 5f, "the demon horn sweeps back behind its base (" + demonMaxZ + ")");
		for (CosmeticStyle style : CosmeticSlot.TAIL.styles()) {
			check(CosmeticModels.tail(style).size() == CosmeticModels.TAIL_SEGMENTS, style + " has every tail segment");
		}
	}

	private static void checkRig() {
		CosmeticRig.Motion rest = CosmeticRig.Motion.REST;
		check(CosmeticRig.place(CosmeticLoadout.EMPTY, rest).isEmpty(), "nothing worn places nothing");
		List<CosmeticRig.Placement> wings = CosmeticRig.place(
			CosmeticLoadout.of(List.of(new CosmeticPiece(CosmeticStyle.ANGEL_WINGS, 0xFFFFFF, 0xFFFFFF, 1f, 0f, 0f, 1f))), rest);
		check(wings.size() == 2 && wings.get(0).anchor() == CosmeticRig.Anchor.BODY
			&& wings.get(0).transform().determinant() > 0 && wings.get(1).transform().determinant() < 0,
			"wings place a left wing and a mirrored right wing on the body");
		org.joml.Vector3f root = wings.get(0).transform().transformPosition(0f, CosmeticModels.ANGEL_ROOT_Y, 0f,
			new org.joml.Vector3f());
		check(root.z > 2f && root.y < 3f, "the wing root sits high on the back, behind the body");
		List<CosmeticRig.Placement> all = CosmeticRig.place(CosmeticLoadout.of(List.of(
			CosmeticPiece.of(CosmeticStyle.CAT_EARS), CosmeticPiece.of(CosmeticStyle.HALO),
			CosmeticPiece.of(CosmeticStyle.CURLED_TAIL))), rest);
		check(all.stream().filter(p -> p.anchor() == CosmeticRig.Anchor.HEAD).count() == 3
			&& all.size() == 3 + CosmeticModels.TAIL_SEGMENTS, "ears and halo follow the head; the tail is a chain on the body");
		CosmeticPiece big = new CosmeticPiece(CosmeticStyle.HALO, 0, 0, 2f, 4f, 0f, 1f);
		org.joml.Vector3f haloCenter = CosmeticRig.place(CosmeticLoadout.of(List.of(big)), rest).get(0)
			.transform().transformPosition(0f, 0f, 0f, new org.joml.Vector3f());
		check(haloCenter.y < -15f, "Height raises the halo above the head");
		List<CosmeticRig.Placement> stink = CosmeticRig.place(
			CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.STINK_AURA))), CosmeticRig.Motion.standing(3f, 1));
		check(stink.stream().anyMatch(p -> p.kind() == CosmeticRig.Kind.TRANSLUCENT && p.alpha() < 255)
			&& stink.stream().anyMatch(p -> p.mesh() == CosmeticModels.INSTANCE.getFLY()),
			"the stink aura is see-through haze with flies");
		List<CosmeticRig.Placement> glow = CosmeticRig.place(
			CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.GLOW_AURA))), CosmeticRig.Motion.standing(3f, 1));
		check(!glow.isEmpty() && glow.stream().allMatch(p -> p.kind() == CosmeticRig.Kind.GLOW),
			"the glow aura is glowing sparks");
	}

	private static void checkAnimation() {
		int twitching = 0;
		float maxTwitch = 0f;
		for (int frame = 0; frame < 20 * 30; frame++) {
			float value = CosmeticRig.earTwitch(frame / 20f, 42, 0, 1f);
			if (value != 0f) twitching++;
			maxTwitch = Math.max(maxTwitch, Math.abs(value));
		}
		check(twitching > 0 && twitching < 20 * 30 / 4 && maxTwitch <= 1f,
			"ears rest most of the time and flick now and then");
		check(CosmeticRig.earTwitch(1.3f, 42, 0, 0f) == 0f, "speed 0 stops the twitch");

		// Walking makes the raw speed and ground contact flicker every frame; the phase must still move smoothly.
		CosmeticMotionTracker tracker = new CosmeticMotionTracker();
		float age = 5000f; // a player who has been online for a while: large clocks made the old phase jump
		CosmeticRig.Motion previous = tracker.update(1, age, 0f, false);
		float maxStep = 0f;
		for (int frame = 1; frame < 600; frame++) {
			float seconds = age + frame / 60f;
			CosmeticRig.Motion motion = tracker.update(1, seconds, frame % 2 == 0 ? 1f : 0.2f, frame % 3 == 0);
			maxStep = Math.max(maxStep, Math.abs(motion.wingPhase() - previous.wingPhase()));
			maxStep = Math.max(maxStep, Math.abs(motion.swayPhase() - previous.swayPhase()));
			previous = motion;
		}
		check(maxStep < 0.5f, "the wing and tail phase advance smoothly while walking (largest step " + maxStep + ")");
		check(previous.moving() > 0.3f && previous.moving() < 0.9f, "walk speed is smoothed, not followed frame by frame");
		CosmeticMotionTracker air = new CosmeticMotionTracker();
		CosmeticMotionTracker ground = new CosmeticMotionTracker();
		CosmeticRig.Motion flying = null;
		CosmeticRig.Motion standing = null;
		for (int frame = 0; frame < 120; frame++) {
			flying = air.update(2, frame / 20f, 0f, true);
			standing = ground.update(2, frame / 20f, 0f, false);
		}
		check(flying.wingPhase() > standing.wingPhase() * 1.5f, "wings beat faster in the air");
	}

	private static void checkBodyShape() {
		float[] flat = BodyShapeGeometry.torso(new BodyShape(0f, 0f), false);
		check(flat.length == 50 * BodyShapeGeometry.FLOATS_PER_QUAD, "an unshaped torso is 12 rows of 4 sides plus top and bottom");
		check(minHalfWidthAround(flat, 8f) > 3.99f, "waist 0 keeps the vanilla width");
		float[] shaped = BodyShapeGeometry.torso(new BodyShape(1f, 1f), false);
		check(minHalfWidthAround(shaped, 8f) < 3.3f && minHalfWidthAround(shaped, 0f) > 3.8f,
			"the waist narrows in the middle and the shoulders keep their width");
		check(shaped.length == 60 * BodyShapeGeometry.FLOATS_PER_QUAD, "a chest adds two five-sided boxes");
		float minZ = 0f;
		for (int i = 0; i < shaped.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) {
			for (int c = 0; c < 4; c++) {
				int o = i + c * 5;
				minZ = Math.min(minZ, shaped[o + 2]);
				float u = shaped[o + 3];
				float v = shaped[o + 4];
				check(u >= 16f && u <= 40f && v >= 16f && v <= 32f, "body quads sample the skin's body area (" + u + "," + v + ")");
			}
		}
		check(minZ < -3.5f, "the chest stands out from the torso front");
		float[] jacket = BodyShapeGeometry.torso(new BodyShape(1f, 1f), true);
		boolean jacketArea = true;
		for (int i = 0; i < jacket.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) jacketArea &= jacket[i + 4] >= 32f;
		check(jacketArea, "the jacket layer samples the skin's outer-layer area");
		check(BodyShapeGeometry.cached(new BodyShape(1f, 1f), false) == BodyShapeGeometry.cached(new BodyShape(1f, 1f), false),
			"torso quads are built once per shape");
	}

	/** A chestplate-sized body cube (8 x 12 x 4, inflated by 1) as the armor warp receives it. */
	private static ArmorBodyWarp.CubeFaces chestplate(float inflate) {
		float x0 = -4 - inflate, x1 = 4 + inflate, y0 = -inflate, y1 = 12 + inflate, z0 = -2 - inflate, z1 = 2 + inflate;
		List<float[][]> faces = List.of(
			new float[][]{{x0, y0, z0, 0, 0}, {x1, y0, z0, 1, 0}, {x1, y1, z0, 1, 1}, {x0, y1, z0, 0, 1}},
			new float[][]{{x1, y0, z1, 0, 0}, {x0, y0, z1, 1, 0}, {x0, y1, z1, 1, 1}, {x1, y1, z1, 0, 1}},
			new float[][]{{x0, y0, z1, 0, 0}, {x0, y0, z0, 1, 0}, {x0, y1, z0, 1, 1}, {x0, y1, z1, 0, 1}},
			new float[][]{{x1, y0, z0, 0, 0}, {x1, y0, z1, 1, 0}, {x1, y1, z1, 1, 1}, {x1, y1, z0, 0, 1}},
			new float[][]{{x0, y0, z1, 0, 0}, {x1, y0, z1, 1, 0}, {x1, y0, z0, 1, 1}, {x0, y0, z0, 0, 1}},
			new float[][]{{x0, y1, z0, 0, 0}, {x1, y1, z0, 1, 0}, {x1, y1, z1, 1, 1}, {x0, y1, z1, 0, 1}});
		return new ArmorBodyWarp.CubeFaces(inflate, faces);
	}

	private static void checkArmorWarp() {
		BodyShape shape = new BodyShape(1f, 1f);
		float[] plain = ArmorBodyWarp.bend(List.of(chestplate(1f)), new BodyShape(0f, 0f));
		boolean identity = true;
		for (int i = 0; i < plain.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) {
			for (int c = 0; c < 4; c++) {
				int o = i + c * 5;
				identity &= Math.abs(Math.abs(plain[o]) - 5f) < 1e-4f || Math.abs(Math.abs(plain[o + 2]) - 3f) < 1e-4f;
			}
		}
		check(identity, "with no shape the armor keeps its vanilla box");
		float[] bent = ArmorBodyWarp.bend(List.of(chestplate(1f)), shape);
		check(bent.length > 4 * 20 * BodyShapeGeometry.FLOATS_PER_QUAD, "armor side faces are cut into rows so they can bend");
		float frontAtChest = 0f;
		float sideAtWaist = Float.MAX_VALUE;
		for (int i = 0; i < bent.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) {
			for (int c = 0; c < 4; c++) {
				int o = i + c * 5;
				float x = bent[o], y = bent[o + 1], z = bent[o + 2];
				if (Math.abs(y - 3.5f) < 0.01f) frontAtChest = Math.min(frontAtChest, z);
				if (Math.abs(y - 8f) < 0.01f) sideAtWaist = Math.min(sideAtWaist, Math.abs(x) < 0.5f ? Float.MAX_VALUE : Math.abs(x));
			}
		}
		float skinBustFront = -2.25f - BodyShapeGeometry.bustOut(shape, 3.5f);
		check(frontAtChest < skinBustFront - 0.05f, "the chestplate covers the chest (" + frontAtChest + " vs " + skinBustFront + ")");
		float skinWaist = 4.25f - BodyShapeGeometry.waistIn(shape, 8f);
		check(sideAtWaist > skinWaist + 0.5f && sideAtWaist < 5f - 0.5f,
			"the chestplate follows the waist in and stays outside the skin (" + sideAtWaist + ")");
		float[] leggings = ArmorBodyWarp.bend(List.of(chestplate(0.5f)), shape);
		float legFront = 0f;
		for (int i = 0; i < leggings.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) {
			for (int c = 0; c < 4; c++) {
				int o = i + c * 5;
				if (Math.abs(leggings[o + 1] - 3.5f) < 0.01f) legFront = Math.min(legFront, leggings[o + 2]);
			}
		}
		check(legFront < skinBustFront && legFront > frontAtChest, "the leggings layer sits between the skin and the chestplate");
	}

	private static void checkDefaults() {
		CosmeticPiece wings = CosmeticPiece.of(CosmeticStyle.DEVIL_WINGS);
		check(wings.size() == CosmeticSlot.WINGS.getDefaultSize() && wings.speed() == CosmeticSlot.WINGS.getDefaultSpeed(),
			"a new piece uses its slot's tuned placement");
		CosmeticPiece hosted = CosmeticProfileCodec.parse("{\"cosmetics\":{\"head\":{\"style\":\"cat_ears\"}}}")
			.loadout().piece(CosmeticSlot.HEAD);
		check(hosted.up() == CosmeticSlot.HEAD.getDefaultUp(), "hosted pieces that leave a field out use the same defaults");
		check(CosmeticsModule.INSTANCE.slot(CosmeticSlot.WINGS).getSize().value() == CosmeticSlot.WINGS.getDefaultSize(),
			"the menu sliders start at the tuned placement");
	}

	/** Smallest |x| among torso corners at height [y] (pixels). */
	private static float minHalfWidthAround(float[] quads, float y) {
		float min = Float.MAX_VALUE;
		for (int i = 0; i < 48 * BodyShapeGeometry.FLOATS_PER_QUAD && i < quads.length; i += BodyShapeGeometry.FLOATS_PER_QUAD) {
			for (int c = 0; c < 4; c++) {
				int o = i + c * 5;
				if (Math.abs(quads[o + 1] - y) < 0.01f) min = Math.min(min, Math.abs(quads[o]));
			}
		}
		return min;
	}

	private static void checkIcons() {
		for (CosmeticStyle style : CosmeticStyle.values()) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(
				CosmeticLoadout.of(List.of(CosmeticPiece.of(style))), CosmeticRig.Motion.standing(1f, 3));
			int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.HEAD, 48, 0.6f, -0.3f);
			int drawn = 0;
			for (int pixel : pixels) if ((pixel >>> 24) > 0) drawn++;
			check(pixels.length == 48 * 48 && drawn > 48 * 48 / (style.getSlot() == CosmeticSlot.TRAIL ? 80 : 10), style + " draws a visible menu picture");
		}
		int[] empty = CosmeticIconRenderer.render(List.of(), null, CosmeticIconRenderer.Stand.NONE, 16, 0f, 0f);
		check(java.util.Arrays.stream(empty).allMatch(pixel -> pixel == 0), "nothing to draw leaves a transparent picture");
	}

	private static void checkModule() {
		CosmeticsModule module = CosmeticsModule.INSTANCE;
		check(module.name().equals("Cosmetics") && module.category() == Category.VISUAL && !module.defaultEnabled(),
			"Cosmetics keeps its Visual identity and the fresh-config default off");
		check(!ClickGuiScreenCategoryBehavior.isModuleCardVisible(module) && !module.showsToggleControl(),
			"Cosmetics has no module card or switch; the top-bar button opens it");
		check(module.localLoadout().isEmpty(), "no cosmetic is worn by default");

		CosmeticsModule.SlotSettings wings = module.slot(CosmeticSlot.WINGS);
		try {
			check(module.toggle(CosmeticStyle.DEVIL_WINGS) && wings.selected() == CosmeticStyle.DEVIL_WINGS
				&& module.localLoadout().piece(CosmeticSlot.WINGS).style() == CosmeticStyle.DEVIL_WINGS,
				"equipping in the menu wears the style");
			check(module.toggle(CosmeticStyle.ANGEL_WINGS) && wings.selected() == CosmeticStyle.ANGEL_WINGS,
				"another style of the same slot replaces it");
			check(!module.toggle(CosmeticStyle.ANGEL_WINGS) && wings.selected() == null, "equipping a worn style takes it off");
			wings.getStyleColors().setValue(false);
			wings.getColor().set(10, 20, 30, 255);
			check(wings.piece(CosmeticStyle.DEVIL_WINGS).color() == 0x0A141E, "own colors apply when style colors are off");
			module.chestSetting().setValue(0.6f);
			check(module.localLoadout().body() != null && module.localLoadout().body().chest() == 0.6f,
				"the chest slider gives your own body a shape");
		} finally {
			wings.getStyle().setValue(CosmeticsModule.NONE);
			wings.getStyleColors().setValue(true);
			module.chestSetting().setValue(0f);
		}

		CosmeticLoadout local = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.ANGEL_WINGS)));
		CosmeticLoadout remote = CosmeticLoadout.of(List.of(CosmeticPiece.of(CosmeticStyle.DEVIL_WINGS),
			CosmeticPiece.of(CosmeticStyle.HALO)), new BodyShape(0.4f, 0f));
		CosmeticLoadout merged = CosmeticsModule.merge(local, remote, java.util.Set.of());
		check(merged.piece(CosmeticSlot.WINGS).style() == CosmeticStyle.ANGEL_WINGS
			&& merged.piece(CosmeticSlot.HALO) != null && merged.body() != null,
			"your own pieces win per slot; hosted pieces and shape fill the rest");
		check(module.loadoutFor(null).isEmpty(), "no player name, no cosmetics");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
