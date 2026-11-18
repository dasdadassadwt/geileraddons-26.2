package geiler.addons.client.module.impl

import geiler.addons.client.farming.ComposterRules
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.farming.RareCropRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

private fun inGarden(): Boolean = HypixelModApi.currentIsland() == Island.GARDEN

/** Shows a title and sound for a `RARE CROP!` or `VERY RARE CROP!` drop, after SkyHanni's Burrowing Spores notifier. */
class RareCropTitleModule private constructor() : Module(
    "Rare Crop Title",
    "Shows a title when a rare or very rare crop drops in the Garden.",
    Category.FARMING,
    false,
) {
    private var shown = 0L
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)

    override fun onEnable() {
        shown = 0L
        diagnostics.enable("title on RARE CROP lines in the Garden")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE shown=$shown")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true inGarden=${inGarden()} shown=$shown suppressedEvents=$it" }
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled() || !inGarden()) return
        val name = RareCropRules.name(plain) ?: return
        val very = RareCropRules.isVeryRare(plain)
        shown++
        diagnostics.event("DROP item=$name veryRare=$very count=$shown")
        PlayerAlerts.alert(if (very) "§6§lVERY RARE: §9$name" else "§6RARE: §9$name")
    }

    companion object {
        @JvmField
        val INSTANCE = RareCropTitleModule()
    }
}

/** Shows the Composter tab-list widget on the HUD and warns when organic matter or fuel runs low, after SkyHanni's Composter Display. */
class ComposterDisplayModule private constructor() : Module(
    "Composter Display",
    "Shows the composter's time left, organic matter, fuel and stored compost, and warns when it runs low.",
    Category.FARMING,
    false,
    *spec.settings.toTypedArray(),
), HudElement {
    private val organicMin = spec.slider("lowOrganicMatter")
    private val fuelMin = spec.slider("lowFuel")
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)
    private var data: ComposterRules.Data? = null
    private var ticks = 0
    private var warnedAt = 0L
    private var warnings = 0

    override fun id(): String = "composter_display"
    override fun displayName(): String = "Composter"
    override fun hudIslands(): Set<Island> = setOf(Island.GARDEN)
    private fun shown(): List<String> = ComposterRules.lines(data ?: SAMPLE)
    override fun width(font: Font?): Int = shown().maxOf { font!!.width(it) }
    override fun height(font: Font?): Int = shown().size * (font!!.lineHeight + 1)
    override fun visible(): Boolean = isEnabled() && data != null && inGarden()
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        shown().forEachIndexed { index, line ->
            graphics.text(font, Component.literal(line), x, y + index * (font.lineHeight + 1), 0xFFFFFFFF.toInt())
        }
    }

    /** The editor shows the sample lines at the size the live widget takes, not just the title. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = render(graphics, font, x, y)

    override fun onEnable() {
        data = null
        warnings = 0
        diagnostics.enable("lowOrganicMatter=${organicMin.intValue()} lowFuel=${fuelMin.intValue()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE warnings=$warnings")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (!inGarden()) {
            data = null
        } else if (ticks++ % READ_TICKS == 0) {
            val read = ComposterRules.parse(PestWidgetReader.currentTabList())
            if ((read != null) != (data != null)) diagnostics.event(if (read != null) "WIDGET found" else "WIDGET gone")
            if (read != null) data = read
            read?.let(::warnIfLow)
        }
        diagnostics.status { "enabled=true inGarden=${inGarden()} data=${data != null} organic=${data?.organicMatter} fuel=${data?.fuel} warnings=$warnings suppressedEvents=$it" }
    }

    private fun warnIfLow(read: ComposterRules.Data) {
        val low = ComposterRules.lowResource(read, organicMin.intValue().toLong(), fuelMin.intValue().toLong()) ?: return
        val now = System.nanoTime()
        if (warnedAt != 0L && now - warnedAt < WARN_COOLDOWN_NANOS) return
        warnedAt = now
        warnings++
        diagnostics.event("LOW $low count=$warnings")
        PlayerAlerts.alert("§cYour $low is low")
    }

    companion object {
        private const val READ_TICKS = 20
        private const val WARN_COOLDOWN_NANOS = 5L * 60L * 1_000_000_000L
        private val SAMPLE = ComposterRules.Data(40_000L, 100_000L, "7m 30s", "1,234")
        private val spec = ModuleSettingsSpec.of("Composter Display")

        @JvmField
        val INSTANCE = ComposterDisplayModule()
    }
}
