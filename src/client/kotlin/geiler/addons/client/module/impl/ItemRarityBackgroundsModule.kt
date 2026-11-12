package geiler.addons.client.module.impl

import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack

/** Colors the slot behind SkyBlock items by rarity (plus Jacob medals and attribute items), in inventories and the hotbar. */
class ItemRarityBackgroundsModule private constructor(
    private val settings: Settings,
) : Module(
    "Item Rarity Backgrounds",
    "Colors the slot behind SkyBlock items by rarity, with Jacob medal and attribute item backgrounds.",
    Category.VISUAL,
    false,
    settings.rarity,
    settings.legacyColors,
    settings.style,
    settings.opacity,
    settings.jacob,
    settings.attributes,
) {
    companion object {
        @JvmField
        val INSTANCE = ItemRarityBackgroundsModule(Settings())
        private const val CACHE_CLEAR_TICKS = 6000L
        private const val ATTRIBUTE_RGB = 0xFF0000
        private val circle = ItemBackgrounds.circleRuns()
    }

    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() })
    private var ticks = 0L
    private var drawn = 0

    init {
        group(
            SettingGroup("Backgrounds", settings.rarity, settings.legacyColors, settings.jacob, settings.attributes),
            SettingGroup("Appearance", settings.style, settings.opacity),
        )
    }

    override fun onEnable() {
        ticks = 0L
        diagnostics.enable(
            "rarity=${settings.rarity.value()},legacy=${settings.legacyColors.value()},style=${settings.style.value()}," +
                "opacity=${settings.opacity.intValue()},jacob=${settings.jacob.value()},attributes=${settings.attributes.value()}",
        )
    }

    override fun onDisable() {
        diagnostics.disable()
        ItemBackgrounds.clearCache()
    }

    /** Clears the per-stack cache every five minutes, like Skyblocker, and writes the status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (++ticks % CACHE_CLEAR_TICKS == 0L) ItemBackgrounds.clearCache()
        diagnostics.status { suppressed ->
            "drawnSinceLastStatus=$drawn cachedStacks=${ItemBackgrounds.cacheSize()} style=${settings.style.value()} " +
                "opacity=${settings.opacity.intValue()} suppressedEvents=$suppressed".also { drawn = 0 }
        }
    }

    /** Draws before the item, so the item stays on top; x and y are the item's top-left corner. */
    fun drawBackground(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || stack.isEmpty) return
        val info = ItemBackgrounds.info(stack)
        val rarity = info.rarity
        if (settings.rarity.value() && rarity != null) fill(graphics, x, y, rarity.color(settings.legacyColors.value()))
        if (settings.jacob.value() && info.jacobColor >= 0) fill(graphics, x, y, info.jacobColor)
        if (settings.attributes.value() && info.hasAttributes) fill(graphics, x, y, ATTRIBUTE_RGB)
    }

    private fun fill(graphics: GuiGraphicsExtractor, x: Int, y: Int, rgb: Int) {
        val argb = ItemBackgrounds.fillArgb(rgb, settings.opacity.intValue())
        if (argb ushr 24 == 0) return
        if (settings.style.value() == "Circular") {
            for (i in circle.indices step 3) {
                val half = circle[i + 2]
                graphics.fill(x + 8 - half, y + circle[i], x + 8 + half, y + circle[i + 1], argb)
            }
        } else {
            graphics.fill(x, y, x + 16, y + 16, argb)
        }
        drawn++
    }

    private class Settings {
        val rarity = BooleanSetting("Rarity Backgrounds", true)
        val legacyColors = BooleanSetting("Legacy Rarity Colors", false)
        val style = ChoiceSetting("Background Style", "Square", "Square", "Circular")
        val opacity = NumberSetting("Background Opacity", 0f, 100f, 50f, true)
        val jacob = BooleanSetting("Jacob Medal Backgrounds", true)
        val attributes = BooleanSetting("Attribute Item Backgrounds", true)
    }
}
