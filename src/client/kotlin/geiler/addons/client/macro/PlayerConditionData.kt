package geiler.addons.client.macro

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth

/** Evaluates [MacroCondition.Player] against the live player; null means the fact cannot be read right now. */
object PlayerConditionData {
    @JvmStatic
    fun evaluate(condition: MacroCondition.Player?, minecraft: Minecraft?): Boolean? {
        if (condition == null || minecraft == null || !minecraft.isSameThread) return null
        val player = minecraft.player ?: return null
        val level = minecraft.level ?: return null
        val operator = condition.operator()
        return when (condition.field()) {
            MacroCondition.Player.Field.YAW, MacroCondition.Player.Field.PITCH -> {
                val expected = condition.expected().trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
                val yaw = condition.field() == MacroCondition.Player.Field.YAW
                val actual = if (yaw) Mth.wrapDegrees(player.yRot).toDouble() else player.xRot.toDouble()
                PlayerConditionRules.angleMatches(actual, expected, PlayerConditionRules.toleranceOf(condition.argument()),
                    operator, yaw)
            }
            MacroCondition.Player.Field.X, MacroCondition.Player.Field.Y, MacroCondition.Player.Field.Z -> {
                val expected = condition.expected().trim().toIntOrNull() ?: return null
                val actual = when (condition.field()) {
                    MacroCondition.Player.Field.X -> player.blockPosition().x
                    MacroCondition.Player.Field.Y -> player.blockPosition().y
                    else -> player.blockPosition().z
                }
                PlayerConditionRules.numberMatches(actual.toDouble(), expected.toDouble(), operator)
            }
            MacroCondition.Player.Field.STANDING_BLOCK -> {
                if (condition.expected().isBlank()) return null
                blockId(level, player.blockPosition().below())?.let {
                    PlayerConditionRules.textMatches(it, condition.expected(), operator)
                }
            }
            MacroCondition.Player.Field.BLOCK_AT -> {
                if (condition.expected().isBlank()) return null
                val position = PlayerConditionRules.parseBlockPos(condition.argument()) ?: return null
                blockId(level, BlockPos(position[0], position[1], position[2]))?.let {
                    PlayerConditionRules.textMatches(it, condition.expected(), operator)
                }
            }
        }
    }

    /** The block's registry id, or null while its chunk is not loaded (an unloaded chunk reads as air). */
    private fun blockId(level: net.minecraft.world.level.Level, position: BlockPos): String? {
        if (!level.hasChunkAt(position)) return null
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).block).toString()
    }
}
