package geiler.addons.client.module.impl

import geiler.addons.client.mining.HotmText
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MiningTabText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
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
 * Heart of the Mountain menu helpers, after SkyHanni's HotM features and Skyblocker's perk level slot text: each
 * perk's level on its slot, an enabled/disabled highlight, the token count on the heart, and extra tooltip lines
 * for powder spent, powder to max, the cost of the next N levels, and the powder held.
 */
class HotmTreeHelperModule private constructor(settings: Settings) : Module(
    "HotM Tree Helper",
    "Shows perk levels on the Heart of the Mountain slots and powder costs in their tooltips.",
    Category.MINING,
    false,
    settings.showLevel, settings.highlight, settings.showTokens, settings.powderSpent, settings.powderToMax,
    settings.powderNext, settings.nextLevels, settings.powderHeld,
) {
    private val showLevel = settings.showLevel
    private val highlight = settings.highlight
    private val showTokens = settings.showTokens
    private val powderSpent = settings.powderSpent
    private val powderToMax = settings.powderToMax
    private val powderNext = settings.powderNext
    private val nextLevels = settings.nextLevels
    private val powderHeld = settings.powderHeld

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private val readings = IdentityHashMap<ItemStack, HotmText.Reading?>()
    private var treeOpen = false
    private var holdingTick = Long.MIN_VALUE
    private var holding: MiningTabText.Powders? = null
    private var tick = 0L
    private var opened = 0L
    private var tooltipsExtended = 0L
    private var slotsDrawn = 0L

    init {
        group(
            geiler.addons.client.module.SettingGroup("Slots", showLevel, highlight, showTokens),
            geiler.addons.client.module.SettingGroup("Tooltip", powderSpent, powderToMax, powderNext, nextLevels, powderHeld),
        )
    }

    private fun settingsText(): String =
        "level=${showLevel.value()};highlight=${highlight.value()};tokens=${showTokens.value()};spent=${powderSpent.value()};" +
            "toMax=${powderToMax.value()};next=${powderNext.value()}x${nextLevels.intValue()};held=${powderHeld.value()}"

    override fun onEnable() {
        readings.clear()
        treeOpen = false
        opened = 0
        tooltipsExtended = 0
        slotsDrawn = 0
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        readings.clear()
        treeOpen = false
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
        tick++
        readings.clear()
        val open = inTree(mc)
        if (open != treeOpen) {
            treeOpen = open
            if (open) opened++
            diagnostics.event("TREE ${if (open) "OPENED" else "CLOSED"} opens=$opened")
        }
        diagnostics.statusOnChange {
            "treeOpen=$treeOpen opens=$opened slotsDrawn=$slotsDrawn tooltipsExtended=$tooltipsExtended " +
                "holding=${holding != null} settings{${settingsText()}}"
        }
    }

    private fun inTree(mc: Minecraft): Boolean {
        val screen = mc.gui.screen() as? AbstractContainerScreen<*> ?: return false
        return screen.title.string.trim().equals(TREE_TITLE, ignoreCase = true)
    }

    private fun reading(stack: ItemStack): HotmText.Reading? =
        readings.getOrPut(stack) { HotmText.read(stack.hoverName.string, SkyblockItems.loreLines(stack)) }

    /** Colors every perk slot behind its item: green enabled, red disabled, gray locked. */
    fun drawSlotHighlights(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        if (!isEnabled() || !highlight.value() || !treeOpen) return
        for (slot in slots) {
            if (!slot.isActive || slot.item.isEmpty) continue
            val read = reading(slot.item) ?: continue
            val color = HotmText.highlight(read)
            if (color == 0) continue
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
        }
    }

    /** The level on a perk slot and the token count on the heart, after the item is drawn. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || !treeOpen || stack.isEmpty) return
        val text = when {
            showLevel.value() -> reading(stack)?.let { HotmText.slotText(it) }.orEmpty()
            else -> ""
        }.ifEmpty { heartTokens(stack) }
        if (text.isEmpty()) return
        slotsDrawn++
        val font = Minecraft.getInstance().font
        val color = if (stack.hoverName.string.equals(TREE_TITLE, true)) AQUA else YELLOW
        graphics.text(font, text, x + 17 - font.width(text), y + 9, color, true)
    }

    private fun heartTokens(stack: ItemStack): String {
        if (!showTokens.value() || !stack.hoverName.string.equals(TREE_TITLE, ignoreCase = true)) return ""
        return HotmText.tokens(SkyblockItems.loreLines(stack))?.takeIf { it > 0 }?.toString().orEmpty()
    }

    /** Adds the powder lines under a perk's cost line, or at the end when the perk shows no cost. */
    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || !treeOpen || stack.isEmpty) return
        val read = reading(stack) ?: return
        val options = HotmText.Options(
            powderSpent.value(), powderToMax.value(), powderNext.value(), powderHeld.value(), nextLevels.intValue(),
        )
        val extra = HotmText.tooltipLines(read, options, heldPowder(read.perk.powder))
        if (extra.isEmpty()) return
        val costIndex = lines.indexOfFirst { it.string.trim() == "Cost" }
        var at = if (costIndex >= 0) (costIndex + 2).coerceAtMost(lines.size) else lines.size
        lines.add(at++, Component.literal(" "))
        for (line in extra) lines.add(at++, Component.literal(line))
        tooltipsExtended++
        diagnostics.event("TOOLTIP perk=${read.perk.name} level=${read.level} added=${extra.size}")
    }

    /** The powder held for [type], from the tab list, refreshed at most once a second while hovering. */
    private fun heldPowder(type: String?): Long? {
        if (type == null) return null
        if (tick - holdingTick >= REFRESH_TICKS || holding == null) {
            holdingTick = tick
            holding = MiningTabText.powders(MiningContext.tabLines())
        }
        val powders = holding ?: return null
        return when (type) {
            "Mithril" -> powders.mithril
            "Gemstone" -> powders.gemstone
            else -> powders.glacite
        }
    }

    private class Settings {
        val showLevel = BooleanSetting("Level On Slot", true)
        val highlight = BooleanSetting("Highlight Perks", true)
        val showTokens = BooleanSetting("Tokens On Heart", true)
        val powderSpent = BooleanSetting("Powder Spent", true)
        val powderToMax = BooleanSetting("Powder To Max", true)
        val powderNext = BooleanSetting("Powder For Next Levels", true)
        val nextLevels = NumberSetting("Next Levels", 1f, 50f, 10f, true)
        val powderHeld = BooleanSetting("Powder You Have", true)
    }

    companion object {
        private const val TREE_TITLE = "Heart of the Mountain"
        private const val REFRESH_TICKS = 20L
        private const val YELLOW = 0xFFFFFF55.toInt()
        private const val AQUA = 0xFF55FFFF.toInt()

        @JvmField
        val INSTANCE = HotmTreeHelperModule(Settings())

        init {
            ItemTooltipCallback.EVENT.register { stack, _, _, lines -> INSTANCE.onTooltip(stack, lines) }
        }
    }
}
