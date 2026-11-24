package geiler.addons.client.hunting

import kotlin.random.Random

/**
 * Pure rules for the Torrhus Canyon / Galatea hunting helpers: which name tags are a Queen Ant or a Blue Jay, the
 * lasso stamina bar, and when Auto Reel presses. Behaviour follows the SkyBlock wiki (Queen Ant, Blue Jay, Lassos),
 * Skyblocker's Lasso HUD and NoFrills' Lasso Alert; the code is written independently.
 */
object QueenAnt {
    const val NAME = "Queen Ant"

    /** A Queen Ant (or "Ant Queen") label; a shard or item label is not the mob. */
    @JvmStatic
    fun matches(label: String?): Boolean {
        if (label == null) return false
        val text = label.replace(FORMATTING, "")
        if (text.contains("Shard", ignoreCase = true)) return false
        return text.contains(NAME, ignoreCase = true) || text.contains("Ant Queen", ignoreCase = true)
    }

    /** The party line, with the player's coordinates in the format the user asked for. */
    @JvmStatic
    fun partyMessage(x: Int, y: Int, z: Int): String = "$NAME at x: $x y: $y z: $z"
}

object BlueJay {
    const val NAME = "Blue Jay"
    const val ALERT_MARK = "/!\\"

    /** Red `/!\`: it saw you. Yellow `/!\`: about to settle (6 s into the 10 s). No mark: safe to lasso. */
    enum class State { ALERT, ALMOST, SAFE }

    /** Yellow in the legacy palette (§e) as Hypixel sends it. */
    const val YELLOW_RGB = 0xFFFF55

    /** The state of a Blue Jay label, or null when the label is not a Blue Jay. [markRgb] is the colour of the mark. */
    @JvmStatic
    fun state(label: String?, markRgb: Int?): State? {
        if (label == null) return null
        val text = label.replace(FORMATTING, "")
        if (!text.contains(NAME, ignoreCase = true) || text.contains("Shard", ignoreCase = true)) return null
        if (!text.contains(ALERT_MARK)) return State.SAFE
        if (label.contains("§e$ALERT_MARK")) return State.ALMOST
        return if (markRgb != null && markRgb and 0xFFFFFF == YELLOW_RGB) State.ALMOST else State.ALERT
    }
}

object LassoLogic {
    /** The stamina bar is an armour stand named with this many cells. */
    const val BAR_CELLS = 20

    /** Lasso tier ids and the cell count at which the REEL prompt appears (Skyblocker's measurements). */
    @JvmStatic
    fun reelCells(itemId: String?): Int? = when (itemId) {
        "ABYSMAL_LASSO" -> 2
        "VINERIP_LASSO", "ENTANGLER_LASSO" -> 3
        "EVERSTRETCH_LASSO" -> 4
        else -> null
    }

    @JvmStatic
    fun isLasso(itemId: String?): Boolean = reelCells(itemId) != null

    /** The bar label: only blank cells as plain text, with the filled cells as its first styled part. */
    @JvmStatic
    fun isBar(plain: String?, parts: Int): Boolean = plain != null && plain.length == BAR_CELLS && plain.isBlank() && parts == 2

    /** How full the bar still is above the reel point, 0 to 100; 0 means reel now. */
    @JvmStatic
    fun percent(filledCells: Int, reelCells: Int): Int {
        val span = BAR_CELLS - reelCells
        if (span <= 0) return 0
        return ((filledCells - reelCells) * 100 / span).coerceIn(0, 100)
    }

    /** The REEL prompt Hypixel floats over a lassoed critter. */
    @JvmStatic
    fun isReelPrompt(plain: String?): Boolean = plain?.replace(FORMATTING, "")?.trim()?.equals("REEL", ignoreCase = true) == true
}

/**
 * One reel click per REEL prompt: a random reaction delay, then a press held for a random time.
 * Times are milliseconds from any monotonic clock.
 */
class ReelScheduler(private val random: Random = Random.Default) {
    enum class Action { NONE, PRESS, RELEASE }

    private var pressAt = -1L
    private var releaseAt = -1L
    private var pressed = false

    fun pending(): Boolean = pressAt >= 0L

    fun isPressed(): Boolean = pressed

    /** Schedules a click unless one is already pending or held. Returns whether it scheduled. */
    fun onReel(nowMs: Long, reactMinMs: Int, reactMaxMs: Int, holdMinMs: Int, holdMaxMs: Int): Boolean {
        if (pending()) return false
        val react = between(reactMinMs, reactMaxMs)
        pressAt = nowMs + react
        releaseAt = pressAt + between(holdMinMs, holdMaxMs).coerceAtLeast(1)
        return true
    }

    /** What to do now; [allowed] false cancels a press that has not happened yet (but always releases a held one). */
    fun poll(nowMs: Long, allowed: Boolean): Action {
        if (!pending()) return Action.NONE
        if (!pressed) {
            if (!allowed) {
                cancel()
                return Action.NONE
            }
            if (nowMs < pressAt) return Action.NONE
            pressed = true
            return Action.PRESS
        }
        if (nowMs < releaseAt) return Action.NONE
        cancel()
        return Action.RELEASE
    }

    /** Drops the schedule. The caller releases the button first if [isPressed]. */
    fun cancel() {
        pressAt = -1L
        releaseAt = -1L
        pressed = false
    }

    private fun between(a: Int, b: Int): Long {
        val low = minOf(a, b).coerceAtLeast(0)
        val high = maxOf(a, b).coerceAtLeast(0)
        return if (high == low) low.toLong() else (low + random.nextInt(high - low + 1)).toLong()
    }
}

private val FORMATTING = Regex("§.")
