package geiler.addons.client.gui

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideCustomSegment
import geiler.addons.client.dungeon.DungeonGuideSegments
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.module.impl.DungeonHelperModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.util.function.Consumer

/** Stage ordering, manual correction, and custom triggers for Dungeon Guide. */
class DungeonGuideStagesScreen(private val parent: Screen?) : Screen(Component.literal("Dungeon Guide Stages")) {
    private var floor: DungeonFloor? = DungeonContextTracker.currentFloor()
    private var selectedId = "ENTRY"
    private var notice = ""
    private var scroll = 0
    private var label: EditBox? = null
    private var event: EditBox? = null
    private var position: EditBox? = null
    private var radius: EditBox? = null
    private var seconds: EditBox? = null

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        val selected = selectedCustom() ?: return
        val layout = layout()
        val x = layout.right + 8
        val width = maxOf(1, layout.w - layout.leftWidth - 20)
        label = field(x, layout.y + 96, width, selected.label) { value ->
            selected.label = value
            DungeonGuideStore.customSegmentChanged()
        }
        event = field(x, layout.y + 132, width, selected.eventText) { value ->
            selected.eventText = value
            DungeonGuideStore.customSegmentChanged()
        }
        position = field(x, layout.y + 168, width, "${selected.x},${selected.y},${selected.z}") { value ->
            val parts = value.split(',', ignoreCase = false, limit = Int.MAX_VALUE)
            if (parts.size != 3) return@field
            try {
                selected.x = ChatStringSemantics.trim(parts[0]).toInt()
                selected.y = ChatStringSemantics.trim(parts[1]).toInt()
                selected.z = ChatStringSemantics.trim(parts[2]).toInt()
                DungeonGuideStore.customSegmentChanged()
            } catch (_: NumberFormatException) {
            }
        }
        radius = field(x, layout.y + 204, width, selected.radius.toString()) { value ->
            try {
                selected.radius = value.toFloat()
                DungeonGuideStore.customSegmentChanged()
            } catch (_: NumberFormatException) {
            }
        }
        seconds = field(x, layout.y + 240, width, selected.seconds.toString()) { value ->
            try {
                selected.seconds = value.toFloat()
                DungeonGuideStore.customSegmentChanged()
            } catch (_: NumberFormatException) {
            }
        }
    }

    private fun field(x: Int, y: Int, width: Int, value: String, responder: (String) -> Unit): EditBox {
        val box = addRenderableWidget(EditBox(font, x, y, width, 18, Component.literal("Stage setting")))
        box.setMaxLength(128)
        box.setValue(value)
        box.setResponder(Consumer { responder(it) })
        return box
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val layout = layout()
        GuiTheme.roundedRectBordered(graphics, layout.x, layout.y, layout.w, layout.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, fit("Dungeon Guide · Stages", layout.w - 24), layout.x + 12, layout.y + 11, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, fit("Auto detection: ${DungeonHelperModule.INSTANCE.phaseEvidence()}", layout.w - 24),
            layout.x + 12, layout.y + 26, GuiTheme.TEXT_MUTED)
        button(graphics, layout.x + 8, layout.y + 44, layout.leftWidth - 12, 19,
            "Floor: ${floor?.displayName() ?: "Unknown"}", mouseX, mouseY)
        graphics.fill(layout.right, layout.y + 40, layout.right + 1, layout.y + layout.h - 54, GuiTheme.BORDER)
        val stages = stages()
        val rowTop = layout.y + 70
        val rowHeight = 20
        val visible = maxOf(1, (layout.h - 125) / rowHeight)
        scroll = scroll.coerceIn(0, maxOf(0, stages.size - visible))
        graphics.enableScissor(layout.x + 8, rowTop, layout.right - 4, layout.y + layout.h - 55)
        if (floor == null) graphics.text(font, fit("Choose a floor above to view its stages.", layout.leftWidth - 20),
            layout.x + 12, rowTop + 6, GuiTheme.TEXT_MUTED)
        for (i in scroll until stages.size) {
            val y = rowTop + (i - scroll) * rowHeight
            if (y >= layout.y + layout.h - 55) break
            val stage = stages[i]
            if (stage.id == selectedId) GuiTheme.roundedRect(graphics, layout.x + 8, y, layout.leftWidth - 12, 18, 3,
                GuiTheme.CATEGORY_SELECTED)
            graphics.text(font, fit(stage.label, layout.leftWidth - 24), layout.x + 14, y + 5, GuiTheme.TEXT_PRIMARY)
        }
        graphics.disableScissor()
        val custom = selectedCustom()
        graphics.text(font, if (custom == null) "Built-in stage" else "Custom stage", layout.right + 8, layout.y + 49,
            GuiTheme.TEXT_PRIMARY)
        graphics.text(font, fit(selectedId, layout.w - layout.leftWidth - 22), layout.right + 8, layout.y + 67,
            GuiTheme.TEXT_MUTED)
        if (custom != null) {
            val names = arrayOf("Stage name", "Exact chat event", "Location · x,y,z", "Location radius", "Timer · seconds")
            for (i in names.indices) graphics.text(font, names[i], layout.right + 8, layout.y + 82 + i * 36, GuiTheme.TEXT_MUTED)
            graphics.text(font, "Trigger: ${custom.trigger} · after built-in #${custom.afterBuiltInIndex}",
                layout.right + 8, layout.y + 270, GuiTheme.TEXT_SECONDARY)
        }
        val buttons = arrayOf("Add Custom", "Use Stage", "Auto", "Trigger", "Earlier", "Later", "Remove", "Done")
        val buttonWidth = maxOf(1, (layout.w - 20) / 4)
        val top = layout.y + layout.h - 48
        for (i in buttons.indices) {
            button(graphics, layout.x + 5 + (i % 4) * (buttonWidth + 3), top + (i / 4) * 21,
                buttonWidth, 19, buttons[i], mouseX, mouseY)
        }
        if (!ChatStringSemantics.isBlank(notice)) graphics.text(font, fit(notice, layout.w - 20), layout.x + 10,
            layout.y + layout.h - 61, GuiTheme.TEXT_MUTED)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(click, doubleClick)) return true
        if (click.button() != 0) return false
        val layout = layout()
        if (inside(click, layout.x + 8, layout.y + 44, layout.leftWidth - 12, 19)) {
            val floors = DungeonFloor.values()
            floor = if (floor == null) floors[0] else floors[(floor!!.ordinal + 1) % floors.size]
            selectedId = "ENTRY"
            scroll = 0
            rebuildWidgets()
            return true
        }
        val rowTop = layout.y + 70
        if (click.x() >= (layout.x + 8).toDouble() && click.x() < (layout.right - 4).toDouble() &&
            click.y() >= rowTop.toDouble() && click.y() < (layout.y + layout.h - 55).toDouble()
        ) {
            val row = ((click.y() - rowTop) / 20).toInt() + scroll
            val stages = stages()
            if (row >= 0 && row < stages.size) {
                selectedId = stages[row].id
                rebuildWidgets()
            }
            return true
        }
        val buttonWidth = maxOf(1, (layout.w - 20) / 4)
        val top = layout.y + layout.h - 48
        for (i in 0 until 8) {
            if (!inside(click, layout.x + 5 + (i % 4) * (buttonWidth + 3), top + (i / 4) * 21, buttonWidth, 19)) continue
            val selected = selectedCustom()
            when (i) {
                0 -> {
                    if (floor == null) {
                        notice = "Choose a floor before adding a custom stage"
                    } else {
                        val fresh = DungeonGuideCustomSegment()
                        fresh.floor = floor!!.displayName()
                        fresh.afterBuiltInIndex = 1
                        DungeonGuideStore.addCustomSegment(fresh)
                        selectedId = fresh.id
                        rebuildWidgets()
                    }
                }
                1 -> notice = if (floor == null) "Choose a floor before selecting a stage" else
                    if (DungeonHelperModule.INSTANCE.setPhase(selectedId)) "Manual stage: $selectedId"
                    else "Enter this floor before choosing its stage"
                2 -> {
                    DungeonHelperModule.INSTANCE.resumeAutomaticPhase()
                    notice = "Automatic stage detection resumed"
                }
                3 -> if (selected != null) {
                    val triggers = DungeonGuideCustomSegment.Trigger.values()
                    selected.trigger = triggers[(selected.trigger!!.ordinal + 1) % triggers.size]
                    DungeonGuideStore.customSegmentChanged()
                }
                4 -> if (selected != null) DungeonGuideStore.moveCustomSegment(selected.id, -1)
                5 -> if (selected != null) DungeonGuideStore.moveCustomSegment(selected.id, 1)
                6 -> if (selected != null) {
                    val removed = DungeonGuideStore.removeCustomSegment(selected.id)
                    notice = if (removed) "Stage removed" else "Remove its guide steps first"
                    if (removed) {
                        selectedId = "ENTRY"
                        rebuildWidgets()
                    }
                }
                7 -> onClose()
            }
            return true
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val layout = layout()
        if (mouseX >= (layout.x + 8).toDouble() && mouseX < (layout.right - 4).toDouble() &&
            mouseY >= (layout.y + 70).toDouble() && mouseY < (layout.y + layout.h - 55).toDouble()
        ) {
            scroll = maxOf(0, scroll - java.lang.Math.round(scrollY * 3).toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun selectedCustom(): DungeonGuideCustomSegment? {
        val currentFloor = floor ?: return null
        for (segment in DungeonGuideStore.customSegments(currentFloor)) if (segment.id == selectedId) return segment
        return null
    }

    private fun stages(): List<Stage> {
        val currentFloor = floor ?: return emptyList()
        val result = ArrayList<Stage>()
        val custom = ArrayList(DungeonGuideStore.customSegments(currentFloor))
        custom.sortBy { it.afterBuiltInIndex }
        val builtIn = DungeonGuideSegments.builtIn(currentFloor)
        for (i in builtIn.indices) {
            val stage = builtIn[i]
            result.add(Stage(stage.id, stage.label))
            for (segment in custom) if (segment.afterBuiltInIndex == i) result.add(Stage(segment.id, segment.label))
        }
        return result
    }

    private fun fit(value: String, maxWidth: Int): String = GuiTheme.fit(font, value, maxWidth)

    private fun inside(event: MouseButtonEvent, x: Int, y: Int, width: Int, height: Int): Boolean =
        event.x() >= x.toDouble() && event.x() < (x + width).toDouble() &&
            event.y() >= y.toDouble() && event.y() < (y + height).toDouble()

    private fun button(
        graphics: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        text: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hover = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
        GuiTheme.roundedRect(graphics, x, y, width, height, GuiTheme.RADIUS_SMALL,
            if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
        graphics.centeredText(font, fit(text, width - 8), x + width / 2,
            y + (height - font.lineHeight) / 2, GuiTheme.TEXT_PRIMARY)
    }

    private fun layout(): Layout {
        val width = maxOf(1, minOf(740, this.width - 12))
        val height = maxOf(1, minOf(540, this.height - 12))
        val x = (this.width - width) / 2
        val y = (this.height - height) / 2
        val leftWidth = maxOf(130, minOf(300, width * 42 / 100))
        return Layout(x, y, width, height, leftWidth, x + leftWidth)
    }

    private data class Stage(val id: String, val label: String)
    private data class Layout(val x: Int, val y: Int, val w: Int, val h: Int, val leftWidth: Int, val right: Int)
}
