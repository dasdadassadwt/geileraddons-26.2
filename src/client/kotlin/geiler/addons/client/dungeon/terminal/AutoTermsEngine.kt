package geiler.addons.client.dungeon.terminal

import java.util.ArrayDeque
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * The numbers Auto Terms plays with. The timing, the Fitts fit, the dwell, the landing offset, the nearest-slot rate and the
 * overshoot rate are from 34 hand-solved simulator runs recorded with Terminal Solver's Analyse Terms (2026-11-18). The
 * movement shape (primary accuracy, corrections, tremor, drift, click shake, burst gap, finish-tile chance) are typical human
 * values that the Cursor Tracker recordings are meant to refit. Times are milliseconds; fractions are 0..1; distances are slot
 * widths unless the name says px (physical screen pixels).
 */
class AutoTermsConfig {
    /** Board appearing to the first click, median and lognormal sigma. */
    var firstClickMs = 450.0
    var firstClickSpread = 0.25

    /** Board appearing to the cursor starting to move (the recording: about 160, 120..220). */
    var cursorStartMs = 160.0

    /** Gap between clicks = (base + perBit * log2(distance / slotWidth + 1)) with lognormal noise, the dwell included. */
    var baseMs = 130.0
    var perBitMs = 70.0
    var spread = 0.45
    var minGapMs = 60.0

    /** Time the cursor rests on the slot before the click (median; the recording: 55). */
    var dwellMs = 55.0

    /** Lit melody button to click. Not measured (one unfinished melody run): a typical visual reaction. */
    var melodyReactionMs = 220.0

    /** Multiplies every time above and below; 1 is the recorded speed, 0.5 is twice as fast. */
    var timeScale = 1.0

    /** Where in the slot the click lands: standard deviation (slot widths) and vertical bias (negative is above the centre). */
    var landingSpread = 0.22
    var landingY = -0.07

    /** Chance a long move runs past the slot and comes back, and how far past (slot widths). */
    var overshootChance = 0.12
    var overshootSize = 0.5

    /** Chance a move longer than a slot first misses and is then corrected, and the miss as a share of the distance (sd). */
    var correctionChance = 0.55
    var primaryAccuracy = 0.06

    /** Sideways bow of a move as a share of its length. */
    var pathCurve = 0.06

    /** Hand tremor (8-13 Hz) in physical pixels, and the slow drift while resting on a slot in slot widths. */
    var tremorPx = 0.4
    var driftSlots = 0.03

    /** Spread (physical pixels) of where each click of a burst lands around the first, and the gap between burst clicks. */
    var shakePx = 1.5
    var burstMs = 85.0
    var burstSpread = 0.3

    /** Rubix: chance a tile is clicked as many times as it needs in one go instead of one click per visit. */
    var finishTileChance = 0.85

    /** False keeps the cursor where it is and only clicks (the timing is the same). */
    var moveCursor = true

    /** Free-order terminals: chance the next slot is the nearest to the cursor; else the second or third nearest. */
    var nearestChance = 0.70

    /** Free-order terminals: chance the first slot is the first in reading order (else the nearest to the cursor). */
    var firstReadingChance = 0.45
}

/** A slot's box in GUI units. */
class AutoBox(val cx: Double, val cy: Double, val size: Double)

/**
 * What the engine sees each frame. [remaining] is what is still to click (the solver's solution, with clicks already sent but
 * not yet confirmed taken off); [button] is the mouse button that slot accepts, or -1 when it cannot be clicked now; [pixel] is
 * one physical screen pixel in GUI units (0 when unknown), so cursor positions can be whole pixels and tremor a real size.
 */
class AutoTermsView @JvmOverloads constructor(
    val type: TerminalType,
    val remaining: List<Int>,
    val box: (Int) -> AutoBox?,
    val button: (Int) -> Int,
    val protectedNow: Boolean,
    val cursorX: Double,
    val cursorY: Double,
    val pixel: Double = 0.0,
)

/** What to do this frame: the cursor spot to put the mouse on (when [move]) and a click to send ([clickSlot] is -1 for none). */
class AutoStep(val x: Double, val y: Double, val move: Boolean, val clickSlot: Int, val clickButton: Int) {
    val clicks: Boolean get() = clickSlot >= 0
}

/**
 * Plays one terminal like a person: a reaction delay, then for each click a slot chosen the way the recordings chose (nearest to
 * the cursor most of the time, reading order to start), a cursor move made of a fast primary movement and often a correction
 * (now and then an overshoot), tremor and drift, a short rest on the slot and a click at a random spot inside it. A rubix tile
 * is clicked as often as it needs in one burst with a little shake, and the melody cursor waits on the right button before it
 * lights. It never clicks a slot the solver would refuse, never before the first-click protection is over and never twice for
 * one solution entry. No Minecraft dependency; fed once per rendered frame.
 */
class AutoTermsEngine @JvmOverloads constructor(private val config: AutoTermsConfig, private val random: Random = Random()) {
    /** One planned click: [xs]/[ys] are the path points (the first is where the move starts), [times] when each is reached. */
    private class Plan(
        val slot: Int, val button: Int, val how: String,
        val xs: DoubleArray, val ys: DoubleArray, val times: LongArray, val bows: DoubleArray, val gammas: DoubleArray,
        var clickAt: Long, var armed: Boolean,
    ) {
        val lx: Double get() = xs[xs.size - 1]
        val ly: Double get() = ys[ys.size - 1]
        val moveEnd: Long get() = times[times.size - 1]
    }

    private var plan: Plan? = null
    private var started = false
    private var curX = 0.0
    private var curY = 0.0
    private var outX = 0.0
    private var outY = 0.0
    private var anchor = -1L
    private var lastClickAt = -1L
    private var litSince = -1L
    private var clicksDone = 0
    private var paused = false
    private var burstSlot = -1
    private var burstKeep = false
    private var burstX = 0.0
    private var burstY = 0.0
    private val phases = DoubleArray(PHASES)
    private val events = ArrayDeque<String>()

    init {
        reseedHand()
    }

    val isPaused: Boolean get() = paused
    val clicks: Int get() = clicksDone

    /** A new terminal: forget the plan, the position, the burst and the pause. */
    fun reset() {
        plan = null
        started = false
        anchor = -1L
        lastClickAt = -1L
        litSince = -1L
        clicksDone = 0
        paused = false
        burstSlot = -1
        events.clear()
        reseedHand()
    }

    /** Stops moving and clicking until [reset]. */
    fun pause() {
        paused = true
        plan = null
    }

    /** The oldest diagnostic line not yet read, or null. */
    fun pollEvent(): String? = events.pollFirst()

    private fun note(line: String) {
        if (events.size >= MAX_EVENTS) events.pollFirst()
        events.addLast(line)
    }

    private fun reseedHand() {
        for (i in phases.indices) phases[i] = random.nextDouble() * 2.0 * PI
    }

    fun step(view: AutoTermsView, nowNanos: Long): AutoStep {
        val now = nowNanos
        if (!started) {
            curX = view.cursorX
            curY = view.cursorY
            outX = curX
            outY = curY
            started = true
        }
        if (paused) return hold()
        val melody = view.type == TerminalType.MELODY
        val candidates = candidates(view)
        val aim = if (melody) melodyAim(view) else -1
        val target = if (melody) candidates.firstOrNull() ?: aim else -1
        if (candidates.isEmpty() && target < 0) {
            plan = null
            anchor = -1L
            litSince = -1L
            return hold()
        }
        if (anchor < 0L) anchor = now
        if (melody) {
            if (candidates.isEmpty()) litSince = -1L else if (litSince < 0L) litSince = now
        }
        if (burstSlot >= 0 && burstSlot !in candidates) burstSlot = -1

        var current = plan
        if (current != null && !valid(current, candidates, view, target)) {
            note("replan slot=${current.slot} reason=target_gone")
            current = null
            plan = null
        }
        if (current == null) {
            if (hypot(view.cursorX - outX, view.cursorY - outY) > USER_MOVED) {
                curX = view.cursorX
                curY = view.cursorY
                outX = curX
                outY = curY
            }
            val first = clicksDone == 0 || lastClickAt < 0L
            current = if (melody) makePlan(view, target, "forced", now, first, true) else {
                val (slot, how) = choose(view, candidates, first)
                makePlan(view, slot, how, now, first, false)
            }
            plan = current
            if (view.type == TerminalType.RUBIX && current.how != "burst") startBurst(view, current)
        }
        if (melody && !current.armed && candidates.isNotEmpty() && candidates[0] == current.slot) arm(current, now)

        val box = view.box(current.slot)
        val size = box?.size ?: 40.0
        val base = position(current, now)
        curX = base.first
        curY = base.second
        val hand = hand(now, view.pixel, size)
        if (now >= current.clickAt && !view.protectedNow && view.button(current.slot) >= 0) {
            var cx = curX + hand.first
            var cy = curY + hand.second
            if (box != null) {
                cx = cx.coerceIn(box.cx - CLICK_LIMIT * size, box.cx + CLICK_LIMIT * size)
                cy = cy.coerceIn(box.cy - CLICK_LIMIT * size, box.cy + CLICK_LIMIT * size)
            }
            outX = quantize(cx, view.pixel)
            outY = quantize(cy, view.pixel)
            val slot = current.slot
            val button = view.button(slot)
            curX = current.lx
            curY = current.ly
            lastClickAt = now
            clicksDone++
            plan = null
            anchor = -1L
            return AutoStep(outX, outY, config.moveCursor, slot, button)
        }
        outX = quantize(curX + hand.first, view.pixel)
        outY = quantize(curY + hand.second, view.pixel)
        return AutoStep(outX, outY, config.moveCursor, -1, -1)
    }

    private fun hold() = AutoStep(outX, outY, false, -1, -1)

    private fun candidates(view: AutoTermsView): List<Int> {
        val list = when (view.type) {
            TerminalType.NUMBERS -> listOfNotNull(view.remaining.firstOrNull())
            TerminalType.MELODY -> listOfNotNull(view.remaining.getOrNull(MELODY_LIT_INDEX))
            else -> view.remaining.distinct()
        }
        return list.filter { view.button(it) >= 0 && view.box(it) != null }
    }

    /** The melody button the pointer's row leads to (the lit one is the same), even before it can be clicked; -1 when unknown. */
    private fun melodyAim(view: AutoTermsView): Int {
        val pane = view.remaining.firstOrNull() ?: return -1
        if (pane < 0) return -1
        val button = (pane / 9) * 9 + MELODY_BUTTON_COLUMN
        return if (button in MELODY_BUTTONS && view.box(button) != null) button else -1
    }

    private fun valid(plan: Plan, candidates: List<Int>, view: AutoTermsView, target: Int): Boolean =
        if (view.type == TerminalType.MELODY) plan.slot == target
        else plan.slot in candidates && view.button(plan.slot) == plan.button

    private fun gauss(): Double = random.nextGaussian().coerceIn(-2.5, 2.5)

    private fun lognormal(median: Double, sigma: Double): Double = median * exp(sigma * gauss())

    private fun choose(view: AutoTermsView, candidates: List<Int>, first: Boolean): Pair<Int, String> {
        if (view.type == TerminalType.NUMBERS) return candidates[0] to "forced"
        if (view.type == TerminalType.RUBIX && burstSlot >= 0 && burstKeep && burstSlot in candidates) return burstSlot to "burst"
        if (candidates.size == 1) return candidates[0] to "only"
        val ranked = candidates.sortedWith(compareBy({ distance(view, it) }, { it }))
        if (first && random.nextDouble() < config.firstReadingChance) return candidates.min() to "reading"
        if (random.nextDouble() < config.nearestChance) return ranked[0] to "nearest"
        return ranked[1 + random.nextInt(min(2, ranked.size - 1))] to "alt"
    }

    private fun distance(view: AutoTermsView, slot: Int): Double {
        val box = view.box(slot) ?: return Double.MAX_VALUE
        return hypot(box.cx - curX, box.cy - curY)
    }

    /** A rubix tile that needs more than one click starts a burst: the rest of its clicks follow on the same spot. */
    private fun startBurst(view: AutoTermsView, plan: Plan) {
        if (view.remaining.count { it == plan.slot } < 2) return
        burstSlot = plan.slot
        burstKeep = random.nextDouble() < config.finishTileChance
        burstX = plan.lx
        burstY = plan.ly
    }

    /** The melody button is lit: click it a reaction time later, once the cursor is on it and the last click has cooled. */
    private fun arm(plan: Plan, now: Long) {
        val scale = max(config.timeScale, 0.05)
        val floor = config.minGapMs * min(scale, 1.0)
        val reaction = lognormal(config.melodyReactionMs * scale, config.firstClickSpread).coerceAtLeast(floor) * MS
        var at = max(litSince + reaction.toLong(), plan.moveEnd + SETTLE_NANOS)
        if (lastClickAt >= 0L) at = max(at, lastClickAt + MELODY_COOLDOWN_NANOS)
        plan.clickAt = max(at, now)
        plan.armed = true
    }

    private fun makePlan(view: AutoTermsView, slot: Int, how: String, now: Long, first: Boolean, melody: Boolean): Plan {
        val box = view.box(slot)!!
        val size = box.size
        val pixel = if (view.pixel > 0.0) view.pixel else 0.5
        val burstClick = how == "burst"
        val lx: Double
        val ly: Double
        if (burstClick) {
            lx = (burstX + gauss() * config.shakePx * pixel).coerceIn(box.cx - CLICK_LIMIT * size, box.cx + CLICK_LIMIT * size)
            ly = (burstY + gauss() * config.shakePx * pixel).coerceIn(box.cy - CLICK_LIMIT * size, box.cy + CLICK_LIMIT * size)
        } else if (melody && abs(curX - box.cx) <= INSIDE * size && abs(curY - box.cy) <= INSIDE * size) {
            lx = curX
            ly = curY
        } else {
            lx = box.cx + (gauss() * config.landingSpread * size).coerceIn(-LANDING_LIMIT * size, LANDING_LIMIT * size)
            ly = box.cy + ((gauss() * config.landingSpread * LANDING_Y_SHARE + config.landingY) * size)
                .coerceIn(-LANDING_LIMIT * size, LANDING_LIMIT * size)
        }
        val dx = lx - curX
        val dy = ly - curY
        val length = hypot(dx, dy)
        val bits = ln(length / size + 1.0) / LN2
        val scale = max(config.timeScale, 0.05)
        val floor = config.minGapMs * min(scale, 1.0)

        val t0: Long
        var clickAt: Long
        val moveEnd: Long
        var dwell = 0L
        if (burstClick) {
            val gap = lognormal(config.burstMs * scale, config.burstSpread).coerceIn(BURST_FLOOR_MS * min(scale, 1.0), MAX_GAP_MS) * MS
            t0 = if (lastClickAt >= 0L && now - lastClickAt < gap) lastClickAt else now
            clickAt = t0 + gap.toLong()
            moveEnd = t0 + (gap * BURST_MOVE_SHARE).toLong()
        } else if (melody) {
            val startDelay = if (first) {
                min(lognormal(config.cursorStartMs, START_SPREAD) * scale, MAX_GAP_MS) * MS
            } else 0.0
            val travel = lognormal((config.baseMs + config.perBitMs * bits) * scale, config.spread).coerceIn(floor, MAX_GAP_MS) * MS
            t0 = (if (first) anchor else now) + startDelay.toLong()
            moveEnd = t0 + max(travel.toLong(), MIN_MOVE_NANOS)
            clickAt = Long.MAX_VALUE
        } else {
            dwell = (min(lognormal(config.dwellMs, DWELL_SPREAD) * scale, MAX_DWELL_MS) * MS).toLong()
            if (first) {
                val total = (lognormal(config.firstClickMs, config.firstClickSpread) * scale).coerceAtLeast(floor) * MS
                val startDelay = min(lognormal(config.cursorStartMs, START_SPREAD) * scale * MS, total * 0.5)
                t0 = anchor + startDelay.toLong()
                clickAt = anchor + total.toLong()
            } else {
                val gap = lognormal((config.baseMs + config.perBitMs * bits) * scale, config.spread).coerceIn(floor, MAX_GAP_MS) * MS
                t0 = if (lastClickAt >= 0L && now - lastClickAt < gap) lastClickAt else now
                clickAt = t0 + gap.toLong()
            }
            moveEnd = max(clickAt - dwell, t0 + MIN_MOVE_NANOS)
        }

        // The path: the primary movement, maybe a miss that is then corrected, ending on the landing spot.
        val ux = if (length > 0.0) dx / length else 0.0
        val uy = if (length > 0.0) dy / length else 0.0
        val overshoots = !burstClick && length > size && random.nextDouble() < config.overshootChance
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        px.add(curX)
        py.add(curY)
        var corrections = 0
        if (overshoots) {
            val past = size * config.overshootSize * (0.6 + 0.8 * random.nextDouble())
            val side = gauss() * 0.1 * size
            px.add(lx + ux * past - uy * side)
            py.add(ly + uy * past + ux * side)
            corrections = 1
        } else if (!burstClick && length > size && random.nextDouble() < config.correctionChance) {
            var along = gauss() * config.primaryAccuracy * length - 0.02 * length
            var across = gauss() * config.primaryAccuracy * length * 0.5
            val miss = hypot(along, across)
            val wanted = (if (miss < MIN_MISS * size) MIN_MISS * size else min(miss, 0.5 * length))
            if (miss < 1e-9) {
                along = wanted
                across = 0.0
            } else {
                along *= wanted / miss
                across *= wanted / miss
            }
            px.add(lx + ux * along - uy * across)
            py.add(ly + uy * along + ux * across)
            corrections = 1
            if (wanted > 1.2 * size && random.nextDouble() < SECOND_CORRECTION) {
                px.add(lx + gauss() * 0.2 * wanted)
                py.add(ly + gauss() * 0.2 * wanted)
                corrections = 2
            }
        }
        px.add(lx)
        py.add(ly)
        val points = px.size
        val weights = DoubleArray(points - 1) { max(hypot(px[it + 1] - px[it], py[it + 1] - py[it]), 0.3 * size).pow(0.6) }
        val total = weights.sum()
        val span = (moveEnd - t0).toDouble()
        val times = LongArray(points)
        times[0] = t0
        var acc = 0.0
        for (i in 1 until points) {
            acc += weights[i - 1]
            times[i] = t0 + (span * acc / total).roundToLong()
        }
        times[points - 1] = moveEnd
        val bows = DoubleArray(points - 1) { if (it == 0 && !burstClick) gauss() * config.pathCurve else 0.0 }
        val gammas = DoubleArray(points - 1) { 0.8 + 0.25 * random.nextDouble() }
        val button = view.button(slot)
        val gapMs = ((if (clickAt == Long.MAX_VALUE) moveEnd else clickAt) - t0) / MS.toLong()
        note(
            "plan slot=$slot btn=$button pick=$how first=$first bits=${"%.2f".format(Locale.ROOT, bits)} " +
                "dist=${length.toInt()} gapMs=$gapMs dwellMs=${(dwell / MS).toLong()} " +
                "landing=${"%.4f".format(Locale.ROOT, (lx - box.cx) / size)},${"%.4f".format(Locale.ROOT, (ly - box.cy) / size)} " +
                "overshoot=$overshoots corr=$corrections burst=$burstClick left=${view.remaining.size}",
        )
        return Plan(slot, button, how, px.toDoubleArray(), py.toDoubleArray(), times, bows, gammas, clickAt, !melody)
    }

    /** The cursor spot at [now]: the path's pieces in turn, each with a minimum-jerk speed skewed a little, the first bowed. */
    private fun position(plan: Plan, now: Long): Pair<Double, Double> {
        val last = plan.times.size - 1
        if (now <= plan.times[0]) return plan.xs[0] to plan.ys[0]
        if (now >= plan.times[last]) return plan.lx to plan.ly
        var i = 1
        while (i < last && now > plan.times[i]) i++
        val span = plan.times[i] - plan.times[i - 1]
        val u = if (span > 0L) (now - plan.times[i - 1]).toDouble() / span else 1.0
        val s = jerk(u.coerceIn(0.0, 1.0).pow(plan.gammas[i - 1]))
        return along(plan.xs[i - 1], plan.ys[i - 1], plan.xs[i], plan.ys[i], s, plan.bows[i - 1])
    }

    private fun along(ax: Double, ay: Double, bx: Double, by: Double, s: Double, bow: Double): Pair<Double, Double> {
        val dx = bx - ax
        val dy = by - ay
        val side = bow * sin(PI * s)
        return (ax + dx * s - dy * side) to (ay + dy * s + dx * side)
    }

    private fun jerk(u: Double): Double = u * u * u * (10.0 - 15.0 * u + 6.0 * u * u)

    /** Tremor (two fast tones) plus a slow drift, the same for every frame of the same instant, so moves stay continuous. */
    private fun hand(now: Long, pixel: Double, size: Double): Pair<Double, Double> {
        val t = now / 1e9
        val unit = if (pixel > 0.0) pixel else 0.5
        val tremor = config.tremorPx * unit * 0.6
        val drift = config.driftSlots * size
        fun tone(freq: Double, phase: Double) = sin(2.0 * PI * freq * t + phase)
        val x = tremor * (tone(9.3, phases[0]) + 0.6 * tone(13.1, phases[1])) +
            drift * (tone(0.37, phases[4]) + 0.5 * tone(0.91, phases[5]))
        val y = tremor * (tone(10.1, phases[2]) + 0.6 * tone(12.3, phases[3])) +
            drift * (tone(0.43, phases[6]) + 0.5 * tone(1.07, phases[7]))
        return x to y
    }

    private fun quantize(value: Double, pixel: Double): Double = if (pixel > 0.0) Math.round(value / pixel) * pixel else value

    private fun abs(v: Double): Double = if (v < 0.0) -v else v

    private companion object {
        const val MS = 1_000_000.0
        const val LN2 = 0.6931471805599453
        const val MAX_EVENTS = 24
        const val PHASES = 8
        const val MELODY_LIT_INDEX = 3
        const val MELODY_BUTTON_COLUMN = 7
        val MELODY_BUTTONS = setOf(16, 25, 34)
        const val MELODY_COOLDOWN_NANOS = 300_000_000L
        const val SETTLE_NANOS = 20_000_000L
        const val MIN_MOVE_NANOS = 40_000_000L
        const val USER_MOVED = 12.0
        const val LANDING_LIMIT = 0.42
        const val CLICK_LIMIT = 0.46
        const val INSIDE = 0.35
        const val LANDING_Y_SHARE = 0.9
        const val DWELL_SPREAD = 0.6
        const val START_SPREAD = 0.4
        const val MAX_DWELL_MS = 250.0
        const val MAX_GAP_MS = 2500.0
        const val BURST_FLOOR_MS = 30.0
        const val BURST_MOVE_SHARE = 0.6
        const val MIN_MISS = 0.3
        const val SECOND_CORRECTION = 0.25
    }
}
