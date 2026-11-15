package geiler.addons.client.dungeon.terminal

import kotlin.math.abs

/**
 * Pure terminal solving for the Terminal Solver module: terminal detection by container title, the six solvers,
 * click prediction and first-click protection. Rules, constants and settings follow Odin's terminal solver
 * (BSD 3-Clause, tag 0.3.6); the code is written independently and has no Minecraft dependency so it can be
 * checked offline.
 */
enum class TerminalType(val windowSize: Int, val title: Regex) {
    PANES(45, Regex("^Correct all the panes!$")),
    RUBIX(45, Regex("^Change all to same color!$")),
    NUMBERS(36, Regex("^Click in order!$")),
    STARTS_WITH(45, Regex("^What starts with: '(\\w)'\\?$")),
    SELECT(54, Regex("^Select all the ([\\w ]+) items!$")),
    MELODY(54, Regex("^Click the button on time!$")),
}

/** One container slot as the solvers see it; [item] is the registry path such as `red_stained_glass_pane`. */
class TermSlot(
    val item: String,
    val count: Int = 1,
    val name: String = "",
    val glint: Boolean = false,
    /** True for items that always glint, which the starts-with terminal ignores. */
    val glintOverrideItem: Boolean = false,
) {
    val isBlackPane: Boolean get() = item == BLACK_PANE

    companion object {
        const val BLACK_PANE = "black_stained_glass_pane"
        val EMPTY = TermSlot("air", 0)
    }
}

/** Which setting colors a solved slot; the module maps each key to its configured color. */
enum class ColorKey {
    PANES, RUBIX_1, RUBIX_2, RUBIX_NEG_1, RUBIX_NEG_2,
    NUMBERS_1, NUMBERS_2, NUMBERS_3, NUMBERS_4, NUMBERS_TRANSPARENT,
    STARTS_WITH, SELECT, MELODY_COLUMN, MELODY_POINTER, MELODY_BACKGROUND,
}

class SlotVisual(val color: ColorKey, val text: String?)

/** The settings the solving rules read. */
interface TerminalConfig {
    val clickPrediction: Boolean
    val rubixLeftClicksOnly: Boolean

    /** Rubix: a wrong-button click on a solution slot is sent as the button the slot needs. */
    val rubixReplaceClicks: Boolean
    val firstClickProtectionMillis: Int
    val protectWithServerTicks: Boolean
    val protectionTicks: Int
}

/** The click to send: container [slot] with mouse [button] (1 right, 2 middle). */
class TerminalClick(val slot: Int, val button: Int)

abstract class TerminalHandler(val type: TerminalType, protected val config: TerminalConfig, private val clock: () -> Long) {
    val clickedSlots = ArrayList<Pair<Int, Int>>()
    val solution = ArrayList<Int>()
    val timeOpened: Long = clock()
    var lastClickTime = 0L
    var ticksOpened = -1

    /** A slot changed (or [slotIndex] 0 with an empty stack to force a reload): recompute the solution. */
    fun updateSlot(slotIndex: Int, updatedIsBlackPane: Boolean, slots: List<TermSlot>) {
        val visible = type.windowSize - 9
        if (slots.size < visible || slotIndex !in 0 until visible || updatedIsBlackPane) return
        val index = clickedSlots.indexOfFirst { it.first == slotIndex }
        if (index >= 0) clickedSlots.subList(0, index + 1).clear()
        solution.clear()
        solution.addAll(solve(slots.subList(0, visible), slotIndex))
        if (config.clickPrediction) clickedSlots.forEach { (slot, button) -> simulateClick(slot, button) }
    }

    protected abstract fun renderSlot(slotIndex: Int): SlotVisual?

    fun visual(slotIndex: Int): SlotVisual? = if (slotIndex !in solution) null else renderSlot(slotIndex)

    open fun simulateClick(slotIndex: Int, clickType: Int) {
        val at = solution.indexOf(slotIndex)
        if (at != -1) solution.removeAt(at)
    }

    abstract fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int>

    open fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex in solution

    /** Records and simulates a click; null when it is not allowed (not a solution slot or still protected). */
    open fun click(slotIndex: Int, button: Int, simulate: Boolean): TerminalClick? {
        if (!canClick(slotIndex, button) || shouldProtect()) return null
        val sent = if (button == 1 && type == TerminalType.RUBIX) 1 else 2
        clickedSlots.add(slotIndex to sent)
        lastClickTime = clock()
        if (simulate) simulateClick(slotIndex, sent)
        return TerminalClick(slotIndex, sent)
    }

    fun shouldProtect(): Boolean =
        clock() - timeOpened < config.firstClickProtectionMillis ||
            (config.protectWithServerTicks && ticksOpened < config.protectionTicks)

    companion object {
        /** Builds the handler for [title], or null when it is not a terminal or its color/letter cannot be read. */
        @JvmStatic
        fun open(title: String, config: TerminalConfig, clock: () -> Long = System::currentTimeMillis): TerminalHandler? {
            val type = TerminalType.entries.firstOrNull { it.title.matches(title) } ?: return null
            return when (type) {
                TerminalType.PANES -> PanesHandler(config, clock)
                TerminalType.RUBIX -> RubixHandler(config, clock)
                TerminalType.NUMBERS -> NumbersHandler(config, clock)
                TerminalType.STARTS_WITH -> StartsWithHandler(type.title.find(title)?.groupValues?.get(1) ?: return null, config, clock)
                TerminalType.SELECT -> {
                    val name = type.title.find(title)?.groupValues?.get(1)?.replace("SILVER", "LIGHT GRAY") ?: return null
                    val color = DYES.firstOrNull { it.replace("_", " ").equals(name, ignoreCase = true) } ?: return null
                    SelectAllHandler(color, config, clock)
                }
                TerminalType.MELODY -> MelodyHandler(config, clock)
            }
        }

        private val DYES = listOf(
            "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN",
            "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
        )
    }
}

class PanesHandler(config: TerminalConfig, clock: () -> Long) : TerminalHandler(TerminalType.PANES, config, clock) {
    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> =
        slots.mapIndexedNotNull { index, slot -> if (slot.item == "red_stained_glass_pane") index else null }

    override fun renderSlot(slotIndex: Int) = SlotVisual(ColorKey.PANES, null)
}

class NumbersHandler(config: TerminalConfig, clock: () -> Long) : TerminalHandler(TerminalType.NUMBERS, config, clock) {
    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> =
        slots.mapIndexedNotNull { index, slot -> if (slot.item == "red_stained_glass_pane") index else null }
            .sortedBy { slots[it].count }

    override fun simulateClick(slotIndex: Int, clickType: Int) {
        if (solution.isNotEmpty()) solution.removeAt(0)
    }

    override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == solution.firstOrNull()

    override fun renderSlot(slotIndex: Int): SlotVisual {
        val at = solution.indexOf(slotIndex)
        val color = when (at) {
            0 -> ColorKey.NUMBERS_1
            1 -> ColorKey.NUMBERS_2
            2 -> ColorKey.NUMBERS_3
            3 -> ColorKey.NUMBERS_4
            else -> ColorKey.NUMBERS_TRANSPARENT
        }
        return SlotVisual(color, (abs((solution.size - 10) - at) + 1).toString())
    }
}

class RubixHandler(config: TerminalConfig, clock: () -> Long) : TerminalHandler(TerminalType.RUBIX, config, clock) {
    private var lockedColor: String? = null
    private val rightClickSlots = HashSet<Int>()

    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> {
        val panes = slots.mapIndexedNotNull { index, slot -> paneColor(slot)?.let { index to it } }
        if (updatedIndex == LAST_PANE_SLOT && lockedColor == null) {
            lockedColor = ORDER.minBy { goal -> clicksFor(goal, panes).values.sumOf { abs(it) } }
        }
        val clicks = lockedColor?.let { clicksFor(it, panes) }.orEmpty()
        rightClickSlots.clear()
        clicks.forEach { (slot, count) -> if (count < 0) rightClickSlots.add(slot) }
        return clicks.flatMap { (slot, count) -> List(abs(count)) { slot } }
    }

    private fun clicksFor(goal: String, panes: List<Pair<Int, String>>): Map<Int, Int> {
        val goalIndex = ORDER.indexOf(goal)
        return panes.associate { (slot, color) ->
            val forward = distance(ORDER.indexOf(color), goalIndex)
            slot to if (forward > 2 && !config.rubixLeftClicksOnly) forward - ORDER.size else forward
        }.filterValues { it != 0 }
    }

    /** The pane's dye name, null for non panes and black panes. */
    private fun paneColor(slot: TermSlot): String? {
        if (!slot.item.endsWith("_stained_glass_pane")) return null
        val color = slot.item.removeSuffix("_stained_glass_pane").uppercase()
        return if (color == "BLACK") null else color
    }

    override fun canClick(slotIndex: Int, button: Int): Boolean =
        slotIndex in solution && (config.rubixReplaceClicks || (button == 1) == (slotIndex in rightClickSlots))

    override fun click(slotIndex: Int, button: Int, simulate: Boolean): TerminalClick? {
        val needed = if (slotIndex in rightClickSlots) 1 else 0
        return super.click(slotIndex, if (config.rubixReplaceClicks) needed else button, simulate)
    }

    private fun distance(pane: Int, most: Int): Int = if (pane > most) (most + ORDER.size) - pane else most - pane

    override fun renderSlot(slotIndex: Int): SlotVisual? {
        val remaining = solution.count { it == slotIndex }.takeIf { it > 0 } ?: return null
        val required = if (slotIndex in rightClickSlots) -remaining else remaining
        val color = when (required) {
            1 -> ColorKey.RUBIX_1
            2 -> ColorKey.RUBIX_2
            -1, 4 -> ColorKey.RUBIX_NEG_1
            else -> ColorKey.RUBIX_NEG_2
        }
        return SlotVisual(color, required.toString())
    }

    private companion object {
        const val LAST_PANE_SLOT = 32
        val ORDER = listOf("ORANGE", "YELLOW", "GREEN", "BLUE", "RED")
    }
}

class StartsWithHandler(private val letter: String, config: TerminalConfig, clock: () -> Long) :
    TerminalHandler(TerminalType.STARTS_WITH, config, clock) {
    private val clickedOverrides = HashMap<Int, Boolean>()

    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> {
        clickedOverrides.computeIfPresent(updatedIndex) { _, _ -> true }
        return slots.mapIndexedNotNull { index, slot ->
            if (slot.name.startsWith(letter, ignoreCase = true) && clickedOverrides[index] != true &&
                (!slot.glint || slot.glintOverrideItem)
            ) index else null
        }
    }

    override fun click(slotIndex: Int, button: Int, simulate: Boolean): TerminalClick? {
        if (canClick(slotIndex, button) && slotIndex !in clickedOverrides) clickedOverrides[slotIndex] = false
        return super.click(slotIndex, button, simulate)
    }

    override fun renderSlot(slotIndex: Int) = SlotVisual(ColorKey.STARTS_WITH, null)
}

class SelectAllHandler(color: String, config: TerminalConfig, clock: () -> Long) :
    TerminalHandler(TerminalType.SELECT, config, clock) {
    private val prefixes: Set<String> = when (color) {
        "BLACK" -> setOf("black", "ink")
        "BLUE" -> setOf("blue", "lapis")
        "BROWN" -> setOf("brown", "cocoa")
        "WHITE" -> setOf("white", "bone", "wool")
        "GREEN" -> setOf("green", "cactus")
        "RED" -> setOf("red", "rose")
        "YELLOW" -> setOf("yellow", "dandelion")
        "LIGHT_GRAY" -> setOf("silver", "light gray")
        else -> setOf(color.lowercase().replace('_', ' '))
    }

    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> =
        slots.mapIndexedNotNull { index, slot ->
            if (!slot.isBlackPane && !slot.glint && prefixes.any { slot.name.lowercase().startsWith(it) }) index else null
        }

    override fun renderSlot(slotIndex: Int) = SlotVisual(ColorKey.SELECT, null)
}

class MelodyHandler(config: TerminalConfig, clock: () -> Long) : TerminalHandler(TerminalType.MELODY, config, clock) {
    override fun solve(slots: List<TermSlot>, updatedIndex: Int): List<Int> {
        val magenta = slots.indexOfFirst { it.item == "magenta_stained_glass_pane" }
        val greenPane = slots.indexOfLast { it.item == "lime_stained_glass_pane" }
        val greenClay = slots.indexOfLast { it.item == "lime_terracotta" }
        return buildList {
            add(greenPane)
            add(magenta)
            add(magenta + 36)
            if (greenPane % 9 == magenta % 9) add(greenClay)
        }
    }

    override fun canClick(slotIndex: Int, button: Int): Boolean = slotIndex == 16 || slotIndex == 25 || slotIndex == 34

    override fun renderSlot(slotIndex: Int): SlotVisual =
        SlotVisual(if (slotIndex / 9 == 0 || slotIndex / 9 == 4) ColorKey.MELODY_COLUMN else ColorKey.MELODY_POINTER, null)
}
