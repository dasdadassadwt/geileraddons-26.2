package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.SpiritBearState
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState

/** HUD for the M4/F4 boss mob count and the Spirit Bear spawn (Odin's Spirit Bear). */
class SpiritBearModule private constructor() : Module(
    "Spirit Bear",
    "Shows the mob count and the Spirit Bear spawn timer in the F4 and M4 boss.",
    Category.F7,
    false,
), HudElement {
    private val state = SpiritBearState()
    private var lastLevel: Any? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        state.reset()
        diagnostics.enable("")
    }

    override fun onDisable() {
        state.reset()
        diagnostics.disable()
    }

    private fun floorFourBoss(): Boolean = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase() &&
        DungeonContextTracker.currentFloor()?.number() == 4

    private fun master(): Boolean = DungeonContextTracker.currentFloor()?.master() == true

    private fun serverTicks(): Long = DungeonAssistModule.INSTANCE.serverTicks()

    /** Called for every client block change (`LevelChunk.setBlockState`). */
    fun onBlockChange(pos: BlockPos, old: BlockState, state: BlockState) {
        if (!isEnabled() || !floorFourBoss()) return
        if (!this.state.onBlockChange(pos.x, pos.y, pos.z, old.block, state.block, master(), serverTicks())) return
        diagnostics.event("LAMP pos=${pos.x},${pos.y},${pos.z} kills=${this.state.kills} master=${master()}")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            state.reset()
        }
        diagnostics.status {
            "floorFourBoss=${floorFourBoss()} floor=${DungeonContextTracker.currentFloor()} kills=${state.kills} " +
                "serverTicks=${serverTicks()} suppressedEvents=$it"
        }
    }

    private fun text(): String? = if (floorFourBoss()) state.text(master(), serverTicks()) else null

    override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

    override fun id(): String = "spirit_bear"
    override fun displayName(): String = "Spirit Bear"
    override fun width(font: Font?): Int = font!!.width(text() ?: SAMPLE)
    override fun height(font: Font?): Int = font!!.lineHeight
    override fun previewWidth(font: Font?): Int = font!!.width(SAMPLE)
    override fun visible(): Boolean = isEnabled() && floorFourBoss()
    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text() ?: return), x, y, WHITE)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text() ?: SAMPLE), x, y, WHITE)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SAMPLE = "§6Bear: §d0/30"

        @JvmField
        val INSTANCE = SpiritBearModule()
    }
}
