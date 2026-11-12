package geiler.addons.client.presentation

import net.minecraft.network.chat.Component
import java.util.ArrayList
import java.util.function.UnaryOperator

/** Null-safe component-list mapping for container tooltips. */
object TooltipComponentMapper {
    /**
     * Maps each non-null line while retaining null entries and the original list when nothing changes.
     * A null mapper result leaves that line untouched.
     */
    @JvmStatic
    fun mapComponents(
        components: List<Component?>?,
        mapper: UnaryOperator<Component?>?,
    ): List<Component?>? {
        if (components == null || components.isEmpty() || mapper == null) return components

        val mapped = ArrayList<Component?>(components.size)
        var changed = false
        for (component in components) {
            if (component == null) {
                mapped.add(null)
                continue
            }
            val replacement = mapper.apply(component) ?: component
            changed = changed || replacement !== component
            mapped.add(replacement)
        }
        return if (changed) mapped else components
    }
}
