package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks

/** Boxes every trapped chest in a dungeon: in a run these are the Mimic chests. */
class TrappedChestEspModule private constructor() : Module(
    "Trapped Chest ESP",
    "Highlights trapped chests in dungeons, which is where the Mimic hides.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val fillColor = spec.color("fillColor")
    private val outlineColor = spec.color("outlineColor")
    private val lineWidth = spec.slider("lineWidth")
    private val range = spec.slider("range")
    private val depthCheck = spec.toggle("depthCheck")

    private var chests: List<BlockPos> = emptyList()
    private var ticks = 0
    private var drawn = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        chests = emptyList()
        ticks = 0
        diagnostics.enable("range=${range.intValue()} depth=${depthCheck.value()}")
    }

    override fun onDisable() {
        chests = emptyList()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null || !DungeonContextTracker.inDungeon()) {
            chests = emptyList()
            return
        }
        diagnostics.advance()
        if (ticks++ % SCAN_INTERVAL != 0) return
        val radius = range.intValue()
        val radiusSquared = radius.toDouble() * radius
        val chunkRadius = (radius shr 4) + 1
        val found = ArrayList<BlockPos>()
        for (cx in (player.blockX shr 4) - chunkRadius..(player.blockX shr 4) + chunkRadius) {
            for (cz in (player.blockZ shr 4) - chunkRadius..(player.blockZ shr 4) + chunkRadius) {
                val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
                for (position in chunk.blockEntities.keys) {
                    if (player.distanceToSqr(position.x + 0.5, position.y + 0.5, position.z + 0.5) > radiusSquared) continue
                    if (level.getBlockState(position).`is`(Blocks.TRAPPED_CHEST)) found.add(position.immutable())
                }
            }
        }
        if (found.size != chests.size) diagnostics.event("CHESTS count=${found.size}")
        chests = found
        diagnostics.status { "enabled=true chests=${chests.size} drawn=$drawn suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || chests.isEmpty() || !DungeonContextTracker.inDungeon()) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        for (pos in chests) {
            EspRenderer.renderBox(
                context.poseStack(), buffers, pos.x - camera.x, pos.y - camera.y, pos.z - camera.z, 1.0, 0.875, 1.0,
                fillColor.argb(), outlineColor.argb(), lineWidth.value(), depthCheck.value(),
            )
            drawn++
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private const val SCAN_INTERVAL = 10
        private val spec = ModuleSettingsSpec.of("Trapped Chest ESP")

        @JvmField
        val INSTANCE = TrappedChestEspModule()
    }
}
