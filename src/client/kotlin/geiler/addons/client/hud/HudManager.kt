package geiler.addons.client.hud

import geiler.addons.client.gui.MoveUiScreen
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.HashMap
import java.util.LinkedHashMap

/**
 * The registry of movable HUD panels and where each one sits.
 *
 * Positions are stored as a fraction of the room the element has to move in, not as pixels:
 * 0 is flush against the left or top edge and 1 is flush against the right or bottom, so an
 * element placed on one resolution stays fully on screen at any other, and changing the GUI scale
 * doesn't push a panel off the edge.
 */
object HudManager {
    private val elements = ArrayList<HudElement>()
    private val positions = LinkedHashMap<String, FloatArray>()
    private val defaults = LinkedHashMap<String, FloatArray>()
    private val categories = LinkedHashMap<String, Category?>()
    private var registeredCategories: List<Category> = emptyList()
    private val idleClock = HudExpiry.Clock()

    @JvmStatic
    fun register(element: HudElement, defaultX: Float, defaultY: Float) {
        val category = (element as? Module)?.category() ?: Category.MISCELLANEOUS
        register(element, category, defaultX, defaultY)
    }

    @JvmStatic
    fun register(element: HudElement, category: Category?, defaultX: Float, defaultY: Float) {
        elements.add(element)
        categories[element.id()] = category
        registeredCategories = java.util.List.copyOf(
            Category.values().filter { categories.containsValue(it) },
        )
        val initial = floatArrayOf(clamp(defaultX), clamp(defaultY), 1.0f)
        defaults.putIfAbsent(element.id(), initial.copyOf())
        positions.putIfAbsent(element.id(), initial)
    }

    @JvmStatic
    fun elements(): List<HudElement> = java.util.List.copyOf(elements)

    /** Move Elements hides elements of disabled modules; modules without a toggle are always listed. */
    @JvmStatic
    fun editorVisible(element: HudElement): Boolean {
        if (!element.editorListed()) return false
        val module = element.module() ?: return true
        return !module.showsToggleControl() || module.isEnabled()
    }

    /** The page right-click opens in Move Elements. Every owner has one, whether or not it lists settings (the page still holds its toggle and keybind). */
    @JvmStatic
    fun settingsModule(element: HudElement): Module? = element.module()

    @JvmStatic
    fun category(element: HudElement): Category? = categories[element.id()]

    @JvmStatic
    fun categories(): List<Category> = registeredCategories

    @JvmStatic
    fun x(element: HudElement, font: Font?, screenWidth: Int): Int =
        Math.round(fraction(element)[0] * travel(screenWidth, scaledWidth(element, font)))

    @JvmStatic
    fun y(element: HudElement, font: Font?, screenHeight: Int): Int =
        Math.round(fraction(element)[1] * travel(screenHeight, scaledHeight(element, font)))

    /** Top-left position clamped using the editor preview's dimensions. */
    @JvmStatic
    fun previewX(element: HudElement, font: Font?, screenWidth: Int): Int =
        Math.round(fraction(element)[0] * travel(screenWidth, scaledPreviewWidth(element, font)))

    /** Top-left position clamped using the editor preview's dimensions. */
    @JvmStatic
    fun previewY(element: HudElement, font: Font?, screenHeight: Int): Int =
        Math.round(fraction(element)[1] * travel(screenHeight, scaledPreviewHeight(element, font)))

    @JvmStatic
    fun setPosition(element: HudElement, x: Int, y: Int, font: Font?, screenWidth: Int, screenHeight: Int) {
        val position = fraction(element)
        position[0] = clamp(x / travel(screenWidth, scaledWidth(element, font)).toFloat())
        position[1] = clamp(y / travel(screenHeight, scaledHeight(element, font)).toFloat())
    }

    /** Stores an editor position relative to the preview dimensions. */
    @JvmStatic
    fun setPreviewPosition(
        element: HudElement,
        x: Int,
        y: Int,
        font: Font?,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val position = fraction(element)
        position[0] = clamp(x / travel(screenWidth, scaledPreviewWidth(element, font)).toFloat())
        position[1] = clamp(y / travel(screenHeight, scaledPreviewHeight(element, font)).toFloat())
    }

    @JvmStatic
    fun scale(element: HudElement): Float = fraction(element)[2]

    /** Changes one panel's editor/live scale while keeping it anchored at its top-left corner. */
    @JvmStatic
    fun setScale(element: HudElement, scale: Float) {
        if (!scale.isFinite()) return
        fraction(element)[2] = maxOf(0.4f, minOf(3.0f, scale))
    }

    /** Resizes one panel while preserving its pixel top-left when the screen bounds allow it. */
    @JvmStatic
    fun setScalePreservingTopLeft(element: HudElement, scale: Float, font: Font?, screenWidth: Int, screenHeight: Int) {
        val x = x(element, font, screenWidth)
        val y = y(element, font, screenHeight)
        setScale(element, scale)
        setPosition(element, x, y, font, screenWidth, screenHeight)
    }

    /** Resizes an editor preview without moving its top-left corner. */
    @JvmStatic
    fun setPreviewScalePreservingTopLeft(
        element: HudElement,
        scale: Float,
        font: Font?,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val x = previewX(element, font, screenWidth)
        val y = previewY(element, font, screenHeight)
        setScale(element, scale)
        setPreviewPosition(element, x, y, font, screenWidth, screenHeight)
    }

    @JvmStatic
    fun scaledWidth(element: HudElement, font: Font?): Int = maxOf(1, Math.round(element.width(font) * scale(element)))

    @JvmStatic
    fun scaledHeight(element: HudElement, font: Font?): Int = maxOf(1, Math.round(element.height(font) * scale(element)))

    @JvmStatic
    fun scaledPreviewWidth(element: HudElement, font: Font?): Int =
        maxOf(1, Math.round(element.previewWidth(font) * scale(element)))

    @JvmStatic
    fun scaledPreviewHeight(element: HudElement, font: Font?): Int =
        maxOf(1, Math.round(element.previewHeight(font) * scale(element)))

    /** Draws one element with its per-id scale, shared by the live HUD and placement preview. */
    @JvmStatic
    fun renderElement(element: HudElement, graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, preview: Boolean) {
        graphics.pose().pushMatrix()
        graphics.pose().translate(x.toFloat(), y.toFloat())
        val scale = scale(element)
        graphics.pose().scale(scale, scale)
        if (preview) element.renderPreview(graphics, font, 0, 0) else element.render(graphics, font, 0, 0)
        graphics.pose().popMatrix()
    }

    /** Draws visible live-HUD elements; skipped while Move Elements has its own previews up. */
    @JvmStatic
    fun render(graphics: GuiGraphicsExtractor) = render(graphics, false)

    /** The pass before the vanilla hotbar, for elements that sit behind the vanilla HUD. */
    @JvmStatic
    fun renderBehindVanilla(graphics: GuiGraphicsExtractor) = render(graphics, true)

    private fun render(graphics: GuiGraphicsExtractor, behind: Boolean) {
        val mc = Minecraft.getInstance()
        if (mc.gui.hud.isHidden() || mc.gui.screen() is MoveUiScreen) return
        VisualModule.INSTANCE.refreshTheme()

        val font = mc.font
        val width = graphics.guiWidth()
        val height = graphics.guiHeight()
        // Fractions keep normal placements inside the screen, but a user-sized label can still be
        // wider than a tiny viewport. Keep every custom HUD element inside the framebuffer as well.
        graphics.enableScissor(0, 0, width, height)
        val island = HypixelModApi.currentIsland()
        val now = System.nanoTime()
        for (element in elements) {
            if (element.rendersBehindVanillaHud() != behind) continue
            val showing = element.renderOnHud() && element.visible()
            val idle = idleClock.idle(element.id(), showing, element.activitySignature(), now)
            if (!showing || idle || !HudExpiry.onIsland(element.hudIslands(), island)) continue
            renderElement(element, graphics, font, x(element, font, width), y(element, font, height), false)
        }
        graphics.disableScissor()
    }

    @JvmStatic
    fun positions(): Map<String, FloatArray> = snapshot()

    /** Applies saved positions; anything not mentioned keeps the default it registered with. */
    @JvmStatic
    fun restore(saved: Map<String, FloatArray?>) {
        for ((id, value) in saved) {
            if (value == null || value.size < 2) continue
            val restoredScale =
                if (value.size >= 3 && value[2].isFinite()) maxOf(0.4f, minOf(3.0f, value[2])) else 1.0f
            positions[id] = floatArrayOf(clamp(value[0]), clamp(value[1]), restoredScale)
        }
    }

    /** Restores every registered panel to its declared factory position. */
    @JvmStatic
    fun resetPositions() {
        positions.clear()
        for ((id, value) in defaults) positions[id] = value.copyOf()
    }

    private fun fraction(element: HudElement): FloatArray =
        positions.getOrPut(element.id()) { floatArrayOf(0f, 0f, 1.0f) }

    /** How many pixels the element can travel; never zero, so the division above is always safe. */
    private fun travel(screenSize: Int, elementSize: Int): Int = maxOf(1, screenSize - elementSize)

    private fun clamp(value: Float): Float = maxOf(0f, minOf(1f, value))

    /** Snapshot for the config, copied so a later drag can't rewrite what is being serialized. */
    @JvmStatic
    fun snapshot(): Map<String, FloatArray> = HashMap<String, FloatArray>().apply {
        for ((id, value) in positions) put(id, value.copyOf())
    }
}
