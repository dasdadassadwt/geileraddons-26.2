package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.ArrowAlignRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.ProjectedLabelRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/** Arrow Align device solver: shows the clicks each frame still needs and can block wrong clicks (Odin's Arrow Align). */
class ArrowAlignModule private constructor() : Module(
    "Arrow Align",
    "Shows the solution for the Arrow Align device in F7 and M7.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val blockWrong = spec.toggle("blockWrong")
    private val invertKey = spec.toggle("invertKey")

    private var current = IntArray(25) { -1 }
    private var solution: IntArray? = null
    private var clicks = IntArray(25)
    private val recentClicks = HashMap<Int, Pair<Int, Long>>()
    private var near = false
    private var blocked = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        reset()
        blocked = 0
        diagnostics.enable("blockWrong=${blockWrong.value()} invert=${invertKey.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.event("DISABLE blocked=$blocked")
        diagnostics.disable()
    }

    private fun reset() {
        current = IntArray(25) { -1 }
        solution = null
        clicks = IntArray(25)
        recentClicks.clear()
        near = false
    }

    private fun onFloor7(): Boolean = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null || !onFloor7()) {
            reset()
            return
        }
        diagnostics.advance()
        val dx = player.x - 0.5
        val dy = player.y - 120.5
        val dz = player.z - 77.5
        near = dx * dx + dy * dy + dz * dz <= NEAR_SQUARED
        if (!near) {
            if (solution != null) diagnostics.event("LEFT_DEVICE")
            reset()
            diagnostics.status { "enabled=true near=false solved=false blocked=$blocked suppressedEvents=$it" }
            return
        }
        val now = System.currentTimeMillis()
        val rotations = IntArray(25) { -1 }
        for (entity in level.entitiesForRendering()) {
            val frame = entity as? ItemFrame ?: continue
            if (frame.item.item != Items.ARROW) continue
            val pos = frame.blockPosition()
            val index = ArrowAlignRules.frameIndex(pos.x, pos.y, pos.z)
            if (index < 0) continue
            val recent = recentClicks[index]
            rotations[index] = if (recent != null && now - recent.second < RECENT_MILLIS) recent.first else frame.rotation
        }
        current = rotations
        val previous = solution
        solution = ArrowAlignRules.solve(rotations)
        clicks = solution?.let { ArrowAlignRules.clicks(rotations, it) } ?: IntArray(25)
        if (previous == null && solution != null) diagnostics.event("SOLVED frames=${rotations.count { it != -1 }} clicks=${clicks.sum()}")
        diagnostics.status { "enabled=true near=true frames=${rotations.count { it != -1 }} solved=${solution != null} clicks=${clicks.sum()} blocked=$blocked suppressedEvents=$it" }
    }

    /** A click on [entity]; returns true when it must be cancelled. */
    fun onEntityUse(entity: Entity): Boolean {
        if (!isEnabled() || !near || !onFloor7()) return false
        val frame = entity as? ItemFrame ?: return false
        if (frame.item.item != Items.ARROW) return false
        val pos = frame.blockPosition()
        val index = ArrowAlignRules.frameIndex(pos.x, pos.y, pos.z)
        if (index < 0 || current[index] == -1) return false
        val sneaking = Minecraft.getInstance().player?.isShiftKeyDown == true
        val shouldBlock = blockWrong.value() && !(sneaking xor invertKey.value())
        if (solution != null && clicks[index] == 0 && shouldBlock) {
            blocked++
            diagnostics.event("BLOCKED frame=$index blocked=$blocked")
            return true
        }
        val next = (current[index] + 1) % 8
        recentClicks[index] = next to System.currentTimeMillis()
        current[index] = next
        solution?.let { clicks = ArrowAlignRules.clicks(current, it) }
        return false
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !near || solution == null) return
        val client = Minecraft.getInstance()
        for (index in 0 until 25) {
            val needed = clicks[index]
            if (needed <= 0) continue
            val pos = ArrowAlignRules.framePosition(index)
            val color = when {
                needed < 3 -> 0xFF55FF55.toInt()
                needed < 5 -> 0xFFFFAA00.toInt()
                else -> 0xFFFF5555.toInt()
            }
            val camera = client.gameRenderer.mainCamera()
            val world = Vec3(pos[0] + 0.2, pos[1] + 0.6, pos[2] + 0.5)
            ProjectedLabelRenderer.drawFloatingText(
                graphics, camera, client.font, world, needed.toString(), color,
                ArrowAlignRules.labelScale(camera.position().distanceTo(world)),
                minScale = ArrowAlignRules.LABEL_MIN_SCALE, maxScale = ArrowAlignRules.LABEL_MAX_SCALE,
            )
        }
    }

    companion object {
        private const val NEAR_SQUARED = 200.0
        private const val RECENT_MILLIS = 1000L
        private val spec = ModuleSettingsSpec.of("Arrow Align")

        @JvmField
        val INSTANCE = ArrowAlignModule()
    }
}
