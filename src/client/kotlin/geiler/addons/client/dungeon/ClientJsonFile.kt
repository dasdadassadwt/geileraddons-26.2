package geiler.addons.client.dungeon

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.List as JavaList
import java.util.concurrent.Future
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Bounded, off-thread JSON persistence shared by the dungeon catalogs. */
class ClientJsonFile private constructor() {
    companion object {
        const val MAX_FILE_BYTES = 1_048_576
        const val MAX_PACKAGE_BYTES = 262_144
        const val MAX_ENTRIES = 2_000

        @JvmField
        val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        /** Load a bundled file from the classpath first, then resolve it through Fabric's packaged mod container. */
        @JvmStatic
        @Throws(IOException::class)
        fun openBundledResource(anchor: Class<*>?, resource: String?): InputStream =
            ClientJsonFileLogic.openBundledResource(anchor, resource)

        @JvmStatic
        fun <T> read(path: Path?, type: Class<T>?): T? = ClientJsonFileLogic.read(path, type)

        @JvmStatic
        fun <T> readResult(path: Path?, type: Class<T>?): ReadResult<T> = ClientJsonFileLogic.readResult(path, type)

        @JvmStatic
        fun writeAsync(path: Path?, json: String?): Future<Boolean> =
            synchronized(ClientJsonFile::class.java) { ClientJsonFileLogic.writeAsync(path, json) }

        /** Writes all data files first and the index last on one serialized worker. */
        @JvmStatic
        fun writeBatchAsync(writes: List<Write?>?): Future<Boolean> =
            synchronized(ClientJsonFile::class.java) { ClientJsonFileLogic.writeBatchAsync(writes) }

        @JvmStatic
        fun flushAndReport(): Boolean = ClientJsonFileLogic.flushAndReport()

        @JvmStatic
        fun flush() = ClientJsonFileLogic.flush()
    }

    @JvmRecord
    data class ReadResult<T>(val exists: Boolean, val value: T?, val error: String?) {
        fun valid(): Boolean = error == null
    }

    @JvmRecord
    data class Write(val path: Path, val bytes: ByteArray)
}
