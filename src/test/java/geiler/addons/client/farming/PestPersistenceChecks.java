package geiler.addons.client.farming;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Isolated file round-trip checks for the pest and Slayer lifetime totals. */
public final class PestPersistenceChecks {
	private static final long WRITE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

	private PestPersistenceChecks() { }

	public static void run() {
		Path configDir = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
		Path tempDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		if (!configDir.startsWith(tempDir)) {
			throw new AssertionError("Pest persistence checks require the isolated temporary config directory.");
		}

		Path root = configDir.resolve("geileraddons");
		Path pestFile = root.resolve("pest-hourly-stats.json");
		Path slayerFile = root.resolve("slayer-hourly-stats.json");
		if (Files.exists(pestFile) || Files.exists(slayerFile)) {
			throw new AssertionError("Pest persistence checks refuse to overwrite an existing fixture.");
		}

		try {
			PersistentHourlyStatsStore.savePests(7, 1234L);
			JsonObject pestJson = awaitJson(pestFile);
			check(pestJson.size() == 2 && pestJson.has("kills") && pestJson.has("activeNanos")
				&& pestJson.get("kills").getAsInt() == 7 && pestJson.get("activeNanos").getAsLong() == 1234L,
				"pest totals keep the legacy JSON fields and values");
			PersistentHourlyStatsStore.Snapshot pestSnapshot = PersistentHourlyStatsStore.loadPests();
			check(pestSnapshot.kills == 7 && pestSnapshot.activeNanos == 1234L,
				"pest totals load after an asynchronous save");

			PersistentHourlyStatsStore.saveSlayer(-4, -20L);
			JsonObject slayerJson = awaitJson(slayerFile);
			check(slayerJson.size() == 2 && slayerJson.has("kills") && slayerJson.has("activeNanos")
				&& slayerJson.get("kills").getAsInt() == 0 && slayerJson.get("activeNanos").getAsLong() == 0L,
				"Slayer save sanitizes negative values and keeps the same schema");
			PersistentHourlyStatsStore.Snapshot slayerSnapshot = PersistentHourlyStatsStore.loadSlayer();
			check(slayerSnapshot.kills == 0 && slayerSnapshot.activeNanos == 0L,
				"Slayer totals load from their independent file");

			Files.writeString(slayerFile, "{\"kills\":-9,\"activeNanos\":-13}");
			PersistentHourlyStatsStore.Snapshot sanitized = PersistentHourlyStatsStore.loadSlayer();
			check(sanitized.kills == 0 && sanitized.activeNanos == 0L,
				"legacy negative stored values remain sanitized on load");
		} catch (IOException error) {
			throw new AssertionError("Could not verify isolated pest persistence", error);
		} finally {
			try {
				Files.deleteIfExists(pestFile);
				Files.deleteIfExists(slayerFile);
				Files.deleteIfExists(root);
			} catch (IOException error) {
				throw new AssertionError("Could not clean isolated pest persistence fixture", error);
			}
		}
	}

	private static JsonObject awaitJson(Path file) {
		long deadline = System.nanoTime() + WRITE_TIMEOUT_NANOS;
		IOException lastFailure = null;
		while (System.nanoTime() < deadline) {
			if (Files.isRegularFile(file)) {
				try {
					return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				} catch (IOException error) {
					lastFailure = error;
				}
			}
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted waiting for the asynchronous stats save", interrupted);
			}
		}
		throw new AssertionError("Stats file was not written within five seconds: " + file, lastFailure);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
