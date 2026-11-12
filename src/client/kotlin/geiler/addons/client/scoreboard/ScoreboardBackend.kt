package geiler.addons.client.scoreboard

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ScoreboardModule
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Client-thread owner of the single layout and the immutable live sidebar snapshot. */
object ScoreboardBackend {
    private const val DIAGNOSTIC_SAMPLE_INTERVAL_TICKS = 20L
    private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
    private const val MAX_COALESCED_DIAGNOSTIC_CHANGES = 999_999
    private var layout = ScoreboardLayout()
    @Volatile
    private var snapshot = ScoreboardSnapshot.empty()
    private var diagnosticTick = 0L
    private var lastDiagnosticWriteTick = Long.MIN_VALUE
    private var lastObservedDiagnosticStatus: String? = null
    private var lastReportedDiagnosticStatus: String? = null
    private var coalescedDiagnosticChanges = 0
    private val STORE_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons-scoreboard-store").apply { isDaemon = true }
    }
    private val QUEUED_JSON = AtomicReference<String?>(null)
    private val PREPARED_FLUSH_JSON = AtomicReference<String?>(null)
    private val SAVE_WORKER_QUEUED = AtomicBoolean()

    @JvmStatic
    fun initialize() {
        layout = ScoreboardLayoutStore.load()
        ScoreboardFontService.initialize()
    }

    /** Reloads a validated profile layout without replacing the active layout on disk/read errors. */
    @JvmStatic
    fun reloadLayout(): Boolean {
        val minecraft: Minecraft? = Minecraft.getInstance()
        if (minecraft == null || !minecraft.isSameThread()) return false
        val result = ScoreboardLayoutState.reload(layout, ScoreboardLayoutStore.loadForReload())
        if (!result.accepted) return false
        layout = result.layout
        return true
    }

    /** Applies the bundled layout defaults. Persistence is reported by the config-transfer flush. */
    @JvmStatic
    fun resetToDefaults(): Boolean {
        val minecraft: Minecraft? = Minecraft.getInstance()
        if (minecraft == null || !minecraft.isSameThread()) return false
        layout = ScoreboardLayoutState.reset(layout, true)
        return save()
    }

    /** Called once per client tick by central wiring, before HUD rendering. */
    @JvmStatic
    fun tick(minecraft: Minecraft?) {
        diagnosticTick = if (diagnosticTick == Long.MAX_VALUE) 0L else diagnosticTick + 1L
        if (!ScoreboardModule.INSTANCE.isEnabled()) {
            snapshot = ScoreboardSnapshot.empty()
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, ScoreboardModule.INSTANCE.name(), false)
            resetDiagnostics()
            return
        }
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, ScoreboardModule.INSTANCE.name(), true)
        snapshot = if (minecraft == null) ScoreboardSnapshot.empty() else ScoreboardSnapshot.capture(minecraft)
        updateDiagnostics()
    }

    private fun updateDiagnostics() {
        val status = diagnosticStatus()
        val ticksSinceReport = if (lastDiagnosticWriteTick == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            diagnosticTick - lastDiagnosticWriteTick
        }
        val observedChanged = lastObservedDiagnosticStatus != null && status != lastObservedDiagnosticStatus
        if (observedChanged && ticksSinceReport < DIAGNOSTIC_SAMPLE_INTERVAL_TICKS
            && coalescedDiagnosticChanges < MAX_COALESCED_DIAGNOSTIC_CHANGES
        ) {
            coalescedDiagnosticChanges++
        }
        lastObservedDiagnosticStatus = status

        val first = lastReportedDiagnosticStatus == null
        val changed = first || status != lastReportedDiagnosticStatus
        val heartbeat = !first && ticksSinceReport >= DIAGNOSTIC_HEARTBEAT_TICKS
        val sampleDue = ticksSinceReport >= DIAGNOSTIC_SAMPLE_INTERVAL_TICKS
            && (changed || coalescedDiagnosticChanges > 0)
        if (!first && !heartbeat && !sampleDue) return

        GeilerAddonsLog.write(
            Category.VISUAL,
            ScoreboardModule.INSTANCE.name(),
            diagnosticTick,
            "STATUS $status changed=$changed heartbeat=$heartbeat" +
                " sampleTicks=$DIAGNOSTIC_SAMPLE_INTERVAL_TICKS" +
                " heartbeatTicks=$DIAGNOSTIC_HEARTBEAT_TICKS" +
                " coalescedChanges=$coalescedDiagnosticChanges",
        )
        lastReportedDiagnosticStatus = status
        lastDiagnosticWriteTick = diagnosticTick
        coalescedDiagnosticChanges = 0
    }

    private fun diagnosticStatus(): String {
        val current = snapshot
        val selectedFont = effectiveFontId(layout.fontId())
        return "snapshotActive=${current.active()}" +
            " snapshotEntries=${minOf(15, current.entries().size)}" +
            " layoutEnabledLines=${enabledLineCount()}" +
            " visibleRows=${visibleRowCount(current)}" +
            " selectedFont=$selectedFont" +
            " fallbackTarget=${fallbackTarget(selectedFont)}"
    }

    private fun enabledLineCount(): Int {
        var count = 0
        for (line in layout.lines()) {
            if (line.enabled()) count++
        }
        return count
    }

    /** Counts rows using the same visibility rules as the shared HUD and editor renderer. */
    private fun visibleRowCount(current: ScoreboardSnapshot): Int {
        var count = 0
        for (line in layout.lines()) {
            if (!line.enabled()) continue
            when (line.type()) {
                ScoreboardLine.Type.OBJECTIVE_TITLE -> if (current.active()) count++
                ScoreboardLine.Type.SCORE_ENTRY ->
                    if (current.active() && line.sourceIndex() < current.entries().size) count++
                ScoreboardLine.Type.CUSTOM_TEXT, ScoreboardLine.Type.SPACER -> count++
                null -> Unit
            }
        }
        return count
    }

    private fun effectiveFontId(configuredFontId: String?): String {
        val identifier = Identifier.tryParse(configuredFontId.orEmpty())
        return identifier?.toString() ?: "minecraft:default"
    }

    private fun fallbackTarget(fontId: String): String {
        if (fontId == "minecraft:default" || fontId == "minecraft:uniform") return "none"
        if (fontId.startsWith("geileraddons:scoreboard/") || fontId.startsWith("geileraddons_user_fonts:scoreboard/")) {
            return "minecraft:default"
        }
        return "unknown"
    }

    private fun resetDiagnostics() {
        lastDiagnosticWriteTick = Long.MIN_VALUE
        lastObservedDiagnosticStatus = null
        lastReportedDiagnosticStatus = null
        coalescedDiagnosticChanges = 0
    }

    @JvmStatic
    fun layout(): ScoreboardLayout = layout

    @JvmStatic
    fun snapshot(): ScoreboardSnapshot = snapshot

    @JvmStatic
    fun shouldReplaceVanillaSidebar(): Boolean = ScoreboardModule.INSTANCE.isEnabled() && snapshot.active()

    @JvmStatic
    fun save(): Boolean {
        val json = ScoreboardLayoutCodec.encode(layout)
        QUEUED_JSON.set(json)
        return scheduleSaveWorker()
    }

    /** Captures mutable layout state on the client thread before a config worker flushes files. */
    @JvmStatic
    fun prepareFlush(): Boolean {
        val minecraft: Minecraft? = Minecraft.getInstance()
        if (minecraft == null || !minecraft.isSameThread()) return false
        PREPARED_FLUSH_JSON.set(ScoreboardLayoutCodec.encode(layout))
        return true
    }

    /** Writes the client-thread snapshot after all earlier coalesced saves on the store executor. */
    @JvmStatic
    fun flushPrepared(): Boolean {
        val json = PREPARED_FLUSH_JSON.getAndSet(null) ?: return false
        try {
            val write: Future<Boolean> = STORE_EXECUTOR.submit<Boolean> { ScoreboardLayoutStore.saveJson(json) }
            val saved = write.get()
            if (!saved) PREPARED_FLUSH_JSON.compareAndSet(null, json)
            return saved
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            PREPARED_FLUSH_JSON.compareAndSet(null, json)
            return false
        } catch (_: ExecutionException) {
            PREPARED_FLUSH_JSON.compareAndSet(null, json)
            return false
        } catch (_: RuntimeException) {
            PREPARED_FLUSH_JSON.compareAndSet(null, json)
            return false
        }
    }

    /** Persists the last editor state before the editor closes. Intended for client-thread UI callbacks. */
    @JvmStatic
    fun flush(): Boolean {
        val json = ScoreboardLayoutCodec.encode(layout)
        try {
            // This runs after any queued/coalesced save on the same executor and writes the final snapshot.
            val write: Future<Boolean> = STORE_EXECUTOR.submit<Boolean> { ScoreboardLayoutStore.saveJson(json) }
            return write.get()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        } catch (_: ExecutionException) {
            return false
        } catch (_: RuntimeException) {
            return false
        }
    }

    private fun scheduleSaveWorker(): Boolean {
        if (!SAVE_WORKER_QUEUED.compareAndSet(false, true)) return true
        try {
            STORE_EXECUTOR.execute {
                try {
                    while (true) {
                        val json = QUEUED_JSON.getAndSet(null) ?: break
                        ScoreboardLayoutStore.saveJson(json)
                    }
                } finally {
                    SAVE_WORKER_QUEUED.set(false)
                    // A save arriving as the worker drains must not be stranded.
                    if (QUEUED_JSON.get() != null) scheduleSaveWorker()
                }
            }
            return true
        } catch (_: RuntimeException) {
            SAVE_WORKER_QUEUED.set(false)
            return false
        }
    }

    @JvmStatic
    fun updateLayout(updated: ScoreboardLayout?) {
        if (updated == null) return
        updated.normalize()
        layout = updated
        save()
    }
}
