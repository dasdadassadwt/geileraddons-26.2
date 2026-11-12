package geiler.addons.client.chat

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Bounded, byte-preserving removal of retired chat-config JSON properties. */
object RetiredChatConfigRedactor {
    const val MAX_INPUT_BYTES = 16L * 1024 * 1024
    const val MAX_DEPTH = 64
    const val MAX_TOKENS = 200_000

    fun interface MoveOperation {
        @Throws(IOException::class)
        fun move(source: Path, target: Path, atomic: Boolean)
    }

    @JvmRecord
    data class Range(val start: Long, val end: Long) {
        override fun toString(): String = "Range[start=$start, end=$end]"
    }

    class ScanResult(sourceJsonValid: Boolean, removed: List<Range>, unsafeReason: String?) {
        private val sourceJsonValidValue = sourceJsonValid
        private val removedValue: List<Range> = java.util.List.copyOf(removed)
        private val unsafeReasonValue = unsafeReason

        fun sourceJsonValid(): Boolean = sourceJsonValidValue
        fun removed(): List<Range> = removedValue
        fun unsafeReason(): String? = unsafeReasonValue

        override fun equals(other: Any?): Boolean = other is ScanResult &&
            sourceJsonValidValue == other.sourceJsonValidValue && removedValue == other.removedValue &&
            unsafeReasonValue == other.unsafeReasonValue

        override fun hashCode(): Int {
            var result = java.lang.Boolean.hashCode(sourceJsonValidValue)
            result = 31 * result + removedValue.hashCode()
            result = 31 * result + (unsafeReasonValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "ScanResult[sourceJsonValid=$sourceJsonValidValue, removed=$removedValue, unsafeReason=$unsafeReasonValue]"
    }

    enum class Status { ABSENT, CLEAN, REDACTED, TOO_LARGE, UNSAFE, IO_ERROR }

    @JvmRecord
    data class Result(
        val status: Status?,
        val removedMembers: Int,
        val reason: String?,
        val sourceJsonValid: Boolean,
    ) {
        fun cleaned(): Boolean = status == Status.REDACTED || status == Status.CLEAN || status == Status.ABSENT

        override fun toString(): String =
            "Result[status=$status, removedMembers=$removedMembers, reason=$reason, sourceJsonValid=$sourceJsonValid]"
    }

    private const val IO_BUFFER_BYTES = 8 * 1024

    @JvmStatic
    fun redact(path: Path?): RetiredChatConfigRedactor.Result = redact(path,
        RetiredChatConfigRedactor.MoveOperation { source, target, atomic -> moveFile(source, target, atomic) })

    @JvmStatic
    fun redact(
        path: Path?,
        moveOperation: RetiredChatConfigRedactor.MoveOperation?,
    ): RetiredChatConfigRedactor.Result {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.ABSENT, 0, null, true)
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.UNSAFE, 0,
                "the chat settings path is not a regular file", false)
        }

        var temporary: Path? = null
        var sourceJsonValid = false
        try {
            val size = Files.size(path)
            if (size <= 0) {
                return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.CLEAN, 0, null, false)
            }
            if (size > RetiredChatConfigRedactor.MAX_INPUT_BYTES) {
                return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.TOO_LARGE, 0,
                    "the file exceeds the 16 MiB cleanup limit", false)
            }
            var removed: List<RetiredChatConfigRedactor.Range> = emptyList()
            FileChannel.open(path, StandardOpenOption.READ).use { input ->
                val scan = RetiredChatConfigRedactor.scan(input, size)
                val unsafeReason = scan.unsafeReason()
                if (unsafeReason != null) {
                    return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.UNSAFE, 0,
                        unsafeReason, false)
                }
                sourceJsonValid = scan.sourceJsonValid()
                removed = scan.removed()
                if (removed.isEmpty()) {
                    return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.CLEAN, 0,
                        if (sourceJsonValid) null else "the original file contains malformed JSON", sourceJsonValid)
                }
                val parent = path.toAbsolutePath().parent
                temporary = Files.createTempFile(parent, path.fileName.toString(), ".redacted.tmp")
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                    copyWithoutRanges(input, output, size, removed)
                    output.force(true)
                }
            }
            replaceCleanedFile(temporary!!, path, moveOperation)
            temporary = null
            return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.REDACTED, removed.size,
                if (sourceJsonValid) null else "the original file contains malformed JSON", sourceJsonValid)
        } catch (failure: IOException) {
            return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.IO_ERROR, 0,
                "the cleanup could not safely replace the file (${describeFailure(failure)})", false)
        } catch (failure: RuntimeException) {
            return RetiredChatConfigRedactor.Result(RetiredChatConfigRedactor.Status.IO_ERROR, 0,
                "the cleanup could not safely replace the file (${describeFailure(failure)})", false)
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary)
                } catch (_: IOException) {
                }
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun scan(input: FileChannel, size: Long): ScanResult = RetiredChatConfigScanner.scan(input, size)

    private fun replaceCleanedFile(
        temporary: Path,
        target: Path,
        moveOperation: RetiredChatConfigRedactor.MoveOperation?,
    ) {
        try {
            moveOperation!!.move(temporary, target, true)
        } catch (atomicFailure: IOException) {
            try {
                replaceWithRecoveryCopy(temporary, target, moveOperation)
            } catch (fallbackFailure: IOException) {
                fallbackFailure.addSuppressed(atomicFailure)
                throw fallbackFailure
            }
        }
    }

    private fun replaceWithRecoveryCopy(
        temporary: Path,
        target: Path,
        moveOperation: RetiredChatConfigRedactor.MoveOperation?,
    ) {
        val absoluteTarget = target.toAbsolutePath()
        val recovery = Files.createTempFile(absoluteTarget.parent,
            absoluteTarget.fileName.toString() + ".recovery-", ".tmp")
        var retainRecovery = false
        try {
            // Windows' non-atomic REPLACE_EXISTING provider may delete the target before its native move.
            // Keep a forced same-directory byte copy so a failed move can never destroy the only original.
            Files.copy(target, recovery, StandardCopyOption.REPLACE_EXISTING)
            forceFile(recovery)
            try {
                moveOperation!!.move(temporary, target, false)
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("replacement did not leave a regular settings file")
                }
            } catch (replacementFailure: IOException) {
                restoreAndRethrow(recovery, target, replacementFailure) { retainRecovery = true }
            } catch (replacementFailure: RuntimeException) {
                restoreAndRethrow(recovery, target, replacementFailure) { retainRecovery = true }
            }

            try {
                Files.delete(recovery)
            } catch (cleanupFailure: IOException) {
                retainRecovery = true
                throw RecoveryCopyRetainedException(
                    "the sanitized file was installed, but the original recovery copy remains at ${recovery.toAbsolutePath()}",
                    cleanupFailure,
                )
            }
        } finally {
            if (!retainRecovery) {
                try {
                    Files.deleteIfExists(recovery)
                } catch (_: IOException) {
                }
            }
        }
    }

    private inline fun restoreAndRethrow(
        recovery: Path,
        target: Path,
        replacementFailure: Throwable,
        retainRecovery: () -> Unit,
    ): Nothing {
        try {
            restoreOriginal(recovery, target)
        } catch (restoreFailure: IOException) {
            retainRecovery()
            val retained = RecoveryCopyRetainedException(
                "the original could not be restored after replacement failure; recovery copy retained at ${recovery.toAbsolutePath()}",
                restoreFailure,
            )
            retained.addSuppressed(replacementFailure)
            throw retained
        } catch (restoreFailure: RuntimeException) {
            retainRecovery()
            val retained = RecoveryCopyRetainedException(
                "the original could not be restored after replacement failure; recovery copy retained at ${recovery.toAbsolutePath()}",
                restoreFailure,
            )
            retained.addSuppressed(replacementFailure)
            throw retained
        }
        try {
            Files.deleteIfExists(recovery)
        } catch (cleanupFailure: IOException) {
            retainRecovery()
            val retained = RecoveryCopyRetainedException(
                "the original was restored, but its recovery copy could not be removed and remains at ${recovery.toAbsolutePath()}",
                cleanupFailure,
            )
            retained.addSuppressed(replacementFailure)
            throw retained
        }
        throw ReplacementFailureException("non-atomic replacement failed; the original was restored", replacementFailure)
    }

    private fun restoreOriginal(recovery: Path, target: Path) {
        Files.copy(recovery, target, StandardCopyOption.REPLACE_EXISTING)
        forceFile(target)
        if (Files.mismatch(recovery, target) != -1L) {
            throw IOException("restored settings file did not match its recovery copy")
        }
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
    }

    private fun moveFile(source: Path, target: Path, atomic: Boolean) {
        if (atomic) {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun describeFailure(failure: Throwable): String {
        if (failure is RecoveryCopyRetainedException) return failure.message ?: "recovery copy retained"
        if (failure is ReplacementFailureException) return failure.message ?: "replacement failed"
        val summary = StringBuilder(failure.javaClass.simpleName)
        if (failure is FileSystemException) {
            val reason = failure.reason
            if (reason != null && !ChatStringSemantics.isBlank(reason)) summary.append(": ").append(reason)
        }
        for (suppressed in failure.suppressed) {
            summary.append("; atomic attempt: ").append(suppressed.javaClass.simpleName)
            if (suppressed is FileSystemException) {
                val reason = suppressed.reason
                if (reason != null && !ChatStringSemantics.isBlank(reason)) summary.append(": ").append(reason)
            }
        }
        return summary.toString()
    }

    private fun copyWithoutRanges(
        input: FileChannel,
        output: FileChannel,
        size: Long,
        ranges: List<RetiredChatConfigRedactor.Range>,
    ) {
        val buffer = ByteBuffer.allocate(IO_BUFFER_BYTES)
        var cursor = 0L
        for (range in ranges) {
            copyRange(input, output, buffer, cursor, range.start - cursor)
            cursor = range.end
        }
        copyRange(input, output, buffer, cursor, size - cursor)
    }

    private fun copyRange(input: FileChannel, output: FileChannel, buffer: ByteBuffer, start: Long, length: Long) {
        var position = start
        var remaining = length
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = input.read(buffer, position)
            if (read < 0) throw IOException("Unexpected end of chat settings file")
            if (read == 0) continue
            buffer.flip()
            while (buffer.hasRemaining()) output.write(buffer)
            position += read
            remaining -= read
        }
    }

    private class ReplacementFailureException(message: String, cause: Throwable) : IOException(message, cause)
    private class RecoveryCopyRetainedException(message: String, cause: Throwable) : IOException(message, cause)
}
