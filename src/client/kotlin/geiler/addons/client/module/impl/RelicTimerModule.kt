package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.RelicTimerLogic
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Countdown until the M7 relics spawn after Necron's death line (Devonian's Relic Timer). */
class RelicTimerModule private constructor() : Module(
    "Relic Timer",
    "Displays a timer that tells you when the relics are going to spawn in M7.",
    Category.F7,
    false,
    spawnTicks,
), HudElement {
    private val logic = RelicTimerLogic()
    private var lastLevel: Any? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    private fun now(): Long = DungeonOdinAssistModule.INSTANCE.serverTicks()

    override fun onEnable() {
        logic.reset()
        logic.setSpawnTicks(spawnTicks.intValue())
        diagnostics.enable("sampleText=${SAMPLE} spawnTicks=${spawnTicks.intValue()}")
    }

    override fun onDisable() {
        logic.reset()
        diagnostics.disable()
    }

    private fun isM7() = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.let {
        it.number() == 7 && it.master()
    } == true

    fun onChatMessage(plain: String) {
        if (!isEnabled() || !isM7()) return
        val before = logic.remaining(now())
        logic.setSpawnTicks(spawnTicks.intValue())
        logic.onChat(plain, now())
        if (logic.remaining(now()) != before) {
            diagnostics.event("NECRON_DEATH serverTick=${now()} spawnTicks=${spawnTicks.intValue()} remaining=${logic.remaining(now())}")
        }
    }

    /** The first relic item reached the player's slot, so this many server ticks after the Necron line (measures the real spawn offset). */
    fun onRelicPickedUp() {
        if (!isEnabled()) return
        val since = logic.ticksSinceStart(now())
        if (since >= 0L) diagnostics.event("RELIC_PICKUP ticksSinceNecron=$since remaining=${logic.remaining(now())}")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            logic.reset()
            diagnostics.event("WORLD_CHANGED timerReset=true")
        }
        val now = now()
        diagnostics.status { "m7=${isM7()} timerTicks=${logic.remaining(now)} serverTicks=$now suppressedEvents=$it" }
    }

    override fun id(): String = "relic_timer"
    override fun displayName(): String = "Relic Timer"
    override fun width(font: Font?): Int = font!!.width(logic.text(now()) ?: SAMPLE)
    override fun height(font: Font?): Int = font!!.lineHeight
    override fun previewWidth(font: Font?): Int = font!!.width(SAMPLE)
    override fun visible(): Boolean = isEnabled() && logic.text(now()) != null
    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(logic.text(now()) ?: return), x, y, WHITE)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(logic.text(now()) ?: SAMPLE), x, y, WHITE)
    }

    companion object {
        private const val SAMPLE = "§a2.15s"
        private const val WHITE = 0xFFFFFFFF.toInt()

        private val spawnTicks = NumberSetting("Relic Spawn Ticks", 20f, 80f, RelicTimerLogic.SPAWN_TICKS.toFloat(), true)

        @JvmField
        val INSTANCE = RelicTimerModule()
    }
}
