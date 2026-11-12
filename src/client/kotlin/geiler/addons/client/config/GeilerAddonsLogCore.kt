package geiler.addons.client.config

import geiler.addons.GeilerAddons
import geiler.addons.client.module.Category
import geiler.addons.client.module.ModuleManager
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/** Kotlin implementation of the shared asynchronous diagnostics sink. */
object GeilerAddonsLogCore {
    private val sessionStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
    private val lineStamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val maxQueuedLines = 8192
    private val root = FabricLoader.getInstance().gameDir.resolve("logs").resolve("geileraddons")
    private val lock = GeilerAddonsLog::class.java
    private val recoveryBackoff = GeilerAddonsLogRecoveryBackoff()

    @Volatile
    private var enabledState = false

    private var masterEnabled = false
    private val independentSources = HashSet<DiagnosticSource>()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var sessionDirectory: Path? = null

    @Volatile
    private var queue: LinkedBlockingQueue<DiagnosticEntry>? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var failure: String? = null

    private var restartRequested = false
    private var closeRequested = false
    private val droppedLines = AtomicLong()

    /** Starts or resumes the current game-session writer when Dev Debug is enabled. */
    @JvmStatic
    fun start() = synchronized(lock) {
        masterEnabled = true
        ensureWriter()
    }

    /**
     * Keeps one module's opt-in diagnostics active without enabling Dev Debug for the rest of the
     * mod. Call from that module's client tick so turning the setting off releases its writer
     * interest.
     */
    @JvmStatic
    fun setModuleDiagnosticsEnabled(category: Category?, module: String?, requested: Boolean) =
        synchronized(lock) {
            if (category == null || module == null) return@synchronized
            val source = DiagnosticSource(category, module)
            val changed = if (requested) independentSources.add(source) else independentSources.remove(source)
            val shouldRun = masterEnabled || independentSources.isNotEmpty()
            if (shouldRun) {
                val nowNanos = System.nanoTime()
                val workerMissing = worker == null || worker?.isAlive != true
                if (recoveryBackoff.shouldStartWriter(changed, workerMissing, failure != null, nowNanos)) {
                    if (changed || failure == null) ensureWriter()
                    else if (recoveryBackoff.beginRetryAt(nowNanos)) scheduleWriterRecovery()
                }
            } else if (changed || worker != null) {
                stopWriter()
            }
        }

    /** Starts the writer while preserving the current global/opt-in ownership state. */
    private fun ensureWriter() {
        val currentWorker = worker
        if (currentWorker != null && currentWorker.isAlive) {
            if (stopRequested) restartRequested = true else enabledState = true
            return
        }
        try {
            if (sessionDirectory == null) {
                Files.createDirectories(root)
                var candidate = root.resolve(LocalDateTime.now().format(sessionStamp))
                var suffix = 1
                while (Files.exists(candidate)) {
                    candidate = root.resolve(LocalDateTime.now().format(sessionStamp) + "-" + suffix++)
                }
                sessionDirectory = candidate
                Files.createDirectories(candidate)
                initializeModuleFiles()
            }
            val sessionQueue = LinkedBlockingQueue<DiagnosticEntry>(maxQueuedLines)
            queue = sessionQueue
            stopRequested = false
            restartRequested = false
            closeRequested = false
            droppedLines.set(0)
            failure = null
            recoveryBackoff.recovered()
            enabledState = true
            val sessionWorker = Thread({ runWriter(sessionQueue) }, "GeilerAddons log writer")
            sessionWorker.isDaemon = true
            worker = sessionWorker
            sessionWorker.start()
            write(Category.DEV, "Core", 0L, "LOG SESSION START")
        } catch (error: IOException) {
            enabledState = false
            failure = error.message ?: error.javaClass.simpleName
            recoveryBackoff.failedAt(System.nanoTime())
            GeilerAddons.LOGGER.error("Could not open the GeilerAddons diagnostic log", error)
        }
    }

    /** Runs automatic recovery off the client tick; the schedule coalesces repeated due checks. */
    private fun scheduleWriterRecovery() {
        try {
            val recoveryThread = Thread({
                try {
                    synchronized(lock) {
                        if (worker?.isAlive == true || failure == null || (!masterEnabled && independentSources.isEmpty())) {
                            return@synchronized
                        }
                        ensureWriter()
                    }
                } finally {
                    synchronized(lock) { recoveryBackoff.finishRetry() }
                }
            }, "GeilerAddons log recovery")
            recoveryThread.isDaemon = true
            recoveryThread.start()
        } catch (error: RuntimeException) {
            recoveryBackoff.failedAt(System.nanoTime())
            recoveryBackoff.finishRetry()
            GeilerAddons.LOGGER.warn("Could not start GeilerAddons diagnostic log recovery", error)
        }
    }

    /** Creates the stable category/module tree up front, including quiet modules with no events. */
    @Throws(IOException::class)
    private fun initializeModuleFiles() {
        val session = sessionDirectory ?: return
        for (category in Category.values()) {
            Files.createDirectories(session.resolve(safe(category.displayName())))
        }
        for (module in ModuleManager.modules()) {
            val file = path(module.category(), module.name()) ?: continue
            Files.createDirectories(file.parent)
            Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** Stops the writer but keeps the session directory so a later toggle resumes it. */
    @JvmStatic
    fun stop() = synchronized(lock) {
        masterEnabled = false
        if (independentSources.isNotEmpty()) {
            ensureWriter()
            return@synchronized
        }
        stopWriter()
    }

    private fun stopWriter() {
        enabledState = false
        val currentWorker = worker
        if (currentWorker == null || !currentWorker.isAlive) {
            queue = null
            worker = null
            stopRequested = false
            return
        }
        // Drain queued lines and exit without blocking the client thread or dropping older lines.
        stopRequested = true
    }

    /** Closes the current writer at game shutdown. */
    @JvmStatic
    fun close() {
        val currentWorker = synchronized(lock) {
            masterEnabled = false
            independentSources.clear()
            enabledState = false
            restartRequested = false
            closeRequested = true
            val activeWorker = worker
            if (activeWorker != null && activeWorker.isAlive) {
                stopRequested = true
                sessionDirectory = null
                activeWorker
            } else {
                sessionDirectory = null
                queue = null
                worker = null
                stopRequested = false
                closeRequested = false
                null
            }
        } ?: return

        if (currentWorker === Thread.currentThread()) return
        try {
            currentWorker.join(1_000L)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (currentWorker.isAlive) {
            GeilerAddons.LOGGER.warn("GeilerAddons diagnostic writer did not drain before shutdown timeout")
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabledState

    @JvmStatic
    fun failure(): String? = failure

    @JvmStatic
    fun sessionDirectory(): Path? = sessionDirectory

    /** Returns the eventual path for a module log, or null when diagnostics are not active. */
    @JvmStatic
    fun path(category: Category?, module: String?): Path? {
        val session = sessionDirectory ?: return null
        if (category == null || module == null) return null
        return session.resolve(safe(category.displayName())).resolve(safe(module) + ".log")
    }

    @JvmStatic
    fun write(category: Category?, module: String?, tick: Long, line: String?) {
        enqueue(category, module, tick, line, null)
    }

    /**
     * Queues one diagnostic line and calls completion on the log-writer thread only after the
     * buffered writer flushes successfully.
     */
    @JvmStatic
    fun writeWithCompletion(
        category: Category?,
        module: String?,
        tick: Long,
        line: String?,
        completion: Consumer<Boolean>?,
    ): Boolean = completion != null && enqueue(category, module, tick, line, completion)

    private fun enqueue(
        category: Category?,
        module: String?,
        tick: Long,
        line: String?,
        completion: Consumer<Boolean>?,
    ): Boolean {
        if (!enabledState || category == null || module == null || line == null) return false
        val safeLine = line.replace('\r', ' ').replace('\n', ' ')
        val text = "[${LocalDateTime.now().format(lineStamp)}] [t$tick] [${category.displayName()}/$module] " +
            safeLine + System.lineSeparator()
        synchronized(lock) {
            if (!enabledState || (!masterEnabled && DiagnosticSource(category, module) !in independentSources)) {
                return false
            }
            val currentQueue = queue ?: return false
            val target = path(category, module) ?: return false
            if (!currentQueue.offer(DiagnosticEntry(target, text, completion))) {
                droppedLines.incrementAndGet()
                return false
            }
            return true
        }
    }

    private fun runWriter(currentQueue: LinkedBlockingQueue<DiagnosticEntry>) {
        val writers = HashMap<Path, Writer>()
        val pendingCompletions = ArrayList<Consumer<Boolean>>()
        var lastFlush = System.nanoTime()
        var pending = 0
        try {
            while (true) {
                val entry = currentQueue.poll(250, TimeUnit.MILLISECONDS)
                if (entry == null) {
                    if (pending > 0) {
                        flush(writers)
                        complete(pendingCompletions, true)
                    }
                    pending = 0
                    lastFlush = System.nanoTime()
                    if (stopRequested && currentQueue.isEmpty()) break
                    continue
                }
                val target = entry.path
                if (target != null) {
                    entry.completion?.let(pendingCompletions::add)
                    val writer = writerFor(target, writers)
                    writer.write(entry.text)
                    pending++
                } else {
                    entry.completion?.let { complete(it, false) }
                }
                if (pending >= 32 || System.nanoTime() - lastFlush >= 250_000_000L) {
                    flush(writers)
                    complete(pendingCompletions, true)
                    pending = 0
                    lastFlush = System.nanoTime()
                }
            }
            while (true) {
                val remaining = currentQueue.poll() ?: break
                val target = remaining.path
                if (target == null) {
                    remaining.completion?.let { complete(it, false) }
                    continue
                }
                remaining.completion?.let(pendingCompletions::add)
                writerFor(target, writers).write(remaining.text)
            }
            flush(writers)
            complete(pendingCompletions, true)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            GeilerAddons.LOGGER.warn("GeilerAddons log writer interrupted")
        } catch (error: IOException) {
            synchronized(lock) {
                enabledState = false
                stopRequested = true
                failure = error.message ?: error.javaClass.simpleName
                recoveryBackoff.failedAt(System.nanoTime())
            }
            GeilerAddons.LOGGER.error("Could not write the GeilerAddons diagnostic log", error)
        } finally {
            complete(pendingCompletions, false)
            while (true) {
                val unprocessed = currentQueue.poll() ?: break
                unprocessed.completion?.let { complete(it, false) }
            }
            val dropped = droppedLines.getAndSet(0)
            if (dropped > 0) GeilerAddons.LOGGER.warn("Dropped {} GeilerAddons diagnostic lines", dropped)
            for (writer in writers.values) {
                try {
                    writer.close()
                } catch (error: IOException) {
                    GeilerAddons.LOGGER.error("Could not close a GeilerAddons diagnostic log", error)
                }
            }
            val restart = synchronized(lock) {
                if (worker === Thread.currentThread()) {
                    worker = null
                    queue = null
                    stopRequested = false
                    if (closeRequested) sessionDirectory = null
                    val shouldRestart = restartRequested && !closeRequested &&
                        (masterEnabled || independentSources.isNotEmpty())
                    restartRequested = false
                    closeRequested = false
                    shouldRestart
                } else {
                    false
                }
            }
            if (restart) synchronized(lock) { ensureWriter() }
        }
    }

    @Throws(IOException::class)
    private fun writerFor(path: Path, writers: MutableMap<Path, Writer>): Writer {
        writers[path]?.let { return it }
        Files.createDirectories(path.parent)
        return Files.newBufferedWriter(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        ).also { writers[path] = it }
    }

    @Throws(IOException::class)
    private fun flush(writers: Map<Path, Writer>) {
        for (writer in writers.values) writer.flush()
    }

    private fun complete(completions: MutableList<Consumer<Boolean>>, flushed: Boolean) {
        for (completion in completions) complete(completion, flushed)
        completions.clear()
    }

    private fun complete(completion: Consumer<Boolean>, flushed: Boolean) {
        try {
            completion.accept(flushed)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("A GeilerAddons log completion callback failed", error)
        }
    }

    private fun safe(value: String): String {
        val result = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").replace(Regex("_+"), "_")
        return if (result.isBlank()) "unknown" else result
    }

    private data class DiagnosticEntry(val path: Path?, val text: String, val completion: Consumer<Boolean>?)

    private data class DiagnosticSource(val category: Category, val module: String)
}

/** Monotonic, capped exponential retry schedule for diagnostic-writer recovery. */
open class GeilerAddonsLogRecoveryBackoff {
    private var nextDelayNanos = INITIAL_DELAY_NANOS
    private var retryAtNanos = 0L
    private var scheduled = false
    private var retryInProgress = false

    fun shouldStartWriter(sourceChanged: Boolean, workerMissing: Boolean, failurePresent: Boolean, nowNanos: Long): Boolean =
        sourceChanged || (workerMissing && (!failurePresent || isDueAt(nowNanos)))

    fun isDueAt(nowNanos: Long): Boolean = scheduled && nowNanos - retryAtNanos >= 0L

    fun beginRetryAt(nowNanos: Long): Boolean {
        if (retryInProgress || !isDueAt(nowNanos)) return false
        retryInProgress = true
        return true
    }

    fun failedAt(nowNanos: Long) {
        retryAtNanos = nowNanos + nextDelayNanos
        scheduled = true
        nextDelayNanos = if (nextDelayNanos >= MAX_DELAY_NANOS / 2L) {
            MAX_DELAY_NANOS
        } else {
            nextDelayNanos * 2L
        }
    }

    fun recovered() {
        nextDelayNanos = INITIAL_DELAY_NANOS
        retryAtNanos = 0L
        scheduled = false
        retryInProgress = false
    }

    fun finishRetry() {
        retryInProgress = false
    }

    companion object {
        @JvmField
        val INITIAL_DELAY_NANOS: Long = TimeUnit.SECONDS.toNanos(5)

        @JvmField
        val MAX_DELAY_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
