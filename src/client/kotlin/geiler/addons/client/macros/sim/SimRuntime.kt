package geiler.addons.client.macros.sim

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.Holder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Applies and removes the Sim effects on the integrated server's local player, on the server thread.
 *
 * The client tick publishes what the three features want ([publish]); every server tick [maintain] compares that with
 * the player and applies, re-applies or removes. Applying is only done for what is missing or different (after milk,
 * death, `/effect clear` or a changed level), never refreshed every tick. Only what this class applied is removed.
 * When the world stops ([SERVER_STOPPING][ServerLifecycleEvents.SERVER_STOPPING]) or stops being singleplayer, everything
 * applied is removed before the player data is saved, so an infinite effect can never be written to the save.
 */
object SimRuntime {
    enum class Feature { SPEED, JUMP, ONE_TAP }

    @Volatile private var speedOn = false
    @Volatile private var speedStat = SimMapping.DEFAULT_SPEED_STAT
    @Volatile private var jumpOn = false
    @Volatile private var jumpLevel = SimMapping.DEFAULT_JUMP_LEVEL
    @Volatile private var oneTapOn = false

    /** What has been applied and not yet removed; written on the server thread, read for diagnostics. */
    @Volatile var speedApplied = false
        private set
    @Volatile var jumpApplied = false
        private set
    @Volatile var oneTapApplied = false
        private set

    private val events = Feature.values().associateWith { ConcurrentLinkedQueue<String>() }
    private var registered = false

    /** Registers the server hooks; call once while the mod starts. */
    @JvmStatic
    fun init() {
        if (registered) return
        registered = true
        ServerTickEvents.END_SERVER_TICK.register { server -> serverTick(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> serverStopping(server) }
    }

    /** What the features want right now. Client thread, every tick. */
    @JvmStatic
    fun publish(speedEnabled: Boolean, stat: Double, jumpEnabled: Boolean, level: Int, oneTapEnabled: Boolean) {
        speedOn = speedEnabled
        speedStat = stat
        jumpOn = jumpEnabled
        jumpLevel = level
        oneTapOn = oneTapEnabled
    }

    /** The next line the server thread left for a feature's diagnostics, or null. Client thread. */
    @JvmStatic
    fun pollEvent(feature: Feature): String? = events.getValue(feature).poll()

    private fun note(feature: Feature, text: String) {
        val queue = events.getValue(feature)
        if (queue.size < MAX_QUEUED_EVENTS) queue.add(text)
    }

    private fun serverTick(server: MinecraftServer) {
        val player = hostPlayer(server) ?: return
        maintain(SimGuard.allowedFor(server), player)
    }

    private fun serverStopping(server: MinecraftServer) {
        val player = hostPlayer(server)
        if (player != null) {
            speedOn = false
            jumpOn = false
            oneTapOn = false
            maintain(false, player)
        }
        speedApplied = false
        jumpApplied = false
        oneTapApplied = false
    }

    private fun hostPlayer(server: MinecraftServer): ServerPlayer? =
        server.playerList.players.firstOrNull { server.isSingleplayerOwner(it.nameAndId()) }

    /** One pass over the local player; server thread. */
    private fun maintain(allowed: Boolean, player: ServerPlayer) {
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED)
        if (speed != null) {
            val wanted = SimEffects.speedModifier(speedStat)
            val present = speed.getModifier(SimEffects.SPEED_MODIFIER_ID)
            val action = SimAction.of(allowed, speedOn, present != null, present != null && present.amount() == wanted.amount(), speedApplied)
            when (action) {
                SimAction.APPLY -> {
                    speed.addOrUpdateTransientModifier(wanted)
                    speedApplied = true
                    note(Feature.SPEED, "APPLY speedStat=$speedStat multiplier=${wanted.amount() + 1.0}")
                }
                SimAction.REMOVE -> {
                    speed.removeModifier(SimEffects.SPEED_MODIFIER_ID)
                    speedApplied = false
                    note(Feature.SPEED, "REMOVE allowed=$allowed enabled=$speedOn")
                }
                SimAction.NONE -> Unit
            }
        }
        jumpApplied = maintainEffect(Feature.JUMP, allowed, jumpOn, jumpApplied, MobEffects.JUMP_BOOST, SimEffects.jumpEffect(jumpLevel), player)
        oneTapApplied = maintainEffect(Feature.ONE_TAP, allowed, oneTapOn, oneTapApplied, MobEffects.STRENGTH, SimEffects.oneTapEffect(), player)
    }

    /** @return whether the effect is now one this class applied */
    private fun maintainEffect(
        feature: Feature,
        allowed: Boolean,
        enabled: Boolean,
        applied: Boolean,
        holder: Holder<MobEffect>,
        wanted: MobEffectInstance,
        player: ServerPlayer,
    ): Boolean {
        val current = player.getEffect(holder)
        val matches = current != null && current.isInfiniteDuration && current.amplifier == wanted.amplifier
        return when (SimAction.of(allowed, enabled, current != null, matches, applied)) {
            SimAction.APPLY -> {
                player.addEffect(wanted)
                note(feature, "APPLY level=${wanted.amplifier + 1} amplifier=${wanted.amplifier} infinite=true")
                true
            }
            SimAction.REMOVE -> {
                player.removeEffect(holder)
                note(feature, "REMOVE allowed=$allowed enabled=$enabled")
                false
            }
            SimAction.NONE -> applied
        }
    }

    private const val MAX_QUEUED_EVENTS = 32
}
