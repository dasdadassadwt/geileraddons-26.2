package geiler.addons.client.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/** Focused offline checks for bounded rollback snapshots. */
public final class ConfigProfileInstallerBoundsChecks {
	private ConfigProfileInstallerBoundsChecks() { }

	public static void main(String[] args) throws Exception {
		checkJavaInterop();
		checkAggregateSnapshotLimit();
		checkGrowthAfterSizeCheck();
		System.out.println("Config profile installer bounds checks passed.");
	}

	private static void checkJavaInterop() {
		assertTrue(ConfigProfileInstaller.Result.class.isRecord(), "the install result retains Java record metadata");
		java.lang.reflect.RecordComponent[] components = ConfigProfileInstaller.Result.class.getRecordComponents();
		assertTrue(components.length == 2
			&& "installed".equals(components[0].getName())
			&& "recoveryRestored".equals(components[1].getName()),
			"the install result retains its Java record components and order");
		try {
			Method install = ConfigProfileInstaller.class.getMethod("install", Path.class, Map.class, Map.class,
				java.util.function.Predicate.class, ConfigProfileInstaller.PayloadValidator.class,
				ConfigProfileInstaller.FailureInjector.class);
			Method read = ConfigProfileInstaller.class.getMethod("readOwnedFileBytes", Path.class, long.class, int.class);
			assertTrue(Modifier.isStatic(install.getModifiers())
				&& install.getReturnType() == ConfigProfileInstaller.Result.class,
				"Java callers retain the static transactional install entrypoint");
			assertTrue(Modifier.isStatic(read.getModifiers()) && read.getReturnType() == byte[].class,
				"Java checks retain the bounded snapshot reader entrypoint");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Config profile installer Java API changed", exception);
		}
	}

	private static void checkAggregateSnapshotLimit() throws IOException {
		Path root = Files.createTempDirectory("ga-profile-aggregate-check-");
		try {
			Path config = root.resolve("config.json");
			Files.write(config, bytes("old"));
			Path macros = Files.createDirectories(root.resolve("macros"));
			for (int index = 0; index < 8; index++) {
				Path macro = macros.resolve("Plan-" + index + "-1.json");
				writeSparseFile(macro, ConfigProfileCodec.MAX_ENTRY_BYTES);
			}

			ConfigProfileInstaller.Result result = ConfigProfileInstaller.install(root,
				Map.of("config.json", bytes("new")), null, ConfigProfileCodec::safePath, files -> { }, null);
			assertTrue(!result.installed(), "an active rollback snapshot above the aggregate profile limit is rejected");
			assertFile(config, "old", "aggregate-limit rejection leaves the active config untouched");
		} finally {
			deleteTree(root);
		}
	}

	private static void checkGrowthAfterSizeCheck() throws IOException {
		Path root = Files.createTempDirectory("ga-profile-growth-check-");
		try {
			Path member = root.resolve("config.json");
			Files.write(member, bytes("old"));
			long sizeBeforeRead = Files.size(member);
			Files.write(member, bytes("grown"), StandardOpenOption.TRUNCATE_EXISTING);
			assertReadRejected(member, sizeBeforeRead, 8,
				"growth after the size check is detected even while still within the read budget");

			assertReadRejected(member, sizeBeforeRead, 4,
				"growth beyond the remaining read budget is rejected after a bounded read");
		} finally {
			deleteTree(root);
		}
	}

	private static void writeSparseFile(Path file, long size) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			channel.position(size - 1);
			channel.write(ByteBuffer.wrap(new byte[] { 0 }));
		}
	}

	private static void assertReadRejected(Path file, long expectedSize, int maximumBytes, String message) throws IOException {
		try {
			ConfigProfileInstaller.readOwnedFileBytes(file, expectedSize, maximumBytes);
			throw new AssertionError(message);
		} catch (IOException expected) { }
	}

	private static void assertFile(Path path, String expected, String message) throws IOException {
		if (!new String(Files.readAllBytes(path), StandardCharsets.UTF_8).equals(expected)) throw new AssertionError(message);
	}

	private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	private static void deleteTree(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertTrue(boolean result, String message) {
		if (!result) throw new AssertionError(message);
	}
}
