package geiler.addons.client.visual;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Offline checks for the Custom Panorama camera path, uniform layout, photo fit and photo loading. */
public final class PanoramaChecks {
	private PanoramaChecks() { }

	public static void run() throws Exception {
		checkLoop();
		checkTerrain();
		checkDuneStyles();
		checkTargetSize();
		checkParams();
		checkCoverFit();
		checkPhotoLoading();
		checkShaderResources();
	}

	private static void checkLoop() {
		double period = PanoramaMath.LOOP_SECONDS;
		check(Math.abs(PanoramaMath.loopTime(period * 3 + 12.5) - 12.5f) < 1e-3, "time wraps at the loop period");
		check(PanoramaMath.loopTime(-1.0) >= 0f, "negative time still lands inside the loop");
		float[] start = PanoramaMath.camera(0.0);
		float[] end = PanoramaMath.camera(period);
		for (int i = 0; i < start.length; i++) {
			check(Math.abs(start[i] - end[i]) < 1e-3, "the camera path is seamless at the loop point (component " + i + ")");
		}
		int samples = 7200;
		double dt = period / samples;
		float maxSpeed = 0f;
		float maxClimb = 0f;
		float maxTurn = 0f;
		float minClearance = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float[] previous = start;
		for (int i = 1; i <= samples; i++) {
			float[] next = PanoramaMath.camera(dt * i);
			maxSpeed = Math.max(maxSpeed, (float) (Math.hypot(next[0] - previous[0], next[1] - previous[1]) / dt));
			maxClimb = Math.max(maxClimb, (float) (Math.abs(next[5] - previous[5]) / dt));
			double turn = Math.abs(Math.atan2(next[3], next[2]) - Math.atan2(previous[3], previous[2]));
			maxTurn = Math.max(maxTurn, (float) (Math.min(turn, 2 * Math.PI - turn) / dt));
			check(Math.abs(Math.hypot(next[2], next[3]) - 1.0) < 1e-3, "the view direction is normalised");
			check(Math.abs(next[4]) <= PanoramaMath.MAX_BANK + 1e-6f, "banking stays gentle");
			minClearance = Math.min(minClearance, next[5] - (float) PanoramaMath.terrainHeight(next[0], next[1]));
			minY = Math.min(minY, next[5]);
			maxY = Math.max(maxY, next[5]);
			previous = next;
		}
		check(maxSpeed > 5f && maxSpeed < 40f, "the camera glides at a steady pace, max speed " + maxSpeed);
		// The old camera hugged every dune; the new one rides a smoothed envelope.
		check(maxClimb < 5f, "the camera rises and sinks slowly, max climb " + maxClimb + " units/s");
		check(maxTurn < 0.5f, "the camera turns gently, max turn " + maxTurn + " rad/s");
		check(minClearance >= PanoramaMath.MIN_CLEARANCE, "the camera never dips into a dune, min clearance " + minClearance);
		check(maxY - minY > 3f, "the flight follows the land a little instead of a flat plane");
	}

	private static void checkTerrain() {
		int[] texels = PanoramaMath.noiseTexels();
		check(texels.length == PanoramaMath.NOISE_SIZE * PanoramaMath.NOISE_SIZE, "one texel per noise cell");
		check(java.util.Arrays.equals(texels, PanoramaMath.noiseTexels()), "the noise is the same every time");
		boolean alphaVaries = false;
		for (int i = 1; i < 64; i++) alphaVaries |= (texels[i] >>> 24) != (texels[0] >>> 24);
		check(alphaVaries, "the alpha channel carries its own noise for the shader");

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		double sum = 0;
		double sumSq = 0;
		int count = 0;
		for (int x = -2000; x <= 2000; x += 16) {
			for (int z = -2000; z <= 2000; z += 16) {
				double h = PanoramaMath.terrainHeight(x, z);
				check(Double.isFinite(h), "terrain is finite");
				min = Math.min(min, h);
				max = Math.max(max, h);
				sum += h;
				sumSq += h * h;
				count++;
			}
		}
		double mean = sum / count;
		double spread = Math.sqrt(sumSq / count - mean * mean);
		check(max < PanoramaMath.MAX_TERRAIN_HEIGHT, "rays above the highest dune may stop marching, max " + max);
		check(max - min > 40, "there are real dunes, height range " + (max - min));
		check(spread > 4, "the land varies, spread " + spread);
		double step = Math.abs(PanoramaMath.terrainHeight(100.0, 200.0) - PanoramaMath.terrainHeight(100.01, 200.0));
		check(step < 0.1, "terrain is continuous");
	}

	/** Every dune style stays inside the shader's height limit, keeps the camera above the sand, and Mixed is the old terrain. */
	private static void checkDuneStyles() {
		PanoramaMath.DuneStyle mixed = PanoramaMath.duneStyle("Mixed", 1f);
		check(mixed.equals(PanoramaMath.DuneStyle.Companion.getDEFAULT()), "Mixed at height 1 is the default style");
		for (int x = -1000; x <= 1000; x += 97) {
			for (int z = -1000; z <= 1000; z += 89) {
				check(PanoramaMath.terrainHeight(x, z) == PanoramaMath.terrainHeight(x, z, mixed),
					"the default terrain is the Mixed style, so the old look is unchanged");
			}
		}
		check(PanoramaMath.duneStyle("nonsense", 1f).equals(mixed), "an unknown style falls back to Mixed");
		check(PanoramaMath.duneStyle("Mixed", 99f).getHeight() == PanoramaMath.MAX_DUNE_HEIGHT
			&& PanoramaMath.duneStyle("Mixed", -3f).getHeight() == PanoramaMath.MIN_DUNE_HEIGHT, "the height slider is clamped");
		double mixedSum = 0;
		for (String name : PanoramaMath.DUNE_STYLES) {
			PanoramaMath.DuneStyle style = PanoramaMath.duneStyle(name, PanoramaMath.MAX_DUNE_HEIGHT);
			double min = Double.MAX_VALUE;
			double max = -Double.MAX_VALUE;
			double diff = 0;
			int count = 0;
			for (int x = -2000; x <= 2000; x += 40) {
				for (int z = -2000; z <= 2000; z += 40) {
					double h = PanoramaMath.terrainHeight(x, z, style);
					check(Double.isFinite(h), name + " terrain is finite");
					min = Math.min(min, h);
					max = Math.max(max, h);
					diff += Math.abs(h - PanoramaMath.terrainHeight(x, z));
					count++;
				}
			}
			check(max < PanoramaMath.MAX_TERRAIN_HEIGHT, name + " at the tallest setting stays under the shader limit, max " + max);
			check(max - min > 20, name + " still has dunes, range " + (max - min));
			if (!name.equals("Mixed")) check(diff / count > 1.0, name + " differs from Mixed, mean difference " + diff / count);
			mixedSum += diff;
			float minClearance = Float.MAX_VALUE;
			for (int i = 0; i < 720; i++) {
				float[] camera = PanoramaMath.camera(i * 0.5, style);
				minClearance = Math.min(minClearance, camera[5] - (float) PanoramaMath.terrainHeight(camera[0], camera[1], style));
			}
			check(minClearance >= PanoramaMath.MIN_CLEARANCE, name + " keeps the camera above the sand, min clearance " + minClearance);
		}
		check(mixedSum > 0, "the styles are not all the same");
	}

	private static void checkTargetSize() {
		check(PanoramaMath.resolutionScale("Performance") == 0.5f, "Performance renders at 50% size");
		check(PanoramaMath.resolutionScale("Balanced") == 0.75f, "Balanced renders at 75% size");
		check(PanoramaMath.resolutionScale("Quality") == 1f, "Quality renders at full size");
		check(PanoramaMath.resolutionScale("nonsense") == 0.75f, "an unknown choice falls back to Balanced");
		check(PanoramaMath.targetSize(1920, 0.5f) == 960, "half of 1920 is 960");
		check(PanoramaMath.targetSize(1, 0.5f) == 1, "a target is never empty");
		check(PanoramaMath.targetSize(20000, 1f) == 4096, "a target is capped at 4096");
	}

	private static void checkParams() {
		float[] out = new float[PanoramaMath.PARAM_FLOATS];
		check(PanoramaMath.PARAM_FLOATS % 4 == 0, "the uniform block is whole vec4s, so std140 adds no padding");
		float[] camera = PanoramaMath.camera(12.0);
		PanoramaMath.DesertLook look = new PanoramaMath.DesertLook(0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0,
			PanoramaMath.duneStyle("Towering", 1.25f));
		PanoramaMath.writeParams(out, 12f, 16f / 9f, 100, 540, camera,
			0xFF3B82F6, 0xF8080808, 0xFFEAF2FF, look);
		check(Math.abs(out[28] - 0x10 / 255f) < 1e-6 && Math.abs(out[32] - 0x40 / 255f) < 1e-6
			&& Math.abs(out[36] - 0x70 / 255f) < 1e-6 && Math.abs(out[40] - 0xA0 / 255f) < 1e-6,
			"the four custom colours are the eighth to eleventh vec4");
		check(out[44] == (float) look.getStyle().getRidge() && out[45] == (float) look.getStyle().getWave()
			&& out[46] == (float) look.getStyle().getStar() && out[47] == (float) look.getStyle().getSwell()
			&& out[48] == (float) look.getStyle().getLinearBias() && out[49] == (float) look.getStyle().getStarBias()
			&& out[50] == (float) look.getStyle().getHeight(), "the dune style fills the last two vec4s");
		check(out[0] == 12f && Math.abs(out[1] - 16f / 9f) < 1e-6 && out[3] == 100f, "time, aspect and steps are the first vec4");
		check(Math.abs(out[2] - 2f / 540f) < 1e-7, "the pixel size follows the target height");
		check(out[10] == camera[5], "the camera height is in the third vec4");
		check(Math.abs(out[16] - 0x3B / 255f) < 1e-6 && Math.abs(out[17] - 0x82 / 255f) < 1e-6
			&& Math.abs(out[18] - 0xF6 / 255f) < 1e-6, "the accent is the fifth vec4");
		check(Math.abs(out[20] - 8 / 255f) < 1e-6, "the background is the sixth vec4");
		check(Math.abs(out[24] - 0xEA / 255f) < 1e-6, "the text colour is the seventh vec4");
		float lx = out[12], ly = out[13], lz = out[14];
		check(Math.abs(Math.sqrt(lx * lx + ly * ly + lz * lz) - 1.0) < 1e-4 && ly > 0f, "the light points down from above the horizon");
	}

	private static void checkCoverFit() {
		float[] wide = PanoramaMath.coverUv(2000, 1000, 1000, 1000, 1f, 0f, 0f);
		check(Math.abs(wide[0] - 0.25f) < 1e-6 && Math.abs(wide[1] - 0.75f) < 1e-6
			&& wide[2] == 0f && wide[3] == 1f, "a wide photo is cropped at the sides, centred");
		float[] tall = PanoramaMath.coverUv(1000, 2000, 1000, 1000, 1f, 0f, 0f);
		check(tall[0] == 0f && tall[1] == 1f && Math.abs(tall[2] - 0.25f) < 1e-6, "a tall photo is cropped top and bottom");
		float[] zoomed = PanoramaMath.coverUv(1000, 1000, 1000, 1000, 1.1f, 1f, -1f);
		check(zoomed[0] >= 0f && zoomed[1] <= 1f && zoomed[2] >= 0f && zoomed[3] <= 1f && zoomed[0] > 0.05f,
			"a zoomed pan never samples outside the photo");
	}

	private static void checkPhotoLoading() throws Exception {
		Path dir = Files.createTempDirectory("ga-panorama");
		try {
			PanoramaImage.Result missing = PanoramaImage.load(dir.resolve("panorama.png"));
			check(missing.pixels() == null && "missing".equals(missing.reason()), "a missing file says so");

			Path corrupt = dir.resolve("corrupt.png");
			Files.write(corrupt, "not an image".getBytes(StandardCharsets.UTF_8));
			check("unreadable".equals(PanoramaImage.load(corrupt).reason()), "a corrupt file is unreadable");

			Path png = dir.resolve("small.png");
			BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, 0xFF112233);
			ImageIO.write(image, "png", png.toFile());
			PanoramaImage.Result ok = PanoramaImage.load(png);
			check(ok.pixels() != null && ok.width() == 40 && ok.height() == 20 && "ok".equals(ok.reason())
				&& ok.pixels()[0] == 0xFF112233, "a small PNG loads unchanged");

			Path jpg = dir.resolve("photo.jpg");
			BufferedImage big = new BufferedImage(3200, 1600, BufferedImage.TYPE_INT_RGB);
			ImageIO.write(big, "jpg", jpg.toFile());
			PanoramaImage.Result scaled = PanoramaImage.load(jpg);
			check(scaled.pixels() != null && scaled.width() == PanoramaImage.MAX_SIDE && scaled.height() == PanoramaImage.MAX_SIDE / 2,
				"a large JPG loads and is scaled to the size limit, keeping its shape");

			Path huge = dir.resolve("huge.png");
			ImageIO.write(new BufferedImage(PanoramaImage.MAX_INPUT_SIDE + 1, 1, BufferedImage.TYPE_INT_RGB), "png", huge.toFile());
			check("too-large-dimensions".equals(PanoramaImage.load(huge).reason()), "an oversized photo is refused before decoding");
		} finally {
			try (var files = Files.list(dir)) {
				files.forEach(file -> file.toFile().delete());
			}
			Files.deleteIfExists(dir);
		}
	}

	private static void checkShaderResources() throws Exception {
		String shader = resource("/assets/geileraddons/shaders/core/panorama_desert.fsh");
		check(shader.contains("uniform sampler2D NoiseSampler;"), "the shader samples the noise texture by its bound name");
		check(shader.contains("uniform PanoramaParams"), "the shader declares the parameter block by its bound name");
		String[] fields = {"Params", "Camera", "CameraExtra", "LightDir", "Accent", "Background", "TextColor",
			"SandDark", "SandLight", "SkyColor", "SunsetColor", "Style0", "Style1"};
		int last = -1;
		for (String field : fields) {
			int at = shader.indexOf("vec4 " + field + ";");
			check(at > last, "block field " + field + " is a vec4 in writeParams order");
			last = at;
		}
		check(fields.length * 4 == PanoramaMath.PARAM_FLOATS, "the block has one vec4 per field");
		check(!shader.contains("pyramid") && !shader.contains("beacon"), "the pyramids and their light beam are gone");
		check(shader.contains("const float MAX_HEIGHT = " + PanoramaMath.MAX_TERRAIN_HEIGHT + ";"),
			"the shader's rising-ray cut-off matches the terrain limit");
		check(shader.contains("out float cover") && shader.contains("cover = min(cover,"),
			"the march measures how close a missed ray passes a crest, so silhouettes blend instead of stepping");
		check(shader.contains("float footA = foot / graze;"),
			"ripples and grain fade by the grazing-angle footprint, so near dunes do not shimmer");
		String fxaa = resource("/assets/geileraddons/shaders/core/panorama_fxaa.fsh");
		check(fxaa.contains("uniform sampler2D InSampler;"), "the edge smoothing pass samples the desert by its bound name");
	}

	private static String resource(String path) throws Exception {
		try (InputStream in = PanoramaChecks.class.getResourceAsStream(path)) {
			check(in != null, "resource exists: " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
