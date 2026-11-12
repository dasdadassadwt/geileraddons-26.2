package geiler.addons.client.dungeon

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.UUID
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Bounded versioned JSON codec for player-authored Dungeon Guide content. */
@JvmSuppressWildcards
class DungeonPackageCodec private constructor() {
    @JvmRecord
    @JvmSuppressWildcards
    data class DecodeResult<T>(val success: Boolean, val entries: List<T>?, val error: String?) {
        override fun toString(): String = "DecodeResult[success=$success, entries=$entries, error=$error]"
    }

    companion object {
        @JvmStatic
        fun encodeGuides(entries: List<DungeonGuideNode>?): String? {
            if (entries == null || entries.size > ClientJsonFile.MAX_ENTRIES) return null
            val data = GuidePackage()
            data.version = 1
            data.nodes = entries
            return boundedEncode(data)
        }

        @JvmStatic
        fun decodeGuides(encoded: String?): DecodeResult<DungeonGuideNode> {
            if (!validRoot(encoded)) return failure("Package JSON is invalid or too large.")
            val data = try {
                ClientJsonFile.GSON.fromJson(encoded, GuidePackage::class.java)
            } catch (_: RuntimeException) {
                return failure("Package data is invalid.")
            }
            val nodes = data?.nodes
            if (data == null || data.version != 1 || nodes == null || nodes.size > ClientJsonFile.MAX_ENTRIES) {
                return failure("Unsupported package version or entry count.")
            }
            val ids = HashSet<String>()
            val clean = ArrayList<DungeonGuideNode>()
            for (node in nodes) {
                if (node == null) continue
                node.sanitize()
                if (!ids.add(node.id)) {
                    node.id = UUID.randomUUID().toString()
                    ids.add(node.id)
                }
                clean.add(node)
            }
            return DecodeResult(true, java.util.List.copyOf(clean), null)
        }

        private fun boundedEncode(data: Any): String? {
            val json = ClientJsonFile.GSON.toJson(data)
            return if (json.toByteArray(StandardCharsets.UTF_8).size <= ClientJsonFile.MAX_PACKAGE_BYTES) json else null
        }

        private fun validRoot(encoded: String?): Boolean {
            if (encoded == null || encoded.toByteArray(StandardCharsets.UTF_8).size > ClientJsonFile.MAX_PACKAGE_BYTES) {
                return false
            }
            return try {
                JsonParser.parseString(encoded).isJsonObject
            } catch (_: JsonParseException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }

        private fun <T> failure(error: String): DecodeResult<T> =
            DecodeResult(false, java.util.List.of(), error)
    }

    private class GuidePackage {
        @JvmField var version: Int = 0
        @JvmField var nodes: List<DungeonGuideNode?>? = null
    }
}
