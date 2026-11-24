package geiler.addons.client.module.impl

import geiler.addons.client.carnival.CarnivalFruit
import geiler.addons.client.carnival.CarnivalGoal
import geiler.addons.client.carnival.CarnivalPatterns
import geiler.addons.client.carnival.CarnivalStore
import geiler.addons.client.carnival.FruitDiggingGame
import geiler.addons.client.carnival.TokenShopProgress
import geiler.addons.client.farming.PestDetector
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudLine
import geiler.addons.client.hud.TextLinesPanel
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.slayer.SlayerSidebar
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional

/**
 * SkyHanni's Carnival features (LGPL-2.1, `features/event/carnival` and `CarnivalShopHelper`): the Fruit Digging solver,
 * Zombie Shootout helpers, the daily-goal list, the double-click game start, the daily ticket reminder and the event
 * token shop overview. SkyHanni's reminder pathfinds to the Carnival Leader; here its chat line warps there on click.
 * SkyHanni writes the shop overview into slot 8 of the shop; here it is a panel beside the open shop.
 */
class CarnivalModule private constructor(private val s: Settings) : Module(
    "Carnival",
    "SkyHanni's Carnival helpers: Fruit Digging solver, Zombie Shootout timers and hitboxes, goals, quick start, ticket reminder and token shop overview.",
    Category.MISCELLANEOUS,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private enum class ZombieType(val points: Int, val helmet: net.minecraft.world.item.Item, val argb: Int, val lifetimeMs: Long) {
        LEATHER(30, Items.LEATHER_HELMET, 0xFFA52A2A.toInt(), 8_000L),
        IRON(50, Items.IRON_HELMET, 0xFFC0C0C0.toInt(), 7_000L),
        GOLD(80, Items.GOLDEN_HELMET, 0xFFFFD700.toInt(), 6_000L),
        DIAMOND(120, Items.DIAMOND_HELMET, 0xFF2CD6FA.toInt(), 5_000L),
    }

    private class ShootoutZombie(val entity: Zombie, val type: ZombieType)

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private val game = FruitDiggingGame()
    private val store = CarnivalStore()
    private val revealedStands = HashSet<Int>()
    private val anchorItems = HashSet<Int>()

    private var inArea = false
    private var areaPollTicks = 0
    private var tickCount = 0L

    private var shootoutStarted = false
    private var zombies: List<ShootoutZombie> = emptyList()
    private val zombieSeen = HashMap<Int, Long>()
    private var maxType = ZombieType.LEATHER
    private var lampPos: BlockPos? = null
    private var lampSince = 0L

    private var lastSelectOptionMs = 0L
    private var lastQuickStartMs = 0L

    private var lastScreenKey: String? = null
    private var openShop: TokenShopProgress.Shop? = null
    private var tokensOwned = 0

    private var nextReminderMs = 0L

    // Diagnostics counters, reset each status line.
    private var solverRuns = 0
    private var reveals = 0
    private var anchors = 0

    val remainingFruitHud = TextLinesPanel(
        "carnival_remaining_fruit", "Remaining Fruit", this,
        { fruitActive() && s.remainingFruit.value() }, ::remainingLines,
        listOf(HudLine("Fruit Digging", GuiTheme.TEXT_WARN), HudLine("Dragonfruit (1200): 1", 0xFFFFFFFF.toInt()),
            HudLine("Mango (300): 10", 0xFFFFFFFF.toInt())),
        setOf(Island.HUB),
    )
    val solverHud = TextLinesPanel(
        "carnival_fruit_solver", "Fruit Digging Solver", this,
        { fruitActive() && s.showBestDig.value() }, ::solverLines,
        listOf(HudLine("Fruit Digging Solver", GuiTheme.TEXT_WARN), HudLine("Rounds: 3/15", 0xFFAAAAAA.toInt()),
            HudLine("Dig: C4", 0xFF55FF55.toInt()), HudLine("Use: Carnival Shovel (Anchor)", 0xFFAAAAAA.toInt()),
            HudLine("Bomb: 12.0%  Samples: 500", 0xFFFF5555.toInt())),
        setOf(Island.HUB),
    )
    val goalsHud = TextLinesPanel(
        "carnival_goals", "Carnival Goals", this,
        { inArea && s.showGoals.value() }, ::goalLines,
        listOf(HudLine("Fruit Digging", 0xFFFFAA00.toInt()), HudLine(" Play 3 games", 0xFFFFFFFF.toInt()),
            HudLine("Zombie Shootout", 0xFFFF5555.toInt()), HudLine(" Shoot 60 Zombies", 0xFFFFFFFF.toInt())),
        setOf(Island.HUB),
    )
    val lampHud = TextLinesPanel(
        "carnival_lamp_timer", "Lantern Timer", this,
        { shootoutActive() && s.lampTimer.value() && lampPos != null }, ::lampLines,
        listOf(HudLine("Lamp disappears in 4.2s", 0xFF55FF55.toInt())),
        setOf(Island.HUB),
    )
    val tokenShopHud = TextLinesPanel(
        "carnival_token_shop", "Token Shop Upgrades", this,
        { s.tokenShopHelper.value() && openShop != null }, ::tokenShopLines,
        listOf(HudLine("Remaining Fishing Festival Upgrades", 0xFF55FFFF.toInt()),
            HudLine("  Fishing Prodigy 2 -> 5: 600", 0xFF55FF55.toInt()), HudLine("Tokens Owned: 120", 0xFFAAAAAA.toInt())),
    )

    override fun onEnable() {
        store.load()
        diagnostics.enable(
            "fruitDigging=${s.fruitDigging.value()} zombieShootout=${s.zombieShootout.value()} goals=${s.showGoals.value()} " +
                "reminder=${s.ticketReminder.value()} quickStart=${s.doubleClickStart.value()} tokenShop=${s.tokenShopHelper.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        game.reset()
        revealedStands.clear()
        anchorItems.clear()
        shootoutStarted = false
        zombies = emptyList()
        zombieSeen.clear()
        lampPos = null
        openShop = null
        lastScreenKey = null
    }

    fun onWorldChanged() {
        if (!isEnabled()) return
        reset()
        inArea = false
        diagnostics.event("WORLD_CHANGED")
    }

    private fun fruitActive() = isEnabled() && s.fruitDigging.value() && inArea && game.playing

    private fun shootoutActive() = isEnabled() && s.zombieShootout.value() && inArea && shootoutStarted

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        tickCount++
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (areaPollTicks++ >= AREA_POLL_TICKS) {
            areaPollTicks = 0
            val now = HypixelModApi.currentIsland() == Island.HUB && sidebarInCarnival(SlayerSidebar.read(level.scoreboard))
            if (now != inArea) {
                inArea = now
                diagnostics.event("AREA inCarnival=$now")
                if (!now) reset()
            }
        }
        tickReminder()
        tickScreens(mc)
        if (fruitActive()) {
            if (tickCount % 2L == 0L) scanBoardEntities(level)
            if (game.updateRecommendation()) solverRuns++
        }
        if (shootoutActive() && tickCount % 2L == 0L) scanZombies(level)
        diagnostics.statusOnChange {
            "inArea=$inArea fruitPlaying=${game.playing} digs=${game.digsUsed} recommendation=${game.recommendation?.let { FruitDiggingGame.label(it.targetRow, it.targetCol) }} " +
                "solverRuns=$solverRuns reveals=$reveals anchors=$anchors shootout=$shootoutStarted zombies=${zombies.size} " +
                "lamp=${lampPos != null} shop=${openShop?.name} goalsOpen=${store.openGoals().size}"
        }
    }

    fun onChatMessage(message: String) {
        if (!isEnabled()) return
        val text = message.trim()
        if (CarnivalPatterns.TICKETS_CLAIMED.matches(text) || CarnivalPatterns.TICKETS_ALREADY.matches(text)) {
            store.markClaimed(LocalDate.now(ZoneOffset.UTC))
            diagnostics.event("TICKETS_CLAIMED")
        }
        if (!inArea) return
        CarnivalGoal.fromChat(text)?.let {
            store.setGoal(it, true, CarnivalStore.skyblockYear(System.currentTimeMillis()))
            diagnostics.event("GOAL_DONE goal=$it")
        }
        if (CarnivalPatterns.SELECT_OPTION.matches(text)) lastSelectOptionMs = System.currentTimeMillis()
        if (s.fruitDigging.value() && game.onChat(text)) {
            if (!game.playing) {
                revealedStands.clear()
                anchorItems.clear()
            }
            diagnostics.event("FRUIT_CHAT playing=${game.playing} digs=${game.digsUsed}")
        }
        if (s.zombieShootout.value()) {
            if (CarnivalPatterns.SHOOTOUT_START.matches(text)) {
                shootoutStarted = true
                diagnostics.event("SHOOTOUT_START")
            } else if (CarnivalPatterns.SHOOTOUT_END.matches(text)) {
                shootoutStarted = false
                zombies = emptyList()
                zombieSeen.clear()
                lampPos = null
                diagnostics.event("SHOOTOUT_END")
            }
        }
    }

    /** Every client-side block change; only Carnival board and lamp changes matter. */
    fun onBlockChanged(pos: BlockPos, old: BlockState, new: BlockState) {
        if (!isEnabled() || !inArea) return
        if (s.fruitDigging.value() && pos.y == FruitDiggingGame.GRID_Y) {
            val row = FruitDiggingGame.rowOf(pos.z.toDouble())
            val col = FruitDiggingGame.colOf(pos.x.toDouble())
            if (FruitDiggingGame.valid(row, col) && old.`is`(Blocks.SAND)) {
                game.onBlockChange(row, col, FruitDiggingGame.Block.SAND, boardBlock(new))
                diagnostics.event("DIG cell=${FruitDiggingGame.label(row, col)} new=${boardBlock(new)}")
            }
        }
        if (shootoutActive() && old.`is`(Blocks.REDSTONE_LAMP) && new.`is`(Blocks.REDSTONE_LAMP)) {
            val wasLit = old.getValue(BlockStateProperties.LIT)
            val lit = new.getValue(BlockStateProperties.LIT)
            if (!wasLit && lit) {
                lampPos = pos.immutable()
                lampSince = System.currentTimeMillis()
            } else if (wasLit && !lit && pos == lampPos) {
                lampPos = null
            }
        }
    }

    /** Right-click on a Carnival game master within 5 s of its option prompt starts the game; returns FAIL to cancel the click. */
    fun onEntityUse(entity: Entity): InteractionResult {
        if (!isEnabled() || !s.doubleClickStart.value() || !inArea) return InteractionResult.PASS
        val now = System.currentTimeMillis()
        if (now - lastSelectOptionMs > QUICK_START_WINDOW_MS) return InteractionResult.PASS
        val npc = npcName(entity) ?: return InteractionResult.PASS
        val npcId = CarnivalPatterns.npcId(npc) ?: return InteractionResult.PASS
        if (now - lastQuickStartMs < 1_000L) return InteractionResult.PASS
        lastQuickStartMs = now
        val connection = Minecraft.getInstance().connection ?: return InteractionResult.PASS
        val tag = CompoundTag()
        tag.putString("npcId", npcId)
        tag.putString("responseKey", "r_2_1")
        connection.send(ServerboundCustomClickActionPacket(Identifier.parse("skyblock:dialogue_response"), Optional.of(tag)))
        diagnostics.event("QUICK_START npc=$npcId")
        return InteractionResult.FAIL
    }

    private fun npcName(entity: Entity): String? {
        // Hypixel NPC names sit on an armor stand just above the body.
        val box = entity.boundingBox.inflate(0.3, 2.5, 0.3)
        val level = entity.level()
        for (stand in level.getEntitiesOfClass(ArmorStand::class.java, box)) {
            val name = stand.customName?.string?.let { ChatText.plain(it).trim() } ?: continue
            if (CarnivalPatterns.npcId(name) != null) return name
        }
        return entity.customName?.string?.let { ChatText.plain(it).trim() }
    }

    private fun scanBoardEntities(level: net.minecraft.client.multiplayer.ClientLevel) {
        val board = AABB(
            FruitDiggingGame.START_X.toDouble() - 1, FruitDiggingGame.GRID_Y.toDouble() - 1, FruitDiggingGame.START_Z.toDouble() - 1,
            FruitDiggingGame.START_X + FruitDiggingGame.GRID + 1.0, FruitDiggingGame.GRID_Y + 4.0, FruitDiggingGame.START_Z + FruitDiggingGame.GRID + 1.0,
        )
        for (stand in level.getEntitiesOfClass(ArmorStand::class.java, board)) {
            if (stand.id in revealedStands) continue
            val name = stand.customName?.string?.let { ChatText.plain(it).trim() } ?: continue
            if (name.isBlank()) continue
            val row = FruitDiggingGame.rowOf(stand.z)
            val col = FruitDiggingGame.colOf(stand.x)
            if (!FruitDiggingGame.valid(row, col) || !CarnivalPatterns.REVEAL.matches(name)) continue
            if (CarnivalFruit.fromName(CarnivalPatterns.REVEAL.matchEntire(name)!!.groups["name"]!!.value) == null) continue
            revealedStands.add(stand.id)
            game.onReveal(row, col, name)
            reveals++
        }
        for (item in level.getEntitiesOfClass(ItemEntity::class.java, board)) {
            if (item.id in anchorItems) continue
            val fruit = CarnivalFruit.fromTexture(PestDetector.headTexture(item.item)) ?: continue
            anchorItems.add(item.id)
            game.onAnchorItem(FruitDiggingGame.rowOf(item.z), FruitDiggingGame.colOf(item.x), fruit)
            anchors++
        }
    }

    private fun scanZombies(level: net.minecraft.client.multiplayer.ClientLevel) {
        val player = Minecraft.getInstance().player ?: return
        val found = ArrayList<ShootoutZombie>()
        for (zombie in level.getEntitiesOfClass(Zombie::class.java, player.boundingBox.inflate(ZOMBIE_RADIUS))) {
            if (zombie.isDeadOrDying || zombie.health <= 0f) continue
            val helmet = zombie.getItemBySlot(EquipmentSlot.HEAD)
            if (helmet.isEmpty) continue
            val type = ZombieType.entries.find { helmet.`is`(it.helmet) } ?: continue
            found.add(ShootoutZombie(zombie, type))
        }
        maxType = found.maxByOrNull { it.type.points }?.type ?: ZombieType.LEATHER
        zombies = found
        val now = System.currentTimeMillis()
        for (zombie in found) zombieSeen.putIfAbsent(zombie.entity.id, now)
        zombieSeen.entries.removeIf { (id, since) -> found.none { it.entity.id == id } && now - since > 15_000L }
    }

    private fun tickReminder() {
        if (!s.ticketReminder.value()) return
        val now = System.currentTimeMillis()
        if (now < nextReminderMs) return
        nextReminderMs = now + REMINDER_INTERVAL_MS
        if (HypixelModApi.currentIsland() == Island.NONE) return
        CarnivalStore.refreshMayor()
        if (!CarnivalStore.carnivalPerkActive()) return
        if (store.claimedOn(LocalDate.now(ZoneOffset.UTC))) return
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null) return
        val message = Component.literal("[GeilerAddons] Carnival Tickets are ready to be claimed! Click to warp to the Carnival.")
            .withStyle { it.withColor(0xFFAA00).withClickEvent(ClickEvent.RunCommand("/warp carnival")) }
        mc.gui.hud.chat.addClientSystemMessage(message)
        diagnostics.event("TICKET_REMINDER")
    }

    private fun tickScreens(mc: Minecraft) {
        // The terminal simulator is a local container screen, never a Carnival menu.
        val screen = (mc.gui.screen() as? AbstractContainerScreen<*>)?.takeUnless { it is geiler.addons.client.termsim.TermSimScreen }
        if (screen == null) {
            if (openShop != null || lastScreenKey != null) {
                openShop = null
                lastScreenKey = null
            }
            return
        }
        val title = ChatText.plain(screen.title.string).trim()
        val slots = screen.menu.slots
        // Contents arrive a few ticks after the screen; re-read while the item set changes.
        val key = title + "|" + slots.sumOf { if (it.hasItem()) it.item.hashCode() else 0 }
        if (key == lastScreenKey) return
        lastScreenKey = key
        if (inArea && s.showGoals.value() && title == CarnivalPatterns.GOALS_INVENTORY) readGoals(slots.map { it.item })
        if (s.tokenShopHelper.value()) readTokenShop(title, slots.map { it.item })
    }

    private fun readGoals(items: List<ItemStack>) {
        val year = CarnivalStore.skyblockYear(System.currentTimeMillis())
        var read = 0
        for (stack in items) {
            val game = when {
                stack.`is`(Items.SAND) -> CarnivalGoal.Game.FRUIT_DIGGING
                stack.`is`(Items.COD) -> CarnivalGoal.Game.CATCH_A_FISH
                stack.`is`(Items.ARROW) -> CarnivalGoal.Game.ZOMBIE_SHOOTOUT
                else -> continue
            }
            val lore = lore(stack)
            val goal = CarnivalGoal.fromLore(game, lore.firstOrNull() ?: continue) ?: continue
            store.setGoal(goal, lore.lastOrNull()?.trim() == CarnivalPatterns.GOAL_COMPLETE, year)
            read++
        }
        diagnostics.event("GOALS_READ count=$read")
    }

    private fun readTokenShop(title: String, items: List<ItemStack>) {
        val shops = TokenShopProgress.shops()
        val shop = shops.firstOrNull { it.name.equals(title, ignoreCase = true) }
        if (shop == null) {
            if (shops.isEmpty()) TokenShopProgress.requestShops()
            openShop = null
            return
        }
        val purchased = HashMap<String, Int>()
        for (slot in SHOP_SLOTS) {
            val stack = items.getOrNull(slot) ?: continue
            if (stack.isEmpty) continue
            val name = ChatText.plain(stack.hoverName.string).trim()
            val match = CarnivalPatterns.UPGRADE_NAME.matchEntire(name) ?: continue
            val tier = CarnivalPatterns.roman(match.groups["tier"]!!.value)
            if (tier <= 0) continue
            val maxed = lore(stack).any { it.trim().endsWith(CarnivalPatterns.UPGRADE_MAXED) }
            purchased[match.groups["upgrade"]!!.value] = if (maxed) tier else tier - 1
        }
        items.getOrNull(SHOP_FOOTER_SLOT)?.takeIf { ChatText.plain(it.hoverName.string).trim() == "Carnival Tokens" }?.let { footer ->
            lore(footer).firstNotNullOfOrNull { CarnivalPatterns.TOKENS.matchEntire(it.trim()) }?.let {
                tokensOwned = it.groups["tokens"]!!.value.replace(",", "").toIntOrNull() ?: 0
            }
        }
        openShop = shop
        store.saveShop(shop.name, purchased)
        diagnostics.event("TOKEN_SHOP shop=${shop.name} upgrades=${purchased.size} tokens=$tokensOwned")
    }

    private fun lore(stack: ItemStack): List<String> =
        stack.get(DataComponents.LORE)?.lines()?.map { ChatText.plain(it.string) } ?: emptyList()

    private fun boardBlock(state: BlockState): FruitDiggingGame.Block = when {
        state.`is`(Blocks.SAND) -> FruitDiggingGame.Block.SAND
        state.`is`(Blocks.SANDSTONE) -> FruitDiggingGame.Block.SANDSTONE
        state.`is`(Blocks.SANDSTONE_STAIRS) -> FruitDiggingGame.Block.SANDSTONE_STAIRS
        else -> FruitDiggingGame.Block.OTHER
    }

    // ---- HUD panel content ----

    private fun remainingLines(): List<HudLine> {
        val rows = CarnivalFruit.entries.filter { game.remaining(it) > 0 }
            .sortedWith(compareByDescending<CarnivalFruit> { game.pointValue(it) }.thenBy { it.inGameName })
            .map { HudLine("${it.inGameName} (${game.pointValue(it)}): ${game.remaining(it)}", 0xFFFFFFFF.toInt()) }
        if (rows.isEmpty()) return emptyList()
        return listOf(HudLine("Fruit Digging", GuiTheme.TEXT_WARN)) + rows
    }

    private fun solverLines(): List<HudLine> {
        val rec = game.recommendation ?: return emptyList()
        return listOf(
            HudLine("Fruit Digging Solver", GuiTheme.TEXT_WARN),
            HudLine("Rounds: ${game.digsUsed}/${geiler.addons.client.carnival.FruitDiggingSolver.MAX_DIGS}", 0xFFAAAAAA.toInt()),
            HudLine("Dig: ${FruitDiggingGame.label(rec.targetRow, rec.targetCol)}", 0xFF55FF55.toInt()),
            HudLine("Use: Carnival Shovel (${rec.shovel})", 0xFFAAAAAA.toInt()),
            HudLine("Bomb: ${percent(rec.pBomb)}  Samples: ${rec.samples}", 0xFFFF5555.toInt()),
        )
    }

    private fun goalLines(): List<HudLine> {
        val open = store.openGoals()
        val lines = ArrayList<HudLine>()
        for (gameType in CarnivalGoal.Game.entries) {
            val goals = open.filter { it.game == gameType }
            if (goals.isEmpty()) continue
            lines.add(HudLine(gameType.title, GAME_COLORS.getValue(gameType)))
            goals.forEach { lines.add(HudLine(" ${it.display}", 0xFFFFFFFF.toInt())) }
        }
        return lines
    }

    private fun lampLines(): List<HudLine> {
        if (lampPos == null) return emptyList()
        val left = (LAMP_LIFETIME_MS - (System.currentTimeMillis() - lampSince)).coerceAtLeast(0L)
        return listOf(HudLine("Lamp disappears in ${seconds(left)}", timerColor(left, LAMP_LIFETIME_MS)))
    }

    private fun tokenShopLines(): List<HudLine> {
        val shop = openShop ?: return emptyList()
        val progress = TokenShopProgress.progress(shop, store.shopProgress(shop.name))
        val lines = ArrayList<HudLine>()
        lines.add(HudLine("Remaining ${shop.name} Upgrades", 0xFF55FFFF.toInt()))
        val remaining = progress.upgrades.filter { it.remainingCosts.isNotEmpty() }
        if (remaining.isEmpty()) {
            lines.add(HudLine("All upgrades purchased!", 0xFF55FF55.toInt()))
        } else {
            remaining.forEach {
                lines.add(HudLine("  ${it.name} ${it.currentLevel} -> ${it.maxLevel}: ${"%,d".format(it.remainingCosts.sum())}", 0xFF55FF55.toInt()))
            }
            val total = progress.remainingSum
            lines.add(HudLine("Total Tokens Needed: ${"%,d".format(total)}", 0xFFAAAAAA.toInt()))
            if (tokensOwned > 0) lines.add(HudLine("Tokens Owned: ${"%,d".format(tokensOwned)}", 0xFFAAAAAA.toInt()))
            val need = total - tokensOwned
            lines.add(if (need > 0) HudLine("Additional Tokens Needed: ${"%,d".format(need)}", 0xFFAAAAAA.toInt())
                else HudLine("You have enough tokens", 0xFFFFFF55.toInt()))
        }
        val overview = TokenShopProgress.shops().map { s2 ->
            val saved = store.shopProgress(s2.name) ?: return@map HudLine("${s2.name}: open shop to load...", 0xFFFF5555.toInt())
            val sum = TokenShopProgress.progress(s2, saved).remainingSum
            if (sum == 0) HudLine("${s2.name}: all upgrades purchased!", 0xFF55FF55.toInt())
            else HudLine("${s2.name}: ${"%,d".format(sum)} tokens needed", 0xFFAAAAAA.toInt())
        }
        if (overview.size > 1) {
            lines.add(HudLine("", 0))
            lines.addAll(overview)
        }
        return lines
    }

    // ---- world drawing ----

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !inArea) return
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        var drew = false
        if (fruitActive()) {
            for (row in 0 until FruitDiggingGame.GRID) for (col in 0 until FruitDiggingGame.GRID) {
                if (game.cell(row, col).diggable) continue
                EspRenderer.renderBox(
                    context.poseStack(), buffers,
                    FruitDiggingGame.START_X + col - camera.x, FruitDiggingGame.GRID_Y - camera.y, FruitDiggingGame.START_Z + row - camera.z,
                    1.0, 1.1, 1.0, 0x80808080.toInt(), 0, 0f, true,
                )
                drew = true
            }
            val rec = game.recommendation
            if (s.showBestDig.value() && rec != null && game.cell(rec.targetRow, rec.targetCol).diggable) {
                val color = s.bestDigColor.argb()
                EspRenderer.renderBox(
                    context.poseStack(), buffers,
                    FruitDiggingGame.START_X + rec.targetCol - camera.x, FruitDiggingGame.GRID_Y - camera.y,
                    FruitDiggingGame.START_Z + rec.targetRow - camera.z, 1.0, 1.0, 1.0,
                    color and 0x00FFFFFF or (0x5A shl 24), color, 2f, false,
                )
                drew = true
            }
        }
        if (shootoutActive()) {
            if (s.coloredHitboxes.value()) {
                lampPos?.let {
                    EspRenderer.renderBox(context.poseStack(), buffers, it.x - camera.x, it.y - camera.y, it.z - camera.z,
                        1.0, 1.0, 1.0, 0x80FF0000.toInt(), 0xFFFF0000.toInt(), 2f, false)
                    drew = true
                }
                for (zombie in drawnZombies()) {
                    if (zombie.entity.isRemoved) continue
                    val box = ProjectedLabelRenderer.entityBox(zombie.entity).inflate(0.1, 0.05, 0.0).move(0.0, 0.05, 0.0)
                    EspRenderer.renderBox(context.poseStack(), buffers, box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z,
                        box.xsize, box.ysize, box.zsize, 0, zombie.type.argb, 3f, false)
                    drew = true
                }
            }
            if (s.coloredLine.value()) lampPos?.let {
                val end = BlockEspTracerGeometry.endpoints(camera, mc.gameRenderer.mainCamera().forwardVector(),
                    Vec3(it.x + 0.5, it.y + 0.5, it.z + 0.5), 0.5)
                EspRenderer.renderLine(context.poseStack(), buffers, end.start.x, end.start.y, end.start.z,
                    end.end.x, end.end.y, end.end.z, 0xFFFF0000.toInt(), 3f, false)
                drew = true
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun drawnZombies(): List<ShootoutZombie> =
        if (s.highestOnly.value()) zombies.filter { it.type == maxType } else zombies

    /** World labels (fruit names, clues, best dig, zombie timers), projected through the shared label renderer. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !inArea) return
        val mc = Minecraft.getInstance()
        if (mc.gui.hud.isHidden) return
        val camera = mc.gameRenderer.mainCamera()
        if (fruitActive()) drawFruitLabels(graphics, mc, camera)
        if (shootoutActive() && s.zombieTimer.value()) drawZombieTimers(graphics, mc, camera)
    }

    private fun drawFruitLabels(graphics: GuiGraphicsExtractor, mc: Minecraft, camera: net.minecraft.client.Camera) {
        for (row in 0 until FruitDiggingGame.GRID) for (col in 0 until FruitDiggingGame.GRID) {
            val cell = game.cell(row, col)
            val labels = ArrayList<HudLine>()
            if (s.showFound.value() && !cell.diggable && cell.found() != CarnivalFruit.UNKNOWN) {
                labels.add(HudLine(cell.found().inGameName, s.foundColor.argb()))
            }
            if (s.showGuesses.value() && cell.uncovered == CarnivalFruit.UNKNOWN && cell.diggable && cell.solved != CarnivalFruit.UNKNOWN) {
                labels.add(HudLine("${cell.solved.inGameName} (${game.pointValue(cell.solved)})", s.guessColor.argb()))
            }
            if (s.showTreasure.value()) {
                if (cell.treasure != CarnivalFruit.UNKNOWN) labels.add(HudLine("<= ${cell.treasure.inGameName}", s.adjacentColor.argb()))
                if (cell.anchored != CarnivalFruit.UNKNOWN && cell.anchored != cell.treasure) {
                    labels.add(HudLine(">= ${cell.anchored.inGameName}", s.adjacentColor.argb()))
                }
            }
            if (s.showMines.value()) cell.adjacentMines?.let { labels.add(HudLine(it.toString(), s.minesColor.argb())) }
            if (labels.isEmpty()) continue
            val base = Vec3(FruitDiggingGame.START_X + col + 0.5, FruitDiggingGame.GRID_Y + 1.1, FruitDiggingGame.START_Z + row + 0.5)
            val start = -5f * (labels.size - 1)
            labels.forEachIndexed { i, label ->
                ProjectedLabelRenderer.drawFloatingText(graphics, camera, mc.font, base, label.text, label.argb, 0.8f, start + i * 10f)
            }
        }
        val rec = game.recommendation
        if (s.showBestDig.value() && rec != null && game.cell(rec.targetRow, rec.targetCol).diggable) {
            val pos = Vec3(FruitDiggingGame.START_X + rec.targetCol + 0.5, FruitDiggingGame.GRID_Y + 1.35, FruitDiggingGame.START_Z + rec.targetRow + 0.5)
            ProjectedLabelRenderer.draw(graphics, camera, mc.font, pos,
                "Dig HERE with (${rec.shovel}) ${percent(rec.pBomb)} bomb", s.bestDigColor.argb(), 1.0f)
        }
    }

    private fun drawZombieTimers(graphics: GuiGraphicsExtractor, mc: Minecraft, camera: net.minecraft.client.Camera) {
        val now = System.currentTimeMillis()
        for (zombie in zombies) {
            if (s.highestOnly.value() && zombie.type != maxType) continue
            val since = zombieSeen[zombie.entity.id] ?: continue
            val left = zombie.type.lifetimeMs - (now - since)
            if (left <= 0L) continue
            val box = ProjectedLabelRenderer.entityBox(zombie.entity)
            val height = if (zombie.entity.isBaby) box.ysize / 2 else box.ysize
            val pos = Vec3((box.minX + box.maxX) / 2, box.minY + height + 0.5, (box.minZ + box.maxZ) / 2)
            ProjectedLabelRenderer.drawFloatingText(graphics, camera, mc.font, pos, seconds(left), timerColor(left, zombie.type.lifetimeMs), 1.25f)
        }
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Carnival")
        val fruitDigging = spec.toggle("fruitDigging")
        val showFound = spec.toggle("showFound")
        val foundColor = spec.color("foundColor")
        val showTreasure = spec.toggle("showTreasure")
        val adjacentColor = spec.color("adjacentColor")
        val showMines = spec.toggle("showMines")
        val minesColor = spec.color("minesColor")
        val showGuesses = spec.toggle("showGuesses")
        val guessColor = spec.color("guessColor")
        val showBestDig = spec.toggle("showBestDig")
        val bestDigColor = spec.color("bestDigColor")
        val remainingFruit = spec.toggle("remainingFruit")
        val zombieShootout = spec.toggle("zombieShootout")
        val coloredHitboxes = spec.toggle("coloredHitboxes")
        val zombieTimer = spec.toggle("zombieTimer")
        val highestOnly = spec.toggle("highestOnly")
        val coloredLine = spec.toggle("coloredLine")
        val lampTimer = spec.toggle("lampTimer")
        val ticketReminder = spec.toggle("ticketReminder")
        val showGoals = spec.toggle("showGoals")
        val doubleClickStart = spec.toggle("doubleClickStart")
        val tokenShopHelper = spec.toggle("tokenShopHelper")
    }

    companion object {
        private const val AREA_POLL_TICKS = 20
        private const val QUICK_START_WINDOW_MS = 5_000L
        private const val REMINDER_INTERVAL_MS = 5 * 60_000L
        private const val LAMP_LIFETIME_MS = 6_000L
        private const val ZOMBIE_RADIUS = 50.0
        private val SHOP_SLOTS = 11..15
        private const val SHOP_FOOTER_SLOT = 32
        private val GAME_COLORS = mapOf(
            CarnivalGoal.Game.FRUIT_DIGGING to 0xFFFFAA00.toInt(),
            CarnivalGoal.Game.CATCH_A_FISH to 0xFF00AAAA.toInt(),
            CarnivalGoal.Game.ZOMBIE_SHOOTOUT to 0xFFFF5555.toInt(),
        )

        @JvmField
        val INSTANCE = CarnivalModule(Settings())

        /** The sidebar location row (the one with the ⏣ marker) names the Carnival. */
        @JvmStatic
        fun sidebarInCarnival(sidebar: String): Boolean =
            sidebar.lineSequence().any { it.contains('⏣') && it.contains("Carnival") }

        @JvmStatic
        fun percent(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f%%", value * 100)

        @JvmStatic
        fun seconds(ms: Long): String = String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)

        /** Green for the first third of a lifetime, yellow for the second, red for the last (SkyHanni's prefix rule). */
        @JvmStatic
        fun timerColor(leftMs: Long, lifetimeMs: Long): Int = when {
            leftMs >= lifetimeMs * 2 / 3 -> 0xFF55FF55.toInt()
            leftMs >= lifetimeMs / 3 -> 0xFFFFFF55.toInt()
            else -> 0xFFFF5555.toInt()
        }
    }
}
