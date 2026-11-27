package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.AutoMaskRules
import geiler.addons.client.dungeon.f7.AutomationLock
import geiler.addons.client.dungeon.helpers.DungeonMaskProcTimers
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.termsim.TermSimScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Items
import java.util.concurrent.ThreadLocalRandom

/**
 * After a Bonzo or Spirit Mask proc in a dungeon: casts the rod in the hotbar once (an autopet rule on the rod swaps to
 * Phoenix), switches back, runs `/stats`, clicks the other mask in that menu to equip it and closes the screen if it is
 * still open. While it runs it holds [AutomationLock], so Auto Simon Says, Auto i4 and Lever Aim Assist neither turn the
 * view nor click. Every step waits a pause drawn between two values; the whole sequence gives up after a timeout.
 */
class AutoMaskModule private constructor() : Module(
    "Auto Mask",
    "After a Bonzo or Spirit Mask proc: casts your rod for Phoenix, then equips the other mask from /stats.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val useRod = spec.toggle("useRod")
    private val delayMin = spec.slider("delayMin")
    private val delayMax = spec.slider("delayMax")
    private val timeout = spec.slider("timeout")

    private val procs = DungeonMaskProcTimers()
    private val sequence = AutoMaskRules.Sequence(ThreadLocalRandom.current())
    private var rodSlot = -1
    private var previousSlot = -1
    private var runs = 0
    private var failures = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        stop("enable")
        runs = 0
        failures = 0
        diagnostics.enable(
            "useRod=${useRod.value()} delay=${delayMin.value().toInt()}..${delayMax.value().toInt()}ms timeout=${timeout.value().toInt()}s",
        )
    }

    override fun onDisable() {
        stop("disable")
        diagnostics.event("DISABLE runs=$runs failures=$failures")
        diagnostics.disable()
    }

    private fun stop(reason: String) {
        if (sequence.running()) diagnostics.event("STOP reason=$reason step=${sequence.step}")
        sequence.stop()
        AutomationLock.release(LOCK_NAME)
        rodSlot = -1
        previousSlot = -1
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val proc = procs.observeChat(plain, DungeonContextTracker.inDungeon(), null) ?: return
        val mask = AutoMaskRules.otherMask(proc.name) ?: return
        if (sequence.running()) {
            diagnostics.event("PROC_IGNORED proc=$proc reason=already-running")
            return
        }
        val player = Minecraft.getInstance().player ?: return
        val now = System.currentTimeMillis()
        if (!AutomationLock.acquire(LOCK_NAME, now)) {
            diagnostics.event("PROC_IGNORED proc=$proc reason=lock-held-by-${AutomationLock.holder(now)}")
            return
        }
        rodSlot = if (useRod.value()) (0 until HOTBAR_SIZE).firstOrNull { player.inventory.getItem(it).item == Items.FISHING_ROD } ?: -1 else -1
        previousSlot = player.inventory.selectedSlot
        sequence.start(now, mask, rodSlot >= 0, delayMin.value().toLong(), delayMax.value().toLong(), timeout.value().toLong() * 1000L)
        runs++
        diagnostics.event("START proc=$proc equip=$mask rodSlot=$rodSlot previousSlot=$previousSlot runs=$runs")
    }

    fun tick() {
        if (!isEnabled()) return
        procs.tick(DungeonContextTracker.inDungeon())
        if (!sequence.running()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val player = client.player
        val gameMode = client.gameMode
        val now = System.currentTimeMillis()
        if (player == null || gameMode == null || !player.isAlive) {
            failures++
            stop("no-player")
            return
        }
        if (sequence.timedOut(now)) {
            failures++
            stop("timeout")
            return
        }
        AutomationLock.acquire(LOCK_NAME, now)
        if (!sequence.due(now)) return
        val min = delayMin.value().toLong()
        val max = delayMax.value().toLong()
        when (sequence.step) {
            AutoMaskRules.Step.ROD_SWITCH -> {
                player.inventory.selectedSlot = rodSlot
                diagnostics.event("ROD_SWITCH slot=$rodSlot")
                sequence.advance(now, min, max)
            }
            AutoMaskRules.Step.ROD_USE -> {
                if (player.inventory.selectedSlot == rodSlot) gameMode.useItem(player, InteractionHand.MAIN_HAND)
                diagnostics.event("ROD_USE held=${player.inventory.selectedSlot}")
                sequence.advance(now, min, max)
            }
            AutoMaskRules.Step.ROD_BACK -> {
                if (Inventory.isHotbarSlot(previousSlot)) player.inventory.selectedSlot = previousSlot
                diagnostics.event("ROD_BACK slot=$previousSlot")
                sequence.advance(now, min, max)
            }
            AutoMaskRules.Step.OPEN_MENU -> {
                player.connection.sendCommand(MENU_COMMAND)
                diagnostics.event("OPEN_MENU command=/$MENU_COMMAND")
                sequence.advance(now, min, max)
            }
            AutoMaskRules.Step.WAIT_MENU -> {
                // Waits for the menu; the timeout ends it if it never opens.
                if (client.gui.screen() is AbstractContainerScreen<*>) {
                    diagnostics.event("MENU_OPEN title=${client.gui.screen()?.title?.string}")
                    sequence.advance(now, min, max)
                }
            }
            AutoMaskRules.Step.CLICK_MASK -> {
                clickMask(client)
                sequence.advance(now, min, max)
            }
            AutoMaskRules.Step.CLOSE -> {
                if (client.gui.screen() is AbstractContainerScreen<*>) player.closeContainer()
                diagnostics.event("CLOSE")
                sequence.advance(now, min, max)
            }
            else -> Unit
        }
        if (!sequence.running()) {
            diagnostics.event("DONE runs=$runs")
            stop("done")
        }
    }

    private fun clickMask(client: Minecraft) {
        val screen = (client.gui.screen() as? AbstractContainerScreen<*>)?.takeIf { it !is TermSimScreen } ?: run {
            diagnostics.event("CLICK_MASK skipped=no-screen")
            return
        }
        val player = client.player ?: return
        val wanted = sequence.wanted ?: return
        val menu = screen.menu
        val slot = menu.slots.firstOrNull { it.container is Inventory && AutoMaskRules.isMask(it.item.hoverName.string, wanted) }
        if (slot == null) {
            failures++
            diagnostics.event("CLICK_MASK missing=$wanted title=${screen.title.string}")
            return
        }
        client.gameMode?.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
        diagnostics.event("CLICK_MASK slot=${slot.index} item=${slot.item.hoverName.string} carried=${!menu.carried.isEmpty}")
        // A menu that lets the item be picked up instead of equipping it: put it straight back, never leave it on the cursor.
        if (!menu.carried.isEmpty) {
            client.gameMode?.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
            diagnostics.event("CLICK_MASK put-back slot=${slot.index}")
        }
    }

    companion object {
        private const val LOCK_NAME = "auto-mask"
        private const val MENU_COMMAND = "stats"
        private const val HOTBAR_SIZE = 9
        private val spec = ModuleSettingsSpec.of("Auto Mask")

        @JvmField
        val INSTANCE = AutoMaskModule()
    }
}
