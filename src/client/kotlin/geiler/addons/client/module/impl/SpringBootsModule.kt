package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EquipmentSlot

/** Pure rules of the Spring Boots jump-height readout: note pitches counted while sneaking, and the height table. */
object SpringBootsRules {
    const val BOOTS_ID = "SPRING_BOOTS"
    const val LOW_PITCH = 0.6984127f
    const val MAX_LOW = 2
    private val HIGH_PITCHES = floatArrayOf(
        0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f,
    )
    private val RESET_PITCHES = floatArrayOf(0.0952381f, 1.6984127f)
    private val BLOCKS = floatArrayOf(
        0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
        20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
        34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
        44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
        53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f,
    )

    /** The charged jump: low and high note counts, and the height they map to. */
    class State {
        var high = 0
            private set
        var low = 0
            private set
        var blocks = 0f
            private set

        /** A note-block pling while sneaking in the boots. */
        fun onPling(pitch: Float) {
            if (pitch == LOW_PITCH) low = minOf(low + 1, MAX_LOW)
            else if (HIGH_PITCHES.any { it == pitch }) high++
            recompute()
        }

        /** The firework launch sound that ends a charge. */
        fun onLaunch(pitch: Float) {
            if (RESET_PITCHES.any { it == pitch }) {
                high = 0
                low = 0
            }
            recompute()
        }

        /** Any other sound still refreshes the height, as the source does. */
        fun recompute() {
            blocks = BLOCKS[(low + high).coerceIn(0, BLOCKS.lastIndex)]
        }

        fun reset() {
            high = 0
            low = 0
            blocks = 0f
        }
    }

    /** Red up to 13.5 blocks, yellow to 22.5, gold to 33, green to 43.5, aqua above. */
    @JvmStatic
    fun color(blocks: Float): Int = when {
        blocks <= 13.5f -> 0xFFFF5555.toInt()
        blocks <= 22.5f -> 0xFFFFFF55.toInt()
        blocks <= 33.0f -> 0xFFFFAA00.toInt()
        blocks <= 43.5f -> 0xFF55FF55.toInt()
        else -> 0xFF55FFFF.toInt()
    }

    @JvmStatic
    fun tableSize(): Int = BLOCKS.size
}

/** Shows how high the Spring Boots will launch you, after Odin's Spring Boots. */
class SpringBootsModule private constructor() : Module(
    "Spring Boots",
    "Shows the current jump height of your spring boots.",
    Category.MISCELLANEOUS,
    false,
), HudElement {
    private val state = SpringBootsRules.State()
    private var sounds = 0L
    private var drawnFrames = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    override fun onEnable() {
        sounds = 0L
        drawnFrames = 0L
        state.reset()
        diagnostics.enable("")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE sounds=$sounds")
        diagnostics.disable()
        state.reset()
    }

    private fun wearingBoots(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return ItemBackgrounds.info(player.getItemBySlot(EquipmentSlot.FEET)).skyblockId == SpringBootsRules.BOOTS_ID
    }

    private fun sneaking(): Boolean = Minecraft.getInstance().player?.isCrouching == true

    /** `handleSoundEvent` tail: [pling] and [launch] classify the sound; every sound refreshes the height. */
    fun onSound(pling: Boolean, launch: Boolean, pitch: Float) {
        if (!isEnabled() || !SkyblockItems.inSkyblock()) return
        val before = state.blocks
        when {
            pling && sneaking() && wearingBoots() -> state.onPling(pitch)
            launch -> state.onLaunch(pitch)
            else -> state.recompute()
        }
        sounds++
        if (state.blocks != before) diagnostics.event("height=${state.blocks} low=${state.low} high=${state.high}")
    }

    /** Client tick end: a sneak that has ended with the boots on clears the charge. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (SkyblockItems.inSkyblock() && !sneaking() && wearingBoots()) state.reset()
        diagnostics.status {
            "enabled=true height=${state.blocks} low=${state.low} high=${state.high} sounds=$sounds " +
                "drawnFrames=$drawnFrames suppressedEvents=$it"
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !SkyblockItems.inSkyblock() || state.blocks == 0f) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val position = player.getPosition(client.deltaTracker.getGameTimeDeltaPartialTick(false))
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        EspRenderer.renderBox(
            pose, buffers, position.x - camera.x, position.y + state.blocks - camera.y, position.z - camera.z,
            1.0, 1.0, 1.0, 0, BOX_COLOR, BOX_WIDTH, false,
        )
        GeilerAddonsRenderTypes.endBatches(buffers)
        drawnFrames++
    }

    // --- HudElement ---------------------------------------------------------------------------------------------

    override fun id(): String = "spring_boots"

    override fun displayName(): String = "Spring Boots"

    override fun visible(): Boolean = isEnabled() && state.blocks != 0f

    override fun previewMode(): String = if (state.blocks == 0f) "sample" else "live"

    private fun shown(): Float = if (state.blocks == 0f) SAMPLE_BLOCKS else state.blocks

    override fun width(font: Font?): Int = font!!.width(PREFIX) + font.width(shown().toString())

    override fun height(font: Font?): Int = font!!.lineHeight

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (state.blocks != 0f) draw(graphics, font, x, y, state.blocks)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        draw(graphics, font, x, y, shown())

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, blocks: Float) {
        graphics.text(font, Component.literal(PREFIX), x, y, PREFIX_COLOR, true)
        graphics.text(font, Component.literal(blocks.toString()), x + font.width(PREFIX), y, SpringBootsRules.color(blocks), true)
    }

    companion object {
        private const val PREFIX = "Height: "
        private const val PREFIX_COLOR = 0xFFFF55FF.toInt()
        private const val BOX_COLOR = 0xFFFF5555.toInt()
        private const val BOX_WIDTH = 2.0f
        private const val SAMPLE_BLOCKS = 22.5f

        @JvmField
        val INSTANCE = SpringBootsModule()
    }
}
