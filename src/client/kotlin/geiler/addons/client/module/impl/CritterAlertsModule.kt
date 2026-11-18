package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.hunting.Critter
import geiler.addons.client.hunting.CritterSpecies
import geiler.addons.client.hunting.TradeLines
import geiler.addons.client.hunting.TradeOffer
import geiler.addons.client.hunting.TradeSpot
import geiler.addons.client.location.SafariBiome
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import net.minecraft.ChatFormatting
import java.util.regex.Pattern

/**
 * Calls out Critter Safari encounters and milestones, and shares them with the party: Gemzie, Wumpa and Doomspiral
 * (ready, started, done), biome complete, everything except Macaw, everything done, a Macaw spawning, Hunter trade
 * offers and your Hunting Hotspot. Each announcement chooses its own audience (nobody, party or all chat). Trades
 * use Critter Mod's party wording so users of either mod see each other's. Sound is off by default.
 * Behaviour follows Critter Mod (MrCloudy2, MIT); the code is written independently.
 */
class CritterAlertsModule private constructor(private val s: Settings) : Module(
    "Critter Alerts",
    "Alerts for Critter Safari encounters, biome milestones, Macaw spawns and Hunter trades, with party sharing.",
    Category.HUNTING,
    s.gemzie, s.wumpa, s.doomspiral, s.inBiomeOnly, s.biomeDone, s.allButMacaw, s.allDone, s.macaw, s.otherBirds,
    s.tradeOffer, s.hotspot, s.sound, s.readSharedTrades,
    s.gemzieTo, s.wumpaTo, s.doomspiralTo, s.milestonesTo, s.macawTo, s.hotspotTo, s.tradesTo,
) {
    enum class Stage { READY, STARTED, DONE }

    class Settings {
        val gemzie = BooleanSetting("Gemzie Alerts", true)
        val wumpa = BooleanSetting("Wumpa Alerts", true)
        val doomspiral = BooleanSetting("Doomspiral Alerts", true)
        val inBiomeOnly = BooleanSetting("Encounter Alerts In Biome Only", true)
        val biomeDone = BooleanSetting("Biome Complete Alert", true)
        val allButMacaw = BooleanSetting("Everything Except Macaw Alert", true)
        val allDone = BooleanSetting("Everything Done Alert", true)
        val macaw = BooleanSetting("Macaw Alert", true)
        val otherBirds = BooleanSetting("Bluebird And Parakeet Alerts", false)
        val tradeOffer = BooleanSetting("Hunter Trade Alert", true)
        val hotspot = BooleanSetting("Hunting Hotspot Alert", true)
        val sound = BooleanSetting("Alert Sound", false)
        val readSharedTrades = BooleanSetting("Read Shared Trades From Party", true)
        val gemzieTo = audience("Gemzie Broadcast")
        val wumpaTo = audience("Wumpa Broadcast")
        val doomspiralTo = audience("Doomspiral Broadcast")
        val milestonesTo = audience("Milestone Broadcast")
        val macawTo = audience("Macaw Broadcast")
        val hotspotTo = audience("Hotspot Broadcast")
        val tradesTo = audience("Trade Broadcast")

        private fun audience(name: String) = ChoiceSetting(name, "Nobody", "Nobody", "Party", "All")
    }

    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 480)
    private val lastFired = HashMap<String, Long>()
    private var gemzieRemaining = 0
    private var lastMacaw = 0L
    private var testing = false
    private var fired = 0
    private var posted = 0

    override fun isActive(): Boolean = isEnabled() && CritterSafari.inSafari()
    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not in the Critter Safari" else null

    override fun onEnable() {
        fired = 0
        posted = 0
        diagnostics.enable(
            "gemzie=${s.gemzie.value()} wumpa=${s.wumpa.value()} doomspiral=${s.doomspiral.value()} inBiomeOnly=${s.inBiomeOnly.value()} " +
                "macaw=${s.macaw.value()} sound=${s.sound.value()} sharedTrades=${s.readSharedTrades.value()}",
        )
    }

    override fun onDisable() = diagnostics.disable()

    fun readsSharedTrades(): Boolean = s.readSharedTrades.value()

    /** Flips the Biome Complete Alert setting and returns its new value (the `/critters biomedone` quick toggle). */
    fun toggleBiomeDone(): Boolean {
        s.biomeDone.toggle()
        return s.biomeDone.value()
    }

    private fun audience(setting: ChoiceSetting): Audience = when (setting.value()) {
        "Party" -> Audience.PARTY
        "All" -> Audience.ALL
        else -> Audience.NOBODY
    }

    // ---- triggers (all no-ops while disabled) ----

    fun onRunStart() {
        lastFired.clear()
        gemzieRemaining = 0
        lastMacaw = 0L
    }

    /** Encounter and Birdfeeder lines. Only ever fed game lines, never player-typed ones. */
    fun onGameLine(line: String) {
        if (!isEnabled()) return
        when {
            line.startsWith("A rumbling sound can be heard") -> {
                // Exactly three Gemzies spawn per chamber, so the encounter ends when three are caught, not on a message.
                gemzieRemaining = GEMZIE_PER_CHAMBER
                encounter("Gemzie", Stage.READY, "chamber open, 3 to catch", 1.4f)
            }
            line.startsWith("You hear the sound of massive footsteps") -> encounter("Wumpa", Stage.READY, "it wakes in ~30s", 1.4f)
            line.startsWith("The Wumpa has awoken") -> encounter("Wumpa", Stage.STARTED, "fight is live", 1.8f)
            line.startsWith("The cave opens up again") -> encounter("Wumpa", Stage.DONE, "the cave has reopened", 1.2f)
            line.startsWith("You used the Soothing Incense to light the candle") -> encounter("Doomspiral", Stage.READY, "ritual underway", 1.4f)
            line.startsWith("Your ritual summoned a Doomspiral") -> encounter("Doomspiral", Stage.STARTED, "fight is live", 1.8f)
            line.startsWith("The Doomspiral retreats back underground") -> encounter("Doomspiral", Stage.DONE, "it retreated", 1.2f)
            else -> birdfeeder(line)
        }
    }

    fun onCatch(critter: Critter) {
        if (!isEnabled()) return
        when (critter.name) {
            "Wumpa", "Doomspiral" -> encounter(critter.name, Stage.DONE, "caught", 1.2f)
            "Gemzie" -> if (gemzieRemaining > 0 && --gemzieRemaining == 0) encounter("Gemzie", Stage.DONE, "all $GEMZIE_PER_CHAMBER caught", 1.2f)
        }
    }

    fun onBiomeComplete(biome: SafariBiome) {
        if (!isEnabled() || !s.biomeDone.value() || onCooldown("biome:${biome.name}")) return
        milestone("${biome.displayName()} Done!", 1.4f)
    }

    fun onAllButMacaw() {
        if (isEnabled() && s.allButMacaw.value()) milestone("Everything except Macaw done!", 1.6f)
    }

    fun onAllDone() {
        if (isEnabled() && s.allDone.value()) milestone("Everything Done!", 2.0f)
    }

    fun onHotspot(biome: SafariBiome) {
        if (!isEnabled() || !s.hotspot.value()) return
        say("Hotspot: ${biome.displayName()}", ChatFormatting.AQUA)
        send(s.hotspotTo, "My hotspot: ${biome.displayName()}", "hotspot")
    }

    fun onTradeFound(offer: TradeOffer, spot: TradeSpot?) {
        if (!isEnabled()) return
        if (s.tradeOffer.value()) {
            val where = spot?.let { " (${it.biome?.displayName() ?: "?"} ${it.x} ${it.y} ${it.z})" } ?: ""
            say("${offer.npc}$where: ${offer.critter.name} Shard for ${offer.item}", ChatFormatting.AQUA)
            banner("HUNTER: ${offer.critter.name.uppercase()}", offer.critter.rarity.argb, 1.5f, "for ${offer.item}")
        }
        send(s.tradesTo, TradeLines.announce(offer, spot), "trade")
    }

    fun onTradeTaken(critter: Critter) {
        if (!isEnabled()) return
        say("Trade taken: ${critter.name} Shard", ChatFormatting.GRAY)
        send(s.tradesTo, TradeLines.taken(critter), "trade-taken")
    }

    /** A Macaw in plain sight, or the Birdfeeder's line for one (where is null then). One call per minute at most. */
    fun onMacawSpawn(spot: TradeSpot?) {
        if (!isEnabled() || !s.macaw.value()) return
        val now = System.currentTimeMillis()
        if (!testing && now - lastMacaw < MACAW_COOLDOWN_MILLIS) return
        lastMacaw = now
        val where = spot?.let { "${it.biome?.displayName() ?: "Forest"} ${it.x} ${it.y} ${it.z}" }
        banner("MACAW!", MACAW_COLOR, 1.6f, where ?: "somewhere in the Forest")
        say("Macaw spawned!" + (where?.let { " ($it)" } ?: ""), ChatFormatting.GOLD)
        send(s.macawTo, "Macaw spawned!" + (where?.let { " ($it)" } ?: ""), "macaw")
    }

    /** Fires every alert once, with the biome gates lifted, so they can be checked from anywhere. */
    fun test() {
        testing = true
        try {
            for ((boss, stage) in listOf("Gemzie" to Stage.READY, "Wumpa" to Stage.STARTED, "Doomspiral" to Stage.DONE)) {
                encounter(boss, stage, "test", 1.4f)
            }
            milestone("Test: Forest Done!", 1.4f)
            onMacawSpawn(null)
        } finally {
            testing = false
        }
    }

    // ---- internals ----

    private fun birdfeeder(line: String) {
        val m = BIRDFEEDER.matcher(line)
        if (!m.matches()) return
        // A wording this loose matches anything shaped like the sentence, so the species must be one we know.
        val bird = CritterSpecies.byName(m.group(1).trim()) ?: return
        if (bird == CritterSpecies.MACAW) {
            onMacawSpawn(null)
        } else if (s.otherBirds.value() && bird.name in OTHER_BIRDS) {
            banner("${bird.name.uppercase()}!", bird.rarity.argb, 1.4f, "at the Birdfeeder")
        }
    }

    private fun encounter(boss: String, stage: Stage, detail: String, pitch: Float) {
        val on = when (boss) {
            "Gemzie" -> s.gemzie.value()
            "Wumpa" -> s.wumpa.value()
            else -> s.doomspiral.value()
        }
        if (!on || !inItsBiome(boss)) return
        // Gemzie chambers repeat every few minutes and ready/done can be seconds apart, so it skips the cooldown.
        if (boss != "Gemzie" && !testing && onCooldown("$boss:$stage")) return
        val color = when (stage) {
            Stage.READY -> 0xFFFFDD55.toInt()
            Stage.STARTED -> 0xFFFF5555.toInt()
            Stage.DONE -> 0xFF55FF55.toInt()
        }
        banner("${boss.uppercase()} $stage", color, pitch, detail)
        say("${boss.uppercase()} $stage - $detail", ChatFormatting.YELLOW)
        val to = when (boss) {
            "Gemzie" -> s.gemzieTo
            "Wumpa" -> s.wumpaTo
            else -> s.doomspiralTo
        }
        send(to, "$boss ${stage.name.lowercase()}", "encounter")
    }

    /**
     * Each of the three belongs to one biome, and with a biome each only one person is standing in it. Without this gate
     * every client calls every stage. Fails open: when the biome cannot be worked out, the alert fires, because
     * silence on "cannot tell" would look exactly like the mod being broken.
     */
    private fun inItsBiome(boss: String): Boolean {
        if (testing || !s.inBiomeOnly.value()) return true
        val here = SafariBiome.current() ?: return true
        return CritterSpecies.byName(boss)?.biome?.let { it == here } ?: true
    }

    private fun milestone(text: String, pitch: Float) {
        banner(text, 0xFF55FF55.toInt(), pitch, null)
        say(text, ChatFormatting.GREEN)
        send(s.milestonesTo, text, "milestone")
    }

    private fun banner(title: String, argb: Int, pitch: Float, subtitle: String?) {
        fired++
        TitleAlertStack.submit("critter_alerts", title, 60, argb)
        if (subtitle != null) TitleAlertStack.submitSubtitle("critter_alerts", subtitle, 60, 0xFFFFFFFF.toInt())
        // Silent unless asked for: a run fires plenty of these.
        if (s.sound.value()) ModuleSounds.play("minecraft:ui.toast.challenge_complete", 1.0f, pitch)
    }

    private fun say(text: String, style: ChatFormatting) = CritterSafari.say(text, style)

    private fun send(setting: ChoiceSetting, message: String, kind: String) {
        val to = audience(setting)
        diagnostics.event("ALERT kind=$kind to=${to.name.lowercase()} fired=$fired")
        if (to == Audience.NOBODY) return
        posted++
        CritterSafari.post(to, message)
    }

    private fun onCooldown(key: String): Boolean {
        if (testing) return false
        val now = System.currentTimeMillis()
        val previous = lastFired[key]
        if (previous != null && now - previous < STAGE_COOLDOWN_MILLIS) return true
        if (lastFired.size < 64) lastFired[key] = now
        return false
    }

    /** Call every client tick while enabled. Counts only; no chat text, names or coordinates. */
    fun diagnose() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.statusOnChangeBy(20L, { "$fired$posted${CritterSafari.run != null}" }) {
            "active=${isActive()} run=${CritterSafari.run != null} alertsFired=$fired partyPosts=$posted gemzieLeft=$gemzieRemaining"
        }
    }

    companion object {
        private const val GEMZIE_PER_CHAMBER = 3
        private const val STAGE_COOLDOWN_MILLIS = 8_000L
        private const val MACAW_COOLDOWN_MILLIS = 60_000L
        private const val MACAW_COLOR = 0xFFFFAA00.toInt()
        private val OTHER_BIRDS = setOf("Bluebird", "Parakeet")

        /** `A Bluebird was attracted to the Birdfeeder!` / `Two Macaws were attracted...`, anchored so a quote cannot set it off. */
        private val BIRDFEEDER = Pattern.compile("^\\S+ ([A-Za-z ]+?)s? (?:were|was) attracted to the Birdfeeder!$")

        @JvmField
        val INSTANCE = CritterAlertsModule(Settings())
    }
}
