package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.phys.HitResult

/** Stops the server's pose updates from replaying the local player's sneak and swim animations. */
class AnimationFixModule private constructor() : Module(
    "Animation Fix",
    "Fixes the sneaking and swimming animations being able to play twice.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var dropped = 0L

    override fun onEnable() {
        dropped = 0L
        diagnostics.enable("skyblockOnly=${skyblockOnlySetting().value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE dropped=$dropped")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true skyblockOnly=${skyblockOnlySetting().value()} dropped=$dropped suppressedEvents=$it" }
    }

    private fun skyblockOnlySetting() = spec.toggle("skyblockOnly")

    fun active(): Boolean = GameFixRules.active(isEnabled(), skyblockOnlySetting().value(), SkyblockItems.inSkyblock())

    /** `handleSetEntityData`: drops the pose entry from a packet addressed to the local player. */
    fun onEntityData(entity: net.minecraft.world.entity.Entity, items: MutableList<SynchedEntityData.DataValue<*>>) {
        if (entity !== Minecraft.getInstance().player || !active()) return
        val removed = try {
            GameFixRules.removeFirstMatching(items) { it.serializer() == EntityDataSerializers.POSE }
        } catch (_: UnsupportedOperationException) {
            diagnostics.event("pose entry could not be removed: packet list is read-only")
            0
        }
        if (removed > 0) {
            dropped += removed
            diagnostics.event("dropPose total=$dropped")
        }
    }

    companion object {
        /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
        private val spec = ModuleSettingsSpec.of("Animation Fix")

        @JvmField
        val INSTANCE = AnimationFixModule()
    }
}

/** Stops Blaze daggers and fishing rods from activating twice from one right-click. */
class DoubleUseFixModule private constructor() : Module(
    "Double Use Fix",
    "Fixes Blaze Daggers and Fishing Rods being able to activate twice at once.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var cancelledDagger = 0L
    private var redirectedRod = 0L

    override fun onEnable() {
        cancelledDagger = 0L
        redirectedRod = 0L
        diagnostics.enable("skyblockOnly=${skyblockOnly().value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE cancelledDagger=$cancelledDagger redirectedRod=$redirectedRod")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "enabled=true skyblockOnly=${skyblockOnly().value()} cancelledDagger=$cancelledDagger " +
                "redirectedRod=$redirectedRod suppressedEvents=$it"
        }
    }

    private fun skyblockOnly() = spec.toggle("skyblockOnly")

    fun active(): Boolean = GameFixRules.active(isEnabled(), skyblockOnly().value(), SkyblockItems.inSkyblock())

    private fun heldType(client: Minecraft): GameFixRules.DisableType {
        val held = client.player?.mainHandItem ?: return GameFixRules.DisableType.NONE
        return GameFixRules.disableType(held.`is`(Items.FISHING_ROD), SkyblockItems.rightClickAbility(held))
    }

    /** `startUseItem` before `useItem`: cancels a dagger use when the crosshair is on a block. */
    fun shouldCancelItemUse(): Boolean {
        if (!active()) return false
        val client = Minecraft.getInstance()
        val hit = client.hitResult ?: return false
        if (hit.type != HitResult.Type.BLOCK || heldType(client) != GameFixRules.DisableType.DAGGER) return false
        cancelledDagger++
        diagnostics.event("cancelDaggerUse total=$cancelledDagger")
        return true
    }

    /** `startUseItem` before `useItemOn`: casts a held rod once through `useItem` and cancels the block use. */
    fun redirectBlockUse(): Boolean {
        if (!active()) return false
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val gameMode = client.gameMode ?: return false
        if (heldType(client) != GameFixRules.DisableType.ROD) return false
        gameMode.useItem(player, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)
        redirectedRod++
        diagnostics.event("redirectRodUse total=$redirectedRod")
        return true
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Double Use Fix")

        @JvmField
        val INSTANCE = DoubleUseFixModule()
    }
}

/** Swallows exceptions from corrupted entity-data packets so they cannot disconnect the client. */
class DisconnectFixModule private constructor() : Module(
    "Disconnect Fix",
    "Fixes Tarantula slayer disconnecting you due to corrupted packets.",
    Category.MISCELLANEOUS,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var caught = 0L

    override fun onEnable() {
        caught = 0L
        diagnostics.enable("")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE caught=$caught")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true caught=$caught suppressedEvents=$it" }
    }

    /** Records one swallowed packet exception; the caller has already decided to swallow it. */
    fun onCaught(error: Exception) {
        caught++
        diagnostics.event("caught total=$caught type=${error.javaClass.simpleName} message=${error.message?.take(120)}")
    }

    companion object {
        @JvmField
        val INSTANCE = DisconnectFixModule()
    }
}
