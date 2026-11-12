package geiler.addons.client.config

import geiler.addons.client.module.Category
import java.nio.file.Path

/** Compatibility facade for Tiki Helper's opt-in research trace. */
object TikiDebugLog {
    private val category = Category.HUNTING
    private const val MODULE = "Tiki Helper"
    private const val MAX_PAYLOAD_LENGTH = 4_096

    @JvmStatic
    fun open() = GeilerAddonsLog.start()

    /** The central writer is shared with every module, so closing Tiki alone must not stop it. */
    @JvmStatic
    fun close() = Unit

    @JvmStatic
    fun write(tick: Long, line: String) =
        GeilerAddonsLog.write(category, MODULE, tick, line.take(MAX_PAYLOAD_LENGTH))

    @JvmStatic
    fun path(): Path? = GeilerAddonsLog.path(category, MODULE)
}
