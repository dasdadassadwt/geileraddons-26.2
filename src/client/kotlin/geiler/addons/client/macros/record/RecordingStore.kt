package geiler.addons.client.macros.record

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * A bounded JSON-lines file. The recording is written in segments of half the bound; when the current one is full the next
 * is started and the one before the previous is deleted, so the oldest samples go and the files on disk never add up to more
 * than [maxBytes]. Every segment starts with [header], so one can be read on its own.
 */
class RecordingStore(
    private val dir: Path,
    private val session: String,
    private val maxBytes: Long,
    private val header: String,
) : AutoCloseable {
    private val segmentBytes = maxBytes / 2
    private var index = 0
    private var writer: BufferedWriter? = null
    private var currentBytes = 0L
    private val sizes = HashMap<Int, Long>()

    init {
        Files.createDirectories(dir)
        open()
    }

    private fun path(i: Int): Path = dir.resolve("$session.$i.jsonl")

    private fun open() {
        writer = Files.newBufferedWriter(path(index), StandardCharsets.UTF_8)
        currentBytes = 0L
        write(header)
    }

    private fun write(line: String) {
        val w = writer ?: return
        w.write(line)
        w.write('\n'.code)
        currentBytes += line.toByteArray(StandardCharsets.UTF_8).size + 1L
        sizes[index] = currentBytes
    }

    /** Adds a line; rotates first when the current segment would pass its share of the bound. */
    fun append(line: String) {
        val size = line.toByteArray(StandardCharsets.UTF_8).size + 1L
        if (currentBytes + size > segmentBytes) rotate()
        write(line)
    }

    private fun rotate() {
        writer?.close()
        index++
        val old = index - 2
        if (old >= 0) {
            Files.deleteIfExists(path(old))
            sizes.remove(old)
        }
        open()
    }

    /** What the kept segments add up to, written or buffered. */
    fun bytes(): Long = sizes.values.sum()

    fun flush() {
        writer?.flush()
    }

    /** The folder the segments are in. */
    fun directory(): Path = dir

    override fun close() {
        writer?.close()
        writer = null
    }
}
