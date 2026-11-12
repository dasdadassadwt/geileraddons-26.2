package geiler.addons.client.utility

import java.util.Locale
import java.util.Optional

/** Pure first-matching-stack counting and `/gfs` deficit planning for the two quick-supply commands. */
object CarriedSupplyPlanner {
    enum class Supply(
        private val stableId: String,
        private val commandName: String,
        private val label: String,
        private val defaultTarget: Int,
    ) {
        ENDER_PEARL("ENDER_PEARL", "ender_pearl", "ender pearl", 16),
        SUPERBOOM_TNT("SUPERBOOM_TNT", "superboom_tnt", "Superboom TNT", 64),
        ;

        fun skyBlockId(): String = stableId

        fun gfsName(): String = commandName

        fun displayName(): String = label

        fun targetCount(): Int = defaultTarget
    }

    /** The exact Hypixel custom-data item ID and count for one carried stack. */
    @JvmRecord
    data class StackIdentity(val skyBlockId: String?, val count: Int)

    @JvmStatic
    fun count(carriedStacks: Collection<StackIdentity?>?, supply: Supply?): Int {
        if (carriedStacks == null || supply == null) return 0
        for (stack in carriedStacks) {
            if (stack == null || stack.count <= 0 || !matches(stack, supply)) continue
            return stack.count
        }
        return 0
    }

    @JvmStatic
    fun matches(stack: StackIdentity?, supply: Supply?): Boolean {
        if (stack == null || supply == null) return false
        return normalizeId(stack.skyBlockId).equals(supply.skyBlockId())
    }

    @JvmStatic
    fun deficit(supply: Supply?, carriedCount: Int): Int =
        if (supply == null) 0 else deficit(supply, carriedCount, supply.targetCount())

    @JvmStatic
    fun deficit(supply: Supply?, carriedCount: Int, targetCount: Int): Int {
        if (supply == null || targetCount <= 0) return 0
        return if (carriedCount >= targetCount) 0 else targetCount - maxOf(0, carriedCount)
    }

    /** Returns the server command without a leading slash for ClientPacketListener.sendCommand. */
    @JvmStatic
    fun withdrawalCommand(supply: Supply?, carriedCount: Int): Optional<String> =
        if (supply == null) Optional.empty() else withdrawalCommand(supply, carriedCount, supply.targetCount())

    @JvmStatic
    fun withdrawalCommand(supply: Supply?, carriedCount: Int, targetCount: Int): Optional<String> {
        if (supply == null || targetCount <= 0) return Optional.empty()
        val missing = deficit(supply, carriedCount, targetCount)
        return if (missing == 0) Optional.empty() else Optional.of("gfs ${supply.gfsName()} $missing")
    }

    private fun normalizeId(id: String?): String = (id ?: "").trim().uppercase(Locale.ROOT)
}
