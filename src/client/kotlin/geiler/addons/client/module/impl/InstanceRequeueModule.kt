package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import net.minecraft.client.Minecraft

/** Sends /instancerequeue a few seconds after a dungeon run ends (the Extra Stats line). */
class InstanceRequeueModule private constructor(
    private val delay: NumberSetting,
    private val disableOnLeave: BooleanSetting,
    private val announceKick: BooleanSetting,
) : Module(
    "Instance Requeue",
    "Automatically requeues the dungeon a few seconds after a run ends.",
    Category.F7,
    false,
    delay,
    disableOnLeave,
    announceKick,
) {
    companion object {
        @JvmField
        val INSTANCE = InstanceRequeueModule(
            NumberSetting("Requeue Delay", 0f, 30f, 2f, true),
            BooleanSetting("Disable On Leave/Kick", true),
            BooleanSetting("Announce Kick", false),
        )
    }

    private val logic = InstanceRequeueLogic()
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() })
    private var pendingTicks = -1
    private var lastLevel: Any? = null
    private var requeues = 0

    override fun onEnable() {
        logic.reset()
        pendingTicks = -1
        diagnostics.enable("delaySeconds=${delay.intValue()},disableOnLeave=${disableOnLeave.value()},announceKick=${announceKick.value()}")
    }

    override fun onDisable() {
        pendingTicks = -1
        diagnostics.disable()
    }

    /** Called for each plain game chat line. */
    fun onChatMessage(content: String) {
        if (!isEnabled()) return
        when (logic.onChat(content, announceKick.value(), disableOnLeave.value())) {
            InstanceRequeueLogic.Action.SCHEDULE_REQUEUE -> {
                pendingTicks = InstanceRequeueLogic.delayTicks(delay.intValue())
                diagnostics.event("RUN_END scheduled delayTicks=$pendingTicks")
            }
            InstanceRequeueLogic.Action.ANNOUNCE_KICK -> {
                Minecraft.getInstance().player?.connection?.sendCommand("pc I was kicked!")
                diagnostics.event("KICK announced")
            }
            InstanceRequeueLogic.Action.NONE -> {
                if (!logic.canFire() && pendingTicks >= 0) {
                    pendingTicks = -1
                    diagnostics.event("CANCELLED party leave during delay")
                }
            }
        }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        if (client.level !== lastLevel) {
            lastLevel = client.level
            logic.reset()
            pendingTicks = -1
        }
        if (pendingTicks >= 0 && --pendingTicks < 0) fire(client)
        diagnostics.status { suppressed ->
            "pendingTicks=$pendingTicks canFire=${logic.canFire()} requeuesSinceEnable=$requeues suppressedEvents=$suppressed"
        }
    }

    private fun fire(client: Minecraft) {
        val player = client.player
        if (player == null || !logic.canFire()) {
            diagnostics.event("SKIPPED player=${player != null} canFire=${logic.canFire()}")
            return
        }
        player.connection.sendCommand("instancerequeue")
        requeues++
        diagnostics.event("REQUEUE sent")
    }
}
