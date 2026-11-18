package geiler.addons.client.dungeon.terminal

import geiler.addons.client.macros.record.RecordLines
import geiler.addons.client.macros.record.RecordingStore
import java.nio.file.Path
import java.util.Locale

/**
 * One hand-solved terminal as the Terminal Solver's "Analyse Terms" setting records it: the cursor path, every click and the
 * confirmations, as a single JSON line written when the terminal closes. It only reads what the solver already knows and has no
 * Minecraft dependency, so it is checked offline. `tools/analyze_terminals.py` turns the file into target-selection and timing
 * numbers for Auto Terms. A slot of -1 means "none" (not over a slot, no expected slot). Cursor positions are in GUI units,
 * the same units as the [Grid] origin, so `(x - originX) / scale` is a position in 24-wide slot boxes.
 */
class TerminalRunRecorder @JvmOverloads constructor(
    private val type: String,
    private val title: String,
    private val src: String,
    private val number: Int,
    private val settings: Settings,
    private val clock: () -> Long = System::nanoTime,
) {
    /** What maps window pixels to slots and what changes how a person plays. */
    class Settings(
        val guiScale: Int, val windowWidth: Int, val windowHeight: Int, val fps: Int,
        val aimAssist: Boolean, val termSize: Float, val protectionMillis: Int,
    )

    /** The slot grid in GUI units; [firstRow]/[firstCol] are where it starts inside the container (9 columns). */
    class Grid(
        val originX: Float, val originY: Float, val scale: Float, val slot: Int, val gap: Int,
        val rows: Int, val cols: Int, val firstRow: Int, val firstCol: Int,
    )

    private val start = clock()
    private var grid: Grid? = null
    private var board: List<Int> = emptyList()
    private var boardMillis = -1L
    private val path = StringBuilder()
    private var pathCount = 0
    private var pathTruncated = false
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private val moves = StringBuilder()
    private var moveCount = 0
    private var movesTruncated = false
    private var rawX = Double.NaN
    private var rawY = Double.NaN
    private val buttons = StringBuilder()
    private var buttonCount = 0
    private val targets = StringBuilder()
    private var targetCount = 0
    private var lastTarget = Int.MIN_VALUE
    private var lastLit = true
    private val clickLines = StringBuilder()
    private var clickCount = 0
    private var autoClicks = 0
    private var clicksTruncated = false
    private val confirms = StringBuilder()
    private var confirmCount = 0

    /** The number of clicks seen, accepted or blocked. */
    val clicks: Int get() = clickCount

    private fun elapsed(): Long = (clock() - start) / 1_000_000L

    /** The first geometry wins; later frames only repeat it. */
    fun grid(value: Grid) {
        if (grid == null) grid = value
    }

    /**
     * The solution as the terminal fills in (the screen opens before the slots arrive, and they arrive one by one): the last
     * one seen before the first click is the whole board, and the time of the first non-empty one is when the person could
     * first see it.
     */
    fun board(solution: List<Int>) {
        if (clickCount > 0 || solution.isEmpty()) return
        board = solution.toList()
        if (boardMillis < 0L) boardMillis = elapsed()
    }

    /**
     * One raw mouse event (the mouse's own rate, about 1000 Hz) in GUI units. Once these arrive the per-frame samples are
     * dropped: they are the same path at a tenth of the resolution.
     */
    fun move(x: Double, y: Double) {
        if (x == rawX && y == rawY) return
        rawX = x
        rawY = y
        if (moveCount >= MAX_MOVES) {
            movesTruncated = true
            return
        }
        if (moveCount > 0) moves.append(',')
        moves.append(String.format(Locale.ROOT, "[%.2f,%.1f,%.1f]", (clock() - start) / 1_000_000.0, x, y))
        moveCount++
    }

    /** A mouse button going down or up (the press length and the rhythm of a spam click). */
    fun button(button: Int, down: Boolean) {
        if (buttonCount >= MAX_BUTTONS) return
        if (buttonCount > 0) buttons.append(',')
        buttons.append(String.format(Locale.ROOT, "[%.2f,%d,%d]", (clock() - start) / 1_000_000.0, button, if (down) 1 else 0))
        buttonCount++
    }

    /**
     * The slot the solver wants clicked next (-1 for none) and whether it can be clicked yet ([lit]; a melody button is known
     * from the pointer's row before it lights). Only changes are kept: this shows where a person aims ahead of time.
     */
    @JvmOverloads
    fun target(slot: Int, lit: Boolean = true) {
        if ((slot == lastTarget && lit == lastLit) || targetCount >= MAX_TARGETS) return
        lastTarget = slot
        lastLit = lit
        if (targetCount > 0) targets.append(',')
        targets.append(String.format(Locale.ROOT, "[%.2f,%d,%d]", (clock() - start) / 1_000_000.0, slot, if (lit) 1 else 0))
        targetCount++
    }

    /** The number of raw mouse events kept. */
    val rawMoves: Int get() = moveCount

    /** The cursor in GUI units; an unchanged position is not sampled again. */
    fun cursor(x: Double, y: Double) {
        if (moveCount > 0) return
        if (x == lastX && y == lastY) return
        lastX = x
        lastY = y
        if (pathCount >= MAX_PATH) {
            pathTruncated = true
            return
        }
        if (pathCount > 0) path.append(',')
        path.append(String.format(Locale.ROOT, "[%d,%.1f,%.1f]", elapsed(), x, y))
        pathCount++
    }

    fun click(
        slot: Int, hover: Int, x: Double, y: Double, pressed: Int, sent: Int, accepted: Boolean,
        left: Int, expected: Int, source: String,
    ) {
        clickCount++
        if (source == "auto") autoClicks++
        if (clickCount > MAX_CLICKS) {
            clicksTruncated = true
            return
        }
        if (clickLines.isNotEmpty()) clickLines.append(',')
        clickLines.append(
            String.format(
                Locale.ROOT,
                "{\"t\":%d,\"slot\":%d,\"hover\":%d,\"x\":%.1f,\"y\":%.1f,\"btn\":%d,\"sent\":%d,\"ok\":%b,\"left\":%d,\"exp\":%d,\"src\":%s}",
                elapsed(), slot, hover, x, y, pressed, sent, accepted, left, expected, RecordLines.quote(source),
            ),
        )
    }

    /** A server (or simulator) slot update for [slot]. */
    fun confirm(slot: Int) {
        if (confirmCount >= MAX_CONFIRMS) return
        if (confirmCount > 0) confirms.append(',')
        confirms.append(String.format(Locale.ROOT, "[%d,%d]", elapsed(), slot))
        confirmCount++
    }

    /** The run as one JSON line, or null when no click was made (nothing worth keeping). */
    fun finish(reason: String, ticksOpened: Int): String? {
        if (clickCount == 0) return null
        val g = grid
        val sb = StringBuilder(256 + path.length + clickLines.length + moves.length)
        sb.append("{\"k\":\"run\",\"n\":").append(number)
            .append(",\"type\":").append(RecordLines.quote(type))
            .append(",\"title\":").append(RecordLines.quote(title))
            .append(",\"src\":").append(RecordLines.quote(src))
            .append(",\"guiScale\":").append(settings.guiScale)
            .append(",\"win\":[").append(settings.windowWidth).append(',').append(settings.windowHeight).append(']')
            .append(",\"fps\":").append(settings.fps)
            .append(",\"aim\":").append(settings.aimAssist)
            .append(",\"auto\":").append(autoClicks > 0)
            .append(String.format(Locale.ROOT, ",\"termSize\":%.2f", settings.termSize))
            .append(",\"protMs\":").append(settings.protectionMillis)
        if (g != null) {
            sb.append(
                String.format(
                    Locale.ROOT,
                    ",\"grid\":{\"ox\":%.2f,\"oy\":%.2f,\"scale\":%.3f,\"slot\":%d,\"gap\":%d,\"rows\":%d,\"cols\":%d,\"row0\":%d,\"col0\":%d}",
                    g.originX, g.originY, g.scale, g.slot, g.gap, g.rows, g.cols, g.firstRow, g.firstCol,
                ),
            )
        }
        sb.append(",\"boardMs\":").append(boardMillis)
            .append(",\"board\":").append(board.joinToString(",", "[", "]"))
            .append(",\"path\":[").append(path).append(']')
            .append(",\"pathTruncated\":").append(pathTruncated)
            .append(",\"moves\":[").append(moves).append(']')
            .append(",\"movesTruncated\":").append(movesTruncated)
            .append(",\"buttons\":[").append(buttons).append(']')
            .append(",\"targets\":[").append(targets).append(']')
            .append(",\"clicks\":[").append(clickLines).append(']')
            .append(",\"clicksTruncated\":").append(clicksTruncated)
            .append(",\"confirms\":[").append(confirms).append(']')
            .append(",\"end\":{\"reason\":").append(RecordLines.quote(reason))
            .append(",\"ms\":").append(elapsed())
            .append(",\"ticks\":").append(ticksOpened).append("}}")
        return sb.toString()
    }

    private companion object {
        const val MAX_PATH = 8_000
        const val MAX_MOVES = 30_000
        const val MAX_BUTTONS = 2_000
        const val MAX_TARGETS = 2_000
        const val MAX_CLICKS = 300
        const val MAX_CONFIRMS = 300
    }
}

/** The bounded session file the finished runs go to, with the per-type run counter shown in chat. */
class TerminalAnalysisLog(dir: Path, session: String, maxBytes: Long) : AutoCloseable {
    private val store = RecordingStore(dir, session, maxBytes, "{\"k\":\"meta\",\"t\":0,\"format\":1,\"session\":${RecordLines.quote(session)}}")
    private val counts = HashMap<String, Int>()
    private var written = 0

    /** The number the next run of [type] will have; it only moves on once a run is written. */
    fun nextNumber(type: String): Int = (counts[type] ?: 0) + 1

    fun write(type: String, line: String?) {
        if (line == null) return
        store.append(line)
        store.flush()
        counts[type] = nextNumber(type)
        written++
    }

    fun runs(): Int = written

    fun bytes(): Long = store.bytes()

    fun directory(): Path = store.directory()

    override fun close() = store.close()
}
