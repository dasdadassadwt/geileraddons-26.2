package geiler.addons.client.config

/** Immutable reset-scope snapshot and Java-style static API. */
class ConfigResetPlan private constructor() {
    class Plan(
        val resetAllModules: Boolean,
        excludedModules: Set<String>,
        namedModules: Set<String>,
        val resetMacros: Boolean,
        val resetMobEsp: Boolean,
        val resetScoreboard: Boolean,
    ) {
        private val excludedSnapshot = java.util.Set.copyOf(excludedModules)
        private val namedSnapshot = java.util.Set.copyOf(namedModules)

        fun resetAllModules(): Boolean = resetAllModules
        fun excludedModules(): Set<String> = excludedSnapshot
        fun namedModules(): Set<String> = namedSnapshot
        fun resetMacros(): Boolean = resetMacros
        fun resetMobEsp(): Boolean = resetMobEsp
        fun resetScoreboard(): Boolean = resetScoreboard

        override fun equals(other: Any?): Boolean = other is Plan
            && resetAllModules == other.resetAllModules
            && excludedSnapshot == other.excludedSnapshot
            && namedSnapshot == other.namedSnapshot
            && resetMacros == other.resetMacros
            && resetMobEsp == other.resetMobEsp
            && resetScoreboard == other.resetScoreboard

        override fun hashCode(): Int {
            var result = resetAllModules.hashCode()
            result = 31 * result + excludedSnapshot.hashCode()
            result = 31 * result + namedSnapshot.hashCode()
            result = 31 * result + resetMacros.hashCode()
            result = 31 * result + resetMobEsp.hashCode()
            result = 31 * result + resetScoreboard.hashCode()
            return result
        }

        override fun toString(): String =
            "Plan[resetAllModules=$resetAllModules, excludedModules=$excludedSnapshot, " +
                "namedModules=$namedSnapshot, resetMacros=$resetMacros, resetMobEsp=$resetMobEsp, " +
                "resetScoreboard=$resetScoreboard]"
    }

    companion object {
        @JvmStatic
        fun create(
            resetModules: Boolean,
            resetMacros: Boolean,
            resetMobEsp: Boolean,
            resetScoreboard: Boolean,
            macroModule: String?,
            mobEspModules: Set<String>?,
        ): Plan = ConfigResetPlanLogic.create(
            resetModules, resetMacros, resetMobEsp, resetScoreboard, macroModule, mobEspModules,
        )
    }
}
