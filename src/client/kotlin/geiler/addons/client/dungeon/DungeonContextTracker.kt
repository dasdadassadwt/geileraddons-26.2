package geiler.addons.client.dungeon

import geiler.addons.client.tree.ChatText
import java.time.Duration
import java.util.Locale
import java.util.regex.Pattern
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

/** Shared client-side dungeon and map-startup context state. */
object DungeonContextTracker {
    private const val EXIT_GRACE_NANOS = 2_000_000_000L
    private val MAP_PRELOAD_MAX_AGE_NANOS = Duration.ofSeconds(30).toNanos()
    private val MAP_PIXEL_CONTEXT_GRACE_NANOS = Duration.ofSeconds(2).toNanos()
    private val FLOOR = Pattern.compile(
        "(?i)(?:\\b(M|F)\\s*(VII|VI|IV|V|III|II|I|[1-7])\\b|\\b(Entrance)\\b)",
    )

    private var inDungeon = false
    /** Legacy pre-blood Clear phase, kept separate from Dungeon Mob ESP's longer gate. */
    private var clearPhase = false
    /** Preserves an explicit blood/boss signal that can arrive just before the first sidebar tick. */
    private var clearEspClosedForRun = false
    /** Dungeon Mob ESP stays active through Blood and closes only when a boss-entry stage is seen. */
    private var mobEspBossPhase = false
    private var lastDungeonSignal = 0L
    private var detectedFloor: DungeonFloor? = null
    private var manualFloor: DungeonFloor? = null
    private var level: Any? = null
    private var mapPreloadLevel: Any? = null
    private var mapPreloadFloor: DungeonFloor? = null
    private var mapPreloadStartedNanos = 0L
    private var mapPreloadActive = false
    private var mapPreloadSource = "none"
    private var mapOverlayPixelsLevel: Any? = null
    private var mapOverlayPixelsSeenNanos = 0L
    private var mapOverlayPixelsActive = false
    /** Level where a boss stage was seen; sidebar loss on this level does not end the run. */
    private var bossLevel: Any? = null
    private var lastSidebarText = ""
    private var lastSignalInDungeon = false
    private var sidebarLossCount = 0
    private var lastSidebarLossText = "none"

    /** Call every client tick. Map geometry is gated by Catacombs context and an active consumer. */
    @JvmStatic
    fun tick(client: Minecraft?, mapGeometryEnabled: Boolean, mapEntryScanEnabled: Boolean) {
        val currentClient = client
        val currentLevel = currentClient?.level
        if (currentClient == null || currentLevel == null) {
            clear()
            return
        }
        if (level !== currentLevel) {
            level = currentLevel
            // Keep the user's manual choice through Hypixel's brief transfer-level replacement.
            lastDungeonSignal = if (inDungeon) System.nanoTime() else 0L
            clearMapOverlayContext()
        }
        val signal = readSidebar(currentLevel.scoreboard)
        val now = System.nanoTime()
        if (mapPreloadActive) {
            val preloadAge = now - mapPreloadStartedNanos
            val stale = preloadAge < 0L || preloadAge >= MAP_PRELOAD_MAX_AGE_NANOS
            val clearPreload = DungeonContextLifecycleRules.shouldClearMapOverlayPreload(
                mapPreloadSource,
                stale,
                DungeonQueueFloorTracker.currentFloor() != null,
                hasCatacombsSidebar(signal),
            )
            if (clearPreload) clearMapOverlayPreload()
        }
        val mapEntryCandidate = DungeonMapRoomDetector.hasEntryCandidate(currentClient)
        val wasInDungeon = inDungeon
        if (wasInDungeon && lastSignalInDungeon && !signal.inDungeon) {
            sidebarLossCount++
            lastSidebarLossText = lastSidebarText.replace('\n', '|').take(160)
        }
        lastSignalInDungeon = signal.inDungeon
        if (signal.inDungeon || wasInDungeon && mapEntryCandidate) {
            inDungeon = true
            if (!wasInDungeon) clearPhase = shouldBeginClearPhase(clearEspClosedForRun)
            lastDungeonSignal = now
            if (signal.floor != null) detectedFloor = signal.floor
        } else if (inDungeon && DungeonContextLifecycleRules.shouldExitOnSidebarLoss(
                mobEspBossPhase,
                bossLevel === currentLevel,
                lastDungeonSignal != 0L && now - lastDungeonSignal > EXIT_GRACE_NANOS,
            )
        ) {
            inDungeon = false
            clearPhase = false
            mobEspBossPhase = false
            bossLevel = null
            manualFloor = null
            detectedFloor = null
        }
        val confirmedDungeonExit = wasInDungeon && !inDungeon
        if (confirmedDungeonExit || inDungeon) clearMapOverlayContext()
        clearEspClosedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterExit(
            clearEspClosedForRun,
            confirmedDungeonExit,
        )

        if (inDungeon && mapGeometryEnabled && currentClient.player != null) {
            DungeonMapRoomDetector.tick(currentClient)
            return
        }

        // Outside a confirmed dungeon, a cheap map-marker/color probe may authorize one bounded
        // geometry check per second, but only while Dungeon Mob ESP, Dungeon Boss ESP or Dungeon Class ESP needs
        // entry detection. The geometry check validates a dungeon map entry; it performs no source-room
        // or chunk reads.
        if (!wasInDungeon && mapEntryCandidate && mapEntryScanEnabled && currentClient.player != null &&
            shouldRunFullMapScan(false, true) && DungeonMapRoomDetector.shouldRunEntryScan(now)
        ) {
            DungeonMapRoomDetector.tick(currentClient)
            if (DungeonMapRoomDetector.current().confirmsDungeonRoom()) {
                inDungeon = true
                clearMapOverlayContext()
                clearPhase = true
                lastDungeonSignal = now
                if (signal.floor != null) detectedFloor = signal.floor
                return
            }
        }
        DungeonMapRoomDetector.deferUntilDungeonContext()
    }

    /** Compatibility entry points for callers that only need lightweight sidebar context updates. */
    @JvmStatic
    fun tick(client: Minecraft?, mapGeometryEnabled: Boolean) = tick(client, mapGeometryEnabled, false)

    @JvmStatic
    fun tick(client: Minecraft?) = tick(client, false, false)

    @JvmStatic
    fun shouldRunFullMapScan(dungeonContext: Boolean, mapEntryCandidate: Boolean): Boolean =
        dungeonContext || mapEntryCandidate

    @JvmStatic
    fun inDungeon(): Boolean = inDungeon

    /**
     * Starts a map-only preload after an exact dungeon countdown cue and independent Catacombs
     * evidence. This deliberately does not promote shared dungeon context or enable other modules.
     */
    @JvmStatic
    fun observeMapOverlayCountdown(message: String?): Boolean {
        val client = Minecraft.getInstance()
        if (client != null && !client.isSameThread()) {
            client.execute { observeMapOverlayCountdown(message) }
            return false
        }
        val currentClient = client ?: return false
        val currentLevel = currentClient.level ?: return false
        if (currentClient.player == null || inDungeon) return false
        val sidebar = readSidebar(currentLevel.scoreboard)
        val queuedFloor = DungeonQueueFloorTracker.currentFloor()
        val catacombsSidebar = hasCatacombsSidebar(sidebar)
        if (!DungeonContextLifecycleRules.shouldStartMapOverlayPreload(
                message,
                queuedFloor != null,
                catacombsSidebar,
            )
        ) return false
        mapPreloadLevel = currentLevel
        mapPreloadFloor = queuedFloor ?: sidebar.floor
        mapPreloadStartedNanos = System.nanoTime()
        mapPreloadActive = true
        mapPreloadSource = if (queuedFloor != null) "queued-floor-countdown" else "catacombs-sidebar-countdown"
        return true
    }

    /** True only while the map's short-lived preload latch remains tied to its originating world. */
    @JvmStatic
    fun isMapOverlayPreloading(): Boolean {
        if (!mapPreloadActive) return false
        val client = Minecraft.getInstance()
        val currentLevel = client?.level
        if (client == null || currentLevel == null || currentLevel !== mapPreloadLevel || inDungeon) {
            clearMapOverlayPreload()
            return false
        }
        val age = System.nanoTime() - mapPreloadStartedNanos
        if (age < 0L || age >= MAP_PRELOAD_MAX_AGE_NANOS) {
            clearMapOverlayPreload()
            return false
        }
        return true
    }

    /** Active only while recent, parsed dungeon-map pixels are being supplied by the HUD module. */
    @JvmStatic
    fun isMapOverlayPixelsContextActive(): Boolean {
        if (!mapOverlayPixelsActive) return false
        val client = Minecraft.getInstance()
        val currentLevel = client?.level
        if (client == null || currentLevel == null || !DungeonContextLifecycleRules.shouldKeepMapOverlayPixelContext(
                mapOverlayPixelsActive,
                currentLevel === mapOverlayPixelsLevel,
                inDungeon,
                System.nanoTime() - mapOverlayPixelsSeenNanos,
                MAP_PIXEL_CONTEXT_GRACE_NANOS,
            )
        ) {
            clearMapOverlayPixelsContext()
            return false
        }
        return true
    }

    /** Combined map-local startup context; unlike inDungeon(), this never enables other modules. */
    @JvmStatic
    fun isMapOverlayContextActive(): Boolean = inDungeon || isMapOverlayPreloading() || isMapOverlayPixelsContextActive()

    /** True when the HUD is visible on its map-local startup context rather than normal dungeon state. */
    @JvmStatic
    fun isMapOverlayStartupContext(): Boolean = !inDungeon &&
        (isMapOverlayPreloading() || isMapOverlayPixelsContextActive())

    @JvmStatic
    fun shouldShowMapOverlay(showInBoss: Boolean, mobEspPhase: Boolean, mapStartupContext: Boolean): Boolean =
        DungeonContextLifecycleRules.shouldShowMapOverlay(inDungeon, showInBoss, mobEspPhase, mapStartupContext)

    /** Promotes countdown bootstrap to short-lived authority from a parsed dungeon-map pixel layout. */
    @JvmStatic
    fun observeAuthoritativeMapPixels(validMapGrid: Boolean) {
        val client = Minecraft.getInstance()
        val currentLevel = client?.level
        if (client == null || currentLevel == null || !client.isSameThread() ||
            !DungeonContextLifecycleRules.shouldObserveAuthoritativeMapPixels(validMapGrid, inDungeon)
        ) return
        mapOverlayPixelsLevel = currentLevel
        mapOverlayPixelsSeenNanos = System.nanoTime()
        mapOverlayPixelsActive = true
        clearMapOverlayPreload()
    }

    @JvmStatic
    fun mapOverlayPixelDiagnostics(): String {
        if (!isMapOverlayPixelsContextActive()) return "active=false"
        val age = maxOf(0L, System.nanoTime() - mapOverlayPixelsSeenNanos)
        return "active=true, ageMs=${age / 1_000_000L}, graceMs=${maxOf(0L, MAP_PIXEL_CONTEXT_GRACE_NANOS - age) / 1_000_000L}"
    }

    /** Current floor captured for the temporary map-only preload; never used to promote shared context. */
    @JvmStatic
    fun mapOverlayPreloadFloor(): DungeonFloor? = if (isMapOverlayPreloading()) mapPreloadFloor else null

    @JvmStatic
    fun mapOverlayContextSource(): String = when {
        inDungeon -> "confirmed-dungeon"
        isMapOverlayPixelsContextActive() -> "validated-map-pixels"
        isMapOverlayPreloading() -> mapPreloadSource
        else -> "inactive"
    }

    @JvmStatic
    fun mapOverlayPreloadDiagnostics(): String {
        if (!isMapOverlayPreloading()) return "active=false"
        val age = maxOf(0L, System.nanoTime() - mapPreloadStartedNanos)
        return "active=true, source=$mapPreloadSource, floor=${mapPreloadFloor?.displayName() ?: "unknown"}" +
            ", ageMs=${age / 1_000_000L}, remainingMs=${maxOf(0L, MAP_PRELOAD_MAX_AGE_NANOS - age) / 1_000_000L}"
    }

    /** Clears only the temporary map display state, for queue cancellation and session boundaries. */
    @JvmStatic
    fun clearMapOverlayPreload() {
        mapPreloadLevel = null
        mapPreloadFloor = null
        mapPreloadStartedNanos = 0L
        mapPreloadActive = false
        mapPreloadSource = "none"
    }

    /** Explicitly clears map-only startup state when the network session ends. */
    @JvmStatic
    fun clearMapOverlayStartupState() = clearMapOverlayContext()

    private fun clearMapOverlayPixelsContext() {
        mapOverlayPixelsLevel = null
        mapOverlayPixelsSeenNanos = 0L
        mapOverlayPixelsActive = false
    }

    private fun clearMapOverlayContext() {
        clearMapOverlayPreload()
        clearMapOverlayPixelsContext()
    }

    private fun hasCatacombsSidebar(signal: DungeonContextTracker.Signal?): Boolean =
        signal != null && signal.inDungeon && signal.floor != null

    /** Current pre-blood Clear phase for consumers that need that narrower context. */
    @JvmStatic
    fun isClearPhase(): Boolean = shouldEnableClearEsp(inDungeon, clearPhase)

    @JvmStatic
    fun shouldEnableClearEsp(dungeon: Boolean, clear: Boolean): Boolean = dungeon && clear

    /** Dungeon Mob ESP remains available from Entry through Blood Clear, until boss entry is observed. */
    @JvmStatic
    fun isMobEspPhase(): Boolean = shouldEnableMobEsp(inDungeon, mobEspBossPhase)

    @JvmStatic
    fun shouldEnableMobEsp(dungeon: Boolean, bossPhase: Boolean): Boolean = dungeon && !bossPhase

    @JvmStatic
    fun mobEspBossPhaseAfter(bossPhase: Boolean, segmentId: String?): Boolean {
        if (segmentId == null) return bossPhase
        return when (segmentId) {
            "ENTRY" -> false
            "BOSS_ENTRY", "COMPLETE", "BONZO_SIKE", "BONZO", "SCARF_MINIONS", "SCARF",
            "GUARDIANS", "PROFESSOR", "PROFESSOR_TRANSFORMED", "TRANSFORMED_PROFESSOR", "THORN", "LIVID",
            "TERRACOTTAS", "SADAN_GIANTS", "SADAN", "MAXOR", "STORM", "TERMINALS", "GOLDOR", "NECRON",
            "WITHER_KING" -> true
            else -> bossPhase
        }
    }

    @JvmStatic
    fun shouldBeginClearPhase(closedByObservedBloodOrBossSignal: Boolean): Boolean =
        DungeonContextLifecycleRules.shouldBeginClearPhase(closedByObservedBloodOrBossSignal)

    /** Receives normalized client chat independently of the optional Dungeon Guide module. */
    @JvmStatic
    fun onChatMessage(message: String?) {
        if (message.isNullOrBlank()) return
        val normalized = message.lowercase(Locale.ROOT)
        // The blood-door line is floor-independent and can arrive before a scoreboard floor is
        // available. Close the legacy Clear-only gate from that event rather than waiting for Guide state.
        if (normalized.contains("blood door has been opened")) {
            clearPhase = false
            clearEspClosedForRun = true
            return
        }
        if (normalized.contains("mort: here, i found this map when i first entered the dungeon.")) {
            clearPhase = inDungeon
            clearEspClosedForRun = false
            mobEspBossPhase = false
            bossLevel = null
            return
        }
        val floor = currentFloor()
        if (floor == null) return
        val event = DungeonGuideSegments.fromChat(floor, message)
        if (event == null || event.independentCue) return
        clearEspClosedForRun = DungeonContextLifecycleRules.clearEspClosedForRunAfterChat(
            clearEspClosedForRun,
            event.segmentId,
        )
        mobEspBossPhase = mobEspBossPhaseAfter(mobEspBossPhase, event.segmentId)
        bossLevel = if (mobEspBossPhase) bossLevel ?: level else null
        when (event.segmentId) {
            "ENTRY", "CLEAR" -> clearPhase = inDungeon
            "BLOOD_OPEN", "BLOOD_CLEAR", "BOSS_ENTRY", "COMPLETE", "BONZO_SIKE", "BONZO", "SCARF_MINIONS",
            "SCARF", "GUARDIANS", "PROFESSOR", "PROFESSOR_TRANSFORMED", "TRANSFORMED_PROFESSOR", "THORN",
            "LIVID", "TERRACOTTAS", "SADAN_GIANTS", "SADAN", "MAXOR", "STORM", "TERMINALS", "GOLDOR",
            "NECRON", "WITHER_KING" -> clearPhase = false
            else -> Unit
        }
    }

    @JvmStatic
    fun detectedFloor(): DungeonFloor? = detectedFloor

    @JvmStatic
    fun manualFloor(): DungeonFloor? = manualFloor

    @JvmStatic
    fun currentFloor(): DungeonFloor? =
        floorSelection(inDungeon, manualFloor, detectedFloor, DungeonQueueFloorTracker.currentFloor()).floor

    /** Exposes which validated source currently supplies the effective floor. */
    @JvmStatic
    fun floorSource(): DungeonContextTracker.FloorSource =
        floorSelection(inDungeon, manualFloor, detectedFloor, DungeonQueueFloorTracker.currentFloor()).source

    @JvmStatic
    fun diagnostics(): String {
        val floor = currentFloor()
        return "dungeon=$inDungeon, floor=${floor?.displayName() ?: "unknown"} [${floorSource()}], " +
            "detectedFloor=${detectedFloor?.displayName() ?: "unknown"}, " +
            "manualFloor=${manualFloor?.displayName() ?: "none"}, ${sidebarDiagnostics()}, ${DungeonMapRoomDetector.diagnostics()}"
    }

    /** Boss latch state and the sidebar text seen on the last tick before the dungeon signal dropped. */
    @JvmStatic
    fun sidebarDiagnostics(): String {
        return "bossLatch=${bossLevel != null && bossLevel === level} sidebarLosses=$sidebarLossCount " +
            "lastSidebarLoss=[$lastSidebarLossText]"
    }

    @JvmStatic
    fun floorSelection(
        dungeon: Boolean,
        manual: DungeonFloor?,
        sidebar: DungeonFloor?,
        queue: DungeonFloor?,
    ): DungeonContextTracker.FloorSelection {
        if (!dungeon) return DungeonContextTracker.FloorSelection(null, DungeonContextTracker.FloorSource.UNKNOWN)
        if (manual != null) return DungeonContextTracker.FloorSelection(manual, DungeonContextTracker.FloorSource.MANUAL)
        if (sidebar != null) return DungeonContextTracker.FloorSelection(sidebar, DungeonContextTracker.FloorSource.SIDEBAR)
        if (queue != null) return DungeonContextTracker.FloorSelection(queue, DungeonContextTracker.FloorSource.QUEUE)
        return DungeonContextTracker.FloorSelection(null, DungeonContextTracker.FloorSource.UNKNOWN)
    }

    /** Manual selection is accepted only while a dungeon context is active. */
    @JvmStatic
    fun setManualFloor(floor: DungeonFloor?): Boolean {
        if (!inDungeon || floor == null) return false
        manualFloor = floor
        return true
    }

    @JvmStatic
    fun clearManualFloor(): Boolean {
        val changed = manualFloor != null
        manualFloor = null
        return changed
    }

    private fun readSidebar(scoreboard: Scoreboard?): DungeonContextTracker.Signal {
        if (scoreboard == null) return DungeonContextTracker.Signal(false, null)
        val sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
            ?: return DungeonContextTracker.Signal(false, null)
        val text = StringBuilder(ChatText.plain(sidebar.displayName.string))
        for (entry in scoreboard.listPlayerScores(sidebar)) {
            if (entry == null || entry.isHidden()) continue
            val lineComponent: Component = entry.display() ?: run {
                val team: PlayerTeam? = scoreboard.getPlayersTeam(entry.owner())
                if (team == null) entry.ownerName() else team.getFormattedName(entry.ownerName())
            }
            val line: String? = lineComponent.string
            if (!line.isNullOrBlank()) text.append('\n').append(ChatText.plain(line))
        }
        lastSidebarText = text.toString()
        return readSidebarText(lastSidebarText)
    }

    @JvmStatic
    fun readSidebarText(plain: String?): DungeonContextTracker.Signal {
        if (plain.isNullOrBlank()) return DungeonContextTracker.Signal(false, null)
        val normalized = plain.lowercase(Locale.ROOT)
        val matcher = FLOOR.matcher(plain)
        var floor: DungeonFloor? = null
        while (matcher.find()) {
            if (matcher.group(3) != null) {
                floor = DungeonFloor.ENTRANCE
                break
            }
            var mode = matcher.group(1)
            if (normalized.contains("master mode")) mode = "M"
            val number = matcher.group(2)
            val parsed = DungeonFloor.romanFloor(number)
            if (parsed > 0) {
                floor = DungeonFloor.parse((mode ?: "F").uppercase(Locale.ROOT) + parsed)
                break
            }
        }
        // Hypixel has used both "The Catacombs (F7)" and "Dungeon: F7"-style sidebar
        // entries. Require a dungeon-specific label or an explicit floor alongside "dungeon"
        // so the party finder and ordinary SkyBlock scoreboard cannot activate this context.
        val dungeon = normalized.contains("catacombs") || normalized.contains("dungeon cleared") ||
            normalized.contains("dungeon: entrance") ||
            normalized.contains("dungeon") && (floor != null || normalized.contains("floor"))
        return DungeonContextTracker.Signal(dungeon, floor)
    }


    private fun clear() {
        level = null
        inDungeon = false
        clearPhase = false
        clearEspClosedForRun = false
        mobEspBossPhase = false
        bossLevel = null
        lastSignalInDungeon = false
        lastDungeonSignal = 0L
        detectedFloor = null
        manualFloor = null
        clearMapOverlayContext()
        DungeonMapRoomDetector.tick(null)
    }

    enum class FloorSource { UNKNOWN, MANUAL, SIDEBAR, QUEUE }

    @JvmRecord
    data class FloorSelection(val floor: DungeonFloor?, val source: FloorSource) {
        override fun toString(): String = "FloorSelection[floor=$floor, source=$source]"
    }

    @JvmRecord
    data class Signal(val inDungeon: Boolean, val floor: DungeonFloor?) {
        override fun toString(): String = "Signal[inDungeon=$inDungeon, floor=$floor]"
    }
}
