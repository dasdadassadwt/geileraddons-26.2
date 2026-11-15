package geiler.addons.client.dungeon.f7

import geiler.addons.client.module.impl.ArrowAlignModule
import geiler.addons.client.module.impl.KingRelicModule
import geiler.addons.client.module.impl.SimonSaysModule
import geiler.addons.client.module.impl.TerminalEspModule
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.world.InteractionResult

/** Client-side interaction hooks for the device solvers and the Terminal ESP flash. Registered once at startup. */
object F7DeviceCallbacks {
    @JvmStatic
    fun register() {
        UseEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (!level.isClientSide) return@register InteractionResult.PASS
            TerminalEspModule.INSTANCE.onEntityClick(entity)
            if (ArrowAlignModule.INSTANCE.onEntityUse(entity)) InteractionResult.FAIL else InteractionResult.PASS
        }
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide) TerminalEspModule.INSTANCE.onEntityClick(entity)
            InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { _, level, _, hit ->
            if (!level.isClientSide) return@register InteractionResult.PASS
            KingRelicModule.INSTANCE.onBlockUse(hit.blockPos)
            if (SimonSaysModule.INSTANCE.onBlockUse(hit.blockPos)) InteractionResult.FAIL else InteractionResult.PASS
        }
        AttackBlockCallback.EVENT.register { _, level, _, pos, _ ->
            if (level.isClientSide && SimonSaysModule.INSTANCE.onBlockAttack(pos)) InteractionResult.FAIL else InteractionResult.PASS
        }
    }
}
