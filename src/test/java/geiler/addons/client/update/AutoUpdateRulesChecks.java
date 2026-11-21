package geiler.addons.client.update;

import java.net.URI;
import java.util.List;

/** Offline checks for choosing, verifying and swapping in an auto-updated jar. */
public final class AutoUpdateRulesChecks {
	private static final String SHA = "sha256:" + "ab".repeat(32);

	private AutoUpdateRulesChecks() { }

	public static void run() {
		AutoUpdateRules.Asset jar = new AutoUpdateRules.Asset("geileraddons-1.8.3.jar",
			"https://github.com/dasdadassadwt/geileraddons-26.2/releases/download/v1.8.3/geileraddons-1.8.3.jar", 12_000_000L, SHA);
		AutoUpdateRules.Asset sources = new AutoUpdateRules.Asset("geileraddons-1.8.3-sources.jar", jar.getUrl(), 1_000L, SHA);
		AutoUpdateRules.Asset readme = new AutoUpdateRules.Asset("notes.txt", jar.getUrl(), 100L, SHA);
		check(AutoUpdateRules.pickAsset(List.of(readme, sources, jar), "1.8.3") == jar, "the plain mod jar of the release is chosen");
		check(AutoUpdateRules.pickAsset(List.of(readme, sources), "1.8.3") == null, "no mod jar gives no update");
		check(AutoUpdateRules.pickAsset(List.of(jar), "1.9.0") == null, "a jar whose name is not the release version is not trusted");
		check(AutoUpdateRules.pickAsset(List.of(new AutoUpdateRules.Asset(jar.getName(), jar.getUrl(), jar.getSize(), null)), "1.8.3") == null,
			"an asset without a digest cannot be verified and is skipped");
		check(AutoUpdateRules.pickAsset(List.of(new AutoUpdateRules.Asset(jar.getName(), jar.getUrl(), AutoUpdateRules.MAX_JAR_BYTES + 1, SHA)), "1.8.3") == null,
			"an oversized asset is skipped");
		check(AutoUpdateRules.pickAsset(List.of(new AutoUpdateRules.Asset(jar.getName(), "http://github.com/x.jar", jar.getSize(), SHA)), "1.8.3") == null,
			"a plain http download is skipped");
		check(AutoUpdateRules.pickAsset(List.of(new AutoUpdateRules.Asset(jar.getName(), "https://evil.example/x.jar", jar.getSize(), SHA)), "1.8.3") == null,
			"a download from another host is skipped");

		check(AutoUpdateRules.trustedHost(URI.create("https://github.com/a/b")) && AutoUpdateRules.trustedHost(URI.create("https://objects.githubusercontent.com/x"))
			&& !AutoUpdateRules.trustedHost(URI.create("https://github.com.evil.example/x")) && !AutoUpdateRules.trustedHost(URI.create("https://notgithub.com/x")),
			"only GitHub hosts are trusted, whole labels only");

		check(AutoUpdateRules.digestMatches(SHA, "ab".repeat(32)) && AutoUpdateRules.digestMatches(SHA.toUpperCase(), "AB".repeat(32))
			&& !AutoUpdateRules.digestMatches(SHA, "cd".repeat(32)) && !AutoUpdateRules.digestMatches("md5:abcd", "abcd"), "digests compare case-insensitively and only as sha256");

		String manifest = "{\"id\":\"geileraddons\",\"version\":\"1.8.3\",\"depends\":{\"minecraft\":\"~26.2\",\"fabricloader\":\">=0.19\"}}";
		check(AutoUpdateRules.manifestMatches(manifest, "geileraddons", "1.8.3", "26.2"), "a jar for this Minecraft version and release is accepted");
		check(AutoUpdateRules.manifestMatches(manifest, "geileraddons", "1.8.3", "26.2.1"), "a patch version of the same Minecraft release is accepted");
		check(!AutoUpdateRules.manifestMatches(manifest, "geileraddons", "1.8.3", "26.3"), "a jar for another Minecraft version is rejected");
		check(!AutoUpdateRules.manifestMatches(manifest, "geileraddons", "1.8.4", "26.2"), "a jar with another version than the release is rejected");
		check(!AutoUpdateRules.manifestMatches(manifest, "other", "1.8.3", "26.2") && !AutoUpdateRules.manifestMatches("nope", "geileraddons", "1.8.3", "26.2"),
			"another mod or unreadable data is rejected");
		check(AutoUpdateRules.manifestMatches("{\"id\":\"geileraddons\",\"version\":\"1.8.3\",\"depends\":{\"minecraft\":[\">=26.2 <26.3\"]}}", "geileraddons", "1.8.3", "26.2"),
			"a list of ranges naming the version is accepted");

		check("1.8.3".equals(AutoUpdateRules.pendingVersion("geileraddons-1.8.3.jar.update")) && AutoUpdateRules.pendingVersion("geileraddons-1.8.3.jar") == null
			&& AutoUpdateRules.pendingVersion("other-1.0.jar.update") == null, "a staged update file names its version");
		check(AutoUpdateRules.safePath("C:\\Users\\me\\mods\\geileraddons-1.8.2.jar") && !AutoUpdateRules.safePath("C:\\a%b\\x.jar")
			&& !AutoUpdateRules.safePath("C:\\a\"b\\x.jar") && !AutoUpdateRules.safePath("C:\\a&b\\x.jar"), "paths with batch metacharacters are not scripted");

		String script = AutoUpdateRules.swapScript("C:\\mods\\geileraddons-1.8.2.jar", "C:\\mods\\geileraddons-1.8.3.jar.update", "C:\\mods\\geileraddons-1.8.3.jar");
		check(script.contains("del /f /q \"C:\\mods\\geileraddons-1.8.2.jar\"") && script.contains("move /y \"C:\\mods\\geileraddons-1.8.3.jar.update\" \"C:\\mods\\geileraddons-1.8.3.jar\"")
			&& script.indexOf("del /f /q") < script.indexOf("move /y"), "the script removes the old jar first and then moves the new one into place");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
