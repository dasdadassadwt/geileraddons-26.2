package geiler.addons.client.module.impl

import geiler.addons.client.fishing.FishingBaitRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.PlayerAlerts
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The fishing bait in your last hotbar slot: a movable "1,234x Whale Bait" line while a rod is held, and warnings
 * when the bait changes or a cast goes out with none, after SkyHanni's Fishing Bait Display and Bait Warnings.
 */
class FishingBaitModule private constructor(settings: Settings) : Module(
    "Fishing Bait",
    "Shows your fishing bait and warns when it changes or runs out.",
    Category.FISHING,
    false,
    settings.showDisplay,
    settings.showIcon,
    settings.baitChangeWarning,
    settings.noBaitWarning,
), HudElement {
    private val showDisplay = settings.showDisplay
    private val showIcon = settings.showIcon
    private val baitChangeWarning = settings.baitChangeWarning
    private val noBaitWarning = settings.noBaitWarning

    private val diagnostics = ModuleDiagnostics(Category.FISHING, { name() }, 8, 100L, 240)
    private val warnings = FishingBaitRules.Warnings()

    private class Bait(val stack: ItemStack, val name: Component, val key: String, val amount: Int)

    private var bait: Bait? = null
    private var holdingRod = false
    private var bobberPresent = false
    private var lastSignature = ""
    private var changeWarnings = 0
    private var noBaitWarnings = 0

    override fun id(): String = "fishing_bait"

    override fun displayName(): String = "Fishing Bait"

    override fun width(font: Font?): Int = line(bait).let { (if (hasIcon(bait)) ICON_WIDTH else 0) + font!!.width(it) }

    override fun height(font: Font?): Int = if (hasIcon(bait)) ICON_SIZE else font!!.lineHeight

    override fun previewWidth(font: Font?): Int = ICON_WIDTH + font!!.width(sampleLine())

    override fun previewHeight(font: Font?): Int = ICON_SIZE

    override fun visible(): Boolean = isEnabled() && showDisplay.value() && holdingRod

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val current = bait
        draw(graphics, font, x, y, current?.stack?.takeIf { hasIcon(current) }, line(current))
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (visible()) render(graphics, font, x, y)
        else draw(graphics, font, x, y, if (showIcon.value()) ItemStack(Items.COD) else null, sampleLine())
    }

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "display=${showDisplay.value()} icon=${showIcon.value()} change=${baitChangeWarning.value()} " +
                "noBait=${noBaitWarning.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null || !SkyblockItems.inSkyblock()) {
            if (lastSignature.isNotEmpty() || holdingRod) {
                reset()
                diagnostics.event("RESET reason=${if (player == null) "no-player" else "not-skyblock"}")
            }
            return
        }

        val holding = player.mainHandItem.`is`(Items.FISHING_ROD)
        val reading = if (holding) readBait(player.inventory.getItem(FishingBaitRules.BAIT_HOTBAR_SLOT)) else null
        val signature = "$holding|${reading?.key}|${reading?.amount}"
        holdingRod = holding
        bait = reading
        if (signature != lastSignature) {
            lastSignature = signature
            diagnostics.event("UPDATE holding=$holding bait=${reading?.key ?: "none"} amount=${reading?.amount ?: 0}")
            warnings.onUpdate(holding, reading?.key)?.let { if (baitChangeWarning.value()) emit(it, "CHANGED") }
        }

        val present = player.fishing?.isRemoved == false
        if (present && !bobberPresent) {
            diagnostics.event("CAST holdingRod=$holding bait=${reading?.key ?: "none"}")
            warnings.onCast()?.let { if (noBaitWarning.value()) emit(it, "NO_BAIT") }
        }
        bobberPresent = present
        diagnostics.status {
            "enabled=true holdingRod=$holdingRod bait=${bait?.key ?: "none"} amount=${bait?.amount ?: 0} " +
                "bobber=$bobberPresent changeWarnings=$changeWarnings noBaitWarnings=$noBaitWarnings suppressedEvents=$it"
        }
    }

    private fun readBait(stack: ItemStack): Bait? {
        if (stack.isEmpty) return null
        val amount = FishingBaitRules.amount(SkyblockItems.loreLines(stack)) ?: return null
        val id = SkyblockItems.skyBlockId(stack).ifEmpty { geiler.addons.client.utility.SkyblockItems.realName(stack) }
        return Bait(stack.copyWithCount(1), stack.hoverName, id, amount)
    }

    private fun emit(warning: FishingBaitRules.Warning, kind: String) {
        if (warning.kind == FishingBaitRules.Kind.CHANGED) changeWarnings++ else noBaitWarnings++
        TitleAlertStack.submit("fishing_bait", warning.title, TITLE_TICKS, WHITE)
        PlayerAlerts.chat(warning.chat)
        ModuleSounds.play(
            if (warning.kind == FishingBaitRules.Kind.CHANGED) "minecraft:ui.button.click" else "minecraft:block.note_block.bass",
        )
        diagnostics.event("WARN $kind chat=${warning.chat.take(80)}")
    }

    private fun hasIcon(current: Bait?): Boolean = showIcon.value() && current != null

    private fun line(current: Bait?): Component =
        if (current == null) Component.literal(FishingBaitRules.NO_BAIT_TEXT)
        else {
            val text: MutableComponent = Component.literal(FishingBaitRules.amountPrefix(current.amount))
            text.append(current.name)
        }

    private fun sampleLine(): Component = Component.literal(FishingBaitRules.displayText("Whale Bait", 1234))

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, icon: ItemStack?, text: Component) {
        var textX = x
        if (icon != null) {
            graphics.item(icon, x, y)
            textX += ICON_WIDTH
        }
        val textY = y + (if (icon != null) (ICON_SIZE - font.lineHeight) / 2 else 0)
        graphics.text(font, text, textX, textY, WHITE)
    }

    private fun reset() {
        bait = null
        holdingRod = false
        bobberPresent = false
        lastSignature = ""
        warnings.reset()
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        private val spec = ModuleSettingsSpec.of("Fishing Bait")
        val showDisplay = spec.toggle("showDisplay")
        val showIcon = spec.toggle("showIcon")
        val baitChangeWarning = spec.toggle("baitChangeWarning")
        val noBaitWarning = spec.toggle("noBaitWarning")
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val ICON_SIZE = 16
        private const val ICON_WIDTH = 18
        private const val TITLE_TICKS = 40

        @JvmField
        val INSTANCE = FishingBaitModule(Settings())
    }
}
