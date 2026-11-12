package geiler.addons.client.module.impl

import geiler.addons.client.gui.SlotIdBadgeLayout
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.SettingGroup
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.AbstractContainerMenu
import java.util.Locale

/** Shows each runtime menu slot id as a badge on that actual slot. */
class SlotIdsModule private constructor(settings: Settings) : Module(
    "Slot IDs",
    "Shows each container slot's runtime index for macro setup.",
    Category.DEV,
    settings.labelTextColor,
), HudElement {
    private val labelTextColorSetting = settings.labelTextColor

    private val diagnostics = ModuleDiagnostics(Category.DEV, { name() })
    private var hasObservedContext = false
    private var lastObservedScreen: Screen? = null
    private var lastObservedMenu: AbstractContainerMenu? = null
    private var lastObservedSlotCount = -1

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    /** Keeps the Java call used by ModConfig and SlotIdOverlay source-compatible. */
    fun labelTextColor(): ColorSetting = labelTextColorSetting

    override fun id(): String = "slot_ids"

    override fun displayName(): String = "Slot IDs"

    override fun width(font: Font?): Int = maxOf(
        8,
        minOf(15, font!!.width(SAMPLE_LABEL) + SlotIdBadgeLayout.BADGE_PADDING),
    )

    override fun height(font: Font?): Int = SlotIdBadgeLayout.BADGE_HEIGHT

    override fun visible(): Boolean = true

    override fun previewMode(): String = "sample"

    override fun renderOnHud(): Boolean = false

    /** Shows a sample only in Move Elements; live badges remain attached to their actual menu slots. */
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val width = width(font)
        graphics.text(
            font,
            SAMPLE_LABEL,
            x + width - font.width(SAMPLE_LABEL) - 1,
            y,
            labelTextColorSetting.argb(),
        )
    }

    override fun onEnable() {
        hasObservedContext = false
        lastObservedScreen = null
        lastObservedMenu = null
        lastObservedSlotCount = -1
        diagnostics.enable(diagnosticSettings())
    }

    override fun onDisable() {
        diagnostics.disable()
        hasObservedContext = false
        lastObservedScreen = null
        lastObservedMenu = null
        lastObservedSlotCount = -1
    }

    /** Samples the active menu on the client thread and writes a bounded status heartbeat. */
    private fun tick(client: Minecraft) {
        if (!isEnabled()) return
        diagnostics.advance()

        val screen = client.gui.screen()
        val containerScreen = screen as? AbstractContainerScreen<*>
        val menu = containerScreen?.menu
        val rawSlotCount = menu?.slots?.size ?: 0
        val slotsReported = rawSlotCount.coerceAtMost(MAX_REPORTED_SLOTS)
        val screenType = screen?.javaClass?.simpleName?.take(MAX_SCREEN_TYPE_CHARS) ?: "none"

        if (!hasObservedContext || screen !== lastObservedScreen || menu !== lastObservedMenu
            || rawSlotCount != lastObservedSlotCount
        ) {
            lastObservedScreen = screen
            lastObservedMenu = menu
            lastObservedSlotCount = rawSlotCount
            hasObservedContext = true
            diagnostics.event(
                "CONTEXT screen=$screenType containerScreen=${containerScreen != null} " +
                    "menuSlots=$slotsReported slotsCapped=${rawSlotCount > MAX_REPORTED_SLOTS} " +
                    "renderPathConfigured=AbstractContainerScreenMixin.extractSlots->SlotIdOverlay " +
                    "badgeHeight=${SlotIdBadgeLayout.BADGE_HEIGHT} badgePadding=${SlotIdBadgeLayout.BADGE_PADDING} " +
                    "labelArgb=${argbHex(labelTextColorSetting.argb())}",
            )
        }

        diagnostics.status { suppressed ->
            "enabled=true screen=$screenType containerScreen=${containerScreen != null} " +
                "menuSlots=$slotsReported slotsCapped=${rawSlotCount > MAX_REPORTED_SLOTS} " +
                "renderPath=AbstractContainerScreenMixin.extractSlots->SlotIdOverlay " +
                "badgeHeight=${SlotIdBadgeLayout.BADGE_HEIGHT} badgePadding=${SlotIdBadgeLayout.BADGE_PADDING} " +
                "labelArgb=${argbHex(labelTextColorSetting.argb())} suppressedEvents=$suppressed"
        }
    }

    private fun diagnosticSettings(): String =
        "labelArgb=${argbHex(labelTextColorSetting.argb())} " +
            "badgeHeight=${SlotIdBadgeLayout.BADGE_HEIGHT} badgePadding=${SlotIdBadgeLayout.BADGE_PADDING} " +
            "previewMode=sample renderOnHud=false"

    private fun argbHex(color: Int): String =
        "0x${Integer.toHexString(color).uppercase(Locale.ROOT).padStart(8, '0')}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Slot IDs")
        val labelTextColor = spec.color("labelTextColor")
    }

    companion object {
        private const val SAMPLE_LABEL = "41"
        private const val MAX_REPORTED_SLOTS = 512
        private const val MAX_SCREEN_TYPE_CHARS = 64

        @JvmField
        val INSTANCE = SlotIdsModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { client -> INSTANCE.tick(client) }
        }
    }
}
