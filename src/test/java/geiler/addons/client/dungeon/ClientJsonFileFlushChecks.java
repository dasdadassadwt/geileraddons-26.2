package geiler.addons.client.dungeon;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/** Focused checks for failure reporting across serialized client JSON writes. */
public final class ClientJsonFileFlushChecks {
	private ClientJsonFileFlushChecks() { }

	public static void main(String[] args) {
		run();
		System.out.println("ClientJsonFileFlushChecks passed");
	}

	public static void run() {
		Path root = null;
		try {
			check(ClientJsonFile.flushAndReport(), "an empty flush succeeds");

			root = Files.createTempDirectory("ga-client-json-flush-check-");
			checkReadContract(root);
			Path blocker = root.resolve("parent-is-a-file");
			Files.writeString(blocker, "blocker");
			Future<Boolean> failed = ClientJsonFile.writeAsync(blocker.resolve("failed.json"), "{\"failed\":true}");
			Path laterPath = root.resolve("later-success.json");
			Future<Boolean> laterSuccess = ClientJsonFile.writeAsync(laterPath, "{\"later\":true}");
			check(!failed.get(), "the earlier write reports its own failure");
			check(laterSuccess.get(), "the later write succeeds");
			check(!ClientJsonFile.flushAndReport(), "flush reports an earlier failed write despite a later success");
			check(ClientJsonFile.flushAndReport(), "a repeated empty flush succeeds after the failure was reported");

			Path successPath = root.resolve("success.json");
			Future<Boolean> success = ClientJsonFile.writeAsync(successPath, "{\"success\":true}");
			check(success.get(), "a standalone write succeeds");
			check(ClientJsonFile.flushAndReport(), "flush reports success when all covered writes succeed");
			check(Files.readString(successPath).equals("{\"success\":true}"), "the successful write reaches disk");
			check(ClientJsonFile.flushAndReport(), "successive empty flushes stay successful");

			Path batchData = root.resolve("batch-data.json");
			Path batchIndex = root.resolve("batch-index.json");
			byte[] originalData = "{\"batch\":true}".getBytes(StandardCharsets.UTF_8);
			byte[] originalIndex = "{\"entries\":1}".getBytes(StandardCharsets.UTF_8);
			Future<Boolean> batch = ClientJsonFile.writeBatchAsync(List.of(
				new ClientJsonFile.Write(batchData, originalData),
				new ClientJsonFile.Write(batchIndex, originalIndex)));
			originalData[2] = (byte) 'X';
			check(batch.get(), "a valid ordered write batch succeeds");
			check(Files.readString(batchData).equals("{\"batch\":true}"),
				"batch writes snapshot caller-owned byte arrays before queueing");
			check(Files.readString(batchIndex).equals("{\"entries\":1}"),
				"the final batch entry is written after the preceding data entry");
			check(ClientJsonFile.flushAndReport(), "a successful batch is reported by flush");

			check(!ClientJsonFile.writeBatchAsync(null).get(), "a missing write batch is rejected");
			check(!ClientJsonFile.flushAndReport(), "flush reports a rejected write batch");
			check(!ClientJsonFile.writeBatchAsync(List.of()).get(), "an empty write batch is rejected");
			check(!ClientJsonFile.flushAndReport(), "flush reports an empty write batch");
		} catch (Exception error) {
			throw new AssertionError("ClientJsonFile flush check failed", error);
		} finally {
			if (root != null) try { deleteTree(root); }
			catch (IOException error) { throw new AssertionError("Could not remove the focused check's temporary files", error); }
		}
	}

	private static void checkReadContract(Path root) throws IOException {
		ClientJsonFile.ReadResult<?> missing = ClientJsonFile.readResult(root.resolve("missing.json"), Object.class);
		check(!missing.exists() && missing.valid() && missing.value() == null,
			"a missing file is reported as absent without an error");
		check(ClientJsonFile.read(null, Object.class) == null, "a missing path returns no value");
		check(!ClientJsonFile.readResult(null, Object.class).valid(), "a missing path is reported as invalid");

		Path directory = root.resolve("directory");
		Files.createDirectory(directory);
		check("path is not a regular file".equals(ClientJsonFile.readResult(directory, Object.class).error()),
			"directories are rejected as JSON inputs");

		Path empty = root.resolve("empty.json");
		Files.createFile(empty);
		check(!ClientJsonFile.readResult(empty, Object.class).valid(), "an empty file is rejected");

		Path validPath = root.resolve("valid.json");
		Files.writeString(validPath, "{\"value\":true}");
		ClientJsonFile.ReadResult<?> valid = ClientJsonFile.readResult(validPath, Map.class);
		check(valid.valid() && valid.exists() && Boolean.TRUE.equals(((Map<?, ?>) valid.value()).get("value")),
			"a bounded JSON object is parsed");

		Path invalidPath = root.resolve("invalid.json");
		Files.writeString(invalidPath, "{");
		check(!ClientJsonFile.readResult(invalidPath, Object.class).valid(), "malformed JSON returns a read error");

		Path oversized = root.resolve("oversized.json");
		Files.write(oversized, new byte[ClientJsonFile.MAX_FILE_BYTES + 1]);
		check("file size is outside the allowed range".equals(ClientJsonFile.readResult(oversized, Object.class).error()),
			"files over the configured byte limit are rejected");
	}

	private static void deleteTree(Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
				if (error != null) throw error;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
