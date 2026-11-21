package geiler.addons.client.module.impl

import geiler.addons.client.farming.GardenPlotStore
import geiler.addons.client.farming.GardenPlotsRules
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.farming.TabWidgetText
import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * A Garden plots panel beside your inventory, after Skyblocker's: your plots as laid out in the desk's Configure Plots menu
 * (read each time you close that menu), a click teleports to the plot, plots with pests blink, and three buttons open the
 * desk, warp to the Garden spawn and set it. Drawn in the Click GUI or the vanilla look.
 */
class GardenPlotsOverlayModule private constructor() : Module(
    "Garden Plots",
    "Shows your Garden plots beside the inventory; click a plot to teleport to it.",
    Category.FARMING,
    false,
    style, closeOnClick,
) {
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 300)
    private var plots = arrayOfNulls<GardenPlotStore.Entry>(GardenPlotsRules.GRID * GardenPlotsRules.GRID)
    private var loadedFor: UUID? = null
    private var infested: Set<Int> = emptySet()
    private var ticks = 0L
    private var lastCommandMillis = 0L
    private var captures = 0L
    private var clicks = 0L
    private var drawn = 0L

    override fun onEnable() {
        loadedFor = null
        captures = 0
        clicks = 0
        drawn = 0
        diagnostics.enable("style=${style.value()};closeOnClick=${closeOnClick.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE captures=$captures clicks=$clicks drawn=$drawn")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null) {
            loadedFor = null
        } else if (loadedFor != player.uuid) {
            loadedFor = player.uuid
            plots = GardenPlotStore.load(player.uuid, player.connection.registryAccess())
            diagnostics.event("LOAD plots=${plots.count { it != null }}")
        }
        if (++ticks % INFESTED_TICKS == 0L && inGarden()) {
            infested = TabWidgetText.parse(PestWidgetReader.currentTabList()).map { ids -> ids.map { GardenPlotsRules.gridIndexOfPlot(it) }.toSet() }
                .orElse(infested)
        }
        diagnostics.statusOnChange { "inGarden=${inGarden()} plots=${plots.count { it != null }} infested=${infested.size} captures=$captures clicks=$clicks drawn=$drawn" }
    }

    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is ContainerScreen && screen.title.string.trim() == MENU_TITLE) {
                ScreenEvents.remove(screen).register { capture(screen) }
            } else if (screen is InventoryScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register { _, event -> !handleClick(screen, event.x(), event.y(), event.button()) }
            }
        }
    }

    private fun inGarden() = HypixelModApi.currentIsland() == Island.GARDEN

    private fun visible() = isEnabled() && inGarden() && Minecraft.getInstance().player != null

    /** Reads the plots from the Configure Plots menu as it closes; a slot that is empty or a pane leaves the saved plot alone. */
    private fun capture(screen: ContainerScreen) {
        if (!isEnabled()) return
        val player = Minecraft.getInstance().player ?: return
        val slots = screen.menu.slots
        val updated = plots.copyOf()
        var read = 0
        for (slotIndex in 0 until minOf(slots.size, GardenPlotsRules.GRID * 9)) {
            val index = GardenPlotsRules.menuIndex(slotIndex)
            if (index < 0) continue
            val stack = slots[slotIndex].item
            if (stack.isEmpty || BuiltInRegistries.ITEM.getKey(stack.item).path in NOT_PLOT_ITEMS) continue
            val name = GardenPlotsRules.plotName(stack.hoverName.string) ?: continue
            updated[index] = GardenPlotStore.Entry(name, stack.copyWithCount(1))
            read++
        }
        if (read == 0) {
            diagnostics.event("CAPTURE none (menu had no plots)")
            return
        }
        plots = updated
        captures++
        val saved = GardenPlotStore.save(player.uuid, updated, player.connection.registryAccess())
        diagnostics.event("CAPTURE read=$read total=${updated.count { it != null }} saved=$saved")
    }

    private fun item(path: String): ItemStack = ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path)) as Item)

    private fun stackAt(index: Int): ItemStack? =
        if (index == GardenPlotsRules.BARN) item("lodestone") else plots[index]?.icon

    /** Called after the player inventory screen draws; the panel needs the screen's own position, which the recipe book moves. */
    fun renderOnInventory(screen: InventoryScreen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!visible()) return
        val font = Minecraft.getInstance().font
        val mc = InventorySurfaces.isMinecraft(style.value())
        val (x, y) = origin(screen, graphics.guiWidth())
        InventorySurfaces.panel(graphics, x, y, GardenPlotsRules.WIDTH, GardenPlotsRules.HEIGHT, mc, highlight = false)
        graphics.text(font, "Garden Plots", x + 8, y + 6, InventorySurfaces.labelColor(mc), false)
        val blink = (System.currentTimeMillis() and 512L) != 0L
        val hoveredCell = GardenPlotsRules.cellAt(mouseX - x, mouseY - y)
        val hoveredButton = GardenPlotsRules.buttonAt(mouseX - x, mouseY - y)
        var tooltip: List<Component>? = null
        for (index in 0 until GardenPlotsRules.GRID * GardenPlotsRules.GRID) {
            val cx = x + GardenPlotsRules.GRID_X + (index % GardenPlotsRules.GRID) * GardenPlotsRules.CELL
            val cy = y + GardenPlotsRules.GRID_Y + (index / GardenPlotsRules.GRID) * GardenPlotsRules.CELL
            InventorySurfaces.slot(graphics, cx + 1, cy + 1, mc)
            val stack = stackAt(index)
            val bad = index in infested
            if (stack != null) graphics.item(stack, cx + 1, cy + 1)
            if (index == hoveredCell) graphics.fill(cx + 1, cy + 1, cx + 17, cy + 17, HOVER_FILL)
            if (bad && blink) graphics.outline(cx + 1, cy + 1, 16, 16, 0xFFFF5555.toInt())
            if (index == hoveredCell && stack != null) tooltip = plotTooltip(index, bad)
        }
        if (plots.all { it == null }) {
            graphics.text(font, "Close Configure", x + 8, y + 40, InventorySurfaces.labelColor(mc), false)
            graphics.text(font, "Plots in /desk", x + 8, y + 40 + font.lineHeight + 2, InventorySurfaces.labelColor(mc), false)
        }
        for (button in 0 until GardenPlotsRules.BUTTONS) {
            val bx = x + GardenPlotsRules.buttonX(button)
            val by = y + GardenPlotsRules.BUTTON_Y
            InventorySurfaces.card(graphics, bx, by, GardenPlotsRules.BUTTON, GardenPlotsRules.BUTTON, button == hoveredButton, mc)
            graphics.item(buttonItem(button), bx + 2, by + 2)
            if (button == hoveredButton) tooltip = listOf(Component.literal(BUTTON_LABELS[button]))
        }
        tooltip?.let { graphics.setComponentTooltipForNextFrame(font, it, mouseX, mouseY) }
        drawn++
    }

    private fun buttonItem(button: Int): ItemStack = item(BUTTON_ITEMS[button])

    private fun plotTooltip(index: Int, bad: Boolean): List<Component> {
        val title = if (index == GardenPlotsRules.BARN) "The Barn" else "Plot: ${plots[index]?.name}"
        val lines = ArrayList<Component>()
        lines.add(Component.literal(title).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
        if (bad) lines.add(Component.literal("GROSS! This plot has pests!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD))
        lines.add(Component.empty())
        lines.add(Component.literal("Click to TP to it!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
        return lines
    }

    private fun origin(screen: InventoryScreen, screenWidth: Int): Pair<Int, Int> {
        val access = screen as geiler.addons.client.access.AbstractContainerScreenAccess
        return GardenPlotsRules.originX(access.`geileraddons$getLeftPos`(), access.`geileraddons$getImageWidth`(), screenWidth) to access.`geileraddons$getTopPos`()
    }

    /** True when the click landed on the panel and was used. */
    private fun handleClick(screen: InventoryScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!visible()) return false
        val client = Minecraft.getInstance()
        val (x, y) = origin(screen, client.window.guiScaledWidth)
        val dx = mouseX.toInt() - x
        val dy = mouseY.toInt() - y
        if (dx < 0 || dy < 0 || dx >= GardenPlotsRules.WIDTH || dy >= GardenPlotsRules.HEIGHT) return false
        if (button != 0) return true
        val key = GardenPlotsRules.buttonAt(dx, dy)
        val cell = GardenPlotsRules.cellAt(dx, dy)
        val command = when {
            key >= 0 -> GardenPlotsRules.buttonCommand(key)
            cell == GardenPlotsRules.BARN -> GardenPlotsRules.command(cell, "")
            cell >= 0 -> plots[cell]?.let { GardenPlotsRules.command(cell, it.name) }
            else -> null
        } ?: return true
        val now = System.currentTimeMillis()
        if (now - lastCommandMillis < COMMAND_GAP_MILLIS) return true
        lastCommandMillis = now
        clicks++
        diagnostics.event("CLICK command=$command")
        PlayerAlerts.command(command)
        if (key < 0 && closeOnClick.value()) client.gui.setScreen(null)
        return true
    }

    companion object {
        private const val MENU_TITLE = "Configure Plots"
        private const val INFESTED_TICKS = 60L
        private const val COMMAND_GAP_MILLIS = 500L
        private const val HOVER_FILL = 0x80FFFFFF.toInt()
        private val NOT_PLOT_ITEMS = setOf("red_stained_glass_pane", "black_stained_glass_pane", "oak_button")
        private val BUTTON_ITEMS = listOf("book", "ender_eye", "red_bed")
        private val BUTTON_LABELS = listOf("Open Desk", "TP to Garden spawn", "Set Garden spawn")
        private val style = InventorySurfaces.styleSetting()
        private val closeOnClick = BooleanSetting("Close Screen On Click", true)

        @JvmField
        val INSTANCE = GardenPlotsOverlayModule()
    }
}
