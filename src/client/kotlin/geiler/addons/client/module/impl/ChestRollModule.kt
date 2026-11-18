package geiler.addons.client.module.impl

import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.dungeon.f7.ChestRollRules
import geiler.addons.client.dungeon.f7.CroesusPrices
import geiler.addons.client.dungeon.f7.CroesusRules
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.inventory.SkyblockRarity
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.IdentityHashMap
import java.util.Random

/**
 * Chest Roll: a case-opening animation for dungeon reward chests. The click on "Open Reward Chest" is held back while a
 * translucent band of rarity-colored cards scrolls past a center line and lands on the chest's main item; the item then
 * grows with its price and tooltip, and the held click is sent so the chest opens as usual. The chest contents come from the
 * open chest screen itself; the filler cards come from the loot pool of the floor seen in the Croesus preview.
 */
class ChestRollModule private constructor(private val s: Settings) : Module(
    "Chest Roll",
    "Case-opening animation when you open a dungeon reward chest.",
    Category.F7,
    false,
    s.wood, s.gold, s.diamond, s.emerald, s.obsidian, s.bedrock,
    s.spinSeconds, s.playSound, s.volume,
) {
    private class Settings {
        val spec = ModuleSettingsSpec.of("Chest Roll")
        val wood = spec.toggle("wood")
        val gold = spec.toggle("gold")
        val diamond = spec.toggle("diamond")
        val emerald = spec.toggle("emerald")
        val obsidian = spec.toggle("obsidian")
        val bedrock = spec.toggle("bedrock")
        val spinSeconds = spec.slider("spinSeconds")
        val playSound = spec.toggle("playSound")
        val volume = spec.slider("volume")
    }

    private class Face(val stack: ItemStack, val rarity: Int)

    private class Item(val stack: ItemStack, val name: String, val price: Double)

    private class Roll(
        val screen: AbstractContainerScreen<*>,
        val tier: ChestRollRules.Tier,
        val strip: List<Face>,
        val winnerIndex: Int,
        val jitter: Double,
        val timeline: ChestRollRules.Timeline,
        val main: Item,
        val others: List<Item>,
        val slot: Slot,
        val slotId: Int,
        val button: Int,
        val input: ContainerInput,
    ) {
        val startNanos = System.nanoTime()
        var offsetMs = 0L
        var lastCard = Int.MIN_VALUE
        var lastPhase = ChestRollRules.Phase.SPIN
        var skipped = false
        fun elapsed(): Long = (System.nanoTime() - startNanos) / 1_000_000L + offsetMs
    }

    private var roll: Roll? = null
    private var dispatching = false
    private var floor: String? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 320)

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun onEnable() {
        diagnostics.enable("tiers=${enabledTiers()} spin=${s.spinSeconds.intValue()}s sound=${s.playSound.value()} lootFloors=${LOOT.keys}")
        if (CroesusPrices.prices.isEmpty()) CroesusPrices.refresh { diagnostics.event("PRICES $it") }
    }

    override fun onDisable() {
        roll?.let { release(it, "DISABLED") }
        roll = null
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    private fun enabledTiers(): List<String> = ChestRollRules.Tier.entries.filter(::tierEnabled).map { it.label }

    private fun tierEnabled(tier: ChestRollRules.Tier): Boolean = when (tier) {
        ChestRollRules.Tier.WOOD -> s.wood.value()
        ChestRollRules.Tier.GOLD -> s.gold.value()
        ChestRollRules.Tier.DIAMOND -> s.diamond.value()
        ChestRollRules.Tier.EMERALD -> s.emerald.value()
        ChestRollRules.Tier.OBSIDIAN -> s.obsidian.value()
        ChestRollRules.Tier.BEDROCK -> s.bedrock.value()
    }

    // ---- client tick: floor memory, timing, releasing the held click ----

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val screen = client.gui.screen() as? AbstractContainerScreen<*>
        screen?.title?.string?.let { title -> ChestRollRules.floorKey(title)?.let { floor = it } }
        val r = roll
        if (r != null) {
            if (screen !== r.screen) {
                diagnostics.event("ABORT screen changed tier=${r.tier.label}")
                roll = null
            } else {
                update(r)
                if (r.timeline.phase(r.elapsed()) == ChestRollRules.Phase.DONE) finish(r)
            }
        }
        diagnostics.status { "enabled=true floor=$floor roll=${roll?.tier?.label ?: "none"} prices=${CroesusPrices.prices.size} suppressedEvents=$it" }
    }

    /** Sounds on card changes and phase changes; safe to call from both the tick and the render pass. */
    private fun update(r: Roll) {
        val t = r.elapsed()
        val phase = r.timeline.phase(t)
        if (phase == ChestRollRules.Phase.SPIN) {
            val card = ChestRollRules.cardUnder(trackX(r, t), centerX(), PITCH)
            if (card != r.lastCard) {
                r.lastCard = card
                val progress = r.timeline.spinProgress(t)
                sound("minecraft:block.note_block.hat", 0.5f, (1.5 - 0.5 * progress).toFloat())
            }
        }
        if (phase != r.lastPhase) {
            val rarity = r.strip[r.winnerIndex].rarity
            when (phase) {
                ChestRollRules.Phase.SETTLE -> sound(if (rarity >= 4) "minecraft:ui.toast.challenge_complete" else "minecraft:entity.experience_orb.pickup", 0.8f, 1f)
                ChestRollRules.Phase.REVEAL -> if (rarity >= 3) sound("minecraft:entity.firework_rocket.twinkle", 0.8f, 0.9f)
                else -> Unit
            }
            diagnostics.event("PHASE $phase tier=${r.tier.label} t=${t}ms")
            r.lastPhase = phase
        }
    }

    private fun sound(id: String, volume: Float, pitch: Float) {
        if (!s.playSound.value()) return
        ModuleSounds.play(id, volume * s.volume.value() / 100f, pitch)
    }

    private fun finish(r: Roll) {
        roll = null
        release(r, if (r.skipped) "SKIPPED" else "DONE")
    }

    /** Sends the held click so the chest opens exactly as it would have without the animation. */
    private fun release(r: Roll, why: String) {
        diagnostics.event("RELEASE $why tier=${r.tier.label} main=${r.main.name} elapsed=${r.elapsed()}ms")
        if (Minecraft.getInstance().gui.screen() !== r.screen) return
        dispatching = true
        try {
            (r.screen as AbstractContainerScreenAccess).`geileraddons$invokeSlotClicked`(r.slot, r.slotId, r.button, r.input)
        } finally {
            dispatching = false
        }
    }

    // ---- hooks called by the container-screen mixin ----

    /** True swallows the click: the open click is held back and a roll starts, or a roll is running. */
    fun onSlotClicked(screen: AbstractContainerScreen<*>, slot: Slot?, slotId: Int, button: Int, input: ContainerInput): Boolean {
        if (!isEnabled() || dispatching) return false
        roll?.let { return it.screen === screen }
        if (slot == null || button != 0 || input != ContainerInput.PICKUP || slot.item.item != Items.CHEST) return false
        val tier = ChestRollRules.tierOf(screen.title.string) ?: return false
        if (!tierEnabled(tier)) return false
        val items = readItems(screen)
        val mainIndex = ChestRollRules.mainIndex(items.map { it.name }, items.map { it.price })
        if (mainIndex < 0) {
            diagnostics.event("SKIP no items tier=${tier.label}")
            return false
        }
        val main = items[mainIndex]
        val rng = Random()
        val winnerIndex = WINNER_BASE + rng.nextInt(WINNER_SPREAD)
        val strip = buildFaces(main, items, tier, winnerIndex, rng)
        val spinMs = (s.spinSeconds.value() * 1000f).toLong().coerceIn(1000L, 12000L)
        val started = Roll(
            screen, tier, strip, winnerIndex, (rng.nextDouble() * 1.4 - 0.7), ChestRollRules.Timeline(spinMs), main,
            items.filterIndexed { i, _ -> i != mainIndex }, slot, slotId, button, input,
        )
        roll = started
        diagnostics.event("START tier=${tier.label} floor=$floor main=${main.name} price=${CroesusRules.money(main.price)} items=${items.size} strip=${strip.size} spin=${spinMs}ms")
        sound("minecraft:block.chest.open", 0.8f, 1f)
        return true
    }

    /** Any key skips; Esc and inventory keys do not close the chest while a roll runs. */
    fun handleKey(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        val r = roll ?: return false
        if (!isEnabled() || r.screen !== screen) return false
        skip(r, "KEY ${event.key()}")
        return true
    }

    fun handleMouseClick(screen: AbstractContainerScreen<*>): Boolean {
        val r = roll ?: return false
        if (!isEnabled() || r.screen !== screen) return false
        skip(r, "CLICK")
        return true
    }

    private fun skip(r: Roll, source: String) {
        val before = r.elapsed()
        r.offsetMs += r.timeline.skip(before) - before
        r.skipped = true
        diagnostics.event("SKIP $source from=${before}ms phase=${r.lastPhase}")
        update(r)
    }

    fun onScreenClosed(screen: AbstractContainerScreen<*>) {
        val r = roll ?: return
        if (r.screen !== screen) return
        diagnostics.event("ABORT screen closed tier=${r.tier.label}")
        roll = null
    }

    // ---- reading the chest and building the strip ----

    private fun readItems(screen: AbstractContainerScreen<*>): List<Item> {
        val all = screen.menu.slots
        val out = ArrayList<Item>()
        val prices = CroesusPrices.prices
        for ((index, slot) in all.subList(0, (all.size - PLAYER_SLOTS).coerceAtLeast(0)).withIndex()) {
            if (index > MAX_OPENED_SLOT) break
            val stack = slot.item
            if (stack.isEmpty || isChrome(stack)) continue
            val name = stack.hoverName.string
            val id = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.ifEmpty { null }
            val openedSlot = if (stack.item == Items.ENCHANTED_BOOK) {
                val enchants = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty("enchantments")
                val key = enchants?.keySet()?.firstOrNull()
                if (enchants == null || key == null) CroesusRules.OpenedSlot.item(name, id) else CroesusRules.OpenedSlot.book(name, key, enchants.getIntOr(key, 0))
            } else {
                CroesusRules.OpenedSlot.item(name, id)
            }
            val price = CroesusRules.openedChest(listOf(openedSlot), prices, true).items.firstOrNull()?.price ?: 0.0
            out.add(Item(stack, name, price))
        }
        return out
    }

    /** Container buttons and background panes are not loot. */
    private fun isChrome(stack: ItemStack): Boolean {
        val item = stack.item
        return item == Items.CHEST || item == Items.BARRIER || item == Items.ARROW || stack.hoverName.string.isBlank() ||
            BuiltInRegistries.ITEM.getKey(item).path.endsWith("glass_pane")
    }

    private fun rarityOf(stack: ItemStack): Int = (ItemBackgrounds.info(stack).rarity?.ordinal ?: 0).coerceAtMost(MAX_RARITY)

    private fun buildFaces(main: Item, items: List<Item>, tier: ChestRollRules.Tier, winnerIndex: Int, rng: Random): List<Face> {
        val faces = IdentityHashMap<ChestRollRules.Card, Face>()
        val winner = ChestRollRules.Card(main.name, rarityOf(main.stack), 1.0, "")
        faces[winner] = Face(main.stack, winner.rarity)
        val pool = ArrayList<ChestRollRules.Card>()
        LOOT[floor]?.get(tier.label)?.let { pool.addAll(it) }
        if (pool.isEmpty()) {
            for (item in items) {
                val card = ChestRollRules.Card(item.name, rarityOf(item.stack), 1.0, "")
                faces[card] = Face(item.stack, card.rarity)
                pool.add(card)
            }
        }
        val icons = HashMap<String, ItemStack>()
        return ChestRollRules.buildStrip(winner, pool, winnerIndex + TAIL_CARDS, winnerIndex, rng).map { card ->
            faces.getOrPut(card) { Face(icons.getOrPut(card.icon) { iconStack(card.icon) }, card.rarity) }
        }
    }

    private fun iconStack(id: String): ItemStack {
        val item = Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getValue(it) }?.takeIf { it != Items.AIR } ?: Items.PAPER
        return ItemStack(item)
    }

    // ---- rendering ----

    private fun centerX(): Double = Minecraft.getInstance().window.guiScaledWidth / 2.0

    private fun trackX(r: Roll, t: Long): Double {
        val start = centerX() - CARD / 2.0
        val end = ChestRollRules.landingTrackX(r.winnerIndex, PITCH, CARD.toDouble(), centerX(), r.jitter)
        return start + (end - start) * r.timeline.spinProgress(t)
    }

    /** Draws the roll over the whole screen. True means the vanilla screen is not drawn, so the loot stays hidden. */
    fun renderRoll(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
        val r = roll ?: return false
        if (!isEnabled() || r.screen !== screen) return false
        update(r)
        val t = r.elapsed()
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        graphics.fill(0, 0, w, h, 0xB0000000.toInt())
        val phase = r.timeline.phase(t)
        val revealMs = (t - r.timeline.revealStart).coerceAtLeast(0L)
        val shutter = if (phase == ChestRollRules.Phase.REVEAL || phase == ChestRollRules.Phase.DONE) {
            1.0 - ChestRollRules.easeOut(revealMs / SHUTTER_MS)
        } else 1.0
        if (shutter > 0.0) drawBand(graphics, r, t, w, h, shutter, phase)
        if (phase == ChestRollRules.Phase.REVEAL || phase == ChestRollRules.Phase.DONE) drawReveal(graphics, r, revealMs, w, h)
        return true
    }

    private fun drawBand(graphics: GuiGraphicsExtractor, r: Roll, t: Long, w: Int, h: Int, shutter: Double, phase: ChestRollRules.Phase) {
        val mid = h / 2
        val half = (BAND_HEIGHT / 2 * shutter).toInt()
        if (half <= 0) return
        val top = mid - half
        val bottom = mid + half
        graphics.fill(0, top, w, bottom, 0xA0000000.toInt())
        graphics.enableScissor(0, top, w, bottom)
        val x0 = trackX(r, t)
        val cardTop = mid - CARD / 2
        for ((i, face) in r.strip.withIndex()) {
            val x = (x0 + i * PITCH).toInt()
            if (x > w || x + CARD < 0) continue
            val color = rarityColor(face.rarity)
            graphics.fill(x, cardTop, x + CARD, cardTop + CARD, (color and 0xFFFFFF) or 0x55000000)
            graphics.fill(x, cardTop + CARD - 3, x + CARD, cardTop + CARD, color or 0xFF000000.toInt())
            graphics.pose().pushMatrix()
            graphics.pose().translate((x + (CARD - 32) / 2).toFloat(), (cardTop + (CARD - 32) / 2 - 1).toFloat())
            graphics.pose().scale(2f, 2f)
            graphics.item(face.stack, 0, 0)
            graphics.pose().popMatrix()
            if (i == r.winnerIndex && phase != ChestRollRules.Phase.SPIN) outline(graphics, x, cardTop, CARD, CARD, 0xFFFFD24A.toInt())
        }
        graphics.disableScissor()
        val cx = w / 2
        graphics.fill(cx - 1, top, cx + 1, bottom, 0xFFFFD24A.toInt())
        graphics.fill(cx - 4, top, cx + 4, top + 2, 0xFFFFD24A.toInt())
        graphics.fill(cx - 4, bottom - 2, cx + 4, bottom, 0xFFFFD24A.toInt())
    }

    private fun drawReveal(graphics: GuiGraphicsExtractor, r: Roll, revealMs: Long, w: Int, h: Int) {
        val font = Minecraft.getInstance().font
        val stack = r.main.stack
        val rarity = r.strip[r.winnerIndex].rarity
        val color = rarityColor(rarity)
        val appear = ((revealMs - SHUTTER_MS * 0.6) / REVEAL_GROW_MS).coerceIn(0.0, 1.0)
        val scale = (easeOutBack(appear) * REVEAL_SCALE).toFloat()
        if (scale <= 0.01f) return
        val box = (16 * REVEAL_SCALE).toInt()
        val itemY = h / 2 - box - 8
        val cx = w / 2
        graphics.fill(cx - box / 2 - 8, itemY - 8, cx + box / 2 + 8, itemY + box + 8, (color and 0xFFFFFF) or 0x66000000)
        outline(graphics, cx - box / 2 - 8, itemY - 8, box + 16, box + 16, color or 0xFF000000.toInt())
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx.toFloat(), (itemY + box / 2).toFloat())
        graphics.pose().scale(scale, scale)
        graphics.pose().translate(-8f, -8f)
        graphics.item(stack, 0, 0)
        graphics.pose().popMatrix()
        if (appear < 1.0) return
        var y = itemY + box + 16
        graphics.centeredText(font, stack.hoverName, cx, y, 0xFFFFFFFF.toInt())
        y += font.lineHeight + 3
        val value = if (r.main.price > 0) "§6Value: §e${CroesusRules.money(r.main.price)} coins" else "§7No price data"
        graphics.centeredText(font, Component.literal(value), cx, y, 0xFFFFFFFF.toInt())
        for (other in r.others.take(MAX_OTHER_LINES)) {
            y += font.lineHeight + 2
            val price = if (other.price > 0) " §8- §6${CroesusRules.money(other.price)}" else ""
            graphics.centeredText(font, Component.literal("§7+ §f${other.name}$price"), cx, y, 0xFFFFFFFF.toInt())
        }
        y += font.lineHeight + 8
        graphics.centeredText(font, Component.literal("§8Click to continue"), cx, y, 0xFFFFFFFF.toInt())
        graphics.setTooltipForNextFrame(font, stack, cx + box / 2 + 16, itemY)
    }

    private fun outline(graphics: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, argb: Int) {
        graphics.fill(x, y, x + width, y + 1, argb)
        graphics.fill(x, y + height - 1, x + width, y + height, argb)
        graphics.fill(x, y, x + 1, y + height, argb)
        graphics.fill(x + width - 1, y, x + width, y + height, argb)
    }

    private fun easeOutBack(t: Double): Double {
        val c1 = 1.70158
        val c3 = c1 + 1
        val x = t - 1
        return 1 + c3 * x * x * x + c1 * x * x
    }

    private fun rarityColor(rarity: Int): Int =
        SkyblockRarity.entries[rarity.coerceIn(0, MAX_RARITY)].color(true) or 0xFF000000.toInt()

    companion object {
        private const val CARD = 44
        private const val PITCH = 48.0
        private const val BAND_HEIGHT = 64
        private const val WINNER_BASE = 38
        private const val WINNER_SPREAD = 8
        private const val TAIL_CARDS = 8
        private const val PLAYER_SLOTS = 36
        private const val MAX_OPENED_SLOT = 40
        private const val MAX_RARITY = 5
        private const val MAX_OTHER_LINES = 6
        private const val SHUTTER_MS = 350.0
        private const val REVEAL_GROW_MS = 450.0
        private const val REVEAL_SCALE = 4.0

        /** Loot pools from `data/chest-roll-loot.json`; an unreadable file only costs the filler cards. */
        private val LOOT: Map<String, Map<String, List<ChestRollRules.Card>>> by lazy {
            try {
                val json = ChestRollModule::class.java.getResourceAsStream("/data/chest-roll-loot.json")?.use { String(it.readAllBytes(), Charsets.UTF_8) }
                if (json == null) emptyMap() else ChestRollRules.parseLoot(json)
            } catch (error: RuntimeException) {
                geiler.addons.client.config.GeilerAddonsLog.write(Category.F7, "Chest Roll", 0, "LOOT FILE FAILED ${error.message}")
                emptyMap()
            }
        }

        @JvmField
        val INSTANCE = ChestRollModule(Settings())
    }
}
