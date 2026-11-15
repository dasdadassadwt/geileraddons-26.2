package geiler.addons.client.termsim

import kotlin.random.Random

/**
 * The terminal simulators of Odin's Terminal Simulator (BSD 3-Clause, tag 0.3.6): boards, click rules and completion,
 * written independently and free of Minecraft types. Nothing here talks to a server; the screen that shows a
 * simulator is the only thing that touches Minecraft, and it never sends a packet either.
 */
class SimItem(
    /** Registry path such as `red_stained_glass_pane`. */
    val path: String,
    val count: Int = 1,
    /** Custom name, or null for the item's own name; the empty string hides the name like Hypixel's panes. */
    val name: String? = null,
    val glint: Boolean = false,
    val lore: List<String> = emptyList(),
) {
    fun withGlint() = SimItem(path, count, name, true, lore)

    val isBlackPane: Boolean get() = path == BLACK_PANE

    companion object {
        const val BLACK_PANE = "black_stained_glass_pane"
        fun pane(color: String) = SimItem("${color}_stained_glass_pane", 1, "")
        val BLACK = pane("black")
    }
}

/** What a click did: [sound] plays the click sound, [open] asks for another screen, [completed] ends the terminal. */
class SimOutcome(
    val sound: Boolean = false,
    val completed: Boolean = false,
    val messages: List<String> = emptyList(),
    val open: SimRequest? = null,
)

/** Another screen to open: a kind, a random terminal, or the start menu (both null). */
class SimRequest(val kind: SimKind?, val random: Boolean = false)

enum class SimKind(val termName: String, val id: String) {
    PANES("Correct all the panes!", "panes"),
    RUBIX("Change all to same color!", "rubix"),
    NUMBERS("Click in order!", "numbers"),
    STARTS_WITH("What starts with: \"*\"?", "starts_with"),
    SELECT("Select all the \"*\" items!", "select"),
    MELODY("Click the button on time!", "melody");

    companion object {
        fun parse(text: String): SimKind? = entries.firstOrNull { it.id.equals(text, true) || it.name.equals(text, true) }
    }

    /** A fresh simulator of this kind. */
    fun create(rng: Random, items: ItemPool): TermSim = when (this) {
        PANES -> PanesSim(rng)
        RUBIX -> RubixSim(rng)
        NUMBERS -> NumbersSim(rng)
        STARTS_WITH -> StartsWithSim(rng, items)
        SELECT -> SelectAllSim(rng)
        MELODY -> MelodySim(rng)
    }
}

/** Item registry paths the starts-with simulator draws from, and which of them always glint. */
class ItemPool(
    val paths: List<String>,
    val alwaysGlint: Set<String>,
    /** The name a player sees for an item path. */
    val nameOf: (String) -> String = { it.replace('_', ' ') },
)

abstract class TermSim(val title: String, val size: Int, protected val rng: Random) {
    val slots: Array<SimItem> = Array(size) { SimItem.BLACK }
    private val dirtySlots = LinkedHashSet<Int>()

    /** Slot indexes changed since the last call; the screen copies them into its menu. */
    fun takeDirty(): List<Int> = dirtySlots.toList().also { dirtySlots.clear() }

    protected fun set(index: Int, item: SimItem) {
        slots[index] = item
        dirtySlots.add(index)
    }

    protected fun fill(block: (Int) -> SimItem) {
        for (i in 0 until size) set(i, block(i))
    }

    abstract fun create()

    /** A click on [index] with mouse [button] (0 left, 1 right). */
    abstract fun click(index: Int, button: Int): SimOutcome

    /** One client tick; returns nothing unless the simulator animates. */
    open fun tick() = Unit

    protected fun row(i: Int) = i / 9
    protected fun col(i: Int) = i % 9

    protected val done get() = SimOutcome(sound = true, completed = true)
    protected val ok get() = SimOutcome(sound = true)
    protected val ignored get() = SimOutcome()
}

class PanesSim(rng: Random) : TermSim("Correct all the panes!", 45, rng) {
    override fun create() = fill {
        if (row(it) in 1..3 && col(it) in 2..6) (if (rng.nextDouble() > 0.75) SimItem.pane("lime") else SimItem.pane("red")) else SimItem.BLACK
    }

    override fun click(index: Int, button: Int): SimOutcome {
        if (slots[index].isBlackPane) return ignored
        set(index, if (slots[index].path == "red_stained_glass_pane") SimItem.pane("lime") else SimItem.pane("red"))
        return if (slots.none { it.path == "red_stained_glass_pane" }) done else ok
    }
}

class RubixSim(rng: Random) : TermSim("Change all to same color!", 45, rng) {
    private val order = listOf("orange", "yellow", "green", "blue", "red")
    private val indices = listOf(12, 13, 14, 21, 22, 23, 30, 31, 32)

    override fun create() = fill {
        if (row(it) in 1..3 && col(it) in 3..5) SimItem.pane(order[rng.nextInt(order.size)]) else SimItem.BLACK
    }

    override fun click(index: Int, button: Int): SimOutcome {
        val current = order.indexOfFirst { slots[index].path == "${it}_stained_glass_pane" }
        if (current < 0) return ignored
        val next = if (button == 1) current - 1 else (current + 1) % order.size
        set(index, SimItem.pane(order[if (next in order.indices) next else order.lastIndex]))
        return if (indices.all { slots[it].path == slots[12].path }) done else ok
    }
}

class NumbersSim(rng: Random) : TermSim("Click in order!", 36, rng) {
    override fun create() {
        val used = (1..10).shuffled(rng).toMutableList()
        fill {
            if (row(it) in 1..2 && col(it) in 2..6) {
                val n = used.removeAt(0)
                SimItem("red_stained_glass_pane", n, "§a$n")
            } else SimItem.BLACK
        }
    }

    override fun click(index: Int, button: Int): SimOutcome {
        val next = slots.indices.minByOrNull { if (slots[it].path == "red_stained_glass_pane") slots[it].count else 1000 }
        if (next != index) return ignored
        set(index, SimItem("lime_stained_glass_pane", slots[index].count, ""))
        return if (slots.none { it.path == "red_stained_glass_pane" }) done else ok
    }
}

class StartsWithSim(rng: Random, private val pool: ItemPool, val letter: String = LETTERS[rng.nextInt(LETTERS.size)]) :
    TermSim("What starts with: '$letter'?", 45, rng) {
    private val clickedOverrides = ArrayList<Int>()

    override fun create() {
        val guaranteed = (10..16).random(rng)
        fill {
            when {
                row(it) !in 1..3 || col(it) !in 1..7 -> SimItem.BLACK
                it == guaranteed -> letterItem(false)
                rng.nextDouble() > .7 -> letterItem(false)
                else -> letterItem(true)
            }
        }
    }

    private fun letterItem(other: Boolean): SimItem {
        val match = pool.paths.filter { path ->
            path.startsWith(letter, true) != other && !path.contains("pane", true) && path != "air"
        }
        return if (match.isEmpty()) SimItem("air", 0) else SimItem(match[rng.nextInt(match.size)])
    }

    private fun realGlint(item: SimItem) = item.glint && item.path !in pool.alwaysGlint

    private fun startsWithLetter(item: SimItem) = pool.nameOf(item.path).startsWith(letter, true)

    override fun click(index: Int, button: Int): SimOutcome {
        val item = slots[index]
        if (item.isBlackPane) return ignored
        if (!startsWithLetter(item)) return SimOutcome(messages = listOf("§cThat item does not start with: '$letter'!"))
        if (realGlint(item) || index in clickedOverrides) return SimOutcome(messages = listOf("§cAlready selected!"))
        if (item.path in pool.alwaysGlint) clickedOverrides.add(index)
        set(index, item.withGlint())
        val finished = slots.indices.all { !startsWithLetter(slots[it]) || realGlint(slots[it]) || it in clickedOverrides }
        return if (finished) done else ok
    }

    companion object {
        val LETTERS = listOf("A", "B", "C", "G", "D", "M", "N", "R", "S", "T", "W")
    }
}

class SelectAllSim(rng: Random, val color: String = COLORS[rng.nextInt(COLORS.size)]) :
    TermSim("Select all the ${color.uppercase().replace("LIGHT_GRAY", "SILVER").replace("_", " ")} items!", 54, rng) {

    override fun create() {
        val valid = (10..16) + (19..25) + (28..34) + (37..43)
        val guaranteed = valid.random(rng)
        fill { slot ->
            if (row(slot) in 1..4 && col(slot) in 1..7) {
                val item = SimItem(possible(color).random(rng))
                if (slot == guaranteed || rng.nextDouble() > 0.75) item
                else SimItem(possible(COLORS.filter { it != color }.random(rng)).random(rng))
            } else SimItem.BLACK
        }
    }

    override fun click(index: Int, button: Int): SimOutcome {
        val item = slots[index]
        if (item.isBlackPane) return ignored
        val mine = possible(color)
        if (item.path !in mine) return SimOutcome(messages = listOf("§cThat item is not: ${color.uppercase()}!"))
        if (item.glint) return SimOutcome(messages = listOf("§cAlready selected!"))
        set(index, item.withGlint())
        return if (slots.none { !it.glint && it.path in mine }) done else ok
    }

    companion object {
        val COLORS = listOf(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
            "purple", "blue", "brown", "green", "red", "black",
        )

        @JvmStatic
        fun possible(color: String): List<String> = listOf(
            "${color}_stained_glass", "${color}_wool", "${color}_concrete",
            when (color) {
                "white" -> "bone_meal"
                "blue" -> "lapis_lazuli"
                "black" -> "ink_sac"
                "brown" -> "cocoa_beans"
                else -> "${color}_dye"
            },
        )
    }
}

class MelodySim(rng: Random) : TermSim("Click the button on time!", 54, rng) {
    private var magentaColumn = 1
    private var limeColumn = 2
    private var currentRow = 1
    private var limeDirection = 1
    private var counter = 0

    override fun create() {
        currentRow = 1
        magentaColumn = (1..5).random(rng)
        limeColumn = 1
        limeDirection = 1
        fill { itemFor(it) }
    }

    override fun tick() {
        if (counter++ % 10 != 0) return
        limeColumn += limeDirection
        if (limeColumn == 1 || limeColumn == 5) limeDirection *= -1
        updateRow(currentRow)
    }

    override fun click(index: Int, button: Int): SimOutcome {
        if (col(index) != 7 || limeColumn != magentaColumn || row(index) != currentRow) return ignored
        val oldRow = currentRow
        magentaColumn = (1 until 5).random(rng)
        currentRow++
        updateRow(0)
        updateRow(oldRow)
        if (currentRow <= 3) updateRow(currentRow)
        return if (currentRow > 3) done else ok
    }

    private fun updateRow(row: Int) {
        for (c in 0..8) set(row * 9 + c, itemFor(row * 9 + c))
    }

    private fun itemFor(index: Int): SimItem {
        val r = row(index)
        val c = col(index)
        return when {
            c == magentaColumn && (r == 0 || r == 4) -> SimItem.pane("magenta")
            c == limeColumn && r == currentRow -> SimItem.pane("lime")
            c in 1..5 && r == currentRow -> SimItem.pane("red")
            c == 7 && r == currentRow -> SimItem("lime_terracotta", 1, "")
            c == 7 && r in 1..3 -> SimItem("red_terracotta", 1, "")
            c in 1..5 && r in 1..3 -> SimItem.pane("white")
            else -> SimItem.BLACK
        }
    }

    /** Exposed for checks: the column the pointer must be in and the column it is in. */
    val aligned: Boolean get() = limeColumn == magentaColumn
    val row: Int get() = currentRow
}

/** The 27-slot start menu: one item per terminal, a random one and a PB reset with confirmation. */
class StartMenu(private val bests: (SimKind) -> Float?, private val resetBests: () -> Unit, rng: Random) :
    TermSim("Terminal Simulator", 27, rng) {
    private var areYouSure = false
    private var cancelAtTick = -1
    private var ticks = 0
    private val pending = ArrayList<String>()

    private val kinds = listOf(SimKind.PANES, SimKind.RUBIX, SimKind.NUMBERS, SimKind.STARTS_WITH, SimKind.SELECT, SimKind.MELODY)
    private val dyes = listOf("lime_dye", "red_dye", "cyan_dye", "pink_dye", "brown_dye", "purple_dye")
    private val colors = listOf("§a", "§6", "§3", "§5", "§b", "§d")

    private fun termItem(i: Int): SimItem {
        val pb = bests(kinds[i])
        val lore = if (pb == null) listOf("§7No PB set!") else listOf("§7Personal Best: §d" + String.format(java.util.Locale.US, "%.2f", pb) + "s")
        return SimItem(dyes[i], 1, colors[i] + kinds[i].termName, false, lore)
    }

    override fun create() = fill {
        when (it) {
            4 -> SimItem("black_dye", 1, "§cReset PBs!")
            13 -> SimItem("white_dye", 1, "§7Random")
            in 10..12 -> termItem(it - 10)
            in 14..16 -> termItem(it - 11)
            else -> SimItem.BLACK
        }
    }

    override fun tick() {
        ticks++
        if (areYouSure && cancelAtTick in 0..ticks) {
            areYouSure = false
            pending.add("§aPBs reset cancelled.")
        }
    }

    /** Messages produced by [tick] since the last call. */
    fun takeMessages(): List<String> = pending.toList().also { pending.clear() }

    override fun click(index: Int, button: Int): SimOutcome = when (index) {
        4 -> {
            if (!areYouSure) {
                areYouSure = true
                cancelAtTick = ticks + 60
                SimOutcome(sound = true, messages = listOf("§cAre you sure you want to reset your PBs? Click again to confirm."))
            } else {
                resetBests()
                create()
                areYouSure = false
                SimOutcome(sound = true, messages = listOf("§aPBs reset!"))
            }
        }
        10 -> open(SimKind.PANES)
        11 -> open(SimKind.RUBIX)
        12 -> open(SimKind.NUMBERS)
        13 -> SimOutcome(sound = true, open = SimRequest(null, random = true))
        14 -> open(SimKind.STARTS_WITH)
        15 -> open(SimKind.SELECT)
        16 -> open(SimKind.MELODY)
        else -> ignored
    }

    private fun open(kind: SimKind) = SimOutcome(sound = true, open = SimRequest(kind))

    companion object {
        /** The kinds the Random button picks from; Odin leaves the melody terminal out. */
        val RANDOM_KINDS = listOf(SimKind.PANES, SimKind.RUBIX, SimKind.NUMBERS, SimKind.STARTS_WITH, SimKind.SELECT)
    }
}
