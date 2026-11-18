package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.PartyFinderListing
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.IdentityHashMap
import java.util.Optional

/**
 * Party Finder listing helpers: the member count and floor on each listing's icon, the classes still open in its
 * tooltip, and coloured class levels. SkyHanni has the open-classes and level-colour features; the count and floor
 * badges are the forum idea. Listings are recognised by their `Members:` lore block on a player head.
 */
class PartyFinderListingsModule private constructor(settings: Settings) : Module(
    "Party Finder Listings",
    "Shows each Party Finder listing's member count and floor, open classes and coloured class levels.",
    Category.F7,
    false,
    settings.count, settings.floor, settings.openClasses, settings.levelColors,
) {
    private val count = settings.count
    private val floor = settings.floor
    private val openClasses = settings.openClasses
    private val levelColors = settings.levelColors

    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 400)
    private val readings = IdentityHashMap<ItemStack, PartyFinderListing.Reading?>()
    private var listings = 0
    private var badgesDrawn = 0L
    private var tooltipsExtended = 0L

    init {
        group(geiler.addons.client.module.SettingGroup("Listings", count, floor, openClasses, levelColors))
    }

    private fun settingsText(): String =
        "count=${count.value()};floor=${floor.value()};openClasses=${openClasses.value()};levelColors=${levelColors.value()}"

    override fun onEnable() {
        readings.clear()
        listings = 0
        badgesDrawn = 0
        tooltipsExtended = 0
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        readings.clear()
        listings = 0
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
        var found = 0
        var sample: PartyFinderListing.Reading? = null
        if (screen != null) {
            for (slot in screen.menu.slots) {
                val read = reading(slot.item) ?: continue
                found++
                if (sample == null) sample = read
            }
        }
        if (found != listings) {
            diagnostics.event(
                "LISTINGS title=${screen?.title?.string?.take(40)} count=$found sample=" +
                    (sample?.let { "floor=${it.floor?.displayName()};members=${it.count()};open=${it.openClasses().size}" } ?: "none"),
            )
        }
        listings = found
        diagnostics.statusOnChange {
            "listings=$listings badgesDrawn=$badgesDrawn tooltipsExtended=$tooltipsExtended settings{${settingsText()}}"
        }
    }

    private fun reading(stack: ItemStack): PartyFinderListing.Reading? {
        if (stack.isEmpty || stack.item !== Items.PLAYER_HEAD) return null
        return readings.getOrPut(stack) { PartyFinderListing.read(SkyblockItems.loreLines(stack)) }
    }

    /** The count (bottom right) and floor (top left) on a listing's head, after the item is drawn. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || listings == 0) return
        val read = reading(stack) ?: return
        val font = Minecraft.getInstance().font
        if (count.value()) {
            val text = "${read.count()}/${PartyFinderListing.MAX_MEMBERS}"
            val color = if (read.count() >= PartyFinderListing.MAX_MEMBERS) RED else GREEN
            graphics.text(font, text, x + 17 - font.width(text), y + 9, color, true)
        }
        val label = read.floor?.displayName()
        if (floor.value() && label != null) graphics.text(font, label, x, y, if (read.floor.master()) RED else AQUA, true)
        badgesDrawn++
    }

    /** Adds the open classes under the member rows and colours each row's level. */
    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || listings == 0) return
        val read = reading(stack) ?: return
        val header = PartyFinderListing.headerIndex(lines.map { it.string })
        if (header < 0) return
        val rows = PartyFinderListing.rows(lines.map { it.string }, header).size
        if (levelColors.value()) {
            for (i in header + 1..header + rows) {
                if (!lines[i].string.trim().startsWith("Empty", ignoreCase = true)) lines[i] = colorLevels(lines[i])
            }
        }
        val open = read.openClasses()
        if (openClasses.value() && open.isNotEmpty()) {
            lines.add(
                header + 1 + rows,
                Component.literal(" Open: ").withStyle { it.withColor(GRAY_RGB) }
                    .append(Component.literal(open.joinToString(", ") { it.displayName() }).withStyle { it.withColor(GREEN_RGB) }),
            )
        }
        tooltipsExtended++
        diagnostics.event("TOOLTIP floor=${read.floor?.displayName()} members=${read.count()} open=${open.size}")
    }

    /** Re-emits [line] segment by segment, colouring the digit runs after its first colon by class level. */
    private fun colorLevels(line: Component): Component {
        val out = Component.empty()
        var afterColon = false
        line.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                var rest = text
                if (!afterColon) {
                    val colon = rest.indexOf(':')
                    if (colon < 0) {
                        out.append(Component.literal(rest).withStyle(style))
                        return@StyledContentConsumer Optional.empty()
                    }
                    afterColon = true
                    out.append(Component.literal(rest.substring(0, colon + 1)).withStyle(style))
                    rest = rest.substring(colon + 1)
                }
                var last = 0
                for (m in DIGITS.findAll(rest)) {
                    val level = m.value.toIntOrNull() ?: continue
                    if (m.range.first > last) out.append(Component.literal(rest.substring(last, m.range.first)).withStyle(style))
                    out.append(Component.literal(m.value).withStyle(style.withColor(PartyFinderListing.levelColor(level) and 0xFFFFFF)))
                    last = m.range.last + 1
                }
                if (last < rest.length) out.append(Component.literal(rest.substring(last)).withStyle(style))
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out
    }

    private class Settings {
        val count = BooleanSetting("Member Count", true)
        val floor = BooleanSetting("Floor", true)
        val openClasses = BooleanSetting("Open Classes", true)
        val levelColors = BooleanSetting("Class Level Colors", true)
    }

    companion object {
        private val DIGITS = Regex("\\d+")
        private const val GREEN = 0xFF55FF55.toInt()
        private const val RED = 0xFFFF5555.toInt()
        private const val AQUA = 0xFF55FFFF.toInt()
        private const val GREEN_RGB = 0x55FF55
        private const val GRAY_RGB = 0xAAAAAA

        @JvmField
        val INSTANCE = PartyFinderListingsModule(Settings())

        init {
            ItemTooltipCallback.EVENT.register { stack, _, _, lines -> INSTANCE.onTooltip(stack, lines) }
        }
    }
}
