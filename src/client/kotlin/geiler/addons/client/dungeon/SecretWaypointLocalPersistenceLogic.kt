package geiler.addons.client.dungeon

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

/** Local-file I/O shared by the store and isolated copied-file checks. */
object SecretWaypointLocalPersistenceLogic {
    @JvmStatic
    fun read(path: Path?): ClientJsonFile.ReadResult<SecretWaypointStore.LocalFile> =
        ClientJsonFile.readResult(path, SecretWaypointStore.LocalFile::class.java)

    /** Returns null when a local snapshot exceeds the persisted file bound. */
    @JvmStatic
    fun encode(file: SecretWaypointStore.LocalFile?): String? {
        if (file == null) return null
        val json = ClientJsonFile.GSON.toJson(file)
        return if (json.toByteArray(StandardCharsets.UTF_8).size <= ClientJsonFile.MAX_FILE_BYTES) json else null
    }

    /** Queues an already encoded snapshot through the same atomic writer as the store. */
    @JvmStatic
    fun writeEncoded(path: Path?, json: String?): Future<Boolean> =
        if (path == null || json == null) CompletableFuture.completedFuture(false)
        else ClientJsonFile.writeAsync(path, json)

    /** Encodes and queues a local snapshot, for copied-store persistence checks and callers. */
    @JvmStatic
    fun write(path: Path?, file: SecretWaypointStore.LocalFile?): Future<Boolean> {
        val json = encode(file) ?: return CompletableFuture.completedFuture(false)
        return writeEncoded(path, json)
    }
}
