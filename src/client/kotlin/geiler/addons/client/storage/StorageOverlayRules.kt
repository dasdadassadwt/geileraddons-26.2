package geiler.addons.client.storage

import java.util.Locale

/**
 * The pure rules behind Storage Overlay: which container titles are storage pages, which command opens a page, how a
 * search matches, and the page-card geometry. The page model follows Skyblocker's storage overlay (LGPL-3.0): nine Ender
 * Chest pages (index 0..8) followed by eighteen Backpack pages (index 9..26). Written independently.
 */
object StorageOverlayRules {
    const val ECHEST_PAGES = 9
    const val BACKPACK_PAGES = 18
    const val PAGE_COUNT = ECHEST_PAGES + BACKPACK_PAGES

    /** One slot cell, and the card frame around a page's slot grid. */
    const val SLOT = 18
    const val EDGE = 7
    const val HEADER = 17
    const val FOOTER = 7

    /** The first nine container slots of a page are navigation, not items. */
    const val NAV_SLOTS = 9

    private val ECHEST = Regex("Ender Chest.*\\((\\d+)/\\d+\\)", RegexOption.IGNORE_CASE)
    private val BACKPACK = Regex("Backpack.*\\(Slot #(\\d+)\\)", RegexOption.IGNORE_CASE)
    private val BACKPACK_SIZE = Regex("has (\\d+) slots", RegexOption.IGNORE_CASE)

    /** The page index a container [title] opens, or -1 when it is not a storage page. */
    @JvmStatic
    fun indexFromTitle(title: String): Int {
        ECHEST.find(title)?.let { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return -1
            return if (number in 1..ECHEST_PAGES) number - 1 else -1
        }
        BACKPACK.find(title)?.let { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return -1
            return if (number in 1..BACKPACK_PAGES) ECHEST_PAGES + number - 1 else -1
        }
        return -1
    }

    /** True for the bare `Storage` menu that lists every page. */
    @JvmStatic
    fun isHomeTitle(title: String): Boolean = title.trim().equals("Storage", ignoreCase = true)

    /** The slashless command that opens page [index], or null when the index is not a page. */
    @JvmStatic
    fun commandFor(index: Int): String? = when (index) {
        in 0 until ECHEST_PAGES -> "echest ${index + 1}"
        in ECHEST_PAGES until PAGE_COUNT -> "backpack ${index - ECHEST_PAGES + 1}"
        else -> null
    }

    @JvmStatic
    fun pageName(index: Int): String =
        if (index < ECHEST_PAGES) "Ender Chest ${index + 1}" else "Backpack ${index - ECHEST_PAGES + 1}"

    /** Upper bounds for the saved cache file and one saved page (a double chest holds 54 slots). */
    const val MAX_STORE_BYTES = 8L * 1024 * 1024
    private const val MAX_PAGE_ITEMS = 54

    @JvmStatic
    fun validPageIndex(index: Int): Boolean = index in 0 until PAGE_COUNT

    @JvmStatic
    fun validPageItems(count: Int): Boolean = count in 0..MAX_PAGE_ITEMS

    @JvmStatic
    fun storeSizeOk(bytes: Long): Boolean = bytes in 1..MAX_STORE_BYTES

    /** The saved cache file of one player: only letters, digits and dashes of the id survive, so it stays in its folder. */
    @JvmStatic
    fun storeFileName(playerId: String): String = playerId.filter { it.isLetterOrDigit() || it == '-' } + ".nbt"

    /** The page a slot of the home menu stands for (Ender Chests in slots 9..17, Backpacks in 27..44), or -1. */
    @JvmStatic
    fun homeIndex(slot: Int): Int = when (slot) {
        in 9..17 -> slot - 9
        in 27..44 -> slot - 18
        else -> -1
    }

    /** The slot count a backpack's lore states ("has 27 slots"), or -1. */
    @JvmStatic
    fun backpackSlots(loreLine: String): Int = BACKPACK_SIZE.find(loreLine)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /** Whether a stack with [name] and [lore] matches the search [filter]; an empty filter matches everything. */
    @JvmStatic
    fun matches(filter: String, name: String, lore: List<String>): Boolean {
        val needle = filter.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return true
        return name.lowercase(Locale.ROOT).contains(needle) || lore.any { it.lowercase(Locale.ROOT).contains(needle) }
    }

    /** The widest column count at most [requested] whose card fits [availableWidth]; never below one. */
    @JvmStatic
    fun fittingColumns(requested: Int, availableWidth: Int): Int {
        var columns = requested.coerceAtLeast(1)
        while (columns > 1 && columns * SLOT + EDGE * 2 > availableWidth) columns--
        return columns
    }

    /** Item rows of a page whose container holds [containerSize] slots (navigation row excluded). */
    @JvmStatic
    fun pageRows(containerSize: Int, columns: Int): Int =
        if (containerSize <= NAV_SLOTS) 0 else Math.ceilDiv(containerSize - NAV_SLOTS, columns.coerceAtLeast(1))

    @JvmStatic
    fun cardHeight(rows: Int): Int = HEADER + rows * SLOT + FOOTER

    /** A scroll position held inside what the content allows. */
    @JvmStatic
    fun clampScroll(scroll: Double, contentHeight: Int, viewHeight: Int): Double =
        scroll.coerceIn(0.0, (contentHeight - viewHeight).coerceAtLeast(0).toDouble())

    /** The y offset of each row of cards: a row is as tall as its tallest card, with [gap] between rows. */
    @JvmStatic
    fun rowOffsets(cardHeights: List<Int>, perRow: Int, gap: Int): IntArray {
        val rows = cardHeights.chunked(perRow.coerceAtLeast(1))
        val offsets = IntArray(rows.size)
        var y = 0
        for ((i, row) in rows.withIndex()) {
            offsets[i] = y
            y += (row.maxOrNull() ?: 0) + gap
        }
        return offsets
    }
}
