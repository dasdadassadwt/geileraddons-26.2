package geiler.addons.client.config;

import geiler.addons.client.scoreboard.ScoreboardLayout;
import geiler.addons.client.scoreboard.ScoreboardLayoutCodec;
import geiler.addons.client.scoreboard.ScoreboardLayoutStore;
import geiler.addons.client.scoreboard.ScoreboardLayoutState;
import geiler.addons.client.scoreboard.ScoreboardFontService;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pure checks for scoreboard profile reload and the isolated reset area. */
public final class ScoreboardProfileChecks {
	private ScoreboardProfileChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		Path root = null;
		try {
			checkBundledFonts();
			ScoreboardLayout imported = new ScoreboardLayout();
			imported.lines().clear();
			imported.lines().add(geiler.addons.client.scoreboard.ScoreboardLine.customText("Imported profile"));
			imported.setWidth(242);
			imported.setFontId("geileraddons:scoreboard/local-font");
			root = Files.createTempDirectory("ga-scoreboard-profile-").resolve("config").resolve("geileraddons");
			Path layoutFile = root.resolve("scoreboard/layout.json");
			checkStoreRoundTripAndCorruption(layoutFile, imported);

			Map<String, byte[]> profile = new LinkedHashMap<>();
			profile.put("config.json", bytes("{}"));
			profile.put("scoreboard/layout.json", bytes(ScoreboardLayoutCodec.encode(imported)));
			ConfigProfileCodec.Archive decoded = ConfigProfileCodec.decode(ConfigProfileCodec.encode(profile));
			byte[] layoutBytes = decoded.files().get("scoreboard/layout.json");
			check(layoutBytes != null, "scoreboard layout participates in portable config profiles");
			ConfigProfileInstaller.Result installed = ConfigProfileInstaller.install(root, decoded.files(), null,
				ConfigProfileCodec::safePath, files -> {
					byte[] candidate = files.get("scoreboard/layout.json");
					if (candidate != null && ScoreboardLayoutCodec.decode(
						new String(candidate, StandardCharsets.UTF_8)).isEmpty()) {
						throw new IOException("Portable scoreboard layout is invalid.");
					}
				}, null);
			check(installed.installed(), "portable profile installer accepts a valid scoreboard layout");
			ScoreboardLayoutState.ReloadResult loaded = ScoreboardLayoutState.reload(new ScoreboardLayout(),
				ScoreboardLayoutStore.loadForReload(layoutFile));
			check(loaded.accepted() && loaded.layout().width() == 242
				&& loaded.layout().lines().size() == 1
				&& loaded.layout().lines().getFirst().text().equals("Imported profile"),
				"a portable profile install reloads the copied scoreboard layout from disk");

			ScoreboardLayoutState.ReloadResult rejected = ScoreboardLayoutState.reload(loaded.layout(),
				ScoreboardLayoutCodec.decode("{"));
			check(!rejected.accepted() && rejected.layout() == loaded.layout()
				&& rejected.layout().width() == 242, "a malformed profile reload keeps the current layout intact");

			ScoreboardLayout preserved = ScoreboardLayoutState.reset(loaded.layout(), false);
			ScoreboardLayout defaults = ScoreboardLayoutState.reset(loaded.layout(), true);
			check(preserved == loaded.layout() && defaults != loaded.layout()
				&& defaults.width() == 0 && defaults.lines().size() == 16
				&& defaults.fontId().equals("geileraddons:scoreboard/noto_sans"),
				"unselected Scoreboard reset preserves layout state and selected reset restores bundled defaults");
			ConfigResetPlan.Plan scoreboardOnly = ConfigResetPlan.create(false, false, false, true,
				"Macros", Set.of("Dungeon Mob ESP"));
			check(scoreboardOnly.resetScoreboard() && !scoreboardOnly.resetAllModules()
				&& scoreboardOnly.namedModules().isEmpty(), "Scoreboard reset selection is independent of module reset scopes");
		} catch (IOException failure) {
			throw new AssertionError("scoreboard profile acceptance check failed unexpectedly", failure);
		} finally {
			if (root != null) {
				try { deleteTree(root.getParent().getParent()); }
				catch (IOException failure) { throw new AssertionError("Could not clean the isolated scoreboard profile.", failure); }
			}
		}
	}

	private static void checkStoreRoundTripAndCorruption(Path layoutFile, ScoreboardLayout custom) throws IOException {
		ScoreboardLayout missing = ScoreboardLayoutStore.loadForReload(layoutFile).orElseThrow();
		check(missing.width() == 0 && missing.lines().size() == 16,
			"a missing persisted layout selects bundled defaults");
		check(ScoreboardLayoutStore.save(custom, layoutFile), "a custom layout saves to its profile-local file");
		check(Files.isRegularFile(layoutFile) && !Files.exists(layoutFile.resolveSibling("layout.json.tmp")),
			"the layout save finishes its atomic replacement without leaving a temporary file");
		ScoreboardLayout reloaded = ScoreboardLayoutStore.loadForReload(layoutFile).orElseThrow();
		check(reloaded.width() == custom.width() && reloaded.lines().size() == 1
			&& reloaded.lines().getFirst().text().equals("Imported profile")
			&& reloaded.fontId().equals(custom.fontId()),
			"a saved custom layout reloads with its editable content");

		Files.write(layoutFile, bytes("{"));
		assertCorruptStoreKeepsCurrent(layoutFile, reloaded, "malformed JSON");
		Files.write(layoutFile, new byte[] {(byte) 0xC3, (byte) 0x28});
		assertCorruptStoreKeepsCurrent(layoutFile, reloaded, "malformed UTF-8");
		Files.write(layoutFile, new byte[ScoreboardLayoutCodec.MAX_JSON_CHARS * 4 + 1]);
		assertCorruptStoreKeepsCurrent(layoutFile, reloaded, "oversized file");
	}

	private static void assertCorruptStoreKeepsCurrent(Path layoutFile, ScoreboardLayout current, String description)
		throws IOException {
		byte[] storedBytes = Files.readAllBytes(layoutFile);
		ScoreboardLayoutState.ReloadResult rejected = ScoreboardLayoutState.reload(current,
			ScoreboardLayoutStore.loadForReload(layoutFile));
		check(!rejected.accepted() && rejected.layout() == current,
			"a " + description + " layout file is rejected while the active layout is preserved");
		check(java.util.Arrays.equals(Files.readAllBytes(layoutFile), storedBytes),
			"a " + description + " layout file remains byte-for-byte intact after rejection");
	}

	private static void checkBundledFonts() throws IOException {
		String[][] fonts = {
			{"noto_sans", "SIL OPEN FONT LICENSE Version 1.1"},
			{"atkinson_hyperlegible_next", "SIL OPEN FONT LICENSE Version 1.1"},
			{"ibm_plex_sans", "SIL OPEN FONT LICENSE Version 1.1"},
			{"roboto_mono", "SIL OPEN FONT LICENSE Version 1.1"},
			{"roboto_slab", "Apache License"},
		};
		for (String[] font : fonts) {
			String name = font[0];
			String fontId = "geileraddons:scoreboard/" + name;
			String resourceRoot = "/assets/geileraddons/font/scoreboard/";
			check(ScoreboardFontService.choices().stream().anyMatch(choice -> choice.id().equals(fontId)),
				"bundled font remains in the scoreboard font catalog: " + name);
			String definition = readResource(resourceRoot + name + ".json");
			check(definition.contains("geileraddons:scoreboard/" + name + ".ttf"),
				"bundled font definition points at its packaged TTF: " + name);
			check(resourceHasBytes(resourceRoot + name + ".ttf"), "bundled font TTF is packaged: " + name);
			check(readResource(resourceRoot + "license_" + name + ".txt").contains(font[1]),
				"bundled font keeps its complete license notice: " + name);
		}
		String fontId = "geileraddons:scoreboard/noto_sans";
		Identifier identifier = Identifier.tryParse(fontId);
		check(identifier != null && new FontDescription.Resource(identifier) != null,
			"the bundled default ID is accepted by the renderer's font descriptor type");
	}

	private static boolean resourceHasBytes(String path) throws IOException {
		try (InputStream stream = ScoreboardProfileChecks.class.getResourceAsStream(path)) {
			return stream != null && stream.read() >= 0;
		}
	}

	private static String readResource(String path) throws IOException {
		try (InputStream stream = ScoreboardProfileChecks.class.getResourceAsStream(path)) {
			if (stream == null) throw new IOException("Missing bundled test resource: " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
	private static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
