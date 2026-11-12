package geiler.addons.client.module.impl

import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Small, client-independent eligibility rules for inventory macro buttons. */
object InventoryButtonRules {
    @JvmStatic
    fun evaluate(
        macroSystemActive: Boolean,
        macroExists: Boolean,
        macroEnabled: Boolean,
        hasSteps: Boolean,
        triggerContextAllowed: Boolean,
        islandAllowed: Boolean,
    ): Eligibility = when {
        !macroSystemActive -> Eligibility(false, "Macro system is disabled")
        !macroExists -> Eligibility(false, "Assigned macro no longer exists")
        !macroEnabled -> Eligibility(false, "Macro is disabled")
        !hasSteps -> Eligibility(false, "Macro has no steps")
        !triggerContextAllowed -> Eligibility(false, "Trigger context does not allow inventory screens")
        !islandAllowed -> Eligibility(false, "Island filter does not allow this island")
        else -> Eligibility(true, "Ready")
    }

    @JvmRecord
    data class Eligibility(val eligible: Boolean, val reason: String?)
}
