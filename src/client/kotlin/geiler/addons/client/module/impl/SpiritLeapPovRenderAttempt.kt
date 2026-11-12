package geiler.addons.client.module.impl

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Owns the phase and fog-buffer cleanup order for one isolated POV render attempt. */
class SpiritLeapPovRenderAttempt<T> {
    interface Operations<T> {
        fun stage(stage: String)
        fun updateCamera()
        fun extractCamera()
        fun setupFog()
        fun updateFogBuffer()
        fun readFogBuffer(): T
        fun updateRenderer()
        fun prepareExtractionContext()
        fun extractLevel()
        fun render(fogBuffer: T)
        fun endFogFrame()
    }

    private var stage = "none"
    private var failureStage = "none"
    private var failure: Throwable? = null
    private var fogBufferUpdateEntered = false
    private var fogBuffer: T? = null

    fun execute(operations: Operations<T>) {
        stage = "none"
        failureStage = "none"
        failure = null
        fogBufferUpdateEntered = false
        fogBuffer = null
        try {
            enter(operations, CAMERA_STAGE)
            operations.updateCamera()
            operations.extractCamera()
            enter(operations, FOG_SETUP_STAGE)
            operations.setupFog()
            enter(operations, FOG_BUFFER_STAGE)
            // updateBuffer owns/advances the private FogRenderer ring even if this call throws.
            fogBufferUpdateEntered = true
            operations.updateFogBuffer()
            val currentFogBuffer = operations.readFogBuffer()
            fogBuffer = currentFogBuffer
            enter(operations, EXTRACTION_STAGE)
            operations.updateRenderer()
            enter(operations, CONTEXT_PREPARATION_STAGE)
            operations.prepareExtractionContext()
            enter(operations, EXTRACTION_STAGE)
            operations.extractLevel()
            enter(operations, RENDER_STAGE)
            operations.render(currentFogBuffer)
        } catch (renderFailure: RuntimeException) {
            failure = renderFailure
            failureStage = stage
        } catch (renderFailure: Error) {
            failure = renderFailure
            failureStage = stage
        } finally {
            if (fogBufferUpdateEntered) {
                if (failure == null) {
                    stage = FOG_CLEANUP_STAGE
                    try {
                        operations.stage(stage)
                    } catch (stageFailure: RuntimeException) {
                        failure = stageFailure
                        failureStage = stage
                    } catch (stageFailure: Error) {
                        failure = stageFailure
                        failureStage = stage
                    }
                }
                try {
                    operations.endFogFrame()
                } catch (cleanupFailure: RuntimeException) {
                    failure = combineFailure(failure, cleanupFailure)
                    if (failureStage == "none") failureStage = FOG_CLEANUP_STAGE
                } catch (cleanupFailure: Error) {
                    failure = combineFailure(failure, cleanupFailure)
                    if (failureStage == "none") failureStage = FOG_CLEANUP_STAGE
                }
            }
        }
    }

    fun failure(): Throwable? = failure
    fun failureStage(): String = failureStage

    private fun enter(operations: Operations<T>, nextStage: String) {
        stage = nextStage
        operations.stage(nextStage)
    }

    private data class FailureNode(val label: String, val failure: Throwable)

    companion object {
        const val CAMERA_STAGE = "camera-update"
        const val FOG_SETUP_STAGE = "fog-setup"
        const val FOG_BUFFER_STAGE = "fog-buffer"
        const val EXTRACTION_STAGE = "level-extraction"
        const val CONTEXT_PREPARATION_STAGE = "fabric-context-preparation"
        const val RENDER_STAGE = "render-attempt"
        const val FOG_CLEANUP_STAGE = "fog-buffer-cleanup"
        const val MAX_FAILURE_SUMMARY_CHARS = 1_600
        const val MAX_SUPPRESSED_CLEANUP_FAILURES = 4
        private const val MAX_FAILURE_NODES = 4
        private const val MAX_FRAMES_PER_FAILURE = 6

        @JvmStatic
        fun failureType(failure: Throwable?): String {
            if (failure == null) return "Unknown"
            val name = failure.javaClass.simpleName
            return if (name.isNullOrEmpty()) "Unknown" else safeField(name, 32)
        }

        @JvmStatic
        fun failureTopSite(failure: Throwable?): String {
            if (failure == null) return "unknown"
            val frames = failure.stackTrace
            return if (frames.isEmpty()) "unknown" else formatFrame(frames[0])
        }

        /** Formats only bounded code locations; exception messages and arbitrary object text are excluded. */
        @JvmStatic
        fun failureSummary(stage: String?, failure: Throwable?): String {
            if (failure == null) return "stage=${safeField(stage, 48)} type=Unknown"
            val summary = StringBuilder()
            append(summary, "stage=${safeField(stage, 48)}")
            val pending = ArrayDeque<FailureNode>()
            val visited: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
            pending.addLast(FailureNode("root", failure))
            var visitedCount = 0
            var scheduledCount = 1
            var relatedTruncated = false
            while (pending.isNotEmpty() && visitedCount < MAX_FAILURE_NODES) {
                val node = pending.removeFirst()
                if (!visited.add(node.failure)) continue
                visitedCount++
                append(summary, " ${node.label}=${safeField(node.failure.javaClass.name, 96)}")
                val frames = node.failure.stackTrace
                if (frames.isEmpty()) {
                    append(summary, " at=none")
                } else {
                    for (index in 0 until minOf(frames.size, MAX_FRAMES_PER_FAILURE)) {
                        append(summary, " at=${formatFrame(frames[index])}")
                    }
                }
                val cause = node.failure.cause
                if (cause != null) {
                    if (scheduledCount < MAX_FAILURE_NODES) {
                        pending.addLast(FailureNode("cause", cause))
                        scheduledCount++
                    } else {
                        relatedTruncated = true
                    }
                }
                val suppressed = node.failure.suppressed
                for (index in 0 until minOf(suppressed.size, MAX_FAILURE_NODES)) {
                    if (scheduledCount >= MAX_FAILURE_NODES) {
                        relatedTruncated = true
                        break
                    }
                    pending.addLast(FailureNode("suppressed", suppressed[index]))
                    scheduledCount++
                }
            }
            if (relatedTruncated || pending.isNotEmpty() || summary.length > MAX_FAILURE_SUMMARY_CHARS) {
                val trimAt = MAX_FAILURE_SUMMARY_CHARS - 1
                if (summary.length > trimAt) summary.setLength(trimAt)
                summary.append('~')
            }
            return summary.toString()
        }

        @JvmStatic
        fun combineFailure(primary: Throwable?, cleanupFailure: Throwable?): Throwable? {
            if (primary == null) return cleanupFailure
            if (cleanupFailure == null || primary === cleanupFailure) return primary
            try {
                if (primary.suppressed.size < MAX_SUPPRESSED_CLEANUP_FAILURES) primary.addSuppressed(cleanupFailure)
            } catch (_: RuntimeException) {
                // Failure reporting must never replace the primary render or cleanup failure.
            } catch (_: Error) {
                // Failure reporting must never replace the primary render or cleanup failure.
            }
            return primary
        }

        private fun formatFrame(frame: StackTraceElement) =
            "${safeField(frame.className, 96)}.${safeField(frame.methodName, 64)}" +
                "(${safeField(frame.fileName, 64)}:${frame.lineNumber})"

        private fun safeField(value: String?, limit: Int): String {
            if (value.isNullOrEmpty()) return "unknown"
            val length = minOf(value.length, limit)
            val safe = StringBuilder(length)
            for (index in 0 until length) {
                val character = value[index]
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                    character == '.' || character == '_' || character == '$' || character == '/' || character == '-'
                ) safe.append(character) else safe.append('?')
            }
            if (value.length > length && safe.length < limit) safe.append('~')
            return safe.toString()
        }

        private fun append(builder: StringBuilder, value: String) {
            if (builder.length >= MAX_FAILURE_SUMMARY_CHARS) return
            builder.append(value)
        }
    }
}
