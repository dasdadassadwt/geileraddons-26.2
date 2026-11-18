package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.dungeon.DungeonContextTracker
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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Croesus: highlights and hides runs in the Croesus menu, prices every chest of a run (profit, best two chests marked) and
 * the chest being opened, and counts unclaimed chests. After Odin's Croesus; prices come from Odin's 7-day average file.
 */
class CroesusModule private constructor() : DungeonTextHudModule(
    "Croesus",
    "Profit and highlights for the Croesus menu and dungeon chests.",
    "croesus_chests",
    "Croesus Chest HUD",
    *spec.settings.toTypedArray(),
    refreshPrices,
) {
    override fun hudIslands(): Set<Island> = setOf(Island.DUNGEON_HUB, Island.CATACOMBS)

    private val highlightState = spec.toggle("highlightState")
    private val highlightProfitable = spec.toggle("highlightProfitable")
    private val hideClaimed = spec.toggle("hideClaimed")
    private val includeKey = spec.toggle("includeKey")
    private val minimized = spec.toggle("minimized")
    private val includeEssence = spec.toggle("includeEssence")
    private val chestWarning = spec.slider("chestWarning")

    private var trackedScreen: Screen? = null
    private var kind = "none"
    private var hidden: Set<Int> = emptySet()
    private var tint: Map<Int, Int> = emptyMap()
    private var kismet: Set<Int> = emptySet()
    private var ticks = 0
    private var chestCount = 0
    private var lastLevel: Any? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 320)

    init {
        group(*spec.groups.toTypedArray())
    }

    /** A sample the size of a real preview, so placing it in Move Elements matches the live list. */
    override fun previewLines(): List<String> = CroesusRules.hudLines(
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

    /**
     * Publishes the chest lines so the list always fits the screen: with the items if they fit, with only the profit per
     * chest if not, and cut off as a last resort. A list taller than the screen cannot be moved in Move Elements.
     */
    private fun publishFitted(chests: List<CroesusRules.ChestResult>) {
        val client = Minecraft.getInstance()
        val lineHeight = client.font.lineHeight + 2
        val allowed = ((client.window.guiScaledHeight * 0.95f) / (lineHeight * HudManager.scale(this))).toInt().coerceAtLeast(3)
        var lines = CroesusRules.hudLines(chests, minimized.value())
        if (lines.size > allowed && !minimized.value()) lines = CroesusRules.hudLines(chests, true)
        if (lines.size > allowed) lines = lines.take(allowed)
        if (lines.isEmpty()) clearHudLines() else publishHudLines(lines)
    }

    override fun onEnable() {
        diagnostics.enable("hide=${hideClaimed.value()} state=${highlightState.value()} profitable=${highlightProfitable.value()} essence=${includeEssence.value()} warning=${chestWarning.intValue()}")
        if (CroesusPrices.prices.isEmpty()) fetchPrices()
    }

    override fun onDisable() {
        clearScreenState()
        clearHudLines()
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

    private fun clearScreenState() {
        trackedScreen = null
        kind = "none"
        hidden = emptySet()
        tint = emptyMap()
        kismet = emptySet()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val level = Minecraft.getInstance().level
        if (level !== lastLevel) {
            lastLevel = level
            if (chestCount != 0) diagnostics.event("CHESTS WORLD_CHANGED count=0")
            chestCount = 0
        }
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        if (screen == null) {
            if (trackedScreen != null) {
                clearScreenState()
                clearHudLines()
            }
            diagnostics.status { "enabled=true screen=none prices=${CroesusPrices.status} chests=$chestCount suppressedEvents=$it" }
            return
        }
        if (screen !== trackedScreen) {
            clearHudLines()
            trackedScreen = screen
            kind = "none"
        }
        if (ticks++ % REFRESH_TICKS != 0) return
        val title = screen.title.string
        val newKind = when {
            CroesusRules.isCroesusMenu(title) -> "menu"
            CroesusRules.isChestPreview(title) -> "preview"
            CroesusRules.isOpenedChest(title) -> "opened"
            else -> "none"
        }
        if (newKind != kind) diagnostics.event("SCREEN $newKind")
        kind = newKind
        when (kind) {
            "menu" -> scanMenu(screen)
            "preview" -> scanPreview(screen)
            "opened" -> scanOpened(screen)
            else -> {
                hidden = emptySet()
                tint = emptyMap()
                kismet = emptySet()
                clearHudLines()
            }
        }
        diagnostics.status { "enabled=true screen=$kind prices=${CroesusPrices.status}(${CroesusPrices.prices.size}) hidden=${hidden.size} chests=$chestCount suppressedEvents=$it" }
    }

    private fun containerSlots(screen: AbstractContainerScreen<*>): List<Slot> {
        val all = screen.menu.slots
        return all.subList(0, (all.size - PLAYER_SLOTS).coerceAtLeast(0))
    }

    private fun lore(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

    private fun struck(stack: ItemStack, name: String): Boolean =
        stack.get(DataComponents.LORE)?.lines()?.any { line ->
            line.siblings.any { it.string == name && it.style.isStrikethrough }
        } ?: false

    private fun scanMenu(screen: AbstractContainerScreen<*>) {
        clearHudLines()
        val newHidden = HashSet<Int>()
        val newTint = HashMap<Int, Int>()
        val newKismet = HashSet<Int>()
        for ((index, slot) in containerSlots(screen).withIndex()) {
            val stack = slot.item
            if (stack.isEmpty) continue
            val name = stack.hoverName.string
            if (name != "The Catacombs" && name != "Master Mode The Catacombs") continue
            val state = CroesusRules.menuState(lore(stack), hideClaimed.value(), includeKey.value(), struck(stack, "Dungeon Chest Key"), highlightState.value())
            if (state.hide) {
                newHidden.add(index)
                continue
            }
            if (state.color != 0) newTint[index] = state.color
            if (struck(stack, "Kismet Feather")) newKismet.add(index)
        }
        hidden = newHidden
        tint = newTint
        kismet = newKismet
    }

    private fun scanPreview(screen: AbstractContainerScreen<*>) {
        hidden = emptySet()
        kismet = emptySet()
        val prices = CroesusPrices.prices
        val chests = ArrayList<CroesusRules.ChestResult>()
        for ((index, slot) in containerSlots(screen).withIndex()) {
            if (index > MAX_PREVIEW_SLOT) break
            val stack = slot.item
            if (stack.isEmpty || stack.item != Items.PLAYER_HEAD) continue
            CroesusRules.previewChest(index, stack.hoverName.string, lore(stack), prices, includeEssence.value())?.let { chests.add(it) }
        }
        val keyPrice = CroesusPrices.keyPrice
        val top = if (highlightProfitable.value()) CroesusRules.topSlots(chests, keyPrice) else emptyList()
        diagnostics.statusOnChange { "PREVIEW chests=${chests.size} top=$top essence=${includeEssence.value()} keyPrice=${"%,.0f".format(keyPrice)}" }
        val newTint = HashMap<Int, Int>()
        top.getOrNull(0)?.let { newTint[it] = CroesusRules.COLOR_BEST }
        top.getOrNull(1)?.let { newTint[it] = CroesusRules.COLOR_SECOND }
        tint = newTint
        publishFitted(CroesusRules.sorted(chests))
    }

    private fun scanOpened(screen: AbstractContainerScreen<*>) {
        hidden = emptySet()
        kismet = emptySet()
        tint = emptyMap()
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
        val result = CroesusRules.openedChest(slots, CroesusPrices.prices, includeEssence.value())
        publishFitted(listOf(result))
    }

    private fun skyblockId(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.ifEmpty { null }

    private fun bookSlot(stack: ItemStack): CroesusRules.OpenedSlot {
        val enchantments = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty("enchantments")
        val key = enchantments?.keySet()?.firstOrNull()
        if (enchantments == null || key == null) return CroesusRules.OpenedSlot.item(stack.hoverName.string, skyblockId(stack))
        return CroesusRules.OpenedSlot.book(stack.hoverName.string, key, enchantments.getIntOr(key, 0))
    }

    /** Called by the slot mixin before a slot is drawn; true hides the slot. */
    fun shouldHideSlot(screen: Screen, slot: Slot): Boolean =
        isEnabled() && screen === trackedScreen && kind == "menu" && slot.index in hidden

    /** Called by the slot mixin before a slot's item is drawn: a tint behind it and a Kismet outline. */
    fun drawSlotBackground(graphics: GuiGraphicsExtractor, screen: Screen, slot: Slot) {
        if (!isEnabled() || screen !== trackedScreen) return
        val color = tint[slot.index]
        if (color != null) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color)
        if (slot.index in kismet) {
            val c = CroesusRules.COLOR_KISMET
            graphics.fill(slot.x - 1, slot.y - 1, slot.x + 17, slot.y, c)
            graphics.fill(slot.x - 1, slot.y + 16, slot.x + 17, slot.y + 17, c)
            graphics.fill(slot.x - 1, slot.y, slot.x, slot.y + 16, c)
            graphics.fill(slot.x + 16, slot.y, slot.x + 17, slot.y + 16, c)
        }
    }

    // ---- chest count ----

    /** Tab-list updates carry "Unclaimed chests: N". */
    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket) {
        if (!isEnabled() || !DungeonContextTracker.inDungeon()) return
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
        override fun hudIslands(): Set<Island> = setOf(Island.DUNGEON_HUB)

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
        private const val REFRESH_TICKS = 4
        private const val PLAYER_SLOTS = 36
        private const val MAX_PREVIEW_SLOT = 16
        private const val MAX_OPENED_SLOT = 40
        private val UNCLAIMED = Regex("""^ Unclaimed chests: (\d+)$""")
        private val EXTRA_STATS = Regex(""" {29}> EXTRA STATS <""")
        private val spec = ModuleSettingsSpec.of("Croesus")

        private val refreshPrices = ModuleAction(
            "Refresh Prices",
            "Downloads Odin's 7-day average prices and the 3-day Dungeon Chest Key price again.",
            Runnable { INSTANCE.refreshNow() },
        )

        @JvmField
        val INSTANCE = CroesusModule()
    }
}
