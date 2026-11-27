package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonAutoSplit
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonRoomOverlayGeometry
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Dungeon Map auto split for no-duplicate runs: dims the rooms another class does, so the map shows your job. The
 * split comes from the map alone ([DungeonAutoSplit]), so every player sees the same one; players of the same class
 * see the same rooms. Needs Dungeon Map enabled.
 */
class DungeonAutoSplitModule private constructor(
    private val dim: NumberSetting,
    private val sharedBorders: BooleanSetting,
    private val hideOthers: BooleanSetting,
) : Module(
    "Map Auto Split",
    "Dungeon Map shows only your class's rooms (blood rush: Mage, Archer, Healer; Berserk and Tank split near the entrance).",
    Category.F7,
    false,
    dim, sharedBorders, hideOthers,
) {
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 400)
    private var lastRooms: List<DungeonRoomOverlayGeometry.Room>? = null
    private var split: Map<String, Set<DungeonClass>> = emptyMap()
    private var cellKeys: Map<Pair<Int, Int>, String> = emptyMap()
    private var lastClass: DungeonClass? = null
    private var recomputes = 0

    init {
        group(SettingGroup("Display", hideOthers, dim, sharedBorders))
    }

    override fun onEnable() {
        lastRooms = null
        diagnostics.enable("dim=${dim.value()} sharedBorders=${sharedBorders.value()} hideOthers=${hideOthers.value()}")
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            val mine = lastClass?.let { c -> split.count { c in it.value } } ?: 0
            "class=$lastClass rooms=${lastRooms?.size ?: 0} assigned=${split.size} mine=$mine recomputes=$recomputes " +
                "dungeonMap=${DungeonRoomOverlayModule.INSTANCE.isEnabled()} suppressedEvents=$it"
        }
    }

    /** Dungeon Map hook, after rooms and doors and before text: dims other classes' rooms, borders shared ones. */
    fun drawOverlay(
        graphics: GuiGraphicsExtractor, state: DungeonRoomOverlayGeometry.Snapshot, gridX: Int, gridY: Int,
        cellSize: Int, cellGap: Int, visible: (Int, Int) -> Boolean,
    ) {
        if (!isEnabled()) return
        val mine = mineFor(state) ?: return
        update(state.rooms(), mine)
        if (split.isEmpty()) return
        val shade = ((dim.value() / 100f).coerceIn(0f, 1f) * 255).toInt() shl 24
        val step = cellSize + cellGap
        for ((cell, key) in cellKeys) {
            val (x, z) = cell
            if (!visible(x, z)) continue
            val owners = split[key] ?: continue
            val left = gridX + x * step
            val top = gridY + z * step
            if (mine !in owners) {
                if (hideOthers.value()) continue
                // The cell plus the gaps it shares with the same room, so the room darkens as one piece.
                val right = cellKeys[x + 1 to z] == key && visible(x + 1, z)
                val below = cellKeys[x to z + 1] == key && visible(x, z + 1)
                graphics.fill(left, top, left + cellSize + (if (right) cellGap else 0), top + cellSize, shade)
                if (below) graphics.fill(left, top + cellSize, left + cellSize, top + cellSize + cellGap, shade)
                if (right && below && cellKeys[x + 1 to z + 1] == key && visible(x + 1, z + 1)) {
                    graphics.fill(left + cellSize, top + cellSize, left + step, top + step, shade)
                }
            } else if (sharedBorders.value() && owners.size > 1) {
                for ((i, owner) in owners.sortedBy { it.ordinal }.withIndex()) {
                    val color = SpiritLeapMenuModule.classColorArgb(owner.displayName())
                    val inset = i
                    if (cellSize - 2 * inset <= 2) break
                    outline(graphics, left + inset, top + inset, cellSize - 2 * inset, color)
                }
            }
        }
    }

    /** Your class for the split, from the map marker or the tab list; null before either knows it. */
    private fun mineFor(state: DungeonRoomOverlayGeometry.Snapshot): DungeonClass? =
        DungeonClass.parse(state.players().firstOrNull { it.localPlayer() }?.dungeonClass()) ?: ownClassFromTab()

    /** Cells of the rooms another class does, when "Hide Other Rooms" is on; the map leaves them out. */
    fun hiddenCells(state: DungeonRoomOverlayGeometry.Snapshot): Set<Pair<Int, Int>> {
        if (!isEnabled() || !hideOthers.value()) return emptySet()
        val mine = mineFor(state) ?: return emptySet()
        update(state.rooms(), mine)
        return cellKeys.filter { (_, key) -> split[key]?.let { mine !in it } == true }.keys
    }

    /** Your class from the tab list, for when the map has no marker for you yet. */
    private fun ownClassFromTab(): DungeonClass? {
        val name = net.minecraft.client.Minecraft.getInstance().player?.gameProfile?.name ?: return null
        return geiler.addons.client.dungeon.helpers.DungeonPlayerRoster.snapshot()
            .firstOrNull { it.name().equals(name, ignoreCase = true) && !it.dead() }
            ?.let { DungeonClass.parse(it.dungeonClass()) }
    }

    private fun update(rooms: List<DungeonRoomOverlayGeometry.Room>, mine: DungeonClass) {
        if (mine != lastClass) {
            lastClass = mine
            diagnostics.event("CLASS class=$mine")
        }
        if (rooms == lastRooms) return
        lastRooms = rooms
        split = DungeonAutoSplit.split(rooms)
        val keys = HashMap<Pair<Int, Int>, String>()
        for (room in rooms) {
            val key = DungeonAutoSplit.key(room)
            for (c in room.footprint()) keys[c.x() to c.z()] = key
        }
        cellKeys = keys
        recomputes++
        diagnostics.event("SPLIT rooms=${rooms.size} assigned=${split.size} " +
            DungeonClass.entries.joinToString(" ") { c -> "${c.name}=${split.count { c in it.value }}" })
    }

    private fun outline(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        graphics.fill(x, y, x + size, y + 1, color)
        graphics.fill(x, y + size - 1, x + size, y + size, color)
        graphics.fill(x, y + 1, x + 1, y + size - 1, color)
        graphics.fill(x + size - 1, y + 1, x + size, y + size - 1, color)
    }

    companion object {
        @JvmField
        val INSTANCE: DungeonAutoSplitModule = DungeonAutoSplitModule(
            NumberSetting("Dim Other Rooms", 0f, 100f, 60f, true),
            BooleanSetting("Shared Room Borders", true),
            BooleanSetting("Hide Other Rooms", false),
        )
    }
}
