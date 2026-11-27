package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.f7.AutoCroesusRules
import geiler.addons.client.dungeon.f7.CroesusPrices
import geiler.addons.client.dungeon.f7.CroesusRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.termsim.TermSimScreen
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.concurrent.ThreadLocalRandom

/**
 * Claims the Croesus chests for you. Open the Croesus menu and press the start key: it opens each run with no chest
 * opened, claims the most profitable chest (and a second one with a Dungeon Chest Key, or rerolls Bedrock with a Kismet
 * Feather, when set), and walks back with the menu's Go Back arrow and through the pages. The screen is never closed and
 * the NPC never clicked. Each kind of click waits its own pause drawn between two values. The key stops it at once; a
 * closed or unexpected screen or a menu that does not answer stops it too.
 */
class AutoCroesusModule private constructor() : Module(
    "Auto Croesus",
    "Claims your Croesus chests from the open menu: best chest, optional key chest and Kismet, with random delays.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val startKey = spec.text("startKey")
    private val minProfit = spec.slider("minProfit")
    private val useKeys = spec.toggle("useKeys")
    private val keyMinProfit = spec.slider("keyMinProfit")
    private val kismet = spec.toggle("kismet")
    private val kismetMinProfit = spec.slider("kismetMinProfit")
    private val kismetFloors = spec.text("kismetFloors")
    private val runMin = spec.slider("runMin")
    private val runMax = spec.slider("runMax")
    private val chestMin = spec.slider("chestMin")
    private val chestMax = spec.slider("chestMax")
    private val claimMin = spec.slider("claimMin")
    private val claimMax = spec.slider("claimMax")
    private val backMin = spec.slider("backMin")
    private val backMax = spec.slider("backMax")
    private val pageMin = spec.slider("pageMin")
    private val pageMax = spec.slider("pageMax")
    private val kismetDelayMin = spec.slider("kismetDelayMin")
    private val kismetDelayMax = spec.slider("kismetDelayMax")
    private val responseTimeout = spec.slider("responseTimeout")

    private var running = false
    private var run = AutoCroesusRules.Run()
    private var runKey = ""
    private val skipped = HashSet<String>()

    /** The click chosen for the screen as it is now, sent at [fireAtMs] if the screen has not changed by then. */
    private var pending: AutoCroesusRules.Decision? = null
    private var fireAtMs = 0L

    /** After a click: the screen and state it was sent on; nothing more is done until either changes. */
    private var sentOn: Any? = null
    private var sentState = -1
    private var sentAtMs = 0L
    private var lastWaitReason = ""
    private var claims = 0
    private var clicks = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 10, 100L, 400)

    override fun onEnable() {
        stop(null)
        diagnostics.enable(
            "key=${ContainerKeybindRules.normalize(startKey.value())} minProfit=${minProfit.value()}m keys=${useKeys.value()}/${keyMinProfit.value()}m " +
                "kismet=${kismet.value()}/${kismetMinProfit.value()}m floors=${kismetFloors.value()} " +
                "delays run=${runMin.intValue()}..${runMax.intValue()} chest=${chestMin.intValue()}..${chestMax.intValue()} " +
                "claim=${claimMin.intValue()}..${claimMax.intValue()} back=${backMin.intValue()}..${backMax.intValue()} " +
                "page=${pageMin.intValue()}..${pageMax.intValue()} kismet=${kismetDelayMin.intValue()}..${kismetDelayMax.intValue()} timeout=${responseTimeout.intValue()}ms",
        )
        if (CroesusPrices.prices.isEmpty()) CroesusPrices.refresh { diagnostics.event("PRICES $it") }
    }

    override fun onDisable() {
        stop("disabled")
        diagnostics.event("DISABLE claims=$claims clicks=$clicks")
        diagnostics.disable()
    }

    private fun stop(reason: String?) {
        if (running && reason != null) {
            diagnostics.event("STOP reason=$reason claims=$claims")
            PlayerAlerts.chat("§6[GeilerAddons] §eAuto Croesus stopped: $reason")
        }
        running = false
        pending = null
        sentOn = null
        run = AutoCroesusRules.Run()
        runKey = ""
    }

    /** A key press in a container screen; true when it was the start/stop key. */
    fun onKey(screen: AbstractContainerScreen<*>, pressedKeyName: String): Boolean {
        if (!isEnabled()) return false
        val wanted = ContainerKeybindRules.normalize(startKey.value())
        if (wanted.isEmpty() || wanted != pressedKeyName) return false
        if (running) {
            stop("stop key")
            return true
        }
        if (screen is TermSimScreen || !CroesusRules.isCroesusMenu(screen.title.string)) {
            PlayerAlerts.chat("§6[GeilerAddons] §cOpen the Croesus menu first.")
            return true
        }
        if (CroesusPrices.prices.isEmpty()) {
            PlayerAlerts.chat("§6[GeilerAddons] §cCroesus prices are not loaded yet, try again in a moment.")
            CroesusPrices.refresh { diagnostics.event("PRICES $it") }
            return true
        }
        running = true
        skipped.clear()
        claims = 0
        pending = null
        sentOn = null
        diagnostics.event("START title=${screen.title.string}")
        PlayerAlerts.chat("§6[GeilerAddons] §aAuto Croesus started. Press the key again to stop.")
        return true
    }

    fun tick() {
        if (!isEnabled() || !running) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        val screen = (client.gui.screen() as? AbstractContainerScreen<*>)?.takeIf { it !is TermSimScreen }
        if (player == null || screen == null) {
            stop("screen closed")
            return
        }
        val now = System.currentTimeMillis()
        val stateId = screen.menu.stateId
        if (sentOn != null) {
            if (screen === sentOn && stateId == sentState) {
                if (now - sentAtMs > responseTimeout.value()) stop("menu did not answer")
                return
            }
            sentOn = null
        }
        val title = screen.title.string
        val waiting = pending
        if (waiting != null) {
            if (now < fireAtMs) return
            pending = null
            // Recheck on the screen as it is now: a slot update in the meantime may change the choice.
            val again = decide(screen, title) ?: return
            if (again.action != waiting.action || again.slot != waiting.slot) {
                diagnostics.event("RECHOSE was=$waiting now=$again")
                schedule(again, now)
                return
            }
            send(screen, again, now)
            return
        }
        val decision = decide(screen, title) ?: return
        schedule(decision, now)
    }

    private fun schedule(decision: AutoCroesusRules.Decision, now: Long) {
        when (decision.action) {
            AutoCroesusRules.Action.DONE -> {
                diagnostics.event("DONE claims=$claims skipped=${skipped.size}")
                PlayerAlerts.chat("§6[GeilerAddons] §aAuto Croesus done: $claims chests claimed, ${skipped.size} runs skipped.")
                running = false
                return
            }
            AutoCroesusRules.Action.WAIT -> {
                if (decision.reason != lastWaitReason) diagnostics.event("WAIT ${decision.reason}")
                lastWaitReason = decision.reason
                return
            }
            else -> Unit
        }
        if (decision.slot < 0) {
            stop("no button for ${decision.action}")
            return
        }
        val (min, max) = when (decision.action) {
            AutoCroesusRules.Action.RUN -> runMin to runMax
            AutoCroesusRules.Action.PAGE -> pageMin to pageMax
            AutoCroesusRules.Action.CHEST -> chestMin to chestMax
            AutoCroesusRules.Action.CLAIM -> claimMin to claimMax
            AutoCroesusRules.Action.KISMET -> kismetDelayMin to kismetDelayMax
            else -> backMin to backMax
        }
        val low = minOf(min.value(), max.value()).toLong()
        val high = maxOf(min.value(), max.value()).toLong()
        val delay = if (high > low) ThreadLocalRandom.current().nextLong(low, high + 1) else low
        pending = decision
        fireAtMs = now + delay
        diagnostics.event("PLAN $decision delayMs=$delay")
    }

    /** The click for the screen as it is now, or null for a screen this module does not know (which stops it). */
    private fun decide(screen: AbstractContainerScreen<*>, title: String): AutoCroesusRules.Decision? {
        val slots = containerSlots(screen)
        val back = slots.firstOrNull { it.item.item == Items.ARROW && it.item.hoverName.string == "Go Back" }?.index ?: -1
        return when {
            CroesusRules.isCroesusMenu(title) -> {
                val runs = slots.filter { it.item.item == Items.PLAYER_HEAD && it.item.hoverName.string in RUN_NAMES }
                    .map {
                        AutoCroesusRules.MenuRun(
                            it.index, lore(it.item).any { line -> line.contains(AutoCroesusRules.FRESH_LINE) },
                            croesusStruckModifier(it.item, "Kismet Feather"),
                        )
                    }
                val next = slots.firstOrNull { it.item.item == Items.ARROW && it.item.hoverName.string == "Next Page" }?.index ?: -1
                val skippedHere = skipped.filter { it.startsWith("$title#") }.map { it.substringAfter('#').toInt() }.toSet()
                AutoCroesusRules.menu(runs, skippedHere, next).also {
                    if (it.action == AutoCroesusRules.Action.RUN) runKey = "$title#${it.slot}"
                }
            }
            CroesusRules.isChestPreview(title) -> {
                val prices = CroesusModule.INSTANCE.effectivePrices().ifEmpty { CroesusPrices.prices }
                val chests = slots.filter { it.index <= MAX_PREVIEW_SLOT && it.item.item == Items.PLAYER_HEAD }
                    .mapNotNull { CroesusRules.previewChest(it.index, it.item.hoverName.string, lore(it.item), prices) }
                if (chests.isEmpty()) return AutoCroesusRules.Decision(AutoCroesusRules.Action.WAIT, -1, "preview not loaded")
                val settings = AutoCroesusRules.Settings(
                    minProfit.value() * MILLION, useKeys.value(), keyMinProfit.value() * MILLION, CroesusPrices.keyPrice,
                    kismet.value(), kismetMinProfit.value() * MILLION, kismetFloors.value(),
                )
                AutoCroesusRules.preview(chests, run, settings, AutoCroesusRules.floorCode(title), back)
            }
            CroesusRules.isOpenedChest(title) -> {
                val claim = slots.firstOrNull { it.item.hoverName.string == "Open Reward Chest" }?.index ?: -1
                val kismetSlot = slots.firstOrNull { it.item.hoverName.string.contains("Reroll") }
                // "Bring a Kismet Feather" (none on you) or a used reroll ("already rerolled") both mean no reroll here.
                val kismetAvailable = kismetSlot != null && lore(kismetSlot.item).none {
                    it.contains("Bring a Kismet Feather") || it.contains("already", ignoreCase = true)
                }
                AutoCroesusRules.chest(run, openedChestSlot, claim, kismetSlot?.index ?: -1, kismetAvailable, back)
            }
            else -> {
                stop("unexpected screen '$title'")
                null
            }
        }
    }

    /** The preview slot of the chest that was opened last, so the claim is remembered against it. */
    private var openedChestSlot = -1

    private fun send(screen: AbstractContainerScreen<*>, decision: AutoCroesusRules.Decision, now: Long) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val gameMode = client.gameMode ?: return
        when (decision.action) {
            AutoCroesusRules.Action.RUN -> run = AutoCroesusRules.Run.from(decision)
            AutoCroesusRules.Action.CHEST -> openedChestSlot = decision.slot
            AutoCroesusRules.Action.SKIP -> {
                skipped.add(runKey)
                diagnostics.event("SKIP run=$runKey ${decision.reason}")
            }
            else -> Unit
        }
        gameMode.handleContainerInput(screen.menu.containerId, decision.slot, 0, ContainerInput.PICKUP, player)
        clicks++
        if (decision.action == AutoCroesusRules.Action.CLAIM) {
            claims++
            PlayerAlerts.chat("§6[GeilerAddons] §aAuto Croesus: ${decision.reason}")
        }
        AutoCroesusRules.afterClick(run, decision)
        sentOn = screen
        sentState = screen.menu.stateId
        sentAtMs = now
        lastWaitReason = ""
        diagnostics.event("CLICK $decision title=${screen.title.string} clicks=$clicks")
        diagnostics.status { "enabled=true running=$running claims=$claims clicks=$clicks skipped=${skipped.size} suppressedEvents=$it" }
    }

    private fun containerSlots(screen: AbstractContainerScreen<*>): List<Slot> = screen.menu.slots.filter { it.container !is Inventory && !it.item.isEmpty }

    private fun lore(stack: ItemStack): List<String> = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

    companion object {
        private const val MILLION = 1_000_000.0
        private const val MAX_PREVIEW_SLOT = 16
        private val RUN_NAMES = setOf("The Catacombs", "Master Mode The Catacombs")
        private val spec = ModuleSettingsSpec.of("Auto Croesus")

        @JvmField
        val INSTANCE = AutoCroesusModule()
    }
}
