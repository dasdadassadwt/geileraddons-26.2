package geiler.addons.client.macros.module

import geiler.addons.client.macros.record.HumanRecorder
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft

/**
 * Records how you play by hand - the view every frame, keys, position, the mobs of the Area Killer's kill list and where they
 * are, every click and kill - into a local file, so the Rotation Engine and the target planner can be tuned against a real
 * player. Turn it on, spawn some mobs, kill them yourself, turn it off. `/ga record start|stop|status` does the same. It reads
 * the client's own state only; nothing is sent anywhere and the game is not changed. The file is at most 20 MB.
 */
class HumanRecorderModule private constructor() : Module(
    "Human Recorder",
    "Records how you play by hand into a local file (view, keys, position, nearby kill-list mobs, clicks, kills), so the " +
        "rotation and targeting can be tuned against a real player. Turn it on, kill some mobs yourself, turn it off. " +
        "Nothing is sent anywhere; the file is capped at 20 MB. /ga record does the same.",
    Category.ENGINE,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.ENGINE, { name() })

    init {
        HumanRecorder.diagnostics = { diagnostics.event(it) }
    }

    /** `/ga record start`: starts a recording, and starts a new one when one is running. */
    fun begin() {
        if (isEnabled()) HumanRecorder.start() else setEnabled(true)
    }

    override fun onEnable() {
        diagnostics.enable("limitMB=20 localOnly=true")
        // A saved "on" from the last session has nothing to record yet; the tick turns it off again.
        if (Minecraft.getInstance().player == null) return
        HumanRecorder.start()
    }

    override fun onDisable() {
        HumanRecorder.stop("by user")
        diagnostics.disable()
    }

    override fun isActive(): Boolean = HumanRecorder.isRecording()

    /** Called every client tick: records the tick, and follows the recorder's state. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        HumanRecorder.tick(Minecraft.getInstance())
        if (!HumanRecorder.isRecording()) {
            // Stopped by itself - the world was left, or the file could not be written: the switch shows it.
            setEnabled(false)
            return
        }
        diagnostics.status { suppressed -> HumanRecorder.status() + " suppressedEvents=$suppressed" }
    }

    companion object {
        @JvmField
        val INSTANCE = HumanRecorderModule()
    }
}
