package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.dungeon.f7.PersonalBestFile
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * Marks the cauldron that matches the relic in your inventory in P5, draws a tracer to it and keeps place
 * time bests (Odin's King Relic, BSD 3-Clause; the tracer is an addition). Written independently.
 */
class KingRelicModule private constructor(private val s: Settings) : Module(
    "King Relic",
    "Highlight correct cauldron and track PB for time placement.",
    Category.F7,
    false,
    s.relicBeacon, s.relicTracer,
) {
    private enum class Relic(val id: String, val colorCode: Char, val argb: Int, val cauldron: BlockPos) {
        Green("GREEN_KING_RELIC", 'a', 0xFF55FF55.toInt(), BlockPos(49, 7, 44)),
        Purple("PURPLE_KING_RELIC", '5', 0xFFAA00AA.toInt(), BlockPos(54, 7, 41)),
        Blue("BLUE_KING_RELIC", 'b', 0xFF5555FF.toInt(), BlockPos(59, 7, 44)),
        Orange("ORANGE_KING_RELIC", '6', 0xFFFFAA00.toInt(), BlockPos(57, 7, 42)),
        Red("RED_KING_RELIC", 'c', 0xFFFF5555.toInt(), BlockPos(51, 7, 42)),
    }

    private val bests = PersonalBestFile("king-relic-personal-bests.json", mapOf(
        "Orange" to 1.95f, "Red" to 3.95f, "Blue" to 4.95f,
    ))
    private var current: Relic? = null
    private var placeTick = -1
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        reset()
        diagnostics.enable("beacon=${s.relicBeacon.value()} tracer=${s.relicTracer.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        current = null
        placeTick = -1
        lastServerTicks = -1L
    }

    fun onSetSlot(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
        if (!isEnabled() || slot != RELIC_SLOT) return
        val player = Minecraft.getInstance().player ?: return
        if (menu !== player.inventoryMenu || !F7Support.inPhase5()) return
        val id = F7Support.skyblockId(stack)
        current = Relic.entries.find { it.id == id }
        if (current != null && placeTick == -1) placeTick = 0
        if (current != null) RelicTimerModule.INSTANCE.onRelicPickedUp()
        diagnostics.event("RELIC_SLOT slot=$slot item=$id relic=${current?.name} placeTick=$placeTick")
    }

    /** A block was right-clicked; the first cauldron or anvil after picking the relic up times the placement. */
    fun onBlockUse(pos: BlockPos) {
        if (!isEnabled() || !F7Support.inPhase5() || placeTick < 0) return
        val block = Minecraft.getInstance().level?.getBlockState(pos)?.block ?: return
        if (block != Blocks.CAULDRON && block != Blocks.ANVIL) return
        val relic = current ?: return
        val seconds = placeTick / 20f
        val outcome = bests.record(relic.name, seconds)
        val tail = if (outcome.newBest) "§7(§d§lNew PB§r§7) Old PB was §8${outcome.old}" else "§8(§7${outcome.old}§8)"
        F7Support.message("§${relic.colorCode}${relic.name} relic §7placed in §6${seconds}s§7! $tail")
        diagnostics.event("PLACED relic=${relic.name} seconds=$seconds newBest=${outcome.newBest}")
        placeTick = -2
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            reset()
            diagnostics.event("WORLD_CHANGED relicReset=true")
        }
        val now = DungeonOdinAssistModule.INSTANCE.serverTicks()
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, 40L)
        lastServerTicks = now
        if (F7Support.inPhase5() && placeTick >= 0) placeTick += delta.toInt()
        diagnostics.status { "phase5=${F7Support.inPhase5()} relic=${current?.name} placeTick=$placeTick suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        val relic = current ?: return
        if (!isEnabled() || !F7Support.inPhase5()) return
        val client = Minecraft.getInstance()
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val pose = context.poseStack()
        val pos = relic.cauldron
        val fill = (relic.argb and 0x00FFFFFF) or (0x60 shl 24)
        var drew = false
        if (s.relicBeacon.value()) {
            EspRenderer.renderBox(
                pose, buffers, pos.x - camera.x, pos.y - camera.y, pos.z - camera.z, 1.0, 1.0, 1.0,
                fill, relic.argb, 2f, false,
            )
            EspRenderer.renderBox(
                pose, buffers, pos.x + 0.35 - camera.x, pos.y + 1.0 - camera.y, pos.z + 0.35 - camera.z,
                0.3, 120.0, 0.3, (relic.argb and 0x00FFFFFF) or (0x40 shl 24), 0, 1f, false,
            )
            drew = true
        }
        if (s.relicTracer.value()) {
            val target = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val endpoints = BlockEspTracerGeometry.endpoints(camera, client.gameRenderer.mainCamera().forwardVector(), target, 0.5)
            EspRenderer.renderLine(
                pose, buffers, endpoints.start.x, endpoints.start.y, endpoints.start.z,
                endpoints.end.x, endpoints.end.y, endpoints.end.z, relic.argb, 2.5f, false,
            )
            drew = true
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    class Settings {
        val relicBeacon = BooleanSetting("Relic Beacon", true)
        val relicTracer = BooleanSetting("Relic Tracer", true)
    }

    companion object {
        private const val RELIC_SLOT = 44

        @JvmField
        val INSTANCE = KingRelicModule(Settings())
    }
}
