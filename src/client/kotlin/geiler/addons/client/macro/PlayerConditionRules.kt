package geiler.addons.client.macro

import java.util.Locale
import net.minecraft.util.Mth
import kotlin.math.abs

/** Pure comparison rules for [MacroCondition.Player]; the game reads live in [PlayerConditionData]. */
object PlayerConditionRules {
    private const val DEFAULT_TOLERANCE = 5.0
    private const val MAX_TOLERANCE = 180.0

    /** The angle tolerance typed into the condition, or [DEFAULT_TOLERANCE] when it is blank or not a number. */
    @JvmStatic
    fun toleranceOf(argument: String?): Double {
        val parsed = argument?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return DEFAULT_TOLERANCE
        return parsed.coerceIn(0.0, MAX_TOLERANCE)
    }

    /**
     * Compares a view angle with the wanted one. Equality means "within [tolerance] degrees": a float yaw is never
     * exactly a typed number. Yaw takes the shortest way around, so 179 and -179 are 2 degrees apart.
     */
    @JvmStatic
    fun angleMatches(actual: Double, expected: Double, tolerance: Double, operator: MacroCondition.Hypixel.Operator,
        yaw: Boolean): Boolean {
        val difference = if (yaw) Mth.wrapDegrees(actual - expected) else actual - expected
        return when (operator) {
            MacroCondition.Hypixel.Operator.EQUALS -> abs(difference) <= tolerance
            MacroCondition.Hypixel.Operator.NOT_EQUALS -> abs(difference) > tolerance
            MacroCondition.Hypixel.Operator.GREATER_THAN -> difference > 0.0
            MacroCondition.Hypixel.Operator.GREATER_OR_EQUAL -> difference >= 0.0
            MacroCondition.Hypixel.Operator.LESS_THAN -> difference < 0.0
            MacroCondition.Hypixel.Operator.LESS_OR_EQUAL -> difference <= 0.0
            MacroCondition.Hypixel.Operator.CONTAINS -> false
        }
    }

    @JvmStatic
    fun numberMatches(actual: Double, expected: Double, operator: MacroCondition.Hypixel.Operator): Boolean =
        when (operator) {
            MacroCondition.Hypixel.Operator.EQUALS -> actual == expected
            MacroCondition.Hypixel.Operator.NOT_EQUALS -> actual != expected
            MacroCondition.Hypixel.Operator.GREATER_THAN -> actual > expected
            MacroCondition.Hypixel.Operator.GREATER_OR_EQUAL -> actual >= expected
            MacroCondition.Hypixel.Operator.LESS_THAN -> actual < expected
            MacroCondition.Hypixel.Operator.LESS_OR_EQUAL -> actual <= expected
            MacroCondition.Hypixel.Operator.CONTAINS -> false
        }

    /** Block ids compare ignoring case and the optional `minecraft:` namespace; ordering is not defined for text. */
    @JvmStatic
    fun textMatches(actual: String, expected: String, operator: MacroCondition.Hypixel.Operator): Boolean {
        val left = bare(actual)
        val right = bare(expected)
        return when (operator) {
            MacroCondition.Hypixel.Operator.EQUALS -> left == right
            MacroCondition.Hypixel.Operator.NOT_EQUALS -> left != right
            MacroCondition.Hypixel.Operator.CONTAINS -> left.contains(right)
            else -> false
        }
    }

    /** Reads `x y z` or `x,y,z`; anything else is not a position. */
    @JvmStatic
    fun parseBlockPos(text: String?): IntArray? {
        val parts = text?.trim()?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() } ?: return null
        if (parts.size != 3) return null
        val values = IntArray(3)
        for (i in 0..2) values[i] = parts[i].toIntOrNull() ?: return null
        return values
    }

    private fun bare(text: String): String = text.trim().lowercase(Locale.ROOT).removePrefix("minecraft:")
}
