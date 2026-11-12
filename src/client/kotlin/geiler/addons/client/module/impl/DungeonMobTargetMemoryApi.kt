package geiler.addons.client.module.impl

import java.util.Objects
import java.util.UUID
import java.util.function.Function

/**
 * Kotlin-owned target memory API consumed by the dungeon ESP module and Java-side checks.
 *
 * The nested immutable value types keep the Java constructors and record-style accessors used by
 * callers while the retention policy lives in [DungeonMobTargetMemoryState].
 */
internal class DungeonMobTargetMemory {
    companion object {
        const val MAX_TARGETS = 512

        @JvmStatic
        fun isPersistent(group: Group?): Boolean = DungeonMobTargetMemoryRules.isPersistent(group)
    }

    enum class Group { STARRED, MINIBOSS, FEL_SKULL, FEL_MOVING }
    enum class Presence { LOADED_ALIVE, LOADED_GONE, UNLOADED }

    class Bounds(
        private val minXValue: Double,
        private val minYValue: Double,
        private val minZValue: Double,
        private val maxXValue: Double,
        private val maxYValue: Double,
        private val maxZValue: Double,
    ) {
        init {
            if (!java.lang.Double.isFinite(minXValue) || !java.lang.Double.isFinite(minYValue)
                || !java.lang.Double.isFinite(minZValue) || !java.lang.Double.isFinite(maxXValue)
                || !java.lang.Double.isFinite(maxYValue) || !java.lang.Double.isFinite(maxZValue)
                || maxXValue < minXValue || maxYValue < minYValue || maxZValue < minZValue
            ) {
                throw IllegalArgumentException("Invalid dungeon ESP bounds")
            }
        }

        fun minX(): Double = minXValue
        fun minY(): Double = minYValue
        fun minZ(): Double = minZValue
        fun maxX(): Double = maxXValue
        fun maxY(): Double = maxYValue
        fun maxZ(): Double = maxZValue

        fun centerX(): Double = (minXValue + maxXValue) * 0.5
        fun centerY(): Double = (minYValue + maxYValue) * 0.5
        fun centerZ(): Double = (minZValue + maxZValue) * 0.5

        override fun equals(other: Any?): Boolean =
            this === other || other is Bounds
                && java.lang.Double.compare(minXValue, other.minXValue) == 0
                && java.lang.Double.compare(minYValue, other.minYValue) == 0
                && java.lang.Double.compare(minZValue, other.minZValue) == 0
                && java.lang.Double.compare(maxXValue, other.maxXValue) == 0
                && java.lang.Double.compare(maxYValue, other.maxYValue) == 0
                && java.lang.Double.compare(maxZValue, other.maxZValue) == 0

        override fun hashCode(): Int {
            var result = java.lang.Double.hashCode(minXValue)
            result = 31 * result + java.lang.Double.hashCode(minYValue)
            result = 31 * result + java.lang.Double.hashCode(minZValue)
            result = 31 * result + java.lang.Double.hashCode(maxXValue)
            result = 31 * result + java.lang.Double.hashCode(maxYValue)
            result = 31 * result + java.lang.Double.hashCode(maxZValue)
            return result
        }

        override fun toString(): String =
            "Bounds[minX=$minXValue, minY=$minYValue, minZ=$minZValue, " +
                "maxX=$maxXValue, maxY=$maxYValue, maxZ=$maxZValue]"
    }

    /** Immutable identity and last-seen rendering data; never keeps a live Entity reference. */
    class Target(
        uuid: UUID?,
        entityId: Int,
        group: Group?,
        type: String?,
        label: String?,
        bounds: Bounds?,
        roomKey: String?,
        seenAtTick: Long,
        fullShadowAssassin: Boolean,
    ) {
        private val uuidValue: UUID = Objects.requireNonNull(uuid, "uuid")!!
        private val entityIdValue = entityId
        private val groupValue: Group = Objects.requireNonNull(group, "group")!!
        private val typeValue = type ?: "unknown"
        private val labelValue = label ?: ""
        private val boundsValue: Bounds = Objects.requireNonNull(bounds, "bounds")!!
        private val roomKeyValue: String = Objects.requireNonNull(roomKey, "roomKey")!!
        private val seenAtTickValue = seenAtTick
        private val fullShadowAssassinValue = fullShadowAssassin

        constructor(
            uuid: UUID?,
            entityId: Int,
            group: Group?,
            type: String?,
            label: String?,
            bounds: Bounds?,
            roomKey: String?,
            seenAtTick: Long,
        ) : this(uuid, entityId, group, type, label, bounds, roomKey, seenAtTick, false)

        fun uuid(): UUID = uuidValue
        fun entityId(): Int = entityIdValue
        fun group(): Group = groupValue
        fun type(): String = typeValue
        fun label(): String = labelValue
        fun bounds(): Bounds = boundsValue
        fun roomKey(): String = roomKeyValue
        fun seenAtTick(): Long = seenAtTickValue
        fun fullShadowAssassin(): Boolean = fullShadowAssassinValue

        override fun equals(other: Any?): Boolean =
            this === other || other is Target
                && entityIdValue == other.entityIdValue
                && seenAtTickValue == other.seenAtTickValue
                && fullShadowAssassinValue == other.fullShadowAssassinValue
                && uuidValue == other.uuidValue
                && groupValue == other.groupValue
                && typeValue == other.typeValue
                && labelValue == other.labelValue
                && boundsValue == other.boundsValue
                && roomKeyValue == other.roomKeyValue

        override fun hashCode(): Int = Objects.hash(
            uuidValue,
            entityIdValue,
            groupValue,
            typeValue,
            labelValue,
            boundsValue,
            roomKeyValue,
            seenAtTickValue,
            fullShadowAssassinValue,
        )

        override fun toString(): String =
            "Target[uuid=$uuidValue, entityId=$entityIdValue, group=$groupValue, type=$typeValue, " +
                "label=$labelValue, bounds=$boundsValue, roomKey=$roomKeyValue, seenAtTick=$seenAtTickValue, " +
                "fullShadowAssassin=$fullShadowAssassinValue]"
    }

    private val state = DungeonMobTargetMemoryState()

    fun update(
        currentlyMatched: Collection<Target?>?,
        gameTick: Long,
        presenceOf: Function<Target, Presence?>?,
        refreshLoaded: Function<Target, Target?>?,
    ): List<Target> = state.update(currentlyMatched, gameTick, presenceOf, refreshLoaded)

    @Deprecated("Compatibility overload retained for room-scoped callers")
    fun update(
        currentlyMatched: Collection<Target?>?,
        currentRoom: String?,
        gameTick: Int,
        presenceOf: Function<Target, Presence?>?,
        refreshLoaded: Function<Target, Target?>?,
    ): List<Target> = state.update(currentlyMatched, currentRoom, gameTick, presenceOf, refreshLoaded)

    fun clearGroup(group: Group?) = state.clearGroup(group)
    fun removeEntityId(entityId: Int) = state.removeEntityId(entityId)
    fun clearTransientGroups() = state.clearTransientGroups()
    fun clear() = state.clear()
    fun size(): Int = state.size()
}
