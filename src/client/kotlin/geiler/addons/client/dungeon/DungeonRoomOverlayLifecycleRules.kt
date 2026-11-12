package geiler.addons.client.dungeon

/** Client lifecycle disposition for the room-map overlay. */
object DungeonRoomOverlayLifecycleRules {
    enum class TickDisposition { RESET_SESSION, PAUSE_DISABLED, ACTIVE }

    private const val MAP_REFRESH_INTERVAL_TICKS = 5

    @JvmStatic
    fun tickDisposition(
        moduleEnabled: Boolean,
        inDungeon: Boolean,
        clientReady: Boolean,
    ): DungeonRoomOverlayLifecycleRules.TickDisposition {
        if (!inDungeon || !clientReady) return DungeonRoomOverlayLifecycleRules.TickDisposition.RESET_SESSION
        return if (moduleEnabled) DungeonRoomOverlayLifecycleRules.TickDisposition.ACTIVE
        else DungeonRoomOverlayLifecycleRules.TickDisposition.PAUSE_DISABLED
    }

    @JvmStatic
    fun hasRenderableMapLayout(snapshotAvailable: Boolean, authoritativeMap: Boolean, verifiedRoomCoreFallback: Boolean): Boolean =
        snapshotAvailable && (authoritativeMap || verifiedRoomCoreFallback)

    @JvmStatic
    fun mapRefreshDue(activeTick: Int): Boolean = activeTick % MAP_REFRESH_INTERVAL_TICKS == 0
}
