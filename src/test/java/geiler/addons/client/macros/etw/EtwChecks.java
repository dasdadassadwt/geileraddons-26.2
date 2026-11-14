package geiler.addons.client.macros.etw;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Covers the /etw recorder's pure logic: spherical block set, mode choice, sample matching, immutability and JSON. */
public final class EtwChecks {
	private EtwChecks() { }

	private static long clock = 1_000L;

	public static void run() throws Exception {
		checkSphere();
		checkMath();
		checkLifecycle();
		checkModeSelection();
		checkFifoMatching();
		checkOverlappingBModeClicks();
		checkImmutability();
		checkObservedPacketsAndUnattributed();
		checkJson();
		checkStore();
	}

	private static EtwRecorder recorder() {
		clock = 1_000L;
		return new EtwRecorder(() -> clock += 10, () -> "a3f9");
	}

	private static Xyz xyz(double x, double y, double z) {
		return new Xyz(x, y, z);
	}

	private static EtwSample sample(EtwMode mode, long tick, long nano, double yaw, double pitch) {
		JsonObject click = new JsonObject();
		click.addProperty("tick", tick);
		click.addProperty("yaw", yaw);
		click.addProperty("pitch", pitch);
		ClickNumbers numbers = new ClickNumbers(xyz(100.5, 64.0, -20.25), xyz(100.5, 65.62, -20.25), yaw, pitch,
			mode == EtwMode.A ? xyz(110.25, 70.5, -20.75) : null, mode == EtwMode.A ? new IntXyz(110, 70, -21) : null,
			tick, nano);
		JsonObject targeting = mode == EtwMode.A ? new JsonObject() : null;
		JsonObject sphere = mode == EtwMode.A ? new JsonObject() : null;
		List<String> unavailable = mode == EtwMode.A ? List.of() : List.of("targeting", "sphere");
		return new EtwSample(mode, click, targeting, sphere, numbers, unavailable);
	}

	private static PacketObservation packet(PacketKind kind, long tick, long nano, double x, double y, double z,
			double yaw, double pitch) {
		JsonObject raw = new JsonObject();
		raw.addProperty("yaw", yaw);
		raw.addProperty("pitch", pitch);
		PlayerState before = new PlayerState(xyz(0, 0, 0), xyz(0, 1.62, 0), 0, 0, null);
		PlayerState after = new PlayerState(xyz(x, y, z), xyz(x, y + 1.62, z), yaw, pitch, null);
		return new PacketObservation(kind, tick, nano, 5_000L + tick, raw, before, after);
	}

	private static void checkSphere() {
		List<int[]> offsets = EtwMath.sphereOffsets(3);
		check(offsets.size() == 123, "the radius-3 block sphere has 123 positions, not the 343 of a cube");
		Set<String> seen = new HashSet<>();
		boolean hasCentre = false;
		for (int[] offset : offsets) {
			check(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2] <= 9,
				"every collected offset satisfies dx^2 + dy^2 + dz^2 <= 9");
			check(seen.add(offset[0] + "," + offset[1] + "," + offset[2]), "no offset is collected twice");
			if (offset[0] == 0 && offset[1] == 0 && offset[2] == 0) hasCentre = true;
		}
		check(hasCentre, "the targeted block itself is included");
		check(seen.contains("3,0,0") && seen.contains("0,-3,0") && seen.contains("2,2,1") && seen.contains("-2,-1,-2"),
			"blocks exactly on the radius are included");
		check(!seen.contains("3,1,0") && !seen.contains("2,2,2") && !seen.contains("3,3,3") && !seen.contains("-3,-1,0"),
			"cube corners and blocks just outside the radius are not collected");
		for (int[] offset : offsets) {
			check(seen.contains((-offset[0]) + "," + (-offset[1]) + "," + (-offset[2])), "the sphere is symmetric");
		}
		check(EtwMath.sphereOffsets(1).size() == 7 && EtwMath.sphereOffsets(0).size() == 1, "other radii follow the same rule");
	}

	private static void checkMath() {
		Xyz relative = EtwMath.relativeHit(xyz(10.25, 64.5, -3.75), new IntXyz(10, 64, -4));
		check(relative.getX() == 0.25 && relative.getY() == 0.5 && relative.getZ() == 0.25,
			"the hit position relative to its block is hit minus block corner, unrounded");
		check(EtwMath.wrapDegrees(10.0 - 350.0) == 20.0, "a turn across the 360 seam is the short way round");
		check(EtwMath.wrapDegrees(-190.0) == 170.0 && EtwMath.wrapDegrees(180.0) == -180.0 && EtwMath.wrapDegrees(0.0) == 0.0,
			"angles wrap into -180 to 180");
		JsonObject diff = EtwMath.positionDiff(xyz(1, 2, 3), xyz(4, 6, 3));
		check(diff.get("dx").getAsDouble() == 3.0 && diff.get("dy").getAsDouble() == 4.0 && diff.get("dz").getAsDouble() == 0.0
			&& diff.get("horizontal").getAsDouble() == 3.0 && diff.get("total").getAsDouble() == 5.0,
			"the position difference carries each axis, the horizontal and the total distance");
	}

	private static void checkLifecycle() {
		EtwRecorder recorder = recorder();
		check(!recorder.isActive() && recorder.end() == null, "the recorder is inactive and ends nothing outside a session");
		check(recorder.click(sample(EtwMode.B, 1, 1, 0, 0)) == null, "a click outside a session is not recorded");
		String id = recorder.start(new JsonObject());
		check(id != null && id.startsWith("etw-") && id.endsWith("-a3f9") && recorder.isActive(), "a session starts with a unique id");
		check(recorder.start(new JsonObject()) == null, "a second /etw start while recording is refused");
		String first = recorder.click(sample(EtwMode.B, 1, 1, 0, 0));
		String second = recorder.click(sample(EtwMode.A, 2, 2, 0, 0));
		check("S0001".equals(first) && "S0002".equals(second), "every interaction gets its own increasing sample id");
		JsonObject document = recorder.end();
		check(document != null && !recorder.isActive(), "/etw end finishes the session");
		check(document.get("sampleCount").getAsInt() == 2 && document.getAsJsonArray("samples").size() == 2,
			"both interactions are in the saved session");
		check(document.get("endedAtEpochMs").getAsLong() > document.get("startedAtEpochMs").getAsLong(),
			"the session records when it began and ended");
		String next = recorder.start(new JsonObject());
		check(next != null && recorder.click(sample(EtwMode.B, 3, 3, 0, 0)).equals("S0001"),
			"a new session numbers its samples from the start and never reuses an old file's data");
		recorder.end();
	}

	private static void checkModeSelection() {
		check(EtwRecorder.modeFor(true) == EtwMode.A && EtwRecorder.modeFor(false) == EtwMode.B,
			"sneaking selects Mode A and a normal click selects Mode B");
	}

	private static void checkFifoMatching() {
		EtwRecorder recorder = recorder();
		recorder.start(new JsonObject());
		recorder.click(sample(EtwMode.A, 10, 10_000_000L, 90.0, 10.0));
		recorder.click(sample(EtwMode.B, 11, 20_000_000L, 91.0, 11.0));
		check(recorder.openCount() == 2, "two clicks wait for their server responses");

		EtwRecorder.PacketResult first = recorder.packet(packet(PacketKind.POSITION, 12, 60_000_000L, 120, 70, -21, 90.0, 10.0));
		check("S0001".equals(first.getSampleId()) && first.getAmbiguous(),
			"the first position packet completes the oldest open sample and is flagged ambiguous while two were open");
		EtwRecorder.PacketResult second = recorder.packet(packet(PacketKind.ROTATION, 13, 70_000_000L, 100.5, 64, -20.25, 12.0, 34.0));
		check("S0002".equals(second.getSampleId()) && !second.getAmbiguous(),
			"a rotation packet skips the Mode A sample (which waits for a position packet) and completes the Mode B one");
		check(recorder.openCount() == 0, "no sample is left open");

		JsonObject document = recorder.end();
		JsonObject a = document.getAsJsonArray("samples").get(0).getAsJsonObject();
		JsonObject b = document.getAsJsonArray("samples").get(1).getAsJsonObject();
		check("completed".equals(a.get("status").getAsString()) && a.get("ambiguous").getAsBoolean()
			&& "ClientboundPlayerPositionPacket".equals(a.getAsJsonObject("serverUpdate").get("packetType").getAsString()),
			"sample 1 is completed by the position packet and keeps the ambiguity flag");
		check(a.getAsJsonObject("serverUpdate").get("ticksSinceClick").getAsLong() == 2
			&& Math.abs(a.getAsJsonObject("serverUpdate").get("millisSinceClick").getAsDouble() - 50.0) < 1.0e-9,
			"timing since the click is recorded in ticks and milliseconds");
		JsonObject diffA = a.getAsJsonObject("diff");
		check(diffA.getAsJsonObject("position").get("dx").getAsDouble() == 19.5, "the position difference is relative to the click");
		check(diffA.getAsJsonObject("resultRelativeToTargetBlock").get("dx").getAsDouble() == 10.0
			&& diffA.getAsJsonObject("resultRelativeToHit").get("dx").getAsDouble() == 9.75,
			"Mode A relates the result to the targeted block and to the exact hit position");
		check("completed".equals(b.get("status").getAsString()) && !b.get("ambiguous").getAsBoolean()
			&& diffA.get("yaw").getAsDouble() == 0.0 && b.getAsJsonObject("diff").get("yaw").getAsDouble() == 12.0 - 91.0,
			"sample 2 is completed by the rotation packet with its own angle difference");
	}

	private static void checkOverlappingBModeClicks() {
		EtwRecorder recorder = recorder();
		recorder.start(new JsonObject());
		recorder.click(sample(EtwMode.B, 1, 1_000_000L, 10, 0));
		recorder.click(sample(EtwMode.B, 2, 2_000_000L, 20, 0));
		recorder.packet(packet(PacketKind.ROTATION, 3, 3_000_000L, 0, 0, 0, 180, 0));
		check(recorder.openCount() == 1, "one packet completes only one of two overlapping clicks");
		JsonObject document = recorder.end();
		JsonObject first = document.getAsJsonArray("samples").get(0).getAsJsonObject();
		JsonObject second = document.getAsJsonArray("samples").get(1).getAsJsonObject();
		check("completed".equals(first.get("status").getAsString()) && "no_server_update".equals(second.get("status").getAsString()),
			"a click that never gets a response is saved as no_server_update at /etw end, with no invented timeout");
		check(second.get("serverUpdate").isJsonNull(), "an unanswered sample has an explicit null serverUpdate");
	}

	private static void checkImmutability() {
		EtwRecorder recorder = recorder();
		recorder.start(new JsonObject());
		JsonObject click = new JsonObject();
		click.addProperty("yaw", 33.0);
		click.addProperty("pitch", 4.0);
		ClickNumbers numbers = new ClickNumbers(xyz(0, 0, 0), xyz(0, 1.62, 0), 33.0, 4.0, null, null, 1, 1);
		EtwSample sample = new EtwSample(EtwMode.B, click, null, null, numbers, List.of("targeting", "sphere"));
		recorder.click(sample);
		click.addProperty("yaw", 999.0);
		recorder.packet(packet(PacketKind.ROTATION, 2, 2_000_000L, 0, 0, 0, 200.0, -30.0));
		JsonObject saved = recorder.end().getAsJsonArray("samples").get(0).getAsJsonObject();
		check(saved.getAsJsonObject("click").get("yaw").getAsDouble() == 33.0
			&& saved.getAsJsonObject("click").get("pitch").getAsDouble() == 4.0,
			"the click-time yaw and pitch stay exactly as captured, whatever happens to the live view afterwards");
		check(saved.getAsJsonObject("serverUpdate").getAsJsonObject("after").get("yaw").getAsDouble() == 200.0,
			"the server's resulting rotation is a separate field");
		check(saved.getAsJsonObject("diff").get("yaw").getAsDouble() == 167.0
			&& saved.getAsJsonObject("diff").get("pitch").getAsDouble() == -34.0
			&& saved.getAsJsonObject("diff").get("yawWrapped180").getAsDouble() == 167.0,
			"differences are measured from the immutable click-time angles");
	}

	private static void checkObservedPacketsAndUnattributed() {
		EtwRecorder recorder = recorder();
		recorder.start(new JsonObject());
		recorder.packet(packet(PacketKind.POSITION, 1, 1_000_000L, 1, 1, 1, 0, 0));
		recorder.click(sample(EtwMode.A, 2, 2_000_000L, 0, 0));
		recorder.packet(packet(PacketKind.POSITION, 3, 3_000_000L, 5, 5, 5, 0, 0));
		recorder.packet(packet(PacketKind.POSITION, 4, 4_000_000L, 6, 6, 6, 0, 0));
		recorder.click(sample(EtwMode.A, 5, 5_000_000L, 0, 0));
		recorder.packet(packet(PacketKind.POSITION, 6, 6_000_000L, 7, 7, 7, 0, 0));
		JsonObject document = recorder.end();
		check(document.getAsJsonArray("unattributedPackets").size() == 2,
			"a packet with no open sample is kept in the session, not dropped or guessed onto a sample");
		JsonObject s1 = document.getAsJsonArray("samples").get(0).getAsJsonObject();
		JsonObject s2 = document.getAsJsonArray("samples").get(1).getAsJsonObject();
		check(s1.getAsJsonArray("observedPacketsSinceClick").size() == 2,
			"a sample lists every packet seen from its click until the next click, even after it was matched");
		check(s2.getAsJsonArray("observedPacketsSinceClick").size() == 1
			&& "completed".equals(s2.get("status").getAsString()), "the next sample only lists packets after its own click");
	}

	private static void checkJson() {
		EtwRecorder recorder = recorder();
		JsonObject context = new JsonObject();
		context.addProperty("minecraftVersion", "26.2");
		recorder.start(context);
		double awkward = 0.1 + 0.2;
		float yawFloat = 123.45679f;
		recorder.click(sample(EtwMode.B, 1, 1, yawFloat, 0.1f));
		recorder.packet(packet(PacketKind.POSITION, 2, 2_000_000L, awkward, 64.0, -0.0000001, 1.0, 1.0));
		JsonObject document = recorder.end();
		String text = new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document);
		JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
		check(parsed.equals(document), "the session is valid JSON that reads back identically");
		check("26.2".equals(parsed.get("minecraftVersion").getAsString()), "world context supplied at start is kept");
		for (String key : new String[] {"schemaVersion", "sessionId", "startedAtEpochMs", "endedAtEpochMs", "samples",
			"sampleCount", "unattributedPackets"}) {
			check(parsed.has(key), "the session has " + key);
		}
		JsonObject sample = parsed.getAsJsonArray("samples").get(0).getAsJsonObject();
		for (String key : new String[] {"sampleId", "mode", "status", "ambiguous", "click", "targeting", "sphere",
			"serverUpdate", "diff", "observedPacketsSinceClick", "unavailableFields"}) {
			check(sample.has(key), "every sample has " + key + " (explicit null when unavailable)");
		}
		check(sample.get("targeting") instanceof JsonNull && sample.get("sphere") instanceof JsonNull
			&& sample.getAsJsonArray("unavailableFields").size() == 2, "Mode B marks targeting and sphere unavailable, not missing");
		JsonObject position = sample.getAsJsonObject("serverUpdate").getAsJsonObject("after").getAsJsonObject("position");
		check(position.get("x").getAsDouble() == awkward, "doubles keep their full precision (0.30000000000000004)");
		check(text.contains("0.30000000000000004") && text.contains("-1.0E-7"), "no rounding is applied when writing");
		check(sample.getAsJsonObject("click").get("yaw").getAsDouble() == (double) yawFloat,
			"a float angle is stored as the exact double of that float");
		JsonElement mode = sample.get("mode");
		check("B".equals(mode.getAsString()), "the mode is recorded as A or B");
	}

	private static void checkStore() throws Exception {
		Path directory = Files.createTempDirectory("etw-checks");
		try {
			Path first = EtwStore.uniqueFile(directory, "etw-1");
			check(first.getFileName().toString().equals("etw-1.json"), "a new session writes to its own file name");
			JsonObject document = new JsonObject();
			document.addProperty("sessionId", "etw-1");
			EtwStore.writeAtomically(first, document);
			Path second = EtwStore.uniqueFile(directory, "etw-1");
			check(!second.equals(first) && !Files.exists(second), "an existing session file is never overwritten");
			EtwStore.writeAtomically(second, document);
			Path third = EtwStore.uniqueFile(directory, "etw-1");
			check(!third.equals(first) && !third.equals(second), "every later file gets a fresh name");
			try (var listing = Files.list(directory)) {
				check(listing.count() == 2, "no temporary file is left behind");
			}
			JsonArray array = new JsonArray();
			array.add("x");
			check(JsonParser.parseString(Files.readString(first)).getAsJsonObject().get("sessionId").getAsString().equals("etw-1"),
				"the written file is readable JSON");
		} finally {
			try (var listing = Files.list(directory)) {
				listing.forEach(path -> path.toFile().delete());
			}
			Files.deleteIfExists(directory);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
