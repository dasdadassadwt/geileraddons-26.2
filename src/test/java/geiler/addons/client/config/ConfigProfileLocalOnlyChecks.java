package geiler.addons.client.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Pure regression checks for portable paths, client-local data, and import preservation. */
public final class ConfigProfileLocalOnlyChecks {
	private ConfigProfileLocalOnlyChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		try {
			byte[] config = bytes("{}");
			Map<String, byte[]> current = new LinkedHashMap<>();
			current.put("config.json", config);
			Map<String, byte[]> portable = new LinkedHashMap<>();
			portable.put("scoreboard/layout.json", bytes("{\"version\":1}"));
			portable.put("inventory-controls/controls.json", bytes("{\"version\":1}"));
			portable.put("secret-waypoints.json", bytes("{\"version\":1}"));
			current.putAll(portable);
			Map<String, byte[]> localOnly = localOnlyFiles();
			current.putAll(localOnly);
			byte[] exported = ConfigProfileCodec.encode(current);
			ConfigProfileCodec.Archive fresh = ConfigProfileCodec.decode(exported);
			Set<String> exportedMembers = archiveMembers(exported);
			check(fresh.files().containsKey("config.json"), "portable profile keeps required config file");
			for (String path : portable.keySet()) {
				check(ConfigProfileCodec.safePath(path), "exact transferable feature path is allowlisted: " + path);
				check(ConfigProfileCodec.isTransferablePath(path), "transfer walker and installer accept the feature path: " + path);
				check(exportedMembers.contains(path), "archive export contains transferable feature path: " + path);
				check(java.util.Arrays.equals(portable.get(path), fresh.files().get(path)), "portable feature path round-trips: " + path);
			}
			for (String path : localOnly.keySet()) {
				check(ConfigProfileCodec.isLocalOnlyPath(path), "feature-local file is classified local-only: " + path);
				check(!ConfigProfileCodec.isTransferablePath(path), "transfer walker and installer exclude local-only file: " + path);
				check(!exportedMembers.contains(path), "new exports omit local-only file: " + path);
			}
			check(!ConfigProfileCodec.safePath("scoreboard/layout.json.tmp"), "scoreboard allowlist does not extend to neighboring paths");
			check(!ConfigProfileCodec.safePath("inventory-controls/other.json"), "inventory-controls allowlist does not extend to neighboring paths");
			check(!ConfigProfileCodec.safePath("secret-waypoints/secret.json"), "secret-waypoint allowlist is file-specific");
			check(!ConfigProfileCodec.safePath("scoreboard/fonts/../layout.json"), "font-local path rule does not bypass traversal validation");
			boolean unsafeLocalRejected = false;
			try {
				ConfigProfileCodec.encode(Map.of("config.json", config,
					"scoreboard/fonts/../escape.ttf", new byte[] { 1 }));
			} catch (IOException expected) { unsafeLocalRejected = true; }
			check(unsafeLocalRejected, "export rejects malformed paths even when their prefix is local-only");

			Map<String, byte[]> legacyFiles = new LinkedHashMap<>();
			legacyFiles.put("config.json", config);
			legacyFiles.putAll(portable);
			legacyFiles.putAll(localOnly);
			ConfigProfileCodec.Archive legacy = ConfigProfileCodec.decode(legacyArchive(legacyFiles));
			for (String path : localOnly.keySet())
				check(!legacy.files().containsKey(path), "legacy profile import discards local-only member: " + path);
			for (String path : portable.keySet())
				check(legacy.files().containsKey(path), "legacy profile import retains transferable member: " + path);

			Path root = Files.createTempDirectory("ga-profile-local-only-");
			try {
				for (Map.Entry<String, byte[]> local : localOnly.entrySet()) {
					Path localFile = root.resolve(local.getKey());
					Files.createDirectories(localFile.getParent());
					Files.write(localFile, local.getValue());
				}
				ConfigProfileInstaller.Result installed = ConfigProfileInstaller.install(root,
					fresh.files(), null, ConfigProfileCodec::safePath, ignored -> { }, null);
				check(installed.installed(), "portable import completes while client-local files are present");
				for (Map.Entry<String, byte[]> local : localOnly.entrySet())
					check(java.util.Arrays.equals(local.getValue(), Files.readAllBytes(root.resolve(local.getKey()))),
						"profile install preserves local-only bytes: " + local.getKey());
				for (Map.Entry<String, byte[]> entry : portable.entrySet())
					check(java.util.Arrays.equals(entry.getValue(), Files.readAllBytes(root.resolve(entry.getKey()))),
						"profile install writes exact transferable bytes: " + entry.getKey());
				byte[] rejectedLocal = bytes("{\"replacement\":true}");
				ConfigProfileInstaller.Result rejected = ConfigProfileInstaller.install(root,
					Map.of("config.json", config, "chat-channels-local.json", rejectedLocal), null,
					ConfigProfileCodec::safePath, ignored -> { }, null);
				check(!rejected.installed(), "installer rejects direct local-only payloads");
				check(java.util.Arrays.equals(localOnly.get("chat-channels-local.json"),
					Files.readAllBytes(root.resolve("chat-channels-local.json"))),
					"rejected direct install leaves the local chat file unchanged");
			} finally { deleteTree(root); }
		} catch (IOException failure) {
			throw new AssertionError("local-only profile check failed unexpectedly", failure);
		}
	}

	private static Map<String, byte[]> localOnlyFiles() {
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("chat-channels-local.json", bytes("{\"azureKey\":\"local-only-secret\"}"));
		files.put("scoreboard/fonts/User Font.otf", new byte[] { 1, 2, 3, 4 });
		files.put("scoreboard/fonts/archive/Old User Font.ttf", new byte[] { 5, 6, 7, 8 });
		files.put("pest-hourly-stats.json", bytes("{\"kills\":12}"));
		files.put("slayer-hourly-stats.json", bytes("{\"kills\":34}"));
		files.put("player-presentations-cache.json", bytes("{\"profiles\":[]}"));
		files.put("player-sizes.json", bytes("{\"version\":1,\"overrides\":{}}"));
		files.put("dungeon-split-personal-bests.json", bytes("{\"version\":1,\"entries\":[]}"));
		files.put("dungeon-puzzle-personal-bests.json", bytes("{\"version\":1,\"entries\":[]}"));
		files.put("lobby-sniper-targets.json", bytes("{\"targets\":[]}"));
		files.put("miniboss-appearance-signatures.json", bytes("{\"schema\":\"geileraddons-miniboss-appearance-v2\"}"));
		return files;
	}

	private static byte[] legacyArchive(Map<String, byte[]> files) throws IOException {
		StringBuilder entries = new StringBuilder();
		long total = 0;
		for (Map.Entry<String, byte[]> entry : files.entrySet()) {
			if (entries.length() > 0) entries.append(',');
			entries.append(entry(entry.getKey(), entry.getValue()));
			total += entry.getValue().length;
		}
		String manifest = "{\"format\":\"geileraddons-config\",\"version\":1,\"createdAt\":\""
			+ Instant.now() + "\",\"totalBytes\":" + total + ",\"entries\":[" + entries + "]}";
		Map<String, byte[]> members = new LinkedHashMap<>();
		members.put("manifest.json", bytes(manifest));
		members.putAll(files);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			for (Map.Entry<String, byte[]> member : members.entrySet()) {
				zip.putNextEntry(new ZipEntry(member.getKey()));
				zip.write(member.getValue());
				zip.closeEntry();
			}
		}
		return output.toByteArray();
	}

	private static Set<String> archiveMembers(byte[] archive) throws IOException {
		Set<String> result = new java.util.HashSet<>();
		try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) result.add(entry.getName());
		}
		return result;
	}

	private static String entry(String path, byte[] data) throws IOException {
		String digest;
		try { digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
		catch (Exception unavailable) { throw new IOException(unavailable); }
		return "{\"path\":\"" + path + "\",\"size\":" + data.length + ",\"sha256\":\"" + digest + "\"}";
	}

	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}
}
