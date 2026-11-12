package geiler.addons.client.config

import java.util.HashSet

/** Kotlin reset-scope rules backing the Java record boundary. */
object ConfigResetPlanLogic {
    @JvmStatic
    fun create(
        resetModules: Boolean,
        resetMacros: Boolean,
        resetMobEsp: Boolean,
        resetScoreboard: Boolean,
        macroModule: String?,
        mobEspModules: Set<String>?,
    ): ConfigResetPlan.Plan {
        val esp: Set<String> = if (mobEspModules == null) emptySet() else java.util.Set.copyOf(mobEspModules)
        val excluded = HashSet<String>()
        val named = HashSet<String>()
        if (resetModules) {
            if (!resetMacros && macroModule != null) excluded.add(macroModule)
            if (!resetMobEsp) excluded.addAll(esp)
        } else {
            if (resetMacros && macroModule != null) named.add(macroModule)
            if (resetMobEsp) named.addAll(esp)
        }
        return ConfigResetPlan.Plan(resetModules, excluded, named, resetMacros, resetMobEsp, resetScoreboard)
    }
}
