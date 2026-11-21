package geiler.addons.client.config

import geiler.addons.client.module.Category
import java.lang.management.ManagementFactory

/**
 * Always-on lag-spike watchdog, written to `Dev/Frame Spikes.log`. The client tick beats a heartbeat; a daemon thread that
 * sees the beat stop for [THRESHOLD_MS] reads the game thread's stack (a few samples while the stall lasts) and writes the
 * frames, with every `geiler.addons` frame listed, so a spike names the code that was running instead of guessing at a
 * module. The end line gives the full stall length and how much of it the garbage collector accounts for. No module is
 * wrapped, nothing is timed on the hot path, and a session writes at most [MAX_STALLS] stalls.
 */
object SpikeWatch {
    const val THRESHOLD_MS = 150L
    private const val POLL_MS = 25L
    private const val MAX_STALLS = 200
    private const val MAX_SAMPLES = 3
    private const val TOP_FRAMES = 10
    private const val MOD_FRAMES = 8
    private const val MAX_LINE = 900
    private const val MOD_PREFIX = "geiler.addons"
    private const val MODULE = "Frame Spikes"

    @Volatile
    private var beatNanos = System.nanoTime()

    @Volatile
    private var tickCount = 0L

    private var started = false

    /** Starts the watchdog for [gameThread]; later calls do nothing. */
    @Synchronized
    fun start(gameThread: Thread) {
        if (started) return
        started = true
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.DEV, MODULE, true)
        write("START thresholdMs=$THRESHOLD_MS pollMs=$POLL_MS maxStalls=$MAX_STALLS thread=${gameThread.name}")
        Thread({ watch(gameThread) }, "geileraddons-spike-watch").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY - 1 }.start()
    }

    /** Called from the client tick on the game thread. */
    @JvmStatic
    fun beat(tick: Long) {
        tickCount = tick
        beatNanos = System.nanoTime()
    }

    private fun watch(gameThread: Thread) {
        var openBeat = 0L
        var samples = 0
        var stalls = 0
        var gcBase = 0L
        var suppressed = 0
        var muted = false
        while (true) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val beat = beatNanos
            val lateMs = (System.nanoTime() - beat) / 1_000_000
            if (openBeat != 0L && beat != openBeat) {
                if (!muted) write("STALL_END totalMs=${(beat - openBeat) / 1_000_000} gcMs=${gcMillis() - gcBase} samples=$samples suppressedStalls=$suppressed")
                openBeat = 0L
            }
            if (lateMs < THRESHOLD_MS) continue
            if (openBeat == 0L) {
                openBeat = beat
                muted = stalls >= MAX_STALLS
                if (muted) suppressed++ else {
                    stalls++
                    samples = 0
                    gcBase = gcMillis()
                }
            }
            if (!muted && samples < MAX_SAMPLES && lateMs >= THRESHOLD_MS * (samples + 1)) {
                samples++
                write("STALL lateMs=$lateMs sample=$samples ${describe(gameThread.stackTrace)}")
            }
        }
    }

    /** The top frames plus every mod frame of [frames], shortened to `Class.method:line`, within [MAX_LINE] characters. */
    @JvmStatic
    fun describe(frames: Array<StackTraceElement>): String {
        if (frames.isEmpty()) return "stack=empty"
        val top = frames.take(TOP_FRAMES)
        val mod = frames.drop(TOP_FRAMES).filter { it.className.startsWith(MOD_PREFIX) }.take(MOD_FRAMES)
        val text = "top=" + top.joinToString(" < ") { short(it) } + (if (mod.isEmpty()) "" else " mod=" + mod.joinToString(" < ") { short(it) })
        return text.take(MAX_LINE)
    }

    private fun short(frame: StackTraceElement): String = frame.className.substringAfterLast('.') + "." + frame.methodName + ":" + frame.lineNumber

    private fun gcMillis(): Long = try {
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0L, it.collectionTime) }
    } catch (_: RuntimeException) {
        0L
    }

    private fun write(line: String) = GeilerAddonsLog.write(Category.DEV, MODULE, tickCount, line)
}
