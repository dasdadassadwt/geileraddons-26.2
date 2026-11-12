package geiler.addons.client.farming

import net.minecraft.world.item.ItemStack
import java.util.HashMap
import java.util.Optional
import java.util.UUID
import java.util.function.Supplier

/**
 * Client-thread cache for ArmorStand head classification, including negative results.
 *
 * [SHARED] is the instance production uses. Two modules scan the same armour stands in the same
 * tick for the same reason, and a per-module cache meant the head texture was read and matched twice
 * for every pest in range. The class stays instantiable so a test can exercise it in isolation.
 */
class PestDetectionCache {
    private val entries = HashMap<UUID, Entry>()

    fun get(entityId: UUID, head: ItemStack, detector: Supplier<Optional<PestKind>>): Optional<PestKind> {
        val cached = entries[entityId]
        if (cached != null && ItemStack.matches(cached.head, head)) {
            return Optional.ofNullable(cached.kind)
        }

        val detected = detector.get()
        entries[entityId] = Entry(head.copy(), detected.orElse(null))
        return detected
    }

    fun retainAll(visibleEntities: MutableSet<UUID>) {
        entries.keys.retainAll(visibleEntities)
    }

    fun clear() {
        entries.clear()
    }

    private data class Entry(val head: ItemStack, val kind: PestKind?)

    companion object {
        /** The one cache behind every production scan; see the class comment for why it is shared. */
        @JvmField
        val SHARED = PestDetectionCache()
    }
}
