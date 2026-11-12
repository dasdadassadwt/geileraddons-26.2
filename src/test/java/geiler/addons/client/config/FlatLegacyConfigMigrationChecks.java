package geiler.addons.client.config;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Isolated regression check for the original flat config path migration. */
public final class FlatLegacyConfigMigrationChecks {
	private FlatLegacyConfigMigrationChecks() { }

	public static void run() throws Exception {
		Path configDirectory = FabricLoader.getInstance().getConfigDir();
		Path legacy = configDirectory.resolve("geileraddons.json");
		Path current = configDirectory.resolve("geileraddons/config.json");
		if (Files.exists(legacy) || Files.exists(current)) {
			throw new AssertionError("Flat legacy config migration check requires an isolated config directory.");
		}
		boolean createdDataDirectory = !Files.exists(current.getParent());
		Files.createDirectories(configDirectory);
		byte[] original = ("{\"enabled\":{},\"toggles\":{},\"choices\":{},\"texts\":{},"
			+ "\"numbers\":{},\"colors\":{},\"keybinds\":{}}")
			.getBytes(StandardCharsets.UTF_8);
		try {
			Files.write(legacy, original);

			Method readData = ModConfigCore.class.getDeclaredMethod("readData");
			readData.setAccessible(true);
			readData.invoke(ModConfigCore.INSTANCE);

			if (!Files.isRegularFile(current) || Files.exists(legacy)
				|| !Arrays.equals(original, Files.readAllBytes(current))) {
				throw new AssertionError("The flat config must move byte-for-byte to geileraddons/config.json.");
			}
		} finally {
			Files.deleteIfExists(current);
			Files.deleteIfExists(legacy);
			if (createdDataDirectory) Files.deleteIfExists(current.getParent());
		}
	}
}
