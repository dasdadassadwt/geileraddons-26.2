package geiler.addons.client.profileviewer

import geiler.addons.client.module.impl.rarityItem
import com.google.gson.JsonObject
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.module.impl.PriceDisplayModule
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

/**
 * Profile Viewer pages for the newer SkyBlock data (skill trees, foraging, hunting, farming, Crimson Isle, Rift,
 * fishing, bestiary, minions, accessories, misc). [Area] is the panel's inner area below the page title.
 */
internal object PvPages {
    class Area(val g: GuiGraphicsExtractor, val font: Font, val x: Int, val y: Int, val w: Int, val h: Int, val mouseX: Int, val mouseY: Int) {
        fun tooltip(lines: List<String>) = g.setTooltipForNextFrame(font, lines.map { Component.literal(it) }, java.util.Optional.empty(), mouseX, mouseY)
        fun hover(x: Int, y: Int, w: Int, h: Int) = mouseX in x until x + w && mouseY in y until y + h
    }

    private const val LINE = 11
    private const val BAR_COLOR = -16_733_441 // 0xFF00AAFF
    private const val MAX_COLOR = -21_760 // 0xFFFFAA00

    private class Column(val g: GuiGraphicsExtractor, val font: Font, val x: Int, var y: Int, val w: Int, val area: Area? = null) {
        /** One line; a line cut to fit shows in full on hover. */
        fun line(text: String) {
            val shown = GuiTheme.fit(font, text, w)
            g.text(font, shown, x, y, GuiTheme.TEXT_PRIMARY, false)
            if (shown != text && area != null && area.hover(x, y - 1, w, LINE)) area.tooltip(listOf(text))
            y += LINE
        }

        fun head(text: String) {
            y += 3
            line("§e§l$text")
        }

        fun bar(fraction: Double, maxed: Boolean) {
            g.fill(x, y - 1, x + w - 8, y + 3, GuiTheme.SLIDER_TRACK)
            g.fill(x, y - 1, x + ((w - 8) * fraction.coerceIn(0.0, 1.0)).toInt(), y + 3, if (maxed) MAX_COLOR else BAR_COLOR)
            y += 6
        }

        fun icon(stack: ItemStack, text: String) {
            g.rarityItem(stack, x, y - 4)
            g.text(font, GuiTheme.fit(font, text, w - 20), x + 20, y, GuiTheme.TEXT_PRIMARY, false)
            y += LINE + 6
        }
    }

    private fun n(value: Number): String = String.format(Locale.ROOT, "%,d", value.toLong())

    private fun short(value: Double): String = when {
        value >= 1e9 -> String.format(Locale.ROOT, "%.2fB", value / 1e9)
        value >= 1e6 -> String.format(Locale.ROOT, "%.1fM", value / 1e6)
        value >= 1e3 -> String.format(Locale.ROOT, "%.1fk", value / 1e3)
        else -> String.format(Locale.ROOT, "%.0f", value)
    }

    private fun title(id: String) = PvSections.title(id)

    private fun columns(a: Area, count: Int, width: Int = a.w): List<Column> {
        val cw = width / count
        return (0 until count).map { Column(a.g, a.font, a.x + it * cw, a.y, cw - 6, a) }
    }

    private fun loading(a: Area, what: String) =
        a.g.text(a.font, "§7Loading $what from the NEU repository... (${PvConstants.status})", a.x, a.y + 4, GuiTheme.TEXT_PRIMARY, false)

    private val LOOKS: Map<PvTree.Look, ItemStack> by lazy {
        mapOf(
            PvTree.Look.REDSTONE_BLOCK to Items.REDSTONE_BLOCK, PvTree.Look.EMERALD_BLOCK to Items.EMERALD_BLOCK,
            PvTree.Look.COAL_BLOCK to Items.COAL_BLOCK, PvTree.Look.COAL to Items.COAL,
            PvTree.Look.EMERALD to Items.EMERALD, PvTree.Look.DIAMOND to Items.DIAMOND,
        ).mapValues { ItemStack(it.value) }
    }

    /** Width of a perk grid drawn by [tree] in an area [h] tall (7 columns, 10 rows). */
    fun treeWidth(h: Int): Int = 7 * treeCell(h)

    private fun treeCell(h: Int) = (h / 10).coerceIn(14, 18)

    /** A perk grid drawn as Hypixel's menu draws it (see [PvTree.look]); started nodes show their level, hover shows it all. */
    private fun tree(a: Area, x: Int, y: Int, perks: List<PvTree.Perk>, selectedAbility: String?) {
        val cell = treeCell(a.h)
        for (p in perks) {
            val px = x + p.x * cell
            val py = y + p.y * cell
            val stack = LOOKS[PvTree.look(p, selectedAbility)] ?: continue
            a.g.item(stack, px, py)
            if (p.kind == PvTree.Kind.NODE && p.level in 1 until p.maxLevel || p.kind == PvTree.Kind.CORE && p.level > 0) {
                a.g.itemDecorations(a.font, stack, px, py, p.level.toString())
            }
            if (a.hover(px, py, cell, cell)) {
                val state = when {
                    p.kind == PvTree.Kind.ABILITY -> if (p.id == selectedAbility) "§aSelected ability" else if (p.level > 0) "§7Unlocked ability" else "§cLocked ability"
                    p.level >= p.maxLevel -> "§aMaxed"
                    p.level > 0 -> "§eLevel ${p.level}/${p.maxLevel}"
                    else -> "§cLocked"
                }
                a.tooltip(listOf((if (p.level >= p.maxLevel) "§a" else if (p.level > 0) "§e" else "§c") + p.name, state))
            }
        }
    }

    fun mining(a: Area, pr: PvProfile) {
        val s = pr.sections
        val (c1, c2) = columns(a, 2, a.w - treeWidth(a.h) - 6)
        c1.icon(ItemStack(Items.IRON_PICKAXE), "§fHOTM §e${s.hotmLevel}§7/10")
        c1.bar(PvLevels.treeProgress(s.hotmXp, false), s.hotmLevel >= 10)
        c1.line("§7XP: §f${n(s.hotmXp)}")
        c1.line("§7Core of the Mountain: §f${s.coreOfTheMountain}§7/10")
        val tokens = PvLevels.hotmTokens(s.hotmLevel, s.coreOfTheMountain)
        c1.line("§7Tokens: §f${tokens - s.mountainTokensSpent}§7 free of §f$tokens")
        c1.line("§7Ability: §f${s.pickaxeAbility}")
        c1.line("§7Mining: §e${pr.skillLevel("MINING").toInt()}")
        c1.head("Powder")
        for ((kind, color) in listOf("mithril" to "§2", "gemstone" to "§d", "glacite" to "§b")) {
            val p = s.powder(kind)
            c1.line("$color${title(kind)}: §f${short(p.available.toDouble())} §7/ ${short(p.total.toDouble())}")
        }
        c2.head("Crystal Hollows")
        c2.line("§7Nucleus runs: §f${s.nucleusRuns}")
        // Two crystals per line so the list and the Glacite rows stay inside the panel.
        val half = c2.w / 2
        s.crystals.entries.chunked(2).forEach { pair ->
            pair.forEachIndexed { i, (crystal, state) ->
                val text = (if (state == "NOT_FOUND") "§c✘ " else "§a✔ ") + "§7${title(crystal)}"
                a.g.text(a.font, GuiTheme.fit(a.font, text, half - 2), c2.x + i * half, c2.y, GuiTheme.TEXT_PRIMARY, false)
            }
            c2.y += LINE
        }
        c2.head("Glacite Tunnels")
        c2.line("§7Fossils: §f${s.fossilsDonated}")
        c2.line("§7Fossil dust: §f${n(s.fossilDust.toLong())}")
        c2.line("§7Mineshafts: §f${s.mineshafts}")
        c2.line("§7Corpses: §f${s.corpsesLooted}")
        val tx = a.x + a.w - treeWidth(a.h)
        val layout = PvConstants.get("hotmlayout")
        if (layout != null) tree(a, tx, a.y, PvTree.parse(layout, "hotm", s.miningNodes), s.abilityId("mining"))
        else a.g.text(a.font, "§8Tree loading...", tx, a.y + 4, GuiTheme.TEXT_PRIMARY, false)
    }

    fun foraging(a: Area, pr: PvProfile) {
        val s = pr.sections
        val (c1, c2) = columns(a, 2, a.w - treeWidth(a.h) - 6)
        val max = PvLevels.hotfMax()
        c1.icon(ItemStack(Items.OAK_SAPLING), "§fHOTF §e${s.hotfLevel}§7/$max")
        c1.bar(PvLevels.treeProgress(s.hotfXp, true), s.hotfLevel >= max)
        c1.line("§7XP: §f${n(s.hotfXp)}")
        c1.line("§7Center of the Forest: §f${s.centerOfTheForest}§7/5")
        val tokens = PvLevels.hotfTokens(s.hotfLevel, s.centerOfTheForest)
        c1.line("§7Tokens: §f${tokens - s.forestTokensSpent}§7 free of §f$tokens")
        c1.line("§7Ability: §f${s.axeAbility}")
        c1.line("§7Foraging: §e${pr.skillLevel("FORAGING").toInt()}")
        c2.head("Galatea")
        c2.line("§2Forest Whispers: §f${short(s.whispersAvailable.toDouble())}")
        c2.line("§7  ever: §f${short(s.whispersTotal.toDouble())}")
        c2.line("§6Desert Whispers: §f${short(s.desertWhispersAvailable.toDouble())}")
        c2.line("§7  ever: §f${short(s.desertWhispersTotal.toDouble())}")
        c2.line("§7Hina chapter: §f${s.hinaChapter}§7/7")
        c2.line("§7Fish family: §f${s.fishFamily}§7/${PvSections.FISH_FAMILY}")
        c2.head("Tree Gifts")
        for (tree in listOf("FIG", "MANGROVE")) c2.line("§7${title(tree)}: §f${s.treeGifts[tree] ?: 0}§7/7")
        val tx = a.x + a.w - treeWidth(a.h)
        val layout = PvConstants.get("hotflayout")
        if (layout != null) tree(a, tx, a.y, PvTree.parse(layout, "hotf", s.foragingNodes), s.abilityId("foraging"))
        else a.g.text(a.font, "§8Tree loading...", tx, a.y + 4, GuiTheme.TEXT_PRIMARY, false)
    }

    /**
     * Hunting skill and attributes; the attribute list scrolls ([scroll] rows) and shows each shard's own item. A
     * [query] filters by attribute or shard name and also lists attributes not unlocked yet.
     */
    fun hunting(a: Area, pr: PvProfile, scroll: Int, query: String = ""): Int {
        val s = pr.sections
        val (c1) = columns(a, 1, 130)
        val level = pr.skillLevel("HUNTING")
        c1.icon(ItemStack(Items.LEAD), "§fHunting §e${level.toInt()}§7/50")
        c1.bar(if (level >= 50) 1.0 else level - level.toInt(), level >= 50)
        c1.line("§7Shards owned: §f${n(s.shardsOwned)}")
        val constants = PvConstants.get("attribute_shards")
        val list = constants?.let { PvAttributes.parse(it, s.attributes, s.shardsByType) }
        if (list == null) {
            c1.line("§7Attributes: §f${s.attributes.size}")
            loading(Area(a.g, a.font, a.x + 136, a.y, a.w - 136, a.h, a.mouseX, a.mouseY), "attribute levels")
            return 0
        }
        val unlocked = list.filter { it.level > 0 }
        c1.line("§7Unlocked: §f${unlocked.size}§7/${list.size}")
        c1.line("§7Maxed: §f${list.count { it.level >= it.maxLevel && it.maxLevel > 0 }}")
        val q = query.trim().lowercase(Locale.ROOT)
        val sorted = list.sortedWith(compareByDescending<PvAttributes.Attribute> { it.level }.thenBy { it.name }).filter {
            if (q.isEmpty()) it.level > 0 || it.owned > 0
            else it.name.lowercase(Locale.ROOT).contains(q) || it.shard.lowercase(Locale.ROOT).contains(q)
        }
        val x0 = a.x + 136
        val cols = 2
        val cw = (a.w - 136 - 8) / cols
        val rowH = 18
        val visible = a.h / rowH
        val rows = (sorted.size + cols - 1) / cols
        val start = scroll.coerceIn(0, maxOf(0, rows - visible))
        for (i in start * cols until minOf(sorted.size, (start + visible) * cols)) {
            val att = sorted[i]
            val x = x0 + (i % cols) * cw
            val y = a.y + (i / cols - start) * rowH
            val icon = (if (att.itemId.isEmpty()) null else PvItems.byId(att.itemId)) ?: ItemStack(Items.PRISMARINE_SHARD)
            a.g.rarityItem(icon, x, y)
            val lv = (if (att.level >= att.maxLevel) "§6" else "§e") + att.level
            a.g.text(a.font, GuiTheme.fit(a.font, "§f${att.name}", cw - 24 - a.font.width(lv)), x + 18, y + 4, GuiTheme.TEXT_PRIMARY, false)
            a.g.text(a.font, lv, x + cw - 4 - a.font.width(lv), y + 4, GuiTheme.TEXT_PRIMARY, false)
            if (a.hover(x, y, cw - 2, rowH)) {
                a.tooltip(listOf("§f${att.name}", "§7Shard: §f${att.shard} §8(${title(att.rarity)})", "§7Level ${att.level}/${att.maxLevel}",
                    "§7Syphoned: §f${att.syphoned}", "§7Owned: §f${att.owned}"))
            }
        }
        if (sorted.isEmpty()) a.g.text(a.font, if (q.isEmpty()) "§8No attributes" else "§8No attribute matches the filter.", x0, a.y, GuiTheme.TEXT_PRIMARY, false)
        scrollbar(a.g, a.x + a.w - 3, a.y, a.h, start, visible, rows)
        return start
    }

    /** The scrollbar drawn this frame, so the screen can drag it; [unit] is how many of its units one scroll step is. */
    class Bar(val x: Int, val y: Int, val h: Int, val thumb: Int, val visible: Int, val total: Int, val unit: Int)

    @JvmStatic
    var bar: Bar? = null

    /**
     * A thin scrollbar at [x] for position [first] of [total] (rows or pixels) with [visible] shown; nothing when
     * everything fits. It is recorded as [bar] so a click or drag on it moves the page.
     */
    fun scrollbar(g: GuiGraphicsExtractor, x: Int, y: Int, h: Int, first: Int, visible: Int, total: Int, unit: Int = 1) {
        if (total <= visible || total <= 0) return
        g.fill(x, y, x + 3, y + h, GuiTheme.SLIDER_TRACK)
        val thumb = maxOf(10, h * visible / total)
        val top = y + ((h - thumb) * first.toDouble() / (total - visible)).toInt().coerceIn(0, h - thumb)
        g.fill(x, top, x + 3, top + thumb, GuiTheme.SCROLLBAR)
        bar = Bar(x, y, h, thumb, visible, total, unit)
    }

    /** Farming skill, Jacob's Contests (best medal per crop) and the Garden on one page. */
    fun farming(a: Area, pr: PvProfile) {
        val s = pr.sections
        val c1 = Column(a.g, a.font, a.x, a.y, 98, a)
        val c2 = Column(a.g, a.font, a.x + 102, a.y, 108, a)
        val level = pr.skillLevel("FARMING")
        val cap = pr.skillCap("FARMING")
        c1.icon(ItemStack(Items.GOLDEN_HOE), "§fFarming §e${level.toInt()}§7/$cap")
        c1.bar(if (level >= cap) 1.0 else level - level.toInt(), level >= cap)
        c1.head("Jacob's Contests")
        c1.line("§7Contests: §f${s.contests}")
        c1.line("§7Level cap perk: §f+${s.farmingCapPerk}")
        c1.line("§7Double Drops: §f${s.doubleDrops}")
        c1.head("Medals")
        for (medal in PvSections.MEDALS) c1.line("${MEDAL_COLORS[medal]}${title(medal)}: §f${s.medals[medal] ?: 0}")
        c2.head("Personal Bests")
        if (s.personalBests.isEmpty()) c2.line("§8None")
        // Short numbers coloured by the best medal so each crop fits on one line; hover shows the exact count.
        s.personalBests.entries.sortedBy { it.key }.forEach { (crop, best) ->
            val medal = s.bestMedal(crop)
            val name = title(PvGarden.cropKey(crop))
            val value = (medal?.let { MEDAL_COLORS[it] } ?: "§f") + short(best.toDouble())
            val y = c2.y
            a.g.text(a.font, GuiTheme.fit(a.font, "§7$name", c2.w - a.font.width(value) - 4), c2.x, y, GuiTheme.TEXT_PRIMARY, false)
            a.g.text(a.font, value, c2.x + c2.w - a.font.width(value), y, GuiTheme.TEXT_PRIMARY, false)
            if (a.hover(c2.x, y - 1, c2.w, LINE)) a.tooltip(listOf("§f$name", "§7Personal best: §f${n(best)}") + (medal?.let { listOf("§7Best medal: ${MEDAL_COLORS[it]}${title(it)}") } ?: emptyList()))
            c2.y += LINE
        }
        garden(Area(a.g, a.font, a.x + 216, a.y, a.w - 216, a.h, a.mouseX, a.mouseY), pr)
    }

    private val MEDAL_COLORS = mapOf("diamond" to "§b", "platinum" to "§3", "gold" to "§6", "silver" to "§f", "bronze" to "§c")

    fun crimson(a: Area, pr: PvProfile) {
        val s = pr.sections
        val (c1, c2, c3) = columns(a, 3)
        c1.head("Factions")
        c1.line("§7Faction: §f${s.faction?.let(::title) ?: "None"}")
        c1.line("§5Mages: §f${n(s.magesReputation.toLong())}")
        c1.line("§cBarbarians: §f${n(s.barbariansReputation.toLong())}")
        c1.line("§7Matriarch pearls: §f${s.matriarchPearls}")
        c2.head("Kuudra")
        for (tier in PvSections.KUUDRA_TIERS) c2.line("§7${if (tier == "none") "Basic" else title(tier)}: §f${s.kuudra[tier] ?: 0}")
        c2.line("§7Total: §f${s.kuudra.values.sum()}")
        c3.head("Dojo")
        for (test in PvSections.DOJO_TESTS) {
            val points = s.dojo[test] ?: 0
            c3.line("§7${PvSections.dojoName(test)}: §f$points ${grade(points)}")
        }
        val total = s.dojo.values.sum()
        c3.line("§7Total: §f$total §7(${PvSections.belt(total)} Belt)")
    }

    /** Dojo grade from a test's points, as SkyCrypt grades them (S from 1000, then every 200). */
    private fun grade(points: Int): String = when {
        points >= 1000 -> "§6S"
        points >= 800 -> "§aA"
        points >= 600 -> "§bB"
        points >= 400 -> "§eC"
        points >= 200 -> "§cD"
        else -> "§4F"
    }

    fun rift(a: Area, pr: PvProfile) {
        val s = pr.sections
        val (c1, c2) = columns(a, 2)
        c1.head("Motes")
        c1.line("§dPurse: §f${n(s.motes.toLong())}")
        c1.line("§dLifetime: §f${n(s.lifetimeMotes.toLong())}")
        c1.line("§7Visits: §f${s.riftVisits}")
        c2.head("Progress")
        c2.line("§7Enigma souls: §f${s.enigmaSouls}")
        c2.line("§7Montezuma cats: §f${s.montezumaCats}")
        c2.line("§7Timecharms: §f${s.timecharms}")
        c2.line("§7Wither eyes killed: §f${s.witherEyes}")
        c2.line("§7Grubber stacks: §f${s.grubberStacks}")
    }

    /** Trophy tier columns: bronze, silver, gold, diamond. */
    private val TROPHY_TIERS = listOf("bronze" to "§6B", "silver" to "§7S", "gold" to "§eG", "diamond" to "§bD")

    /** Fishing stats, trophy fish and trophy frogs (a tick per tier caught; hover a fish for its counts), each with its Item List item. */
    fun fishing(a: Area, pr: PvProfile) {
        val s = pr.sections
        val c1 = Column(a.g, a.font, a.x, a.y, 118, a)
        c1.head("Fishing")
        c1.line("§7Level: §e${pr.skillLevel("FISHING").toInt()}")
        c1.line("§7Items fished: §f${n(s.itemsFished)}")
        c1.line("§7Treasures: §f${n(s.treasuresFished)}")
        c1.line("§7Large treasures: §f${n(s.largeTreasuresFished)}")
        c1.line("§7Sea creatures: §f${n(s.seaCreatureKills)}")
        c1.line("§7Trophy fish: §f${n(s.trophyTotal)}")
        val row = 10
        val bx = a.x + 122
        val colW = (a.w - 122) / 2
        val name = colW - 4 * 22 - 4
        fun header(x: Int, label: String) {
            a.g.text(a.font, label, x, a.y, GuiTheme.TEXT_PRIMARY, false)
            TROPHY_TIERS.forEachIndexed { i, t -> a.g.text(a.font, t.second, x + name + i * 22, a.y, GuiTheme.TEXT_PRIMARY, false) }
        }
        header(bx, "§7Trophy Fish")
        header(bx + colW, "§7Trophy Frogs")
        fun icon(id: String, x: Int, y: Int) {
            val stack = PvItems.byId(id) ?: return
            a.g.pose().pushMatrix()
            a.g.pose().translate(x.toFloat(), (y - 1).toFloat())
            a.g.pose().scale(0.5625f, 0.5625f)
            a.g.item(stack, 0, 0)
            a.g.pose().popMatrix()
        }
        var ry = a.y + row
        for (fish in PvSections.TROPHY_FISH) {
            icon(fish.uppercase(Locale.ROOT) + "_DIAMOND", bx, ry)
            a.g.text(a.font, GuiTheme.fit(a.font, title(fish), name - 12), bx + 11, ry, GuiTheme.TEXT_SECONDARY, false)
            TROPHY_TIERS.forEachIndexed { i, t ->
                a.g.text(a.font, if (s.trophy(fish, t.first) > 0) "§a✔" else "§8-", bx + name + i * 22, ry, GuiTheme.TEXT_PRIMARY, false)
            }
            if (a.hover(bx, ry - 1, colW - 4, row)) {
                a.tooltip(listOf("§f${title(fish)}") + TROPHY_TIERS.map { (tier, label) ->
                    val count = s.trophy(fish, tier)
                    "$label§7 ${title(tier)}: " + if (count == 0) "§8none" else "§f${n(count)}"
                } + "§7Total: §f${n(TROPHY_TIERS.sumOf { s.trophy(fish, it.first) })}")
            }
            ry += row
        }
        ry = a.y + row
        for (frog in PvSections.TROPHY_FROGS) {
            icon(frog + "_DIAMOND", bx + colW, ry)
            a.g.text(a.font, GuiTheme.fit(a.font, title(frog), name - 12), bx + colW + 11, ry, GuiTheme.TEXT_SECONDARY, false)
            val tier = s.trophyFrog(frog)
            for (i in TROPHY_TIERS.indices) a.g.text(a.font, if (tier > i) "§a✔" else "§8-", bx + colW + name + i * 22, ry, GuiTheme.TEXT_PRIMARY, false)
            ry += row
        }
    }

    fun bestiary(a: Area, pr: PvProfile, scroll: Int): Int {
        val constants = PvConstants.get("bestiary") ?: run { loading(a, "bestiary data"); return 0 }
        val b = PvBestiary.parse(constants, pr.sections.bestiaryKills)
        a.g.text(a.font, "§7Milestone §e${b.milestone} §8(${b.tiers} tiers)", a.x, a.y, GuiTheme.TEXT_PRIMARY, false)
        val rows = (a.h - 14) / 10
        val cw = a.w / 2
        val entries = b.islands
        val start = scroll.coerceIn(0, maxOf(0, entries.size - rows * 2))
        for ((i, island) in entries.drop(start).take(rows * 2).withIndex()) {
            val x = a.x + (i / rows) * cw
            val y = a.y + 14 + (i % rows) * 10
            val done = island.maxed == island.mobs.size
            a.g.text(a.font, GuiTheme.fit(a.font, "${if (done) "§a" else "§f"}${island.name} §7${island.maxed}/${island.mobs.size}", cw - 6), x, y, GuiTheme.TEXT_PRIMARY, false)
            if (a.hover(x, y, cw - 6, 10)) {
                a.tooltip(listOf("§f${island.name}") + island.mobs.take(24).map { m ->
                    "${if (m.tier >= m.maxTier) "§a" else "§7"}${m.name} §f${m.tier}/${m.maxTier} §8(${n(m.kills)} kills)"
                })
            }
        }
        return start
    }

    fun minions(a: Area, pr: PvProfile) {
        val misc = PvConstants.get("misc") ?: run { loading(a, "minion data"); return }
        val list = PvMinions.parse(misc, pr.craftedMinions)
        val unique = PvMinions.uniqueCrafts(pr.craftedMinions)
        a.g.text(a.font, "§7Unique crafts: §f$unique §7Maxed: §f${list.count { it.highest >= it.maxTier && it.maxTier > 0 }}§7/${list.size}", a.x, a.y, GuiTheme.TEXT_PRIMARY, false)
        val cols = 4
        val cw = a.w / cols
        val rows = (a.h - 14) / 9
        for ((i, m) in list.take(rows * cols).withIndex()) {
            val x = a.x + (i / rows) * cw
            val y = a.y + 14 + (i % rows) * 9
            val color = when {
                m.highest >= m.maxTier -> "§a"
                m.highest > 0 -> "§f"
                else -> "§8"
            }
            a.g.text(a.font, GuiTheme.fit(a.font, "$color${title(m.id)} §7${m.highest}/${m.maxTier}", cw - 4), x, y, GuiTheme.TEXT_PRIMARY, false)
        }
    }

    /** Missing accessories, cheapest first; [owned] is null while the containers are still being read. */
    fun accessories(a: Area, pr: PvProfile, owned: Set<String>?) {
        val s = pr.sections
        a.g.text(a.font, "§7Magical Power: §6${s.magicalPower} §7Power: §f${s.selectedPower ?: "None"}", a.x, a.y, GuiTheme.TEXT_PRIMARY, false)
        if (!pr.inventoryApi) {
            a.g.text(a.font, "§cInventory API off.", a.x, a.y + 14, GuiTheme.TEXT_PRIMARY, false)
            return
        }
        val misc = PvConstants.get("misc") ?: run { loading(Area(a.g, a.font, a.x, a.y + 14, a.w, a.h, a.mouseX, a.mouseY), "accessory upgrades"); return }
        val compiled = ItemListRepository.compiled
        if (owned == null || compiled == null) {
            a.g.text(a.font, "§7Reading accessories... ${if (compiled == null) "(Item List not loaded)" else ""}", a.x, a.y + 14, GuiTheme.TEXT_PRIMARY, false)
            if (compiled == null) ItemListRepository.request()
            return
        }
        val all = compiled.entries.filter { it.type.endsWith("ACCESSORY") || it.type == "HATCESSORY" }.map { it.id }.distinct()
        val result = PvAccessories.missing(misc, all, owned)
        val prices = PriceDisplayModule.INSTANCE.prices().lowestBin
        fun price(id: String) = prices?.get(id)
        val cell = 20
        var y = a.y + 14
        for ((label, ids) in listOf("Upgrades" to result.upgrades, "Missing" to result.missing)) {
            a.g.text(a.font, "§e§l$label §7(${ids.size})", a.x, y, GuiTheme.TEXT_PRIMARY, false)
            y += 11
            val sorted = ids.sortedBy { price(it) ?: Double.MAX_VALUE }
            val perRow = a.w / cell
            val maxRows = if (label == "Upgrades") 2 else (a.y + a.h - y) / cell
            for ((i, id) in sorted.take(perRow * maxRows).withIndex()) {
                val x = a.x + (i % perRow) * cell
                val iy = y + (i / perRow) * cell
                val stack = PvItems.byId(id) ?: ItemStack(Items.PAPER)
                a.g.rarityItem(stack, x + 1, iy + 1)
                if (a.hover(x, iy, cell, cell)) {
                    a.tooltip(listOf(compiled.byId[id]?.plainName ?: title(id), price(id)?.let { "§7Lowest BIN: §6${short(it)}" } ?: "§8No price"))
                }
            }
            y += minOf(maxRows, (sorted.size + perRow - 1) / perRow).coerceAtLeast(1) * cell + 4
        }
    }

    /** Off, loading, error or the data of a SkyBlockPV backend endpoint, drawn as a status line when not ready. */
    private fun backend(a: Area, kind: String, profileId: String): JsonObject? {
        if (!geiler.addons.client.module.impl.ProfileViewerModule.INSTANCE.gardenAndMuseum.value()) {
            a.g.text(a.font, "§7Turn on §fGarden and Museum §7in the Profile Viewer settings to load this.", a.x, a.y + 4, GuiTheme.TEXT_PRIMARY, false)
            return null
        }
        val result = PvBackend.get(kind, profileId)
        if (result == null) {
            a.g.text(a.font, "§7Loading from the SkyBlockPV backend...", a.x, a.y + 4, GuiTheme.TEXT_PRIMARY, false)
            return null
        }
        if (result.data == null) {
            a.g.text(a.font, if (result.error == "none") "§7No ${title(kind)} on this profile." else "§c${title(kind)} failed: ${result.error}", a.x, a.y + 4, GuiTheme.TEXT_PRIMARY, false)
            return null
        }
        return result.data
    }

    private fun garden(a: Area, pr: PvProfile) {
        val constants = PvConstants.get("garden") ?: run { loading(a, "garden data"); return }
        val data = backend(a, "garden", pr.id)?.let { it.get("garden")?.takeIf { g -> g.isJsonObject }?.asJsonObject } ?: return
        val g = PvGarden.parse(data, constants)
        val c1 = Column(a.g, a.font, a.x, a.y, 104, a)
        c1.icon(ItemStack(Items.SUNFLOWER), "§fGarden §e${g.level}§7/${g.maxLevel}")
        c1.line("§7XP: §f${n(g.experience)}")
        c1.line("§7Plots: §f${g.plots}§7/${g.maxPlots}")
        c1.line("§7Visitors: §f${n(g.visitorsServed)}")
        c1.line("§7Unique: §f${g.uniqueVisitors}§7/${g.maxVisitors}")
        c1.head("Composter")
        if (g.composter.isEmpty()) c1.line("§8No upgrades")
        g.composter.forEach { (k, v) -> c1.line("§7${title(k)}: §f$v") }
        val bx = a.x + 108
        val col = intArrayOf(0, 64, 82, 98)
        listOf("§7Crop", "§7MS", "§7Up", "§7Total").forEachIndexed { i, h -> a.g.text(a.font, h, bx + col[i], a.y, GuiTheme.TEXT_PRIMARY, false) }
        var y = a.y + 11
        for ((crop, milestone) in g.milestones.entries.sortedBy { it.key }) {
            a.g.text(a.font, GuiTheme.fit(a.font, title(crop), col[1] - 2), bx, y, GuiTheme.TEXT_SECONDARY, false)
            a.g.text(a.font, milestone.toString(), bx + col[1], y, GuiTheme.TEXT_PRIMARY, false)
            a.g.text(a.font, (g.cropUpgrades[crop] ?: 0).toString(), bx + col[2], y, GuiTheme.TEXT_PRIMARY, false)
            a.g.text(a.font, short((g.collected[crop] ?: 0L).toDouble()), bx + col[3], y, GuiTheme.TEXT_PRIMARY, false)
            y += 10
        }
    }

    fun museum(a: Area, pr: PvProfile, uuid: String) {
        val constants = PvConstants.get("museum") ?: run { loading(a, "museum data"); return }
        val members = backend(a, "museum", pr.id)?.let { it.get("members")?.takeIf { m -> m.isJsonObject }?.asJsonObject } ?: return
        val member = members.get(PvBackend.compact(uuid))?.takeIf { it.isJsonObject }?.asJsonObject
            ?: run { a.g.text(a.font, "§7This player has not donated to the museum.", a.x, a.y + 4, GuiTheme.TEXT_PRIMARY, false); return }
        val m = PvMuseum.parse(member, constants)
        val c1 = Column(a.g, a.font, a.x, a.y, 150)
        c1.icon(ItemStack(Items.GOLD_BLOCK), "§fMuseum")
        c1.line("§7Value: §6${short(m.value.toDouble())}")
        c1.line("§7Appraised: ${if (m.appraisal) "§aYes" else "§cNo"}")
        c1.line("§7Donated: §f${m.donated}§7/${m.categories.sumOf { it.max }}")
        c1.line("§7Special items: §f${m.special}")
        val bx = a.x + 158
        a.g.text(a.font, "§7Category §8(hover for missing items)", bx, a.y, GuiTheme.TEXT_PRIMARY, false)
        var y = a.y + 12
        val prices = PriceDisplayModule.INSTANCE.prices().lowestBin
        for (cat in m.categories) {
            val done = cat.donated >= cat.max
            val text = "${if (done) "§a" else "§f"}${title(cat.name)} §7${cat.donated}/${cat.max}"
            a.g.text(a.font, text, bx, y, GuiTheme.TEXT_PRIMARY, false)
            val fraction = if (cat.max == 0) 1.0 else cat.donated.toDouble() / cat.max
            a.g.fill(bx + 120, y + 2, bx + 220, y + 6, GuiTheme.SLIDER_TRACK)
            a.g.fill(bx + 120, y + 2, bx + 120 + (100 * fraction.coerceIn(0.0, 1.0)).toInt(), y + 6, if (done) MAX_COLOR else BAR_COLOR)
            if (a.hover(bx, y, 220, 11) && cat.missing.isNotEmpty()) {
                val names = cat.missing.sortedBy { prices?.get(it) ?: Double.MAX_VALUE }.take(20).map { id ->
                    "§7${ItemListRepository.compiled?.byId?.get(id)?.plainName ?: title(id)}" + (prices?.get(id)?.let { " §6${short(it)}" } ?: "")
                }
                a.tooltip(listOf("§fMissing ${title(cat.name)} (${cat.missing.size})") + names + if (cat.missing.size > 20) listOf("§8...") else emptyList())
            }
            y += 13
        }
    }

    fun misc(a: Area, pr: PvProfile) {
        val s = pr.sections
        val (c1, c2, c3) = columns(a, 3)
        c1.head("Essence")
        if (s.essence.isEmpty()) c1.line("§8None")
        s.essence.entries.sortedByDescending { it.value }.forEach { c1.line("§7${title(it.key)}: §f${n(it.value)}") }
        c2.head("Kills §7(${n(s.totalKills)})")
        s.kills.entries.sortedByDescending { it.value }.take(6).forEach { c2.line("§7${title(it.key)}: §f${n(it.value)}") }
        c2.head("Deaths §7(${n(s.totalDeaths)})")
        s.deaths.entries.sortedByDescending { it.value }.take(5).forEach { c2.line("§7${title(it.key)}: §f${n(it.value)}") }
        c3.head("Chocolate Factory")
        c3.line("§7Prestige: §f${s.chocolatePrestige}")
        c3.line("§6Chocolate: §f${short(s.chocolate.toDouble())}")
        c3.line("§7Since prestige: §f${short(s.chocolateSincePrestige.toDouble())}")
        c3.line("§7All-time: §f${short(s.chocolateTotal.toDouble())}")
        c3.line("§7Rabbits found: §f${s.rabbits}")
    }
}
