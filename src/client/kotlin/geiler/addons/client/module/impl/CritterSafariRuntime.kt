package geiler.addons.client.module.impl

import geiler.addons.client.entity.Nameplates
import geiler.addons.client.hunting.Critter
import geiler.addons.client.hunting.CritterChatParser
import geiler.addons.client.hunting.CritterEvent
import geiler.addons.client.hunting.CritterSpecies
import geiler.addons.client.hunting.FoundTrade
import geiler.addons.client.hunting.MarkerRules
import geiler.addons.client.hunting.SafariRun
import geiler.addons.client.hunting.SafariVisibility
import geiler.addons.client.hunting.ShardPriceFeed
import geiler.addons.client.hunting.TradeBook
import geiler.addons.client.hunting.TradeLines
import geiler.addons.client.hunting.TradeParser
import geiler.addons.client.hunting.TradeSpot
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.location.SafariBiome
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** One labelled critter the client knows about. [visible] (a line to it) is only worked out when asked. */
class CritterSighting(val critter: Critter, val sparkling: Boolean, val label: Entity, val body: Entity?) {
    val visible: Boolean by lazy { SafariVisibility.hasLineTo(body ?: label) }
    fun position(): Vec3 = (body ?: label).position()
}

/** Where a chat line is sent. */
enum class Audience(val command: String?) { NOBODY(null), PARTY("pc"), ALL("ac") }

/**
 * The live Critter Safari run and everything the three Critter modules share: the run tally, the Hunter trades,
 * the hotspot, one entity sweep of labelled critters, and the preset world state. It runs while any Critter module
 * is enabled and is otherwise idle. All of it is client-thread state.
 *
 * A run opens on arrival at the Safari (not on the entry banner, which names whoever warped the party in, so a
 * non-leader never sees their own name) and closes after the player has been away for a grace period with no
 * recent catch. The finished run stays as [lastRun] so `/critters` still works after leaving.
 */
object CritterSafari {
    private const val LEAVE_GRACE_TICKS = 40
    private const val AWAY_TICKS = 100
    private const val RECENT_ACTIVITY_MILLIS = 60_000L
    private const val SWEEP_TICKS = 5
    private const val SWEEP_RADIUS = 64.0
    private const val MAX_SIGHTINGS = 256
    private const val MAX_QUEUE = 8
    private const val SEND_GAP_TICKS = 20L

    var run: SafariRun? = null
        private set
    var lastRun: SafariRun? = null
        private set
    val trades = TradeBook()
    private val tradeParser = TradeParser()
    private val tradeSpots = HashMap<String, TradeSpot>()
    var hotspot: SafariBiome? = null
        private set
    var moundsBroken = 0
        private set
    var moundRockmites = 0
        private set

    private val sightingList = ArrayList<CritterSighting>()
    private var previousIds: Set<UUID> = emptySet()
    private var fresh: List<CritterSighting> = emptyList()
    private var macawVisible = false

    private var ticksAway = 0
    private var arrivalStartsRun = true
    private var lastEventMillis = 0L
    private var startedBy = "none"
    private var sweepTicks = 0
    private var tick = 0L
    private var lastSend = Long.MIN_VALUE
    private val outbox = ArrayDeque<String>()
    private val announcedBiomes = java.util.EnumSet.noneOf(SafariBiome::class.java)
    private var announcedAllButMacaw = false
    private var announcedAllDone = false

    private var lastWorld: Any? = null
    var instanceResets = 0
        private set

    /** Whether the world object changed under a live run while still in the Safari, i.e. the player warped to a new instance. */
    @JvmStatic
    fun worldSwapped(inSafari: Boolean, hasRun: Boolean, previous: Any?, current: Any?): Boolean =
        inSafari && hasRun && previous != null && current != null && previous !== current

    /** Whether a critter counts toward the nearby tally: in line of sight, or anywhere in range with Count Through Walls on. */
    @JvmStatic
    fun countsNearby(visible: Boolean, throughWalls: Boolean): Boolean = visible || throughWalls

    fun sightings(): List<CritterSighting> = sightingList

    fun inSafari(): Boolean = HypixelModApi.currentIsland() == Island.SAFARI

    /** Whether any Critter module is switched on, which is what keeps this running. */
    fun active(): Boolean =
        CritterTrackerModule.INSTANCE.isEnabled() || CritterMarkersModule.INSTANCE.isEnabled() || CritterAlertsModule.INSTANCE.isEnabled()

    fun selfName(): String = Minecraft.getInstance().user.name

    /** The live run, or the last finished one. */
    fun currentOrLast(): SafariRun? = run ?: lastRun

    // ---- lifecycle ----

    fun startRun(trigger: String) {
        val finished = run
        run = SafariRun(selfName(), System.currentTimeMillis()).also { it.uniqueOnly = CritterTrackerModule.INSTANCE.uniqueOnly() }
        if (finished != null && !finished.isEmpty()) lastRun = finished
        startedBy = trigger
        lastEventMillis = System.currentTimeMillis()
        announcedBiomes.clear()
        announcedAllButMacaw = false
        announcedAllDone = false
        trades.reset()
        tradeParser.reset()
        tradeSpots.clear()
        hotspot = null
        moundsBroken = 0
        moundRockmites = 0
        macawVisible = false
        CritterSafariWorld.reset()
        CritterAlertsModule.INSTANCE.onRunStart()
    }

    private fun endRun() {
        val finished = run ?: return
        run = null
        finished.endedAtMillis = lastEventMillis.coerceAtLeast(finished.startedAtMillis + 1)
        if (!finished.isEmpty()) lastRun = finished
    }

    /** Wipes the live run without waiting to leave the island (`/critters reset`). */
    fun resetRun() = startRun("command")

    fun tick() {
        tick++
        if (!active()) {
            if (run != null) endRun()
            sightingList.clear()
            return
        }
        flushOutbox()

        val world: Any? = Minecraft.getInstance().level
        if (inSafari()) {
            if (ticksAway >= AWAY_TICKS) arrivalStartsRun = true
            ticksAway = 0
            if (arrivalStartsRun) {
                arrivalStartsRun = false
                startRun("arrival")
            } else if (worldSwapped(true, run != null, lastWorld, world)) {
                // Warping to another Safari instance keeps the island, so only the world object tells the runs apart.
                instanceResets++
                sightingList.clear()
                previousIds = emptySet()
                startRun("instance")
            }
            lastWorld = world
        } else {
            lastWorld = world
            ticksAway++
            val quiet = System.currentTimeMillis() - lastEventMillis >= RECENT_ACTIVITY_MILLIS
            if (run != null && quiet && ticksAway >= LEAVE_GRACE_TICKS) endRun()
            sightingList.clear()
            return
        }

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val tracker = CritterTrackerModule.INSTANCE
        val markers = CritterMarkersModule.INSTANCE

        run?.uniqueOnly = tracker.uniqueOnly()
        ShardPriceFeed.tick(System.currentTimeMillis(), tracker.isEnabled() && tracker.shardValueOn() && run != null)

        if (++sweepTicks >= SWEEP_TICKS) {
            sweepTicks = 0
            sweep(level, player)
        }
        val counts = tracker.isEnabled() && tracker.countsOn()
        CritterSafariWorld.tick(
            sightingList, fresh,
            wallsAndPerches = markers.wantsWalls() || counts,
            beesWanted = markers.wantsBees(),
            moundsWanted = markers.wantsMounds() || counts,
        )
        fresh = emptyList()
        run?.let { updateUnavailable(it) }
        watchMacaw()
    }

    /** Snoozle comes from the breakable Cavern walls: once every wall is broken and none was seen or caught, none can. */
    private fun updateUnavailable(run: SafariRun) {
        val snoozle = CritterSpecies.byName("Snoozle") ?: return
        val gone = MarkerRules.snoozleGone(CritterSafariWorld.snooper.allBroken(), run.partyCatches(snoozle), run.nearby(snoozle))
        run.unavailable = if (gone) setOf(snoozle) else emptySet()
    }

    /** One pass over labelled critters; labels first seen this pass are kept as [fresh] for edge-triggered logic. */
    private fun sweep(level: ClientLevel, player: LocalPlayer) {
        val limit = SWEEP_RADIUS * SWEEP_RADIUS
        val ids = HashSet<UUID>()
        val newOnes = ArrayList<CritterSighting>()
        sightingList.clear()
        for (entity in level.entitiesForRendering()) {
            if (sightingList.size >= MAX_SIGHTINGS) break
            if (!entity.hasCustomName() || entity.distanceToSqr(player) > limit) continue
            val text = entity.customName?.string ?: continue
            val sparkling = CritterSpecies.sparkling(text)
            val critter = CritterSpecies.byName(sparkling ?: CritterSpecies.named(text)) ?: continue
            val sighting = CritterSighting(critter, sparkling != null, entity, Nameplates.resolveBody(level, entity))
            sightingList.add(sighting)
            ids.add(entity.uuid)
            if (entity.uuid !in previousIds) newOnes.add(sighting)
        }
        previousIds = ids
        fresh = newOnes

        val run = run ?: return
        if (!CritterTrackerModule.INSTANCE.nearbyOn()) {
            run.setNearby(emptyMap())
            return
        }
        val counts = HashMap<Critter, Int>()
        val throughWalls = CritterTrackerModule.INSTANCE.nearbyThroughWalls()
        for (s in sightingList) if (countsNearby(s.visible, throughWalls)) counts.merge(s.critter, 1, Int::plus)
        run.setNearby(counts)
    }

    /** A Macaw in plain sight, announced once when it appears (not on every sweep it stands there). */
    private fun watchMacaw() {
        val seen = sightingList.firstOrNull { it.critter == CritterSpecies.MACAW && it.visible }
        if (seen != null && !macawVisible) {
            val p = seen.position()
            CritterAlertsModule.INSTANCE.onMacawSpawn(TradeSpot(SafariBiome.FOREST, p.x.toInt(), p.y.toInt(), p.z.toInt()))
        }
        macawVisible = seen != null
    }

    // ---- chat ----

    /** Feeds one plain chat line (colour codes already stripped). */
    fun onChat(plain: String) {
        if (!active()) return
        val line = CritterChatParser.clean(plain)
        if (line.isEmpty()) return

        // Anything a player can type, a player can quote: typed lines only ever carry shared trades.
        if (CritterChatParser.playerSaid(line)) {
            onPartyLine(line)
            return
        }

        val event = CritterChatParser.parse(line)
        if (event != null) {
            onEvent(event)
            return
        }
        CritterChatParser.hotspot(line)?.let {
            hotspot = it
            CritterAlertsModule.INSTANCE.onHotspot(it)
            return
        }
        if (line.startsWith("The mound falls apart, but nothing is inside")) {
            moundsBroken++
        } else if (line.startsWith("The mound fell apart, revealing")) {
            moundsBroken++
            moundRockmites++
        }
        onTradeLine(line)
        CritterAlertsModule.INSTANCE.onGameLine(line)
    }

    private fun onEvent(event: CritterEvent) {
        // A catch or capsule throw only happens in the Safari, so it opens a run even if arrival was missed.
        if (run == null) startRun("first catch")
        val current = run ?: return
        lastEventMillis = System.currentTimeMillis()
        current.record(event)
        val critter = event.critter ?: return
        if (!event.isCatch()) return
        CritterAlertsModule.INSTANCE.onCatch(critter)
        for (biome in SafariBiome.values()) {
            if (biome in announcedBiomes || !current.biomeComplete(biome)) continue
            announcedBiomes.add(biome)
            CritterAlertsModule.INSTANCE.onBiomeComplete(biome)
        }
        if (!announcedAllDone && current.dexComplete()) {
            announcedAllDone = true
            announcedAllButMacaw = true
            CritterAlertsModule.INSTANCE.onAllDone()
        } else if (!announcedAllButMacaw && current.allCaughtExcept(CritterSpecies.MACAW)) {
            announcedAllButMacaw = true
            CritterAlertsModule.INSTANCE.onAllButMacaw()
        }
    }

    // ---- trades ----

    private fun onTradeLine(line: String) {
        val player = Minecraft.getInstance().player
        val offer = tradeParser.parse(line)
        tradeParser.offerJustRegistered()?.let { npc ->
            // Where the dialog opened, not where it finished: the two lines are seconds apart.
            if (player != null) {
                val p = player.blockPosition()
                if (tradeSpots.size < 16) tradeSpots[npc] = TradeSpot(SafariBiome.current(), p.x, p.y, p.z)
            }
        }
        if (offer != null) {
            val spot = tradeSpots[offer.npc]
            if (trades.add(FoundTrade(offer, spot))) CritterAlertsModule.INSTANCE.onTradeFound(offer, spot)
        }
        // The one NPC-independent completion line: every Hunter thanks you differently and Harry sometimes not at all.
        TradeLines.given(line)?.let { critter ->
            if (trades.take(critter)) CritterAlertsModule.INSTANCE.onTradeTaken(critter)
        }
    }

    /** A partymate's trade announcement (this mod's wording, or Critter Mod's) or taken line, read off party chat. */
    private fun onPartyLine(line: String) {
        val (sender, text) = TradeLines.partyLine(line) ?: return
        if (sender == selfName() || !CritterAlertsModule.INSTANCE.readsSharedTrades()) return
        TradeLines.parseShared(text)?.let { shared ->
            val offer = geiler.addons.client.hunting.TradeOffer(shared.npc, shared.critter, shared.item)
            trades.add(FoundTrade(offer, shared.spot))
            return
        }
        TradeLines.parseTaken(text)?.let { trades.take(it) }
    }

    // ---- output ----

    /** Local chat line, prefixed so it reads as the mod's. */
    fun say(text: String, style: ChatFormatting = ChatFormatting.WHITE) {
        Minecraft.getInstance().gui?.hud?.chat?.addClientSystemMessage(
            Component.literal("[Critters] ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(style)),
        )
    }

    /** Queues a line for party or all chat. Spaced out and bounded so a burst of alerts cannot trip Hypixel's chat limits. */
    fun post(audience: Audience, message: String) {
        val command = audience.command ?: return
        if (outbox.size >= MAX_QUEUE) outbox.removeFirst()
        outbox.addLast("$command $message")
    }

    private fun flushOutbox() {
        if (outbox.isEmpty() || tick - lastSend < SEND_GAP_TICKS) return
        val player = Minecraft.getInstance().player ?: return
        lastSend = tick
        player.connection.sendCommand(outbox.removeFirst())
    }

    /** Bounded, player-free state for the diagnostic STATUS lines. */
    fun summary(): String {
        val r = run
        return "inSafari=${inSafari()} run=${if (r == null) "none" else "open"} startedBy=$startedBy instanceResets=$instanceResets " +
            "party=${r?.partyUnique() ?: 0}/${CritterSpecies.count()} own=${r?.ownUnique() ?: 0} " +
            "catches=${r?.partyTotal() ?: 0} shards=${r?.totalShards() ?: 0} trades=${trades.all().size} " +
            "sightings=${sightingList.size} mounds=${CritterSafariWorld.moundCount()} " +
            "snooperLeft=${CritterSafariWorld.snooper.remaining()} troodonLeft=${CritterSafariWorld.troodon.remaining()} " +
            "prices=${ShardPriceFeed.status(System.currentTimeMillis())} queued=${outbox.size}"
    }
}
