package geiler.addons.client.dungeon

import java.util.Locale

/** Shared pure rules for dungeon context transitions and temporary map startup state. */
object DungeonContextLifecycleRulesLogic {
    @JvmStatic
    fun clearEspClosedForRunAfterChat(closed: Boolean, segmentId: String?): Boolean {
        if (segmentId == null) return closed
        return when (segmentId) {
            "ENTRY", "CLEAR" -> false
            "BLOOD_OPEN", "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE", "BONZO_SIKE", "BONZO",
            "SCARF_MINIONS", "SCARF", "GUARDIANS", "PROFESSOR", "PROFESSOR_TRANSFORMED",
            "TRANSFORMED_PROFESSOR", "THORN", "LIVID", "TERRACOTTAS", "SADAN_GIANTS", "SADAN",
            "MAXOR", "STORM", "TERMINALS", "GOLDOR", "NECRON", "WITHER_KING" -> true
            else -> closed
        }
    }

    /** Keeps the old run's phase latch through uncertainty and transfer grace. */
    @JvmStatic
    fun clearEspClosedForRunAfterExit(closed: Boolean, confirmedExit: Boolean): Boolean =
        if (confirmedExit) false else closed

    /** Hypixel's sidebar can drop its Catacombs line mid boss (M7 P5); leaving a dungeon always changes level. */
    @JvmStatic
    fun shouldExitOnSidebarLoss(bossPhase: Boolean, sameLevelAsBoss: Boolean, graceExpired: Boolean): Boolean =
        graceExpired && !(bossPhase && sameLevelAsBoss)

    @JvmStatic
    fun shouldBeginClearPhase(closedByObservedBloodOrBossSignal: Boolean): Boolean =
        !closedByObservedBloodOrBossSignal

    /** Accepts only the exact short countdown lines emitted when a dungeon run is about to start. */
    @JvmStatic
    fun isStartCountdownLine(message: String?): Boolean {
        if (message == null) return false
        return when (message.trim { Character.isWhitespace(it) }.lowercase(Locale.ROOT)) {
            "starting in 5 seconds.", "starting in 4 seconds.", "starting in 3 seconds.",
            "starting in 2 seconds.", "starting in 1 second." -> true
            else -> false
        }
    }

    @JvmStatic
    fun shouldStartMapOverlayPreload(
        message: String?,
        queuedCatacombsFloor: Boolean,
        authoritativeCatacombsSidebar: Boolean,
    ): Boolean = isStartCountdownLine(message) && (queuedCatacombsFloor || authoritativeCatacombsSidebar)

    @JvmStatic
    fun shouldClearMapOverlayPreload(
        source: String?,
        stale: Boolean,
        queuedFloorPresent: Boolean,
        authoritativeCatacombsSidebar: Boolean,
    ): Boolean = stale || source == "queued-floor-countdown" && !queuedFloorPresent && !authoritativeCatacombsSidebar

    @JvmStatic
    fun shouldKeepMapOverlayPixelContext(
        active: Boolean,
        sameLevel: Boolean,
        inDungeon: Boolean,
        ageNanos: Long,
        graceNanos: Long,
    ): Boolean = active && sameLevel && !inDungeon && ageNanos >= 0L && ageNanos < graceNanos

    @JvmStatic
    fun shouldObserveAuthoritativeMapPixels(validMapGrid: Boolean, inDungeon: Boolean): Boolean =
        validMapGrid && !inDungeon

    @JvmStatic
    fun shouldShowMapOverlay(
        inDungeon: Boolean,
        showInBoss: Boolean,
        mobEspPhase: Boolean,
        mapStartupContext: Boolean,
    ): Boolean = showInBoss || mobEspPhase || !inDungeon && mapStartupContext
}
