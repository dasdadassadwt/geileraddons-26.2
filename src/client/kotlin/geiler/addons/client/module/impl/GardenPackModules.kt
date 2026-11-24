package geiler.addons.client.module.impl

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.farming.GardenBazaarRules
import geiler.addons.client.farming.GardenCurrentPlotParser
import geiler.addons.client.farming.GardenKeybindRules
import geiler.addons.client.farming.GreenhouseRules
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.farming.VisitorRules
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.module.TextSetting
import geiler.addons.client.slayer.SlayerSidebar
import geiler.addons.client.termsim.TermSimScreen
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

private fun gardenIsland(): Boolean = HypixelModApi.currentIsland() == Island.GARDEN

private fun loreOf(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

/** The open container screen, never the terminal simulator, which must not receive real clicks. */
private fun containerScreen(): AbstractContainerScreen<*>? =
    (Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>)?.takeIf { it !is TermSimScreen }

/**
 * Remaps the movement, jump, sneak, attack and use keys while you farm, after SkyHanni's Garden custom keybinds: set a key for
 * a control and only that key drives it. Active in the Garden with a farming tool in hand and no screen open, and not in the
 * barn or greenhouse. A control left empty keeps its normal key.
 */
class GardenKeybindsModule private constructor() : Module(
    "Garden Keybinds",
    "Remaps movement, jump, sneak, attack and use to other keys while farming in the Garden.",
    Category.FARMING,
    false,
    *spec.settings.toTypedArray(),
) {
    private val keys: Map<String, TextSetting> = mapOf(
        "key.attack" to spec.text("attack"), "key.use" to spec.text("useItem"), "key.left" to spec.text("left"),
        "key.right" to spec.text("right"), "key.forward" to spec.text("forward"), "key.back" to spec.text("back"),
        "key.jump" to spec.text("jump"), "key.sneak" to spec.text("sneak"),
    )
    private val excludeBarn = spec.toggle("excludeBarn")
    private val requireTool = spec.toggle("requireTool")
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 300)
    private var active = false
    private var plot: String? = null
    private var ticks = 0
    private var overrides = 0L
    private var activations = 0
    private val parsed = HashMap<String, InputConstants.Key?>()
    private val wasHeld = HashMap<String, Boolean>()
    private val noted = HashSet<String>()

    override fun onEnable() {
        overrides = 0L
        activations = 0
        diagnostics.enable("excludeBarn=${excludeBarn.value()} requireTool=${requireTool.value()} remapped=${keys.values.count { it.value().isNotBlank() }}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE activations=$activations overrides=$overrides")
        diagnostics.disable()
        setActive(false)
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        if (level == null || player == null || !gardenIsland()) {
            setActive(false)
        } else {
            if (ticks++ % PLOT_TICKS == 0) plot = GardenCurrentPlotParser.parse(SlayerSidebar.read(level.scoreboard)).orElse(null)
            val tool = !requireTool.value() || GardenKeybindRules.isFarmingTool(skyblockId(player.mainHandItem))
            setActive(client.gui.screen() == null && tool && !(excludeBarn.value() && GardenKeybindRules.isExcludedPlot(plot)))
        }
        diagnostics.status { "enabled=true active=$active plot=$plot overrides=$overrides activations=$activations suppressedEvents=$it" }
    }

    private fun setActive(now: Boolean) {
        if (now == active) return
        active = now
        wasHeld.clear()
        noted.clear()
        if (now) activations++
        diagnostics.event(if (now) "ACTIVE plot=$plot" else "INACTIVE")
        KeyMapping.releaseAll()
    }

    private fun skyblockId(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.ifEmpty { null }

    private fun keyFor(mapping: String): InputConstants.Key? {
        val setting = keys[mapping]?.value()?.let { ContainerKeybindRules.normalize(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return parsed.getOrPut(setting) {
            try {
                InputConstants.getKey(setting).takeIf { it != InputConstants.UNKNOWN }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private fun held(key: InputConstants.Key): Boolean {
        val handle = Minecraft.getInstance().window.handle()
        return if (key.type == InputConstants.Type.MOUSE) GLFW.glfwGetMouseButton(handle, key.value) == GLFW.GLFW_PRESS
        else GLFW.glfwGetKey(handle, key.value) == GLFW.GLFW_PRESS
    }

    /** True when [physical], the mapping's own key, is now another control's key and so must do nothing. */
    private fun suppressed(mapping: String, physical: String?): Boolean =
        physical != null && keys.keys.any { it != mapping && keyFor(it)?.name == physical }

    /** Records the first use of each mapped control per activation: which key replaced which, so a missed remap shows in the log. */
    private fun note(kind: String, mapping: String, mapped: String?, physical: String?) {
        if (noted.add("$kind $mapping")) diagnostics.event("$kind control=$mapping mappedKey=${mapped ?: "none"} defaultKey=${physical ?: "?"}")
    }

    /** Replaces a control's held state: null leaves it alone. */
    @JvmOverloads
    fun override(mapping: String, physical: String? = null): Boolean? {
        if (!active || !GardenKeybindRules.isControl(mapping)) return null
        val key = keyFor(mapping)
        if (key == null) {
            val blocked = suppressed(mapping, physical)
            if (blocked) note("SUPPRESS", mapping, null, physical)
            return if (blocked) false else null
        }
        overrides++
        note("OVERRIDE", mapping, key.name, physical)
        return held(key)
    }

    /** Replaces a control's click: a rise of the configured key; null leaves it alone. */
    @JvmOverloads
    fun overrideClick(mapping: String, physical: String? = null): Boolean? {
        if (!active || !GardenKeybindRules.isControl(mapping)) return null
        val key = keyFor(mapping)
        if (key == null) return if (suppressed(mapping, physical)) false else null
        note("OVERRIDE_CLICK", mapping, key.name, physical)
        val now = held(key)
        val rise = GardenKeybindRules.edge(wasHeld[mapping] == true, now)
        wasHeld[mapping] = now
        return rise
    }

    companion object {
        private const val PLOT_TICKS = 20
        private val spec = ModuleSettingsSpec.of("Garden Keybinds")

        @JvmField
        val INSTANCE = GardenKeybindsModule()
    }
}

/**
 * One key per Garden action (warp, set spawn, barn, desk and plots 1 to 24), like Command Keybinds. Works in the world view on
 * the Garden only; every key starts unbound. Plot keys run `/plottp <n>`, the plot numbers of the Configure Plots menu.
 */
class GardenCommandKeybindsModule private constructor() : Module(
    "Garden Command Keybinds",
    "Binds keys to Garden commands: warp to the Garden, set spawn, teleport to the barn, open the desk and teleport to a plot.",
    Category.FARMING,
    false,
    *spec.settings.toTypedArray(),
) {
    private val keys = ContainerKeybindRules.GARDEN_PRESETS.map { spec.text(it.id) }
    private var sent = 0L
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        sent = 0L
        diagnostics.enable("bound=${keys.count { it.value().isNotBlank() }}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE sent=$sent")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true inGarden=${gardenIsland()} sent=$sent suppressedEvents=$it" }
    }

    /** A key press with no screen open; true means a Garden command was sent and the press is used. */
    fun onKey(pressedKeyName: String): Boolean {
        if (!isEnabled() || !gardenIsland()) return false
        val command = ContainerKeybindRules.commandFor(
            pressedKeyName, keys.map { ContainerKeybindRules.normalize(it.value()) }, ContainerKeybindRules.GARDEN_PRESETS,
        ) ?: return false
        PlayerAlerts.command(command)
        sent++
        diagnostics.event("COMMAND key=$pressedKeyName command=$command")
        return true
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Garden Command Keybinds")

        @JvmField
        val INSTANCE = GardenCommandKeybindsModule()
    }
}

/**
 * Lists what the Garden visitors on your island want, from the Items Required of each visitor menu you open, as a themed card
 * after SkyHanni's visitor shopping list: a header with the visitor and ready counts and the total Instabuy cost in millions,
 * then each item still short with its icon, amount and cost. Sacks are not counted. Clicking an item to buy only runs the
 * Bazaar search and copies the missing amount (and boxes that item in green in the search result); it never clicks anything
 * in the menu for you.
 */
class VisitorShoppingListModule private constructor() : Module(
    "Visitor Shopping List",
    "Shows the items the Garden visitors want, what they cost on the Bazaar and which visitors you can serve, read from each visitor menu you open.",
    Category.FARMING,
    false,
    showIcons,
), HudElement {
    private val needs = LinkedHashMap<String, Map<String, Int>>()
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)
    private var ticks = 0
    private var rows: List<VisitorRules.Row> = emptyList()
    private var icons: Map<String, ItemStack> = emptyMap()
    private val unresolved = HashSet<String>()
    private var read = 0
    private var searches = 0
    private var searchedName: String? = null
    private var searchedAtMillis = 0L
    private var highlighted = 0L

    override fun id(): String = "visitor_shopping_list"
    override fun displayName(): String = "Visitor Shopping List"
    override fun hudIslands(): Set<Island> = setOf(Island.GARDEN)
    override fun settingTooltip(setting: Setting): String? =
        if (setting === showIcons) "Draws the item next to each entry you still have to buy." else null
    private fun shown(): List<VisitorRules.Row> = rows.ifEmpty { SAMPLE }
    private fun rowHeight(font: Font): Int = if (showIcons.value()) maxOf(font.lineHeight + 1, ICON_ROW) else font.lineHeight + 1
    private fun indent(row: VisitorRules.Row): Int = if (row.buyItem == null) 0 else if (showIcons.value()) ICON_ROW else 6
    override fun width(font: Font?): Int = maxOf(
        MIN_WIDTH,
        shown().maxOf { indent(it) + font!!.width(it.text) + (it.costText?.let { cost -> COST_GAP + font.width(cost) } ?: 0) } + 2 * PAD,
    )
    override fun height(font: Font?): Int = shown().size * rowHeight(font!!) + 2 * PAD
    override fun visible(): Boolean = isEnabled() && rows.isNotEmpty() && gardenIsland()
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val width = width(font)
        val rowHeight = rowHeight(font)
        GuiTheme.roundedRectBordered(graphics, x, y, width, height(font), GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        shown().forEachIndexed { index, row ->
            val rowY = y + PAD + index * rowHeight
            val textY = rowY + (rowHeight - font.lineHeight) / 2
            val icon = if (showIcons.value()) row.buyItem?.let { icons[it] ?: SAMPLE_ICONS[it] } else null
            if (icon != null) graphics.item(icon, x + PAD, rowY + (rowHeight - 16) / 2)
            graphics.text(font, Component.literal(row.text), x + PAD + indent(row), textY, GuiTheme.TEXT_PRIMARY)
            row.costText?.let { graphics.text(font, Component.literal(it), x + width - PAD - font.width(it), textY, GuiTheme.TEXT_PRIMARY) }
        }
    }

    /** The editor draws the sample card at the size the live one would take, not just its title. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = render(graphics, font, x, y)

    override fun onEnable() {
        needs.clear()
        rows = emptyList()
        icons = emptyMap()
        unresolved.clear()
        searchedName = null
        read = 0
        // Prices come from the Bazaar data set, which loads on demand; icons are built from the item name.
        PriceDisplayModule.INSTANCE.setBazaarDemand(true)
        diagnostics.enable("source=visitor menu lore (every tick while a menu is open) and tab list visitors, rebuilt every 10 ticks; icons=${showIcons.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE menusRead=$read searches=$searches highlights=$highlighted")
        diagnostics.disable()
        PriceDisplayModule.INSTANCE.setBazaarDemand(false)
        needs.clear()
        rows = emptyList()
        icons = emptyMap()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (!gardenIsland()) {
            if (needs.isNotEmpty()) needs.clear()
            rows = emptyList()
        } else {
            // Read on every tick a menu is open: a menu closed within a few ticks of opening must still be recorded.
            val changed = readMenu()
            if (++ticks % REBUILD_TICKS == 0) dropLeft()
            if (changed || ticks % REBUILD_TICKS == 0) rebuild()
        }
        diagnostics.status { "enabled=true inGarden=${gardenIsland()} visitors=${needs.size} rows=${rows.size} menusRead=$read searches=$searches suppressedEvents=$it" }
    }

    /** Hooks left clicks on the list in any open screen (chat, inventory, a sign); the list is not clickable with no screen open. */
    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            // The mod's own screens (Click GUI, Move UI, editors) draw over the list; a click there is never a click on it.
            if (screen.javaClass.name.startsWith(OWN_SCREENS)) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { _, event -> !handleClick(event.x(), event.y(), event.button()) }
        }
    }

    /** True when the click landed on an item row and was used. */
    private fun handleClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0 || !visible()) return false
        val client = Minecraft.getInstance()
        val font = client.font
        val window = client.window
        val scale = HudManager.scale(this)
        val row = GardenBazaarRules.lineAt(
            mouseX, mouseY, HudManager.x(this, font, window.guiScaledWidth), HudManager.y(this, font, window.guiScaledHeight) + (PAD * scale).toInt(),
            width(font), rowHeight(font) - 1, scale, rows.size,
        )
        val item = rows.getOrNull(row)?.takeIf { it.buyItem != null } ?: return false
        search(item.buyItem!!, item.buyAmount)
        return true
    }

    /** Puts the amount on the clipboard (for Ctrl+V in the Custom Amount sign) and searches the Bazaar for the item. */
    private fun search(name: String, amount: Int) {
        val client = Minecraft.getInstance()
        client.keyboardHandler.setClipboard(amount.toString())
        searches++
        searchedName = name
        searchedAtMillis = System.currentTimeMillis()
        if (client.gui.screen() is AbstractSignEditScreen) {
            diagnostics.event("COPY item=$name amount=$amount (sign open)")
            PlayerAlerts.chat("§7Copied §e$amount §7for §f$name§7: press Ctrl+V in the sign")
            return
        }
        diagnostics.event("SEARCH item=$name amount=$amount")
        PlayerAlerts.command(GardenBazaarRules.command(name))
        PlayerAlerts.chat("§7Bazaar: §f$name§7. §e$amount §7is copied: choose Buy, Custom Amount, then Ctrl+V")
    }

    /** True when a visitor's wants were recorded or changed this tick. */
    private fun readMenu(): Boolean {
        val slots = containerScreen()?.menu?.slots ?: return false
        val info = slots.getOrNull(INFO_SLOT)?.item ?: return false
        val accept = slots.getOrNull(ACCEPT_SLOT)?.item ?: return false
        if (info.isEmpty || accept.isEmpty || !VisitorRules.isVisitorInfo(loreOf(info)) || accept.hoverName.string != "Accept Offer") return false
        val name = info.hoverName.string.replace(Regex("§."), "").trim()
        val items = VisitorRules.parseRequired(loreOf(accept))
        if (needs[name] == items) return false
        if (needs.size < MAX_VISITORS || name in needs) needs[name] = items
        read++
        diagnostics.event("VISITOR $name items=${items.size}")
        return true
    }

    /** Visitors that left the tab list are no longer waiting; an unreadable widget keeps everything. */
    private fun dropLeft() {
        val names = VisitorRules.visitorsInTab(PestWidgetReader.currentTabList()) ?: return
        if (needs.keys.retainAll(names.toSet())) diagnostics.event("VISITORS left now=${needs.size}")
    }

    private fun rebuild() {
        val player = Minecraft.getInstance().player ?: return
        val held = (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.filter { !it.isEmpty }
            .map { VisitorRules.Held(geiler.addons.client.utility.SkyblockItems.realName(it).replace(Regex("§."), "").trim(), it.count) }
        val bazaar = PriceDisplayModule.INSTANCE.prices().bazaar
        rows = VisitorRules.rows(needs, { VisitorRules.have(held, it) }, { name -> bazaar?.get(VisitorRules.bazaarId(name))?.buyPrice })
        icons = rows.mapNotNull { row ->
            val item = row.buyItem ?: return@mapNotNull null
            item to (cropIcon(item) ?: return@mapNotNull null)
        }.toMap()
        for (row in rows) {
            val item = row.buyItem ?: continue
            val id = VisitorRules.bazaarId(item)
            val priced = bazaar?.get(id)?.buyPrice != null
            if ((!priced && bazaar != null || cropIcon(item) == null) && unresolved.size < MAX_UNRESOLVED && unresolved.add(item)) {
                diagnostics.event("UNRESOLVED item=$item id=$id bazaar=${bazaar != null} priced=$priced icon=${cropIcon(item) != null}")
            }
        }
    }

    /** The plain crop item with the enchanted shimmer, built from the Bazaar id (`ENCHANTED_MELON` is a melon slice); null for an unknown crop. */
    private fun cropIcon(name: String): ItemStack? {
        val item = CROP_ITEMS[VisitorRules.baseId(VisitorRules.bazaarId(name))] ?: return null
        return ItemStack(item).also { it.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) }
    }

    /** In a Bazaar search result, boxes in green the slot of the item a shopping-list click searched for; called before the slots draw. */
    fun drawSlotHighlights(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        val wanted = searchedName ?: return
        if (!isEnabled() || System.currentTimeMillis() - searchedAtMillis > SEARCH_MEMORY_MILLIS) return
        val screen = containerScreen() ?: return
        val title = screen.title.string
        if (!title.contains("Bazaar", ignoreCase = true) && !title.contains('➜')) return
        for (slot in slots) {
            if (slot.container is Inventory || !slot.hasItem()) continue
            if (!slot.item.hoverName.string.replace(FORMATTING, "").trim().equals(wanted, ignoreCase = true)) continue
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, MATCH_FILL)
            graphics.outline(slot.x - 1, slot.y - 1, 18, 18, MATCH_OUTLINE)
            if (highlighted++ == 0L) diagnostics.event("BAZAAR_MATCH item=$wanted title=${title.take(40)}")
        }
    }

    companion object {
        private const val REBUILD_TICKS = 10
        private const val INFO_SLOT = 13
        private const val ACCEPT_SLOT = 29
        private const val MAX_VISITORS = 12
        private const val PAD = 4
        private const val MIN_WIDTH = 110
        private const val ICON_ROW = 18
        private const val COST_GAP = 10
        private const val MAX_UNRESOLVED = 8
        private const val SEARCH_MEMORY_MILLIS = 120_000L
        private const val OWN_SCREENS = "geiler.addons.client.gui."
        private const val MATCH_FILL = 0x4055FF55
        private const val MATCH_OUTLINE = 0xFF55FF55.toInt()
        private val FORMATTING = Regex("§.")
        private val CROP_ITEMS = mapOf(
            "MELON" to Items.MELON_SLICE, "MELON_BLOCK" to Items.MELON, "PUMPKIN" to Items.PUMPKIN, "SUGAR_CANE" to Items.SUGAR_CANE, "SUGAR" to Items.SUGAR,
            "CACTUS" to Items.CACTUS, "CACTUS_GREEN" to Items.DYE.green(), "CARROT" to Items.CARROT, "GOLDEN_CARROT" to Items.GOLDEN_CARROT,
            "POTATO" to Items.POTATO, "BAKED_POTATO" to Items.BAKED_POTATO, "WHEAT" to Items.WHEAT, "SEEDS" to Items.WHEAT_SEEDS, "HAY_BLOCK" to Items.HAY_BLOCK,
            "BREAD" to Items.BREAD, "COOKIE" to Items.COOKIE, "COCOA" to Items.COCOA_BEANS, "RED_MUSHROOM" to Items.RED_MUSHROOM, "BROWN_MUSHROOM" to Items.BROWN_MUSHROOM,
            "HUGE_MUSHROOM_1" to Items.BROWN_MUSHROOM_BLOCK, "HUGE_MUSHROOM_2" to Items.RED_MUSHROOM_BLOCK, "NETHER_STALK" to Items.NETHER_WART,
            "SUNFLOWER" to Items.SUNFLOWER, "MOONFLOWER" to Items.BLUE_ORCHID, "WILD_ROSE" to Items.ROSE_BUSH,
            "RAW_CHICKEN" to Items.CHICKEN, "PORK" to Items.PORKCHOP, "GRILLED_PORK" to Items.COOKED_PORKCHOP, "MUTTON" to Items.MUTTON, "COOKED_MUTTON" to Items.COOKED_MUTTON,
            "RAW_BEEF" to Items.BEEF, "RABBIT" to Items.RABBIT, "COOKED_RABBIT" to Items.COOKED_RABBIT, "RAW_FISH" to Items.COD, "RAW_SALMON" to Items.SALMON, "COOKED_FISH" to Items.COOKED_COD,
        )
        private val showIcons = BooleanSetting("Show Icons", true)
        private val SAMPLE = listOf(
            VisitorRules.Row("§7Visitor Shopping List §e2 §8(§a1 ready§8)", costText = "§7Total §61.25M"),
            VisitorRules.Row("§fEnchanted Baked Potato §e10", "Enchanted Baked Potato", 10, "§61.25M"),
        )
        private val SAMPLE_ICONS: Map<String, ItemStack> by lazy { mapOf("Enchanted Baked Potato" to ItemStack(Items.BAKED_POTATO)) }

        @JvmField
        val INSTANCE = VisitorShoppingListModule()
    }
}

/**
 * In the Greenhouse `Crop Diagnostics` menu: colours the status slot (green harvestable, yellow drops, red otherwise) and
 * the water slot when watered, and keeps a countdown to the next growth stage with a notice when it is ready, after
 * SkyHanni's greenhouse helpers.
 */
class GreenhouseHelperModule private constructor() : Module(
    "Greenhouse Helper",
    "Highlights harvestable and watered crops in Crop Diagnostics and counts down to the next growth stage.",
    Category.FARMING,
    false,
    *spec.settings.toTypedArray(),
), HudElement {
    private val highlightHarvest = spec.toggle("highlightHarvestable")
    private val highlightWater = spec.toggle("highlightWater")
    private val growthTimer = spec.toggle("growthTimer")
    private val diagnostics = ModuleDiagnostics(Category.FARMING, { name() }, 8, 100L, 240)
    private var deadlineNanos = 0L
    private var notified = true
    private var drawn = 0L

    override fun id(): String = "greenhouse_growth"
    override fun displayName(): String = "Greenhouse Growth"
    override fun hudIslands(): Set<Island> = setOf(Island.GARDEN)
    private fun text(): String = "§6Next Greenhouse Growth Stage: " + GreenhouseRules.timerText(
        if (deadlineNanos == 0L) SAMPLE_SECONDS else (deadlineNanos - System.nanoTime()) / NANOS_PER_SECOND,
    )
    override fun width(font: Font?): Int = font!!.width(text())
    override fun height(font: Font?): Int = font!!.lineHeight
    override fun visible(): Boolean = isEnabled() && growthTimer.value() && deadlineNanos != 0L && gardenIsland()
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    /** The editor shows the sample timer at the size the live one takes, not just the title. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = render(graphics, font, x, y)

    override fun onEnable() {
        deadlineNanos = 0L
        notified = true
        drawn = 0L
        diagnostics.enable("highlightHarvestable=${highlightHarvest.value()} highlightWater=${highlightWater.value()} timer=${growthTimer.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE highlightsDrawn=$drawn")
        diagnostics.disable()
        deadlineNanos = 0L
    }

    private fun inDiagnostics(): AbstractContainerScreen<*>? =
        containerScreen()?.takeIf { gardenIsland() && GreenhouseRules.isDiagnosticsTitle(it.title.string) }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val screen = inDiagnostics()
        if (screen != null) {
            val stack = screen.menu.slots.getOrNull(GreenhouseRules.TIMER_SLOT)?.item
            val seconds = stack?.takeIf { !it.isEmpty }?.let { GreenhouseRules.nextStageSeconds(loreOf(it)) }
            if (seconds != null) {
                val deadline = System.nanoTime() + seconds * NANOS_PER_SECOND
                if (deadlineNanos == 0L || kotlin.math.abs(deadline - deadlineNanos) > NANOS_PER_SECOND * 5) diagnostics.event("TIMER seconds=$seconds")
                deadlineNanos = deadline
                notified = false
            }
        }
        if (deadlineNanos != 0L && growthTimer.value() && !notified && System.nanoTime() >= deadlineNanos) {
            notified = true
            diagnostics.event("READY")
            PlayerAlerts.alert("§aGreenhouse growth stage is ready")
        }
        if (deadlineNanos != 0L && System.nanoTime() - deadlineNanos > MAX_OVERDUE_NANOS) deadlineNanos = 0L
        diagnostics.status { "enabled=true inDiagnostics=${screen != null} timerSet=${deadlineNanos != 0L} highlightsDrawn=$drawn suppressedEvents=$it" }
    }

    /** Fills the status and water slots behind their items; called before the slots draw. */
    fun drawSlotHighlights(graphics: GuiGraphicsExtractor, slots: List<Slot>) {
        if (!isEnabled() || inDiagnostics() == null) return
        if (highlightHarvest.value()) {
            val stack = slots.getOrNull(GreenhouseRules.STATUS_SLOT)?.takeIf { it.hasItem() }
            if (stack != null && BuiltInRegistries.ITEM.getKey(stack.item.item).toString() == "minecraft:beacon") {
                fill(graphics, stack, GreenhouseRules.harvestColor(loreOf(stack.item)))
            }
        }
        if (highlightWater.value()) {
            val slot = slots.getOrNull(GreenhouseRules.WATER_SLOT)?.takeIf { it.hasItem() }
            if (slot != null && BuiltInRegistries.ITEM.getKey(slot.item.item).toString() == "minecraft:water_bucket" && GreenhouseRules.waterOk(loreOf(slot.item))) {
                fill(graphics, slot, GreenhouseRules.GREEN)
            }
        }
    }

    private fun fill(graphics: GuiGraphicsExtractor, slot: Slot, color: Int) {
        drawn++
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MAX_OVERDUE_NANOS = 60L * 60L * NANOS_PER_SECOND
        private const val SAMPLE_SECONDS = 6020L
        private val spec = ModuleSettingsSpec.of("Greenhouse Helper")

        @JvmField
        val INSTANCE = GreenhouseHelperModule()
    }
}
