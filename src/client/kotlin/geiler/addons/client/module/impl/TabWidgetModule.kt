package geiler.addons.client.module.impl

import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.tabwidget.TabWidgetRules
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Tab Widget (after SkyHanni's Tab Widget display): shows the Hypixel tab-list widgets you pick as movable HUD panels, each
 * only on the islands it belongs to. A widget only appears when the tab list shows it (turn it on with `/widget`).
 */
class TabWidgetModule private constructor(private val s: Settings) : Module(
    "Tab Widget",
    "Shows chosen tab-list widgets (Soulflow, Commissions, Pests, ...) as movable HUD panels, on their own islands.",
    Category.VISUAL,
    false,
    *s.all().toTypedArray(),
) {
    private class Settings {
        val islandFilter = BooleanSetting("Island Filter", true)
        val appearance = MiningHudAppearance()
        val toggles: Map<String, BooleanSetting> = TabWidgetRules.WIDGETS.associate { it.id to BooleanSetting(it.name, false) }

        fun all(): List<Setting> = listOf<Setting>(islandFilter) + appearance.all() + toggles.values
    }

    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)

    @Volatile
    private var blocks: Map<String, List<String>> = emptyMap()
    private var tickCount = 0L
    private var tabRows = 0

    /** One movable HUD panel per widget, registered with the HUD manager in the Visual category. */
    val panels: List<HudElement> = TabWidgetRules.WIDGETS.map { Panel(it) }

    init {
        val byGroup = TabWidgetRules.WIDGETS.groupBy { it.group }
        group(
            SettingGroup("Display", s.islandFilter),
            SettingGroup("Appearance", *s.appearance.all()),
            *byGroup.map { (name, widgets) -> SettingGroup(name, *widgets.map { s.toggles.getValue(it.id) }.toTypedArray()) }.toTypedArray(),
        )
    }

    override fun settingTooltip(setting: Setting): String? = when {
        setting === s.islandFilter -> "Show each widget only on the islands where it exists. Off shows it on every island."
        s.toggles.values.any { it === setting } -> "Shows this widget when the tab list has it. If nothing appears, turn the widget on with /widget."
        else -> null
    }

    override fun onEnable() {
        blocks = emptyMap()
        tickCount = 0L
        diagnostics.enable("filter=${s.islandFilter.value()} widgets=${enabledIds().joinToString(",").ifEmpty { "none" }}")
    }

    override fun onDisable() {
        blocks = emptyMap()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    private fun enabledIds(): List<String> = TabWidgetRules.WIDGETS.filter { s.toggles.getValue(it.id).value() }.map { it.id }

    /** Reads the tab list a few times a second and keeps one block per widget. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        if (mc.level == null || mc.player == null) {
            blocks = emptyMap()
            return
        }
        if (tickCount++ % REFRESH_TICKS == 0L) {
            val rows = PestWidgetReader.currentTabList()
            tabRows = rows.size
            val parsed = TabWidgetRules.parse(rows)
            val before = blocks
            for (id in enabledIds()) {
                val wasShown = before.containsKey(id)
                val isShown = parsed.containsKey(id)
                if (wasShown != isShown) {
                    diagnostics.event("WIDGET ${if (isShown) "APPEARED" else "GONE"} id=$id lines=${parsed[id]?.size ?: 0} island=${HypixelModApi.currentIsland()}")
                }
            }
            blocks = parsed
        }
        diagnostics.status {
            "enabled=true tabRows=$tabRows found=${blocks.keys.joinToString(",").ifEmpty { "none" }} " +
                "selected=${enabledIds().size} island=${HypixelModApi.currentIsland()} filter=${s.islandFilter.value()} suppressedEvents=$it"
        }
    }

    private inner class Panel(private val widget: TabWidgetRules.Widget) : HudElement {
        private val toggle = s.toggles.getValue(widget.id)

        override fun id(): String = "tab_widget_${widget.id}"

        override fun displayName(): String = "Tab Widget: ${widget.name}"

        override fun module(): Module = this@TabWidgetModule

        override fun hudIslands(): Set<Island> = if (s.islandFilter.value()) widget.islands else emptySet()

        /** Move Elements lists only the widgets that are switched on, so the editor is not full of unused panels. */
        override fun editorListed(): Boolean = toggle.value()

        private fun lines(): List<String> = blocks[widget.id].orEmpty()

        override fun visible(): Boolean = isEnabled() && toggle.value() && lines().isNotEmpty()

        override fun previewMode(): String = if (lines().isEmpty()) "sample" else "live"

        override fun width(font: Font?): Int = widthOf(font!!, lines().ifEmpty { widget.sample })

        override fun height(font: Font?): Int = heightOf(font!!, lines().ifEmpty { widget.sample })

        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, lines())

        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
            draw(graphics, font, x, y, lines().ifEmpty { widget.sample })

        private fun widthOf(font: Font, content: List<String>): Int =
            Math.round((content.maxOfOrNull { font.width(it) } ?: 1) * s.appearance.textScale.value()) + PAD * 2

        private fun heightOf(font: Font, content: List<String>): Int =
            Math.round(content.size * (font.lineHeight + 2) * s.appearance.textScale.value()) + PAD * 2

        private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, content: List<String>) {
            if (content.isEmpty()) return
            val look = s.appearance
            if (look.background.value()) {
                GuiTheme.roundedRect(graphics, x, y, widthOf(font, content), heightOf(font, content), 3, look.backgroundColor.argb())
            }
            val scale = look.textScale.value()
            graphics.pose().pushMatrix()
            graphics.pose().translate((x + PAD).toFloat(), (y + PAD).toFloat())
            graphics.pose().scale(scale, scale)
            for ((index, line) in content.withIndex()) {
                val text = if (index == 0) "§6$line" else line
                graphics.text(font, Component.literal(text), 0, index * (font.lineHeight + 2), look.textColor.argb())
            }
            graphics.pose().popMatrix()
        }
    }

    companion object {
        private const val REFRESH_TICKS = 10L
        private const val PAD = 4

        @JvmField
        val INSTANCE = TabWidgetModule(Settings())
    }
}
