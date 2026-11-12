package geiler.addons.client.module.impl

import geiler.addons.GeilerAddons
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonStats
import geiler.addons.client.dungeon.DungeonStatsService
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.party.PartySnapshot
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.util.ArrayDeque
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

/** Per-floor Party Finder requirements and optional leader-only enforcement. */
class AutoKickModule private constructor(settings: Settings = Settings()) : Module(
    "Auto Kick",
    "Checks Party Finder requirements.",
    Category.F7,
    *settings.allSettings().toTypedArray(),
) {
    companion object {
        @JvmField
        val INSTANCE = AutoKickModule()

        private const val MIN_DELAY_MILLIS = 1_000L
        private const val MAX_DELAY_MILLIS = 2_000L
        private const val CHAT_NOTICE_GAP_TICKS = 5
        private const val MAX_DIAGNOSTIC_DETAILS_PER_TICK = 16
        private const val DIAGNOSTIC_STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512

        private fun actionKey(generation: Long, floor: DungeonFloor, member: PartyMember): String {
            val uuid = member.uuid()?.toString() ?: "?"
            val dungeonClass = member.dungeonClass()?.name ?: "?"
            return "$generation:${floor.name}:${member.name()!!.lowercase(Locale.ROOT)}:$uuid:$dungeonClass"
        }

        private fun findMember(snapshot: PartySnapshot, name: String): PartyMember? =
            snapshot.members().firstOrNull { it.name().equals(name, ignoreCase = true) }

        private fun localName(): String? = Minecraft.getInstance().player?.getGameProfile()?.name()

        private fun randomDelay(): Long =
            ThreadLocalRandom.current().nextLong(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS + 1)

        private fun limitChat(message: String): String =
            if (message.length <= 220) message else message.substring(0, 217) + "..."
    }

    private val policies = settings.policies
    private val debugSetting = settings.debug
    private val profileRetryWindow = settings.profileRetryWindow
    private val profileRetryInterval = settings.profileRetryInterval

    private val handled = HashSet<String>()
    private val profileRetries = HashMap<String, ProfileRetry>()
    private val manualFallbacks = HashSet<String>()
    private val pendingKicks = ArrayList<PendingKick>()
    private val pendingNotices = ArrayDeque<Component>()

    private var markerGeneration = Long.MIN_VALUE
    private var noticeCooldown = 0
    private var diagnosticTick = 0L
    private var diagnosticDetailsThisTick = 0
    private var suppressedDiagnosticDetails = 0
    private var lastStatusTick = 0L

    init {
        group(
            SettingGroup.debug("Diagnostics", settings.debug),
            SettingGroup("Safety", settings.profileRetryWindow, settings.profileRetryInterval),
            SettingGroup("Normal").containing(*settings.normal.map { it.group() }.toTypedArray()),
            SettingGroup("Master").containing(*settings.master.map { it.group() }.toTypedArray()),
        )
    }

    fun wantsData(): Boolean = isEnabled() && policies.values.any { it.autoKick.value() }

    fun onStats(floor: DungeonFloor?, stats: DungeonStats?) {
        onStats(floor, PartyListBackend.snapshot().generation(), stats)
    }

    fun onStats(floor: DungeonFloor?, expectedGeneration: Long, stats: DungeonStats?) {
        if (stats == null) return
        if (!isEnabled()) {
            debug("Ignoring stats for %s because Auto Kick is disabled", stats.name())
            return
        }
        if (floor == null) {
            debug("Cannot evaluate %s: the queued dungeon floor is unknown", stats.name())
            trace("SKIP reason=unknown-floor")
            return
        }

        val snapshot = PartyListBackend.snapshot()
        syncGeneration(snapshot.generation())
        if (expectedGeneration >= 0 && snapshot.generation() != expectedGeneration) {
            debug("Ignoring stats for %s because party generation changed while it was loading", stats.name())
            trace("SKIP floor=${floor.name};reason=stale-party-generation")
            return
        }
        val member = findMember(snapshot, stats.name())
        if (member == null) {
            debug("Cannot evaluate %s: they are no longer in the tracked party", stats.name())
            trace("SKIP floor=${floor.name};reason=member-departed")
            return
        }
        if (!snapshot.isLeader(localName())) {
            debug("Cannot evaluate %s: local player is not party leader (leader=%s)", stats.name(), snapshot.leaderName())
            trace("SKIP floor=${floor.name};reason=local-player-not-leader")
            return
        }
        val policy = policies[floor]
        if (policy == null || !policy.autoKick.value()) {
            debug("Skipping %s: the %s policy is disabled", member.name(), floor.displayName())
            trace("SKIP floor=${floor.name};reason=policy-disabled")
            return
        }

        val key = actionKey(snapshot.generation(), floor, member)
        if (manualFallbacks.contains(key)) {
            debug("Skipping %s because profile verification failed; an explicit stats retry is required", member.name())
            trace("SKIP floor=${floor.name};reason=manual-retry-required")
            return
        }
        if (!handled.add(key)) {
            debug("Already evaluated %s for %s in party generation %d", member.name(), floor.displayName(), snapshot.generation())
            trace("SKIP floor=${floor.name};reason=already-evaluated")
            return
        }

        debug(
            "Using %s policy for %s: mode=%s; checks=%s",
            floor.displayName(),
            member.name(),
            if (policy.askBeforeKick.value()) "ASK BEFORE KICK" else "AUTOMATIC",
            policy.describeChecks(),
        )

        // Incomplete profile values remain unknown; they can never authorize enforcement.
        handled.remove(key)
        val evaluation = policy.evaluate(member, snapshot, stats, floor)
        debug(
            "Evaluation for %s on %s: %s",
            member.name(),
            floor.displayName(),
            when {
                !stats.cacheable() || evaluation.hasUnknown() ->
                    "waiting for " + (evaluation.unknowns.ifEmpty { listOf("complete profile data") }.joinToString())
                evaluation.hasAction() -> evaluation.failures.joinToString()
                else -> "passed"
            },
        )

        val profileIncomplete = !stats.cacheable()
        val hasUnknownData = profileIncomplete || evaluation.hasUnknown()
        trace(
            "EVALUATION floor=${floor.name};generation=${snapshot.generation()};mode=" +
                (if (policy.askBeforeKick.value()) "manual" else "automatic") +
                ";cacheable=${stats.cacheable()};unknowns=${evaluation.unknowns.joinToString(",")}" +
                ";failures=${evaluation.failures.joinToString(",")}",
        )

        if (!AutoKickRules.canApplyAction(hasUnknownData, evaluation.hasAction())) {
            if (hasUnknownData) {
                val refreshableUnknowns = AutoKickRules.profileRefreshableUnknowns(evaluation.unknowns)
                if (AutoKickRules.needsProfileRefresh(profileIncomplete, evaluation.unknowns)) {
                    val unknowns = refreshableUnknowns.ifEmpty { listOf("complete profile data") }
                    scheduleProfileRetry(key, floor, snapshot.generation(), member.name()!!, unknowns, null)
                } else {
                    // Another roster member's missing class cannot be fixed by refetching this profile.
                    profileRetries.remove(key)
                    debug("Waiting for party class data for %s without refetching their profile", member.name())
                    trace("WAIT floor=${floor.name};reason=party-class-data;profile-refetch=false")
                }
            } else {
                profileRetries.remove(key)
                handled.add(key)
            }
            return
        }

        profileRetries.remove(key)
        handled.add(key)
        if (policy.askBeforeKick.value()) {
            debug("Asking before kicking %s because manual confirmation is enabled for %s", member.name(), floor.displayName())
            val message = Component.literal("[Auto Kick] ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("${member.name()} failed: ${evaluation.failures.joinToString()} ").withStyle(ChatFormatting.GRAY))
                .append(
                    Component.literal("[Kick]").withStyle { style ->
                        style.withColor(ChatFormatting.RED)
                            .withUnderlined(true)
                            .withClickEvent(ClickEvent.RunCommand("/party kick ${member.name()}"))
                            .withHoverEvent(HoverEvent.ShowText(Component.literal("/party kick ${member.name()}")))
                    },
                )
            queueNotice(message)
            trace("ACTION floor=${floor.name};mode=manual-confirmation;failures=${evaluation.failures.joinToString(",")}")
        } else {
            scheduleKick(floor, snapshot.generation(), member, evaluation.evaluatedClass, stats.selectedClass(), evaluation.failures)
            queueNotice(
                Component.literal("[Auto Kick] ").withStyle(ChatFormatting.RED)
                    .append(
                        Component.literal("${member.name()} failed: ${evaluation.failures.joinToString()} (scheduled)")
                            .withStyle(ChatFormatting.GRAY),
                    ),
            )
        }
    }

    /** Allows an explicit user retry to resume evaluation after an unavailable/incomplete fallback. */
    fun onExplicitStatsRetryStarted(generation: Long, name: String?) {
        if (name.isNullOrBlank()) return
        syncGeneration(generation)
        val normalized = name.lowercase(Locale.ROOT)
        profileRetries.entries.removeIf { (_, retry) -> retry.generation == generation && retry.name.equals(name, true) }
        val nameSegment = ":$normalized:"
        manualFallbacks.removeIf { it.startsWith("$generation:") && nameSegment in it }
        handled.removeIf { it.startsWith("$generation:") && nameSegment in it }
        trace("PROFILE_RETRY source=explicit;generation=$generation")
    }

    /** Cancels work tied to the departed membership episode before a same-name rejoin can reuse it. */
    fun onMemberDeparted(name: String?) {
        if (name.isNullOrBlank()) return
        val normalized = name.lowercase(Locale.ROOT)
        pendingKicks.removeIf { it.name.equals(name, true) }
        profileRetries.entries.removeIf { (_, retry) -> retry.name.equals(name, true) }
        val nameSegment = ":$normalized:"
        handled.removeIf { nameSegment in it }
        manualFallbacks.removeIf { nameSegment in it }
        trace("MEMBER_DEPARTED;pendingActions=${pendingKicks.size};retries=${profileRetries.size}")
    }

    fun tick() {
        beginDiagnosticTick()
        tickNotices()
        syncGeneration(PartyListBackend.snapshot().generation())
        if (!isEnabled()) return

        tickProfileRetries()
        if (pendingKicks.isEmpty()) return
        val now = System.currentTimeMillis()
        val iterator = pendingKicks.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (!stillAllowed(pending)) {
                debug("Cancelled scheduled kick for %s because party state or leadership changed", pending.name)
                trace("ACTION_CANCELLED floor=${pending.floor.name};stage=pre-send;reason=party-state-changed")
                iterator.remove()
                continue
            }
            val mc = Minecraft.getInstance()
            val connection = mc.player?.connection
            if (connection == null) {
                trace("ACTION_CANCELLED floor=${pending.floor.name};stage=pre-send;reason=disconnected")
                iterator.remove()
                continue
            }
            if (!pending.messageSent && now >= pending.messageAt) {
                if (!stillAllowed(pending)) {
                    debug("Cancelled scheduled kick for %s immediately before sending its reason", pending.name)
                    trace("ACTION_CANCELLED floor=${pending.floor.name};stage=reason-send;reason=party-state-changed")
                    iterator.remove()
                    continue
                }
                connection.sendCommand("pc ${pending.partyMessage}")
                pending.messageSent = true
                debug("Sent party reason for %s; kick follows in %d ms", pending.name, pending.kickAt - now)
                trace("COMMAND_SENT floor=${pending.floor.name};type=party-reason;remainingMs=${pending.kickAt - now}")
            }
            if (pending.messageSent && now >= pending.kickAt) {
                if (!stillAllowed(pending)) {
                    debug("Cancelled scheduled kick for %s immediately before sending the kick command", pending.name)
                    trace("ACTION_CANCELLED floor=${pending.floor.name};stage=kick-send;reason=party-state-changed")
                    iterator.remove()
                    continue
                }
                connection.sendCommand("party kick ${pending.name}")
                debug("Sent delayed party kick for %s", pending.name)
                trace("COMMAND_SENT floor=${pending.floor.name};type=party-kick")
                iterator.remove()
            }
        }
    }

    override fun onEnable() {
        diagnosticTick = 0
        diagnosticDetailsThisTick = 0
        suppressedDiagnosticDetails = 0
        lastStatusTick = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        writeDiagnostic("STATUS enabled=true;generation=${PartyListBackend.snapshot().generation()};policies=${policies.size}")
    }

    override fun onDisable() {
        writeDiagnostic(
            "STATUS enabled=false;generation=$markerGeneration;cancelledKicks=${pendingKicks.size};" +
                "cancelledRetries=${profileRetries.size};droppedDetails=$suppressedDiagnosticDetails",
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
        pendingKicks.clear()
        pendingNotices.clear()
        noticeCooldown = 0
        handled.clear()
        profileRetries.clear()
        manualFallbacks.clear()
        markerGeneration = Long.MIN_VALUE
    }

    /** Evaluation markers are meaningful only inside one party generation. */
    private fun syncGeneration(generation: Long) {
        if (markerGeneration == generation) return
        val previous = markerGeneration
        markerGeneration = generation
        handled.clear()
        profileRetries.clear()
        manualFallbacks.clear()
        trace("PARTY_GENERATION changed=$previous->$generation;markers=cleared")
    }

    private fun tickProfileRetries() {
        if (profileRetries.isEmpty()) return
        val now = System.currentTimeMillis()
        for (retry in profileRetries.values.toList()) {
            if (profileRetries[retry.key] !== retry) continue
            val snapshot = PartyListBackend.snapshot()
            val policy = policies[retry.floor]
            val member = findMember(snapshot, retry.name)
            if (
                snapshot.generation() != retry.generation || !snapshot.isLeader(localName()) || member == null ||
                actionKey(retry.generation, retry.floor, member) != retry.key || policy?.autoKick?.value() != true
            ) {
                profileRetries.remove(retry.key)
                trace("PROFILE_RETRY cancelled;floor=${retry.floor.name};reason=party-state-changed")
                continue
            }
            if (now - retry.startedAt >= profileRetryWindowMillis()) {
                profileRetries.remove(retry.key)
                if (manualFallbacks.add(retry.key)) queueManualFallback(retry)
                trace("PROFILE_RETRY expired;floor=${retry.floor.name};attempts=${retry.attempts};fallback=manual")
                continue
            }
            if (retry.inFlight || now < retry.nextAttemptAt) continue
            retry.inFlight = true
            retry.attempts++
            retry.nextAttemptAt = now + profileRetryIntervalMillis()
            trace("PROFILE_RETRY request;floor=${retry.floor.name};attempt=${retry.attempts};missing=${retry.unknowns.joinToString(",")}")
            // DungeonStatsService returns its callback to the client thread; mutable party state stays client-owned.
            DungeonStatsService.fetchFresh(retry.name) { result ->
                if (profileRetries[retry.key] !== retry) return@fetchFresh
                retry.inFlight = false
                if (System.currentTimeMillis() - retry.startedAt >= profileRetryWindowMillis()) {
                    profileRetries.remove(retry.key)
                    if (manualFallbacks.add(retry.key)) queueManualFallback(retry)
                    trace("PROFILE_RETRY expired-in-flight;floor=${retry.floor.name};fallback=manual")
                    return@fetchFresh
                }
                if (!result.available()) {
                    retry.lastError = result.error
                    debug("Profile retry %d for %s failed: %s", retry.attempts, retry.name, retry.lastError)
                    profileRetries.remove(retry.key)
                    if (manualFallbacks.add(retry.key)) queueManualFallback(retry)
                    trace("PROFILE_RETRY failed;floor=${retry.floor.name};attempt=${retry.attempts};fallback=manual")
                    return@fetchFresh
                }
                trace("PROFILE_RETRY completed;floor=${retry.floor.name};attempt=${retry.attempts}")
                onStats(retry.floor, retry.generation, result.stats)
            }
        }
    }

    private fun scheduleProfileRetry(
        key: String,
        floor: DungeonFloor,
        generation: Long,
        name: String,
        unknowns: List<String>?,
        error: String?,
    ) {
        val now = System.currentTimeMillis()
        val retry = profileRetries.getOrPut(key) { ProfileRetry(key, floor, generation, name, now) }
        retry.unknowns = unknowns.orEmpty().ifEmpty { listOf("profile data") }.toList()
        if (!error.isNullOrBlank()) retry.lastError = error
        retry.inFlight = false
        retry.nextAttemptAt = minOf(retry.nextAttemptAt, now + profileRetryIntervalMillis())
        debug("Delaying enforcement for %s; retrying incomplete profile (%s)", name, retry.unknowns.joinToString())
        trace("PROFILE_RETRY scheduled;floor=${floor.name};missing=${retry.unknowns.joinToString(",")};" +
            "windowSeconds=${profileRetryWindow.intValue()};intervalSeconds=${profileRetryInterval.intValue()}")
    }

    private fun queueManualFallback(retry: ProfileRetry) {
        val reason = retry.unknowns.joinToString()
        val suffix = retry.lastError?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        val message = Component.literal("[Auto Kick] ").withStyle(ChatFormatting.YELLOW)
            .append(
                Component.literal(
                    "${retry.name} could not be verified after ${Math.round(profileRetryWindowMillis() / 1000.0)}s; " +
                        "no kick was issued. Missing: $reason$suffix. Retry stats manually: ",
                ).withStyle(ChatFormatting.GRAY),
            )
            .append(
                Component.literal("[Retry Stats]").withStyle { style ->
                    style.withColor(ChatFormatting.YELLOW)
                        .withUnderlined(true)
                        .withClickEvent(ClickEvent.RunCommand(PartyFinderStatsModule.retryCommand(retry.name)))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Retry dungeon stats for ${retry.name}")))
                },
            )
        queueNotice(message)
        trace("MANUAL_FALLBACK floor=${retry.floor.name};missing=$reason;attempts=${retry.attempts}")
    }

    private fun profileRetryWindowMillis(): Long = maxOf(1, profileRetryWindow.intValue()).toLong() * 1_000L

    private fun profileRetryIntervalMillis(): Long = maxOf(1, profileRetryInterval.intValue()).toLong() * 1_000L

    private fun scheduleKick(
        floor: DungeonFloor,
        generation: Long,
        member: PartyMember,
        evaluatedClass: DungeonClass?,
        profileClass: DungeonClass?,
        failures: List<String>,
    ) {
        val name = member.name()!!
        val membershipIdentity = PartyListBackend.membershipIdentity(name)
        if (membershipIdentity == 0L) {
            debug("Not scheduling a kick for %s because the current membership has no identity", name)
            trace("ACTION_REJECTED floor=${floor.name};reason=missing-membership-identity")
            return
        }
        val messageDelay = randomDelay()
        val kickDelay = randomDelay()
        val now = System.currentTimeMillis()
        val queueAfter = pendingKicks.maxOfOrNull { it.kickAt } ?: now
        val startAt = maxOf(now, queueAfter)
        val partyMessage = limitChat("$name will be kicked for: ${failures.joinToString()}")
        pendingKicks += PendingKick(
            floor,
            generation,
            name,
            membershipIdentity,
            evaluatedClass,
            profileClass,
            partyMessage,
            startAt + messageDelay,
            startAt + messageDelay + kickDelay,
        )
        debug(
            "Scheduled %s after the existing action queue: party reason in %d ms, kick %d ms later",
            name,
            startAt - now + messageDelay,
            kickDelay,
        )
        trace("ACTION_SCHEDULED floor=${floor.name};generation=$generation;messageDelayMs=${startAt - now + messageDelay};" +
            "kickDelayMs=$kickDelay;failures=${failures.joinToString(",")};queueSize=${pendingKicks.size}")
    }

    private fun stillAllowed(pending: PendingKick): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        if (player.connection == null) return false
        val snapshot = PartyListBackend.snapshot()
        val member = findMember(snapshot, pending.name)
        val currentClass = member?.dungeonClass() ?: if (member == null) null else pending.profileClass
        val policy = policies[pending.floor]
        return snapshot.generation() == pending.generation && snapshot.isLeader(localName()) && member != null &&
            PartyListBackend.membershipIdentity(pending.name) == pending.membershipIdentity &&
            currentClass == pending.evaluatedClass && policy != null && policy.autoKick.value() && !policy.askBeforeKick.value()
    }

    private fun tickNotices() {
        if (noticeCooldown > 0) noticeCooldown--
        if (noticeCooldown > 0 || pendingNotices.isEmpty()) return
        val mc = Minecraft.getInstance()
        if (mc.gui == null) return
        mc.gui.hud.getChat().addClientSystemMessage(pendingNotices.removeFirst())
        noticeCooldown = CHAT_NOTICE_GAP_TICKS
    }

    private fun queueNotice(message: Component?) {
        if (message == null) return
        if (pendingNotices.size >= 16) pendingNotices.removeFirst()
        if (pendingNotices.isEmpty() && noticeCooldown == 0) noticeCooldown = CHAT_NOTICE_GAP_TICKS
        pendingNotices.addLast(message)
    }

    private fun beginDiagnosticTick() {
        if (!isEnabled()) return
        diagnosticTick++
        diagnosticDetailsThisTick = 0
        if (suppressedDiagnosticDetails > 0) {
            writeDiagnostic("SUPPRESSED details=$suppressedDiagnosticDetails;limitPerTick=$MAX_DIAGNOSTIC_DETAILS_PER_TICK")
            suppressedDiagnosticDetails = 0
        }
        if (diagnosticTick - lastStatusTick >= DIAGNOSTIC_STATUS_INTERVAL_TICKS) {
            lastStatusTick = diagnosticTick
            writeDiagnostic(
                "STATUS enabled=true;generation=$markerGeneration;policies=${policies.size};" +
                    "handled=${handled.size};retries=${profileRetries.size};manualFallbacks=${manualFallbacks.size};" +
                    "pendingKicks=${pendingKicks.size};queuedNotices=${pendingNotices.size};" +
                    "retryWindowSeconds=${profileRetryWindow.intValue()};retryIntervalSeconds=${profileRetryInterval.intValue()}",
            )
        }
    }

    private fun trace(line: String) {
        if (!isEnabled()) return
        if (diagnosticDetailsThisTick >= MAX_DIAGNOSTIC_DETAILS_PER_TICK) {
            suppressedDiagnosticDetails++
            return
        }
        diagnosticDetailsThisTick++
        writeDiagnostic(line)
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.F7, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    private fun debug(format: String, vararg arguments: Any?) {
        val message = String.format(Locale.ROOT, format, *arguments)
        GeilerAddons.LOGGER.debug("[Auto Kick] {}", message)
        if (!debugSetting.value()) return
        val mc = Minecraft.getInstance()
        if (mc.gui != null) {
            mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[Auto Kick Debug] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(message).withStyle(ChatFormatting.GRAY)),
            )
        }
    }

    private class PendingKick(
        val floor: DungeonFloor,
        val generation: Long,
        val name: String,
        val membershipIdentity: Long,
        val evaluatedClass: DungeonClass?,
        val profileClass: DungeonClass?,
        val partyMessage: String,
        val messageAt: Long,
        val kickAt: Long,
        var messageSent: Boolean = false,
    )

    private class Evaluation {
        val failures = mutableListOf<String>()
        val unknowns = mutableListOf<String>()
        var evaluatedClass: DungeonClass? = null
        fun hasAction(): Boolean = failures.isNotEmpty()
        fun hasUnknown(): Boolean = unknowns.isNotEmpty()
    }

    private class ProfileRetry(
        val key: String,
        val floor: DungeonFloor,
        val generation: Long,
        val name: String,
        val startedAt: Long,
        var nextAttemptAt: Long = startedAt,
        var attempts: Int = 0,
        var inFlight: Boolean = false,
        var lastError: String? = null,
        var unknowns: List<String> = listOf("profile data"),
    )

    private class Settings {
        val debug = BooleanSetting.debug("Debug", false)
        val profileRetryWindow = NumberSetting("Profile Retry Window", "Retry Window (s)", 1f, 60f, 15f, true)
        val profileRetryInterval = NumberSetting("Profile Retry Interval", "Retry Every (s)", 1f, 10f, 2f, true)
        val policies = EnumMap<DungeonFloor, FloorPolicy>(DungeonFloor::class.java)
        val normal = mutableListOf<FloorPolicy>()
        val master = mutableListOf<FloorPolicy>()

        init {
            for (floor in DungeonFloor.values()) {
                if (floor.number() == 0) continue
                val policy = FloorPolicy(floor)
                policies[floor] = policy
                (if (floor.master()) master else normal) += policy
            }
        }

        fun allSettings(): List<Setting> = buildList {
            add(debug)
            add(profileRetryWindow)
            add(profileRetryInterval)
            policies.values.forEach { addAll(it.settings()) }
        }
    }

    private class FloorPolicy(val floor: DungeonFloor) {
        private val prefix = "${floor.displayName()} "
        val autoKick = BooleanSetting("${prefix}Auto Kick", "Auto Kick", false)
        val askBeforeKick = BooleanSetting("${prefix}Ask Before", "Ask Before", true)
        val dupeCheck = BooleanSetting("${prefix}Dupe", "Dupe", false)
        val minCata = TextSetting("${prefix}Cata", "Min Cata Level", "", 6)
        val minClass = TextSetting("${prefix}Class", "Min Class Level", "", 6)
        val minClassAverage = TextSetting("${prefix}Class Avg", "Min Class Avg", "", 8)
        val minSecrets = TextSetting("${prefix}Secrets", "Min Secrets", "", 12)
        val minSecretAverage = TextSetting("${prefix}Secret Avg", "Min Secret Avg", "", 8)
        val minMagicalPower = TextSetting("${prefix}MP", "Min MP", "", 8)
        val personalBestLimit = TextSetting("${prefix}Minimum PB", "PB limit (m:ss or sec; 0 off)", "", 16)
        val minBank = TextSetting("${prefix}Bank", "Min Bank", "", 14)
        val terminator = BooleanSetting("${prefix}Terminator", "Terminator", false)
        val hyperion = BooleanSetting("${prefix}Hyperion", "Hyperion", false)
        val goldenDragon = BooleanSetting("${prefix}GDrag", "GDrag", false)

        fun settings(): List<Setting> = listOf(
            autoKick, askBeforeKick, dupeCheck, minCata, minClass, minClassAverage, minSecrets,
            minSecretAverage, minMagicalPower, personalBestLimit, minBank, terminator, hyperion, goldenDragon,
        )

        fun group(): SettingGroup = SettingGroup.switchedFolded(
            floor.displayName(), autoKick, askBeforeKick, dupeCheck, minCata, minClass,
            minClassAverage, minSecrets, minSecretAverage, minMagicalPower, personalBestLimit,
            minBank, terminator, hyperion, goldenDragon,
        )

        fun evaluate(member: PartyMember, snapshot: PartySnapshot, stats: DungeonStats, floor: DungeonFloor): Evaluation {
            val result = Evaluation()
            val requiredCata = minCata.intValue(0).coerceAtLeast(0)
            val requiredClass = minClass.intValue(0).coerceAtLeast(0)
            val requiredClassAverage = minClassAverage.doubleValue(0.0).coerceAtLeast(0.0)
            val requiredSecrets = minSecrets.longValue(0L).coerceAtLeast(0L)
            val requiredSecretAverage = minSecretAverage.doubleValue(0.0).coerceAtLeast(0.0)
            val requiredMagicalPower = minMagicalPower.intValue(0).coerceAtLeast(0)
            val requiredPersonalBest = AutoKickRules.parsePersonalBestLimitSeconds(personalBestLimit.value())

            if (requiredCata > 0 && !stats.has(DungeonStats.DataField.CATACOMBS_LEVEL)) {
                result.unknowns += "Cata"
            } else if (requiredCata > 0 && stats.catacombsLevel() < requiredCata) {
                result.failures += "Cata ${stats.catacombsLevel()}/$requiredCata"
            }

            val dungeonClass = member.dungeonClass() ?: stats.selectedClass()
            result.evaluatedClass = dungeonClass
            if (dupeCheck.value() && dungeonClass == null) result.unknowns += "Class"
            if (dupeCheck.value() && dungeonClass != null) {
                for (other in snapshot.members()) {
                    if (other.name().equals(member.name(), ignoreCase = true)) continue
                    val otherClass = other.dungeonClass()
                    if (otherClass == null) {
                        if ("Party classes" !in result.unknowns) result.unknowns += "Party classes"
                        continue
                    }
                    if (dungeonClass == otherClass) {
                        result.failures += "duplicate ${dungeonClass.displayName()}"
                        break
                    }
                }
            }

            if (requiredClass > 0) {
                when {
                    dungeonClass == null -> result.unknowns += "Class"
                    !stats.hasClassLevel(dungeonClass) -> result.unknowns += dungeonClass.displayName()
                    stats.classLevel(dungeonClass) < requiredClass ->
                        result.failures += "${dungeonClass.displayName()} ${stats.classLevel(dungeonClass)}/$requiredClass"
                }
            }
            if (requiredClassAverage > 0 && !stats.has(DungeonStats.DataField.CLASS_AVERAGE)) {
                result.unknowns += "CA"
            } else if (requiredClassAverage > 0 && stats.classAverage() < requiredClassAverage) {
                result.failures += "CA ${format(stats.classAverage())}/${format(requiredClassAverage)}"
            }
            if (requiredSecrets > 0 && !stats.has(DungeonStats.DataField.SECRETS)) {
                result.unknowns += "Secrets"
            } else if (requiredSecrets > 0 && stats.totalSecrets() < requiredSecrets) {
                result.failures += "Secrets ${stats.totalSecrets()}/$requiredSecrets"
            }
            if (requiredSecretAverage > 0 &&
                (!stats.has(DungeonStats.DataField.SECRETS) || !stats.has(DungeonStats.DataField.RUNS))
            ) {
                result.unknowns += "SA"
            } else if (requiredSecretAverage > 0 && stats.secretAverage() < requiredSecretAverage) {
                result.failures += "SA ${format(stats.secretAverage())}/${format(requiredSecretAverage)}"
            }
            if (requiredMagicalPower > 0 && !stats.has(DungeonStats.DataField.MAGICAL_POWER)) {
                result.unknowns += "MP"
            } else if (requiredMagicalPower > 0 && stats.magicalPower() < requiredMagicalPower) {
                result.failures += "MP ${stats.magicalPower()}/$requiredMagicalPower"
            }
            if (requiredPersonalBest > 0 && !stats.hasPersonalBest(floor)) {
                result.unknowns += "PB"
            } else if (requiredPersonalBest > 0 &&
                !AutoKickRules.personalBestPasses(stats.fastestSPlusSeconds(floor), requiredPersonalBest.toLong())
            ) {
                result.failures += "PB ${DungeonStatsService.formatTime(stats.fastestSPlusSeconds(floor))}/" +
                    DungeonStatsService.formatTime(requiredPersonalBest.toLong())
            }

            val bank = minBank.longValue(0L)
            if (bank > 0 && !stats.has(DungeonStats.DataField.BANK)) {
                result.unknowns += "Bank"
            } else if (bank > 0 && stats.bank() < bank) {
                result.failures += "bank ${stats.bank()}"
            }

            addGearResult(result, terminator.value(), stats, DungeonStats.Gear.TERMINATOR, "Term", "no Term")
            addGearResult(result, hyperion.value(), stats, DungeonStats.Gear.HYPERION, "Hype", "no Hype")
            addGearResult(result, goldenDragon.value(), stats, DungeonStats.Gear.GOLDEN_DRAGON, "GDrag", "no GDrag")
            return result
        }

        fun describeChecks(): String = buildList {
            minCata.intValue(0).coerceAtLeast(0).takeIf { it > 0 }?.let { add("Cata >= $it") }
            minClass.intValue(0).coerceAtLeast(0).takeIf { it > 0 }?.let { add("class >= $it") }
            minClassAverage.doubleValue(0.0).coerceAtLeast(0.0).takeIf { it > 0 }?.let { add("CA >= ${format(it)}") }
            minSecrets.longValue(0L).coerceAtLeast(0L).takeIf { it > 0 }?.let { add("secrets >= $it") }
            minSecretAverage.doubleValue(0.0).coerceAtLeast(0.0).takeIf { it > 0 }?.let { add("SA >= ${format(it)}") }
            minMagicalPower.intValue(0).coerceAtLeast(0).takeIf { it > 0 }?.let { add("MP >= $it") }
            AutoKickRules.parsePersonalBestLimitSeconds(personalBestLimit.value()).takeIf { it > 0 }
                ?.let { add("PB <= ${DungeonStatsService.formatTime(it.toLong())}") }
            minBank.longValue(0L).coerceAtLeast(0L).takeIf { it > 0 }?.let { add("bank >= $it") }
            if (dupeCheck.value()) add("no class dupe")
            if (terminator.value()) add("Term")
            if (hyperion.value()) add("Hype")
            if (goldenDragon.value()) add("GDrag")
        }.ifEmpty { listOf("none configured") }.joinToString()

        private fun addGearResult(
            result: Evaluation,
            enabled: Boolean,
            stats: DungeonStats,
            gear: DungeonStats.Gear,
            unknownLabel: String,
            failureLabel: String,
        ) {
            when (AutoKickRules.gearOutcome(enabled, stats.hasGearData(gear), stats.has(gear))) {
                AutoKickRules.GearOutcome.PASS -> Unit
                AutoKickRules.GearOutcome.UNKNOWN -> result.unknowns += unknownLabel
                AutoKickRules.GearOutcome.FAIL -> result.failures += failureLabel
            }
        }

        private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
