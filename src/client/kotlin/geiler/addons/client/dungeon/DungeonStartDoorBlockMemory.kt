package geiler.addons.client.dungeon

import java.util.Collections
import java.util.LinkedHashMap

/** Remembers eligible Start Door source blocks until the client observes air at their positions. */
class DungeonStartDoorBlockMemory<B> {
    private val sources = LinkedHashMap<Long, B>()

    fun remember(packedPosition: Long, source: B?) {
        if (source == null) return
        sources.remove(packedPosition)
        sources[packedPosition] = source
        while (sources.size > MAX_POSITIONS) {
            val oldest = sources.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
    }

    /** Unloaded positions are retained; only an observed air state retires the remembered position. */
    fun observeAir(packedPosition: Long, air: Boolean) {
        if (air) sources.remove(packedPosition)
    }

    fun snapshot(): Map<Long, B> = Collections.unmodifiableMap(LinkedHashMap(sources))

    fun clear() = sources.clear()

    private companion object {
        const val MAX_POSITIONS = 128
    }
}
