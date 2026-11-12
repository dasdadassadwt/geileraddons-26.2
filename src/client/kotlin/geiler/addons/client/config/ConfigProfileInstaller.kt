package geiler.addons.client.config

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Map as JavaMap
import java.util.function.Predicate

/** Transactional staged file replacement with an injectable seam for isolated failure checks. */
object ConfigProfileInstaller {
    fun interface FailureInjector {
        @Throws(IOException::class)
        fun beforeMove(restoring: Boolean, index: Int, target: Path)
    }

    fun interface PayloadValidator {
        @Throws(IOException::class)
        fun validate(files: Map<String, ByteArray?>)
    }

    @JvmRecord
    data class Result(val installed: Boolean, val recoveryRestored: Boolean)

    /** Whether a transaction that reached installation restored the authoritative on-disk snapshot. */
    @JvmStatic
    fun shouldReloadRestoredFiles(installationAttempted: Boolean, recoveryRestored: Boolean): Boolean =
        installationAttempted && recoveryRestored

    @JvmStatic
    fun install(
        root: Path,
        incoming: Map<String, ByteArray?>?,
        backup: Map<String, ByteArray?>?,
        safePath: Predicate<String>?,
        validator: PayloadValidator?,
        injector: FailureInjector?,
    ): Result {
        try {
            replace(root, incoming, safePath, validator, injector, restoring = false)
            return Result(true, true)
        } catch (failedInstall: IOException) {
            if (backup == null || backup.isEmpty()) return Result(false, false)
            return try {
                replace(root, backup, safePath, validator, injector, restoring = true)
                Result(false, true)
            } catch (failedRestore: IOException) {
                Result(false, false)
            }
        }
    }

    @Throws(IOException::class)
    private fun replace(
        root: Path,
        files: Map<String, ByteArray?>?,
        safePath: Predicate<String>?,
        validator: PayloadValidator?,
        injector: FailureInjector?,
        restoring: Boolean,
    ) {
        val dataRoot = root.toAbsolutePath().normalize()
        ConfigPathGuard.rejectReparseChain(dataRoot)
        if (files == null || files.isEmpty()) throw IOException("Replacement profile is empty.")
        val foldedPaths = HashSet<String>()
        for ((path, bytes) in files) {
            // A decoded profile omits local-only members; reject any direct caller that tries to install one.
            if (!ConfigProfileCodec.isTransferablePath(path) || safePath == null || !safePath.test(path) ||
                !foldedPaths.add(path.lowercase(Locale.ROOT)) || bytes == null ||
                bytes.size > ConfigProfileCodec.MAX_ENTRY_BYTES
            ) {
                throw IOException("Replacement contains an unsafe or oversized file.")
            }
        }
        validator!!.validate(JavaMap.copyOf(files))
        val oldFiles = collectOwnedFiles(dataRoot, safePath)
        Files.createDirectories(dataRoot)
        val dataGuard = ConfigPathGuard.checkedDirectory(dataRoot)
        val stage = Files.createTempDirectory(dataRoot, ".ga-profile-stage-")
        ConfigPathGuard.assertContained(dataGuard, stage)
        try {
            for ((relative, bytes) in files) {
                val content = bytes ?: throw IOException("Replacement contains a missing file body.")
                val staged = stage.resolve(relative).normalize()
                if (!staged.startsWith(stage)) throw IOException("Staged path escapes its temporary root.")
                Files.createDirectories(staged.parent)
                ConfigPathGuard.assertContained(dataGuard, staged.parent)
                Files.write(staged, content)
                ConfigPathGuard.assertContained(dataGuard, staged)
            }
            var index = 0
            for (relative in files.keys) {
                val target = dataRoot.resolve(relative).normalize()
                if (!target.startsWith(dataRoot)) throw IOException("Target path is unsafe.")
                ConfigPathGuard.rejectReparseChain(target)
                ensureNoLinks(dataRoot, target.parent)
                Files.createDirectories(target.parent)
                ConfigPathGuard.assertContained(dataGuard, target.parent)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) ConfigPathGuard.assertContained(dataGuard, target)
                injector?.beforeMove(restoring, index, target)
                val staged = stage.resolve(relative).normalize()
                ConfigPathGuard.assertContained(dataGuard, staged)
                move(staged, target)
                ConfigPathGuard.assertContained(dataGuard, target)
                index++
            }
            for (old in oldFiles.keys) {
                if (files.containsKey(old)) continue
                val target = dataRoot.resolve(old).normalize()
                if (!target.startsWith(dataRoot)) throw IOException("Cleanup path is unsafe.")
                ConfigPathGuard.rejectReparseChain(target)
                ensureNoLinks(dataRoot, target.parent)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) ConfigPathGuard.assertContained(dataGuard, target)
                Files.deleteIfExists(target)
            }
        } finally {
            deleteTree(stage)
        }
    }

    @Throws(IOException::class)
    private fun collectOwnedFiles(root: Path, safePath: Predicate<String>?): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val totalBytes = longArrayOf(0L)
        ConfigPathGuard.rejectReparseChain(root)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files
        val rootGuard = ConfigPathGuard.checkedDirectory(root)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                ConfigPathGuard.assertSafe(dir, attrs)
                ConfigPathGuard.assertContained(rootGuard, dir)
                if (dir == root.toAbsolutePath().normalize().resolve("exports")) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                ConfigPathGuard.assertSafe(file, attrs)
                ConfigPathGuard.assertContained(rootGuard, file)
                if (!attrs.isRegularFile) throw IOException("Config profile contains a non-regular file.")
                val relative = root.relativize(file).toString().replace('\\', '/')
                // These files are outside profile ownership: replacement and stale-file cleanup must preserve them.
                if (ConfigProfileCodec.isLocalOnlyPath(relative)) return FileVisitResult.CONTINUE
                if (!ConfigProfileCodec.isTransferablePath(relative) || safePath == null || !safePath.test(relative)) {
                    throw IOException("Unknown active config path: $relative")
                }
                val size = attrs.size()
                val remainingBytes = ConfigProfileCodec.MAX_TOTAL_BYTES - totalBytes[0]
                val maximumBytes = minOf(ConfigProfileCodec.MAX_ENTRY_BYTES.toLong(), remainingBytes)
                if (size < 0L || size > maximumBytes) throw IOException("Existing config files exceed the profile size limit.")
                val bytes = readOwnedFileBytes(file, size, maximumBytes.toInt())
                totalBytes[0] += bytes.size
                files[relative] = bytes
                if (files.size > ConfigProfileCodec.MAX_ENTRIES) throw IOException("Too many active config files.")
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readOwnedFileBytes(file: Path, expectedSize: Long, maximumBytes: Int): ByteArray {
        if (expectedSize < 0 || maximumBytes < 0 || expectedSize > maximumBytes) {
            throw IOException("Existing config member exceeds the profile size limit.")
        }
        Files.newInputStream(file).use { input ->
            val bytes = input.readNBytes(maximumBytes)
            // Keep the allocation within budget while still detecting a concurrent append.
            if (bytes.size == maximumBytes && input.read() != -1) {
                throw IOException("Existing config member grew beyond the profile size limit.")
            }
            if (bytes.size.toLong() != expectedSize || Files.size(file) != expectedSize) {
                throw IOException("Existing config member changed while reading.")
            }
            return bytes
        }
    }

    @Throws(IOException::class)
    private fun ensureNoLinks(root: Path, directory: Path?) {
        if (directory == null || !directory.normalize().startsWith(root)) {
            throw IOException("Config directory escapes the root.")
        }
        ConfigPathGuard.rejectReparseChain(directory)
    }

    @Throws(IOException::class)
    private fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Throws(IOException::class)
    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        ConfigPathGuard.rejectReparseChain(root)
        val rootGuard = ConfigPathGuard.checkedDirectory(root)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                ConfigPathGuard.assertSafe(dir, attrs)
                ConfigPathGuard.assertContained(rootGuard, dir)
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                ConfigPathGuard.assertSafe(file, attrs)
                ConfigPathGuard.assertContained(rootGuard, file)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            @Throws(IOException::class)
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                ConfigPathGuard.assertContained(rootGuard, dir)
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
