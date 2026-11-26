package geiler.addons.client.module.impl

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import geiler.addons.GeilerAddons
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonQueueFloorTracker
import geiler.addons.client.dungeon.DungeonStats
import geiler.addons.client.dungeon.DungeonStatsService
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyListRequestGate
import geiler.addons.client.party.PartyLookupAttempts
import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import geiler.addons.client.tree.ChatText
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import java.util.UUID
import java.util.regex.Matcher
import java.util.regex.Pattern

// Keep Java String.trim()/isBlank() semantics from the frozen 26.1.2 module.
private fun javaStringIsBlank(value: String?): Boolean =
    value == null || ChatStringSemantics.isBlank(value)

private fun javaStringTrim(value: String): String = ChatStringSemantics.trim(value)

/** Displays cached profile statistics when a Party Finder player joins. */
class PartyFinderStatsModule private constructor(private val settings: Settings = Settings()) :
    Module(
        "Party Finder Stats",
        "Shows dungeon statistics for Party Finder members.",
        Category.F7,
        *settings.allSettings().toTypedArray(),
    ),
    ModulePreview {

    companion object {
        @JvmField
        val INSTANCE = PartyFinderStatsModule()

        private val DUNGEON_JOIN = Pattern.compile(
            "^Party Finder > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}) joined the dungeon group! \\(([^)]+) Level \\d+\\)$",
        )
        private val PARTY_MEMBER_JOINED = Pattern.compile(
            "^.*?([A-Za-z0-9_]{1,16}) joined the party\\.$",
        )
        private val QUEUE_SUCCESS = Pattern.compile(
            "^Party Finder\\s*>\\s*Your party has been queued in the dungeon finder!$",
        )
        private const val LIST_FALLBACK_TICKS = 20
        private const val CHAT_CARD_GAP_TICKS = 5
        private const val MAX_QUEUED_CHAT_CARDS = 16
        private const val MAX_REDISPLAY_STATS_CARDS = MAX_QUEUED_CHAT_CARDS - 1
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_AUTOMATIC_DIAGNOSTIC_LINE_CHARS = 512
        private const val DSTATS_DIAGNOSTIC_SOURCE = "DStats"
        private const val MAX_DSTATS_DIAGNOSTIC_EVENTS_PER_WINDOW = 16

        @JvmStatic
        fun laterJoinDisposition(
            requestedEpoch: Long,
            currentEpoch: Long,
            displayEnabled: Boolean,
            autoKickEnabled: Boolean,
        ): LaterJoinDisposition {
            if (requestedEpoch == currentEpoch && displayEnabled) return LaterJoinDisposition.DISPLAY
            return if (autoKickEnabled) LaterJoinDisposition.AUTO_KICK_ONLY else LaterJoinDisposition.IGNORE
        }

        @JvmStatic
        fun retryCommand(name: String): String = "/ga pfretry $name"

        @JvmStatic
        fun showStatsCommand(): String = "/ga pfstats"

        @JvmStatic
        fun apiUnavailableStatus(name: String, error: String?): String {
            val reason = if (javaStringIsBlank(error)) "unavailable" else error
            return "Dungeon stats API unavailable: $name ($reason)"
        }

        private fun partyFinderJoinContext(): String {
            val island = HypixelModApi.currentIsland()
            if (island == Island.DUNGEON_HUB) return "dungeon_hub"
            if (island == Island.CATACOMBS || DungeonContextTracker.inDungeon()) return "catacombs"
            return "other"
        }

        private fun localName(): String? =
            Minecraft.getInstance().player?.getGameProfile()?.name()

        private fun findMember(snapshot: PartySnapshot, name: String): PartyMember? =
            snapshot.members().firstOrNull { it.name().equals(name, ignoreCase = true) }

        private fun identityCompatible(requested: PartyMember?, current: PartyMember?): Boolean {
            if (requested == null || current == null || !requested.name().equals(current.name(), true)) return false
            return requested.uuid() == null || current.uuid() == null || requested.uuid() == current.uuid()
        }

        private fun allPbs(stats: DungeonStats, master: Boolean): String {
            val lines = ArrayList<String>()
            for (floor in DungeonFloor.values()) {
                if (floor.number() == 0) continue
                if (floor.master() == master) {
                    lines.add(floor.displayName() + ": " + DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor)))
                }
            }
            return lines.joinToString("\n")
        }

        private fun isPartyLifecycleReset(message: String): Boolean {
            val lower = message.lowercase(Locale.ROOT)
            return lower == "you left the party."
                || lower.contains("the party was disbanded")
                || lower.contains("you have been kicked from the party")
                || lower.contains("you removed your group from the party finder")
                || lower.contains("you are not currently in a party")
                || lower == "you are not in a party."
        }

        private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        private fun formatBank(value: Long): String = String.format(Locale.ROOT, "%,d", value)

        private fun classColor(dungeonClass: DungeonClass?): ChatFormatting {
            if (dungeonClass == null) return ChatFormatting.YELLOW
            return when (dungeonClass) {
                DungeonClass.TANK -> ChatFormatting.GREEN
                DungeonClass.HEALER -> ChatFormatting.LIGHT_PURPLE
                DungeonClass.MAGE -> ChatFormatting.AQUA
                DungeonClass.BERSERK -> ChatFormatting.RED
                DungeonClass.ARCHER -> ChatFormatting.GOLD
            }
        }

        private fun mark(known: Boolean, present: Boolean): Component {
            val marker = Component.literal(if (!known) "?" else if (present) "✓" else "X")
                .withStyle(if (known && present) ChatFormatting.GREEN else if (known) ChatFormatting.RED else ChatFormatting.YELLOW)
            return if (known) marker else hover(marker, "Inventory API data unavailable")
        }

        private fun pvAction(name: String): Component =
            Component.literal("PV").withStyle { style ->
                style.withColor(ChatFormatting.GREEN)
                    .withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/pv $name"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Run /pv $name")))
            }

        private fun kickAction(name: String): Component =
            Component.literal("[Kick]").withStyle { style ->
                style.withColor(ChatFormatting.RED)
                    .withUnderlined(true)
                    .withClickEvent(ClickEvent.RunCommand("/party kick $name"))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("/party kick $name")))
            }

        private fun hover(component: Component, text: String): Component =
            hover(component, Component.literal(text))

        private fun hover(component: Component, text: Component): Component =
            component.copy().withStyle { style -> style.withHoverEvent(HoverEvent.ShowText(text)) }

        private fun joinParts(parts: List<Component>): Component {
            val joined = Component.empty()
            for (index in parts.indices) {
                if (index > 0) joined.append(separator())
                joined.append(parts[index])
            }
            return joined
        }

        private fun joinLines(lines: List<Component>): Component {
            val joined = Component.empty()
            for (index in lines.indices) {
                if (index > 0) joined.append("\n")
                joined.append(lines[index])
            }
            return joined
        }

        private fun cardTop(font: Font?, width: Int, label: String): Component {
            val title = Component.literal(label).withStyle { style ->
                style.withColor(ChatFormatting.AQUA).withBold(true)
            }
            val available = maxOf(0, width - textWidth(font, "╭╮") - textWidth(font, title.string))
            val dashCount = available / maxOf(1, textWidth(font, "━"))
            val left = dashCount / 2
            val right = dashCount - left
            return Component.literal("╭" + "━".repeat(left)).withStyle(ChatFormatting.DARK_AQUA)
                .append(title)
                .append(Component.literal("━".repeat(right) + "╮").withStyle(ChatFormatting.DARK_AQUA))
        }

        private fun cardBottom(font: Font?, width: Int): Component {
            val available = maxOf(0, width - textWidth(font, "╰╯"))
            val dashCount = available / maxOf(1, textWidth(font, "━"))
            return Component.literal("╰" + "━".repeat(dashCount) + "╯").withStyle(ChatFormatting.DARK_AQUA)
        }

        private fun textWidth(font: Font?, text: String): Int = font?.width(text) ?: text.length

        private fun chatTextWidth(mc: Minecraft): Int {
            val scale = maxOf(0.01, mc.options.chatScale().get())
            return maxOf(
                20,
                kotlin.math.floor(ChatComponent.getWidth(mc.options.chatWidth().get()) / scale).toInt() - 8,
            )
        }

        private fun separator(): Component = Component.literal(" │ ").withStyle(ChatFormatting.DARK_GRAY)
    }

    enum class LaterJoinDisposition {
        DISPLAY,
        AUTO_KICK_ONLY,
        IGNORE,
    }

    private class Settings {
        val compact = BooleanSetting("Compact", false)
        val showCata = BooleanSetting("Cata", true)
        val showOverflowCata = BooleanSetting("Show overflow Cata", true)
        val showClass = BooleanSetting("Class", true)
        val showClassAverage = BooleanSetting("CA", true)
        val showMagicalPower = BooleanSetting("MP", true)
        val showSecretAverage = BooleanSetting("SA", true)
        val showPersonalBest = BooleanSetting("PB", true)
        val showTerminator = BooleanSetting("Terminator", true)
        val showHyperion = BooleanSetting("Hyperion", true)
        val showGoldenDragon = BooleanSetting("GDrag", true)
        val showBank = BooleanSetting("Bank", true)
        val debug = BooleanSetting.debug("Debug", false)

        fun allSettings(): List<Setting> = listOf(
            compact,
            showCata,
            showOverflowCata,
            showClass,
            showClassAverage,
            showMagicalPower,
            showSecretAverage,
            showPersonalBest,
            showTerminator,
            showHyperion,
            showGoldenDragon,
            showBank,
            debug,
        )
    }

    private val compact = settings.compact
    private val debug = settings.debug
    private val showCata = settings.showCata
    private val showOverflowCata = settings.showOverflowCata
    private val showClass = settings.showClass
    private val showClassAverage = settings.showClassAverage
    private val showMagicalPower = settings.showMagicalPower
    private val showSecretAverage = settings.showSecretAverage
    private val showPersonalBest = settings.showPersonalBest
    private val showTerminator = settings.showTerminator
    private val showHyperion = settings.showHyperion
    private val showGoldenDragon = settings.showGoldenDragon
    private val showBank = settings.showBank
    private val lookupAttempts = PartyLookupAttempts()
    private val partyListRequestGate = PartyListRequestGate()
    private var requestedFloor: DungeonFloor? = null
    private var fetchRequested = false
    private var cycleStarted = false
    private var ticksSinceRequest = 0
    private var cycleTargetName: String? = null
    private var pendingResults = 0
    private var initialDispatching = false
    private var initialScanFinished = false
    private var localJoinedThroughFinder = false
    private var reminderShownForPartySession = false
    private var suppressInitialScanCards = false
    private var suppressedInitialScanCards = 0
    private var chatCardCooldown = 0
    private var scanSequence = 0L
    private var activeScanToken = -1L
    private var partySessionGeneration = Long.MIN_VALUE
    private var clientTick = 0L
    private val diagnostics = ModuleDiagnostics(
        Category.F7, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, 100L, MAX_AUTOMATIC_DIAGNOSTIC_LINE_CHARS,
    )
    private var dStatsTraceRequests = 0
    private var dStatsTraceSequence = 0L
    private var dStatsDiagnosticWindow = Long.MIN_VALUE
    private var dStatsDiagnosticEvents = 0
    private var suppressedDStatsDiagnostics = 0
    /** Invalidates display-owned later-join callbacks when the module is disabled. */
    private var displayLifecycleEpoch = 0L
    private val cycleStats = HashMap<String, DungeonStats>()
    /** UUID captured with each result so a late callback cannot be applied to a replacement name. */
    private val cycleStatUuids = HashMap<String, UUID?>()
    private val deferredJoins = LinkedHashMap<String, DungeonClass?>()
    private val scanPending = HashMap<Long, ScanState>()
    private val pendingChatCards = ArrayDeque<Component>()

    init {
        group(
            SettingGroup(
                "Display",
                settings.compact,
                settings.showCata,
                settings.showOverflowCata,
                settings.showClass,
                settings.showClassAverage,
                settings.showMagicalPower,
                settings.showSecretAverage,
                settings.showPersonalBest,
                settings.showTerminator,
                settings.showHyperion,
                settings.showGoldenDragon,
                settings.showBank,
            ),
            SettingGroup.debug("Diagnostics", settings.debug),
        )
    }

    fun onChatMessage(message: String?) {
        val normalized = javaStringTrim(ChatText.plain(message ?: ""))
        if (QUEUE_SUCCESS.matcher(normalized).matches()) {
            val floor = DungeonQueueFloorTracker.confirmQueued()
            requestedFloor = floor
            if (floor == null) {
                debug("Queue confirmed, but no fresh Group Builder floor was captured")
            } else {
                debug("Queue confirmed; activated Group Builder floor %s", floor.displayName())
            }
            return
        }
        if (isPartyLifecycleReset(normalized)) {
            pendingChatCards.clear()
            reminderShownForPartySession = false
            suppressInitialScanCards = false
            suppressedInitialScanCards = 0
            if (DungeonQueueFloorTracker.clear()) debug("Cleared queued floor after party lifecycle reset")
        }
        syncPartySession(PartyListBackend.snapshot().generation())
        val departedMember = PartyListBackend.departedMemberName(normalized)
        if (departedMember != null) {
            clearDepartedMemberLookup(departedMember)
            return
        }
        val partyJoin = PARTY_MEMBER_JOINED.matcher(normalized)
        if (partyJoin.matches()) {
            val currentParty = PartyListBackend.snapshot()
            val joinedName = partyJoin.group(1)
            if (!wantsData() || !cycleStarted || !currentParty.inParty()
                || joinedName.equals(localName(), ignoreCase = true)
            ) return
            var effectiveFloor = DungeonQueueFloorTracker.currentFloor()
            if (effectiveFloor == null) effectiveFloor = requestedFloor
            if (fetchRequested || !initialScanFinished) {
                deferredJoins[joinedName.lowercase(Locale.ROOT)] = null
                debug("Queued chat-joined member %s for the active initial party scan", joinedName)
            } else {
                debug("Detected chat-joined party member %s; fetching only that player", joinedName)
                fetchJoinedPlayer(effectiveFloor, joinedName, null)
            }
            return
        }
        val matcher = DUNGEON_JOIN.matcher(normalized)
        if (!matcher.matches()) {
            if (debug.value() && normalized.startsWith("Party Finder >")) {
                debug("Ignored Party Finder line: %s", normalized)
            }
            return
        }
        val name = matcher.group(1)
        val classOrFloor = matcher.group(2)
        val localJoin = name.equals(localName(), ignoreCase = true)
        if (localJoin) {
            val joinedFloor = DungeonQueueFloorTracker.confirmJoinedListing()
            if (joinedFloor == null) {
                debug("Local Party Finder join had no fresh clicked-listing floor")
            } else {
                debug("Local Party Finder join activated clicked listing floor %s", joinedFloor.displayName())
            }
        }
        val joinContext = if (localJoin) partyFinderJoinContext() else "other"
        val remindToCheckStats = localJoin && isEnabled()
            && (joinContext == "catacombs" || joinContext == "dungeon_hub")
        if (remindToCheckStats) showPartyStatsReminder()
        if (localJoin) {
            diagnostics.event(
                "local_pf_join context=$joinContext reminder=$remindToCheckStats reminderShown=$reminderShownForPartySession",
            )
        }
        val floor = DungeonQueueFloorTracker.currentFloor()
        val joinedClass = DungeonClass.parse(classOrFloor)
        if (!wantsData()) {
            debug("Join detected for %s (%s), but both dungeon modules are disabled", name, classOrFloor)
            return
        }
        if (joinedClass != null) {
            PartyListBackend.setClass(name, joinedClass)
            recheckRetainedAutoKickStats(floor ?: requestedFloor, PartyListBackend.snapshot().generation())
        }
        val currentParty = PartyListBackend.snapshot()
        if (cycleStarted && currentParty.inParty()) {
            val effectiveFloor = floor ?: requestedFloor
            if (floor != null) requestedFloor = floor
            if (fetchRequested || !initialScanFinished) {
                if (remindToCheckStats) suppressActiveInitialScanCards()
                deferredJoins[name.lowercase(Locale.ROOT)] = joinedClass
                debug("Queued %s for the active initial party scan", name)
            } else {
                debug("Detected later join for %s as %s; fetching that player", name, classOrFloor)
                fetchJoinedPlayer(effectiveFloor, name, joinedClass, remindToCheckStats)
            }
            return
        }

        requestedFloor = floor
        cycleTargetName = name
        localJoinedThroughFinder = localJoin
        suppressInitialScanCards = remindToCheckStats
        suppressedInitialScanCards = 0
        if (suppressInitialScanCards) diagnostics.event("initial_scan_card_suppression enabled=true")
        scanPending.clear()
        activeScanToken = -1L
        deferredJoins.clear()
        fetchRequested = true
        cycleStarted = true
        initialScanFinished = false
        ticksSinceRequest = 0
        debug(
            "Detected %s joining as %s; queued floor=%s; bootstrapping party roster",
            name,
            classOrFloor,
            floor?.displayName() ?: "unknown",
        )
        if (!requestPartyList(currentParty) && PartyListBackend.hasStableList()) {
            // A complete roster is already available; do not wait for a list response that was not requested.
            ticksSinceRequest = LIST_FALLBACK_TICKS
        }
    }

    fun tick() {
        clientTick++
        tickAutomaticDiagnostics()
        tickChatCards()
        if (DungeonQueueFloorTracker.tick(Minecraft.getInstance())) {
            pendingChatCards.clear()
            debug("Cleared queued floor after a client-level change")
        }
        val stableList = PartyListBackend.consumeStableListResponse()
        val currentParty = PartyListBackend.snapshot()
        syncPartySession(currentParty.generation())
        if (!fetchRequested) {
            if (stableList && cycleStarted && initialScanFinished) reconcileStableList()
            if (cycleStarted && currentParty.inParty() && wantsData() && !PartyListBackend.hasStableList()) {
                requestPartyList(currentParty)
            }
            if (!currentParty.inParty()) {
                cycleStarted = false
                pendingResults = 0
                initialDispatching = false
                initialScanFinished = false
                localJoinedThroughFinder = false
                reminderShownForPartySession = false
                suppressInitialScanCards = false
                suppressedInitialScanCards = 0
                activeScanToken = -1L
                scanPending.clear()
                deferredJoins.clear()
            }
            return
        }
        ticksSinceRequest++
        if (!stableList && ticksSinceRequest < LIST_FALLBACK_TICKS) return
        fetchRequested = false
        val snapshot = currentParty
        if (!snapshot.inParty()) {
            debug("Party scan stopped: the party list is empty")
            return
        }
        val generation = snapshot.generation()
        val scanToken = ++scanSequence
        activeScanToken = scanToken
        val suppressCardsForScan = suppressInitialScanCards
        val queuedFloor = requestedFloor
        val targetName = cycleTargetName
        for ((memberName, dungeonClass) in deferredJoins) {
            if (dungeonClass != null) PartyListBackend.setClass(memberName, dungeonClass)
        }
        val local = localName()
        val membersToFetch = ArrayList<LookupWork>()
        for (member in snapshot.members()) {
            if (local != null && member.name().equals(local, ignoreCase = true)) {
                debug("Skipping local player %s during party scan", member.name())
                continue
            }
            val begin = lookupAttempts.beginAutomatic(generation, member)
            if (begin.started()) {
                membersToFetch.add(LookupWork(member, begin.handle!!))
            } else {
                val attempt = begin.attempt
                if (attempt != null) {
                    if (attempt.state == PartyLookupAttempts.State.SUCCESS && attempt.stats != null) {
                        putCycleStats(attempt.stats, member)
                    } else if (attempt.state == PartyLookupAttempts.State.UNAVAILABLE
                        && (isEnabled() || AutoKickModule.INSTANCE.wantsData())
                        && lookupAttempts.markUnavailableReported(generation, member)
                    ) {
                        if (suppressCardsForScan) recordSuppressedInitialScanCard("unavailable")
                        else member.name()?.let { showUnavailable(it, attempt.error) }
                    }
                }
            }
        }
        pendingResults = membersToFetch.size
        scanPending[scanToken] = ScanState(generation, pendingResults, suppressCardsForScan)
        debug(
            "Starting stats scan for %d party member(s), floor=%s, trigger=%s, list=%s",
            pendingResults,
            queuedFloor?.displayName() ?: "unknown",
            targetName,
            if (stableList) "stable" else "fallback",
        )
        diagnostics.event(
            "initial_scan_start generation=$generation roster=" + snapshot.members().size
                + " members=$pendingResults floor=" + (queuedFloor?.name ?: "unknown")
                + " triggerPresent=" + (targetName != null)
                + " stableList=$stableList suppressedDisplay=$suppressCardsForScan",
        )
        initialDispatching = true
        for (work in membersToFetch) {
            diagnostics.event(
                "lookup_dispatch source=initial generation=" + generation
                    + " scan=" + scanToken + " attempt=" + work.handle.token
                    + " uuidKnown=" + (work.member.uuid() != null),
            )
            DungeonStatsService.fetch(work.member.name()) { result ->
                onResult(scanToken, generation, work.handle, queuedFloor, targetName, work.member, result)
            }
        }
        initialDispatching = false
        if (pendingResults == 0) finishInitialScan(scanToken, queuedFloor, generation, targetName)
    }

    /** Called by the container click mixin before Hypixel closes Group Builder. */
    fun onContainerSlotClick(
        screen: AbstractContainerScreen<*>?,
        slot: Slot?,
        button: Int,
        input: ContainerInput?,
    ) {
        if (!wantsData()) return
        var result = DungeonQueueFloorTracker.captureConfirmation(screen, slot, button, input)
        if (result.attempted) {
            if (result.floor == null) {
                debug("Group Builder confirmation detected, but floor capture failed: %s", result.error)
            } else {
                debug(
                    "Captured Group Builder floor candidate %s; waiting for queue confirmation",
                    result.floor.displayName(),
                )
            }
            return
        }
        result = DungeonQueueFloorTracker.captureJoinedListing(screen, slot, button, input)
        if (!result.attempted) return
        if (result.floor == null) {
            debug("Party Finder listing click detected, but floor capture failed: %s", result.error)
        } else {
            debug("Captured clicked Party Finder listing floor candidate %s", result.floor.displayName())
        }
    }

    private fun onResult(
        scanToken: Long,
        requestedGeneration: Long,
        handle: PartyLookupAttempts.RequestHandle,
        floor: DungeonFloor?,
        targetName: String?,
        requestedMember: PartyMember,
        result: DungeonStatsService.Result,
    ) {
        val requestedName = requestedMember.name()
        val snapshot = PartyListBackend.snapshot()
        syncPartySession(snapshot.generation())
        val attemptAccepted = lookupAttempts.complete(
            handle,
            snapshot.generation(),
            if (result.available()) result.stats else null,
            result.error,
        )
        val scan = scanPending[scanToken] ?: return
        val sameGeneration = snapshot.generation() == scan.generation()
            && requestedGeneration == scan.generation()
        var member = if (snapshot.inParty() && requestedName != null) {
            findMember(snapshot, requestedName)
        } else {
            null
        }
        val sameIdentity = sameGeneration && member != null && identityCompatible(requestedMember, member)
        diagnostics.event(
            "lookup_result source=initial generation=" + snapshot.generation()
                + " requestedGeneration=" + requestedGeneration + " scan=" + scanToken
                + " attempt=" + handle.token + " available=" + result.available()
                + " accepted=" + attemptAccepted + " sameGeneration=" + sameGeneration
                + " memberPresent=" + (member != null) + " sameIdentity=$sameIdentity"
                + " suppressedDisplay=" + scan.suppressDisplay()
                + " errorType=" + (result.error?.let { it.substringBefore(':').take(48) } ?: "none"),
        )
        if (sameGeneration && !result.available()) {
            debug("Stats unavailable for %s: %s", requestedName, result.error)
            val rosterTemporarilyIncomplete = member == null && snapshot.inParty()
                && !PartyListBackend.hasStableList()
            if (attemptAccepted && (sameIdentity || rosterTemporarilyIncomplete)
                && (isEnabled() || AutoKickModule.INSTANCE.wantsData())
                && lookupAttempts.markUnavailableReported(snapshot.generation(), requestedMember)
            ) {
                if (scan.suppressDisplay()) recordSuppressedInitialScanCard("unavailable")
                else requestedName?.let { showUnavailable(it, result.error) }
            } else if (member != null && !sameIdentity) {
                debug("Ignored unavailable result for replaced member %s", requestedName)
            }
        } else if (sameGeneration && result.available()) {
            val stats = result.stats!!
            debug(
                "Stats loaded for %s: cata=%d, class=%s, secrets=%d, mp=%d",
                stats.name(),
                stats.catacombsLevel(),
                stats.selectedClass()?.displayName() ?: "unknown",
                stats.totalSecrets(),
                stats.magicalPower(),
            )
            if (attemptAccepted && (member == null || sameIdentity)) {
                putCycleStats(stats, member ?: requestedMember)
            }
            if (attemptAccepted && sameIdentity) {
                PartyListBackend.setUuid(stats.name(), stats.uuid())
                PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass())
                member = findMember(PartyListBackend.snapshot(), stats.name())
                if (member != null) putCycleStats(stats, member)
                if (member != null && isEnabled()) {
                    if (scan.suppressDisplay()) recordSuppressedInitialScanCard("stats")
                    else showStats(floor, member, stats)
                }
                AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats)
                recheckRetainedAutoKickStats(floor, snapshot.generation())
            } else if (member != null && !sameIdentity) {
                debug("Ignored stale stats result for replaced member %s", requestedName)
            }
        }

        val next = scan.remaining() - 1
        if (next <= 0) {
            scanPending.remove(scanToken)
            if (scanToken == activeScanToken) {
                pendingResults = 0
                if (!sameGeneration) {
                    // A disband/rejoin creates a new backend generation. Retire the old callbacks, but do
                    // not leave the state machine waiting forever for results that are being discarded.
                    activeScanToken = -1L
                    initialScanFinished = false
                    cycleStarted = false
                    cycleTargetName = null
                    cycleStats.clear()
                    cycleStatUuids.clear()
                    deferredJoins.clear()
                    debug("Retired stale party scan %d after generation changed", scanToken)
                } else {
                    debug("Stats result received for %s (0 remaining)", requestedName)
                    if (!initialDispatching) finishInitialScan(scanToken, floor, scan.generation(), targetName)
                }
            }
            return
        }
        scanPending[scanToken] = ScanState(scan.generation(), next, scan.suppressDisplay())
        if (scanToken == activeScanToken) pendingResults = next
        debug("Stats result received for %s (%d remaining)", requestedName, next)
    }

    private fun finishInitialScan(
        scanToken: Long,
        floor: DungeonFloor?,
        generation: Long,
        targetName: String?,
    ) {
        if (scanToken != activeScanToken) return
        if (PartyListBackend.snapshot().generation() != generation) {
            activeScanToken = -1L
            pendingResults = 0
            initialScanFinished = false
            cycleStarted = false
            return
        }
        if (initialScanFinished) {
            debug("Ignored duplicate completion for party scan generation %d", generation)
            return
        }
        initialScanFinished = true
        val suppressDeferredDisplay = suppressInitialScanCards
        val deferredCount = deferredJoins.size
        val currentParty = PartyListBackend.snapshot()
        val targetMember = if (targetName == null) null else findMember(currentParty, targetName)
        val target = statsFor(targetMember)
        debug("Stats scan finished; target=%s; auto-kick evaluation floor=%s", targetName, floor?.displayName() ?: "unknown")
        if (target != null) AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), target)
        // A /p list refresh can briefly hide a member while its callback arrives. The result is
        // retained above; replay every retained result against the now-stable active party so that
        // transient list timing cannot permanently skip an enforcement decision.
        val activeParty = PartyListBackend.snapshot()
        for (stats in cycleStats.values) {
            val member = findMember(activeParty, stats.name())
            if (member != null && statsFor(member) === stats) {
                AutoKickModule.INSTANCE.onStats(floor, activeParty.generation(), stats)
            }
        }
        for ((name, dungeonClass) in deferredJoins) {
            val member = findMember(PartyListBackend.snapshot(), name)
            val stats = statsFor(member)
            if (stats != null) {
                AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats)
            } else {
                fetchJoinedPlayer(floor, name, dungeonClass, suppressDeferredDisplay)
            }
        }
        deferredJoins.clear()
        suppressInitialScanCards = false
        diagnostics.event(
            "initial_scan_complete generation=$generation deferred=$deferredCount suppressedDisplay=$suppressDeferredDisplay suppressedCards=$suppressedInitialScanCards",
        )
        val snapshot = PartyListBackend.snapshot()
        if (localJoinedThroughFinder && snapshot.inParty() && !snapshot.isLeader(localName()) && isEnabled()) {
            queueChatCard(partyStatsCompletionAction())
            debug("Queued one Leave Party action after the joined-party stats scan")
        }
    }

    private fun fetchJoinedPlayer(floor: DungeonFloor?, name: String, joinedClass: DungeonClass?) {
        fetchJoinedPlayer(floor, name, joinedClass, false)
    }

    private fun fetchJoinedPlayer(
        floor: DungeonFloor?,
        name: String,
        joinedClass: DungeonClass?,
        suppressDisplay: Boolean,
    ) {
        val local = localName()
        if (local != null && name.equals(local, ignoreCase = true)) {
            debug("Skipping local player %s", name)
            return
        }
        val snapshot = PartyListBackend.snapshot()
        val currentMember = findMember(snapshot, name) ?: return
        val begin = lookupAttempts.beginAutomatic(snapshot.generation(), currentMember)
        if (!begin.started()) {
            val attempt = begin.attempt
            if (attempt != null && attempt.state == PartyLookupAttempts.State.SUCCESS && attempt.stats != null) {
                putCycleStats(attempt.stats, currentMember)
                if (isEnabled()) {
                    if (suppressDisplay) recordSuppressedInitialScanCard("stats")
                    else showStats(floor, currentMember, attempt.stats)
                }
                AutoKickModule.INSTANCE.onStats(floor, snapshot.generation(), attempt.stats)
            } else if (attempt != null && attempt.state == PartyLookupAttempts.State.UNAVAILABLE
                && (isEnabled() || AutoKickModule.INSTANCE.wantsData())
                && lookupAttempts.markUnavailableReported(snapshot.generation(), currentMember)
            ) {
                if (suppressDisplay) recordSuppressedInitialScanCard("unavailable")
                else showUnavailable(currentMember.name() ?: name, attempt.error)
            }
            debug("Reused %s stats attempt for %s", attempt?.state ?: "unknown", name)
            return
        }
        val requestedMember = currentMember
        val requestedGeneration = snapshot.generation()
        val requestedDisplayEpoch = displayLifecycleEpoch
        diagnostics.event(
            "lookup_dispatch source=later generation=$requestedGeneration attempt=" + begin.handle!!.token
                + " uuidKnown=" + (requestedMember.uuid() != null)
                + " suppressDisplay=$suppressDisplay",
        )
        DungeonStatsService.fetch(name) { result ->
            onLaterJoinResult(
                requestedDisplayEpoch,
                begin.handle!!,
                floor,
                name,
                joinedClass,
                requestedMember,
                requestedGeneration,
                suppressDisplay,
                false,
                result,
            )
        }
    }

    private fun onLaterJoinResult(
        requestedDisplayEpoch: Long,
        handle: PartyLookupAttempts.RequestHandle,
        floor: DungeonFloor?,
        name: String,
        joinedClass: DungeonClass?,
        requestedMember: PartyMember,
        requestedGeneration: Long,
        suppressDisplay: Boolean,
        explicitRetry: Boolean,
        result: DungeonStatsService.Result,
    ) {
        val snapshot = PartyListBackend.snapshot()
        syncPartySession(snapshot.generation())
        val attemptAccepted = lookupAttempts.complete(
            handle,
            snapshot.generation(),
            if (result.available()) result.stats else null,
            result.error,
        )
        val autoKickActive = AutoKickModule.INSTANCE.wantsData()
        val disposition = if (explicitRetry) LaterJoinDisposition.DISPLAY
            else laterJoinDisposition(requestedDisplayEpoch, displayLifecycleEpoch, isEnabled(), autoKickActive)
        // If neither consumer remains active, a callback from before disablement must not recreate
        // chat cards or repopulate the display's party caches after onDisable cleared them.
        if (disposition == LaterJoinDisposition.IGNORE) {
            diagnostics.event(
                "lookup_result source=later generation=" + snapshot.generation()
                    + " requestedGeneration=$requestedGeneration attempt=" + handle.token
                    + " available=" + result.available() + " accepted=$attemptAccepted disposition=ignore",
            )
            return
        }
        val displayCurrent = disposition == LaterJoinDisposition.DISPLAY
        val sameGeneration = snapshot.generation() == requestedGeneration
        var member = if (snapshot.inParty()) findMember(snapshot, name) else null
        val sameIdentity = sameGeneration && member != null && identityCompatible(requestedMember, member)
        diagnostics.event(
            "lookup_result source=" + (if (explicitRetry) "retry" else "later")
                + " generation=" + snapshot.generation() + " requestedGeneration=" + requestedGeneration
                + " attempt=" + handle.token + " available=" + result.available()
                + " accepted=$attemptAccepted disposition=" + disposition.name.lowercase(Locale.ROOT)
                + " sameGeneration=$sameGeneration memberPresent=" + (member != null)
                + " sameIdentity=$sameIdentity suppressDisplay=$suppressDisplay"
                + " errorType=" + (result.error?.substringBefore(':')?.take(48) ?: "none"),
        )
        if (!sameGeneration) {
            debug("Discarded stale later-join result for %s after party generation changed", name)
            return
        }
        if (member == null) {
            if (displayCurrent && attemptAccepted && result.available()) {
                putCycleStats(result.stats, requestedMember)
            } else if (displayCurrent && attemptAccepted && snapshot.inParty()
                && !PartyListBackend.hasStableList()
                && lookupAttempts.markUnavailableReported(snapshot.generation(), requestedMember)
            ) {
                if (suppressDisplay) recordSuppressedInitialScanCard("unavailable")
                else showUnavailable(name, result.error)
            }
            debug("Discarded stale stats result for %s", name)
            return
        }
        if (!attemptAccepted || !identityCompatible(requestedMember, member)) {
            debug("Ignored stale later-join result for replaced member %s", name)
            return
        }
        if (!result.available()) {
            debug("Stats unavailable for later join %s: %s", name, result.error)
            val rosterTemporarilyIncomplete = snapshot.inParty() && !PartyListBackend.hasStableList()
            if ((displayCurrent || autoKickActive) && (member != null || rosterTemporarilyIncomplete)
                && lookupAttempts.markUnavailableReported(snapshot.generation(), requestedMember)
            ) {
                if (suppressDisplay) recordSuppressedInitialScanCard("unavailable")
                else showUnavailable(name, result.error)
            }
            return
        }
        val stats = result.stats!!
        // Auto Kick may still need the shared party identity enrichment after the display module was
        // disabled, but display-owned maps are only updated by a current display request.
        PartyListBackend.setUuid(stats.name(), stats.uuid())
        PartyListBackend.setClassIfUnknown(stats.name(), joinedClass)
        PartyListBackend.setClassIfUnknown(stats.name(), stats.selectedClass())
        member = findMember(PartyListBackend.snapshot(), stats.name())
        if (displayCurrent && member != null) putCycleStats(stats, member)
        debug(
            "Later-join stats loaded for %s: cata=%d, mp=%d, class=%s",
            stats.name(),
            stats.catacombsLevel(),
            stats.magicalPower(),
            member?.dungeonClass()?.displayName() ?: "unknown",
        )
        if (displayCurrent && member != null) {
            if (suppressDisplay) recordSuppressedInitialScanCard("stats")
            else showStats(floor, member, stats)
        }
        if (autoKickActive) {
            AutoKickModule.INSTANCE.onStats(floor, PartyListBackend.snapshot().generation(), stats)
            recheckRetainedAutoKickStats(floor, snapshot.generation())
        }
    }

    /** Re-evaluates cached member stats after a roster class becomes available, without another request. */
    private fun recheckRetainedAutoKickStats(floor: DungeonFloor?, generation: Long) {
        if (floor == null || !AutoKickModule.INSTANCE.wantsData()) return
        val snapshot = PartyListBackend.snapshot()
        if (!snapshot.inParty() || snapshot.generation() != generation) return
        for (member in snapshot.members()) {
            val attempt = lookupAttempts.get(generation, member)
            if (attempt != null && attempt.state == PartyLookupAttempts.State.SUCCESS && attempt.stats != null) {
                AutoKickModule.INSTANCE.onStats(floor, generation, attempt.stats)
            }
        }
    }

    private fun wantsData(): Boolean = isEnabled() || AutoKickModule.INSTANCE.wantsData()

    private fun showPartyStatsReminder() {
        if (reminderShownForPartySession) return
        val mc = Minecraft.getInstance()
        if (mc.gui == null) return
        mc.gui.hud.getChat().addClientSystemMessage(
            Component.literal("[PF] Check party stats.").withStyle(ChatFormatting.GRAY),
        )
        reminderShownForPartySession = true
        diagnostics.event("party_stats_reminder displayed=true")
    }

    private fun suppressActiveInitialScanCards() {
        suppressInitialScanCards = true
        scanPending.replaceAll { _, scan -> scan.withSuppressedDisplay(true) }
        diagnostics.event("initial_scan_card_suppression enabled=true")
    }

    private fun recordSuppressedInitialScanCard(kind: String) {
        if (suppressedInitialScanCards < Int.MAX_VALUE) suppressedInitialScanCards++
        diagnostics.event("initial_scan_card_suppressed kind=$kind total=$suppressedInitialScanCards")
    }

    private fun tickAutomaticDiagnostics() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.statusOnChange(20L) { "${automaticDiagnosticStatus()} sampleTicks=20 heartbeatTicks=100" }
    }

    private fun automaticDiagnosticStatus(): String {
        val party = PartyListBackend.snapshot()
        return "enabled=true context=" + partyFinderJoinContext() +
            " inParty=" + party.inParty() + " generation=" + party.generation() +
            " stableList=" + PartyListBackend.hasStableList() + " members=" + party.members().size +
            " floor=" + (requestedFloor?.name ?: "unknown") +
            " cycleStarted=$cycleStarted fetchRequested=$fetchRequested" +
            " initialDispatching=$initialDispatching initialScanFinished=$initialScanFinished" +
            " scanToken=$activeScanToken pending=$pendingResults deferred=" + deferredJoins.size +
            " lookupRequests=" + partyListRequestGate.requestCount(party.generation()) +
            " queuedCards=" + pendingChatCards.size + " reminderShown=$reminderShownForPartySession" +
            " suppressInitialCards=$suppressInitialScanCards suppressedCards=$suppressedInitialScanCards"
    }

    private fun syncPartySession(generation: Long) {
        if (partySessionGeneration == generation) return
        partySessionGeneration = generation
        lookupAttempts.reset(generation)
        cycleStats.clear()
        cycleStatUuids.clear()
        fetchRequested = false
        cycleStarted = false
        ticksSinceRequest = 0
        cycleTargetName = null
        pendingResults = 0
        initialDispatching = false
        initialScanFinished = false
        localJoinedThroughFinder = false
        reminderShownForPartySession = false
        suppressInitialScanCards = false
        suppressedInitialScanCards = 0
        activeScanToken = -1L
        scanPending.clear()
        deferredJoins.clear()
        debug("Started party lookup session %d", generation)
        diagnostics.event("party_session generation=$generation")
    }

    private fun clearDepartedMemberLookup(name: String) {
        AutoKickModule.INSTANCE.onMemberDeparted(name)
        val snapshot = PartyListBackend.snapshot()
        syncPartySession(snapshot.generation())
        val attemptCleared = lookupAttempts.forgetMember(snapshot.generation(), name)
        val key = name.lowercase(Locale.ROOT)
        cycleStats.remove(key)
        cycleStatUuids.remove(key)
        deferredJoins.remove(key)
        debug(
            "Observed confirmed departure for %s; cleared per-membership lookup state (attempt=%s)",
            name,
            attemptCleared,
        )
        diagnostics.event("member_departure attemptCleared=$attemptCleared")
    }

    private fun requestPartyList(snapshot: PartySnapshot?): Boolean {
        if (snapshot == null || !snapshot.inParty() || PartyListBackend.hasStableList()) return false
        if (PartyListBackend.listResponsePending()) {
            diagnostics.statusOnChange(20L) { "party_list_request outcome=waiting_for_open_response generation=${snapshot.generation()}" }
            return false
        }
        val mc = Minecraft.getInstance()
        val connection = mc.player?.connection
        if (connection == null) {
            debug("Could not request a party-list bootstrap/recovery because the client connection is unavailable")
            diagnostics.event("party_list_request outcome=no_connection")
            return false
        }
        if (!partyListRequestGate.shouldRequest(snapshot.generation(), clientTick, PartyListBackend.hasStableList())) {
            return false
        }
        connection.sendCommand("p list")
        debug(
            "Requested /p list for roster bootstrap/recovery (%d request(s) this party session)",
            partyListRequestGate.requestCount(snapshot.generation()),
        )
        diagnostics.event(
            "party_list_request outcome=sent generation=" + snapshot.generation()
                + " count=" + partyListRequestGate.requestCount(snapshot.generation()),
        )
        return true
    }

    /** Manually retries an unavailable or incomplete lookup for a current party member. */
    fun retryPlayer(name: String?) {
        if (name == null || !ChatStringSemantics.isMinecraftUsername(name)) {
            showRetryFeedback("Usage: /ga pfretry <player-name>")
            return
        }
        val snapshot = PartyListBackend.snapshot()
        syncPartySession(snapshot.generation())
        val member = if (snapshot.inParty()) findMember(snapshot, name) else null
        if (member == null) {
            showRetryFeedback("$name is not in the currently tracked party.")
            diagnostics.event("manual_retry outcome=member_not_found")
            return
        }
        val retry = lookupAttempts.beginManualRetry(snapshot.generation(), member, clientTick)
        if (!retry.started()) {
            val reason = when (retry.blockReason) {
                PartyLookupAttempts.RetryBlockReason.NOT_TRACKED -> "No failed or incomplete stats attempt is recorded for $name."
                PartyLookupAttempts.RetryBlockReason.PENDING -> "Stats are already being checked for $name."
                PartyLookupAttempts.RetryBlockReason.NOT_RETRYABLE -> "Stats for $name are already available."
                PartyLookupAttempts.RetryBlockReason.COOLDOWN -> {
                    val seconds = maxOf(1, (retry.cooldownTicksRemaining + 19) / 20)
                    "Retry for $name is on cooldown for " + seconds + "s."
                }
                else -> "Could not retry stats for $name."
            }
            diagnostics.event(
                "manual_retry outcome=blocked reason=" + retry.blockReason
                    + " cooldownTicks=" + retry.cooldownTicksRemaining,
            )
            showRetryFeedback(reason)
            return
        }
        val floor = requestedFloor
        val displayEpoch = displayLifecycleEpoch
        AutoKickModule.INSTANCE.onExplicitStatsRetryStarted(snapshot.generation(), name)
        diagnostics.event(
            "lookup_dispatch source=retry generation=" + snapshot.generation()
                + " attempt=" + retry.handle!!.token + " uuidKnown=" + (member.uuid() != null),
        )
        DungeonStatsService.fetchFresh(name) { result ->
            onLaterJoinResult(
                displayEpoch,
                retry.handle!!,
                floor,
                name,
                member.dungeonClass(),
                member,
                snapshot.generation(),
                false,
                true,
                result,
            )
        }
        showRetryFeedback("Retrying dungeon stats for $name.")
    }

    private fun showRetryFeedback(text: String) {
        val mc = Minecraft.getInstance()
        if (mc.gui != null) {
            mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[PF] $text").withStyle(ChatFormatting.GRAY),
            )
        }
    }

    private fun retryAction(name: String): Component {
        val command = retryCommand(name)
        return Component.literal("[Retry]").withStyle { style ->
            style.withColor(ChatFormatting.YELLOW)
                .withUnderlined(true)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Retry dungeon stats for $name")))
        }
    }

    /**
     * A repeated /p list is only a view refresh, not a new party session. Keep the results already
     * shown, but pick up members that appeared while the original asynchronous scan was running.
     */
    private fun reconcileStableList() {
        val snapshot = PartyListBackend.snapshot()
        if (!snapshot.inParty()) return
        val local = localName()
        for (member in snapshot.members()) {
            val memberName = member.name() ?: continue
            if (local != null && memberName.equals(local, ignoreCase = true)) continue
            val stats = statsFor(member)
            val attempt = lookupAttempts.get(snapshot.generation(), member)
            if (stats != null) {
                putCycleStats(stats, member)
                // Re-apply retained results to the current party view. AutoKick's handled key keeps
                // completed actions idempotent, while a changed class/UUID gets a fresh evaluation.
                AutoKickModule.INSTANCE.onStats(requestedFloor, snapshot.generation(), stats)
            } else if (attempt == null) {
                fetchJoinedPlayer(requestedFloor, memberName, member.dungeonClass())
            } else if (attempt.state == PartyLookupAttempts.State.UNAVAILABLE) {
                // Report a failure that arrived during a list rebuild once the member reappears.
                if ((isEnabled() || AutoKickModule.INSTANCE.wantsData())
                    && lookupAttempts.markUnavailableReported(snapshot.generation(), member)
                ) {
                    showUnavailable(memberName, attempt.error)
                }
            }
        }
    }

    private fun putCycleStats(stats: DungeonStats?, member: PartyMember?) {
        if (stats == null || member == null) return
        val key = member.name()?.lowercase(Locale.ROOT) ?: return
        cycleStats[key] = stats
        cycleStatUuids[key] = member.uuid()
    }

    private fun statsFor(member: PartyMember?): DungeonStats? {
        if (member == null) return null
        val memberName = member.name() ?: return null
        val generation = PartyListBackend.snapshot().generation()
        val attempt = lookupAttempts.get(generation, member)
        if (attempt != null) return if (attempt.state == PartyLookupAttempts.State.SUCCESS) attempt.stats else null
        val key = memberName.lowercase(Locale.ROOT)
        val stats = cycleStats[key] ?: return null
        val storedUuid = cycleStatUuids[key]
        if ((storedUuid != null || member.uuid() != null) && !Objects.equals(storedUuid, member.uuid())) return null
        return stats
    }

    private data class ScanState(val generation: Long, val remaining: Int, val suppressDisplay: Boolean) {
        fun generation() = generation
        fun remaining() = remaining
        fun suppressDisplay() = suppressDisplay

        fun withSuppressedDisplay(suppress: Boolean) =
            ScanState(generation, remaining, suppressDisplay || suppress)
    }

    private data class LookupWork(val member: PartyMember, val handle: PartyLookupAttempts.RequestHandle) {
        fun member() = member
        fun handle() = handle
    }

    private fun showUnavailable(name: String, error: String?) {
        val message = Component.literal("[PF] ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(apiUnavailableStatus(name, error) + " ").withStyle(ChatFormatting.GRAY))
            .append(pvAction(name))
            .append(Component.literal(" "))
            .append(retryAction(name))
        if (!ModConfig.hypixelModApi()) {
            message.append("\n").append(
                Component.literal("[PF] Island detection API is off").withStyle(ChatFormatting.YELLOW),
            )
        }
        queueChatCard(message)
    }

    private fun showStats(floor: DungeonFloor?, member: PartyMember, stats: DungeonStats) {
        val mc = Minecraft.getInstance()
        if (mc.gui == null) return
        val dungeonClass = member.dungeonClass() ?: stats.selectedClass()
        val view = StatsView.from(floor, member, stats, dungeonClass)
        queueChatCard(joinLines(formatCard(mc.font, view, chatTextWidth(mc), true, false, stats.cacheable())))
    }

    /** Requeues only stats already retained for the active roster; this action performs no lookups. */
    fun showLoadedPartyStats(): Int {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { showLoadedPartyStats() }
            return 1
        }
        val snapshot = PartyListBackend.snapshot()
        if (client.gui == null || !snapshot.inParty() || !PartyListBackend.hasFreshSnapshot()) {
            showRetryFeedback("No current party is available to show stats for.")
            diagnostics.event("party_stats_redisplay outcome=no_fresh_party")
            return 0
        }
        syncPartySession(snapshot.generation())
        val local = localName()
        var queued = 0
        var missing = 0
        var omitted = 0
        for (member in snapshot.members()) {
            val memberName = member.name() ?: continue
            if (local != null && memberName.equals(local, ignoreCase = true)) continue
            val stats = statsFor(member)
            if (stats == null) {
                missing++
                continue
            }
            if (queued >= MAX_REDISPLAY_STATS_CARDS) {
                omitted++
                continue
            }
            showStats(requestedFloor, member, stats)
            queued++
        }
        if (queued == 0) {
            showRetryFeedback("No cached party stats are loaded yet.")
            diagnostics.event("party_stats_redisplay outcome=no_cached_stats missing=$missing")
            return 0
        }
        val detail = buildString {
            append("Re-showing ").append(queued).append(" cached stats card(s)")
            if (missing > 0) append(" · ").append(missing).append(" member(s) not loaded")
            if (omitted > 0) append(" · ").append(omitted).append(" more omitted by output cap")
        }
        queueChatCard(Component.literal("[PF] $detail.").withStyle(ChatFormatting.GRAY))
        diagnostics.event("party_stats_redisplay outcome=queued cards=$queued missing=$missing omitted=$omitted")
        return 1
    }

    /** Performs a one-off profile lookup for /ga dstats, without requiring either display module to be enabled. */
    fun lookupStats(name: String) {
        val requestId = beginDStatsTrace(name)
        val startedAtNanos = System.nanoTime()
        DungeonStatsService.fetch(name) { result ->
            var outcome = "failed"
            var partyMemberFound = false
            try {
                if (!result.available()) {
                    outcome = "unavailable"
                    showLookupUnavailable(name, result.error)
                    return@fetch
                }
                val mc = Minecraft.getInstance()
                if (mc.gui == null) {
                    outcome = "no_gui"
                    return@fetch
                }
                val stats = result.stats!!
                val snapshot = PartyListBackend.snapshot()
                var member = if (snapshot.inParty()) findMember(snapshot, stats.name()) else null
                partyMemberFound = member != null
                val dungeonClass = member?.dungeonClass() ?: stats.selectedClass()
                if (member == null) member = PartyMember(stats.name(), stats.uuid(), dungeonClass)
                val view = StatsView.from(null, member, stats, dungeonClass)
                queueChatCard(joinLines(formatCard(mc.font, view, chatTextWidth(mc), true, true, stats.cacheable())))
                outcome = "card_queued"
            } finally {
                finishDStatsTrace(
                    requestId,
                    outcome,
                    result.available(),
                    result.stats?.cacheable() == true,
                    partyMemberFound,
                    (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L,
                )
            }
        }
    }

    private fun beginDStatsTrace(name: String): Long {
        if (dStatsTraceRequests == 0) {
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, DSTATS_DIAGNOSTIC_SOURCE, true)
        }
        dStatsTraceRequests++
        dStatsTraceSequence = if (dStatsTraceSequence == Long.MAX_VALUE) 1L else dStatsTraceSequence + 1L
        val requestId = dStatsTraceSequence
        recordDStatsDiagnostic(
            dStatsDiagnosticTick(),
            "REQUEST id=$requestId command=dstats nameLength=${name.length}",
        )
        return requestId
    }

    private fun finishDStatsTrace(
        requestId: Long,
        outcome: String,
        available: Boolean,
        cached: Boolean,
        partyMemberFound: Boolean,
        elapsedMillis: Long,
    ) {
        recordDStatsDiagnostic(
            dStatsDiagnosticTick(),
            "RESULT id=$requestId outcome=$outcome available=$available cached=$cached " +
                "partyMemberFound=$partyMemberFound elapsedMs=$elapsedMillis",
        )
        dStatsTraceRequests = (dStatsTraceRequests - 1).coerceAtLeast(0)
        if (dStatsTraceRequests == 0) {
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, DSTATS_DIAGNOSTIC_SOURCE, false)
        }
    }

    private fun recordDStatsDiagnostic(window: Long, payload: String) {
        if (dStatsDiagnosticWindow != window) {
            if (dStatsDiagnosticWindow != Long.MIN_VALUE && suppressedDStatsDiagnostics > 0) {
                GeilerAddonsLog.write(
                    Category.F7,
                    DSTATS_DIAGNOSTIC_SOURCE,
                    dStatsDiagnosticWindow,
                    "EVENTS_SUPPRESSED count=$suppressedDStatsDiagnostics " +
                        "limit=$MAX_DSTATS_DIAGNOSTIC_EVENTS_PER_WINDOW",
                )
            }
            dStatsDiagnosticWindow = window
            dStatsDiagnosticEvents = 0
            suppressedDStatsDiagnostics = 0
        }
        if (dStatsDiagnosticEvents >= MAX_DSTATS_DIAGNOSTIC_EVENTS_PER_WINDOW) {
            if (suppressedDStatsDiagnostics < Int.MAX_VALUE) suppressedDStatsDiagnostics++
            return
        }
        GeilerAddonsLog.write(
            Category.F7,
            DSTATS_DIAGNOSTIC_SOURCE,
            window,
            payload.take(MAX_AUTOMATIC_DIAGNOSTIC_LINE_CHARS),
        )
        dStatsDiagnosticEvents++
    }

    private fun dStatsDiagnosticTick(): Long = System.nanoTime() / 50_000_000L

    private fun showLookupUnavailable(name: String, error: String?) {
        val message = Component.literal("[DStats] ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(apiUnavailableStatus(name, error) + " ").withStyle(ChatFormatting.GRAY))
            .append(pvAction(name))
        if (!ModConfig.hypixelModApi()) {
            message.append("\n").append(
                Component.literal("[DStats] Island detection API is off").withStyle(ChatFormatting.YELLOW),
            )
        }
        queueChatCard(message)
    }

    override fun previewLines(font: Font, width: Int): List<Component> =
        formatCard(font, StatsView.preview(), maxOf(40, width), false)

    private fun formatCard(font: Font?, view: StatsView, width: Int, includeActions: Boolean): List<Component> =
        formatCard(font, view, width, includeActions, false)

    private fun formatCard(
        font: Font?,
        view: StatsView,
        width: Int,
        includeActions: Boolean,
        manualLookup: Boolean,
    ): List<Component> = formatCard(font, view, width, includeActions, manualLookup, false)

    private fun formatCard(
        font: Font?,
        view: StatsView,
        width: Int,
        includeActions: Boolean,
        manualLookup: Boolean,
        completeStats: Boolean,
    ): List<Component> {
        val actions = if (includeActions) liveActions(view, completeStats) else emptyList()
        return CardFormatter.format(font, view, displayOptions(), width, actions, manualLookup, !ModConfig.hypixelModApi())
    }

    private fun displayOptions() = DisplayOptions(
        compact.value(),
        showCata.value(),
        showOverflowCata.value(),
        showClass.value(),
        showClassAverage.value(),
        showMagicalPower.value(),
        showSecretAverage.value(),
        showPersonalBest.value(),
        showTerminator.value(),
        showHyperion.value(),
        showGoldenDragon.value(),
        showBank.value(),
    )

    private fun liveActions(view: StatsView, completeStats: Boolean): List<Component> {
        val actions = ArrayList<Component>()
        val party = PartyListBackend.snapshot()
        if (completeStats && party.isLeader(localName()) && findMember(party, view.name()) != null) {
            actions.add(kickAction(view.name()))
        }
        if (!view.gearKnown() || !view.bankKnown()) actions.add(pvAction(view.name()))
        return actions
    }

    class DisplayOptions(
        private val compactValue: Boolean,
        private val showCataValue: Boolean,
        private val showOverflowCataValue: Boolean,
        private val showClassValue: Boolean,
        private val showClassAverageValue: Boolean,
        private val showMagicalPowerValue: Boolean,
        private val showSecretAverageValue: Boolean,
        private val showPersonalBestValue: Boolean,
        private val showTerminatorValue: Boolean,
        private val showHyperionValue: Boolean,
        private val showGoldenDragonValue: Boolean,
        private val showBankValue: Boolean,
    ) {
        fun compact() = compactValue
        fun showCata() = showCataValue
        fun showOverflowCata() = showOverflowCataValue
        fun showClass() = showClassValue
        fun showClassAverage() = showClassAverageValue
        fun showMagicalPower() = showMagicalPowerValue
        fun showSecretAverage() = showSecretAverageValue
        fun showPersonalBest() = showPersonalBestValue
        fun showTerminator() = showTerminatorValue
        fun showHyperion() = showHyperionValue
        fun showGoldenDragon() = showGoldenDragonValue
        fun showBank() = showBankValue
    }

    /** Pure card construction shared by chat output and the side-effect-free Click GUI preview. */
    class CardFormatter private constructor() {
        companion object {
            @JvmStatic
            fun format(
                font: Font?,
                view: StatsView,
                options: DisplayOptions,
                width: Int,
                actions: List<Component>,
            ): List<Component> = format(font, view, options, width, actions, false, false)

            @JvmStatic
            fun format(
                font: Font?,
                view: StatsView,
                options: DisplayOptions,
                width: Int,
                actions: List<Component>,
                manualLookup: Boolean,
                islandApiOff: Boolean,
            ): List<Component> {
                val header = Component.literal("✦ ").withStyle(ChatFormatting.AQUA)
                    .append(
                        Component.literal(view.name()).withStyle { style ->
                            style.withColor(ChatFormatting.WHITE).withBold(true)
                        },
                    )
                if (!manualLookup) header.append(Component.literal(" joined").withStyle(ChatFormatting.GRAY))
                if (view.floor() != null) {
                    header.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(
                            PartyFinderStatsModule.hover(
                                Component.literal("[" + view.floor()!!.displayName() + "]")
                                    .withStyle(if (view.floor()!!.master()) ChatFormatting.RED else ChatFormatting.GOLD),
                                (if (view.floor()!!.master()) "Master mode" else "Normal mode") + " dungeon floor",
                            ),
                        )
                }

                val overviewParts = ArrayList<Component>()
                if (options.showCata()) {
                    val cata = Component.literal("Cata ").withStyle(ChatFormatting.GRAY)
                        .append(
                            Component.literal(
                                if (view.catacombsKnown()) DungeonStatsService.formatCatacombsLevel(
                                    view.catacombsExperience(),
                                    options.showOverflowCata(),
                                ) else "?",
                            ).withStyle(ChatFormatting.GOLD),
                        )
                    overviewParts.add(
                        PartyFinderStatsModule.hover(
                            cata,
                            ComponentFormatting.catacombsTooltip(view, options.showOverflowCata()),
                        ),
                    )
                }
                if (options.showClass()) {
                    val selectedClass = view.selectedClass()
                    val classValue = if (selectedClass == null) "Class ?"
                        else selectedClass.displayName() + " " + if (view.selectedClassKnown()) view.selectedClassLevel() else "?"
                    val classPart = PartyFinderStatsModule.hover(
                        Component.literal(classValue).withStyle(PartyFinderStatsModule.classColor(selectedClass)),
                        Component.literal(
                            (if (selectedClass == null) "Selected dungeon class unavailable in profile\n" else "Dungeon class levels\n")
                                + view.allClassLevels(),
                        ),
                    )
                    overviewParts.add(classPart)
                }
                if (options.showClassAverage()) {
                    val classAverage = Component.literal("CA ").withStyle(ChatFormatting.GRAY)
                        .append(
                        Component.literal(if (view.classAverageKnown()) PartyFinderStatsModule.format(view.classAverage()) else "?")
                                .withStyle(ChatFormatting.AQUA),
                        )
                    overviewParts.add(
                        PartyFinderStatsModule.hover(
                            classAverage,
                            if (view.classAverageKnown()) "Class average: " + PartyFinderStatsModule.format(view.classAverage())
                                + "\n" + view.allClassLevels()
                            else "Class level data unavailable in profile",
                        ),
                    )
                }
                if (options.showMagicalPower()) {
                    val magicalPower = Component.literal("MP ").withStyle(ChatFormatting.GRAY)
                        .append(
                            Component.literal(
                                if (view.magicalPowerKnown()) String.format(Locale.ROOT, "%,d", view.magicalPower()) else "?",
                            ).withStyle(ChatFormatting.LIGHT_PURPLE),
                        )
                    overviewParts.add(
                        PartyFinderStatsModule.hover(
                            magicalPower,
                            if (view.magicalPowerKnown()) "Magical Power: "
                                + String.format(Locale.ROOT, "%,d", view.magicalPower()) + "\nProfile value"
                            else "Magical Power data unavailable in profile",
                        ),
                    )
                }
                if (options.showSecretAverage()) {
                    val secretPart = Component.literal("SA ").withStyle(ChatFormatting.GRAY)
                        .append(
                            Component.literal(
                                if (view.secretsKnown() && view.runsKnown()) PartyFinderStatsModule.format(view.secretAverage()) else "?",
                            ).withStyle(ChatFormatting.AQUA),
                        )
                    val secrets = if (view.secretsKnown()) String.format(Locale.ROOT, "%,d", view.totalSecrets())
                        else "unavailable in profile"
                    val runs = if (view.runsKnown()) String.format(Locale.ROOT, "%,d", view.totalRuns())
                        else "unavailable in profile"
                    overviewParts.add(PartyFinderStatsModule.hover(secretPart, "Total secrets: $secrets\nRuns: $runs"))
                }
                if (options.showPersonalBest()) {
                    if (!manualLookup) {
                        val personalBest = view.personalBest()
                        val pbPart = Component.literal("PB ").withStyle(ChatFormatting.GRAY)
                            .append(
                                Component.literal(personalBest).withStyle(
                                    if (personalBest == "-") ChatFormatting.YELLOW else ChatFormatting.GREEN,
                                ),
                            )
                        val pbHover = if (!view.personalBestsKnown()) "Personal best data unavailable in profile"
                            else (if (view.floor() == null) "Queued-floor personal best: "
                                else view.floor()!!.displayName() + " personal best: ") + personalBest +
                                (if (personalBest == "-") {
                                    if (view.floor() == null) "\nNo queued dungeon floor was captured"
                                    else "\nNo personal best recorded for this floor"
                                } else "")
                        overviewParts.add(PartyFinderStatsModule.hover(pbPart, pbHover))
                    }
                    overviewParts.add(
                        PartyFinderStatsModule.hover(
                            Component.literal("Normal PBs").withStyle(ChatFormatting.GREEN),
                            if (view.personalBestsKnown()) "Normal mode personal bests\n" + view.normalPbs()
                            else "Normal mode personal best data unavailable in profile",
                        ),
                    )
                    overviewParts.add(
                        PartyFinderStatsModule.hover(
                            Component.literal("Master PBs").withStyle(ChatFormatting.RED),
                            if (view.personalBestsKnown()) "Master mode personal bests\n" + view.masterPbs()
                            else "Master mode personal best data unavailable in profile",
                        ),
                    )
                }

                val gearParts = ArrayList<Component>()
                if (options.showTerminator()) {
                    gearParts.add(
                        ComponentFormatting.gearPart(
                            "Term ",
                            view.terminatorKnown(),
                            view.terminator(),
                            ComponentFormatting.itemTooltip("Terminator", DungeonStats.Gear.TERMINATOR, view.terminatorKnown(), view.terminator(), view.itemDetails()),
                        ),
                    )
                }
                if (options.showHyperion()) {
                    gearParts.add(
                        ComponentFormatting.gearPart(
                            "Hype ",
                            view.hyperionKnown(),
                            view.hyperion(),
                            ComponentFormatting.itemTooltip("Hyperion", DungeonStats.Gear.HYPERION, view.hyperionKnown(), view.hyperion(), view.itemDetails()),
                        ),
                    )
                }
                if (options.showGoldenDragon()) {
                    gearParts.add(ComponentFormatting.gearPart("GDrag ", view.goldenDragonKnown(), view.goldenDragon(), ComponentFormatting.goldenDragonTooltip(view)))
                }
                if (options.showBank()) {
                    val bankPart = if (view.bankKnown()) Component.literal(PartyFinderStatsModule.formatBank(view.bank())).withStyle(ChatFormatting.GOLD)
                        else Component.literal("?").withStyle(ChatFormatting.YELLOW)
                    val bankLine = Component.literal("Bank ").withStyle(ChatFormatting.GRAY).append(bankPart)
                    gearParts.add(
                        PartyFinderStatsModule.hover(
                            bankLine,
                            if (view.bankKnown()) "Bank balance: " + PartyFinderStatsModule.formatBank(view.bank()) else "Bank API data unavailable",
                        ),
                    )
                }
                gearParts.addAll(actions)

                val segments = ArrayList<Component>()
                segments.add(header)
                if (overviewParts.isNotEmpty()) segments.add(PartyFinderStatsModule.joinParts(overviewParts))
                if (gearParts.isNotEmpty()) segments.add(PartyFinderStatsModule.joinParts(gearParts))
                if (islandApiOff) {
                    segments.add(Component.literal("Island detection API is off").withStyle(ChatFormatting.YELLOW))
                }
                if (options.compact()) {
                    val compactLine = Component.literal(if (manualLookup) "[✦ DStats] " else "[✦ PF] ")
                        .withStyle { style -> style.withColor(ChatFormatting.AQUA).withBold(true) }
                    return listOf(compactLine.append(PartyFinderStatsModule.joinParts(segments)))
                }

                val lines = ArrayList<Component>()
                lines.add(PartyFinderStatsModule.cardTop(font, width, if (manualLookup) " ✦ DUNGEON STATS ✦ " else " ✦ PARTY FINDER ✦ "))
                for (segment in segments) {
                    lines.add(Component.literal("┃ ").withStyle(ChatFormatting.DARK_AQUA).append(segment))
                }
                lines.add(PartyFinderStatsModule.cardBottom(font, width))
                return lines
            }
        }
    }

    class StatsView(
        private val nameValue: String,
        private val floorValue: DungeonFloor?,
        private val selectedClassValue: DungeonClass?,
        private val selectedClassLevelValue: Int,
        private val catacombsLevelValue: Int,
        private val classAverageValue: Double,
        private val totalSecretsValue: Long,
        private val totalRunsValue: Long,
        private val secretAverageValue: Double,
        private val magicalPowerValue: Int,
        private val bankValue: Long,
        private val bankKnownValue: Boolean,
        private val gearKnownValue: Boolean,
        private val terminatorKnownValue: Boolean,
        private val hyperionKnownValue: Boolean,
        private val goldenDragonKnownValue: Boolean,
        private val terminatorValue: Boolean,
        private val hyperionValue: Boolean,
        private val goldenDragonValue: Boolean,
        private val personalBestValue: String,
        private val allClassLevelsValue: String,
        private val normalPbsValue: String,
        private val masterPbsValue: String,
        private val catacombsKnownValue: Boolean,
        private val catacombsExperienceValue: Long,
        private val selectedClassKnownValue: Boolean,
        private val classAverageKnownValue: Boolean,
        private val magicalPowerKnownValue: Boolean,
        private val secretsKnownValue: Boolean,
        private val runsKnownValue: Boolean,
        private val personalBestsKnownValue: Boolean,
        itemDetails: List<DungeonStats.ItemDetails>?,
        goldenDragonPets: List<DungeonStats.GoldenDragonPet>?,
    ) {
        private val itemDetailsValue: List<DungeonStats.ItemDetails> =
            if (itemDetails == null) emptyList() else java.util.List.copyOf(itemDetails)
        private val goldenDragonPetsValue: List<DungeonStats.GoldenDragonPet> =
            if (goldenDragonPets == null) emptyList() else java.util.List.copyOf(goldenDragonPets)

        fun name() = nameValue
        fun floor() = floorValue
        fun selectedClass() = selectedClassValue
        fun selectedClassLevel() = selectedClassLevelValue
        fun catacombsLevel() = catacombsLevelValue
        fun classAverage() = classAverageValue
        fun totalSecrets() = totalSecretsValue
        fun totalRuns() = totalRunsValue
        fun secretAverage() = secretAverageValue
        fun magicalPower() = magicalPowerValue
        fun bank() = bankValue
        fun bankKnown() = bankKnownValue
        fun gearKnown() = gearKnownValue
        fun terminatorKnown() = terminatorKnownValue
        fun hyperionKnown() = hyperionKnownValue
        fun goldenDragonKnown() = goldenDragonKnownValue
        fun terminator() = terminatorValue
        fun hyperion() = hyperionValue
        fun goldenDragon() = goldenDragonValue
        fun personalBest() = personalBestValue
        fun allClassLevels() = allClassLevelsValue
        fun normalPbs() = normalPbsValue
        fun masterPbs() = masterPbsValue
        fun catacombsKnown() = catacombsKnownValue
        fun catacombsExperience() = catacombsExperienceValue
        fun selectedClassKnown() = selectedClassKnownValue
        fun classAverageKnown() = classAverageKnownValue
        fun magicalPowerKnown() = magicalPowerKnownValue
        fun secretsKnown() = secretsKnownValue
        fun runsKnown() = runsKnownValue
        fun personalBestsKnown() = personalBestsKnownValue
        fun itemDetails() = itemDetailsValue
        fun goldenDragonPets() = goldenDragonPetsValue

        fun withCatacombsExperience(experience: Long): StatsView {
            val progress = DungeonStatsService.catacombsProgress(experience)
            return StatsView(
                nameValue,
                floorValue,
                selectedClassValue,
                selectedClassLevelValue,
                progress.level,
                classAverageValue,
                totalSecretsValue,
                totalRunsValue,
                secretAverageValue,
                magicalPowerValue,
                bankValue,
                bankKnownValue,
                gearKnownValue,
                terminatorKnownValue,
                hyperionKnownValue,
                goldenDragonKnownValue,
                terminatorValue,
                hyperionValue,
                goldenDragonValue,
                personalBestValue,
                allClassLevelsValue,
                normalPbsValue,
                masterPbsValue,
                catacombsKnownValue,
                progress.totalExperience,
                selectedClassKnownValue,
                classAverageKnownValue,
                magicalPowerKnownValue,
                secretsKnownValue,
                runsKnownValue,
                personalBestsKnownValue,
                itemDetailsValue,
                goldenDragonPetsValue,
            )
        }

        companion object {
            @JvmStatic
            fun from(
                floor: DungeonFloor?,
                member: PartyMember,
                stats: DungeonStats,
                selectedClass: DungeonClass?,
            ): StatsView {
                val personalBest = if (floor == null) "-" else DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor))
                return StatsView(
                    stats.name(),
                    floor,
                    selectedClass,
                    if (selectedClass == null) 0 else stats.classLevel(selectedClass),
                    stats.catacombsLevel(),
                    stats.classAverage(),
                    stats.totalSecrets(),
                    stats.totalRuns(),
                    stats.secretAverage(),
                    stats.magicalPower(),
                    stats.bank(),
                    stats.bankKnown(),
                    stats.gearKnown(),
                    stats.hasGearData(DungeonStats.Gear.TERMINATOR),
                    stats.hasGearData(DungeonStats.Gear.HYPERION),
                    stats.hasGearData(DungeonStats.Gear.GOLDEN_DRAGON),
                    stats.has(DungeonStats.Gear.TERMINATOR),
                    stats.has(DungeonStats.Gear.HYPERION),
                    stats.has(DungeonStats.Gear.GOLDEN_DRAGON),
                    personalBest,
                    stats.allClassLevels(),
                    PartyFinderStatsModule.allPbs(stats, false),
                    PartyFinderStatsModule.allPbs(stats, true),
                    stats.catacombsExperienceKnown(),
                    stats.catacombsExperience(),
                    selectedClass != null && stats.hasClassLevel(selectedClass),
                    stats.has(DungeonStats.DataField.CLASS_AVERAGE),
                    stats.has(DungeonStats.DataField.MAGICAL_POWER),
                    stats.has(DungeonStats.DataField.SECRETS),
                    stats.has(DungeonStats.DataField.RUNS),
                    stats.has(DungeonStats.DataField.PERSONAL_BESTS),
                    stats.allItemDetails(),
                    stats.goldenDragonPets(),
                )
            }

            @JvmStatic
            fun preview(): StatsView = StatsView(
                "ExamplePlayer",
                DungeonFloor.F7,
                DungeonClass.MAGE,
                45,
                42,
                46.25,
                12_480,
                1_120,
                11.14,
                720,
                125_000_000L,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                "6:42",
                "Tank 38\nHealer 29\nMage 45\nBerserk 41\nArcher 40",
                "F1: 2:10\nF2: 2:32\nF3: 3:01\nF4: 3:44\nF5: 4:18\nF6: 5:31\nF7: 6:42",
                "M1: 2:30\nM2: 3:02\nM3: 4:01\nM4: 5:10\nM5: 6:22\nM6: 8:14\nM7: 10:42",
                true,
                100_000_000L,
                true,
                true,
                true,
                true,
                true,
                true,
                listOf(
                    DungeonStats.ItemDetails(
                        DungeonStats.Gear.TERMINATOR,
                        "TERMINATOR",
                        "§6Terminator",
                        "Inventory",
                        listOf("§7Shortbow: Instantly shoots!"),
                    ),
                    DungeonStats.ItemDetails(
                        DungeonStats.Gear.HYPERION,
                        "HYPERION",
                        "§dHyperion",
                        "Ender Chest",
                        listOf("§7Wither Impact"),
                    ),
                ),
                listOf(
                    DungeonStats.GoldenDragonPet(
                        "LEGENDARY",
                        "200",
                        "1,000,000,000",
                        "PET_ITEM_TIER_BOOST",
                        "",
                        true,
                    ),
                ),
            )
        }
    }

    private fun queueChatCard(card: Component?) {
        if (card == null) return
        if (pendingChatCards.size >= MAX_QUEUED_CHAT_CARDS) {
            pendingChatCards.removeFirst()
            GeilerAddons.LOGGER.debug("[Party Finder Stats] Dropped oldest queued chat card to keep output bounded")
        }
        pendingChatCards.addLast(card)
    }

    private fun tickChatCards() {
        if (chatCardCooldown > 0) chatCardCooldown--
        if (chatCardCooldown > 0 || pendingChatCards.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.gui == null) return
        mc.gui.hud.getChat().addClientSystemMessage(pendingChatCards.removeFirst())
        chatCardCooldown = CHAT_CARD_GAP_TICKS
    }

    private fun partyStatsCompletionAction(): Component =
        Component.literal("[PF] ").withStyle { style ->
            style.withColor(ChatFormatting.AQUA).withBold(true)
        }
            .append(Component.literal("Finished loading this party  ").withStyle(ChatFormatting.GRAY))
            .append(
                Component.literal("[Show Stats]").withStyle { style ->
                    style.withColor(ChatFormatting.GREEN)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(ClickEvent.RunCommand(showStatsCommand()))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Re-show loaded party stats")))
                },
            )
            .append(Component.literal("  "))
            .append(
                Component.literal("[Leave Party]").withStyle { style ->
                    style.withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(ClickEvent.RunCommand("/party leave"))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Run /party leave")))
                },
            )

    override fun onEnable() {
        diagnostics.enable("automaticDiagnostics=true eventLimit=$MAX_DIAGNOSTIC_EVENTS_PER_TICK statusSampleTicks=20")
    }

    override fun onDisable() {
        diagnostics.disable()
        displayLifecycleEpoch++
        pendingChatCards.clear()
        chatCardCooldown = 0
        fetchRequested = false
        cycleStarted = false
        ticksSinceRequest = 0
        cycleTargetName = null
        pendingResults = 0
        initialDispatching = false
        initialScanFinished = false
        localJoinedThroughFinder = false
        reminderShownForPartySession = false
        suppressInitialScanCards = false
        suppressedInitialScanCards = 0
        activeScanToken = -1L
        scanPending.clear()
        cycleStats.clear()
        cycleStatUuids.clear()
        deferredJoins.clear()
    }

    private fun debug(format: String, vararg arguments: Any?) {
        val message = String.format(Locale.ROOT, format, *arguments)
        GeilerAddons.LOGGER.debug("[Party Finder Stats] {}", message)
        if (!debug.value()) return
        val mc = Minecraft.getInstance()
        if (mc.gui != null) {
            mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[PF Debug] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(message).withStyle(ChatFormatting.GRAY)),
            )
        }
    }

    private object ComponentFormatting {
        fun gearPart(label: String, known: Boolean, present: Boolean, tooltip: Component): Component {
            val indicator = Component.literal(if (!known) "?" else if (present) "✓" else "X")
                .withStyle(if (known && present) ChatFormatting.GREEN else if (known) ChatFormatting.RED else ChatFormatting.YELLOW)
            return PartyFinderStatsModule.hover(
                Component.literal(label).withStyle(ChatFormatting.GRAY).copy().append(indicator),
                tooltip,
            )
        }

        fun itemTooltip(
            label: String,
            gear: DungeonStats.Gear,
            known: Boolean,
            present: Boolean,
            allDetails: List<DungeonStats.ItemDetails>,
        ): Component {
            val details = allDetails.filter { it.gear() == gear }
            if (!known && details.isEmpty()) return Component.literal("Inventory API data unavailable")
            if (!present && details.isEmpty()) {
                return Component.literal("No $label found in the available profile inventories")
            }
            if (details.isEmpty()) {
                return Component.literal("$label detected, but item tooltip details were not included in the profile response")
            }

            val tooltip = Component.empty()
            for (index in details.indices) {
                val item = details[index]
                if (index > 0) tooltip.append("\n\n")
                tooltip.append(
                    Component.literal(label + if (details.size > 1) " " + (index + 1) else "")
                        .withStyle(ChatFormatting.GOLD),
                )
                tooltip.append("\n").append(profileTooltipText(item.displayName()))
                if (!javaStringIsBlank(item.identifier())) {
                    tooltip.append("\n").append(
                        Component.literal("Item ID: " + item.identifier()).withStyle(ChatFormatting.DARK_GRAY),
                    )
                }
                if (!javaStringIsBlank(item.source())) {
                    tooltip.append("\n").append(
                        Component.literal("From: " + item.source()).withStyle(ChatFormatting.DARK_GRAY),
                    )
                }
                for (line in item.lore()) tooltip.append("\n").append(profileTooltipText(line))
            }
            return tooltip
        }

        fun goldenDragonTooltip(view: StatsView): Component {
            val pets = view.goldenDragonPets()
            val items = view.itemDetails().filter { it.gear() == DungeonStats.Gear.GOLDEN_DRAGON }
            if (!view.goldenDragonKnown() && pets.isEmpty() && items.isEmpty()) {
                return Component.literal("Pet API data unavailable")
            }
            if (!view.goldenDragon() && pets.isEmpty() && items.isEmpty()) {
                return Component.literal("No Golden Dragon found in the available profile data")
            }
            val tooltip = Component.empty()
            for (index in pets.indices) {
                val pet = pets[index]
                if (tooltip.string.length > 0) tooltip.append("\n\n")
                val title = "Golden Dragon" +
                    (if (pets.size > 1) " " + (index + 1) else "") +
                    (if (javaStringIsBlank(pet.rarity())) "" else " · " + pet.rarity())
                tooltip.append(Component.literal(title).withStyle(ChatFormatting.GOLD))
                appendTooltipField(tooltip, "Level", pet.level())
                appendTooltipField(tooltip, "Experience", pet.experience())
                appendTooltipField(tooltip, "Held item", pet.heldItem())
                appendTooltipField(tooltip, "Skin", pet.skin())
                val active = pet.active()
                if (active != null) appendTooltipField(tooltip, "Active", if (active) "Yes" else "No")
            }
            if (items.isNotEmpty()) {
                if (tooltip.getString().length > 0) tooltip.append("\n\n")
                tooltip.append(itemTooltip("Golden Dragon item", DungeonStats.Gear.GOLDEN_DRAGON, true, true, items))
            }
            if (javaStringIsBlank(tooltip.getString())) {
                return Component.literal("Golden Dragon found; detailed pet data was not included in the profile response")
            }
            return tooltip
        }

        fun appendTooltipField(tooltip: MutableComponent, label: String, value: String?) {
            if (javaStringIsBlank(value)) return
            tooltip.append("\n").append(Component.literal("$label: ").withStyle(ChatFormatting.GRAY))
                .append(profileTooltipText(value))
        }

        fun catacombsTooltip(view: StatsView, showOverflow: Boolean): Component {
            if (!view.catacombsKnown()) return Component.literal("Catacombs XP data unavailable in profile")
            val progress = DungeonStatsService.catacombsProgress(view.catacombsExperience())
            val tooltip = Component.literal(
                "Catacombs Level " + DungeonStatsService.formatCatacombsLevel(view.catacombsExperience(), showOverflow),
            )
                .withStyle(ChatFormatting.GOLD)
                .append("\nTotal Catacombs XP: ")
                .append(
                    Component.literal(String.format(Locale.ROOT, "%,d", progress.totalExperience))
                        .withStyle(ChatFormatting.WHITE),
                )
            if (progress.capped) {
                tooltip.append("\nOverflow XP: ")
                    .append(
                        Component.literal(String.format(Locale.ROOT, "%,d", progress.overflowExperience))
                            .withStyle(ChatFormatting.AQUA),
                    )
            } else {
                val remaining = maxOf(0L, progress.experienceForLevel - progress.experienceIntoLevel)
                val percentage = if (progress.experienceForLevel == 0L) 0.0
                    else 100.0 * progress.experienceIntoLevel / progress.experienceForLevel
                tooltip.append("\nXP in current level: ")
                    .append(
                        Component.literal(
                            String.format(
                                Locale.ROOT,
                                "%,d / %,d",
                                progress.experienceIntoLevel,
                                progress.experienceForLevel,
                            ),
                        ).withStyle(ChatFormatting.WHITE),
                    )
                tooltip.append("\nXP to next level: ")
                    .append(Component.literal(String.format(Locale.ROOT, "%,d", remaining)).withStyle(ChatFormatting.AQUA))
                tooltip.append("\nProgress: ")
                    .append(Component.literal(String.format(Locale.ROOT, "%.1f%%", percentage)).withStyle(ChatFormatting.GREEN))
            }
            return tooltip
        }

        fun profileTooltipText(text: String?): Component {
            if (text.isNullOrEmpty()) return Component.empty()
            val trimmed = javaStringTrim(text)
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    val component = ComponentSerialization.CODEC
                        .parse(JsonOps.INSTANCE, JsonParser.parseString(trimmed)).result().orElse(null)
                    if (component != null) return component
                } catch (_: RuntimeException) {
                    // Fall back to the profile string if an optional custom component is malformed.
                }
            }
            val output = Component.empty()
            val segment = StringBuilder()
            var style = Style.EMPTY
            var index = 0
            while (index < text.length) {
                val character = text[index]
                if (character != '§' || index + 1 >= text.length) {
                    segment.append(character)
                    index++
                    continue
                }
                val formatting = ChatFormatting.getByCode(text[index + 1])
                if (formatting == null) {
                    segment.append(character)
                    index++
                    continue
                }
                if (segment.length > 0) {
                    output.append(Component.literal(segment.toString()).withStyle(style))
                    segment.setLength(0)
                }
                style = if (formatting == ChatFormatting.RESET) Style.EMPTY else style.applyLegacyFormat(formatting)
                index += 2
            }
            if (segment.length > 0) output.append(Component.literal(segment.toString()).withStyle(style))
            return if (output.getString().isEmpty() && text.isEmpty()) Component.empty() else output
        }
    }

}
