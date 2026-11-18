package geiler.addons.client.module.impl

import geiler.addons.client.foraging.HotfPerks
import geiler.addons.client.mining.HotmText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.IdentityHashMap

/**
 * Heart of the Forest menu helpers, the Forest counterpart of HotM Tree Helper (after SkyHanni's HotF features): each
 * perk's level on its slot, an enabled/disabled highlight, the token count on the heart, and extra tooltip lines for
 * whispers spent, whispers to max, the cost of the next N levels, and the whispers held (Forest and Desert, read from the heart item).
 */
class HotfTreeHelperModule private constructor(settings: Settings) : Module(
    "HotF Tree Helper",
    "Shows perk levels, highlights and Forest and Desert Whisper costs in the Heart of the Forest menu.",
    Category.FORAGING,
    false,
    settings.showLevel, settings.highlight, settings.showTokens, settings.whispersSpent, settings.whispersToMax,
    settings.whispersNext, settings.nextLevels, settings.whispersHeld,
) {
    private val showLevel = settings.showLevel
    private val highlight = settings.highlight
    private val showTokens = settings.showTokens
    private val whispersSpent = settings.whispersSpent
    private val whispersToMax = settings.whispersToMax
    private val whispersNext = settings.whispersNext
    private val nextLevels = settings.nextLevels
    private val whispersHeld = settings.whispersHeld

    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 100L, 400)
    private val readings = IdentityHashMap<ItemStack, HotmText.Reading?>()
    private var menuOpen = false
    private var held: Map<String, Long> = emptyMap()
    private val currencies = HashMap<String, String>()
    private var opened = 0L
    private var tooltipsExtended = 0L
    private var slotsDrawn = 0L

    init {
        group(
            SettingGroup("Slots", showLevel, highlight, showTokens),
            SettingGroup("Tooltip", whispersSpent, whispersToMax, whispersNext, nextLevels, whispersHeld),
        )
    }

    private fun settingsText(): String =
        "level=${showLevel.value()};highlight=${highlight.value()};tokens=${showTokens.value()};spent=${whispersSpent.value()};" +
            "toMax=${whispersToMax.value()};next=${whispersNext.value()}x${nextLevels.intValue()};held=${whispersHeld.value()}"

    override fun onEnable() {
        readings.clear()
        menuOpen = false
        held = emptyMap()
        currencies.clear()
        opened = 0
        tooltipsExtended = 0
        slotsDrawn = 0
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        readings.clear()
        menuOpen = false
        held = emptyMap()
        currencies.clear()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        readings.clear()
        val screen = mc.gui.screen() as? AbstractContainerScreen<*>
        val open = screen != null && screen.title.string.trim().equals(MENU_TITLE, ignoreCase = true)
        if (open != menuOpen) {
            menuOpen = open
            if (open) opened++
            diagnostics.event("MENU ${if (open) "OPENED" else "CLOSED"} opens=$opened")
        }
        // The whispers held are only written on the heart item, so read them from the open menu.
        val heart = if (open) screen.menu.slots.firstOrNull { it.item.hoverName.string.equals(MENU_TITLE, true) } else null
        held = heart?.let { HotfPerks.whispers(SkyblockItems.loreLines(it.item)) } ?: emptyMap()
        diagnostics.statusOnChange {
            "menuOpen=$menuOpen opens=$opened slotsDrawn=$slotsDrawn tooltipsExtended=$tooltipsExtended " +
                "held=$held settings{${settingsText()}}"
        }
    }

    private fun reading(stack: ItemStack): HotmText.Reading? =
        readings.getOrPut(stack) { HotfPerks.read(stack.hoverName.string, SkyblockItems.loreLines(stack)) }

    /** Colors every perk slot behind its item: green enabled, red disabled, gray locked. */
    fun drawSlotHighlights(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        if (!isEnabled() || !highlight.value() || !menuOpen) return
        for (slot in slots) {
            if (!slot.isActive || slot.item.isEmpty) continue
            val color = HotmText.highlight(reading(slot.item) ?: continue)
            if (color == 0) continue
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
        }
    }

    /** The level on a perk slot, gold once maxed, and the token count on the heart, after the item is drawn. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || !menuOpen || stack.isEmpty) return
        val read = if (showLevel.value()) reading(stack) else null
        val text = read?.let { HotmText.slotText(it) }.orEmpty().ifEmpty { heartTokens(stack) }
        if (text.isEmpty()) return
        slotsDrawn++
        val font = Minecraft.getInstance().font
        val color = when {
            stack.hoverName.string.equals(MENU_TITLE, true) -> AQUA
            read?.maxed() == true -> GOLD
            else -> CREAM
        }
        graphics.text(font, text, x + 17 - font.width(text), y + 9, color, true)
    }

    private fun heartTokens(stack: ItemStack): String {
        if (!showTokens.value() || !stack.hoverName.string.equals(MENU_TITLE, ignoreCase = true)) return ""
        return HotmText.tokens(SkyblockItems.loreLines(stack))?.takeIf { it > 0 }?.toString().orEmpty()
    }

    /** Adds the whisper lines under a perk's cost line, or at the end when the perk shows no cost. */
    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || !menuOpen || stack.isEmpty) return
        val read = reading(stack) ?: return
        val options = HotmText.Options(
            whispersSpent.value(), whispersToMax.value(), whispersNext.value(), whispersHeld.value(), nextLevels.intValue(),
        )
        val costIndex = lines.indexOfFirst { it.string.trim() == "Cost" }
        // The cost line names the perk's currency; a maxed perk has none, so reuse what an earlier tooltip showed.
        val named = if (costIndex >= 0) lines.getOrNull(costIndex + 1)?.let { HotfPerks.currency(it.string) } else null
        if (named != null) currencies[read.perk.name] = named
        val currency = named ?: currencies[read.perk.name] ?: "Whispers"
        val extra = HotmText.tooltipLines(read, options, held[currency], currency)
        if (extra.isEmpty()) return
        var at = if (costIndex >= 0) (costIndex + 2).coerceAtMost(lines.size) else lines.size
        lines.add(at++, Component.literal(" "))
        for (line in extra) lines.add(at++, Component.literal(line))
        tooltipsExtended++
        diagnostics.event("TOOLTIP perk=${read.perk.name} level=${read.level} currency=$currency added=${extra.size}")
    }

    private class Settings {
        val showLevel = BooleanSetting("Level On Slot", true)
        val highlight = BooleanSetting("Highlight Perks", true)
        val showTokens = BooleanSetting("Tokens On Heart", true)
        val whispersSpent = BooleanSetting("Whispers Spent", true)
        val whispersToMax = BooleanSetting("Whispers To Max", true)
        val whispersNext = BooleanSetting("Whispers For Next Levels", true)
        val nextLevels = NumberSetting("Next Levels", 1f, 50f, 10f, true)
        val whispersHeld = BooleanSetting("Whispers You Have", true)
    }

    companion object {
        private const val MENU_TITLE = "Heart of the Forest"
        private const val GOLD = 0xFFFFAA00.toInt()
        private const val CREAM = 0xFFFFFFAA.toInt()
        private const val AQUA = 0xFF55FFFF.toInt()

        @JvmField
        val INSTANCE = HotfTreeHelperModule(Settings())

        init {
            ItemTooltipCallback.EVENT.register { stack, _, _, lines -> INSTANCE.onTooltip(stack, lines) }
        }
    }
}
