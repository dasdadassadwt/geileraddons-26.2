package geiler.addons.client.gui

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.macro.MacroJavaStrings
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.ArrayList
import java.util.function.BiConsumer
import java.util.function.Supplier

/** Shared CRUD UI for a feature's independent folder tree and entries. */
class FolderManagerScreen(
    private val parent: Screen?,
    private val title: String,
    private val tree: FolderTree,
    private val entries: Supplier<List<Entry>>,
    private val moveEntry: BiConsumer<String?, String?>,
    private val changed: Runnable,
) : Screen(Component.literal("Folders")) {
    private var selectedFolder: String? = null
    private var selectedEntry: String? = null
    private var popup: Popup? = null
    private var dialog: Dialog? = null
    private val dialogField = TextFieldModel(48)
    private val dialogText: String get() = dialogField.text
    private var dialogDragging = false
    private var folderScroll = 0
    private var entryScroll = 0
    private var layout: Layout? = null

    override fun isPauseScreen(): Boolean = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val currentLayout = layout()
        layout = currentLayout
        val panel = currentLayout.panel
        GuiTheme.roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, "$title Folders", panel.x + 12, panel.y + 10, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, "Folders are independent for this feature. Deleting one promotes its contents.",
            panel.x + 12, panel.y + 24, GuiTheme.TEXT_MUTED)
        graphics.fill(panel.x + 10, panel.y + 43, panel.x + panel.w - 10, panel.y + 44, GuiTheme.BORDER)
        renderFolders(graphics, mouseX, mouseY, currentLayout)
        renderEntries(graphics, mouseX, mouseY, currentLayout)
        renderActions(graphics, mouseX, mouseY, currentLayout)
        popup?.let { renderPopup(graphics, mouseX, mouseY, currentLayout, it) }
        dialog?.let { renderDialog(graphics, mouseX, mouseY, currentLayout, it) }
    }

    private fun renderFolders(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, currentLayout: Layout) {
        val pane = currentLayout.folders
        pane(graphics, pane, "Folders", "Click to select a parent or folder.")
        val rows = ArrayList<Row>()
        for (root in tree.childrenOf(null)) appendFolderRows(root, 0, rows)
        val top = pane.y + 39
        val view = GuiRect(pane.x + 4, top, pane.w - 8, maxOf(10, pane.h - 44))
        folderScroll = clamp(folderScroll, 0, maxOf(0, rows.size * ROW_HEIGHT - view.h))
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        row(graphics, mouseX, mouseY, Row(null, "Root", 0), view, 0, selectedFolder == null)
        for (index in rows.indices) {
            val current = rows[index]
            row(graphics, mouseX, mouseY, current, view, (index + 1) * ROW_HEIGHT - folderScroll,
                selectedFolder == current.id)
        }
        graphics.disableScissor()
    }

    private fun appendFolderRows(folder: FolderTree.Folder, depth: Int, rows: MutableList<Row>) {
        rows.add(Row(folder.id, folder.name, depth))
        for (child in tree.childrenOf(folder.id)) appendFolderRows(child, depth + 1, rows)
    }

    /**
     * The entries that belong in a folder, treating an unknown folder as root.
     * An entry can name a folder that no longer exists; resolving it here keeps it reachable without
     * rewriting its saved value.
     */
    private fun entriesIn(folderId: String?): List<Entry> {
        val visible = ArrayList<Entry>()
        for (entry in entries.get()) {
            if (effectiveFolder(entry.folderId) == folderId) visible.add(entry)
        }
        return visible
    }

    /** An entry's folder, or null when that folder is not in the tree any more. */
    private fun effectiveFolder(folderId: String?): String? {
        if (folderId.isNullOrBlank()) return null
        return if (tree.contains(folderId)) folderId else null
    }

    private fun row(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, row: Row,
        view: GuiRect, offset: Int, selected: Boolean) {
        val y = view.y + offset
        val bounds = GuiRect(view.x, y, view.w - 4, ROW_HEIGHT - 1)
        val hovered = bounds.contains(mouseX, mouseY) && view.contains(mouseX, mouseY)
        if (selected || hovered) GuiTheme.roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h,
            GuiTheme.RADIUS_SMALL, if (selected) GuiTheme.CATEGORY_SELECTED else GuiTheme.CATEGORY_HOVER)
        graphics.text(font, trim(font, row.label, bounds.w - 12 - row.depth * 10),
            bounds.x + 6 + row.depth * 10, bounds.y + 5,
            if (selected) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_SECONDARY)
    }

    private fun renderEntries(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, currentLayout: Layout) {
        val pane = currentLayout.entries
        val folderName = selectedFolder?.let(::name) ?: "Root"
        pane(graphics, pane, "Entries in $folderName", "Select an entry to move it.")
        val visible = entriesIn(selectedFolder)
        val view = GuiRect(pane.x + 4, pane.y + 39, pane.w - 8, maxOf(10, pane.h - 44))
        entryScroll = clamp(entryScroll, 0, maxOf(0, visible.size * ROW_HEIGHT - view.h))
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        if (visible.isEmpty()) graphics.text(font, "No entries in this folder.", view.x + 6, view.y + 6, GuiTheme.TEXT_MUTED)
        for (index in visible.indices) {
            val entry = visible[index]
            val y = view.y + index * ROW_HEIGHT - entryScroll
            val bounds = GuiRect(view.x, y, view.w - 4, ROW_HEIGHT - 1)
            val hovered = bounds.contains(mouseX, mouseY) && view.contains(mouseX, mouseY)
            val selected = selectedEntry == entry.id
            if (selected || hovered) GuiTheme.roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h,
                GuiTheme.RADIUS_SMALL, if (selected) GuiTheme.CATEGORY_SELECTED else GuiTheme.CATEGORY_HOVER)
            graphics.text(font, trim(font, entry.name, bounds.w - 12), bounds.x + 6, bounds.y + 5,
                if (selected) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_SECONDARY)
        }
        graphics.disableScissor()
    }

    private fun renderActions(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, currentLayout: Layout) {
        val y = currentLayout.panel.y + currentLayout.panel.h - 29
        var x = currentLayout.panel.x + 10
        val gap = 4
        val widths = actionWidths(currentLayout)
        val labels = if (widths[0] < 50) arrayOf("New", "Rename", "Parent", "Delete", "Move", "Done")
            else arrayOf("New Folder", "Rename", "Move Folder", "Delete", "Move Entry", "Done")
        for (index in labels.indices) {
            val bounds = GuiRect(x, y, widths[index], 20)
            val enabled = when (index) {
                1, 2, 3 -> selectedFolder != null
                4 -> selectedEntry != null
                else -> true
            }
            button(graphics, labels[index], bounds, mouseX, mouseY, enabled)
            x += widths[index] + gap
        }
    }

    private fun renderPopup(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int,
        currentLayout: Layout, active: Popup) {
        val bounds = active.bounds(currentLayout)
        GuiTheme.roundedRectBordered(graphics, bounds.x, bounds.y, bounds.w, bounds.h, GuiTheme.RADIUS_SMALL,
            GuiTheme.MODULE_PANEL_TOP, GuiTheme.MODULE_PANEL_BOTTOM, GuiTheme.BORDER)
        val targets = active.targets
        val view = GuiRect(bounds.x + 4, bounds.y + 4, bounds.w - 8, bounds.h - 8)
        val visibleRows = maxOf(1, view.h / ROW_HEIGHT)
        active.scroll = clamp(active.scroll, 0, maxOf(0, targets.size - visibleRows))
        graphics.enableScissor(view.x, view.y, view.x + view.w, view.y + view.h)
        for (index in active.scroll until minOf(targets.size, active.scroll + visibleRows)) {
            val target = targets[index]
            val y = view.y + (index - active.scroll) * ROW_HEIGHT
            val row = GuiRect(view.x, y, view.w, ROW_HEIGHT)
            val hover = row.contains(mouseX, mouseY)
            if (hover) GuiTheme.roundedRect(graphics, row.x, row.y, row.w, row.h, GuiTheme.RADIUS_SMALL, GuiTheme.BUTTON_HOVER)
            graphics.text(font, trim(font, target.name, row.w - 10), row.x + 5, row.y + 5,
                if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY)
        }
        graphics.disableScissor()
    }

    private fun renderDialog(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int,
        currentLayout: Layout, activeDialog: Dialog) {
        graphics.fill(0, 0, width, height, 0x90000000.toInt())
        val box = currentLayout.dialog
        GuiTheme.roundedRectBordered(graphics, box.x, box.y, box.w, box.h, GuiTheme.RADIUS,
            GuiTheme.MODULE_PANEL_TOP, GuiTheme.MODULE_PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.centeredText(font, if (activeDialog == Dialog.CREATE) "Create Folder" else "Rename Folder",
            box.x + box.w / 2, box.y + 10, GuiTheme.TEXT_PRIMARY)
        val input = GuiRect(box.x + 12, box.y + 34, box.w - 24, 20)
        GuiTheme.roundedRectBordered(graphics, input.x, input.y, input.w, input.h, GuiTheme.RADIUS_SMALL,
            GuiTheme.CARD_BG, GuiTheme.CARD_BG, GuiTheme.SLIDER_FILL)
        if (dialogText.isEmpty()) graphics.text(font, "Folder name…", input.x + 6, input.y + 6, GuiTheme.TEXT_MUTED)
        TextFieldInput.draw(graphics, font, dialogField, input.x + 6, input.y + 6, input.w - 12,
            GuiTheme.TEXT_PRIMARY, GuiTheme.SLIDER_FILL, true)
        val save = GuiRect(box.x + 12, box.y + 62, (box.w - 30) / 2, 19)
        val cancel = GuiRect(save.x + save.w + 6, save.y, save.w, save.h)
        button(graphics, "Save", save, mouseX, mouseY, !MacroJavaStrings.isBlank(dialogText))
        button(graphics, "Cancel", cancel, mouseX, mouseY, true)
    }

    private fun pane(graphics: GuiGraphicsExtractor, pane: GuiRect, heading: String, hint: String) {
        GuiTheme.roundedRectBordered(graphics, pane.x, pane.y, pane.w, pane.h, GuiTheme.RADIUS_SMALL,
            GuiTheme.MODULE_PANEL_TOP, GuiTheme.MODULE_PANEL_BOTTOM, GuiTheme.BORDER)
        graphics.text(font, trim(font, heading, pane.w - 12), pane.x + 6, pane.y + 6, GuiTheme.TEXT_PRIMARY)
        graphics.text(font, trim(font, hint, pane.w - 12), pane.x + 6, pane.y + 19, GuiTheme.TEXT_MUTED)
    }

    private fun button(graphics: GuiGraphicsExtractor, label: String, bounds: GuiRect,
        mouseX: Int, mouseY: Int, enabled: Boolean) {
        val hover = enabled && bounds.contains(mouseX, mouseY)
        GuiTheme.roundedRect(graphics, bounds.x, bounds.y, bounds.w, bounds.h, GuiTheme.RADIUS_SMALL,
            if (enabled) (if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG) else GuiTheme.SLIDER_TRACK)
        graphics.centeredText(font, label, bounds.x + bounds.w / 2, bounds.y + (bounds.h - 8) / 2,
            if (enabled) (if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY) else GuiTheme.TEXT_MUTED)
    }

    private fun layout(): Layout {
        val w = maxOf(220, minOf(720, width - 20))
        val h = maxOf(150, minOf(430, height - 20))
        val x = (width - w) / 2
        val y = (height - h) / 2
        val contentY = y + 48
        val contentH = maxOf(36, h - 86)
        val gap = 6
        val innerW = w - 20
        val folderW = innerW / 2
        return Layout(GuiRect(x, y, w, h), GuiRect(x + 10, contentY, folderW, contentH),
            GuiRect(x + 10 + folderW + gap, contentY, innerW - folderW - gap, contentH),
            GuiRect(x + (w - 220) / 2, y + (h - 92) / 2, 220, 92))
    }

    private fun actionWidths(currentLayout: Layout = layout!!): IntArray {
        val available = currentLayout.panel.w - 20 - 5 * 4
        val each = available / 6
        return intArrayOf(each, each, each, each, each, available - each * 5)
    }

    private fun name(id: String): String = tree.folder(id)?.name ?: "Root"

    private fun parentTargets(movingId: String?): List<Target> {
        val result = ArrayList<Target>()
        result.add(Target(null, "Root"))
        for (folder in tree.folders()) {
            val id = folder.id ?: continue
            if (id != movingId && !tree.isDescendant(id, movingId)) {
                result.add(Target(id, folderPath(id)))
            }
        }
        return result
    }

    private fun folderPath(id: String): String {
        val names = ArrayList<String>()
        var current = tree.folder(id)
        while (current != null) {
            names.add(0, current.name ?: "null")
            current = tree.folder(current.parentId)
        }
        return names.joinToString(" / ")
    }

    private fun beginDialog(mode: Dialog) {
        dialog = mode
        dialogField.reset(if (mode == Dialog.RENAME && selectedFolder != null) name(selectedFolder!!) else "")
        dialogField.selectAll()
        popup = null
    }

    private fun saveDialog() {
        val mode = dialog ?: return
        if (MacroJavaStrings.isBlank(dialogText)) return
        if (mode == Dialog.CREATE) tree.create(dialogText, selectedFolder) else tree.rename(selectedFolder, dialogText)
        dialog = null
        changed.run()
    }

    private fun activateAction(index: Int) {
        when (index) {
            0 -> beginDialog(Dialog.CREATE)
            1 -> beginDialog(Dialog.RENAME)
            2 -> popup = Popup(PopupAction.MOVE_FOLDER, parentTargets(selectedFolder), 0)
            3 -> {
                val folderId = selectedFolder
                val parentId = tree.delete(folderId)
                for (entry in entries.get()) if (folderId == entry.folderId) moveEntry.accept(entry.id, parentId)
                selectedFolder = parentId
                selectedEntry = null
                changed.run()
            }
            4 -> popup = Popup(PopupAction.MOVE_ENTRY, parentTargets(null), 0)
            5 -> onClose()
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != 0) return true
        val x = event.x().toInt()
        val y = event.y().toInt()
        val activeDialog = dialog
        if (activeDialog != null) {
            val box = layout!!.dialog
            val save = GuiRect(box.x + 12, box.y + 62, (box.w - 30) / 2, 19)
            val cancel = GuiRect(save.x + save.w + 6, save.y, save.w, save.h)
            val input = GuiRect(box.x + 12, box.y + 34, box.w - 24, 20)
            if (save.contains(x, y)) saveDialog() else if (cancel.contains(x, y)) dialog = null
            else if (input.contains(x, y)) {
                TextFieldInput.mousePressed(dialogField, font, event.x(), event.y(), input.x + 6, input.w - 12, minecraft.hasShiftDown())
                dialogDragging = true
            }
            return true
        }
        val activePopup = popup
        if (activePopup != null) {
            val bounds = activePopup.bounds(layout!!)
            val view = GuiRect(bounds.x + 4, bounds.y + 4, bounds.w - 8, bounds.h - 8)
            if (!bounds.contains(x, y)) { popup = null; return true }
            val row = (y - view.y) / ROW_HEIGHT + activePopup.scroll
            if (row < 0 || row >= activePopup.targets.size) return true
            val target = activePopup.targets[row]
            if (activePopup.action == PopupAction.MOVE_FOLDER) tree.move(selectedFolder, target.id)
            else if (selectedEntry != null) moveEntry.accept(selectedEntry, target.id)
            popup = null
            changed.run()
            return true
        }
        val currentLayout = layout!!
        val buttons = GuiRect(currentLayout.panel.x + 10, currentLayout.panel.y + currentLayout.panel.h - 29,
            currentLayout.panel.w - 20, 20)
        if (buttons.contains(x, y)) {
            var cursor = buttons.x
            val widths = actionWidths(currentLayout)
            for (index in widths.indices) {
                val button = GuiRect(cursor, buttons.y, widths[index], buttons.h)
                if (button.contains(x, y)) {
                    val enabled = when (index) {
                        1, 2, 3 -> selectedFolder != null
                        4 -> selectedEntry != null
                        else -> true
                    }
                    if (enabled) activateAction(index)
                    return true
                }
                cursor += widths[index] + 4
            }
            return true
        }
        if (currentLayout.folders.contains(x, y)) {
            val rows = ArrayList<Row>()
            for (root in tree.childrenOf(null)) appendFolderRows(root, 0, rows)
            val view = GuiRect(currentLayout.folders.x + 4, currentLayout.folders.y + 39,
                currentLayout.folders.w - 8, maxOf(10, currentLayout.folders.h - 44))
            if (view.contains(x, y)) {
                val row = (y - view.y + folderScroll) / ROW_HEIGHT
                if (row == 0) { selectedFolder = null; selectedEntry = null; return true }
                if (row > 0 && row <= rows.size) { selectedFolder = rows[row - 1].id; selectedEntry = null; return true }
            }
            return true
        }
        if (currentLayout.entries.contains(x, y)) {
            val visible = entriesIn(selectedFolder)
            val view = GuiRect(currentLayout.entries.x + 4, currentLayout.entries.y + 39,
                currentLayout.entries.w - 8, maxOf(10, currentLayout.entries.h - 44))
            if (view.contains(x, y)) {
                val row = (y - view.y + entryScroll) / ROW_HEIGHT
                if (row >= 0 && row < visible.size) selectedEntry = visible[row].id
            }
            return true
        }
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val activePopup = popup
        if (activePopup != null) {
            activePopup.scroll = maxOf(0, activePopup.scroll - sign(scrollY).toInt())
            return true
        }
        val currentLayout = layout
        if (currentLayout != null && currentLayout.folders.contains(mouseX.toInt(), mouseY.toInt())) {
            folderScroll = maxOf(0, folderScroll - sign(scrollY).toInt() * 2)
        } else if (currentLayout != null && currentLayout.entries.contains(mouseX.toInt(), mouseY.toInt())) {
            entryScroll = maxOf(0, entryScroll - sign(scrollY).toInt() * 2)
        }
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val box = layout?.dialog
        if (!dialogDragging || dialog == null || box == null || event.button() != 0) return super.mouseDragged(event, dragX, dragY)
        TextFieldInput.mouseDragged(dialogField, font, event.x(), box.x + 12 + 6, box.w - 24 - 12)
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (dialogDragging && event.button() == 0) {
            dialogDragging = false
            return true
        }
        return super.mouseReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (dialog != null) TextFieldInput.handleChar(dialogField, event, "folder-name")
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (dialog != null) {
            if (event.key() == InputConstants.KEY_ESCAPE) { dialog = null; return true }
            if (event.key() == InputConstants.KEY_RETURN) { saveDialog(); return true }
            TextFieldInput.handleKey(dialogField, event, "folder-name")
            return true
        }
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true }
        return true
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private fun trim(font: Font, value: String?, maxWidth: Int): String = GuiTheme.fit(font, value, maxWidth)

    private fun clamp(value: Int, min: Int, max: Int): Int = maxOf(min, minOf(max, value))


    private data class Layout(val panel: GuiRect, val folders: GuiRect, val entries: GuiRect, val dialog: GuiRect)
    private data class Row(val id: String?, val label: String?, val depth: Int)
    private data class Target(val id: String?, val name: String)
    @JvmRecord
    data class Entry(val id: String, val name: String, val folderId: String?)

    private enum class Dialog { CREATE, RENAME }
    private enum class PopupAction { MOVE_FOLDER, MOVE_ENTRY }

    private class Popup(val action: PopupAction, val targets: List<Target>, var scroll: Int) {
        fun bounds(layout: Layout): GuiRect {
            val w = minOf(260, layout.panel.w - 20)
            val h = minOf(180, targets.size * ROW_HEIGHT + 8)
            return GuiRect(layout.panel.x + (layout.panel.w - w) / 2, layout.panel.y + 48, w, maxOf(40, h))
        }
    }

    companion object {
        private const val ROW_HEIGHT = 19
        private fun sign(value: Double): Double = java.lang.Math.signum(value)
    }
}
