package geiler.addons.client.dungeon

/** Shared pure transitions for dungeon context and temporary map startup state. */
class DungeonContextLifecycleRules private constructor() {
    companion object {
        @JvmStatic
        fun clearEspClosedForRunAfterChat(closed: Boolean, segmentId: String?): Boolean =
            DungeonContextLifecycleRulesLogic.clearEspClosedForRunAfterChat(closed, segmentId)

        /** Keeps the old run's phase latch through uncertainty and transfer grace. */
        @JvmStatic
        fun clearEspClosedForRunAfterExit(closed: Boolean, confirmedExit: Boolean): Boolean =
            DungeonContextLifecycleRulesLogic.clearEspClosedForRunAfterExit(closed, confirmedExit)

        /** Sidebar loss ends the run after the grace, except in boss phase on the level where the boss was seen. */
        @JvmStatic
        fun shouldExitOnSidebarLoss(bossPhase: Boolean, sameLevelAsBoss: Boolean, graceExpired: Boolean): Boolean =
            DungeonContextLifecycleRulesLogic.shouldExitOnSidebarLoss(bossPhase, sameLevelAsBoss, graceExpired)

        @JvmStatic
        fun shouldBeginClearPhase(closedByObservedBloodOrBossSignal: Boolean): Boolean =
            DungeonContextLifecycleRulesLogic.shouldBeginClearPhase(closedByObservedBloodOrBossSignal)

        /** Accepts only the exact short countdown lines emitted when a dungeon run is about to start. */
        @JvmStatic
        fun isStartCountdownLine(message: String?): Boolean = DungeonContextLifecycleRulesLogic.isStartCountdownLine(message)

        @JvmStatic
        fun shouldStartMapOverlayPreload(
            message: String?,
            queuedCatacombsFloor: Boolean,
            authoritativeCatacombsSidebar: Boolean,
        ): Boolean = DungeonContextLifecycleRulesLogic.shouldStartMapOverlayPreload(
            message, queuedCatacombsFloor, authoritativeCatacombsSidebar,
        )

        @JvmStatic
        fun shouldClearMapOverlayPreload(
            source: String?,
            stale: Boolean,
            queuedFloorPresent: Boolean,
            authoritativeCatacombsSidebar: Boolean,
        ): Boolean = DungeonContextLifecycleRulesLogic.shouldClearMapOverlayPreload(
            source, stale, queuedFloorPresent, authoritativeCatacombsSidebar,
        )

        @JvmStatic
        fun shouldKeepMapOverlayPixelContext(
            active: Boolean,
            sameLevel: Boolean,
            inDungeon: Boolean,
            ageNanos: Long,
            graceNanos: Long,
        ): Boolean = DungeonContextLifecycleRulesLogic.shouldKeepMapOverlayPixelContext(
            active, sameLevel, inDungeon, ageNanos, graceNanos,
        )

        @JvmStatic
        fun shouldObserveAuthoritativeMapPixels(validMapGrid: Boolean, inDungeon: Boolean): Boolean =
            DungeonContextLifecycleRulesLogic.shouldObserveAuthoritativeMapPixels(validMapGrid, inDungeon)

        @JvmStatic
        fun shouldShowMapOverlay(
            inDungeon: Boolean,
            showInBoss: Boolean,
            mobEspPhase: Boolean,
            mapStartupContext: Boolean,
        ): Boolean = DungeonContextLifecycleRulesLogic.shouldShowMapOverlay(
            inDungeon, showInBoss, mobEspPhase, mapStartupContext,
        )
    }
}
