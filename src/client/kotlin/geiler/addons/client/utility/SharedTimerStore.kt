package geiler.addons.client.utility

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The `/ga timer` countdown on disk, in the user's home folder so every game instance (each Modrinth profile
 * has its own config folder) reads and writes the same file. Each write carries a [Record.revision]; an instance
 * applies a record only when its revision is newer than the last one it saw.
 */
object SharedTimerStore {
    data class Record(val revision: Long, val writer: String, val timer: CountdownTimerState.Snapshot)

    @JvmStatic
    fun defaultPath(): Path = Paths.get(System.getProperty("user.home"), ".geileraddons", "shared-timer.json")

    /** Strictly increasing, and close to wall time so a fresh instance never loses to an older file. */
    @JvmStatic
    fun nextRevision(lastSeen: Long, nowEpochMs: Long): Long = maxOf(nowEpochMs, lastSeen + 1)

    @JvmStatic
    fun shouldApply(record: Record?, lastRevision: Long, self: String): Boolean =
        record != null && record.revision > lastRevision && record.writer != self

    @JvmStatic
    fun encode(record: Record): String {
        val json = JsonObject()
        json.addProperty("revision", record.revision)
        json.addProperty("writer", record.writer)
        json.addProperty("initialMs", record.timer.initialMs)
        json.addProperty("remainingMs", record.timer.remainingMs)
        json.addProperty("running", record.timer.running)
        json.addProperty("endEpochMs", record.timer.endEpochMs)
        return json.toString()
    }

    /** Null for anything that is not a complete record; a broken file never throws. */
    @JvmStatic
    fun decode(text: String): Record? = try {
        val json = JsonParser.parseString(text).asJsonObject
        Record(
            json.get("revision").asLong,
            json.get("writer").asString,
            CountdownTimerState.Snapshot(
                json.get("initialMs").asLong,
                json.get("remainingMs").asLong,
                json.get("running").asBoolean,
                json.get("endEpochMs").asLong,
            ),
        )
    } catch (exception: RuntimeException) {
        null
    }

    @JvmStatic
    fun read(path: Path): Record? = try {
        if (Files.isRegularFile(path)) decode(Files.readString(path)) else null
    } catch (exception: java.io.IOException) {
        null
    }

    /** Writes to a temp file and moves it over, so a reader never sees half a record. */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun write(path: Path, record: Record) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temp, encode(record))
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @JvmStatic
    fun modifiedMs(path: Path): Long = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (exception: java.io.IOException) {
        -1L
    }
}
