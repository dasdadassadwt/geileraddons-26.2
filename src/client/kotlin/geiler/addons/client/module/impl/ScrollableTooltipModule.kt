package geiler.addons.client.module.impl

import geiler.addons.client.gui.PlayerInventorySlotScreenAccess
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import org.joml.Matrix3x2fStack

/**
 * Scrolls and scales the tooltip of the hovered inventory item with the mouse wheel, so tooltips taller than the
 * screen can be read. Scroll = up/down, Shift = sideways, Ctrl = scale, Alt = finer steps.
 */
class ScrollableTooltipModule private constructor(settings: Settings) : Module(
    "Scrollable Tooltip",
    "Scroll an item's tooltip with the mouse wheel: wheel moves it up and down, Shift sideways, Ctrl scales, " +
        "Alt makes finer steps.",
    Category.MISCELLANEOUS,
    false,
    settings.allowHorizontal,
    settings.allowVertical,
    settings.allowScale,
    settings.lockInPlace,
    settings.dontDivideByScale,
    settings.resetTooltip,
    settings.startTop,
    settings.reset,
) {
    private val allowHorizontal = settings.allowHorizontal
    private val allowVertical = settings.allowVertical
    private val allowScale = settings.allowScale
    private val lockInPlace = settings.lockInPlace
    private val dontDivideByScale = settings.dontDivideByScale
    private val resetTooltip = settings.resetTooltip
    private val startTop = settings.startTop
    private val state = settings.state
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 320)
    private var scrolls = 0L
    private var drawnTooltips = 0L

    override fun onEnable() {
        state.reset()
        scrolls = 0L
        drawnTooltips = 0L
        diagnostics.enable(settingsLine())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE scrolls=$scrolls drawnTooltips=$drawnTooltips")
        diagnostics.disable()
        state.reset()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "enabled=true x=${state.x} y=${state.y} scale=${state.scale} scrolls=$scrolls " +
                "drawnTooltips=$drawnTooltips ${settingsLine()} suppressedEvents=$it"
        }
    }

    /** Mouse wheel over a container screen; [screen] supplies the hovered slot. */
    fun onScroll(screen: AbstractContainerScreen<*>, scrollX: Double, scrollY: Double) {
        if (!isEnabled() || hoveredStack(screen) == null) return
        val amount = if (scrollY != 0.0) scrollY else scrollX
        val client = Minecraft.getInstance()
        val changed = state.scroll(
            amount, client.hasControlDown(), client.hasShiftDown(), client.hasAltDown(),
            allowHorizontal.value(), allowVertical.value(), allowScale.value(),
        )
        if (!changed) return
        scrolls++
        diagnostics.event("scroll x=${state.x} y=${state.y} scale=${state.scale}")
    }

    /**
     * Called as an item tooltip is positioned. Returns true when the tooltip is to be transformed; the hovered slot
     * is also tracked here so the offset resets when it changes.
     */
    fun beginTooltip(): Boolean {
        if (!isEnabled()) return false
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return false
        if (hoveredStack(screen) == null) return false
        state.hover((screen as PlayerInventorySlotScreenAccess).`geileraddons$hoveredSlot`(), resetTooltip.value())
        drawnTooltips++
        return true
    }

    /** Pushes the tooltip transform onto [pose]; the caller pops it once the tooltip has been drawn. */
    fun transform(pose: Matrix3x2fStack) {
        val scale = state.effectiveScale()
        pose.pushMatrix()
        pose.scale(scale, scale)
        if (lockInPlace.value()) {
            val divisor = if (dontDivideByScale.value()) 1.0f else scale
            pose.translate(state.x / divisor, state.y / divisor)
        } else {
            pose.translate(state.x, state.y)
        }
    }

    fun locked(): Boolean = lockInPlace.value()
    fun scale(): Float = state.effectiveScale()
    fun startsAtTop(): Boolean = isEnabled() && startTop.value()

    private fun hoveredStack(screen: Screen) =
        (screen as? PlayerInventorySlotScreenAccess)?.`geileraddons$hoveredSlot`()?.item?.takeUnless { it.isEmpty }

    private fun settingsLine(): String =
        "horizontal=${allowHorizontal.value()} vertical=${allowVertical.value()} scaling=${allowScale.value()} " +
            "locked=${lockInPlace.value()} ignoreScale=${dontDivideByScale.value()} reset=${resetTooltip.value()} " +
            "startTop=${startTop.value()}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Scrollable Tooltip")
        val allowHorizontal = spec.toggle("allowHorizontal")
        val allowVertical = spec.toggle("allowVertical")
        val allowScale = spec.toggle("allowScale")
        val lockInPlace = spec.toggle("lockInPlace")
        val dontDivideByScale = spec.toggle("dontDivideByScale")
        val resetTooltip = spec.toggle("resetTooltip")
        val startTop = spec.toggle("startTop")
        val state = ScrollableTooltipState()
        val reset = ModuleAction("Reset", "Resets the tooltip scale to 1.0 and its scroll position to zero.") { state.reset() }
    }

    companion object {
        @JvmField
        val INSTANCE = ScrollableTooltipModule(Settings())

        /** A huge screen height makes vanilla's "shift the tooltip up at the bottom edge" clamp never trigger. */
        const val NO_BOTTOM_CLAMP_HEIGHT = Int.MAX_VALUE / 2
    }
}
