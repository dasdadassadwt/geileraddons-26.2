package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.ChestRollRules
import geiler.addons.client.dungeon.f7.CroesusPrices
import geiler.addons.client.dungeon.f7.CroesusRules
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.inventory.SkyblockRarity
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.SettingGroup
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.IdentityHashMap
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Chest Roll: a case-opening animation for dungeon reward chests. When an enabled chest tier opens, and again after every
 * reroll changes its contents, a band of rarity-colored cards scrolls past a center line over a blurred backdrop and lands on
 * the chest's main item; the item then grows, spins and shows its name, rarity and description, and the normal chest screen is
 * shown. The chest contents come from the open chest screen itself; the filler cards are real items of the floor's loot pool
 * (floor from the dungeon, else the last Croesus preview). The Test Roll button plays a roll over the game view without any chest.
 */
class ChestRollModule private constructor(private val s: Settings) : Module(
    "Chest Roll",
    "Case-opening animation when you open a dungeon reward chest.",
    Category.F7,
    false,
    s.wood, s.gold, s.diamond, s.emerald, s.obsidian, s.bedrock,
    s.spinSeconds, s.revealSeconds, s.blur, s.playSound, s.volume, s.test,
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
        val revealSeconds = spec.slider("revealSeconds")
        val blur = spec.toggle("blur")
        val playSound = spec.toggle("playSound")
        val volume = spec.slider("volume")
        val test = ModuleAction("Test Roll", "Plays a Bedrock roll with a random rare item from the M7 loot pool. The module must be enabled.") { INSTANCE.startTest() }
    }

    private class Face(val stack: ItemStack, val rarity: Int)

    private class Item(val stack: ItemStack, val name: String, val price: Double, val rarity: Int = -1)

    /** [screen] is null for a test roll, which is drawn over the game view instead of a chest screen. */
    private class Roll(
        val screen: AbstractContainerScreen<*>?,
        val tier: ChestRollRules.Tier,
        val strip: List<Face>,
        val winnerIndex: Int,
        val jitter: Double,
        val timeline: ChestRollRules.Timeline,
        val main: Item,
        val others: List<Item>,
    ) {
        val startNanos = System.nanoTime()
        var offsetMs = 0L
        var lastCard = Int.MIN_VALUE
        var lastPhase = ChestRollRules.Phase.SPIN
        var skipped = false
        var revealSounded = false
        var blurLogged = false
        fun elapsed(): Long = (System.nanoTime() - startNanos) / 1_000_000L + offsetMs
    }

    private var roll: Roll? = null
    private var floor: String? = null
    private val watch = ChestRollRules.ContentWatch()
    private var watchedScreen: AbstractContainerScreen<*>? = null
    private var pendingScreen: AbstractContainerScreen<*>? = null

    /** Chests that already rolled, so reopening one (or viewing it in Croesus) does not roll again for three hours. */
    private val rolled = ChestRollRules.RolledChests(32, 3L * 60L * 60L * 1000L)
    /** Item names of rolled chests by tier, so the Croesus preview can tell which of its chests were already rolled. */
    private val rolledContents = ArrayDeque<Triple<ChestRollRules.Tier, List<String>, Long>>()

    /** Chest screen whose current content is known to be rolled already, and the menu state it was judged at. */
    private var knownScreen: AbstractContainerScreen<*>? = null
    private var knownStateId = -1
    /** Chest screen covered before its roll starts, and when the cover began. */
    private var coverScreen: AbstractContainerScreen<*>? = null
    private var coverStartMs = 0L
    private var coverDrawn = false
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 320)

    init {
        group(*s.spec.groups.toTypedArray(), SettingGroup("Test", s.test))
    }

    override fun onEnable() {
        diagnostics.enable("tiers=${enabledTiers()} spin=${s.spinSeconds.intValue()}s reveal=${s.revealSeconds.value()}s blur=${s.blur.value()} sound=${s.playSound.value()} lootFloors=${LOOT.keys}")
        if (CroesusPrices.prices.isEmpty()) CroesusPrices.refresh { diagnostics.event("PRICES $it") }
        ItemListRepository.request()
    }

    override fun onDisable() {
        roll?.let { diagnostics.event("ABORT disabled tier=${it.tier.label}") }
        roll = null
        watch.reset()
        watchedScreen = null
        pendingScreen = null
        rolled.clear()
        rolledContents.clear()
        knownScreen = null
        coverScreen = null
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
            if (r.screen != null && screen !== r.screen) {
                diagnostics.event("ABORT screen changed tier=${r.tier.label}")
                roll = null
            } else {
                update(r)
                if (r.timeline.phase(r.elapsed()) == ChestRollRules.Phase.DONE) finish(r)
            }
        } else if (screen != null) {
            watchChest(screen)
        } else {
            watch.reset()
            watchedScreen = null
            pendingScreen = null
        }
        diagnostics.status { "enabled=true floor=$floor screen=${screen?.title?.string} roll=${roll?.tier?.label ?: "none"} prices=${CroesusPrices.prices.size} suppressedEvents=$it" }
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
                val left = r.winnerIndex - card
                if (left in 1..LAST_CARDS) {
                    sound("minecraft:block.note_block.pling", 0.45f, (1.2 + 0.12 * (LAST_CARDS - left)).toFloat())
                } else {
                    sound("minecraft:block.note_block.hat", 0.5f, (1.5 - 0.5 * progress).toFloat())
                }
            }
        }
        if (phase != r.lastPhase) {
            val rarity = r.strip[r.winnerIndex].rarity
            when (phase) {
                ChestRollRules.Phase.SETTLE -> {
                    sound("minecraft:block.note_block.basedrum", 0.9f, 0.8f)
                    if (rarity >= 3) sound("minecraft:block.amethyst_block.chime", 0.6f, 1.4f)
                }
                ChestRollRules.Phase.REVEAL -> sound("minecraft:entity.player.attack.sweep", 0.5f, 1.4f)
                else -> Unit
            }
            diagnostics.event("PHASE $phase tier=${r.tier.label} t=${t}ms")
            r.lastPhase = phase
        }
        if (!r.revealSounded) {
            if (phase == ChestRollRules.Phase.DONE) {
                r.revealSounded = true
            } else if (phase == ChestRollRules.Phase.REVEAL && r.timeline.revealFraction(t) >= ChestRollRules.SPIN_END) {
                r.revealSounded = true
                revealSting(r.strip[r.winnerIndex].rarity)
            }
        }
    }

    /** The sound of the item landing after its spin; richer for rarer items. */
    private fun revealSting(rarity: Int) {
        when {
            rarity >= 5 -> {
                sound("minecraft:ui.toast.challenge_complete", 0.9f, 1f)
                sound("minecraft:entity.firework_rocket.blast", 0.7f, 1f)
            }
            rarity == 4 -> {
                sound("minecraft:item.totem.use", 0.5f, 1.2f)
                sound("minecraft:entity.firework_rocket.twinkle", 0.8f, 0.9f)
            }
            rarity == 3 -> sound("minecraft:entity.player.levelup", 0.7f, 1.1f)
            rarity == 2 -> sound("minecraft:block.amethyst_block.chime", 0.9f, 1.2f)
            else -> sound("minecraft:entity.experience_orb.pickup", 0.8f, 1f)
        }
    }

    private fun sound(id: String, volume: Float, pitch: Float) {
        if (!s.playSound.value()) return
        ModuleSounds.play(id, volume * s.volume.value() / 100f, pitch)
    }

    private fun finish(r: Roll) {
        roll = null
        r.screen?.let {
            knownScreen = it
            knownStateId = it.menu.stateId
        }
        diagnostics.event("${if (r.skipped) "SKIPPED" else "DONE"} tier=${r.tier.label} main=${r.main.name} elapsed=${r.elapsed()}ms")
    }

    // ---- starting a roll ----

    /** Starts a roll when a chest screen of an enabled tier shows new content: the first open and every reroll. */
    private fun watchChest(screen: AbstractContainerScreen<*>) {
        if (screen !== watchedScreen) {
            watchedScreen = screen
            watch.reset()
        }
        val tier = ChestRollRules.tierOf(screen.title.string)
        if (tier == null) {
            watch.reset()
            pendingScreen = null
            return
        }
        val items = readItems(screen)
        val signature = if (items.isEmpty()) null else items.joinToString("|") { "${it.name}x${it.stack.count}" }
        val key = signature?.let { "${tier.label}|$it" }
        val now = System.currentTimeMillis()
        val known = key != null && rolled.contains(key, now)
        if (known) {
            knownScreen = screen
            knownStateId = screen.menu.stateId
        } else if (knownScreen === screen && key != null) {
            knownScreen = null
            restartCover(screen)
        }
        // Until the roll starts the loot is already on screen for a tick or two; the result overlays wait for it.
        pendingScreen = if (tierEnabled(tier) && key != null && !known) screen else null
        if (!watch.update(signature)) return
        val enabled = tierEnabled(tier)
        diagnostics.event("SEEN tier=${tier.label} enabled=$enabled items=${items.size} floor=$floor known=$known")
        if (!enabled) return
        if (known) {
            diagnostics.event("SKIP already rolled tier=${tier.label} items=${items.size}")
            return
        }
        rolled.mark(key ?: return, now)
        rolledContents.addLast(Triple(tier, items.map { it.name }, now))
        while (rolledContents.size > MAX_ROLLED_CONTENTS) rolledContents.removeFirst()
        startRoll(screen, tier, items, resolveFloor())
    }

    /** The floor of the run you are in wins over the floor remembered from the last Croesus preview, which can be stale. */
    private fun resolveFloor(): String? =
        ChestRollRules.pickFloor(ChestRollRules.floorKeyOf(DungeonContextTracker.currentFloor()?.displayName()), floor)

    /** True while a roll runs on [screen] (or is about to), so profit lines and other overlays must not show the loot yet. */
    fun hidesResult(screen: AbstractContainerScreen<*>?): Boolean =
        isEnabled() && screen != null && (roll?.screen === screen || pendingScreen === screen || coverScreen === screen && coverDrawn)

    /**
     * Whether a Croesus preview chest (head name [chestName], loot lines [lootLines]) may show its value: always when Chest
     * Roll is off or its tier is not rolled, else only once that chest was rolled. Unknown chests stay hidden.
     */
    fun revealedInPreview(chestName: String?, lootLines: List<String>): Boolean {
        if (!isEnabled()) return true
        val tier = ChestRollRules.tierOf(chestName?.trim()) ?: return true
        if (!tierEnabled(tier)) return true
        val now = System.currentTimeMillis()
        rolledContents.removeAll { now - it.third > ROLLED_TTL_MS }
        return rolledContents.any { it.first == tier && ChestRollRules.previewMatches(lootLines, it.second) }
    }

    /**
     * Croesus preview tooltip: an unrolled chest's loot lines become obfuscated text of the same length, so hovering it does
     * not spoil the roll. The name, cost and key lines stay readable.
     */
    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || stack.item != Items.PLAYER_HEAD) return
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return
        if (!CroesusRules.isChestPreview(screen.title.string)) return
        val plain = lines.map { it.string }
        val range = ChestRollRules.lootLineRange(plain) ?: return
        if (revealedInPreview(stack.hoverName.string, plain.slice(range))) return
        for (i in range) {
            lines[i] = Component.literal(plain[i]).withStyle(ChatFormatting.GRAY, ChatFormatting.OBFUSCATED)
        }
        if (scrambledLogged.add(stack.hoverName.string)) notePreviewHidden(0, range.count())
        if (scrambledLogged.size > MAX_ROLLED_CONTENTS) scrambledLogged.clear()
    }

    /** Chest names already logged as scrambled, so hovering does not log every frame. */
    private val scrambledLogged = HashSet<String>()

    /** Diagnostics for what the Croesus preview hid, bounded by the module's event limits. */
    fun notePreviewHidden(hidden: Int, scrambledLines: Int) {
        if (hidden > 0) diagnostics.event("PREVIEW_HIDDEN chests=$hidden")
        if (scrambledLines > 0) diagnostics.event("TOOLTIP_SCRAMBLED lines=$scrambledLines")
    }

    private fun startRoll(screen: AbstractContainerScreen<*>?, tier: ChestRollRules.Tier, items: List<Item>, floorKey: String?) {
        val mainIndex = ChestRollRules.mainIndex(items.map { it.name }, items.map { it.price })
        if (mainIndex < 0) {
            diagnostics.event("SKIP no items tier=${tier.label}")
            return
        }
        val main = items[mainIndex]
        val rng = Random()
        val winnerIndex = WINNER_BASE + rng.nextInt(WINNER_SPREAD)
        val strip = buildFaces(main, items, tier, floorKey, winnerIndex, rng)
        val spinMs = (s.spinSeconds.value() * 1000f).toLong().coerceIn(1000L, 12000L)
        val revealMs = ChestRollRules.revealMs(s.revealSeconds.value())
        roll = Roll(
            screen, tier, strip, winnerIndex, (rng.nextDouble() * 1.4 - 0.7), ChestRollRules.Timeline(spinMs, ChestRollRules.SETTLE_MS, revealMs), main,
            items.filterIndexed { i, _ -> i != mainIndex },
        )
        diagnostics.event(
            "START ${if (screen == null) "test " else ""}tier=${tier.label} floor=$floorKey main=${main.name} price=${CroesusRules.money(main.price)} " +
                "items=${items.size} strip=${strip.size} spin=${spinMs}ms reveal=${revealMs}ms blur=${s.blur.value()} " +
                "faces real=$realFaces icon=$iconFaces itemList=${ItemListRepository.compiled?.stacks?.size ?: "not loaded"} unresolved=${unresolved.take(6)}",
        )
        sound("minecraft:block.chest.open", 0.8f, 1f)
        sound("minecraft:entity.bat.takeoff", 0.4f, 1.3f)
    }

    /** The Test Roll button: a Bedrock roll over the game view with a random rare item of the M7 loot pool. */
    fun startTest() {
        if (!isEnabled()) return
        Minecraft.getInstance().gui.setScreen(null)
        val pool = LOOT["M7"]?.get(ChestRollRules.Tier.BEDROCK.label).orEmpty()
        val card = pool.filter { it.rarity >= 4 }.randomOrNull() ?: pool.randomOrNull()
        val main = if (card != null) {
            ItemListRepository.request()
            val real = realStack(card.id)
            val stack = real ?: iconStack(card.icon).also { it.set(DataComponents.CUSTOM_NAME, Component.literal(card.name)) }
            Item(stack, card.name, TEST_PRICE, card.rarity)
        } else {
            Item(ItemStack(Items.NETHER_STAR), "Test Reward", TEST_PRICE, MAX_RARITY)
        }
        startRoll(null, ChestRollRules.Tier.BEDROCK, listOf(main), "M7")
    }

    // ---- hooks called by the container-screen mixin ----

    /** True while a roll runs on this screen, so the chest cannot be clicked underneath it. */
    fun swallowsClick(screen: AbstractContainerScreen<*>): Boolean =
        isEnabled() && (roll?.screen === screen || roll == null && coverScreen === screen && coverDrawn)

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
        if (coverScreen === screen) {
            endCover("closed")
            coverScreen = null
        }
        if (knownScreen === screen) knownScreen = null
        val r = roll ?: return
        if (r.screen !== screen) return
        diagnostics.event("ABORT screen closed tier=${r.tier.label}")
        roll = null
    }

    // ---- reading the chest and building the strip ----

    private fun readItems(screen: AbstractContainerScreen<*>): List<Item> {
        val all = screen.menu.slots
        val out = ArrayList<Item>()
        val prices = CroesusModule.INSTANCE.effectivePrices()
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

    private fun rarityOf(item: Item): Int =
        item.rarity.takeIf { it >= 0 } ?: (ItemBackgrounds.info(item.stack).rarity?.ordinal ?: 0).coerceAtMost(MAX_RARITY)

    private fun buildFaces(main: Item, items: List<Item>, tier: ChestRollRules.Tier, floorKey: String?, winnerIndex: Int, rng: Random): List<Face> {
        val faces = IdentityHashMap<ChestRollRules.Card, Face>()
        val winner = ChestRollRules.Card(main.name, rarityOf(main), 1.0, "")
        faces[winner] = Face(main.stack, winner.rarity)
        val pool = ArrayList<ChestRollRules.Card>()
        LOOT[floorKey]?.get(tier.label)?.let { pool.addAll(it) }
        if (pool.isEmpty()) {
            for (item in items) {
                val card = ChestRollRules.Card(item.name, rarityOf(item), 1.0, "")
                faces[card] = Face(item.stack, card.rarity)
                pool.add(card)
            }
        }
        val icons = HashMap<String, ItemStack>()
        realFaces = 0
        iconFaces = 0
        unresolved.clear()
        ItemListRepository.compiled?.stacks?.beginFrame()
        return ChestRollRules.buildStrip(winner, pool, winnerIndex + TAIL_CARDS, winnerIndex, rng).map { card ->
            faces.getOrPut(card) {
                val real = realStack(card.id)
                if (real != null) {
                    realFaces++
                } else {
                    iconFaces++
                    if (card.id.isNotEmpty()) unresolved.add(card.id)
                }
                Face(real ?: icons.getOrPut(card.icon) { iconStack(card.icon) }, card.rarity)
            }
        }
    }

    /** The real item of a NEU id from the Item List repository, or null while it is not loaded, unknown or failed to build. */
    private fun realStack(id: String): ItemStack? {
        if (id.isEmpty()) return null
        val compiled = ItemListRepository.compiled ?: return null
        val stack = compiled.stacks.peek(id) ?: compiled.byId[id]?.let { compiled.stacks.get(it) } ?: return null
        return stack.takeUnless { it.isEmpty || it.item == Items.BARRIER }
    }

    private var realFaces = 0
    private var iconFaces = 0
    private val unresolved = LinkedHashSet<String>()

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
        if (!isEnabled()) return false
        val r = roll ?: return renderCover(screen, graphics)
        if (r.screen !== screen) return false
        if (coverScreen === screen) endCover("roll")
        draw(r, graphics)
        return true
    }

    private fun restartCover(screen: AbstractContainerScreen<*>) {
        coverScreen = screen
        coverStartMs = System.currentTimeMillis()
        coverDrawn = false
    }

    private fun endCover(reason: String) {
        if (coverDrawn) diagnostics.event("COVER end=$reason ms=${System.currentTimeMillis() - coverStartMs}")
        coverDrawn = false
    }

    /**
     * Until the tick starts a roll, a chest of an enabled tier is drawn as the roll's dark backdrop instead of its slots, so
     * the loot never shows for a frame. Content changed since it was judged known (a reroll) counts as unknown again.
     */
    private fun renderCover(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor): Boolean {
        val tier = ChestRollRules.tierOf(screen.title.string) ?: return false
        if (coverScreen !== screen) restartCover(screen)
        if (knownScreen === screen && screen.menu.stateId != knownStateId) {
            knownScreen = null
            restartCover(screen)
        }
        val elapsed = System.currentTimeMillis() - coverStartMs
        if (!ChestRollRules.covers(tierEnabled(tier), knownScreen === screen, elapsed)) {
            if (coverDrawn) endCover(if (elapsed >= ChestRollRules.COVER_MAX_MS) "timeout" else "known")
            return false
        }
        if (!coverDrawn) diagnostics.event("COVER start tier=${tier.label}")
        coverDrawn = true
        val blurred = s.blur.value() && try {
            graphics.blurBeforeThisStratum()
            graphics.nextStratum()
            true
        } catch (_: IllegalStateException) {
            false
        }
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), if (blurred) 0x70000000 else 0xB0000000.toInt())
        return true
    }

    /** Draws a test roll, which has no chest screen, over the game view. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        val r = roll ?: return
        if (!isEnabled() || r.screen != null) return
        draw(r, graphics)
    }

    private fun draw(r: Roll, graphics: GuiGraphicsExtractor) {
        update(r)
        val t = r.elapsed()
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        val blurred = applyBlur(graphics, r)
        graphics.fill(0, 0, w, h, if (blurred) 0x70000000 else 0xB0000000.toInt())
        val phase = r.timeline.phase(t)
        val fraction = r.timeline.revealFraction(t)
        val revealing = phase == ChestRollRules.Phase.REVEAL || phase == ChestRollRules.Phase.DONE
        val shutter = if (revealing) 1.0 - ChestRollRules.easeOut(fraction / SHUTTER_FRACTION) else 1.0
        if (shutter > 0.0) drawBand(graphics, r, t, w, h, shutter, phase)
        if (revealing) drawReveal(graphics, r, fraction, w, h)
    }

    /**
     * Blurs everything drawn so far (world and HUD) so the band and the reveal sit on a blurred backdrop. The vanilla blur
     * follows the player's Menu Background Blur option and can run once per frame; a refusal only costs the blur.
     */
    private fun applyBlur(graphics: GuiGraphicsExtractor, r: Roll): Boolean {
        if (!s.blur.value()) return false
        val applied = try {
            graphics.blurBeforeThisStratum()
            graphics.nextStratum()
            true
        } catch (error: IllegalStateException) {
            if (!r.blurLogged) diagnostics.event("BLUR refused ${error.message}")
            false
        }
        if (!r.blurLogged && applied) diagnostics.event("BLUR applied (strength follows Options > Menu Background Blur)")
        r.blurLogged = true
        return applied
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
            graphics.rarityItem(face.stack, 0, 0)
            graphics.pose().popMatrix()
            if (i == r.winnerIndex && phase != ChestRollRules.Phase.SPIN) outline(graphics, x, cardTop, CARD, CARD, 0xFFFFD24A.toInt())
        }
        graphics.disableScissor()
        val cx = w / 2
        graphics.fill(cx - 1, top, cx + 1, bottom, 0xFFFFD24A.toInt())
        graphics.fill(cx - 4, top, cx + 4, top + 2, 0xFFFFD24A.toInt())
        graphics.fill(cx - 4, bottom - 2, cx + 4, bottom, 0xFFFFD24A.toInt())
    }

    /**
     * The reveal: the item grows from small to big while it spins (a flip), a glow in its rarity color blooms and a ring bursts
     * as it lands; then its name, rarity and description fade in below. [fraction] is the progress of the whole reveal.
     */
    private fun drawReveal(graphics: GuiGraphicsExtractor, r: Roll, fraction: Double, w: Int, h: Int) {
        val spin = ChestRollRules.revealSpin(fraction)
        if (spin <= 0.01) return
        val font = Minecraft.getInstance().font
        val stack = r.main.stack
        val rarity = r.strip[r.winnerIndex].rarity
        val rgb = rarityColor(rarity) and 0xFFFFFF
        val box = (16 * REVEAL_SCALE).toInt()
        val cx = w / 2
        val cy = (h * ITEM_CENTER_Y).toInt()

        for (layer in 0..2) {
            val half = box / 2 + 6 + layer * 7
            val alpha = ((0x38 - layer * 0x0E) * spin).toInt()
            graphics.fill(cx - half, cy - half, cx + half, cy + half, (alpha shl 24) or rgb)
        }
        val burst = ((fraction - ChestRollRules.SPIN_END) / BURST_FRACTION).coerceIn(0.0, 1.0)
        if (burst > 0.0 && burst < 1.0) {
            val half = (box / 2 + 8 + 34 * ChestRollRules.easeOut(burst)).toInt()
            outline(graphics, cx - half, cy - half, half * 2, half * 2, (((1.0 - burst) * 255).toInt() shl 24) or rgb)
        }

        val grow = easeOutBack(spin)
        val scale = (REVEAL_START_SCALE + (REVEAL_SCALE - REVEAL_START_SCALE) * grow).toFloat()
        val flip = abs(cos(spin * 2.0 * PI)).coerceAtLeast(0.06).toFloat()
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx.toFloat(), cy.toFloat())
        graphics.pose().scale(scale * flip, scale)
        graphics.pose().translate(-8f, -8f)
        graphics.rarityItem(stack, 0, 0)
        graphics.pose().popMatrix()

        val text = ChestRollRules.revealText(fraction)
        if (text <= 0.02) return
        val alpha = (text * 255).toInt().coerceIn(0, 255)
        val white = (alpha shl 24) or 0xFFFFFF
        var y = cy + box / 2 + 14 + ((1.0 - text) * 8).toInt()
        graphics.pose().pushMatrix()
        graphics.pose().translate(cx.toFloat(), y.toFloat())
        graphics.pose().scale(NAME_SCALE, NAME_SCALE)
        graphics.centeredText(font, stack.hoverName, 0, 0, white)
        graphics.pose().popMatrix()
        y += (font.lineHeight * NAME_SCALE).toInt() + 3
        val rarityInfo = SkyblockRarity.entries[rarity.coerceIn(0, MAX_RARITY)]
        graphics.centeredText(font, Component.literal("§l${rarityInfo.display}"), cx, y, (alpha shl 24) or rgb)
        y += font.lineHeight + 5

        val reserved = (if (r.main.price > 0 || r.others.isNotEmpty()) font.lineHeight * 3 else font.lineHeight) + 22
        val room = ((h - reserved - y) / (font.lineHeight + 1)).coerceIn(0, MAX_LORE_LINES)
        for (line in descriptionLines(stack, rarityInfo.display).take(room)) {
            if (line.string.isBlank()) {
                y += LORE_GAP
            } else {
                graphics.centeredText(font, line, cx, y, white)
                y += font.lineHeight + 1
            }
        }
        y += 4
        val value = if (r.main.price > 0) "§6Value: §e${CroesusRules.money(r.main.price)} coins" else "§7No price data"
        graphics.centeredText(font, Component.literal(value), cx, y, white)
        for (other in r.others.take(MAX_OTHER_LINES)) {
            y += font.lineHeight + 2
            if (y > h - font.lineHeight - 4) break
            val price = if (other.price > 0) " §8- §6${CroesusRules.money(other.price)}" else ""
            graphics.centeredText(font, Component.literal("§7+ §f${other.name}$price"), cx, y, white)
        }
        if (r.screen == null || text < 1.0 || y > h - font.lineHeight * 2) return
        graphics.centeredText(font, Component.literal("§8Click to continue"), cx, y + font.lineHeight + 6, white)
    }

    /** The lore of the item without its closing rarity line, which the reveal shows on its own. */
    private fun descriptionLines(stack: ItemStack, rarityName: String): List<Component> {
        val lore = stack.get(DataComponents.LORE)?.lines().orEmpty()
        val last = lore.indexOfLast { it.string.isNotBlank() }
        return if (last >= 0 && lore[last].string.trim().startsWith(rarityName, ignoreCase = true)) lore.subList(0, last) else lore
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
        private const val MAX_ROLLED_CONTENTS = 32
        private const val ROLLED_TTL_MS = 3L * 60L * 60L * 1000L
        private const val PITCH = 48.0
        private const val BAND_HEIGHT = 64
        private const val WINNER_BASE = 38
        private const val WINNER_SPREAD = 8
        private const val TAIL_CARDS = 8
        private const val PLAYER_SLOTS = 36
        private const val MAX_OPENED_SLOT = 40
        private const val MAX_RARITY = 5
        private const val MAX_OTHER_LINES = 6
        private const val LAST_CARDS = 3
        private const val SHUTTER_FRACTION = 0.3
        private const val BURST_FRACTION = 0.3
        private const val REVEAL_START_SCALE = 0.3
        private const val REVEAL_SCALE = 3.5
        private const val ITEM_CENTER_Y = 0.27
        private const val NAME_SCALE = 1.5f
        private const val MAX_LORE_LINES = 12
        private const val LORE_GAP = 4
        private const val TEST_PRICE = 25_000_000.0

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
