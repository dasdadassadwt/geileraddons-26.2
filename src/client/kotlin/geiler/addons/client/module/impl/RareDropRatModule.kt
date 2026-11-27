package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.CroesusRules
import geiler.addons.client.dungeon.f7.RareDropRatRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.termsim.TermSimScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier

/**
 * A rat that grows in the middle of the screen and shakes when a big drop shows up: Necron's Handle, the Implosion,
 * Shadow Warp and Wither Shield scrolls and the Fifth Master Star. It is triggered by a drop chat line or by the item
 * appearing in a dungeon reward chest or a Croesus chest preview, once per item and chest. Drawn above an open chest
 * screen, otherwise on the HUD.
 */
class RareDropRatModule private constructor() : Module(
    "Rare Drop Rat",
    "A shaking rat grows on screen when Necron's Handle, a Wither scroll or the Fifth Master Star drops.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val size = spec.slider("size")
    private val growMs = spec.slider("growMs")
    private val holdMs = spec.slider("holdMs")
    private val fadeMs = spec.slider("fadeMs")
    private val shake = spec.slider("shake")

    private var startedAtMs = -1L
    private var trackedScreen: Any? = null
    private val seenOnScreen = HashSet<String>()
    private var shown = 0
    private var missingLogged = false
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 200)

    override fun onEnable() {
        startedAtMs = -1L
        trackedScreen = null
        seenOnScreen.clear()
        diagnostics.enable("size=${size.intValue()} grow=${growMs.intValue()} hold=${holdMs.intValue()} fade=${fadeMs.intValue()} shake=${shake.value()}")
    }

    override fun onDisable() {
        startedAtMs = -1L
        diagnostics.event("DISABLE shown=$shown")
        diagnostics.disable()
    }

    /** Starts the rat (a test command uses this too). */
    fun show(drop: String, source: String) {
        if (!isEnabled()) return
        startedAtMs = System.currentTimeMillis()
        shown++
        diagnostics.event("SHOW drop=$drop source=$source shown=$shown")
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        RareDropRatRules.chatDrop(plain)?.let { show(it, "chat") }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val screen = (Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>)?.takeIf { it !is TermSimScreen }
        if (screen !== trackedScreen) {
            trackedScreen = screen
            seenOnScreen.clear()
        }
        if (screen == null) return
        val title = screen.title.string
        if (!CroesusRules.isOpenedChest(title) && !CroesusRules.isChestPreview(title)) return
        val all = screen.menu.slots
        for (slot in all.subList(0, (all.size - PLAYER_SLOTS).coerceAtLeast(0))) {
            val stack = slot.item
            if (stack.isEmpty) continue
            val lines = ArrayList<String>()
            lines.add(stack.hoverName.string)
            stack.get(DataComponents.LORE)?.lines()?.forEach { lines.add(it.string) }
            for (line in lines) {
                val drop = RareDropRatRules.drop(line) ?: continue
                if (seenOnScreen.add("${slot.index}:$drop")) show(drop, "chest title=$title slot=${slot.index}")
            }
        }
    }

    /** The HUD layer: only while no screen is open (an open screen draws the rat itself, above its contents). */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (Minecraft.getInstance().gui.screen() == null) draw(graphics)
    }

    /** Called above an open container screen. */
    fun drawOverScreen(graphics: GuiGraphicsExtractor) = draw(graphics)

    private fun draw(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || startedAtMs < 0) return
        val ms = System.currentTimeMillis() - startedAtMs
        val grow = growMs.value().toLong()
        val hold = holdMs.value().toLong()
        val fade = fadeMs.value().toLong()
        if (RareDropRatRules.done(ms, grow, hold, fade)) {
            startedAtMs = -1L
            return
        }
        val scale = RareDropRatRules.scale(ms, grow, hold, fade)
        if (scale <= 0.0) return
        // The image is added by hand; without it Minecraft would draw its missing-texture checkerboard.
        if (!Minecraft.getInstance().resourceManager.getResource(TEXTURE).isPresent) {
            if (!missingLogged) diagnostics.event("TEXTURE_MISSING path=assets/geileraddons/textures/gui/rat.png")
            missingLogged = true
            return
        }
        val alpha = (RareDropRatRules.alpha(ms, grow, hold, fade) * 255).toInt().coerceIn(0, 255)
        val offset = RareDropRatRules.shake(ms, shake.value().toDouble(), scale)
        val px = size.value() * scale
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((graphics.guiWidth() / 2.0 - px / 2.0 + offset[0]).toFloat(), (graphics.guiHeight() / 2.0 - px / 2.0 + offset[1]).toFloat())
        pose.scale((px / TEXTURE_SIZE).toFloat(), (px / TEXTURE_SIZE).toFloat())
        graphics.nextStratum()
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0.0f, 0.0f,
            TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, TEXTURE_SIZE, (alpha shl 24) or 0xFFFFFF,
        )
        pose.popMatrix()
    }

    companion object {
        private const val PLAYER_SLOTS = 36
        private const val TEXTURE_SIZE = 128
        private val TEXTURE = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/rat.png")
        private val spec = ModuleSettingsSpec.of("Rare Drop Rat")

        @JvmField
        val INSTANCE = RareDropRatModule()
    }
}
