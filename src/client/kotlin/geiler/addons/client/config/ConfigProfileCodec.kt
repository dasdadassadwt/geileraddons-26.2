package geiler.addons.client.config

import java.io.IOException

/** Stable static API for the bounded Kotlin profile codec. */
class ConfigProfileCodec private constructor() {
    class Archive(files: Map<String, ByteArray>, createdAt: String, totalBytes: Long) {
        private val snapshot: Map<String, ByteArray> = java.util.Map.copyOf(files)
        private val timestamp: String = createdAt
        private val byteCount: Long = totalBytes

        fun files(): Map<String, ByteArray> = snapshot
        fun createdAt(): String = timestamp
        fun totalBytes(): Long = byteCount

        override fun equals(other: Any?): Boolean =
            other is Archive && snapshot == other.snapshot && timestamp == other.timestamp && byteCount == other.byteCount

        override fun hashCode(): Int {
            var result = snapshot.hashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + byteCount.hashCode()
            return result
        }

        override fun toString(): String =
            "Archive[files=$snapshot, createdAt=$timestamp, totalBytes=$byteCount]"
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val MAX_ICON_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_ENTRIES = 10_000
        const val MAX_JSON_DEPTH = 160
        const val MAX_JSON_NODES = 100_000
        const val MAX_JSON_TOKENS = 200_000

        @JvmStatic fun isLocalOnlyPath(path: String?): Boolean = ConfigProfileCodecSupport.isLocalOnlyPath(path)

        @JvmStatic fun isTransferablePath(path: String?): Boolean = ConfigProfileCodecSupport.isTransferablePath(path)

        @JvmStatic
        @Throws(IOException::class)
        @Suppress("UNCHECKED_CAST")
        fun encode(files: Map<String, ByteArray>?): ByteArray =
            ConfigProfileCodecSupport.encode(files as Map<String?, ByteArray?>?)

        @JvmStatic
        @Throws(IOException::class)
        fun decode(archive: ByteArray?): Archive = ConfigProfileCodecSupport.decode(archive)

        @JvmStatic
        @Throws(IOException::class)
        fun validateJsonBounds(json: ByteArray?) = ConfigProfileCodecSupport.validateJsonBounds(json)

        @JvmStatic
        @Throws(IOException::class)
        fun validateIconPng(png: ByteArray?) = ConfigProfileCodecSupport.validateIconPng(png)

        @JvmStatic fun safeName(name: String?): Boolean = ConfigProfileCodecSupport.safeName(name)

        @JvmStatic fun safePath(path: String?): Boolean = ConfigProfileCodecSupport.safePath(path)
    }
}
