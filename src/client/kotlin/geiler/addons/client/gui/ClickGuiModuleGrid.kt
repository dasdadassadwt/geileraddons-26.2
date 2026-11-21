package geiler.addons.client.gui

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleCategoryFilter

/** Section grouping and card-grid geometry shared by the Click GUI and its Java screen adapter. */
object ClickGuiModuleGrid {
    private const val CARD_HEIGHT_FRACTION = 0.10f
    /** Fixed design-px spacing on the 4/8 grid: 16 around the cards, 12 between them. */
    const val GRID_INSET = 16
    const val CARD_GAP = 12

    @JvmRecord
    data class Section(val label: String?, val modules: List<Module>)

    @JvmRecord
    data class SectionLayout(
        val section: Section,
        val headingY: Int,
        val cardsY: Int,
        val firstModuleIndex: Int,
        val rowCount: Int,
    )

    @JvmRecord
    data class Layout(val sections: List<SectionLayout>, val contentHeight: Int)

    @JvmRecord
    data class CardRect(val module: Module, val x: Int, val y: Int, val w: Int, val h: Int, val index: Int)

    @JvmStatic
    fun cardHeightForPanelHeight(panelHeight: Int): Int =
        Math.max(52, Math.round(Math.max(1, panelHeight) * CARD_HEIGHT_FRACTION))

    @JvmStatic
    fun cardColumnsForModuleWidth(moduleWidth: Int): Int = when {
        moduleWidth >= 900 -> 4
        moduleWidth >= 720 -> 3
        else -> 2
    }

    @JvmRecord
    data class ChipRect(val x: Int, val y: Int, val w: Int, val h: Int)

    @JvmRecord
    data class ChipLayout(val chips: List<ChipRect>, val height: Int)

    /** Flows chips left to right, wrapping to a new row when one would pass [availableWidth]. */
    @JvmStatic
    fun chipLayout(widths: List<Int>, availableWidth: Int, chipHeight: Int, gapX: Int, gapY: Int): ChipLayout {
        if (widths.isEmpty()) return ChipLayout(java.util.List.of(), 0)
        val limit = Math.max(1, availableWidth)
        val chips = ArrayList<ChipRect>(widths.size)
        var x = 0
        var y = 0
        for (requested in widths) {
            val w = Math.min(Math.max(1, requested), limit)
            if (x > 0 && x + w > limit) {
                x = 0
                y += chipHeight + gapY
            }
            chips.add(ChipRect(x, y, w, chipHeight))
            x += w + gapX
        }
        return ChipLayout(java.util.List.copyOf(chips), y + chipHeight)
    }

    @JvmStatic
    fun gridCardContentWidth(viewportWidth: Int, scrollbarGutter: Int): Int =
        Math.max(1, viewportWidth - scrollbarGutter)

    @JvmStatic
    fun cardWidthForGridViewport(viewportWidth: Int, columns: Int, gap: Int, scrollbarGutter: Int): Int {
        val count = Math.max(1, columns)
        val available = gridCardContentWidth(viewportWidth, scrollbarGutter) - Math.max(0, gap) * (count - 1)
        return Math.max(1, available / count)
    }

    @JvmStatic
    fun firstGridRowIntersectingViewport(appliedScroll: Int, cardHeight: Int, rowStep: Int): Int =
        Math.floorDiv(appliedScroll - cardHeight, Math.max(1, rowStep)) + 1

    @JvmStatic
    fun lastGridRowIntersectingViewport(appliedScroll: Int, viewportHeight: Int, rowStep: Int): Int =
        Math.floorDiv(appliedScroll + viewportHeight - 1, Math.max(1, rowStep))

    @JvmStatic
    fun sections(category: Category, modules: List<Module>, searchQuery: String): List<Section> {
        val filters = if (searchQuery.isBlank()) ModuleCategoryFilter.children(category) else emptyList()
        if (filters.isEmpty()) {
            return if (modules.isEmpty()) emptyList() else java.util.List.of(Section(null, modules))
        }

        val sections = ArrayList<Section>()
        val assigned = HashSet<Module>()
        for (filter in filters) {
            val grouped = ArrayList<Module>()
            for (module in modules) {
                if (filter.matches(module)) {
                    grouped.add(module)
                    assigned.add(module)
                }
            }
            if (grouped.isNotEmpty()) sections.add(Section(filter.label(), java.util.List.copyOf(grouped)))
        }

        val ungrouped = ArrayList<Module>()
        for (module in modules) if (!assigned.contains(module)) ungrouped.add(module)
        if (ungrouped.isNotEmpty()) sections.add(Section("Other", java.util.List.copyOf(ungrouped)))
        return java.util.List.copyOf(sections)
    }

    @JvmStatic
    fun layout(
        sections: List<Section>,
        columns: Int,
        cardHeight: Int,
        cardGap: Int,
        sectionHeaderHeight: Int,
        sectionHeaderGap: Int,
        sectionGap: Int,
    ): Layout {
        if (sections.isEmpty()) return Layout(java.util.List.of(), 0)

        val safeColumns = Math.max(1, columns)
        var cursor = 0
        var firstModuleIndex = 0
        val layouts = ArrayList<SectionLayout>(sections.size)
        for (i in sections.indices) {
            val section = sections[i]
            var headingY = -1
            if (section.label != null) {
                headingY = cursor
                cursor += sectionHeaderHeight + sectionHeaderGap
            }
            val cardsY = cursor
            val count = section.modules.size
            val rows = (count + safeColumns - 1) / safeColumns
            layouts.add(SectionLayout(section, headingY, cardsY, firstModuleIndex, rows))
            if (rows > 0) cursor += rows * cardHeight + (rows - 1) * cardGap
            firstModuleIndex += count
            if (i + 1 < sections.size) cursor += sectionGap
        }
        return Layout(java.util.List.copyOf(layouts), cursor)
    }

    @JvmStatic
    fun cardRects(
        layout: Layout,
        viewportX: Int,
        viewportY: Int,
        appliedScroll: Int,
        columns: Int,
        cardWidth: Int,
        cardHeight: Int,
        cardGap: Int,
    ): List<CardRect> {
        val safeColumns = Math.max(1, columns)
        val rowStep = cardHeight + cardGap
        val cards = ArrayList<CardRect>()
        for (sectionLayout in layout.sections) {
            val modules = sectionLayout.section.modules
            for (i in modules.indices) {
                val column = i % safeColumns
                val row = i / safeColumns
                val cardX = viewportX + column * (cardWidth + cardGap)
                val cardY = viewportY + sectionLayout.cardsY + row * rowStep - appliedScroll
                val index = sectionLayout.firstModuleIndex + i
                cards.add(CardRect(modules[i], cardX, cardY, cardWidth, cardHeight, index))
            }
        }
        return java.util.List.copyOf(cards)
    }

    /** Builds geometry only for section rows that can intersect the clipped module grid. */
    @JvmStatic
    fun visibleCardRects(
        layout: Layout,
        viewportX: Int,
        viewportY: Int,
        viewportHeight: Int,
        appliedScroll: Int,
        columns: Int,
        cardWidth: Int,
        cardHeight: Int,
        cardGap: Int,
    ): List<CardRect> {
        if (layout.sections.isEmpty()) return java.util.List.of()
        val safeColumns = Math.max(1, columns)
        val rowStep = cardHeight + cardGap
        val cards = ArrayList<CardRect>()
        for (sectionLayout in layout.sections) {
            if (sectionLayout.rowCount == 0) continue
            val sectionScroll = appliedScroll - sectionLayout.cardsY
            val firstVisibleRow = Math.max(
                0,
                firstGridRowIntersectingViewport(sectionScroll, cardHeight, rowStep),
            )
            val lastVisibleRow = Math.min(
                sectionLayout.rowCount - 1,
                lastGridRowIntersectingViewport(sectionScroll, viewportHeight, rowStep),
            )
            for (row in firstVisibleRow..lastVisibleRow) {
                for (column in 0 until safeColumns) {
                    val indexInSection = row * safeColumns + column
                    if (indexInSection >= sectionLayout.section.modules.size) break
                    val cardX = viewportX + column * (cardWidth + cardGap)
                    val cardY = viewportY + sectionLayout.cardsY + row * rowStep - appliedScroll
                    val index = sectionLayout.firstModuleIndex + indexInSection
                    val module = sectionLayout.section.modules[indexInSection]
                    cards.add(CardRect(module, cardX, cardY, cardWidth, cardHeight, index))
                }
            }
        }
        return java.util.List.copyOf(cards)
    }
}
