package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.ChestRollRules
import geiler.addons.client.dungeon.f7.CroesusPrices
import geiler.addons.client.dungeon.f7.CroesusRules
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Croesus: highlights and hides runs in the Croesus menu, prices every chest of a run (profit, best two chests marked) and
 * the chest being opened, and counts unclaimed chests. After Odin's Croesus; prices come from Odin's 7-day average file
 * or, by choice, from the live Bazaar and lowest BIN data Price Display downloads.
 */
class CroesusModule private constructor() : Module(
    "Croesus",
    "Profit and highlights for the Croesus menu and dungeon chests.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
    refreshPrices,
), HudElement {

    private val highlightState = spec.toggle("highlightState")
    private val highlightProfitable = spec.toggle("highlightProfitable")
    private val hideClaimed = spec.toggle("hideClaimed")
    private val includeKey = spec.toggle("includeKey")
    private val minimized = spec.toggle("minimized")
    private val includeEssence = spec.toggle("includeEssence")
    private val chestWarning = spec.slider("chestWarning")
    private val keyRule = spec.toggle("keyRule")
    private val priceSource = spec.choice("priceSource")

    private var trackedScreen: AbstractContainerScreen<*>? = null
    private var kind = "none"
    private var scannedStateId = -1
    private var tint: Map<Int, Int> = emptyMap()
    private var rows: List<CroesusRules.Row> = emptyList()
    private var chestCount = 0
    private var livePrices: Map<String, Double> = emptyMap()
    private var liveKey: List<Any?> = emptyList()
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 320)

    init {
        group(*spec.groups.toTypedArray())
    }

    // ---- profit list element ----

    /** A sample the size of a real preview, so placing it in Move Elements matches the live list. */
    private val sampleRows = CroesusRules.rows(
        listOf(
            CroesusRules.ChestResult(
                "Bedrock",
                listOf(CroesusRules.ChestItem("Enchanted Book (One For All V)", 85_000_000.0), CroesusRules.ChestItem("Wither Catalyst", 12_000_000.0), CroesusRules.ChestItem("Necron's Handle", 550_000_000.0)),
                632_000_000.0, 0,
            ),
            CroesusRules.ChestResult(
                "Obsidian",
                listOf(CroesusRules.ChestItem("Enchanted Book (Legion V)", 45_000_000.0), CroesusRules.ChestItem("Wither Blood", 8_500_000.0)),
                46_500_000.0, 1,
            ),
            CroesusRules.ChestResult("Diamond", listOf(CroesusRules.ChestItem("Wither Essence x10", 2_000_000.0)), -4_433.0, 2),
        ),
        false,
    )

    override fun id(): String = "croesus_chests"
    override fun module(): Module = this
    override fun displayName(): String = "Croesus Chest HUD"
    override fun hudIslands(): Set<Island> = emptySet()
    override fun visible(): Boolean = isEnabled() && rows.isNotEmpty()

    /** Drawn by the slot mixin above the open chest screen, never on the HUD layer below it. */
    override fun renderOnHud(): Boolean = false

    override fun previewMode(): String = if (rows.isEmpty()) "sample" else "live"
    override fun width(font: Font?): Int = rowsWidth(font!!, rows.ifEmpty { sampleRows })
    override fun height(font: Font?): Int = maxOf(1, CroesusRules.rowsHeight(rows.ifEmpty { sampleRows }))

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = drawRows(graphics, font, x, y, rows)

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        drawRows(graphics, font, x, y, rows.ifEmpty { sampleRows })

    /** The profit column starts where Odin's does, after the widest tier name. */
    private fun nameWidth(font: Font): Int = font.width("Diamond ")

    private fun rowsWidth(font: Font, list: List<CroesusRules.Row>): Int =
        list.maxOfOrNull { (if (it.chest != null) nameWidth(font) else 0) + font.width(it.text) } ?: 1

    private fun drawRows(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, list: List<CroesusRules.Row>) {
        for (row in list) {
            val chest = row.chest
            if (chest != null) graphics.text(font, chest.display ?: Component.literal(chest.name), x, y + row.y, WHITE)
            graphics.text(font, Component.literal(row.text), x + if (chest != null) nameWidth(font) else 0, y + row.y, WHITE)
        }
    }

    /** Called by the slot mixin at tooltip time: brings the list up to date and draws it over the screen. */
    fun drawOverScreen(graphics: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>) {
        if (!isEnabled()) return
        scanIfChanged(screen)
        if (kind != "preview" && kind != "opened") return
        // The profit list names the loot, so it waits until the Chest Roll animation has finished on this screen.
        if (rows.isEmpty() || ChestRollModule.INSTANCE.hidesResult(screen)) return
        val font = Minecraft.getInstance().font
        graphics.nextStratum()
        HudManager.renderElement(
            this, graphics, font,
            HudManager.x(this, font, graphics.guiWidth()), HudManager.y(this, font, graphics.guiHeight()), false,
        )
    }

    private fun publishFitted(chests: List<CroesusRules.ChestResult>) {
        // A list taller than the screen cannot be moved in Move Elements.
        val allowed = ((Minecraft.getInstance().window.guiScaledHeight * 0.95f) / HudManager.scale(this)).toInt()
        rows = CroesusRules.fitted(chests, minimized.value(), allowed)
    }

    // ---- prices ----

    private fun liveSource(): Boolean = priceSource.value() != "AVERAGE"

    /** The prices of the chosen source; Chest Roll reads the same ones. */
    fun effectivePrices(): Map<String, Double> {
        val base = CroesusPrices.prices
        if (!isEnabled() || !liveSource()) return base
        val data = PriceDisplayModule.INSTANCE.prices()
        val key = listOf(base, data, priceSource.value())
        // Identity comparison: every download publishes a new map or snapshot.
        if (key.size != liveKey.size || key.indices.any { key[it] !== liveKey[it] }) {
            livePrices = CroesusPrices.live(base, data.lowestBin, data.bazaar, priceSource.value() == "SELL_OFFER")
            liveKey = key
        }
        return livePrices
    }

    private fun keyPrice(): Double =
        (if (liveSource()) effectivePrices()[CroesusPrices.KEY_ID] else null) ?: CroesusPrices.keyPrice

    override fun onEnable() {
        diagnostics.enable("hide=${hideClaimed.value()} state=${highlightState.value()} profitable=${highlightProfitable.value()} keyRule=${keyRule.value()} essence=${includeEssence.value()} source=${priceSource.value()} warning=${chestWarning.intValue()}")
        if (CroesusPrices.prices.isEmpty()) fetchPrices()
    }

    override fun onDisable() {
        PriceDisplayModule.INSTANCE.setCroesusDemand(false)
        clearScreenState()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    private fun fetchPrices() {
        CroesusPrices.refresh { status ->
            diagnostics.event("PRICES $status")
        }
    }

    /** Fetches the price list again and reports the result in chat. */
    fun refreshNow() {
        CroesusPrices.refresh { status ->
            Minecraft.getInstance().execute { PlayerAlerts.chat("§6[GeilerAddons] §aCroesus prices: $status") }
            diagnostics.event("PRICES $status")
        }
    }

    // ---- screens ----

    private var lastHidden = 0

    private fun clearScreenState() {
        lastHidden = 0
        trackedScreen = null
        kind = "none"
        scannedStateId = -1
        tint = emptyMap()
        rows = emptyList()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        PriceDisplayModule.INSTANCE.setCroesusDemand(liveSource())
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        if (screen == null) {
            if (trackedScreen != null) clearScreenState()
        } else {
            scan(screen)
        }
        diagnostics.status {
            "enabled=true screen=$kind prices=${CroesusPrices.status}(${CroesusPrices.prices.size}) source=${priceSource.value()} " +
                "liveBazaar=${PriceDisplayModule.INSTANCE.prices().bazaar?.size ?: 0} liveBin=${PriceDisplayModule.INSTANCE.prices().lowestBin?.size ?: 0} " +
                "rows=${rows.size} chests=$chestCount suppressedEvents=$it"
        }
    }

    /** Rescans in the same frame a slot packet lands, so nothing waits for the next tick. */
    private fun scanIfChanged(screen: AbstractContainerScreen<*>) {
        if (screen !== trackedScreen || screen.menu.stateId != scannedStateId) scan(screen)
    }

    private fun scan(screen: AbstractContainerScreen<*>) {
        if (screen !== trackedScreen) {
            clearScreenState()
            trackedScreen = screen
            val title = screen.title.string
            kind = when {
                CroesusRules.isCroesusMenu(title) -> "menu"
                CroesusRules.isChestPreview(title) -> "preview"
                CroesusRules.isOpenedChest(title) -> "opened"
                else -> "none"
            }
            if (kind != "none") {
                diagnostics.event("SCREEN $kind")
                CroesusPrices.refreshIfStale { diagnostics.event("PRICES $it") }
            }
        }
        scannedStateId = screen.menu.stateId
        when (kind) {
            "preview" -> scanPreview(screen)
            "opened" -> scanOpened(screen)
        }
    }

    private fun containerSlots(screen: AbstractContainerScreen<*>): List<Slot> {
        val all = screen.menu.slots
        return all.subList(0, (all.size - PLAYER_SLOTS).coerceAtLeast(0))
    }

    private fun lore(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

    private fun struck(stack: ItemStack, name: String): Boolean = croesusStruckModifier(stack, name)


    private fun scanPreview(screen: AbstractContainerScreen<*>) {
        val prices = effectivePrices()
        val chests = ArrayList<CroesusRules.ChestResult>()
        var hidden = 0
        for ((index, slot) in containerSlots(screen).withIndex()) {
            if (index > MAX_PREVIEW_SLOT) break
            val stack = slot.item
            if (stack.isEmpty || stack.item != Items.PLAYER_HEAD) continue
            val lore = lore(stack)
            // Chest Roll: an unrolled chest's value would spoil its roll, so it gets no tint and no profit row yet.
            val loot = ChestRollRules.lootLineRange(lore)?.let { lore.slice(it) }.orEmpty()
            if (!ChestRollModule.INSTANCE.revealedInPreview(stack.hoverName.string, loot)) {
                hidden++
                continue
            }
            CroesusRules.previewChest(index, stack.hoverName.string, lore, prices, includeEssence.value())
                ?.let { chests.add(it.withDisplay(stack.hoverName)) }
        }
        if (hidden != lastHidden) ChestRollModule.INSTANCE.notePreviewHidden(hidden, 0)
        lastHidden = hidden
        val keyPrice = if (keyRule.value()) keyPrice() else 0.0
        val top = if (highlightProfitable.value()) CroesusRules.topSlots(chests, keyPrice) else emptyList()
        diagnostics.statusOnChange {
            "PREVIEW chests=${chests.size} top=$top essence=${includeEssence.value()} source=${priceSource.value()} " +
                "keyPrice=${CroesusRules.money(keyPrice)} unpriced=${chests.sumOf { chest -> chest.items.count { it.price == 0.0 } }} " +
                "profits=${chests.joinToString(",") { CroesusRules.money(it.profit) }}"
        }
        val newTint = HashMap<Int, Int>()
        top.getOrNull(0)?.let { newTint[it] = CroesusRules.COLOR_BEST }
        top.getOrNull(1)?.let { newTint[it] = CroesusRules.COLOR_SECOND }
        tint = newTint
        publishFitted(CroesusRules.sorted(chests))
    }

    private fun scanOpened(screen: AbstractContainerScreen<*>) {
        val slots = ArrayList<CroesusRules.OpenedSlot>()
        for ((index, slot) in containerSlots(screen).withIndex()) {
            if (index > MAX_OPENED_SLOT) break
            val stack = slot.item
            slots.add(
                when {
                    stack.isEmpty -> CroesusRules.OpenedSlot.empty()
                    stack.item == Items.CHEST -> CroesusRules.OpenedSlot.chest(lore(stack))
                    stack.item == Items.ENCHANTED_BOOK -> bookSlot(stack)
                    else -> CroesusRules.OpenedSlot.item(stack.hoverName.string, skyblockId(stack))
                },
            )
        }
        publishFitted(listOf(CroesusRules.openedChest(slots, effectivePrices(), includeEssence.value())))
    }

    private fun skyblockId(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.ifEmpty { null }

    private fun bookSlot(stack: ItemStack): CroesusRules.OpenedSlot {
        val enchantments = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty("enchantments")
        val key = enchantments?.keySet()?.firstOrNull()
        if (enchantments == null || key == null) return CroesusRules.OpenedSlot.item(stack.hoverName.string, skyblockId(stack))
        return CroesusRules.OpenedSlot.book(stack.hoverName.string, key, enchantments.getIntOr(key, 0))
    }

    /**
     * Called by the slot mixin before a slot is drawn: tints it, outlines a used Kismet Feather and returns true to
     * hide it. A Croesus menu slot is judged from the item being drawn, so a page change is never stale.
     */
    fun onExtractSlot(graphics: GuiGraphicsExtractor, screen: AbstractContainerScreen<*>, slot: Slot): Boolean {
        if (!isEnabled()) return false
        scanIfChanged(screen)
        if (kind == "preview") {
            tint[slot.index]?.let { graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, it) }
            return false
        }
        if (kind != "menu") return false
        val stack = slot.item
        if (stack.isEmpty) return false
        val name = stack.hoverName.string
        if (name != "The Catacombs" && name != "Master Mode The Catacombs") return false
        val state = CroesusRules.menuState(lore(stack), hideClaimed.value(), includeKey.value(), struck(stack, "Dungeon Chest Key"), highlightState.value())
        if (state.hide) return true
        if (state.color != 0) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, state.color)
        if (struck(stack, "Kismet Feather")) {
            val c = CroesusRules.COLOR_KISMET
            graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 1, c)
            graphics.fill(slot.x, slot.y + 15, slot.x + 16, slot.y + 16, c)
            graphics.fill(slot.x, slot.y + 1, slot.x + 1, slot.y + 15, c)
            graphics.fill(slot.x + 15, slot.y + 1, slot.x + 16, slot.y + 15, c)
        }
        return false
    }

    // ---- chest count ----

    /** Tab-list updates carry "Unclaimed chests: N". */
    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket) {
        if (!isEnabled()) return
        val relevant = packet.actions().any {
            it == ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME || it == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER
        }
        if (!relevant) return
        for (entry in packet.entries()) {
            val text = entry.displayName()?.string ?: continue
            UNCLAIMED.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { setChestCount(it, "TAB") }
        }
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled() || !DungeonContextTracker.inDungeon() || DungeonContextTracker.isMobEspPhase()) return
        if (EXTRA_STATS.matches(plain)) setChestCount(chestCount + 1, "RUN_END")
    }

    private fun setChestCount(value: Int, source: String) {
        if (value != chestCount) diagnostics.event("CHESTS $source count=$value")
        chestCount = value
        if (chestCount > chestWarning.intValue()) PlayerAlerts.alert("§cChest limit reached!")
    }

    fun chestCountText(): String? = CroesusRules.chestCountText(isEnabled(), DungeonContextTracker.inDungeon(), chestCount)

    /** The separate "Chest Count" HUD element. */
    val chestCountHud = object : HudElement {
        override fun hudIslands(): Set<Island> = setOf(Island.CATACOMBS)

        override fun id(): String = "croesus_chest_count"
        override fun module(): Module = this@CroesusModule
        override fun displayName(): String = "Chest Count"
        override fun width(font: Font?): Int = font!!.width("Chests: 00")
        override fun height(font: Font?): Int = font!!.lineHeight
        override fun visible(): Boolean = chestCountText() != null
        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(chestCountText() ?: return), x, y, 0xFFFFFFFF.toInt())
        }
        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
            graphics.text(font, Component.literal(chestCountText() ?: "§6Chests: §a12"), x, y, 0xFFFFFFFF.toInt())
        }
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val PLAYER_SLOTS = 36
        private const val MAX_PREVIEW_SLOT = 16
        private const val MAX_OPENED_SLOT = 40
        private val UNCLAIMED = Regex("""^ Unclaimed chests: (\d+)$""")
        private val EXTRA_STATS = Regex(""" {29}> EXTRA STATS <""")
        private val spec = ModuleSettingsSpec.of("Croesus")

        private val refreshPrices = ModuleAction(
            "Refresh Prices",
            "Downloads Odin's 7-day average prices and the 3-day Dungeon Chest Key price again. Live prices refresh on their own.",
            Runnable { INSTANCE.refreshNow() },
        )

        @JvmField
        val INSTANCE = CroesusModule()
    }
}

/**
 * Whether the Croesus "Available Modifiers" line [name] is struck through (that modifier was used on the run). Reads every
 * styled run of the line, so a leading space or a split component still counts.
 */
internal fun croesusStruckModifier(stack: ItemStack, name: String): Boolean =
    stack.get(DataComponents.LORE)?.lines()?.any { line ->
        var hit = false
        line.visit({ style, text ->
            if (style.isStrikethrough && text.trim().isNotEmpty() && name.contains(text.trim())) hit = true
            java.util.Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        hit && line.string.trim() == name
    } ?: false
