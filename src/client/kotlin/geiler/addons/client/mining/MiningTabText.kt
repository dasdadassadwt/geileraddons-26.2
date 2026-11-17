package geiler.addons.client.mining

import geiler.addons.client.farming.TabWidgetText
import geiler.addons.client.tree.ChatText
import java.util.Locale

/**
 * Reads the Dwarven Mines tab-list widgets (`Commissions:` and `Powders:`). A header row starts the block and the
 * rows after it belong to it until the next header. Unknown means null or empty, never "zero".
 */
object MiningTabText {
    /** One commission row: the quest name and its progress (a fraction, or [done]). */
    class Commission(val name: String, val fraction: Double, val done: Boolean, private val text: String) {
        fun progressText(): String = text
    }

    class Powders(val mithril: Long, val gemstone: Long, val glacite: Long)

    private val COMMISSION_ROW = Regex("^(.+?):\\s*(DONE|[\\d.,]+%)$", RegexOption.IGNORE_CASE)
    private val POWDER_ROW = Regex("^(Mithril|Gemstone|Glacite):\\s*([\\d,]+)$", RegexOption.IGNORE_CASE)

    @JvmStatic
    fun commissions(lines: List<String?>?): List<Commission> {
        val out = ArrayList<Commission>()
        for (row in block(lines, "commissions:")) {
            val match = COMMISSION_ROW.matchEntire(row) ?: continue
            val value = match.groupValues[2]
            val done = value.equals("DONE", true)
            val fraction = if (done) 1.0 else (value.removeSuffix("%").replace(",", "").toDoubleOrNull() ?: continue) / 100.0
            val complete = done || fraction >= 1.0
            out.add(Commission(match.groupValues[1].trim(), if (complete) 1.0 else fraction, complete, value))
        }
        return out
    }

    @JvmStatic
    fun powders(lines: List<String?>?): Powders? {
        val rows = block(lines, "powders:")
        var mithril = 0L
        var gemstone = 0L
        var glacite = 0L
        var any = false
        for (row in rows) {
            val match = POWDER_ROW.matchEntire(row) ?: continue
            val amount = match.groupValues[2].replace(",", "").toLongOrNull() ?: continue
            any = true
            when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "mithril" -> mithril = amount
                "gemstone" -> gemstone = amount
                else -> glacite = amount
            }
        }
        return if (any) Powders(mithril, gemstone, glacite) else null
    }

    private val CORPSE_ROW = Regex("^(\\w+):\\s*(NOT LOOTED|LOOTED)$", RegexOption.IGNORE_CASE)

    /** The `Frozen Corpses:` widget: each corpse type in the shaft and whether it is looted. Empty when absent. */
    @JvmStatic
    fun corpses(lines: List<String?>?): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (row in block(lines, "frozen corpses:")) {
            val match = CORPSE_ROW.matchEntire(row) ?: continue
            out[match.groupValues[1]] = match.groupValues[2].equals("LOOTED", true)
        }
        return out
    }

    /** The cleaned rows under [header], up to the next row that looks like another widget header. */
    private fun block(lines: List<String?>?, header: String): List<String> {
        if (lines.isNullOrEmpty()) return emptyList()
        val rows = lines.map { ChatText.stripForMatch(it ?: "") }
        val start = rows.indexOfFirst { it.lowercase(Locale.ROOT) == header }
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (index in start + 1 until rows.size) {
            val row = rows[index]
            if (row.isEmpty()) continue
            val lower = row.lowercase(Locale.ROOT)
            if (lower.endsWith(":") || TabWidgetText.isOtherWidget(lower)) break
            out.add(row)
        }
        return out
    }
}

/**
 * Session powder gain from successive tab readings: gain only ever grows (spending powder on the Heart of the
 * Mountain lowers the reading but is not a loss), and per-hour is gain scaled by elapsed time.
 */
class MiningPowderSession {
    private var last: MiningTabText.Powders? = null
    private var mithril = 0L
    private var gemstone = 0L
    private var glacite = 0L
    private var startNanos = Long.MIN_VALUE

    fun observe(now: MiningTabText.Powders, nanos: Long) {
        val previous = last
        if (previous == null) {
            startNanos = nanos
        } else {
            mithril += (now.mithril - previous.mithril).coerceAtLeast(0)
            gemstone += (now.gemstone - previous.gemstone).coerceAtLeast(0)
            glacite += (now.glacite - previous.glacite).coerceAtLeast(0)
        }
        last = now
    }

    fun gainMithril(): Long = mithril
    fun gainGemstone(): Long = gemstone
    fun gainGlacite(): Long = glacite

    fun perHour(gain: Long, nowNanos: Long): Long {
        if (startNanos == Long.MIN_VALUE) return 0
        val elapsed = nowNanos - startNanos
        if (elapsed < MIN_ELAPSED_NANOS) return 0
        return Math.round(gain * 3_600_000_000_000.0 / elapsed)
    }

    fun reset() {
        last = null
        mithril = 0
        gemstone = 0
        glacite = 0
        startNanos = Long.MIN_VALUE
    }

    private companion object {
        /** Under a few seconds the rate is noise, so report zero rather than a wild figure. */
        const val MIN_ELAPSED_NANOS = 5_000_000_000L
    }
}
