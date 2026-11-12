package geiler.addons.client.gui

import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale
import java.util.function.Consumer

/** Registry/file-backed selector for an inventory button's macro, icon, or PNG. */
class InventoryButtonPickerScreen(
    private val parent: Screen?,
    private val mode: Mode,
    private val selected: Consumer<String>,
    private val createNew: Runnable?,
    private val cancelled: Runnable?,
) : Screen(Component.literal(mode.screenTitle)) {
    enum class Mode(val screenTitle: String) {
        MACRO("Choose Macro"),
        ITEM("Choose Registered Item or Block Icon"),
        PNG("Choose Inventory Button PNG"),
    }

    private val options = ArrayList<Option>()
    private var search: EditBox? = null
    private var filter = ""
    private var scroll = 0
    private var panel = GuiRect(0, 0, 1, 1)

    constructor(parent: Screen?, mode: Mode, selected: Consumer<String>) : this(
        parent, mode, selected, null, null,
    )

    init {
        loadOptions()
    }

    override fun isPauseScreen(): Boolean = false

    private fun loadOptions() {
        when (mode) {
            Mode.MACRO -> MacrosModule.INSTANCE.macros().forEach { macro: MacroDefinition ->
                options.add(
                    Option(
                        macro.id().toString(), macro.name(),
                        "id ${macro.id()} · ${if (macro.enabled()) "enabled" else "disabled"}",
                    ),
                )
            }
            Mode.ITEM -> {
                for (item: Item in BuiltInRegistries.ITEM) {
                    if (item == Items.AIR) continue
                    val id: Identifier? = BuiltInRegistries.ITEM.getKey(item)
                    if (id != null) {
                        options.add(Option(id.toString(), item.getName(ItemStack(item)).getString(), id.toString()))
                    }
                }
                options.sortWith(compareBy<Option> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id })
            }
            Mode.PNG -> InventoryButtonOverlay.listPngIcons().forEach { file ->
                options.add(Option(file, file, "<Fabric config>/geileraddons/inventory-button-icons/$file"))
            }
        }
    }

    override fun init() {
        panel = panel()
        val createWidth = if (createNew == null) 0 else createButtonWidth()
        val searchWidth = maxOf(40, panel.w - 20 - (if (createNew == null) 0 else createWidth + 10))
        val field = EditBox(font, panel.x + 10, panel.y + 34, searchWidth, 18, Component.literal("Search by name or ID"))
        field.setMaxLength(128)
        field.setValue(filter)
        field.setResponder { value ->
            filter = value
            scroll = 0
        }
        search = addRenderableWidget(field)
        setInitialFocus(field)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        VisualModule.INSTANCE.refreshTheme()
        panel = panel()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.text(font, trim(font, titleForMode(), panel.w - 20), panel.x + 10, panel.y + 9, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, trim(font, hintForMode(), panel.w - 20), panel.x + 10, panel.y + 21, GuiTheme.TEXT_MUTED)
        if (createNew != null) {
            val create = createRect()
            val hover = create.contains(mouseX, mouseY)
            GuiTheme.roundedRect(
                graphics, create.x, create.y, create.w, create.h, GuiTheme.RADIUS_SMALL,
                if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG,
            )
            graphics.centeredText(
                font, "Create Macro", create.x + create.w / 2, create.y + 5,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
            )
        }

        val visible = visibleOptions()
        val view = viewRect()
        val visibleRows = maxOf(1, view.h / ROW_HEIGHT)
        val maxScroll = maxOf(0, visible.size - visibleRows)
        scroll = scroll.coerceIn(0, maxScroll)
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        for (index in scroll until minOf(visible.size, scroll + visibleRows)) {
            val option = visible[index]
            val y = view.y + (index - scroll) * ROW_HEIGHT
            val hover = mouseX >= view.x && mouseX < view.x + view.w && mouseY >= y && mouseY < y + ROW_HEIGHT
            if (hover) {
                GuiTheme.roundedRect(
                    graphics, view.x, y, view.w - 4, ROW_HEIGHT - 1, GuiTheme.RADIUS_SMALL,
                    GuiTheme.CATEGORY_HOVER,
                )
            }
            val detailWidth = minOf(140, maxOf(56, view.w * 2 / 5))
            val nameWidth = maxOf(1, view.w - detailWidth - 20)
            graphics.text(
                font, trim(font, option.name, nameWidth), view.x + 7, y + 3,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
            )
            graphics.text(
                font, trim(font, option.detail, detailWidth), view.x + view.w - detailWidth - 7, y + 3,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_MUTED,
            )
            if (mode == Mode.MACRO) {
                graphics.text(
                    font, if (option.detail.endsWith("disabled")) "Not runnable" else "Macro button",
                    view.x + 7, y + 12, GuiTheme.TEXT_MUTED,
                )
            }
        }
        graphics.disableScissor()
        if (visible.isEmpty()) graphics.text(font, emptyMessage(), view.x + 8, view.y + 8, GuiTheme.TEXT_MUTED)
        if (mode == Mode.PNG) {
            graphics.text(
                font, "PNG files only; texture IDs cannot be entered here.",
                panel.x + 10, panel.y + panel.h - 14, GuiTheme.TEXT_MUTED,
            )
        }
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
            option.name.lowercase(Locale.ROOT).contains(needle) ||
                option.id.lowercase(Locale.ROOT).contains(needle) ||
                option.detail.lowercase(Locale.ROOT).contains(needle)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val x = event.x().toInt()
        val y = event.y().toInt()
        val view = viewRect()
        if (event.button() == 0 && createNew != null && createRect().contains(x, y)) {
            createNew.run()
            return true
        }
        if (event.button() == 0 && view.contains(x, y)) {
            val visible = visibleOptions()
            val index = scroll + (y - view.y) / ROW_HEIGHT
            if (index in visible.indices) {
                val value = visible[index].id
                minecraft?.gui?.setScreen(parent)
                selected.accept(value)
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val visible = maxOf(1, viewRect().h / ROW_HEIGHT)
        val maxScroll = maxOf(0, visibleOptions().size - visible)
        scroll = (scroll - java.lang.Math.signum(scrollY).toInt() * 3).coerceIn(0, maxScroll)
        return true
    }

    override fun onClose() {
        cancelled?.run()
        minecraft?.gui?.setScreen(parent)
    }

    private fun panel(): GuiRect {
        val panelWidth = minOf(620, maxOf(1, width - 20))
        val panelHeight = minOf(430, maxOf(1, height - 20))
        return GuiRect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)
    }

    private fun viewRect(): GuiRect = GuiRect(
        panel.x + 8,
        panel.y + 58,
        panel.w - 16,
        panel.h - if (mode == Mode.PNG) 83 else 68,
    )

    private fun createRect(): GuiRect {
        val buttonWidth = createButtonWidth()
        return GuiRect(panel.x + panel.w - buttonWidth - 10, panel.y + 34, buttonWidth, 18)
    }

    private fun createButtonWidth(): Int = minOf(102, maxOf(52, panel.w / 3))

    private fun titleForMode(): String = when (mode) {
        Mode.MACRO -> "Choose Macro"
        Mode.ITEM -> "Choose Registered Item or Block Icon"
        Mode.PNG -> "Choose PNG Icon"
    }

    private fun hintForMode(): String = when (mode) {
        Mode.MACRO -> "The macro's stable numeric ID will be stored in this local layout."
        Mode.ITEM -> "Search localized item names or technical registry identifiers."
        Mode.PNG -> "Choose an image file from the GeilerAddons config icon folder."
    }

    private fun emptyMessage(): String =
        if (mode == Mode.PNG && options.isEmpty()) "No PNG files yet. Add one to the config icon folder."
        else "No matching entries."

    private fun trim(screenFont: Font, text: String, maxWidth: Int): String = GuiTheme.fit(screenFont, text, maxWidth)

    private data class Option(val id: String, val name: String, val detail: String)


    private companion object {
        const val ROW_HEIGHT = 22
    }
}
