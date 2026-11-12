package geiler.addons.client.gui

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.SecretWaypointStore
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.function.Consumer

/** Edits only markers attached to the floor and room cells selected when this screen opens. */
class SecretWaypointEditorScreen(
    private val parent: Screen?,
    private val floor: String,
    roomIds: List<String>,
) : Screen(Component.literal("Secret Waypoints")) {
    private val roomIds: List<String> = java.util.List.copyOf(roomIds)

    private var selectedId: String? = null
    private var pendingName = "Personal Secret"
    private var pendingX = ""
    private var pendingY = ""
    private var pendingZ = ""
    private var notice = ""
    private var page = 0
    private var nameField: EditBox? = null
    private var xField: EditBox? = null
    private var yField: EditBox? = null
    private var zField: EditBox? = null
    private var panelX = 0
    private var panelY = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var leftWidth = 0
    private var rightX = 0
    private var rightWidth = 0
    private var pageCount = 0

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        panelX = 8
        panelY = 26
        panelWidth = maxOf(240, width - 16)
        panelHeight = maxOf(160, height - 58)
        leftWidth = maxOf(104, minOf(210, panelWidth * 39 / 100))
        rightX = panelX + leftWidth + 12
        rightWidth = maxOf(96, panelWidth - leftWidth - 20)
        val entries = entries()
        pageCount = maxOf(1, (entries.size + PAGE_ROWS - 1) / PAGE_ROWS)
        page = page.coerceIn(0, pageCount - 1)
        if (selectedId == null || findSelected(entries) == null) {
            selectedId = if (entries.isEmpty()) null else entries[0].waypoint().id()
        }
        if (pendingX.isEmpty() && pendingY.isEmpty() && pendingZ.isEmpty()) loadSelectedFields()

        val listY = panelY + 30
        for (offset in 0 until PAGE_ROWS) {
            val index = page * PAGE_ROWS + offset
            if (index >= entries.size) break
            val rowIndex = index
            val entry = entries[index]
            val label = rowLabel(entry)
            addRenderableWidget(
                Button.builder(Component.literal(label)) {
                    captureFields()
                    selectedId = entries[rowIndex].waypoint().id()
                    loadSelectedFields()
                    rebuildWidgets()
                }.bounds(panelX + 7, listY + offset * 21, leftWidth - 10, 19).build(),
            )
        }
        val listFooterY = panelY + panelHeight - 24
        addRenderableWidget(
            Button.builder(Component.literal("‹")) {
                page = maxOf(0, page - 1)
                rebuildWidgets()
            }.bounds(panelX + 7, listFooterY, 30, 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("${page + 1}/${pageCount}")) {
                page = (page + 1) % pageCount
                rebuildWidgets()
            }.bounds(panelX + 41, listFooterY, maxOf(36, leftWidth - 86), 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("›")) {
                page = (page + 1) % pageCount
                rebuildWidgets()
            }.bounds(panelX + leftWidth - 38, listFooterY, 30, 18).build(),
        )

        val innerWidth = maxOf(80, rightWidth - 12)
        nameField = addField(rightX + 6, panelY + 38, innerWidth, pendingName, "Waypoint name", 64,
            Consumer { pendingName = it })
        val coordinateWidth = maxOf(28, (innerWidth - 8) / 3)
        val coordinateY = panelY + 73
        xField = addField(rightX + 6, coordinateY, coordinateWidth, pendingX, "X", 12, Consumer { pendingX = it })
        yField = addField(rightX + 10 + coordinateWidth, coordinateY, coordinateWidth, pendingY, "Y", 12,
            Consumer { pendingY = it })
        zField = addField(rightX + 14 + coordinateWidth * 2, coordinateY, coordinateWidth, pendingZ, "Z", 12,
            Consumer { pendingZ = it })

        val buttonWidth = maxOf(38, (innerWidth - 4) / 2)
        val buttonX = rightX + 6
        val actionY = panelY + 98
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { saveSelected() }
                .bounds(buttonX, actionY, innerWidth, 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Use Crosshair")) { loadCrosshair() }
                .bounds(buttonX, actionY + 21, buttonWidth, 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Add Here")) { addAtCrosshair() }
                .bounds(buttonX + buttonWidth + 4, actionY + 21, buttonWidth, 18).build(),
        )
        val removeLabel = if (selectedIsPersonal()) "Remove Personal" else if (selectedIsHidden()) {
            "Restore Default"
        } else {
            "Hide Default"
        }
        addRenderableWidget(
            Button.builder(Component.literal(removeLabel)) { removeOrToggleDefault() }
                .bounds(buttonX, actionY + 42, innerWidth, 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Restore Room Defaults")) { resetRoomDefaults() }
                .bounds(buttonX, panelY + panelHeight - 24, innerWidth, 18).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 44, height - 25, 88, 18).build(),
        )
    }

    private fun addField(
        x: Int,
        y: Int,
        width: Int,
        value: String?,
        label: String,
        maxLength: Int,
        responder: Consumer<String>,
    ): EditBox {
        val field = EditBox(font, x, y, width, FIELD_HEIGHT, Component.literal(label))
        field.setMaxLength(maxLength)
        field.setValue(value ?: "")
        field.setResponder(responder)
        return addRenderableWidget(field)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xB8000000.toInt())
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0121722.toInt())
        graphics.fill(panelX + leftWidth + 4, panelY + 24, panelX + leftWidth + 5,
            panelY + panelHeight - 4, 0xFF344257.toInt())
        graphics.centeredText(font, "Secret Waypoints · $floor", width / 2, 10, 0xFFFFFFFF.toInt())
        graphics.text(font, "Current room cells", panelX + 7, panelY + 8, 0xFFE6EBF2.toInt())
        graphics.text(font, roomIds.joinToString("  "), panelX + 7, panelY + 19, 0xFF9DAAC0.toInt())
        graphics.text(font, "Marker", rightX + 6, panelY + 8, 0xFFE6EBF2.toInt())
        val selected = selectedEntry()
        if (selected == null) {
            graphics.text(font, "No markers in this room yet", rightX + 6, panelY + 20, 0xFF9DAAC0.toInt())
        } else {
            val status = (if (selected.personal()) "Personal" else "Built-in") +
                (if (selected.hidden()) " · Hidden" else "") +
                (if (selected.collected()) " · Map confirmed" else "")
            graphics.text(font, clip(status, rightWidth - 12), rightX + 6, panelY + 20, 0xFF9DAAC0.toInt())
            graphics.text(font, "ID: ${clip(selected.waypoint().id(), rightWidth - 38)}", rightX + 6,
                panelY + 59, 0xFF73839B.toInt())
        }
        if (notice.isNotEmpty()) {
            graphics.centeredText(font, clip(notice, width - 18), width / 2, height - 39, 0xFFFFD36C.toInt())
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        minecraft!!.gui.setScreen(parent)
    }

    private fun entries(): List<SecretWaypointStore.EditableWaypoint> =
        SecretWaypointStore.editableForRooms(floor, roomIds)

    private fun selectedEntry(): SecretWaypointStore.EditableWaypoint? = findSelected(entries())

    private fun findSelected(values: List<SecretWaypointStore.EditableWaypoint>): SecretWaypointStore.EditableWaypoint? {
        val id = selectedId ?: return null
        return values.firstOrNull { entry -> entry.waypoint().id() == id }
    }

    private fun rowLabel(entry: SecretWaypointStore.EditableWaypoint): String {
        var prefix = if (entry.personal()) "P" else "D"
        if (entry.hidden()) prefix += "×" else if (entry.collected()) prefix += "✓"
        return prefix + " " + clip(entry.waypoint().name(), leftWidth - 30)
    }

    private fun clip(value: String?, pixelWidth: Int): String {
        var clipped = value ?: return ""
        while (clipped.isNotEmpty() && font.width(clipped) > pixelWidth) {
            clipped = clipped.substring(0, clipped.length - 1)
        }
        return clipped
    }

    private fun captureFields() {
        nameField?.let { pendingName = it.value }
        xField?.let { pendingX = it.value }
        yField?.let { pendingY = it.value }
        zField?.let { pendingZ = it.value }
    }

    private fun loadSelectedFields() {
        val selected = selectedEntry()
        if (selected == null) {
            pendingName = "Personal Secret"
            pendingX = ""
            pendingY = ""
            pendingZ = ""
            return
        }
        val waypoint = selected.waypoint()
        pendingName = waypoint.name()
        pendingX = Integer.toString(waypoint.x())
        pendingY = Integer.toString(waypoint.y())
        pendingZ = Integer.toString(waypoint.z())
    }

    private fun saveSelected() {
        captureFields()
        val selected = selectedEntry()
        if (selected == null) {
            notice = "Select a marker or add one at the crosshair"
            return
        }
        val x = parseInteger(pendingX)
        val y = parseInteger(pendingY)
        val z = parseInteger(pendingZ)
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE || z == Int.MIN_VALUE) {
            notice = "Enter whole-number coordinates"
            return
        }
        val old = selected.waypoint()
        val saved = if (selected.personal()) {
            SecretWaypointStore.updatePersonal(old.id(), old.floor(), old.roomId(), pendingName, x, y, z, old.color())
        } else {
            SecretWaypointStore.replaceDefault(old.id(), SecretWaypointStore.Waypoint(
                old.id(), old.floor(), old.roomId(), pendingName, x, y, z, old.color(),
            ))
        }
        if (!saved) {
            notice = "Could not save; keep the marker inside its room cell"
            return
        }
        notice = "Saved waypoint"
        rebuildWidgets()
    }

    private fun loadCrosshair() {
        captureFields()
        val pos = targetedLoadedBlock()
        val selected = selectedEntry()
        if (pos == null || selected == null || !inSelectedRoom(pos, selected.waypoint().roomId())) {
            notice = "Point at a loaded block inside the selected marker's room"
            return
        }
        pendingX = Integer.toString(pos.x)
        pendingY = Integer.toString(pos.y)
        pendingZ = Integer.toString(pos.z)
        notice = "Crosshair coordinates loaded; press Save to apply"
        rebuildWidgets()
    }

    private fun addAtCrosshair() {
        captureFields()
        val pos = targetedLoadedBlock()
        val cell = if (pos == null) null else DungeonGridRules.cellAt(pos.x, pos.z)
        val roomId = if (cell == null) "" else "cell:${cell.componentX},${cell.componentZ}"
        if (pos == null || !roomIds.contains(roomId)) {
            notice = "Point at a loaded block in the current room"
            return
        }
        val name = SecretWaypointEditorTextRules.nameForAdd(pendingName)
        val id = SecretWaypointStore.addPersonal(floor, roomId, name, pos.x, pos.y, pos.z, 0xFF38CAFF.toInt())
        if (id == null) {
            notice = "Waypoint could not be saved; check the local file and room cell"
            return
        }
        selectedId = id
        loadSelectedFields()
        notice = "Added personal waypoint"
        rebuildWidgets()
    }

    private fun removeOrToggleDefault() {
        val selected = selectedEntry()
        if (selected == null) {
            notice = "Select a marker first"
            return
        }
        val changed: Boolean
        if (selected.personal()) {
            changed = SecretWaypointStore.removePersonal(selected.waypoint().id())
            if (changed) {
                val remaining = entries()
                selectedId = if (remaining.isEmpty()) null else remaining.first().waypoint().id()
                loadSelectedFields()
            }
            notice = if (changed) "Removed personal waypoint" else "Could not remove personal waypoint"
        } else if (selected.hidden()) {
            changed = SecretWaypointStore.restoreDefault(selected.waypoint().id())
            notice = if (changed) "Restored built-in waypoint" else "Could not restore built-in waypoint"
        } else {
            changed = SecretWaypointStore.hideDefault(selected.waypoint().id())
            notice = if (changed) "Hidden built-in waypoint" else "Could not hide built-in waypoint"
        }
        if (changed) rebuildWidgets()
    }

    private fun resetRoomDefaults() {
        val count = SecretWaypointStore.resetRoomOverrides(floor, roomIds)
        notice = if (count == 0) "No built-in overrides to restore" else {
            "Restored $count built-in waypoint override(s)"
        }
        if (count > 0) rebuildWidgets()
    }

    private fun selectedIsPersonal(): Boolean {
        val entry = selectedEntry()
        return entry != null && entry.personal()
    }

    private fun selectedIsHidden(): Boolean {
        val entry = selectedEntry()
        return entry != null && entry.hidden()
    }

    private fun targetedLoadedBlock(): BlockPos? {
        val client = Minecraft.getInstance() ?: return null
        val level = client.level
        val hitResult = client.hitResult
        if (level == null || hitResult !is BlockHitResult || hitResult.type != HitResult.Type.BLOCK ||
            !level.hasChunkAt(hitResult.blockPos)
        ) return null
        return hitResult.blockPos.immutable()
    }

    private fun inSelectedRoom(pos: BlockPos, roomId: String): Boolean {
        val cell = DungeonGridRules.cellAt(pos.x, pos.z)
        return cell != null && roomId == "cell:${cell.componentX},${cell.componentZ}"
    }

    private fun parseInteger(value: String): Int = try {
        Integer.parseInt(value.trim { it <= ' ' })
    } catch (_: NumberFormatException) {
        Int.MIN_VALUE
    }

    private companion object {
        const val PAGE_ROWS = 7
        const val FIELD_HEIGHT = 17
    }
}

internal object SecretWaypointEditorTextRules {
    @JvmStatic
    fun nameForAdd(value: String?): String =
        if (value == null || ChatStringSemantics.isBlank(value)) "Personal Secret" else value
}
