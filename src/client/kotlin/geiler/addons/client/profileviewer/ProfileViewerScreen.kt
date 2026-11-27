package geiler.addons.client.profileviewer

import geiler.addons.client.module.impl.rarityItem
import geiler.addons.client.dungeon.DungeonStatsServiceCore
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.module.impl.PriceDisplayModule
import geiler.addons.client.module.impl.ProfileViewerModule
import geiler.addons.client.pricing.NetworthSupport
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.PlayerSkinWidget
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import org.lwjgl.glfw.GLFW
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * `/pv`: a SkyBlock profile viewer (page tabs on top, profile picker, name box and SkyCrypt link under the panel), drawn
 * with this mod's theme. Data from the same profile proxy Party Finder Stats uses; one lookup per name is reused for
 * [CACHE_MS]. Item containers are decoded off the client thread right after a profile is chosen.
 */
class ProfileViewerScreen(private val parent: Screen?, private var requested: String) : Screen(Component.literal("Profile Viewer")) {
    private enum class Page(val title: String, val icon: () -> ItemStack) {
        BASIC("Basic Info", { ItemStack(Items.PLAYER_HEAD) }),
        DUNGEONS("Dungeons", { ItemStack(Items.WITHER_SKELETON_SKULL) }),
        INVENTORIES("Inventories", { ItemStack(Items.CHEST) }),
        WARDROBE("Wardrobe and Loadouts", { ItemStack(Items.LEATHER_CHESTPLATE) }),
        COLLECTIONS("Collections", { ItemStack(Items.PAINTING) }),
        PETS("Pets", { ItemStack(Items.BONE) }),
        MINING("Mining", { ItemStack(Items.IRON_PICKAXE) }),
        FORAGING("Foraging", { ItemStack(Items.OAK_SAPLING) }),
        HUNTING("Hunting and Attributes", { ItemStack(Items.LEAD) }),
        FARMING("Farming", { ItemStack(Items.GOLDEN_HOE) }),
        FISHING("Fishing", { ItemStack(Items.FISHING_ROD) }),
        CRIMSON("Crimson Isle", { ItemStack(Items.NETHERRACK) }),
        RIFT("Rift", { ItemStack(Items.ENDER_EYE) }),
        EXTRA("Slayers", { ItemStack(Items.ROTTEN_FLESH) }),
        BESTIARY("Bestiary", { ItemStack(Items.WRITABLE_BOOK) }),
        MINIONS("Minions", { ItemStack(Items.COBBLESTONE) }),
        ACCESSORIES("Missing Accessories", { ItemStack(Items.EMERALD) }),
        MUSEUM("Museum", { ItemStack(Items.GOLD_BLOCK) }),
        MISC("Misc", { ItemStack(Items.BOOK) }),
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
    }

    /** One profile lookup, kept for [CACHE_MS] so reopening `/pv` is instant. */
    private class Loaded(val name: String, val uuid: String, val profiles: List<PvProfile>, val at: Long)

    private var name = requested
    private var uuid: String? = null
    private var profiles: List<PvProfile> = emptyList()
    private var current: PvProfile? = null
    private var status: String? = "Loading $requested..."
    private var page = Page.BASIC
    private var dropdown = false
    private var container = "inv_contents"
    private var scroll = 0
    private var accessoryPage = 0
    private var category = 0
    private var head = ItemStack(Items.PLAYER_HEAD)
    private var skinFor: String? = null
    private var skin: PlayerSkinWidget? = null
    private val items = HashMap<String, List<ItemStack>>()
    private val pending = HashSet<String>()
    private var worth: PvNetworth.Result? = null
    private var worthMuseumTried = false
    private var ownedIds: Set<String>? = null
    private val targets = ArrayList<Pair<Rect, () -> Unit>>()
    private lateinit var nameBox: EditBox
    private lateinit var filterBox: EditBox
    private var tooltipFallbackLogged = false

    override fun isPauseScreen(): Boolean = false

    private fun panel(): Rect {
        val w = (width - 40).coerceIn(W, W_MAX)
        val h = (height - 90).coerceIn(H, H_MAX)
        return Rect((width - w) / 2, max(30, (height - h) / 2), w, h)
    }

    override fun init() {
        val p = panel()
        nameBox = EditBox(font, p.x + 106, p.bottom + 5, 100, 16, Component.literal("Player"))
        nameBox.setMaxLength(16)
        nameBox.setValue(name)
        addRenderableWidget(nameBox)
        val filterText = if (::filterBox.isInitialized) filterBox.value else ""
        filterBox = EditBox(font, p.right - 128, p.y + 4, 120, 12, Component.literal("Filter"))
        filterBox.setMaxLength(32)
        filterBox.setHint(Component.literal("§8Filter..."))
        filterBox.setValue(filterText)
        filterBox.setResponder { scroll = 0 }
        addRenderableWidget(filterBox)
        skin?.let { removeWidget(it) }
        skin = null
        skinFor = null
        if (uuid == null && status?.startsWith("Loading") == true && profiles.isEmpty()) load(requested)
    }

    private fun load(player: String) {
        requested = player
        name = player
        profiles = emptyList()
        current = null
        uuid = null
        CACHE[player.lowercase(Locale.ROOT)]?.takeIf { System.currentTimeMillis() - it.at < CACHE_MS }?.let {
            status = null
            ProfileViewerModule.INSTANCE.onCached(it.profiles.size)
            applyLoaded(it)
            return
        }
        status = "Loading $player..."
        ProfileViewerModule.INSTANCE.onLookup()
        val self = minecraft.player?.gameProfile?.takeIf { it.name().equals(player, ignoreCase = true) }?.id()?.toString()?.replace("-", "")
        val started = System.currentTimeMillis()
        DungeonStatsServiceCore.fetchProfiles(player, self) { result ->
            if (minecraft.gui.screen() !== this || !requested.equals(player, ignoreCase = true)) return@fetchProfiles
            val root = result.root
            val id = result.uuid
            if (root == null || id == null) {
                status = result.error ?: "Lookup failed"
                ProfileViewerModule.INSTANCE.onResult(false, 0, status, System.currentTimeMillis() - started)
                return@fetchProfiles
            }
            val parsed = PvParser.profiles(root, id)
            ProfileViewerModule.INSTANCE.onResult(parsed.isNotEmpty(), parsed.size, null, System.currentTimeMillis() - started)
            val loaded = Loaded(result.name, id, parsed, System.currentTimeMillis())
            if (CACHE.size >= MAX_CACHED) CACHE.entries.removeIf { System.currentTimeMillis() - it.value.at >= CACHE_MS }
            if (CACHE.size < MAX_CACHED) CACHE[player.lowercase(Locale.ROOT)] = loaded
            applyLoaded(loaded)
        }
    }

    private fun applyLoaded(loaded: Loaded) {
        name = loaded.name
        uuid = loaded.uuid
        profiles = loaded.profiles
        PvNames.put(loaded.uuid, loaded.name)
        if (profiles.isEmpty()) {
            status = "$name has no SkyBlock profiles"
            return
        }
        status = null
        head = ItemStack(Items.PLAYER_HEAD).also { it.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(parseUuid(loaded.uuid))) }
        select(PvParser.selected(profiles))
    }

    private fun select(profile: PvProfile?) {
        current = profile
        items.clear()
        pending.clear()
        worth = null
        worthMuseumTried = false
        ownedIds = null
        scroll = 0
        accessoryPage = 0
        container = "inv_contents"
        if (profile != null) {
            computeNetworth(profile)
            computeOwned(profile)
            // Decode every container now so the item pages open without waiting.
            for (key in profile.inventories.keys) stacks(profile, key)
        }
        PvConstants.requestAll()
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        targets.clear()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val p = panel()
        drawTabs(graphics, p, mouseX, mouseY)
        GuiTheme.roundedRectBordered(graphics, p.x, p.y, p.w, p.h, GuiTheme.RADIUS_SMALL, GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        val profile = current
        filterBox.visible = profile != null && (page == Page.PETS || page == Page.HUNTING || (page == Page.INVENTORIES && container in STORAGE_KEYS))
        PvPages.bar = null
        skin?.visible = profile != null && page == Page.BASIC
        graphics.text(font, "§l${page.title}", p.x + 8, p.y + 6, GuiTheme.TEXT_PRIMARY, false)
        if (profile == null) {
            // The frame, tabs and name show at once; the content fills in when the lookup answers.
            graphics.centeredText(font, status ?: "", p.x + p.w / 2, p.y + p.h / 2 - 4, GuiTheme.TEXT_SECONDARY)
        } else {
            when (page) {
                Page.BASIC -> drawBasic(graphics, p, profile, mouseX, mouseY)
                Page.DUNGEONS -> drawDungeons(graphics, p, profile, mouseX, mouseY)
                Page.INVENTORIES -> drawInventories(graphics, p, profile, mouseX, mouseY)
                Page.WARDROBE -> drawWardrobe(graphics, p, profile, mouseX, mouseY)
                Page.COLLECTIONS -> drawCollections(graphics, p, profile, mouseX, mouseY)
                Page.PETS -> drawPets(graphics, p, profile, mouseX, mouseY)
                Page.EXTRA -> drawExtra(graphics, p, profile)
                else -> {
                    val area = PvPages.Area(graphics, font, p.x + 8, p.y + 20, p.w - 16, p.h - 26, mouseX, mouseY)
                    when (page) {
                        Page.MINING -> PvPages.mining(area, profile)
                        Page.FORAGING -> PvPages.foraging(area, profile)
                        Page.HUNTING -> scroll = PvPages.hunting(area, profile, scroll, filterBox.value)
                        Page.FARMING -> PvPages.farming(area, profile)
                        Page.MUSEUM -> PvPages.museum(area, profile, uuid ?: "")
                        Page.FISHING -> PvPages.fishing(area, profile)
                        Page.CRIMSON -> PvPages.crimson(area, profile)
                        Page.RIFT -> PvPages.rift(area, profile)
                        Page.BESTIARY -> scroll = PvPages.bestiary(area, profile, scroll)
                        Page.MINIONS -> PvPages.minions(area, profile)
                        Page.ACCESSORIES -> PvPages.accessories(area, profile, ownedIds)
                        else -> PvPages.misc(area, profile)
                    }
                }
            }
        }
        drawBottom(graphics, p, mouseX, mouseY)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawTabs(graphics: GuiGraphicsExtractor, p: Rect, mouseX: Int, mouseY: Int) {
        val tabW = min(TAB, p.w / Page.entries.size)
        for ((i, tab) in Page.entries.withIndex()) {
            val r = Rect(p.x + i * tabW, p.y - TAB + 2, tabW - 2, TAB)
            val selected = tab == page
            val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
            graphics.fill(r.x, r.y, r.right, r.bottom, if (selected) GuiTheme.PANEL_TOP else if (hover) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.outline(r.x, r.y, r.w, r.h - if (selected) 2 else 0, if (selected) GuiTheme.PANEL_HIGHLIGHT else GuiTheme.BORDER)
            graphics.item(if (tab == Page.BASIC) head else tab.icon(), r.x + (r.w - 16) / 2, r.y + 5)
            if (hover) graphics.setTooltipForNextFrame(font, Component.literal(tab.title), mouseX, mouseY)
            targets.add(r to { if (page != tab) { page = tab; scroll = 0; click() } })
        }
    }

    private fun drawBottom(graphics: GuiGraphicsExtractor, p: Rect, mouseX: Int, mouseY: Int) {
        val y = p.bottom + 3
        val box = Rect(p.x, y, 100, 20)
        button(graphics, box, current?.let { "${it.cuteName} ▾" } ?: "Profile", mouseX, mouseY) { if (profiles.isNotEmpty()) dropdown = !dropdown }
        val sky = Rect(p.right - 100, y, 100, 20)
        button(graphics, sky, "Open SkyCrypt", mouseX, mouseY) {
            val url = "https://sky.shiiyu.moe/stats/$name" + (current?.let { "/${it.cuteName}" } ?: "")
            ConfirmLinkScreen.confirmLinkNow(this, url)
        }
        val load = Rect(p.x + 210, y, 40, 20)
        button(graphics, load, "View", mouseX, mouseY) { lookupBox() }
        if (dropdown) {
            for ((i, profile) in profiles.withIndex()) {
                val r = Rect(p.x, y + 22 + i * 14, 100, 14)
                val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
                graphics.fill(r.x, r.y, r.right, r.bottom, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
                val mode = if (profile.gameMode == "normal") "" else " §7(${profile.gameMode})"
                graphics.text(font, (if (profile === current) "§a" else "") + profile.cuteName + mode, r.x + 4, r.y + 3, GuiTheme.TEXT_PRIMARY, false)
                targets.add(0, r to { dropdown = false; select(profile); click() })
            }
        }
    }

    private fun button(graphics: GuiGraphicsExtractor, r: Rect, text: String, mouseX: Int, mouseY: Int, action: () -> Unit) {
        val hover = r.contains(mouseX.toDouble(), mouseY.toDouble())
        GuiTheme.roundedRect(graphics, r.x, r.y, r.w, r.h, GuiTheme.RADIUS_SMALL, if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
        graphics.centeredText(font, GuiTheme.fit(font, text, r.w - 6), r.x + r.w / 2, r.y + (r.h - 8) / 2, GuiTheme.TEXT_PRIMARY)
        targets.add(r to { click(); action() })
    }

    private fun hover(mouseX: Int, mouseY: Int, x: Int, y: Int, w: Int, h: Int) = mouseX in x until x + w && mouseY in y until y + h

    private fun tooltip(graphics: GuiGraphicsExtractor, lines: List<String>, mouseX: Int, mouseY: Int) =
        graphics.setTooltipForNextFrame(font, lines.map { Component.literal(it) }, java.util.Optional.empty(), mouseX, mouseY)

    /** The rotating 3D player model of the viewed player (vanilla skin widget; drag to turn it). */
    private fun skinWidget(p: Rect) {
        val id = uuid ?: return
        if (skinFor == id) return
        skin?.let { removeWidget(it) }
        val lookup = minecraft.playerSkinRenderCache().createLookup(ResolvableProfile.createUnresolved(parseUuid(id)))
        skin = addRenderableWidget(PlayerSkinWidget(70, 96, minecraft.entityModels) { lookup.get().playerSkin() }).also {
            it.setPosition(p.x + 22, p.y + 26)
        }
        skinFor = id
    }

    private fun drawBasic(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        val left = p.x + 8
        skinWidget(p)
        skin?.setPosition(p.x + 26, p.y + 26)
        graphics.text(font, "§e$name", left, p.y + 18, GuiTheme.TEXT_PRIMARY, false)
        var y = p.y + 126
        fun line(label: String, value: String, hoverLines: (() -> List<String>)? = null) {
            graphics.text(font, GuiTheme.fit(font, "§7$label: §f$value", 150), left, y, GuiTheme.TEXT_PRIMARY, false)
            if (hoverLines != null && hover(mouseX, mouseY, left, y - 1, 150, 11)) tooltip(graphics, hoverLines(), mouseX, mouseY)
            y += 11
        }
        val level = pr.skyblockLevel.toInt()
        line("SkyBlock Level", PvLevels.sbLevelColor(level) + level) { listOf("§7XP: §f" + n((pr.skyblockLevel * 100).toLong()), "§7Next level: §f${((pr.skyblockLevel - level) * 100).toInt()}/100") }
        line("Skill Avg", "§6" + String.format(Locale.ROOT, "%.2f", pr.skillAverage))
        line("Purse", "§6" + short(pr.purse)) { listOf("§6" + n(pr.purse.toLong()) + " coins") }
        line("Bank", pr.bank?.let { "§6" + short(it) } ?: "§8API off") { listOf(pr.bank?.let { "§6" + n(it.toLong()) + " coins" } ?: "§8Banking API off") }
        val w = worth
        museumForWorth(pr)
        line("Networth", w?.let { "§6" + short(it.total) } ?: if (!pr.inventoryApi) "§8Inventory API off" else if (PriceDisplayModule.INSTANCE.prices().bazaar == null) "§8needs Price Display" else "§8...") {
            if (w == null) listOf("§7Still adding up...") else networthLines(w)
        }
        line("Fairy Souls", pr.fairySouls.toString())
        val x0 = p.x + 170
        val colW = (p.w - 178) / 2
        for ((i, skill) in PvProfile.SKILLS.withIndex()) {
            val col = i / 6
            val row = i % 6
            val x = x0 + col * colW
            val sy = p.y + 22 + row * 29
            val level = pr.skillLevel(skill)
            val cap = pr.skillCap(skill)
            graphics.item(SKILL_ICONS[skill] ?: ItemStack(Items.BOOK), x, sy)
            graphics.text(font, "§f${title(skill)} §e${level.toInt()}", x + 20, sy + 1, GuiTheme.TEXT_PRIMARY, false)
            bar(graphics, x + 20, sy + 11, colW - 30, if (level >= cap) 1.0 else level - level.toInt(), if (level >= cap) MAX_COLOR else BAR_COLOR)
            if (hover(mouseX, mouseY, x, sy, colW - 8, 18)) tooltip(graphics, skillLines(skill, level, cap, pr.skillXp[skill] ?: 0L), mouseX, mouseY)
        }
    }

    private fun skillLines(skill: String, level: Double, cap: Int, xp: Long): List<String> {
        val whole = level.toInt()
        val lines = mutableListOf("§f${title(skill)} §e$whole§7/$cap", "§7Total XP: §f${n(xp)}")
        if (whole < cap) {
            val into = xp - PvLevels.skillXpFor(skill, whole)
            val need = PvLevels.skillXpFor(skill, whole + 1) - PvLevels.skillXpFor(skill, whole)
            lines.add("§7Next level: §f${n(into)}§7/§f${n(need)} §8(${String.format(Locale.ROOT, "%.1f", (level - whole) * 100)}%)")
        } else {
            lines.add("§6Maxed")
        }
        return lines
    }

    private fun networthLines(w: PvNetworth.Result): List<String> {
        val lines = mutableListOf("§6Networth", "§7Total: §6${n(w.total.toLong())}", "§7Without soulbound: §6${n(w.tradeable.toLong())}", "")
        w.categories.entries.sortedByDescending { it.value }.forEach { (name, value) -> lines.add("§7$name: §6${short(value)}") }
        if (!w.museumIncluded) lines.add(if (ProfileViewerModule.INSTANCE.gardenAndMuseum.value()) "§8Museum: loading..." else "§8Museum not counted (turn on Garden and Museum)")
        lines.add("")
        euroPerCoin()?.let { rate ->
            lines.add("§7In euro: §a" + String.format(Locale.ROOT, "%,.2f €", w.tradeable * rate))
            lines.add("§8(tradeable value at the Booster Cookie price)")
        } ?: lines.add("§8Euro needs the Booster Cookie price")
        return lines
    }

    /** Euro per coin from the live Booster Cookie price: one cookie is 325 gems, 675 gems cost 4.99 € in the store. */
    private fun euroPerCoin(): Double? {
        val prices = PriceDisplayModule.INSTANCE.prices()
        if (prices.bazaar == null) return null
        val cookie = NetworthSupport.priceOf(prices, "BOOSTER_COOKIE").takeIf { it > 0 } ?: return null
        return EURO_PER_GEM * COOKIE_GEMS / cookie
    }

    private fun drawDungeons(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        val left = p.x + 8
        var y = p.y + 22
        val cata = pr.catacombsLevel
        graphics.item(ItemStack(Items.WITHER_SKELETON_SKULL), left, y)
        graphics.text(font, "§fCatacombs §e" + String.format(Locale.ROOT, "%.2f", cata), left + 20, y + 1, GuiTheme.TEXT_PRIMARY, false)
        bar(graphics, left + 20, y + 11, 120, if (cata >= 50) 1.0 else cata - cata.toInt(), if (cata >= 50) MAX_COLOR else BAR_COLOR)
        if (hover(mouseX, mouseY, left, y, 145, 18)) {
            val progress = DungeonProgress.of(pr.catacombsXp)
            tooltip(graphics, listOf("§fCatacombs §e" + String.format(Locale.ROOT, "%.2f", cata), "§7Catacombs XP: §f${n(pr.catacombsXp)}",
                "§7To level ${cata.toInt() + 1}: §f${n(progress.into)}§7/§f${n(progress.need)} §8(${progress.percentText()})",
                "§7Class XP (all classes): §f${n(pr.classXp.values.sum())}", "§7Class average: §e" + String.format(Locale.ROOT, "%.2f",
                    PvProfile.CLASSES.sumOf { pr.classLevel(it) } / PvProfile.CLASSES.size)), mouseX, mouseY)
        }
        y += 26
        graphics.text(font, "§7Secrets: §f${n(pr.secrets)}", left, y, GuiTheme.TEXT_PRIMARY, false)
        y += 11
        graphics.text(font, "§7Secrets per run: §f" + String.format(Locale.ROOT, "%.2f", PvProfile.secretAverage(pr.secrets, pr.totalRuns)), left, y, GuiTheme.TEXT_PRIMARY, false)
        y += 11
        graphics.text(font, "§7Selected: §f${pr.selectedClass?.let(::title) ?: "?"}", left, y, GuiTheme.TEXT_PRIMARY, false)
        y += 14
        for (cls in PvProfile.CLASSES) {
            val level = pr.classLevel(cls)
            val xp = pr.classXp[cls] ?: 0L
            val mark = if (cls == pr.selectedClass) "§a" else "§f"
            val progress = DungeonProgress.of(xp)
            graphics.text(font, "$mark${title(cls)} §e${level.toInt()}", left, y, GuiTheme.TEXT_PRIMARY, false)
            val right = "§b${progress.percentText()} §8${short(progress.left.toDouble())} left"
            graphics.text(font, right, left + 150 - font.width(right), y, GuiTheme.TEXT_PRIMARY, false)
            bar(graphics, left, y + 10, 150, progress.fraction, if (level >= 50) MAX_COLOR else BAR_COLOR)
            if (hover(mouseX, mouseY, left, y, 152, 16)) tooltip(graphics, listOf("§f${title(cls)} §e" + String.format(Locale.ROOT, "%.2f", level),
                "§7Total XP: §f${n(xp)}", "§7To level ${level.toInt() + 1}: §f${n(progress.into)}§7/§f${n(progress.need)} §8(${progress.percentText()})",
                "§7XP left: §f${n(progress.left)}"), mouseX, mouseY)
            y += 18
        }
        val x = p.x + 176
        val cols = intArrayOf(0, 40, 96, 160, 210)
        listOf("Floor", "Comps", "S+", "Master", "S+").forEachIndexed { i, h -> graphics.text(font, "§7$h", x + cols[i], p.y + 22, GuiTheme.TEXT_MUTED, false) }
        val rowH = min(18, (p.h - 40) / 8)
        for (floor in 0..7) {
            val ry = p.y + 36 + floor * rowH
            if (floor % 2 == 0) graphics.fill(x - 4, ry - 4, p.right - 8, ry + rowH - 6, GuiTheme.CARD_BG)
            graphics.text(font, "§fF$floor", x + cols[0], ry, GuiTheme.TEXT_PRIMARY, false)
            graphics.text(font, (pr.completions[floor] ?: 0).toString(), x + cols[1], ry, GuiTheme.TEXT_PRIMARY, false)
            graphics.text(font, time(pr.fastestSPlus[floor]), x + cols[2], ry, GuiTheme.TEXT_SECONDARY, false)
            if (floor > 0) {
                graphics.text(font, (pr.masterCompletions[floor] ?: 0).toString(), x + cols[3], ry, GuiTheme.TEXT_PRIMARY, false)
                graphics.text(font, time(pr.masterFastestSPlus[floor]), x + cols[4], ry, GuiTheme.TEXT_SECONDARY, false)
            }
        }
    }

    private fun drawInventories(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        if (!pr.inventoryApi) {
            graphics.centeredText(font, "§cThis player has the inventory API turned off.", p.x + p.w / 2, p.y + p.h / 2, GuiTheme.TEXT_PRIMARY)
            return
        }
        val keys = CONTAINERS.filter { (key, _) ->
            key == "armor" || pr.inventories.containsKey(key) ||
                (key == ENDER && pr.inventories.containsKey("ender_chest_contents")) || (key == BACKPACKS && backpackKeys(pr).isNotEmpty())
        }
        if (keys.none { it.first == container }) container = "inv_contents"
        for ((i, entry) in keys.withIndex()) {
            val r = Rect(p.x + 8, p.y + 20 + i * 18, 18, 18)
            graphics.fill(r.x, r.y, r.right, r.bottom, if (entry.first == container) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
            graphics.rarityItem(entry.second(), r.x + 1, r.y + 1)
            if (r.contains(mouseX.toDouble(), mouseY.toDouble())) graphics.setTooltipForNextFrame(font, Component.literal(CONTAINER_NAMES[entry.first] ?: entry.first), mouseX, mouseY)
            targets.add(r to { container = entry.first; scroll = 0; accessoryPage = 0; click() })
        }
        if (container in STORAGE_KEYS) {
            drawStorage(graphics, Rect(p.x + 30, p.y + 20, p.w - 36, p.h - 24), pr, container == ENDER, mouseX, mouseY)
            return
        }
        val gridTop = p.y + 22
        val gridLeft = p.x + 36
        val list = if (container == "armor") armor(pr) else stacks(pr, container)
        if (list == null) {
            graphics.text(font, "§7Loading items...", gridLeft, gridTop, GuiTheme.TEXT_PRIMARY, false)
            return
        }
        val ordered = if (container == "inv_contents" && list.size >= 36) list.subList(9, 36) + list.subList(0, 9) else list
        val visibleRows = (p.bottom - 6 - gridTop) / SLOT
        if (container == "talisman_bag") {
            // Paged like the bag in game: one page of up to 5 rows, arrows to flip.
            val perPage = 9 * min(5, visibleRows)
            val pages = max(1, (ordered.size + perPage - 1) / perPage)
            accessoryPage = accessoryPage.coerceIn(0, pages - 1)
            drawGrid(graphics, ordered.drop(accessoryPage * perPage).take(perPage), gridLeft, gridTop, 9, 0, perPage / 9, mouseX, mouseY)
            val nx = gridLeft + 9 * SLOT + 8
            graphics.text(font, "§7Page §f${accessoryPage + 1}§7/$pages", nx, gridTop, GuiTheme.TEXT_PRIMARY, false)
            button(graphics, Rect(nx, gridTop + 12, 20, 16), "<", mouseX, mouseY) { accessoryPage = max(0, accessoryPage - 1) }
            button(graphics, Rect(nx + 24, gridTop + 12, 20, 16), ">", mouseX, mouseY) { accessoryPage = min(pages - 1, accessoryPage + 1) }
            graphics.text(font, "§7Accessories: §f${ordered.count { !it.isEmpty }}", nx, gridTop + 34, GuiTheme.TEXT_PRIMARY, false)
            graphics.text(font, "§7Magical Power: §6${pr.sections.magicalPower}", nx, gridTop + 45, GuiTheme.TEXT_PRIMARY, false)
            return
        }
        val rows = (ordered.size + 8) / 9
        scroll = scroll.coerceIn(0, max(0, rows - visibleRows))
        drawGrid(graphics, ordered, gridLeft, gridTop, 9, scroll, visibleRows, mouseX, mouseY)
        PvPages.scrollbar(graphics, gridLeft + 9 * SLOT + 3, gridTop, visibleRows * SLOT, scroll, visibleRows, rows)
    }

    /**
     * Ender Chest pages ([ender]) or backpacks as storage cards (title, 9 wide slot grid) inside [view], filterable,
     * centred with a fixed gap, scrolled in pixels.
     */
    private fun drawStorage(graphics: GuiGraphicsExtractor, view: Rect, pr: PvProfile, ender: Boolean, mouseX: Int, mouseY: Int) {
        val cards = ArrayList<Pair<String, List<ItemStack>?>>()
        if (ender) {
            val pages = stacks(pr, "ender_chest_contents")
            if (pages == null) cards.add("Ender Chest" to null)
            else pages.chunked(45).forEachIndexed { i, page -> if (page.any { !it.isEmpty } || i == 0) cards.add("Page ${i + 1}" to page) }
        } else {
            for (key in backpackKeys(pr)) cards.add("Backpack ${(key.removePrefix("backpack_").toIntOrNull() ?: 0) + 1}" to stacks(pr, key))
        }
        val filter = filterBox.value.trim().lowercase(Locale.ROOT)
        val cardW = 9 * SLOT + 2 * EDGE
        val gap = 6
        val perRow = max(1, (view.w - 6 + gap) / (cardW + gap))
        val left = view.x + (view.w - 6 - (perRow * cardW + (perRow - 1) * gap)) / 2
        val heights = cards.map { (_, list) -> 18 + ((list?.size ?: 9) + 8) / 9 * SLOT + EDGE - 1 }
        val rowTops = ArrayList<Int>()
        var total = 0
        for (row in heights.chunked(perRow)) {
            rowTops.add(total)
            total += (row.maxOrNull() ?: 0) + gap
        }
        val maxScroll = max(0, total - view.h)
        scroll = scroll.coerceIn(0, maxScroll / SCROLL_PX + 1)
        val offset = min(scroll * SCROLL_PX, maxScroll)
        var hovered: ItemStack? = null
        graphics.enableScissor(view.x, view.y, view.right, view.bottom)
        for ((i, card) in cards.withIndex()) {
            val cx = left + (i % perRow) * (cardW + gap)
            val cy = view.y + rowTops[i / perRow] - offset
            val ch = heights[i]
            if (cy + ch < view.y || cy > view.bottom) continue
            InventorySurfaces.card(graphics, cx, cy, cardW, ch, false, false)
            graphics.text(font, card.first, cx + 8, cy + 6, GuiTheme.TEXT_PRIMARY, false)
            val list = card.second
            if (list == null) {
                graphics.text(font, "§7Loading...", cx + 8, cy + 20, GuiTheme.TEXT_PRIMARY, false)
                continue
            }
            for ((j, stack) in list.withIndex()) {
                val x = cx + EDGE + 1 + (j % 9) * SLOT
                val y = cy + 18 + (j / 9) * SLOT
                InventorySurfaces.slot(graphics, x, y, false)
                if (stack.isEmpty) continue
                graphics.rarityItem(stack, x, y)
                graphics.itemDecorations(font, stack, x, y)
                if (filter.isNotEmpty() && !matches(stack, filter)) graphics.fill(x, y, x + 16, y + 16, NOT_MATCHED)
                if (view.contains(mouseX.toDouble(), mouseY.toDouble()) && hover(mouseX, mouseY, x - 1, y - 1, 18, 18)) hovered = stack
            }
        }
        graphics.disableScissor()
        PvPages.scrollbar(graphics, view.right - 3, view.y, view.h, offset, view.h, total, SCROLL_PX)
        hovered?.let { itemTooltip(graphics, it, mouseX, mouseY) }
        if (cards.isEmpty()) graphics.text(font, if (ender) "§7No Ender Chest." else "§7No backpacks.", view.x + 4, view.y + 4, GuiTheme.TEXT_PRIMARY, false)
    }

    private fun matches(stack: ItemStack, filter: String): Boolean =
        stack.hoverName.string.lowercase(Locale.ROOT).contains(filter) ||
            (stack.get(DataComponents.LORE)?.lines()?.any { it.string.lowercase(Locale.ROOT).contains(filter) } ?: false)

    /** Wardrobe armor sets (one column each), equipment sets and named loadouts, from the profile's `loadout` data. */
    private fun drawWardrobe(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        if (!pr.inventoryApi) {
            graphics.centeredText(font, "§cThis player has the inventory API turned off.", p.x + p.w / 2, p.y + p.h / 2, GuiTheme.TEXT_PRIMARY)
            return
        }
        val view = Rect(p.x + 8, p.y + 20, p.w - 18, p.h - 24)
        val perRow = max(1, (view.w - 4) / (SLOT + 4))
        val legacy = stacks(pr, "wardrobe_contents").takeIf { pr.inventories.containsKey("wardrobe_contents") }
        val sections = ArrayList<Triple<String, List<Int>, (Int) -> List<ItemStack>?>>()
        if (pr.armorSets.isNotEmpty()) sections.add(Triple("Wardrobe", pr.armorSets) { id -> pieces(pr, ARMOR.map { "wardrobe_${id}_$it" }) })
        else if (legacy != null) sections.add(Triple("Wardrobe", (0 until legacy.size / 36 * 9).toList()) { slot -> legacyWardrobe(legacy, slot) })
        if (pr.equipmentSets.isNotEmpty()) sections.add(Triple("Equipment", pr.equipmentSets) { id -> pieces(pr, (1..4).map { "equipment_${id}_$it" }) })
        var y = view.y - scroll * SCROLL_PX
        var hovered: ItemStack? = null
        val top = y
        graphics.enableScissor(view.x, view.y, view.right, view.bottom)
        for ((label, ids, pieces) in sections) {
            graphics.text(font, "§e§l$label §7(${ids.size})", view.x, y, GuiTheme.TEXT_PRIMARY, false)
            y += 11
            for (chunk in ids.chunked(perRow)) {
                for ((i, id) in chunk.withIndex()) {
                    val x = view.x + i * (SLOT + 4)
                    val list = pieces(id)
                    for (row in 0 until 4) {
                        val sy = y + row * SLOT
                        InventorySurfaces.slot(graphics, x + 1, sy + 1, false)
                        val stack = list?.getOrNull(row) ?: continue
                        if (stack.isEmpty) continue
                        graphics.rarityItem(stack, x + 1, sy + 1)
                        if (view.contains(mouseX.toDouble(), mouseY.toDouble()) && hover(mouseX, mouseY, x, sy, SLOT, SLOT)) hovered = stack
                    }
                    graphics.centeredText(font, "§8${if (label == "Wardrobe" && pr.armorSets.isEmpty()) id + 1 else id}", x + SLOT / 2, y + 4 * SLOT + 1, GuiTheme.TEXT_MUTED)
                }
                y += 4 * SLOT + 12
            }
            y += 4
        }
        if (pr.loadouts.isNotEmpty()) {
            graphics.text(font, "§e§lLoadouts §7(${pr.loadouts.size})", view.x, y, GuiTheme.TEXT_PRIMARY, false)
            y += 12
            for (l in pr.loadouts) {
                graphics.fill(view.x, y - 1, view.right - 4, y + SLOT, GuiTheme.CARD_BG)
                graphics.text(font, GuiTheme.fit(font, "§f${l.name}", 80), view.x + 4, y + 5, GuiTheme.TEXT_PRIMARY, false)
                var x = view.x + 88
                val armor = l.armorSet?.let { id -> ARMOR.map { piece(pr, "wardrobe_${id}_$it") ?: ItemStack.EMPTY } } ?: emptyList()
                val equipment = l.equipmentSet?.let { id -> (1..4).map { piece(pr, "equipment_${id}_$it") ?: ItemStack.EMPTY } } ?: emptyList()
                for (stack in armor + listOf(ItemStack.EMPTY) + equipment) {
                    if (!stack.isEmpty) {
                        graphics.rarityItem(stack, x, y)
                        if (view.contains(mouseX.toDouble(), mouseY.toDouble()) && hover(mouseX, mouseY, x, y, 17, 17)) hovered = stack
                    }
                    x += 17
                }
                val pet = l.pet?.let { id -> pr.pets.firstOrNull { it.uuid == id } }
                if (pet != null) {
                    x += 6
                    graphics.rarityItem(icon("${pet.type};${RARITIES.indexOf(pet.tier).coerceAtLeast(0)}"), x, y)
                    if (hover(mouseX, mouseY, x, y, 17, 17)) tooltip(graphics, listOf("${RARITY_COLORS[pet.tier] ?: "§f"}[Lvl ${pet.level}] ${title(pet.type)}"), mouseX, mouseY)
                    x += 20
                }
                l.powerStone?.let { graphics.text(font, GuiTheme.fit(font, "§7Power: §f${title(it)}", view.right - x - 6), x + 4, y + 5, GuiTheme.TEXT_PRIMARY, false) }
                y += SLOT + 3
            }
        }
        graphics.disableScissor()
        if (sections.isEmpty() && pr.loadouts.isEmpty()) graphics.text(font, "§7No wardrobe or loadout data.", view.x, view.y + 4, GuiTheme.TEXT_PRIMARY, false)
        val content = y - top
        val maxRows = max(0, (content - view.h + SCROLL_PX - 1) / SCROLL_PX)
        if (scroll > maxRows) scroll = maxRows
        PvPages.scrollbar(graphics, view.right + 2, view.y, view.h, scroll * SCROLL_PX, view.h, content, SCROLL_PX)
        hovered?.let { itemTooltip(graphics, it, mouseX, mouseY) }
    }

    /** One piece of a loadout set: the first item of its container, EMPTY when absent, null while decoding. */
    private fun piece(pr: PvProfile, key: String): ItemStack? {
        if (!pr.inventories.containsKey(key)) return ItemStack.EMPTY
        return stacks(pr, key)?.firstOrNull() ?: ItemStack.EMPTY.takeIf { items.containsKey(key) }
    }

    /** The pieces of one set, or null while any of them is still decoding. */
    private fun pieces(pr: PvProfile, keys: List<String>): List<ItemStack>? {
        val out = keys.map { piece(pr, it) }
        return if (out.any { it == null }) null else out.map { it ?: ItemStack.EMPTY }
    }

    /** Old `wardrobe_contents`: pages of 36 slots, four armor rows of nine sets each. */
    private fun legacyWardrobe(list: List<ItemStack>, slot: Int): List<ItemStack> {
        val page = slot / 9
        return (0 until 4).map { row -> list.getOrNull(page * 36 + row * 9 + slot % 9) ?: ItemStack.EMPTY }
    }

    /** Collections by category tab (co-op totals); hover shows each member's share and the next tier. */
    private fun drawCollections(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        if (pr.collections.isEmpty()) {
            graphics.text(font, "§7No collection data (Collections API off).", p.x + 8, p.y + 22, GuiTheme.TEXT_PRIMARY, false)
            return
        }
        val categories = PvConstants.get("collections")?.let(PvCollections::parse) ?: emptyList()
        val known = categories.flatMap { c -> c.items.map { it.id } }.toSet()
        val tabs = categories.map { it.name to it.items } +
            listOf("Other" to pr.collections.keys.filter { it !in known }.map { PvCollections.Item(it, title(it), emptyList()) }).filter { it.second.isNotEmpty() }
        if (tabs.isEmpty()) {
            graphics.text(font, "§7Loading collection categories... (${PvConstants.status})", p.x + 8, p.y + 22, GuiTheme.TEXT_PRIMARY, false)
            return
        }
        category = category.coerceIn(0, tabs.size - 1)
        var tx = p.x + 8
        for ((i, tab) in tabs.withIndex()) {
            val w = font.width(tab.first) + 10
            val r = Rect(tx, p.y + 18, w, 14)
            GuiTheme.roundedRect(graphics, r.x, r.y, r.w, r.h, GuiTheme.RADIUS_SMALL, if (i == category) GuiTheme.CATEGORY_SELECTED else if (r.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG)
            graphics.text(font, tab.first, r.x + 5, r.y + 3, GuiTheme.TEXT_PRIMARY, false)
            targets.add(r to { if (category != i) { category = i; scroll = 0; click() } })
            tx += w + 3
        }
        val list = tabs[category].second.sortedByDescending { pr.collections[it.id] ?: 0L }
        val area = Rect(p.x + 8, p.y + 36, p.w - 20, p.h - 40)
        val cols = 3
        val cw = area.w / cols
        val rowH = 22
        val visible = area.h / rowH
        val rows = (list.size + cols - 1) / cols
        scroll = scroll.coerceIn(0, max(0, rows - visible))
        for (i in scroll * cols until min(list.size, (scroll + visible) * cols)) {
            val item = list[i]
            val amount = pr.collections[item.id] ?: 0L
            val x = area.x + (i % cols) * cw
            val y = area.y + (i / cols - scroll) * rowH
            val tier = item.tier(amount)
            val maxed = item.tiers.isNotEmpty() && tier >= item.tiers.size
            graphics.fill(x, y, x + cw - 3, y + rowH - 2, if (maxed) MAXED_BG else GuiTheme.CARD_BG)
            graphics.rarityItem(icon(item.id), x + 2, y + 2)
            val tierText = if (item.tiers.isEmpty()) "" else (if (maxed) " §6" else " §e") + roman(tier)
            graphics.text(font, GuiTheme.fit(font, "§f${item.name}$tierText", cw - 26), x + 21, y + 2, GuiTheme.TEXT_PRIMARY, false)
            graphics.text(font, "§7" + n(amount), x + 21, y + 11, GuiTheme.TEXT_PRIMARY, false)
            if (hover(mouseX, mouseY, x, y, cw - 3, rowH)) {
                val lines = mutableListOf("§f${item.name}" + if (item.tiers.isEmpty()) "" else " §7tier §e$tier§7/${item.tiers.size}", "§7Co-op total: §f${n(amount)}")
                item.tiers.getOrNull(tier)?.let { lines.add("§7Next tier at §f${n(it)} §8(${n(it - amount)} to go)") }
                val members = pr.collectionsByMember[item.id] ?: emptyMap()
                if (members.size > 1 || pr.memberIds.size > 1) {
                    lines.add("")
                    members.entries.sortedByDescending { it.value }.forEach { (member, value) -> lines.add("§7${PvNames.get(member)}: §f${n(value)}") }
                }
                tooltip(graphics, lines, mouseX, mouseY)
            }
        }
        PvPages.scrollbar(graphics, area.right + 2, area.y, area.h, scroll, visible, rows)
    }

    private fun drawPets(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile, mouseX: Int, mouseY: Int) {
        val filter = filterBox.value.trim().lowercase(Locale.ROOT)
        val pets = if (filter.isEmpty()) pr.pets else pr.pets.filter {
            title(it.type).lowercase(Locale.ROOT).contains(filter) || it.tier.lowercase(Locale.ROOT).contains(filter) ||
                (it.heldItem?.lowercase(Locale.ROOT)?.contains(filter) ?: false)
        }
        val gridW = p.w - 22
        val cols = gridW / PET_W
        val visibleRows = (p.h - 26) / PET_H
        val rows = (pets.size + cols - 1) / cols
        scroll = scroll.coerceIn(0, max(0, rows - visibleRows))
        if (pets.isEmpty()) graphics.text(font, if (pr.pets.isEmpty()) "§7No pets." else "§7No pet matches the filter.", p.x + 8, p.y + 22, GuiTheme.TEXT_PRIMARY, false)
        graphics.text(font, "§7${pets.size} pets", p.x + 8 + font.width("§lPets") + 8, p.y + 6, GuiTheme.TEXT_MUTED, false)
        for (i in scroll * cols until min(pets.size, (scroll + visibleRows) * cols)) {
            val pet = pets[i]
            val x = p.x + 8 + (i % cols) * PET_W
            val y = p.y + 20 + (i / cols - scroll) * PET_H
            graphics.fill(x, y, x + PET_W - 2, y + PET_H - 2, if (pet.active) GuiTheme.CATEGORY_SELECTED else GuiTheme.CARD_BG)
            val icon = icon("${pet.type};${RARITIES.indexOf(pet.tier).coerceAtLeast(0)}")
            graphics.rarityItem(icon, x + (PET_W - 18) / 2, y + 2)
            graphics.centeredText(font, "§7${pet.level}", x + PET_W / 2 - 1, y + 19, GuiTheme.TEXT_PRIMARY)
            if (mouseX in x until x + PET_W && mouseY in y until y + PET_H) {
                val lines = mutableListOf("${RARITY_COLORS[pet.tier] ?: "§f"}[Lvl ${pet.level}] ${title(pet.type)}", "§7XP: §f" + short(pet.exp.toDouble()))
                pet.heldItem?.let { lines.add("§7Held: §f" + title(it.removePrefix("PET_ITEM_"))) }
                if (pet.active) lines.add("§aActive")
                tooltip(graphics, lines, mouseX, mouseY)
            }
        }
        PvPages.scrollbar(graphics, p.right - 8, p.y + 20, visibleRows * PET_H, scroll, visibleRows, rows)
    }

    private fun drawExtra(graphics: GuiGraphicsExtractor, p: Rect, pr: PvProfile) {
        val left = p.x + 8
        val col0 = left + 250
        val colW = 30
        graphics.text(font, "§7Tier kills:", col0 - 60, p.y + 22, GuiTheme.TEXT_MUTED, false)
        // Numerals and counts share one centre per tier so they line up.
        listOf("I", "II", "III", "IV", "V").forEachIndexed { tier, numeral ->
            graphics.centeredText(font, "§7$numeral", col0 + tier * colW, p.y + 22, GuiTheme.TEXT_MUTED)
        }
        for ((i, boss) in PvProfile.SLAYERS.withIndex()) {
            val y = p.y + 34 + i * 26
            val xp = pr.slayerXp[boss] ?: 0L
            val level = PvLevels.slayer(boss, xp)
            graphics.item(SLAYER_ICONS[boss] ?: ItemStack(Items.BOOK), left, y)
            graphics.text(font, "§f${SLAYER_NAMES[boss]} §e$level§7/${PvLevels.slayerMax(boss)}", left + 20, y + 1, GuiTheme.TEXT_PRIMARY, false)
            graphics.text(font, "§7XP: §f" + short(xp.toDouble()), left + 20, y + 11, GuiTheme.TEXT_PRIMARY, false)
            val kills = pr.slayerKills[boss] ?: emptyList()
            kills.forEachIndexed { tier, count -> graphics.centeredText(font, "§f" + short(count.toDouble()), col0 + tier * colW, y + 5, GuiTheme.TEXT_SECONDARY) }
        }
    }

    private fun drawGrid(graphics: GuiGraphicsExtractor, list: List<ItemStack>, x0: Int, y0: Int, cols: Int, rowStart: Int, rows: Int, mouseX: Int, mouseY: Int) {
        for (i in rowStart * cols until min(list.size, (rowStart + rows) * cols)) {
            val x = x0 + (i % cols) * SLOT
            val y = y0 + (i / cols - rowStart) * SLOT
            graphics.fill(x, y, x + SLOT - 1, y + SLOT - 1, GuiTheme.CARD_BG)
            val stack = list[i]
            if (stack.isEmpty) continue
            graphics.rarityItem(stack, x + 1, y + 1)
            graphics.itemDecorations(font, stack, x + 1, y + 1)
            if (mouseX in x until x + SLOT && mouseY in y until y + SLOT) itemTooltip(graphics, stack, mouseX, mouseY)
        }
    }

    /** Item tooltip; a stack whose vanilla tooltip throws (registry data the client never bound) shows its name and lore. */
    private fun itemTooltip(graphics: GuiGraphicsExtractor, stack: ItemStack, mouseX: Int, mouseY: Int) {
        try {
            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
        } catch (e: RuntimeException) {
            if (!tooltipFallbackLogged) ProfileViewerModule.INSTANCE.onTooltipFallback(e.javaClass.simpleName)
            tooltipFallbackLogged = true
            val lines = mutableListOf<Component>(stack.hoverName)
            stack.get(DataComponents.LORE)?.lines()?.let(lines::addAll)
            graphics.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
        }
    }

    private fun bar(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, fraction: Double, color: Int) {
        graphics.fill(x, y, x + w, y + 4, GuiTheme.SLIDER_TRACK)
        graphics.fill(x, y, x + (w * fraction.coerceIn(0.0, 1.0)).toInt(), y + 4, color)
    }

    // ---- items ----------------------------------------------------------------------------------

    private fun backpackKeys(pr: PvProfile) =
        pr.inventories.keys.filter { it.startsWith("backpack_") && !it.startsWith("backpack_icon") }.sortedBy { it.removePrefix("backpack_").toIntOrNull() ?: 0 }

    /** Armor (helmet first) then equipment, in one row each. */
    private fun armor(pr: PvProfile): List<ItemStack>? {
        val armor = stacks(pr, "inv_armor") ?: if (pr.inventories.containsKey("inv_armor")) return null else emptyList()
        val equipment = stacks(pr, "equipment_contents") ?: if (pr.inventories.containsKey("equipment_contents")) return null else emptyList()
        val row = ArrayList<ItemStack>()
        row.addAll(armor.reversed())
        while (row.size < 9) row.add(ItemStack.EMPTY)
        row.addAll(equipment)
        return row
    }

    /** Decoded stacks of one container, built off the client thread on first use; null while building. */
    private fun stacks(pr: PvProfile, key: String): List<ItemStack>? {
        items[key]?.let { return it }
        val data = pr.inventories[key] ?: return emptyList()
        if (pending.add(key)) {
            val profile = pr
            CompletableFuture.supplyAsync({
                val tags = PvParser.decode(data) ?: emptyList()
                var failed = 0
                val built = tags.map { PvItems.stack(it).also { r -> if (!r.fixed) failed++ }.stack }
                ProfileViewerModule.INSTANCE.onItems(key, built.count { !it.isEmpty }, failed)
                built
            }, WORKER).thenAccept { built -> minecraft.execute { if (current === profile) items[key] = built } }
        }
        return null
    }

    /** SkyBlock ids of every accessory and stored item (for Missing Accessories), read off the client thread. */
    private fun computeOwned(pr: PvProfile) {
        if (!pr.inventoryApi) return
        val profile = pr
        CompletableFuture.supplyAsync({
            val ids = HashSet<String>()
            for ((key, data) in pr.inventories) {
                if (key.startsWith("backpack_icon") || key == "wardrobe_contents" || key.startsWith("wardrobe_")) continue
                for (tag in PvParser.decode(data) ?: continue) PvItems.skyblockId(tag).takeIf { it.isNotEmpty() }?.let(ids::add)
            }
            ids as Set<String>
        }, WORKER).thenAccept { ids -> minecraft.execute { if (current === profile) ownedIds = ids } }
    }

    private fun computeNetworth(pr: PvProfile, museum: com.google.gson.JsonObject? = null) {
        if (!pr.inventoryApi) return
        val prices = PriceDisplayModule.INSTANCE.prices()
        if (prices.bazaar == null) return
        val profile = pr
        val started = System.currentTimeMillis()
        CompletableFuture.supplyAsync({
            PvNetworth.compute(pr, museum, { PvParser.decode(it) }) { NetworthSupport.priceOf(prices, it) }
        }, WORKER).thenAccept { w ->
            minecraft.execute {
                if (current !== profile) return@execute
                worth = w
                ProfileViewerModule.INSTANCE.onNetworth(w.total, w.categories.mapValues { it.value.toLong() }, w.museumIncluded, System.currentTimeMillis() - started)
            }
        }
    }

    /** Recounts the networth once with the museum when the Garden and Museum backend has this member's museum. */
    private fun museumForWorth(pr: PvProfile) {
        if (worthMuseumTried || worth == null || !ProfileViewerModule.INSTANCE.gardenAndMuseum.value()) return
        val result = PvBackend.get("museum", pr.id) ?: return
        worthMuseumTried = true
        val member = result.data?.get("members")?.takeIf { it.isJsonObject }?.asJsonObject?.get(PvBackend.compact(uuid ?: return))
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        computeNetworth(pr, member)
    }

    private val iconCache = HashMap<String, ItemStack>()

    private fun icon(id: String): ItemStack = iconCache[id] ?: (PvItems.byId(id) ?: ItemStack(Items.PAPER).also {
        it.set(DataComponents.CUSTOM_NAME, Component.literal(title(id.substringBefore(';'))))
    }).also { if (PvItems.byId(id) != null || geiler.addons.client.itemlist.ItemListRepository.compiled != null) iconCache[id] = it }

    // ---- input ----------------------------------------------------------------------------------

    private fun lookupBox() {
        val text = nameBox.value.trim()
        if (text.matches(Regex("[A-Za-z0-9_]{1,16}"))) load(text)
    }

    /** Moves the scroll position to where the mouse is on the scrollbar track drawn last frame. */
    private fun dragBar(mouseY: Double) {
        val bar = PvPages.bar ?: return
        val fraction = ((mouseY - bar.y - bar.thumb / 2.0) / (bar.h - bar.thumb)).coerceIn(0.0, 1.0)
        scroll = Math.round(fraction * (bar.total - bar.visible) / bar.unit).toInt()
    }

    private var draggingBar = false

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (draggingBar && event.button() == 0) {
            dragBar(event.y())
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        draggingBar = false
        return super.mouseReleased(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val onWidget = nameBox.isMouseOver(event.x(), event.y()) || (filterBox.visible && filterBox.isMouseOver(event.x(), event.y()))
        val bar = PvPages.bar
        if (event.button() == 0 && !onWidget && !dropdown && bar != null && event.x() >= bar.x - 3 && event.x() < bar.x + 6 && event.y() >= bar.y && event.y() < bar.y + bar.h) {
            draggingBar = true
            dragBar(event.y())
            return true
        }
        if (event.button() == 0 && !onWidget) {
            for ((rect, action) in targets.toList()) {
                if (rect.contains(event.x(), event.y())) {
                    action()
                    return true
                }
            }
            dropdown = false
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (panel().contains(mouseX, mouseY)) {
            if (page == Page.INVENTORIES && container == "talisman_bag") accessoryPage = max(0, accessoryPage - scrollY.toInt())
            else scroll = max(0, scroll - scrollY.toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (nameBox.isFocused && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            lookupBox()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private fun click() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private companion object {
        const val W = 431
        const val H = 202
        const val W_MAX = 520
        const val H_MAX = 250
        const val TAB = 28
        const val SLOT = 18
        const val PET_W = 26
        const val PET_H = 30
        const val SCROLL_PX = 18
        const val EDGE = 7
        const val CACHE_MS = 30_000L
        const val MAX_CACHED = 16
        const val COOKIE_GEMS = 325.0
        const val EURO_PER_GEM = 4.99 / 675.0
        const val BAR_COLOR = -16_733_441 // 0xFF00AAFF
        const val MAX_COLOR = -21_760 // 0xFFFFAA00
        const val NOT_MATCHED = -1_442_840_576 // 0xAA000000
        const val MAXED_BG = 0x40FFAA00
        val CACHE = HashMap<String, Loaded>()
        /** Side-list keys of the storage views in the Inventories page (not API container names). */
        const val ENDER = "storage_ender"
        const val BACKPACKS = "storage_backpacks"
        val STORAGE_KEYS = setOf(ENDER, BACKPACKS)
        val ARMOR = listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")
        val WORKER = Executors.newFixedThreadPool(2) { Thread(it, "geileraddons-profile-viewer").also { t -> t.isDaemon = true } }
        val RARITIES = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")
        val RARITY_COLORS = mapOf("COMMON" to "§f", "UNCOMMON" to "§a", "RARE" to "§9", "EPIC" to "§5", "LEGENDARY" to "§6", "MYTHIC" to "§d")
        val SKILL_ICONS: Map<String, ItemStack> by lazy {
            mapOf(
                "FARMING" to Items.GOLDEN_HOE, "MINING" to Items.STONE_PICKAXE, "COMBAT" to Items.STONE_SWORD,
                "FORAGING" to Items.JUNGLE_SAPLING, "FISHING" to Items.FISHING_ROD, "ENCHANTING" to Items.ENCHANTING_TABLE,
                "ALCHEMY" to Items.BREWING_STAND, "TAMING" to Items.LEAD, "CARPENTRY" to Items.CRAFTING_TABLE,
                "HUNTING" to Items.LEAD, "RUNECRAFTING" to Items.MAGMA_CREAM, "SOCIAL" to Items.EMERALD,
            ).mapValues { ItemStack(it.value) }
        }
        val SLAYER_ICONS: Map<String, ItemStack> by lazy {
            mapOf(
                "zombie" to Items.ROTTEN_FLESH, "spider" to Items.COBWEB, "wolf" to Items.MUTTON,
                "enderman" to Items.ENDER_PEARL, "blaze" to Items.BLAZE_ROD, "vampire" to Items.REDSTONE,
            ).mapValues { ItemStack(it.value) }
        }
        val SLAYER_NAMES = mapOf(
            "zombie" to "Revenant Horror", "spider" to "Tarantula Broodfather", "wolf" to "Sven Packmaster",
            "enderman" to "Voidgloom Seraph", "blaze" to "Inferno Demonlord", "vampire" to "Riftstalker Bloodfiend",
        )
        val CONTAINERS: List<Pair<String, () -> ItemStack>> = listOf(
            "inv_contents" to { ItemStack(Items.CHEST) },
            "armor" to { ItemStack(Items.DIAMOND_CHESTPLATE) },
            "talisman_bag" to { ItemStack(Items.EMERALD) },
            "personal_vault_contents" to { ItemStack(Items.IRON_DOOR) },
            "potion_bag" to { ItemStack(Items.POTION) },
            "fishing_bag" to { ItemStack(Items.FISHING_ROD) },
            "quiver" to { ItemStack(Items.ARROW) },
            "sacks_bag" to { ItemStack(Items.BUNDLE) },
            ENDER to { ItemStack(Items.ENDER_CHEST) },
            BACKPACKS to { ItemStack(Items.BARREL) },
        )
        val CONTAINER_NAMES = mapOf(
            "inv_contents" to "Inventory", "armor" to "Armor and Equipment", "talisman_bag" to "Accessory Bag",
            "personal_vault_contents" to "Personal Vault", "potion_bag" to "Potion Bag", "fishing_bag" to "Fishing Bag",
            "quiver" to "Quiver", "sacks_bag" to "Sack of Sacks", ENDER to "Ender Chest", BACKPACKS to "Backpacks",
        )

        fun title(id: String): String = id.lowercase(Locale.ROOT).split('_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

        fun n(value: Number): String = String.format(Locale.ROOT, "%,d", value.toLong())

        fun roman(value: Int): String {
            if (value <= 0) return "0"
            val numerals = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
            var left = value
            return buildString { for ((v, s) in numerals) while (left >= v) { append(s); left -= v } }
        }

        fun short(value: Double): String = when {
            value >= 1e9 -> String.format(Locale.ROOT, "%.2fB", value / 1e9)
            value >= 1e6 -> String.format(Locale.ROOT, "%.1fM", value / 1e6)
            value >= 1e3 -> String.format(Locale.ROOT, "%.1fk", value / 1e3)
            else -> String.format(Locale.ROOT, "%.0f", value)
        }

        fun time(ms: Long?): String {
            if (ms == null || ms <= 0) return "-"
            val s = ms / 1000
            return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }

        fun parseUuid(compact: String): UUID = try {
            val c = compact.replace("-", "")
            UUID(java.lang.Long.parseUnsignedLong(c.substring(0, 16), 16), java.lang.Long.parseUnsignedLong(c.substring(16), 16))
        } catch (_: RuntimeException) {
            UUID(0, 0)
        }
    }
}
