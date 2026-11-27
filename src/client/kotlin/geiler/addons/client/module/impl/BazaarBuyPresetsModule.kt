package geiler.addons.client.module.impl

import geiler.addons.client.bazaar.BazaarPresetRules
import geiler.addons.client.mixin.AbstractSignEditScreenAccessor
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.network.chat.Component

/**
 * Amount buttons beside the Bazaar's buy amount sign. A button only writes the number onto the sign: the player still
 * presses Done, and the mod sends nothing itself.
 */
class BazaarBuyPresetsModule private constructor(private val spec: ModuleSettingsSpec.Spec) : Module(
    "Bazaar Buy Presets",
    "Amount buttons on the Bazaar buy sign. They fill in the number; you press Done.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray<Setting>(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 200)
    private val presets = spec.text("presets")
    private var limit = 0
    private var menuSeenAt = 0L
    private var lastMenu = ""
    private var filled = 0

    override fun onEnable() {
        forgetMenu()
        diagnostics.enable("presets=${presets.value()}")
    }

    override fun onDisable() {
        forgetMenu()
        diagnostics.disable()
    }

    private fun forgetMenu() {
        limit = 0
        menuSeenAt = 0L
        lastMenu = ""
    }

    /** Client tick: remembers the per-order limit from the amount menu that leads to the sign. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "limit=$limit menu=${lastMenu.take(40)} filled=$filled suppressedEvents=$it" }
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
        val title = screen.title.string.trim()
        if (!BazaarPresetRules.isAmountMenu(title)) {
            if (menuSeenAt != 0L) forgetMenu()
            return
        }
        menuSeenAt = System.currentTimeMillis()
        if (title == lastMenu && limit > 0) return
        val slots = screen.menu.slots
        val items = slots.take(maxOf(0, slots.size - PLAYER_SLOTS)).map { it.item }.filter { !it.isEmpty }
        var found = items.firstNotNullOfOrNull { item ->
            BazaarPresetRules.limitFromLore(SkyblockItems.loreLines(item)).takeIf { it > 0 }
        } ?: 0
        var source = "lore"
        if (found == 0) {
            // The "Buy only one!" and "Buy a stack!" slots show the product itself.
            val product = items.firstOrNull { it.hoverName.string.startsWith("Buy ") }
            if (product != null) {
                found = BazaarPresetRules.limitFromStackSize(product.maxStackSize)
                source = "stack-size"
            }
        }
        if (found > 0 && (found != limit || title != lastMenu)) {
            limit = found
            lastMenu = title
            diagnostics.event("LIMIT limit=$found source=$source menu=${title.take(60)}")
        }
    }

    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, width, _ ->
            if (screen !is AbstractSignEditScreen || !isEnabled()) return@register
            val accessor = screen as AbstractSignEditScreenAccessor
            val lines = accessor.`geiler$messages`()
            val matches = BazaarPresetRules.isAmountSign(lines)
            val fresh = menuSeenAt != 0L && System.currentTimeMillis() - menuSeenAt <= MENU_MEMORY_MS
            diagnostics.event("SIGN match=$matches fromAmountMenu=$fresh limit=$limit " +
                "lines=${lines.drop(1).joinToString("|").take(120)}")
            if (!matches) return@register
            val known = if (fresh) limit else 0
            var y = 65
            for (preset in BazaarPresetRules.parsePresets(presets.value())) {
                val amount = BazaarPresetRules.amountOf(preset.amount, known) ?: continue
                val label = if (preset.amount == null) "Max (%,d)".format(java.util.Locale.ROOT, amount) else preset.label
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal(label)) { fill(accessor, amount) }
                        .bounds(width / 2 + 60, y, BUTTON_WIDTH, 20).build(),
                )
                y += 22
            }
        }
    }

    private fun fill(sign: AbstractSignEditScreenAccessor, amount: Int) {
        sign.`geiler$messages`()[0] = amount.toString()
        sign.`geiler$signField`()?.setCursorToEnd()
        filled++
        diagnostics.event("FILL amount=$amount")
    }

    companion object {
        private const val PLAYER_SLOTS = 36
        private const val MENU_MEMORY_MS = 120_000L
        private const val BUTTON_WIDTH = 86

        @JvmField
        val INSTANCE = BazaarBuyPresetsModule(ModuleSettingsSpec.of("Bazaar Buy Presets"))
    }
}
