package geiler.addons.client.macros.etw

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where recordings live and how they are written: new files only, atomically, nulls and full precision kept. */
object EtwStore {
    private val GSON = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    @JvmStatic
    fun directory(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("etw")

    /** A path in [directory] that does not exist yet: a recording is never written over another one. */
    @JvmStatic
    fun uniqueFile(directory: Path, sessionId: String): Path {
        var candidate = directory.resolve("$sessionId.json")
        var counter = 2
        while (Files.exists(candidate)) {
            candidate = directory.resolve("$sessionId-$counter.json")
            counter++
        }
        return candidate
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeAtomically(file: Path, document: JsonObject) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "etw-", ".tmp")
        try {
            Files.writeString(temporary, GSON.toJson(document), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
