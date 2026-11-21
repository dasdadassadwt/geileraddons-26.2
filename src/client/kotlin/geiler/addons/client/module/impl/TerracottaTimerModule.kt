package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.FlowerPotBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.Locale

/** Pure rules of the Terracotta respawn timers: seconds counted in server ticks, and the countdown color. */
object TerracottaTimerRules {
    const val SECONDS_PER_SERVER_TICK = 0.05f
    const val NORMAL_SECONDS = 15f
    const val MASTER_SECONDS = 12f
    const val FLOOR = 6
    /** Real-font scale for the projected label; 1.0 matches Tiki Helper's default text size. */
    const val HUD_SCALE = 1.0f
    private const val GREEN = 0xFF55FF55.toInt()
    private const val GOLD = 0xFFFFAA00.toInt()
    private const val RED = 0xFFFF5555.toInt()

    @JvmStatic
    fun totalSeconds(master: Boolean): Float = if (master) MASTER_SECONDS else NORMAL_SECONDS

    /** Seconds left after [elapsedServerTicks]; callers drop the timer once this reaches 0. */
    @JvmStatic
    fun remainingSeconds(totalSeconds: Float, elapsedServerTicks: Long): Float =
        totalSeconds - elapsedServerTicks * SECONDS_PER_SERVER_TICK

    @JvmStatic
    fun color(seconds: Float): Int = when {
        seconds > 5f -> GREEN
        seconds > 2f -> GOLD
        else -> RED
    }

    /** One decimal and the S glyph the label font has, e.g. `12.3S`. */
    @JvmStatic
    fun label(seconds: Float): String = String.format(Locale.ROOT, "%.1fS", maxOf(0f, seconds))
}

/** Shows when each Terracotta respawns in the Floor 6 boss, after Odin's Terracotta Timer. */
class TerracottaTimerModule private constructor() : Module(
    "Terracotta Timer",
    "Displays the time until the terracotta respawns.",
    Category.F7,
    false,
) {
    private class Timer(val pos: BlockPos, val startServerTicks: Long, val totalSeconds: Float)

    private val timers = ArrayList<Timer>()
    private var started = 0L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)
    private var hudDrawn = 0

    override fun onEnable() {
        started = 0L
        timers.clear()
        diagnostics.enable("")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE started=$started")
        diagnostics.disable()
        timers.clear()
    }

    private fun inBoss(): Boolean = DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase()

    private fun floorSix(): Boolean = DungeonContextTracker.currentFloor()?.number() == TerracottaTimerRules.FLOOR

    private fun serverTicks(): Long = DungeonAssistModule.INSTANCE.serverTicks()

    /** `handleBlockUpdate`: a block turning into a flower pot in the Floor 6 boss starts that pot's timer. */
    fun onBlockChange(pos: BlockPos, state: BlockState) {
        if (!isEnabled() || state.block !is FlowerPotBlock || !floorSix() || !inBoss()) return
        if (timers.any { it.pos == pos }) return
        val master = DungeonContextTracker.currentFloor()?.master() == true
        val total = TerracottaTimerRules.totalSeconds(master)
        timers.add(Timer(pos.immutable(), serverTicks(), total))
        started++
        diagnostics.event("start pos=${pos.x},${pos.y},${pos.z} seconds=$total master=$master")
    }

    /** Called by the shared client tick registration: drops finished timers and writes the status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val now = serverTicks()
        timers.removeAll { TerracottaTimerRules.remainingSeconds(it.totalSeconds, now - it.startServerTicks) <= 0f }
        if (!inBoss() && timers.isNotEmpty()) timers.clear()
        diagnostics.status { "enabled=true active=${timers.size} started=$started inBoss=${inBoss()} hudLabels=$hudDrawn suppressedEvents=$it" }
    }

    /** HUD element `terracotta_labels`: real-font text projected above each pot, like Tiki Helper's labels. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || timers.isEmpty() || !inBoss() || !floorSix()) return
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera()
        val now = serverTicks()
        var drawn = 0
        for (timer in timers) {
            val seconds = TerracottaTimerRules.remainingSeconds(timer.totalSeconds, now - timer.startServerTicks)
            if (seconds <= 0f) continue
            val world = Vec3(timer.pos.x + 0.5, timer.pos.y + 0.5, timer.pos.z + 0.5)
            if (ProjectedLabelRenderer.draw(graphics, camera, mc.font, world,
                    TerracottaTimerRules.label(seconds), TerracottaTimerRules.color(seconds), TerracottaTimerRules.HUD_SCALE)) drawn++
        }
        hudDrawn = drawn
    }

    companion object {

        @JvmField
        val INSTANCE = TerracottaTimerModule()
    }
}
