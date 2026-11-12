package geiler.addons.client.config

import geiler.addons.client.module.Category
import java.nio.file.Path
import java.util.function.Consumer

/** Static Kotlin entry point for bounded diagnostics shared by modules and mixin adapters. */
class GeilerAddonsLog private constructor() {
    companion object {
        @JvmStatic
        @Synchronized
        fun start() = GeilerAddonsLogCore.start()

        @JvmStatic
        @Synchronized
        fun setModuleDiagnosticsEnabled(category: Category?, module: String?, requested: Boolean) =
            GeilerAddonsLogCore.setModuleDiagnosticsEnabled(category, module, requested)

        @JvmStatic
        @Synchronized
        fun stop() = GeilerAddonsLogCore.stop()

        @JvmStatic fun close() = GeilerAddonsLogCore.close()
        @JvmStatic fun enabled(): Boolean = GeilerAddonsLogCore.isEnabled()
        @JvmStatic fun failure(): String? = GeilerAddonsLogCore.failure()
        @JvmStatic fun sessionDirectory(): Path? = GeilerAddonsLogCore.sessionDirectory()
        @JvmStatic fun path(category: Category?, module: String?): Path? = GeilerAddonsLogCore.path(category, module)
        @JvmStatic fun write(category: Category?, module: String?, tick: Long, line: String?) =
            GeilerAddonsLogCore.write(category, module, tick, line)

        @JvmStatic
        fun writeWithCompletion(
            category: Category?,
            module: String?,
            tick: Long,
            line: String?,
            completion: Consumer<Boolean>?,
        ): Boolean = GeilerAddonsLogCore.writeWithCompletion(category, module, tick, line, completion)
    }
}
