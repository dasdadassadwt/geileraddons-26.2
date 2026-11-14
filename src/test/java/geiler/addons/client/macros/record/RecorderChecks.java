package geiler.addons.client.macros.record;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The human recorder's file: it is bounded however long it runs, the oldest samples are the ones that go, every segment
 * starts with its own header so it can be read alone, and every line it writes is one JSON object with a kind and a time.
 */
public final class RecorderChecks {
	private RecorderChecks() { }

	public static void run() throws IOException {
		checkBounded();
		checkLines();
		checkKeys();
	}

	private static void checkBounded() throws IOException {
		Path dir = Files.createTempDirectory("recorder-checks");
		try {
			long max = 100_000L;
			String header = "{\"k\":\"meta\",\"t\":0,\"session\":\"s\"}";
			try (RecordingStore store = new RecordingStore(dir, "s", max, header)) {
				for (int i = 0; i < 5000; i++) store.append(String.format("{\"k\":\"f\",\"t\":%d,\"pad\":\"%s\"}", i, "x".repeat(70)));
				check(store.bytes() <= max, "the recording stays within its bound (" + store.bytes() + " of " + max + ")");
				check(store.bytes() > max / 4, "and keeps a good share of the newest samples (" + store.bytes() + ")");
			}
			List<Path> files = list(dir);
			check(files.size() <= 2, "at most two segments are kept (" + files.size() + ")");
			long onDisk = 0;
			for (Path file : files) onDisk += Files.size(file);
			check(onDisk <= max, "the files on disk add up to no more than the bound (" + onDisk + ")");
			List<String> newest = Files.readAllLines(files.get(files.size() - 1));
			check(newest.get(0).equals(header), "every segment starts with the header");
			check(newest.get(newest.size() - 1).contains("\"t\":4999"), "the newest sample is the last one written");
			List<String> oldest = Files.readAllLines(files.get(0));
			check(oldest.get(0).equals(header), "the older segment has the header too");
			check(!oldest.stream().anyMatch(l -> l.contains("\"t\":0,\"pad\"")), "the first samples are the ones that were rotated out");
			for (String line : newest) JsonParser.parseString(line);
		} finally {
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
			}
		}
	}

	private static List<Path> list(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.sorted().collect(Collectors.toList());
		}
	}

	private static void checkLines() {
		JsonObject frame = JsonParser.parseString(RecordLines.frame(1234L, 12.3456f, -3.25f)).getAsJsonObject();
		check(frame.get("k").getAsString().equals("f") && frame.get("t").getAsLong() == 1234L, "a frame line has its kind and time");
		check(Math.abs(frame.get("yaw").getAsDouble() - 12.3456) < 1.0E-3 && Math.abs(frame.get("pitch").getAsDouble() + 3.25) < 1.0E-3, "and the view angles");

		JsonObject tick = JsonParser.parseString(RecordLines.tick(2000L, 40L, 1.5, 64.0, -2.25, 0.1, 0.2, "WJ", 3, true, "creeper#7")).getAsJsonObject();
		check(tick.get("k").getAsString().equals("t") && tick.get("keys").getAsString().equals("WJ"), "a tick line has the keys");
		check(tick.get("atk").getAsBoolean() && tick.get("on").getAsString().equals("creeper#7"), "and the attack key and what the crosshair is on");
		JsonObject none = JsonParser.parseString(RecordLines.tick(2050L, 41L, 0, 0, 0, 0, 0, "", 0, false, null)).getAsJsonObject();
		check(none.get("on").isJsonNull(), "nothing under the crosshair is null");

		JsonObject mobs = JsonParser.parseString(RecordLines.mobs(2100L, List.of(new RecordLines.MobSample(7, "minecraft:creeper", 12.5, -33.0, 4.0, 3)))).getAsJsonObject();
		check(mobs.get("k").getAsString().equals("m") && mobs.getAsJsonArray("mobs").size() == 1, "a mob line lists the mobs");
		JsonObject mob = mobs.getAsJsonArray("mobs").get(0).getAsJsonObject();
		check(mob.get("id").getAsInt() == 7 && Math.abs(mob.get("dist").getAsDouble() - 12.5) < 1.0E-3 && mob.get("crowd").getAsInt() == 3,
			"with their id, distance, bearing off the view and crowd");

		JsonObject event = JsonParser.parseString(RecordLines.event(3000L, "kill", "mob=creeper#7 sinceLastMs=830")).getAsJsonObject();
		check(event.get("k").getAsString().equals("e") && event.get("ev").getAsString().equals("kill"), "an event line has its name");
		JsonObject quote = JsonParser.parseString(RecordLines.event(3001L, "note", "a \"quoted\" \\ text\n")).getAsJsonObject();
		check(quote.get("info").getAsString().equals("a \"quoted\" \\ text\n"), "text with quotes and newlines survives as one JSON line");
		check(!RecordLines.event(3002L, "note", "line\nbreak").contains("\n"), "and no line break reaches the file");
	}

	private static void checkKeys() {
		check(RecordLines.keys(true, false, false, false, false, false, false).equals("W"), "forward alone is W");
		check(RecordLines.keys(true, false, true, false, true, true, true).equals("WAJCR"), "forward, left, jump, sneak and sprint");
		check(RecordLines.keys(false, false, false, false, false, false, false).isEmpty(), "no keys is empty");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
