package geiler.addons.client.dungeon;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Focused checks for multi-file Dungeon Guide crash recovery. */
public final class DungeonGuideStoreRecoveryChecks {
	private DungeonGuideStoreRecoveryChecks() { }

	public static void main(String[] args) throws Exception {
		run();
		System.out.println("DungeonGuideStoreRecoveryChecks passed");
	}

	public static void run() throws Exception {
		checkInterruptionAfterEachReplacement();
		checkSuccessfulCommit();
		checkCaughtFailureRollback();
		checkInvalidJournalFailsClosed();
		checkCorruptedBackupFailsClosed();
	}

	private static void checkInterruptionAfterEachReplacement() throws Exception {
		for (int interruptAfter = 1; interruptAfter <= 4; interruptAfter++) {
			int stopAfter = interruptAfter;
			Path root = createRoot("ga-guide-crash-");
			try {
				Path first = root.resolve("F1/ENTRY/route-a.json");
				Path second = root.resolve("F1/CLEAR/route-b.json");
				Path newRoute = root.resolve("F1/BLOOD/route-c.json");
				Path index = root.resolve("index.json");
				writeFile(first, "old-route-a");
				writeFile(second, "old-route-b");
				writeFile(index, "old-index");
				List<ClientJsonFile.Write> writes = List.of(
					entry(first, "new-route-a"), entry(second, "new-route-b"),
					entry(newRoute, "new-route-c"), entry(index, "new-index"));
				boolean interrupted = false;
				DungeonGuideStore.SaveResult saveResult = null;
				try {
					saveResult = DungeonGuideStore.writeTransactionalBatch(writes, root, replacement -> {
						if (replacement == stopAfter) throw new SimulatedProcessInterruption();
					});
				} catch (SimulatedProcessInterruption expected) { interrupted = true; }
				if (!interrupted) check(saveResult != null && saveResult.success(),
					"save reaches the replacement hook before failure: " + (saveResult == null ? "no result" : saveResult.detail()));
				check(interrupted, "simulated process interruption fires after replacement " + interruptAfter);
				check(Files.exists(journal(root)), "journal remains after simulated process interruption");

				DungeonGuideStore.RecoveryResult recovered = DungeonGuideStore.recoverPendingTransaction(root);
				check(recovered.success(), "recovery succeeds after replacement " + interruptAfter + ": " + recovered.detail());
				equals("old-route-a", read(first), "first route returns to old generation");
				equals("old-route-b", read(second), "second route returns to old generation");
				check(!Files.exists(newRoute), "new route from interrupted generation is removed");
				equals("old-index", read(index), "index returns to old generation");
				check(!Files.exists(journal(root)), "recovery removes the journal only after restoration");
			} finally { deleteTree(root); }
		}
	}

	private static void checkSuccessfulCommit() throws Exception {
		Path root = createRoot("ga-guide-commit-");
		try {
			Path route = root.resolve("F2/ENTRY/route.json");
			Path index = root.resolve("index.json");
			writeFile(route, "old-route");
			writeFile(index, "old-index");
			DungeonGuideStore.SaveResult result = DungeonGuideStore.writeTransactionalBatch(List.of(
				entry(route, "new-route"), entry(index, "new-index")), root, ignored -> { });
			check(result.success(), "complete transaction succeeds");
			equals("new-route", read(route), "successful transaction commits route data");
			equals("new-index", read(index), "successful transaction commits index last");
			check(!Files.exists(journal(root)), "successful transaction removes its journal");
			check(DungeonGuideStore.recoverPendingTransaction(root).success(), "restart recovery accepts committed generation");
			equals("new-index", read(index), "committed generation is not rolled back after journal removal");
		} finally { deleteTree(root); }
	}

	private static void checkCaughtFailureRollback() throws Exception {
		Path root = createRoot("ga-guide-rollback-");
		try {
			Path route = root.resolve("F3/ENTRY/route.json");
			Path index = root.resolve("index.json");
			writeFile(route, "old-route");
			writeFile(index, "old-index");
			DungeonGuideStore.SaveResult result = DungeonGuideStore.writeTransactionalBatch(List.of(
				entry(route, "new-route"), entry(index, "new-index")), root, replacement -> {
					if (replacement == 1) throw new IllegalStateException("injected caught failure");
				});
			check(!result.success() && result.priorStateRestored(), "caught writer error reports a successful rollback");
			equals("old-route", read(route), "caught failure restores the route file");
			equals("old-index", read(index), "caught failure retains the prior index");
			check(!Files.exists(journal(root)), "successful caught-failure rollback clears the journal");
		} finally { deleteTree(root); }
	}

	private static void checkInvalidJournalFailsClosed() throws Exception {
		Path root = createRoot("ga-guide-invalid-journal-");
		try {
			Path index = root.resolve("index.json");
			writeFile(index, "keep-this-file");
			Files.writeString(journal(root), "{\"version\":1,\"backupDirectory\":\"..\\\\outside\",\"files\":[{\"target\":\"../outside\",\"existed\":false}]}", StandardCharsets.UTF_8);
			DungeonGuideStore.RecoveryResult result = DungeonGuideStore.recoverPendingTransaction(root);
			check(!result.success(), "invalid path manifest is rejected");
			equals("keep-this-file", read(index), "invalid journal leaves target files untouched");
			check(Files.exists(journal(root)), "invalid journal remains for manual recovery");
		} finally { deleteTree(root); }
	}

	private static void checkCorruptedBackupFailsClosed() throws Exception {
		Path root = createRoot("ga-guide-corrupt-backup-");
		try {
			Path route = root.resolve("F4/ENTRY/route.json");
			Path index = root.resolve("index.json");
			writeFile(route, "old-route");
			writeFile(index, "old-index");
			try {
				DungeonGuideStore.writeTransactionalBatch(List.of(entry(route, "new-route"), entry(index, "new-index")), root,
					replacement -> { if (replacement == 1) throw new SimulatedProcessInterruption(); });
			} catch (SimulatedProcessInterruption expected) { }
			Path transactionRoot = root.resolve(".transactions");
			Path transactionDirectory;
			try (var directories = Files.list(transactionRoot)) { transactionDirectory = directories.findFirst().orElseThrow(); }
			Files.writeString(transactionDirectory.resolve("backup-00000.bin"), "tampered", StandardCharsets.UTF_8);
			DungeonGuideStore.RecoveryResult result = DungeonGuideStore.recoverPendingTransaction(root);
			check(!result.success(), "checksum mismatch rejects the recovery plan before mutation");
			equals("new-route", read(route), "checksum failure preserves the current route bytes for manual recovery");
			equals("old-index", read(index), "checksum failure preserves other files untouched");
			check(Files.exists(journal(root)), "checksum failure keeps the journal for manual recovery");
		} finally { deleteTree(root); }
	}

	private static ClientJsonFile.Write entry(Path target, String value) {
		return new ClientJsonFile.Write(target, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void writeFile(Path target, String value) throws IOException {
		Files.createDirectories(target.getParent());
		Files.writeString(target, value, StandardCharsets.UTF_8);
	}

	private static String read(Path target) throws IOException { return Files.readString(target, StandardCharsets.UTF_8); }
	private static Path journal(Path root) { return root.resolve(".pending-transaction.json"); }
	private static Path createRoot(String prefix) throws IOException {
		Path temporaryRoot = Path.of("build", "dungeon-guide-store-recovery-check-jdk25-20261010", "workspace-temp")
			.toAbsolutePath().normalize();
		Files.createDirectories(temporaryRoot);
		return Files.createTempDirectory(temporaryRoot, prefix);
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
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
	private static void equals(String expected, String actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " (expected " + expected + ", got " + actual + ")");
	}
	private static final class SimulatedProcessInterruption extends Error { }
}
