package geiler.addons.client.spotify;

import geiler.addons.client.module.impl.SpotifyModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline checks for the Spotify HUD: Web API and bridge parsing, PKCE, interpolation and the mod-side blocklist. */
public final class SpotifyChecks {
	private SpotifyChecks() { }

	public static void main(String[] args) throws Exception { run(); }

	public static void run() throws Exception {
		checkWebApiParsing();
		checkBridgeParsing();
		checkPkce();
		checkBlocklist();
		checkCoverAndClicks();
		require("3:35".equals(SpotifyModule.clock(215_000L)) && "0:00".equals(SpotifyModule.clock(-5L)), "clock text");
		require("1:23 / 3:35".equals(SpotifyModule.timeText(83_000L, 215_000L, true))
			&& "1:23 / 3:35  paused".equals(SpotifyModule.timeText(83_000L, 215_000L, false))
			&& "1:23".equals(SpotifyModule.timeText(83_000L, 0L, true)), "time text drops an unknown song length");
	}

	private static final String PLAYER = "{\"is_playing\":true,\"progress_ms\":10000,\"shuffle_state\":false,\"repeat_state\":\"off\","
		+ "\"device\":{\"volume_percent\":55},\"item\":{\"id\":\"T1\",\"name\":\"Song\",\"duration_ms\":200000,"
		+ "\"artists\":[{\"id\":\"A1\",\"name\":\"One\"},{\"id\":\"A2\",\"name\":\"Two\"}],"
		+ "\"album\":{\"name\":\"Album\",\"images\":[{\"url\":\"big\",\"width\":640},{\"url\":\"mid\",\"width\":300},{\"url\":\"small\",\"width\":64}]}}}";

	private static void checkWebApiParsing() {
		SpotifyPlayback p = SpotifyPlayback.parseWebApi(PLAYER, 1_000L);
		require(p != null && "T1".equals(p.getTrackId()) && "Song".equals(p.getTitle()) && "One, Two".equals(p.getArtistText()),
			"track, title and artists are read");
		require("small".equals(p.getArtUrl()), "the smallest cover of at least 64 px is chosen");
		require(p.getVolume() == 55 && Boolean.FALSE.equals(p.getShuffle()) && "off".equals(p.getRepeat()), "device and modes are read");
		require(p.progressAt(3_000L) == 12_000L, "playing progress advances with time");
		require(p.progressAt(1_000_000L) == 200_000L, "progress stops at the song's end");
		SpotifyPlayback paused = SpotifyPlayback.parseWebApi(PLAYER.replace("\"is_playing\":true", "\"is_playing\":false"), 1_000L);
		require(paused.progressAt(9_000L) == 10_000L, "paused progress stays put");
		require(SpotifyPlayback.parseWebApi("", 0L) == null && SpotifyPlayback.parseWebApi("{\"is_playing\":true}", 0L) == null
			&& SpotifyPlayback.parseWebApi("not json", 0L) == null, "empty bodies, ads and junk show nothing");
	}

	private static void checkBridgeParsing() {
		SpotifyPlayback p = SpotifyPlayback.parseBridge(
			"{\"type\":\"state\",\"title\":\"Song\",\"artist\":\"One\",\"album\":\"A\",\"pos\":5000,\"dur\":100000,\"playing\":true}", 0L);
		require(p != null && p.getSource() == SpotifyPlayback.Source.WINDOWS && "Song|One".equals(p.getKey()) && p.getDurationMs() == 100_000L,
			"bridge state lines are read");
		SpotifyPlayback extra = SpotifyPlayback.parseBridge("{\"type\":\"state\",\"title\":\"Song\",\"artist\":\"One\",\"pos\":5000,"
			+ "\"dur\":0,\"playing\":true,\"rawPos\":5000,\"ago\":120,\"thumb\":true}", 0L);
		require(extra != null && extra.getDurationMs() == 0L && extra.progressAt(1_000L) == 6_000L,
			"diagnostic bridge fields are ignored and an unknown length still advances");
		require(SpotifyPlayback.parseBridge("{\"type\":\"none\"}", 0L) == null
			&& SpotifyPlayback.parseBridge("{\"type\":\"state\",\"title\":\"\"}", 0L) == null, "no session or no title shows nothing");
		String stripped = WindowsMediaBridge.withoutNames("{\"type\":\"state\",\"title\":\"Secret Song\",\"artist\":\"Someone\",\"album\":\"A\",\"pos\":1,\"ago\":5}");
		require(stripped != null && !stripped.contains("Secret Song") && !stripped.contains("Someone") && stripped.contains("\"ago\":5")
			&& stripped.contains("\"artistBlank\":false"), "diagnostic bridge lines drop the song names");
		String art = "{\"type\":\"art\",\"key\":\"k\",\"data\":\"" + Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 }) + "\"}";
		var parsed = WindowsMediaBridge.parseArt(art);
		require(parsed != null && "k".equals(parsed.getFirst()) && parsed.getSecond().length == 3, "thumbnail lines decode");
	}

	private static void checkPkce() throws Exception {
		String verifier = SpotifyAuth.newVerifier();
		require(verifier.length() >= 43 && verifier.length() <= 128 && verifier.matches("[A-Za-z0-9_-]+"), "the verifier meets RFC 7636");
		String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
			MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		require(expected.equals(SpotifyAuth.challenge(verifier)), "the challenge is S256 of the verifier");
		String url = SpotifyAuth.authorizeUrl("abc", 8888, verifier, "st");
		require(url.startsWith("https://accounts.spotify.com/authorize?") && url.contains("client_id=abc")
			&& url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8888%2Fcallback") && url.contains("code_challenge_method=S256")
			&& url.contains("user-modify-playback-state") && !url.contains("client_secret"), "the authorize URL uses PKCE and loopback");
		Map<String, String> q = SpotifyAuth.query("code=a%2Bb&state=xy&bad");
		require("a+b".equals(q.get("code")) && "xy".equals(q.get("state")) && q.size() == 2, "callback queries decode");
	}

	private static void checkBlocklist() {
		SpotifyPlayback p = SpotifyPlayback.parseWebApi(PLAYER, 0L);
		require(!SpotifyBlocklist.blocked(p, Set.of()), "nothing is blocked by default");
		require(SpotifyBlocklist.blocked(p, Set.of(SpotifyBlocklist.trackEntry(p))), "a blocked song matches");
		require(SpotifyBlocklist.blocked(p, Set.of("artist:A2")), "any blocked artist on the song matches");
		SpotifyPlayback windows = SpotifyPlayback.parseBridge(
			"{\"type\":\"state\",\"title\":\"Song\",\"artist\":\"One\",\"pos\":0,\"dur\":1,\"playing\":true}", 0L);
		require("artist:one".equals(SpotifyBlocklist.artistEntry(windows)) && SpotifyBlocklist.blocked(windows, List.of("artist:one")),
			"without ids, artists are blocked by lowercase name");
	}

	/** PowerShell 5.1 reads the WinRT stream Size as empty (seen 2026-11-27), so the cover must not depend on it. */
	private static void checkCoverAndClicks() {
		String script = WindowsMediaBridge.SCRIPT;
		require(!script.contains(".Size"), "bridge cover read does not use the WinRT Size property");
		require(script.contains("GetMethod('AsStreamForRead'") && script.contains("\"artskip\""),
			"bridge copies the cover through a .NET stream and reports empty reads");
		require(geiler.addons.client.hud.HudManager.insidePanel(100, 50, 176, 166, 100.0, 50.0)
			&& !geiler.addons.client.hud.HudManager.insidePanel(100, 50, 176, 166, 276.0, 50.0)
			&& !geiler.addons.client.hud.HudManager.insidePanel(100, 50, 176, 166, 99.5, 120.0),
			"inventory clicks reach the HUD only outside the menu panel");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
