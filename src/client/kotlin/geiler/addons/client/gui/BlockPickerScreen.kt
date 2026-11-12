package geiler.addons.client.gui

import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.Collections
import java.util.Locale
import java.util.function.Consumer
import kotlin.math.sign

/** Searchable picker backed by the registered block registry, not substring runtime matching. */
class BlockPickerScreen(
    private val parent: Screen?,
    private val selected: Consumer<String>,
) : Screen(Component.literal("Choose Block")) {
    private val options = ArrayList<Option>()
    private lateinit var search: EditBox
    private var filter = ""
    private var scroll = 0
    private var panel = GuiRect(0, 0, 0, 0)

    init {
        for (blockId in registeredBlockIds()) {
            val id = Identifier.tryParse(blockId)
            val block = id?.let(BuiltInRegistries.BLOCK::getValue)
            if (block != null) options += Option(blockId, block.name.string)
        }
        options.sortWith(compareBy<Option> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
    }

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        panel = panelBounds()
        search = EditBox(font, panel.x + 10, panel.y + 34, panel.w - 20, 18,
            Component.literal("Search blocks by name or ID"))
        search.setMaxLength(128)
        search.setValue(filter)
        search.setResponder { value ->
            filter = value
            scroll = 0
        }
        addRenderableWidget(search)
        setInitialFocus(search)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        panel = panelBounds()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.text(font, trim(font, "Choose Block", panel.w - 20), panel.x + 10, panel.y + 9, GuiTheme.TEXT_PRIMARY)
        graphics.text(
            font,
            trim(font, "Filter by localized name or technical identifier.", panel.w - 20),
            panel.x + 10,
            panel.y + 21,
            GuiTheme.TEXT_MUTED,
        )

        val visible = visibleOptions()
        val view = viewRect()
        val visibleRows = maxOf(1, view.h / ROW_HEIGHT)
        val furthestScroll = maxScroll(visible.size, view.h)
        scroll = scroll.coerceIn(0, furthestScroll)
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        for (index in scroll until minOf(visible.size, scroll + visibleRows)) {
            val option = visible[index]
            val y = view.y + (index - scroll) * ROW_HEIGHT
            val hover = mouseX >= view.x && mouseX < view.x + view.w && mouseY >= y && mouseY < y + ROW_HEIGHT
            if (hover) {
                GuiTheme.roundedRect(
                    graphics, view.x, y, view.w - 4, ROW_HEIGHT - 1,
                    GuiTheme.RADIUS_SMALL, GuiTheme.CATEGORY_HOVER,
                )
            }
            val idWidth = minOf(140, maxOf(64, view.w * 2 / 5))
            val nameWidth = maxOf(1, view.w - idWidth - 18)
            graphics.text(
                font, trim(font, option.name, nameWidth), view.x + 7, y + 5,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
            )
            graphics.text(
                font, trim(font, option.id, idWidth), view.x + view.w - idWidth - 7, y + 5,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_MUTED,
            )
        }
        graphics.disableScissor()
        if (visible.isEmpty()) graphics.text(font, "No matching blocks.", view.x + 8, view.y + 8, GuiTheme.TEXT_MUTED)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private var visibleFilter: String? = null
    private var visibleSourceSize = -1
    private var visibleCache: List<Option> = emptyList()

    /** The filtered list is rebuilt only when the search text or the option list changes, not every frame. */
    private fun visibleOptions(): List<Option> {
        if (filter == visibleFilter && options.size == visibleSourceSize) return visibleCache
        val result = filterOptions()
        visibleFilter = filter
        visibleSourceSize = options.size
        visibleCache = result
        return result
    }

    private fun filterOptions(): List<Option> {
        val needle = filter.lowercase(Locale.ROOT).trim { it <= ' ' }
        if (needle.isEmpty()) return options
        return options.filter { option ->
            option.name.lowercase(Locale.ROOT).contains(needle) || option.id.lowercase(Locale.ROOT).contains(needle)
        }
    }

    private fun panelBounds(): GuiRect {
        val w = minOf(620, maxOf(1, width - 20))
        val h = minOf(430, maxOf(1, height - 20))
        return GuiRect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun viewRect(): GuiRect = GuiRect(panel.x + 8, panel.y + 58, panel.w - 16, panel.h - 68)

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        val view = viewRect()
        if (event.button() == 0 && view.contains(x, y)) {
            val visible = visibleOptions()
            val index = scroll + (y - view.y) / ROW_HEIGHT
            if (index in visible.indices) {
                selected.accept(visible[index].id)
                minecraft?.gui?.setScreen(parent)
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scroll = (scroll - sign(scrollY).toInt() * 3)
            .coerceIn(0, maxScroll(visibleOptions().size, viewRect().h))
        return true
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun trim(font: Font, text: String, maxWidth: Int): String = GuiTheme.fit(font, text, maxWidth)

    private data class Option(val id: String, val name: String)


    companion object {
        private const val ROW_HEIGHT = 20

        /** IDs accepted by this picker, shared with settings that persist its selections. */
        @JvmStatic
        fun registeredBlockIds(): List<String> {
            val ids = ArrayList<String>()
            for (block: Block in BuiltInRegistries.BLOCK) {
                if (block === Blocks.AIR || block === Blocks.CAVE_AIR || block === Blocks.VOID_AIR || block.asItem() === Items.AIR) {
                    continue
                }
                val id = BuiltInRegistries.BLOCK.getKey(block)
                if (id != null) ids += id.toString()
            }
            return Collections.unmodifiableList(ids)
        }

        @JvmStatic
        fun maxScroll(optionCount: Int, viewportHeight: Int): Int =
            maxOf(0, optionCount - maxOf(1, viewportHeight / ROW_HEIGHT))
    }
}
