package geiler.addons.client.gui

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideCustomSegment
import geiler.addons.client.dungeon.DungeonGuideNode
import geiler.addons.client.dungeon.DungeonGuidePhaseTracker
import geiler.addons.client.dungeon.DungeonGuideSegments
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.module.impl.DungeonHelperModule
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.macro.MacroDefinition
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.ArrayList
import java.util.Comparator
import java.util.EnumSet
import java.util.Locale
import java.util.function.Consumer

/** Compact one-route-per-floor editor with a phase-grouped list and selected-step inspector. */
class DungeonGuideEditorScreen(private val parent: Screen?) : Screen(Component.literal("Dungeon Guide")) {
    private var editingFloor: DungeonFloor? = null
    private var selectedId: String? = null
    private var searchText = ""
    private var macroSearchText = ""
    private var notice = ""
    private var advancedOpen = false
    private var shapePickerOpen = false
    private var simulatedPhase: String? = null
    private var rebuilding = false
    private var search: EditBox? = null
    private var routeName: EditBox? = null
    private var macroSearch: EditBox? = null
    private var listScroll = 0
    private var detailScroll = 0
    private var macroScroll = 0
    private var cachedRowsRevision = Long.MIN_VALUE
    private var cachedRowsFloor: DungeonFloor? = null
    private var cachedRowsSearch: String? = null
    private var cachedRowsSimulation: String? = null
    private var cachedRows: List<ListRow> = emptyList()

    init {
        if (DungeonContextTracker.inDungeon()) {
            editingFloor = DungeonContextTracker.currentFloor()
            DungeonGuideStore.load()
        }
    }

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        if (!DungeonContextTracker.inDungeon()) {
            minecraft?.gui?.setScreen(parent)
            return
        }
        rebuildEditorWidgets()
    }

    private fun rebuildEditorWidgets() {
        rebuilding = true
        clearWidgets()
        macroSearch = null
        val layout = layout()
        search = addEdit(layout.leftX + 8, layout.y + 60, maxOf(1, layout.leftWidth - 16), 19,
            "Search steps", searchText) { value ->
            searchText = value
            listScroll = 0
        }
        val floorName = editingFloor?.displayName()
        routeName = addEdit(
            layout.rightX + 8,
            layout.y + 52,
            maxOf(1, layout.rightWidth - 16),
            19,
            if (floorName == null) "Choose a floor before naming a route" else "Route name for $floorName",
            floorName?.let { DungeonGuideStore.routeName(it) } ?: "",
        ) { value ->
            if (rebuilding) return@addEdit
            val floor = editingFloor
            if (floor == null) {
                notice = "Choose a floor before naming a route"
                return@addEdit
            }
            if (!DungeonGuideStore.setRouteName(floor.displayName(), value)) {
                notice = "Route name must be 1–48 characters"
            }
        }
        val node = selected()
        if (node != null) {
            var contentY = layout.bodyTop - detailScroll
            val contentBottom = layout.footerTop
            val x = layout.rightX + 8
            val width = maxOf(1, layout.rightWidth - 16)
            val row = 28
            val labelY = contentY
            addIfVisible(labelY, contentBottom) {
                addEdit(x, labelY, width, 19, "Step label", node.label) { value ->
                    updateSelected(Consumer { it.label = value })
                }
            }
            contentY += row
            val gap = 4
            val posWidth = maxOf(1, (width - gap * 2) / 3)
            val py = contentY
            addIfVisible(py, contentBottom) {
                addEdit(x, py, posWidth, 19, "X", node.x.toString()) { value ->
                    updateSelected(Consumer { it.x = parseInt(value, it.x) })
                }
            }
            addIfVisible(py, contentBottom) {
                addEdit(x + posWidth + gap, py, posWidth, 19, "Y", node.y.toString()) { value ->
                    updateSelected(Consumer { it.y = parseInt(value, it.y) })
                }
            }
            addIfVisible(py, contentBottom) {
                addEdit(x + (posWidth + gap) * 2, py, posWidth, 19, "Z", node.z.toString()) { value ->
                    updateSelected(Consumer { it.z = parseInt(value, it.z) })
                }
            }
            contentY += row
            contentY += row // phase selector
            contentY += row // condition rule
            contentY += row // condition row one
            contentY += 22  // condition row two
            contentY += row // skip-ahead toggle
            val paramY = contentY
            val half = maxOf(1, (width - gap) / 2)
            addIfVisible(paramY, contentBottom) {
                addEdit(x, paramY, half, 19, "Radius", String.format(Locale.ROOT, "%.2f", node.triggerRadius)) { value ->
                    updateSelected(Consumer { it.triggerRadius = parseFloat(value, it.triggerRadius) })
                }
            }
            addIfVisible(paramY, contentBottom) {
                addEdit(x + half + gap, paramY, half, 19, "Delay seconds", String.format(Locale.ROOT, "%.1f", node.triggerSeconds)) { value ->
                    updateSelected(Consumer { it.triggerSeconds = parseFloat(value, it.triggerSeconds) })
                }
            }
            contentY += row
            val eventY = contentY
            addIfVisible(eventY, contentBottom) {
                addEdit(x, eventY, width, 19, "Chat phrase", node.eventText) { value ->
                    updateSelected(Consumer { it.eventText = value })
                }
            }
            contentY += row // chat phrase input
            contentY += row // chat match mode
            contentY += row // advanced toggle
            if (advancedOpen) {
                val sizeY = contentY
                addIfVisible(sizeY, contentBottom) {
                    addEdit(x, sizeY, half, 19, "Marker size", String.format(Locale.ROOT, "%.2f", node.size)) { value ->
                        updateSelected(Consumer { it.size = parseFloat(value, it.size) })
                    }
                }
                addIfVisible(sizeY, contentBottom) {
                    addEdit(x + half + gap, sizeY, half, 19, "Rotation", String.format(Locale.ROOT, "%.1f", node.rotation)) { value ->
                        updateSelected(Consumer { it.rotation = parseFloat(value, it.rotation) })
                    }
                }
                contentY += row
                // The custom color swatches below open the same hue/saturation/alpha picker as Click GUI.
                contentY += row
                val textY = contentY
                addIfVisible(textY, contentBottom) {
                    addEdit(x + half + gap, textY, half, 19, "Text scale", String.format(Locale.ROOT, "%.2f", node.labelScale)) { value ->
                        updateSelected(Consumer { it.labelScale = parseFloat(value, it.labelScale) })
                    }
                }
                contentY += row
                val rangeY = contentY
                addIfVisible(rangeY, contentBottom) {
                    addEdit(x, rangeY, half, 19, "Visibility range (0 = default)", node.visibilityDistance.toString()) { value ->
                        updateSelected(Consumer { it.visibilityDistance = parseInt(value, it.visibilityDistance) })
                    }
                }
                contentY += row
                if (node.shape == DungeonGuideNode.Shape.RING) {
                    contentY += 20 // compact group label before this ring's appearance controls
                    val ringRowOne = contentY
                    addIfVisible(ringRowOne, contentBottom) {
                        addEdit(x, ringRowOne, half, 19, "Ring count (1–8)", node.ringCountValue().toString()) { value ->
                            updateSelected(Consumer { it.ringCount = parseInt(value, it.ringCountValue()) })
                        }
                    }
                    addIfVisible(ringRowOne, contentBottom) {
                        addEdit(x + half + gap, ringRowOne, half, 19, "Ring height (0.25–8)", String.format(Locale.ROOT, "%.2f", node.ringHeightValue())) { value ->
                            updateSelected(Consumer { it.ringHeight = parseFloat(value, it.ringHeightValue()) })
                        }
                    }
                    contentY += row
                    val ringRowTwo = contentY
                    addIfVisible(ringRowTwo, contentBottom) {
                        addEdit(x, ringRowTwo, half, 19, "Ring radius (0.25–8)", String.format(Locale.ROOT, "%.2f", node.ringRadiusValue())) { value ->
                            updateSelected(Consumer { it.ringRadius = parseFloat(value, it.ringRadiusValue()) })
                        }
                    }
                    addIfVisible(ringRowTwo, contentBottom) {
                        addEdit(x + half + gap, ringRowTwo, half, 19, "Ring speed (0.05–2)", String.format(Locale.ROOT, "%.2f", node.ringSpeedValue())) { value ->
                            updateSelected(Consumer { it.ringSpeed = parseFloat(value, it.ringSpeedValue()) })
                        }
                    }
                    contentY += row
                    val ringRowThree = contentY
                    addIfVisible(ringRowThree, contentBottom) {
                        addEdit(x, ringRowThree, half, 19, "Ring line width (0.5–5)", String.format(Locale.ROOT, "%.2f", node.ringWidthValue())) { value ->
                            updateSelected(Consumer { it.ringWidth = parseFloat(value, it.ringWidthValue()) })
                        }
                    }
                    contentY += row
                }
            }
            if (advancedOpen) {
                val macroY = contentY
                if (visible(macroY, contentBottom)) {
                    macroSearch = addEdit(x, macroY, width, 19, "Search macros", macroSearchText) { value ->
                        macroSearchText = value
                        macroScroll = 0
                    }
                }
            }
        }
        rebuilding = false
    }

    private fun addIfVisible(y: Int, bottom: Int, add: () -> Unit) {
        if (visible(y, bottom)) add()
    }

    private fun visible(y: Int, bottom: Int): Boolean = y >= layout().bodyTop && y + 19 <= bottom

    private fun addEdit(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        hint: String,
        value: String?,
        responder: (String) -> Unit,
    ): EditBox {
        val edit = EditBox(font, x, y, maxOf(1, width), height, Component.literal(hint))
        edit.setMaxLength(128)
        edit.setValue(value ?: "")
        edit.setTooltip(Tooltip.create(Component.literal(editTooltip(hint))))
        edit.setResponder(Consumer { responder(it) })
        return addRenderableWidget(edit)
    }

    private fun editTooltip(hint: String?): String {
        if (hint != null && hint.startsWith("Route name for ")) {
            return "Name shared by this floor's guide route. Keep it between 1 and 48 characters."
        }
        return when (hint) {
            "Search steps" -> "Filter this floor's step list by step label, phase name, or chat phrase."
            "Step label" -> "Short name shown above this step's marker and in the guide status."
            "X" -> "World X block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically."
            "Y" -> "World Y block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically."
            "Z" -> "World Z block coordinate for the step marker. Use Add at Feet or Add at Aim to fill coordinates automatically."
            "Radius" -> "Distance in blocks used by this step's Radius condition and Skip ahead option."
            "Delay seconds" -> "Seconds to wait after the previous step before advancing."
            "Chat phrase" -> "Phrase to watch for. Select Chat and choose Exact or Contains to use it."
            "Marker size" -> "Size of this step's world marker in blocks."
            "Rotation" -> "Rotation in degrees for this step's 3D box."
            "Text scale" -> "Scale of the floating step label."
            "Visibility range (0 = default)" -> "Maximum render distance in blocks. Zero uses the module's Render Range setting."
            "Ring count (1–8)" -> "Number of moving rings for this step."
            "Ring height (0.25–8)" -> "Vertical distance covered by this step's rings, scaled by Marker size."
            "Ring radius (0.25–8)" -> "Base radius in blocks for this step's rings, scaled by Marker size."
            "Ring speed (0.05–2)" -> "Movement speed of this step's rings."
            "Ring line width (0.5–5)" -> "Line thickness of this step's rings."
            "Search macros" -> "Filter the macro list below, then click a result to link it to this step."
            else -> "Edit this value. Coordinates use world blocks; numeric values accept decimal points."
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val layout = layout()
        GuiTheme.roundedRectBordered(graphics, layout.x, layout.y, layout.width, layout.height, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, fit("Dungeon Guide · Route Editor", layout.width - 24), layout.x + 12, layout.y + 8, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, fit("One named route per floor · steps advance in phase order", layout.width - 24),
            layout.x + 12, layout.y + 22, GuiTheme.TEXT_MUTED)
        drawButton(graphics, GuiRect(layout.x + 12, layout.y + 38, maxOf(1, layout.leftWidth - 24), 19),
            "Floor: ${editingFloor?.displayName() ?: "Unknown"}", mouseX, mouseY)
        graphics.fill(layout.rightX, layout.y + 34, layout.rightX + 1, layout.y + layout.height - 59, GuiTheme.BORDER)
        val simulation = simulationButton(layout)
        if (simulation.x > layout.rightX + 62) graphics.text(font, "Floor route", layout.rightX + 8, layout.y + 40, GuiTheme.TEXT_MUTED)
        drawButton(graphics, simulation, simulationLabel(), mouseX, mouseY)
        renderStepList(graphics, layout, mouseX, mouseY)
        renderInspector(graphics, layout, mouseX, mouseY)
        renderFooter(graphics, layout, mouseX, mouseY)
        if (!ChatStringSemantics.isBlank(notice)) graphics.text(font, fit(notice, layout.width - 24), layout.x + 12,
            layout.y + layout.height - 11, GuiTheme.TEXT_MUTED)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun renderStepList(graphics: GuiGraphicsExtractor, layout: Layout, mouseX: Int, mouseY: Int) {
        val rows = rows()
        val rowHeight = 22
        val visible = maxOf(0, (layout.footerTop - layout.bodyTop) / rowHeight)
        listScroll = listScroll.coerceIn(0, maxOf(0, rows.size - visible))
        graphics.enableScissor(layout.leftX + 5, layout.bodyTop, layout.leftX + layout.leftWidth - 3, layout.footerTop)
        if (editingFloor == null) {
            graphics.text(font, fit("Choose a floor above to view its route.", layout.leftWidth - 18),
                layout.leftX + 10, layout.bodyTop + 8, GuiTheme.TEXT_MUTED)
        } else if (rows.isEmpty()) {
            graphics.text(font, if (simulatedPhase == null) "No steps yet. Add one below." else "No steps in this simulated phase.",
                layout.leftX + 10, layout.bodyTop + 8, GuiTheme.TEXT_MUTED)
        }
        for (i in listScroll until minOf(rows.size, listScroll + visible)) {
            val row = rows[i]
            val y = layout.bodyTop + (i - listScroll) * rowHeight
            val node = row.node
            if (node == null) {
                graphics.text(font, row.phaseLabel, layout.leftX + 9, y + 5, GuiTheme.TEXT_MUTED)
                continue
            }
            val selected = node.id == selectedId
            val hovered = mouseX >= layout.leftX + 5 && mouseX < layout.leftX + layout.leftWidth - 3 &&
                mouseY >= y && mouseY < y + rowHeight
            if (selected || hovered) {
                GuiTheme.roundedRect(graphics, layout.leftX + 5, y, layout.leftWidth - 9, rowHeight - 1,
                    GuiTheme.RADIUS_SMALL, if (selected) GuiTheme.CATEGORY_SELECTED else GuiTheme.CATEGORY_HOVER)
            }
            graphics.fill(layout.leftX + 10, y + 8, layout.leftX + 15, y + 13, node.color)
            graphics.text(font, fit(node.label, layout.leftWidth - 36), layout.leftX + 20, y + 4, GuiTheme.TEXT_PRIMARY)
        }
        graphics.disableScissor()
    }

    private fun renderInspector(graphics: GuiGraphicsExtractor, layout: Layout, mouseX: Int, mouseY: Int) {
        graphics.enableScissor(layout.rightX + 5, layout.bodyTop, layout.rightX + layout.rightWidth - 3, layout.footerTop)
        val node = selected()
        if (node == null) {
            val emptyState = if (editingFloor == null) "Choose a floor before adding or editing route steps."
                else "Select a step or add one at your position."
            graphics.text(font, fit(emptyState, layout.rightWidth - 18), layout.rightX + 10, layout.bodyTop + 8, GuiTheme.TEXT_MUTED)
            graphics.disableScissor()
            return
        }
        val x = layout.rightX + 8
        val width = maxOf(1, layout.rightWidth - 16)
        val row = 28
        var y = layout.bodyTop - detailScroll
        y += row * 2
        drawButton(graphics, GuiRect(x, y, (width - 4) / 2, 19), "Phase: ${phaseLabel(node)}", mouseX, mouseY)
        drawButton(graphics, GuiRect(x + (width + 4) / 2, y, (width - 4) / 2, 19),
            "Shape: ${shapeLabel(node.shape)}", mouseX, mouseY)
        y += row
        drawButton(graphics, GuiRect(x, y, maxOf(1, width), 19), "Condition rule: ${node.conditionRule}", mouseX, mouseY)
        y += row
        drawConditionRow(graphics, node, arrayOf("Manual", "Radius", "Delay"), x, y, width, mouseX, mouseY)
        y += 22
        drawConditionRow(graphics, node, arrayOf("Chat", "Detected phase"), x, y, width, mouseX, mouseY)
        y += row
        drawButton(graphics, GuiRect(x, y, width, 19), "Skip ahead: ${if (node.skipAhead) "On" else "Off"}", mouseX, mouseY,
            if (node.skipAhead) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
        y += row * 2 // parameter row and chat phrase input
        y += row // chat match mode
        drawButton(graphics, GuiRect(x, y, width, 19), "Chat: ${chatMatchLabel(node.chatMatch)}", mouseX, mouseY)
        y += row
        if (node.hasCondition(DungeonGuideNode.Condition.ENTER_RADIUS) || node.hasCondition(DungeonGuideNode.Condition.AFTER_SECONDS)) {
            // Parameter fields at this row are self-labeled by their placeholders.
        }
        if (node.hasCondition(DungeonGuideNode.Condition.CHAT_EVENT)) {
            // The chat phrase input is available on the following row.
        }
        drawButton(graphics, GuiRect(x, y, width, 19), if (advancedOpen) "Appearance and macro ▾" else "Appearance and macro ▸", mouseX, mouseY)
        if (advancedOpen) {
            val half = maxOf(1, (width - 4) / 2)
            val gap = 4
            val colorY = y + 56
            drawColorPickerButton(graphics, GuiRect(x, colorY, half, 19), "Outline color", node.color, mouseX, mouseY)
            drawColorPickerButton(graphics, GuiRect(x + half + gap, colorY, half, 19), "Fill color", node.fillColor, mouseX, mouseY)
            val textY = y + 84
            drawColorPickerButton(graphics, GuiRect(x, textY, half, 19), "Text color", node.labelColor, mouseX, mouseY)
            if (macroSearch != null && macroSearch!!.y >= layout.bodyTop) renderMacroResults(graphics, layout, node, mouseX, mouseY)
            if (node.shape == DungeonGuideNode.Shape.RING) {
                val ringHeaderY = layout.bodyTop - detailScroll + 414
                graphics.text(font, "Ring appearance · this step", x, ringHeaderY + 5, GuiTheme.TEXT_MUTED)
                drawButton(graphics, ringFillBounds(layout), "Fill interior: ${if (node.ringFillEnabled()) "On" else "Off"}", mouseX, mouseY,
                    if (node.ringFillEnabled()) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
            }
        }
        if (shapePickerOpen) renderShapePicker(graphics, layout, node, mouseX, mouseY)
        graphics.disableScissor()
    }

    private fun drawColorPickerButton(graphics: GuiGraphicsExtractor, bounds: GuiRect, label: String, argb: Int,
        mouseX: Int, mouseY: Int) {
        val hovered = inside(mouseX.toDouble(), mouseY.toDouble(), bounds.x, bounds.y, bounds.w, bounds.h)
        val fill = if (hovered) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG
        GuiTheme.roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, GuiTheme.RADIUS_SMALL, fill, fill, GuiTheme.BORDER)
        val size = minOf(12, maxOf(7, bounds.h - 6))
        val x = bounds.x + 4
        val y = bounds.y + (bounds.h - size) / 2
        graphics.fill(x, y, x + size, y + size, 0xFF777777.toInt())
        graphics.fill(x + 2, y + 2, x + size - 1, y + size - 1, argb)
        graphics.centeredText(font, fit(label, maxOf(1, bounds.w - size - 12)),
            bounds.x + size + 8 + maxOf(1, (bounds.w - size - 12) / 2),
            bounds.y + (bounds.h - font.lineHeight) / 2, GuiTheme.TEXT_PRIMARY)
    }

    private fun ringFillBounds(layout: Layout): GuiRect {
        val width = maxOf(1, layout.rightWidth - 16)
        val half = maxOf(1, (width - 4) / 2)
        val x = layout.rightX + 8
        val ringRowThree = layout.bodyTop - detailScroll + 434 + 2 * 28
        return GuiRect(x + half + 4, ringRowThree, half, 19)
    }

    private fun renderShapePicker(graphics: GuiGraphicsExtractor, layout: Layout, node: DungeonGuideNode,
        mouseX: Int, mouseY: Int) {
        val options = shapeOptions(layout)
        val panel = GuiRect(options[0].x - 3, options[0].y - 3,
            options[1].x + options[1].w - options[0].x + 6,
            options[2].y + options[2].h - options[0].y + 6)
        GuiTheme.roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS_SMALL,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        val labels = arrayOf("3D Box", "Beacon", "Ring", "Text")
        for (i in options.indices) {
            val fill = if ((node.shape?.ordinal ?: -1) == i) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG
            drawButton(graphics, options[i], labels[i], mouseX, mouseY, fill)
        }
    }

    private fun shapeOptions(layout: Layout): Array<GuiRect> {
        val width = maxOf(1, layout.rightWidth - 16)
        val half = maxOf(1, (width - 4) / 2)
        val x = layout.rightX + 8 + (width + 4) / 2
        val y = layout.bodyTop - detailScroll + 2 * 28 + 22
        val gap = 3
        val cellWidth = maxOf(1, (half - gap) / 2)
        return arrayOf(
            GuiRect(x, y, cellWidth, 19), GuiRect(x + cellWidth + gap, y, cellWidth, 19),
            GuiRect(x, y + 22, cellWidth, 19), GuiRect(x + cellWidth + gap, y + 22, cellWidth, 19),
        )
    }

    private fun drawConditionRow(graphics: GuiGraphicsExtractor, node: DungeonGuideNode, labels: Array<String>,
        x: Int, y: Int, width: Int, mouseX: Int, mouseY: Int) {
        val gap = 3
        val buttonWidth = (width - gap * (labels.size - 1)) / labels.size
        for (i in labels.indices) {
            val condition = conditionFor(labels[i])
            val active = node.hasCondition(condition)
            val left = x + i * (buttonWidth + gap)
            drawButton(graphics, GuiRect(left, y, buttonWidth, 19), (if (active) "✓ " else "□ ") + labels[i], mouseX, mouseY,
                if (active) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
        }
    }

    private fun renderMacroResults(graphics: GuiGraphicsExtractor, layout: Layout, node: DungeonGuideNode, mouseX: Int, mouseY: Int) {
        val x = layout.rightX + 8
        val y = macroSearch!!.y + 21
        val row = 19
        val visible = maxOf(0, (layout.footerTop - y) / row)
        val macros = filteredMacros()
        macroScroll = macroScroll.coerceIn(0, maxOf(0, macros.size - visible))
        if (visible > 0) graphics.enableScissor(x, y, layout.rightX + layout.rightWidth - 8, layout.footerTop)
        for (i in macroScroll until minOf(macros.size, macroScroll + visible)) {
            val macro = macros[i]
            val value = (if (node.macroId == macro.id()) "✓ " else "") + macro.name()
            drawButton(graphics, GuiRect(x, y + (i - macroScroll) * row, layout.rightWidth - 16, row - 1),
                fit(value, layout.rightWidth - 28), mouseX, mouseY)
        }
        if (visible > 0) graphics.disableScissor()
    }

    private fun renderFooter(graphics: GuiGraphicsExtractor, layout: Layout, mouseX: Int, mouseY: Int) {
        val actions = arrayOf("Add at Feet", "Add at Aim", "Copy Route", "Paste Route", "Delete Step", "Done")
        val columns = 3
        val gap = 4
        val buttonWidth = (layout.width - 16 - gap * (columns - 1)) / columns
        for (i in actions.indices) {
            val x = layout.x + 8 + (i % columns) * (buttonWidth + gap)
            val y = layout.footerTop + (i / columns) * 21
            drawButton(graphics, GuiRect(x, y, buttonWidth, 19), actions[i], mouseX, mouseY)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val layout = layout()
        if (shapePickerOpen && event.button() == 0) {
            val options = shapeOptions(layout)
            for (i in options.indices) {
                val option = options[i]
                if (inside(event.x(), event.y(), option.x, option.y, option.w, option.h)) {
                    val node = selected()
                    if (node != null) {
                        node.shape = DungeonGuideNode.Shape.values()[i]
                        if (node.shape == DungeonGuideNode.Shape.RING) node.useDefaultRingAppearanceIfUnset()
                        DungeonGuideStore.changed()
                    }
                    shapePickerOpen = false
                    rebuildEditorWidgets()
                    return true
                }
            }
            shapePickerOpen = false
            rebuildEditorWidgets()
        }
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val simulation = simulationButton(layout)
        if (inside(event.x(), event.y(), simulation.x, simulation.y, simulation.w, simulation.h)) {
            cycleSimulationPhase()
            return true
        }
        if (inside(event.x(), event.y(), layout.x + 12, layout.y + 38, maxOf(1, layout.leftWidth - 24), 19)) {
            val floors = DungeonFloor.values()
            editingFloor = if (editingFloor == null) floors[0] else floors[(editingFloor!!.ordinal + 1) % floors.size]
            simulatedPhase = null
            selectedId = null
            listScroll = 0
            detailScroll = 0
            macroScroll = 0
            rebuildEditorWidgets()
            return true
        }
        val action = actionIndex(event.x(), event.y(), layout)
        if (action >= 0) {
            when (action) {
                0 -> addAtPosition(false)
                1 -> addAtPosition(true)
                2 -> copyRoute()
                3 -> pasteRoute()
                4 -> removeSelected()
                5 -> onClose()
            }
            return true
        }
        val node = selected()
        if (node != null) {
            val x = layout.rightX + 8
            val width = maxOf(1, layout.rightWidth - 16)
            var y = layout.bodyTop - detailScroll + 2 * 28
            val half = (width - 4) / 2
            if (inside(event.x(), event.y(), x, y, half, 19)) { cyclePhase(node); return true }
            if (inside(event.x(), event.y(), x + half + 4, y, half, 19)) {
                shapePickerOpen = !shapePickerOpen
                rebuildEditorWidgets()
                return true
            }
            y += 28
            if (inside(event.x(), event.y(), x, y, width, 19)) {
                node.conditionRule = if (node.conditionRule == DungeonGuideNode.ConditionRule.ALL)
                    DungeonGuideNode.ConditionRule.ANY else DungeonGuideNode.ConditionRule.ALL
                DungeonGuideStore.changed()
                rebuildEditorWidgets()
                return true
            }
            y += 28
            if (hitConditionRow(event.x(), event.y(), node, arrayOf("Manual", "Radius", "Delay"), x, y, width)) return true
            y += 22
            if (hitConditionRow(event.x(), event.y(), node, arrayOf("Chat", "Detected phase"), x, y, width)) return true
            y += 28
            if (inside(event.x(), event.y(), x, y, width, 19)) {
                node.skipAhead = !node.skipAhead
                DungeonGuideStore.changed()
                return true
            }
            y += 84
            if (inside(event.x(), event.y(), x, y, width, 19)) {
                node.chatMatch = if (node.chatMatch == DungeonGuideNode.ChatMatch.EXACT)
                    DungeonGuideNode.ChatMatch.CONTAINS else DungeonGuideNode.ChatMatch.EXACT
                DungeonGuideStore.changed()
                return true
            }
            y += 28
            if (inside(event.x(), event.y(), x, y, width, 19)) {
                advancedOpen = !advancedOpen
                detailScroll = 0
                rebuildEditorWidgets()
                return true
            }
            if (advancedOpen) {
                val colorHalf = maxOf(1, (width - 4) / 2)
                val colorY = y + 56
                if (inside(event.x(), event.y(), x, colorY, colorHalf, 19)) {
                    openColorPicker("Outline color", node.color, 0)
                    return true
                }
                if (inside(event.x(), event.y(), x + colorHalf + 4, colorY, colorHalf, 19)) {
                    openColorPicker("Fill color", node.fillColor, 1)
                    return true
                }
                if (inside(event.x(), event.y(), x, y + 84, colorHalf, 19)) {
                    openColorPicker("Text color", node.labelColor, 2)
                    return true
                }
                if (node.shape == DungeonGuideNode.Shape.RING) {
                    val fillBounds = ringFillBounds(layout)
                    if (inside(event.x(), event.y(), fillBounds.x, fillBounds.y, fillBounds.w, fillBounds.h)) {
                        node.ringFill = !node.ringFillEnabled()
                        DungeonGuideStore.changed()
                        rebuildEditorWidgets()
                        return true
                    }
                }
            }
            val searchBox = macroSearch
            if (advancedOpen && searchBox != null) {
                val macroY = searchBox.y + 21
                if (event.x() >= x.toDouble() && event.x() < (x + width).toDouble() &&
                    event.y() >= macroY.toDouble() && event.y() < layout.footerTop.toDouble()
                ) {
                    val index = macroScroll + ((event.y() - macroY) / 19).toInt()
                    val macros = filteredMacros()
                    if (index >= 0 && index < macros.size) updateSelected(Consumer { it.macroId = macros[index].id() })
                    return true
                }
            }
        }
        if (event.x() >= (layout.leftX + 5).toDouble() && event.x() < (layout.leftX + layout.leftWidth - 3).toDouble() &&
            event.y() >= layout.bodyTop.toDouble() && event.y() < layout.footerTop.toDouble()
        ) {
            val index = listScroll + ((event.y() - layout.bodyTop) / 22).toInt()
            val rows = rows()
            if (index >= 0 && index < rows.size && rows[index].node != null) {
                selectedId = rows[index].node!!.id
                detailScroll = 0
                macroScroll = 0
                rebuildEditorWidgets()
            }
            return true
        }
        return false
    }

    private fun hitConditionRow(mx: Double, my: Double, node: DungeonGuideNode, labels: Array<String>, x: Int, y: Int, width: Int): Boolean {
        val gap = 3
        val buttonWidth = (width - gap * (labels.size - 1)) / labels.size
        if (my < y || my >= y + 19) return false
        for (i in labels.indices) {
            val left = x + i * (buttonWidth + gap)
            if (mx < left || mx >= left + buttonWidth) continue
            val condition = conditionFor(labels[i])
            val updated = if (node.conditions == null) EnumSet.noneOf(DungeonGuideNode.Condition::class.java)
                else EnumSet.copyOf(node.conditions)
            if (updated.contains(condition)) updated.remove(condition) else updated.add(condition)
            if (updated.isEmpty()) updated.add(DungeonGuideNode.Condition.MANUAL)
            node.conditions = updated
            node.updateLegacyTrigger()
            DungeonGuideStore.changed()
            rebuildEditorWidgets()
            return true
        }
        return false
    }

    private fun cyclePhase(node: DungeonGuideNode) {
        val phases = phaseIds()
        val current = phases.indexOf(node.phase)
        node.phase = phases[(current + 1 + phases.size) % phases.size]
        DungeonGuideStore.changed()
        rebuildEditorWidgets()
    }

    private fun cycleSimulationPhase() {
        val phases = phaseIds()
        if (phases.isEmpty()) {
            simulatedPhase = null
            notice = "Choose a floor before simulating a phase"
            return
        }
        val index = phases.indexOf(simulatedPhase)
        simulatedPhase = if (index < 0) phases[0] else if (index + 1 >= phases.size) null else phases[index + 1]
        listScroll = 0
        if (simulatedPhase == null) {
            notice = "Phase simulation off; the live route was not changed"
        } else {
            val previewRows = rows()
            selectedId = previewRows.firstOrNull { it.node != null }?.node?.id
            notice = "Previewing ${phaseLabelFor(simulatedPhase)} steps; the live route was not changed"
        }
        detailScroll = 0
        macroScroll = 0
        rebuildEditorWidgets()
    }

    private fun phaseLabelFor(phaseId: String?): String {
        for (segment in DungeonGuideSegments.builtIn(editingFloor)) if (segment.id == phaseId) return segment.label
        for (segment in DungeonGuideStore.customSegments(editingFloor)) if (segment.id == phaseId) return segment.label
        return phaseId?.replace('_', ' ') ?: "Off"
    }

    private fun simulationButton(layout: Layout): GuiRect {
        val width = minOf(132, maxOf(94, layout.rightWidth / 2))
        return GuiRect(layout.rightX + layout.rightWidth - width - 8, layout.y + 36, width, 19)
    }

    private fun simulationLabel(): String = simulatedPhase?.let { "Simulate: ${phaseLabelFor(it)}" } ?: "Simulate: Off"

    private fun phaseIds(): List<String> {
        val floor = editingFloor ?: return emptyList()
        val phases = ArrayList<String>()
        DungeonGuideSegments.builtIn(floor).forEach { phases.add(it.id) }
        DungeonGuideStore.customSegments(floor).forEach { phases.add(it.id) }
        return if (phases.isEmpty()) listOf("ENTRY") else phases
    }

    private fun phaseLabel(node: DungeonGuideNode): String {
        for (segment in DungeonGuideSegments.builtIn(editingFloor)) if (segment.id == node.phase) return segment.label
        for (segment in DungeonGuideStore.customSegments(editingFloor)) if (segment.id == node.phase) return segment.label
        return node.phase.replace('_', ' ')
    }

    private fun shapeLabel(shape: DungeonGuideNode.Shape?): String = when (shape) {
        DungeonGuideNode.Shape.BOX -> "3D Box"
        DungeonGuideNode.Shape.BEACON -> "Beacon"
        DungeonGuideNode.Shape.RING -> "Ring"
        DungeonGuideNode.Shape.TEXT_ONLY -> "Text only"
        null -> "Beacon"
    }

    private fun chatMatchLabel(match: DungeonGuideNode.ChatMatch?): String =
        if (match == DungeonGuideNode.ChatMatch.CONTAINS) "Contains" else "Exact"

    private fun conditionFor(label: String): DungeonGuideNode.Condition = when (label) {
        "Radius" -> DungeonGuideNode.Condition.ENTER_RADIUS
        "Delay" -> DungeonGuideNode.Condition.AFTER_SECONDS
        "Chat" -> DungeonGuideNode.Condition.CHAT_EVENT
        "Detected phase" -> DungeonGuideNode.Condition.DETECTED_PHASE
        else -> DungeonGuideNode.Condition.MANUAL
    }

    private fun rows(): List<ListRow> {
        val revision = DungeonGuideStore.guideRevision()
        if (cachedRowsRevision == revision && cachedRowsFloor == editingFloor && cachedRowsSearch == searchText &&
            cachedRowsSimulation == simulatedPhase
        ) return cachedRows
        cachedRowsRevision = revision
        cachedRowsFloor = editingFloor
        cachedRowsSearch = searchText
        cachedRowsSimulation = simulatedPhase
        val floor = editingFloor ?: run { cachedRows = emptyList(); return cachedRows }
        val needle = ChatStringSemantics.trim(searchText).lowercase(Locale.ROOT)
        val nodes = DungeonGuideStore.all()
            .filter { it.floor == floor.displayName() }
            .filter { simulatedPhase == null || simulatedPhase == it.phase }
            .filter {
                needle.isEmpty() || it.label.lowercase(Locale.ROOT).contains(needle) ||
                    it.phase.lowercase(Locale.ROOT).contains(needle) || it.eventText.lowercase(Locale.ROOT).contains(needle)
            }
            .sortedWith(compareBy<DungeonGuideNode> { DungeonGuideStore.phaseOrder(it.floor, it.phase) }
                .thenBy { it.phase }.thenBy { it.order })
        val result = ArrayList<ListRow>()
        var previous: String? = null
        for (node in nodes) {
            if (node.phase != previous) {
                result.add(ListRow(null, phaseLabel(node.phase)))
                previous = node.phase
            }
            result.add(ListRow(node, ""))
        }
        cachedRows = java.util.List.copyOf(result)
        return cachedRows
    }

    private fun phaseLabel(phase: String): String {
        for (segment in DungeonGuideSegments.builtIn(editingFloor)) if (segment.id == phase) return segment.label
        for (segment in DungeonGuideStore.customSegments(editingFloor)) if (segment.id == phase) return segment.label
        return phase.replace('_', ' ')
    }

    private fun filteredMacros(): List<MacroDefinition> {
        val needle = ChatStringSemantics.trim(macroSearchText).lowercase(Locale.ROOT)
        return MacrosModule.INSTANCE.macros().filter {
            needle.isEmpty() || it.name().lowercase(Locale.ROOT).contains(needle)
        }.sortedBy { it.name().lowercase(Locale.ROOT) }
    }

    private fun addAtPosition(aim: Boolean) {
        val floor = editingFloor
        if (floor == null) {
            notice = "Choose a floor before adding a route step"
            return
        }
        val client = Minecraft.getInstance()
        var pos = client.player?.blockPosition() ?: BlockPos.ZERO
        if (aim && client.player != null) {
            val hit = client.player!!.pick(6.0, 0.0f, false)
            if (hit.type == HitResult.Type.BLOCK) pos = (hit as BlockHitResult).blockPos
        }
        val floorNodes = DungeonGuideStore.all().filter { it.floor == floor.displayName() }
        val detectedPhase = if (floor == DungeonContextTracker.currentFloor()) DungeonHelperModule.INSTANCE.detectedPhaseFor(floor) else null
        val phases = phaseIds()
        val selectedPhase = selected()?.phase
        val phase = DungeonGuidePhaseTracker.preferredWaypointPhase(phases, detectedPhase, selectedPhase)
        val node = DungeonGuideNode(floor, phase, floorNodes.size, pos.x, pos.y, pos.z)
        node.routeName = DungeonGuideStore.routeName(floor.displayName())
        node.label = "Step ${floorNodes.size + 1}"
        DungeonGuideStore.add(node)
        selectedId = node.id
        notice = "Added step to ${node.routeName}"
        detailScroll = 0
        macroScroll = 0
        rebuildEditorWidgets()
    }

    private fun copyRoute() {
        val encoded = DungeonGuideStore.exportPackage()
        if (encoded == null) notice = "Route package exceeds the size limit" else {
            Minecraft.getInstance().keyboardHandler.setClipboard(encoded)
            notice = "Copied guide route"
        }
    }

    private fun pasteRoute() {
        if (editingFloor == null) {
            notice = "Choose a floor before importing route steps"
            return
        }
        val result = DungeonGuideStore.importPackage(Minecraft.getInstance().keyboardHandler.clipboard)
        if (result.success) DungeonHelperModule.INSTANCE.migrateLegacyRingAppearance()
        notice = if (result.success) "Imported ${result.imported} step(s)" else result.error ?: "Import failed"
        rebuildEditorWidgets()
    }

    private fun removeSelected() {
        val node = selected()
        if (node != null) {
            DungeonGuideStore.remove(node.id)
            selectedId = null
            notice = "Step deleted"
            rebuildEditorWidgets()
        }
    }

    private fun updateSelected(change: Consumer<DungeonGuideNode>) {
        val node = selected()
        if (node != null) {
            change.accept(node)
            DungeonGuideStore.changed()
        }
    }

    private fun selected(): DungeonGuideNode? = DungeonGuideStore.all().firstOrNull { it.id == selectedId }

    private fun parseInt(value: String, fallback: Int): Int = try {
        ChatStringSemantics.trim(value).toInt()
    } catch (_: NumberFormatException) {
        fallback
    }

    private fun parseFloat(value: String, fallback: Float): Float = try {
        ChatStringSemantics.trim(value).toFloat().let { if (it.isFinite()) it else fallback }
    } catch (_: NumberFormatException) {
        fallback
    }

    private fun openColorPicker(title: String, color: Int, which: Int) {
        val target = selected() ?: return
        minecraft?.gui?.setScreen(GuiColorPickerScreen(this, title, color) { argb ->
            if (which == 0) target.color = argb
            else if (which == 1) target.fillColor = argb
            else target.labelColor = argb
            DungeonGuideStore.changed()
            rebuildEditorWidgets()
        })
    }

    private fun inside(mx: Double, my: Double, x: Int, y: Int, width: Int, height: Int): Boolean =
        mx >= x && mx < x + width && my >= y && my < y + height

    private fun fit(value: String, width: Int): String = GuiTheme.fit(font, value, width)

    private fun drawButton(graphics: GuiGraphicsExtractor, bounds: GuiRect, text: String, mouseX: Int, mouseY: Int) {
        drawButton(graphics, bounds, text, mouseX, mouseY,
            if (mouseX >= bounds.x && mouseX < bounds.x + bounds.w && mouseY >= bounds.y && mouseY < bounds.y + bounds.h)
                GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
    }

    private fun drawButton(graphics: GuiGraphicsExtractor, bounds: GuiRect, text: String, mouseX: Int, mouseY: Int, fill: Int) {
        GuiTheme.roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, GuiTheme.RADIUS_SMALL, fill)
        graphics.centeredText(font, fit(text, bounds.w - 8), bounds.x + bounds.w / 2,
            bounds.y + (bounds.h - font.lineHeight) / 2, GuiTheme.TEXT_PRIMARY)
    }

    private fun actionIndex(x: Double, y: Double, layout: Layout): Int {
        val gap = 4
        val columns = 3
        val buttonWidth = (layout.width - 16 - gap * 2) / columns
        if (y < layout.footerTop || y >= layout.y + layout.height - 8) return -1
        val row = ((y - layout.footerTop) / 21).toInt()
        val column = ((x - layout.x - 8) / (buttonWidth + gap)).toInt()
        if (row < 0 || row > 1 || column < 0 || column >= columns) return -1
        val left = layout.x + 8 + column * (buttonWidth + gap)
        if (x < left || x >= left + buttonWidth) return -1
        return row * columns + column
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val layout = layout()
        if (mouseX >= layout.leftX && mouseX < layout.leftX + layout.leftWidth &&
            mouseY >= layout.bodyTop && mouseY < layout.footerTop
        ) {
            listScroll = maxOf(0, listScroll - java.lang.Math.round(scrollY * 3).toInt())
            return true
        }
        if (mouseX >= layout.rightX && mouseX < layout.rightX + layout.rightWidth &&
            mouseY >= layout.bodyTop && mouseY < layout.footerTop
        ) {
            val searchBox = macroSearch
            if (advancedOpen && searchBox != null && mouseY >= searchBox.y) {
                macroScroll = maxOf(0, macroScroll - java.lang.Math.round(scrollY * 2).toInt())
            } else {
                detailScroll = (detailScroll - java.lang.Math.round(scrollY * 3).toInt()).coerceIn(0, 600)
            }
            rebuildEditorWidgets()
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE &&
            (search == null || !search!!.isFocused) && (routeName == null || !routeName!!.isFocused) &&
            (macroSearch == null || !macroSearch!!.isFocused)
        ) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean = super.charTyped(event)

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun layout(): Layout {
        val width = maxOf(240, minOf(780, this.width - 12))
        val height = maxOf(260, minOf(560, this.height - 12))
        val x = (this.width - width) / 2
        val y = (this.height - height) / 2
        val leftWidth = maxOf(115, minOf(245, width * 34 / 100))
        val leftX = x + 8
        val rightX = leftX + leftWidth
        val rightWidth = maxOf(1, width - leftWidth - 16)
        val bodyTop = y + 82
        val footerTop = y + height - 54
        return Layout(x, y, width, height, leftX, leftWidth, rightX, rightWidth, bodyTop, footerTop)
    }

    private data class Layout(
        val x: Int, val y: Int, val width: Int, val height: Int,
        val leftX: Int, val leftWidth: Int, val rightX: Int, val rightWidth: Int,
        val bodyTop: Int, val footerTop: Int,
    )

    private data class ListRow(val node: DungeonGuideNode?, val phaseLabel: String)
}
