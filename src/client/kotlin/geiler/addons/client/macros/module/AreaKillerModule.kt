package geiler.addons.client.macros.module

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.area.GateArea
import geiler.addons.client.macros.area.GateBounds
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.sim.InstantTransmission
import geiler.addons.client.macros.config.MacroWorkspaceConfig
import geiler.addons.client.macros.input.TeleportDetect
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.killer.TargetPattern
import geiler.addons.client.macros.navigation.PathfindMacro
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.RangeSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The Area Killer's settings, its start/stop switch and its stats card. Turning the module on starts the macro; it turns
 * itself off when the macro stops. The settings are copied into the shared macro configuration every tick; the kill list,
 * the totals and the optional bounds box are saved as their own sections of the Macros workspace file.
 */
class AreaKillerModule private constructor(private val c: Carrier) : Module(
    "Area Killer",
    "Finds the mobs on its kill list in the stretch of tunnel it was started in, walks or teleports to them and kills them, " +
        "and never goes through the gate. Turn it on to start, off to stop; /ga areakiller does the same.",
    Category.AREA_KILLER,
    false,
    *c.all(),
), HudElement {
    private val diagnostics = ModuleDiagnostics(Category.AREA_KILLER, { name() })
    private val config: AreaKillerConfig get() = MacroRuntime.config

    init {
        group(
            SettingGroup("Targets", c.targetPattern, c.viewCone, c.assumeOneTap, c.precomputeNext),
            SettingGroup("Area", c.fromX, c.fromY, c.fromZ, c.toX, c.toY, c.toZ, c.margin),
            SettingGroup("Attack", c.weaponSlot, c.leftCps, c.cpsRandomness, c.sneakChance, c.ungrabMouse),
            SettingGroup("Aim", c.aimHead, c.aimShoulders, c.aimUpper, c.aimCenter, c.aimLower, c.aimSpread),
            SettingGroup(
                "Finisher", c.useBlackHole, c.blackHoleSlot, c.finisherHealth, c.holdBlackHole, c.rightCps, c.switchDelay,
            ),
            SettingGroup(
                "Approach", c.approachDistance, c.approachVariation, c.directApproach, c.turnFirst, c.chaseKnockedBack, c.chaseRange, c.failsafeSeconds,
                c.failsafeDistance,
            ),
            SettingGroup(
                "Travel", c.useTeleport, c.autoItem, c.teleportSlot, c.teleportDistance, c.teleportDelay,
                c.teleportLead, c.hopDrop, c.hopGap, c.useEtherwarp, c.etherRange, c.warpGap, c.etherMinDistance,
            ),
            SettingGroup("Search", c.scanRadius),
            SettingGroup("Display", c.showOverlay, c.showHud, c.showBackground),
        )
        AreaKillerMacro.diagnostics = { diagnostics.event(it) }
        MacroWorkspaceConfig.registerSection(KillList())
        MacroWorkspaceConfig.registerSection(Stats())
        MacroWorkspaceConfig.registerSection(Bounds())
    }

    /** `/areakiller start`: starts the macro, and starts its session again when it is already running, as the source does. */
    fun start() {
        if (isEnabled()) AreaKillerMacro.start() else setEnabled(true)
    }

    fun setMargin(blocks: Int) {
        c.margin.setValue(blocks.toFloat())
        pull()
    }

    fun setWeaponSlot(slot: Int) {
        c.weaponSlot.setValue(slot.toFloat())
        pull()
    }

    fun setOverlay(on: Boolean) {
        c.showOverlay.setValue(on)
        pull()
    }

    /** No gate: the fields are emptied and so is the configuration. */
    fun clearGate() {
        for (field in c.gateFields()) field.setValue("")
        config.gate.from = IntArray(0)
        config.gate.to = IntArray(0)
    }

    /** Takes a gate the command has already checked: into the fields, and into the configuration at once. */
    fun setGate(from: IntArray, to: IntArray) {
        val fields = c.gateFields()
        for (i in 0 until 3) {
            fields[i].setValue(from[i].toString())
            fields[i + 3].setValue(to[i].toString())
        }
        config.gate.from = from
        config.gate.to = to
    }

    fun setScanRadius(blocks: Int) {
        c.scanRadius.setValue(blocks.toFloat())
        pull()
    }

    override fun onEnable() {
        diagnostics.enable(
            "mobs=${config.mobs.size} gate=${if (config.gate.from.size == 3) config.gate.from.joinToString(",") + ".." + config.gate.to.joinToString(",") else "none"} area='${config.paintArea}' " +
                "margin=${c.margin.value().toInt()} weaponSlot=${c.weaponSlot.value().toInt()} teleport=${c.useTeleport.rawValue()} " +
                "etherwarp=${c.useEtherwarp.rawValue()} blackHole=${c.useBlackHole.rawValue()} scanRadius=${c.scanRadius.value().toInt()} " +
                "hopDrop=${c.hopDrop.value()} gaps=${c.hopGap.value().toInt()}/${c.warpGap.value().toInt()}",
        )
        // A saved "on" from the last session has nothing to hunt in yet; the tick turns it off again.
        if (Minecraft.getInstance().player == null) return
        pull()
        AreaKillerMacro.start()
    }

    override fun onDisable() {
        AreaKillerMacro.stop("by user")
        diagnostics.disable()
    }

    override fun isActive(): Boolean = AreaKillerMacro.isRunning()

    /** Called every client tick: copies the settings into the shared configuration and follows the macro's state. */
    fun tick() {
        pull()
        if (!isEnabled()) return
        diagnostics.advance()
        if (!AreaKillerMacro.isRunning()) {
            // The macro stopped by itself, or refused to start: the switch shows it.
            setEnabled(false)
            return
        }
        diagnostics.status { suppressed ->
            "running=true state='${AreaKillerMacro.hudLine()}' sessionMillis=${AreaKillerMacro.sessionMillis()} " +
                "sessionKills=${AreaKillerMacro.sessionKills()} totalKills=${config.totalKills} suppressedEvents=$suppressed"
        }
    }

    private fun pull() {
        val k = config
        // The gate is locked while the macro runs, and a gate that is not whole numbers on one straight row is not taken.
        if (!AreaKillerMacro.isRunning()) {
            val raw = c.gateFields().map { it.value().trim() }
            if (raw == OLD_DEFAULT_GATE) {
                // The wall of the source's default, saved in the fields of a profile that never set one: not a gate anybody chose.
                clearGate()
            } else if (raw.all { it.isEmpty() }) {
                k.gate.from = IntArray(0)
                k.gate.to = IntArray(0)
            }
            val typed = c.gateFields().map { it.value().trim().toIntOrNull() }
            if (typed.none { it == null }) {
                val from = intArrayOf(typed[0]!!, typed[1]!!, typed[2]!!)
                val to = intArrayOf(typed[3]!!, typed[4]!!, typed[5]!!)
                if (from[0] == to[0] || from[2] == to[2]) {
                    k.gate.from = from
                    k.gate.to = to
                }
            }
        }
        k.margin = c.margin.value().toInt()
        k.weaponSlot = c.weaponSlot.value().toInt()
        k.leftCpsMin = c.leftCps.low().toDouble()
        k.leftCpsMax = c.leftCps.high().toDouble()
        k.cpsRandomnessMin = c.cpsRandomness.low().toDouble()
        k.cpsRandomness = c.cpsRandomness.high().toDouble()
        k.sneakChanceMin = c.sneakChance.low().toDouble()
        k.sneakChance = c.sneakChance.high().toDouble()
        k.ungrabMouse = c.ungrabMouse.rawValue()
        k.aimHead = c.aimHead.rawValue()
        k.aimShoulders = c.aimShoulders.rawValue()
        k.aimUpper = c.aimUpper.rawValue()
        k.aimCenter = c.aimCenter.rawValue()
        k.aimLower = c.aimLower.rawValue()
        k.aimSpreadMin = c.aimSpread.low().toDouble()
        k.aimSpread = c.aimSpread.high().toDouble()
        k.useBlackHole = c.useBlackHole.rawValue()
        k.blackHoleSlot = c.blackHoleSlot.value().toInt()
        k.finisherHealthPercent = c.finisherHealth.value().toDouble()
        k.holdBlackHole = c.holdBlackHole.rawValue()
        k.rightCpsMin = c.rightCps.low().toDouble()
        k.rightCpsMax = c.rightCps.high().toDouble()
        k.switchDelayMinMillis = c.switchDelay.low().toInt()
        k.switchDelayMaxMillis = c.switchDelay.high().toInt()
        k.approachDistance = c.approachDistance.value().toDouble()
        k.approachVariationMin = c.approachVariation.low().toDouble()
        k.approachVariation = c.approachVariation.high().toDouble()
        k.chaseKnockedBack = c.chaseKnockedBack.rawValue()
        k.chaseRange = c.chaseRange.value().toDouble()
        k.failsafeSeconds = c.failsafeSeconds.value().toDouble()
        k.failsafeDistance = c.failsafeDistance.value().toDouble()
        // A trip of the Pathfinder alone has its own Travel settings (see PathfinderModule): whoever runs owns them.
        if (!(PathfindMacro.isRunning() && !AreaKillerMacro.isRunning())) {
            k.scanRadius = c.scanRadius.value().toInt()
            k.useTeleport = c.useTeleport.rawValue()
            k.teleportSlot = c.teleportSlot.value().toInt()
            k.teleportDistance = c.teleportDistance.value().toDouble()
            // In singleplayer the shovel of the Etherwarp Sim is the item, and what it carries is the item's, not a setting.
            if (EtherwarpSimModule.INSTANCE.isActive()) {
                k.teleportDistance = InstantTransmission.distance(EtherwarpSimModule.INSTANCE.tuners())
            }
            k.teleportDelayMinMillis = c.teleportDelay.low().toInt()
            k.teleportDelayMaxMillis = c.teleportDelay.high().toInt()
            k.teleportLeadTicks = c.teleportLead.value().toInt()
            k.hopDrop = c.hopDrop.value().toDouble()
            k.hopGapTicks = c.hopGap.value().toInt()
            k.warpGapTicks = c.warpGap.value().toInt()
            k.useEtherwarp = c.useEtherwarp.rawValue()
            k.etherRange = c.etherRange.value().toDouble()
            k.etherMinDistance = c.etherMinDistance.value().toDouble()
            if (c.autoItem.rawValue() && isEnabled()) {
                val player = Minecraft.getInstance().player
                if (player != null) TeleportDetect.apply(player, k)?.let { diagnostics.event(it) }
            }
        }
        k.targetPattern = TargetPattern.valueOf(c.targetPattern.value())
        k.viewCone = c.viewCone.value().toDouble()
        k.turnFirstDegrees = c.turnFirst.value().toDouble()
        k.directApproach = c.directApproach.rawValue()
        k.assumeOneTap = c.assumeOneTap.rawValue()
        k.precomputeNext = c.precomputeNext.rawValue()
        k.showOverlay = c.showOverlay.rawValue()
        k.showHud = c.showHud.rawValue()
        k.normalize()
    }

    override fun settingTooltip(setting: Setting): String? = TOOLTIPS[setting.name()]

    // ------------------------------------------------------------------ the stats card

    /** What the card says now, one line each: the title with the status, then the session, then everything together. */
    private fun lines(): Array<String> {
        val status = AreaKillerMacro.hudLine()
        if (status == null && PathfindMacro.isRunning()) return arrayOf("Pathfinder", PathfindMacro.hudLine() ?: "")
        val millis = AreaKillerMacro.sessionMillis()
        val kills = AreaKillerMacro.sessionKills()
        return arrayOf(
            "Area Killer",
            status ?: "Stopped",
            "Session: " + AreaKillerMacro.clock(millis),
            "Kills: $kills",
            "Kills / hour: " + (if (millis < 30_000L) "-" else String.format("%.0f", kills * 3_600_000.0 / millis)),
            "Total kills: " + String.format("%,d", config.totalKills),
            "Total time: " + AreaKillerMacro.clock(config.totalRunMillis),
        )
    }

    private fun sampleLines(): Array<String> = arrayOf(
        "Area Killer", "Stopped", "Session: 00:12:34", "Kills: 148", "Kills / hour: 707", "Total kills: 12,430", "Total time: 05:21:09",
    )

    override fun id(): String = "area_killer_stats"

    override fun displayName(): String = "Area Killer Stats"

    private fun width(font: Font, lines: Array<String>): Int {
        var width = 100
        for (line in lines) width = Math.max(width, font.width(line))
        return width + 10
    }

    private fun height(font: Font, lines: Array<String>): Int = lines.size * (font.lineHeight + 1) + 8

    override fun width(font: Font?): Int = width(font!!, lines())

    override fun height(font: Font?): Int = height(font!!, lines())

    override fun previewWidth(font: Font?): Int = width(font!!, if (visible()) lines() else sampleLines())

    override fun previewHeight(font: Font?): Int = height(font!!, if (visible()) lines() else sampleLines())

    /** On the screen while the area killer or `/pathfind` runs, with no screen open. */
    override fun visible(): Boolean =
        (AreaKillerMacro.isRunning() || PathfindMacro.isRunning()) && config.showHud &&
            Minecraft.getInstance().player != null && Minecraft.getInstance().gui.screen() == null

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = renderLines(graphics, font, x, y, lines())

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        renderLines(graphics, font, x, y, if (visible()) lines() else sampleLines())

    /** The panel of the other trackers: a rounded background (optional) and plain lines, the title in the secondary colour. */
    private fun renderLines(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: Array<String>) {
        if (c.showBackground.rawValue()) {
            GuiTheme.roundedRect(graphics, x, y, width(font, lines), height(font, lines), 3, GuiTheme.PANEL_TOP)
        }
        var textY = y + 4
        lines.forEachIndexed { index, line ->
            graphics.text(font, line, x + 5, textY, if (index == 0) GuiTheme.TEXT_SECONDARY else GuiTheme.TEXT_PRIMARY)
            textY += font.lineHeight + 1
        }
    }

    // ------------------------------------------------------------------ what is saved beside the settings

    private inner class KillList : MacroWorkspaceConfig.Section {
        override fun name(): String = "killList"

        override fun save(): JsonElement {
            val array = JsonArray()
            for (entry in config.mobs) array.add(entry)
            return array
        }

        override fun load(element: JsonElement?) {
            config.mobs.clear()
            if (element == null || !element.isJsonArray) return
            for (entry in element.asJsonArray) {
                if (entry.isJsonPrimitive && entry.asJsonPrimitive.isString) config.addMob(entry.asString)
            }
        }
    }

    private inner class Stats : MacroWorkspaceConfig.Section {
        override fun name(): String = "stats"

        override fun save(): JsonElement {
            val json = JsonObject()
            json.addProperty("totalKills", config.totalKills)
            json.addProperty("totalRunMillis", config.totalRunMillis)
            return json
        }

        override fun load(element: JsonElement?) {
            config.totalKills = 0L
            config.totalRunMillis = 0L
            val json = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            config.totalKills = long(json, "totalKills")
            config.totalRunMillis = long(json, "totalRunMillis")
            config.normalize()
        }

        private fun long(json: JsonObject, key: String): Long {
            val value = json.get(key)
            return if (value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else 0L
        }
    }

    /** The optional box the macro may never leave. It has no control: it is set by hand in the file, as in the source. */
    private inner class Bounds : MacroWorkspaceConfig.Section {
        override fun name(): String = "gate"

        override fun save(): JsonElement {
            val json = JsonObject()
            val bounds = config.bounds
            if (bounds == null) {
                json.add("bounds", JsonNull.INSTANCE)
            } else {
                val box = JsonObject()
                box.add("min", ints(bounds.min))
                box.add("max", ints(bounds.max))
                json.add("bounds", box)
            }
            return json
        }

        override fun load(element: JsonElement?) {
            config.bounds = null
            val box = element?.takeIf { it.isJsonObject }?.asJsonObject?.get("bounds")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return
            val min = ints(box.get("min")) ?: return
            val max = ints(box.get("max")) ?: return
            config.bounds = GateBounds(min, max)
        }

        private fun ints(values: IntArray): JsonArray {
            val array = JsonArray()
            for (value in values) array.add(value)
            return array
        }

        private fun ints(element: JsonElement?): IntArray? {
            if (element == null || !element.isJsonArray || element.asJsonArray.size() != 3) return null
            if (element.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isNumber }) return null
            return IntArray(3) { element.asJsonArray[it].asInt }
        }
    }

    /** All the settings, created before the module so they can be handed to its constructor. */
    private class Carrier {
        val targetPattern = ChoiceSetting(
            "Target pattern", TargetPattern.HUMAN.name, TargetPattern.values().associate { it.name to it.label },
            *TargetPattern.values().map { it.name }.toTypedArray(),
        )
        val viewCone = NumberSetting("View cone", "View cone (degrees)", 0.0f, 180.0f, 70.0f, true)
        val turnFirst = NumberSetting("Turn before walking", "Turn before walking (degrees)", 0.0f, 180.0f, 100.0f, true)
        val directApproach = BooleanSetting("Run straight at mobs", true)
        val assumeOneTap = BooleanSetting("Assume one tap", false)
        val precomputeNext = BooleanSetting("Plan the next mob while fighting", true)

        val fromX = TextSetting("Gate from x", "", 9)
        val fromY = TextSetting("Gate from y", "", 9)
        val fromZ = TextSetting("Gate from z", "", 9)
        val toX = TextSetting("Gate to x", "", 9)
        val toY = TextSetting("Gate to y", "", 9)
        val toZ = TextSetting("Gate to z", "", 9)
        val margin = NumberSetting("Safety margin", 0.0f, 3.0f, 1.0f, true)

        val weaponSlot = NumberSetting("Weapon slot", 1.0f, 9.0f, 1.0f, true)
        val leftCps = RangeSetting("Left CPS", "Left CPS min", "Left CPS max", 1.0f, 20.0f, 8.0f, 12.0f)
        val cpsRandomness = RangeSetting("Click randomness", "Click randomness min", "Click randomness", 0.0f, 100.0f, 36.0f, 60.0f)
        val sneakChance = RangeSetting("Sneak chance per kill", "Sneak chance per kill min", "Sneak chance per kill", 0.0f, 100.0f, 15.0f, 25.0f)
        val ungrabMouse = BooleanSetting("Ungrab mouse while running", true)
        val aimHead = BooleanSetting("Aim zone: Head", false)
        val aimShoulders = BooleanSetting("Aim zone: Shoulders", true)
        val aimUpper = BooleanSetting("Aim zone: Upper", false)
        val aimCenter = BooleanSetting("Aim zone: Center", true)
        val aimLower = BooleanSetting("Aim zone: Lower", false)
        val aimSpread = RangeSetting("Aim spread", "Aim spread min", "Aim spread", 0.0f, 100.0f, 36.0f, 60.0f)

        val useBlackHole = BooleanSetting("Black Hole", true)
        val blackHoleSlot = NumberSetting("Black Hole slot", 1.0f, 9.0f, 3.0f, true)
        val finisherHealth = NumberSetting("Black Hole below health", "Black Hole below health (%)", 1.0f, 50.0f, 10.0f)
        val holdBlackHole = BooleanSetting("Hold right click", false)
        val rightCps = RangeSetting("Black Hole CPS", "Black Hole CPS min", "Black Hole CPS max", 1.0f, 20.0f, 6.0f, 10.0f)
        val switchDelay = RangeSetting("Item switch delay (ms)", "Item switch delay min", "Item switch delay max", 0.0f, 1000.0f, 100.0f, 200.0f, true)

        val approachDistance = NumberSetting("Walk up to the mob until", 0.3f, 3.5f, 1.2f)
        val approachVariation = RangeSetting("Approach variation", "Approach variation min", "Approach variation", 0.0f, 1.5f, 0.24f, 0.4f)
        val chaseKnockedBack = BooleanSetting("Run after knocked-out mobs", true)
        val chaseRange = NumberSetting("Longest chase", 3.0f, 16.0f, 9.0f)
        val failsafeSeconds = NumberSetting("Failsafe seconds", 0.0f, 60.0f, 8.0f)
        val failsafeDistance = NumberSetting("Failsafe unless this much closer", 0.5f, 10.0f, 2.0f)
        val scanRadius = NumberSetting("Scan radius", 0.0f, 1024.0f, 128.0f, true)

        val useTeleport = BooleanSetting("Teleport jumps", true)
        val autoItem = BooleanSetting("Auto detect item", true)
        val teleportSlot = NumberSetting("Teleport slot", 1.0f, 9.0f, 2.0f, true)
        val teleportDistance = NumberSetting("Jump distance", 4.0f, 30.0f, 12.0f)
        val teleportDelay = RangeSetting("Teleport pause (ms)", "Teleport pause min", "Teleport pause max", 0.0f, 1000.0f, 120.0f, 220.0f, true)
        val teleportLead = NumberSetting("Fall lead", "Fall lead (ticks)", 0.0f, 6.0f, 2.0f, true)
        val hopDrop = NumberSetting("Max teleport drop", 1.0f, 40.0f, 4.0f)
        val hopGap = NumberSetting("Hop gap", "Hop gap (ticks)", 0.0f, 20.0f, 3.0f, true)
        val warpGap = NumberSetting("Etherwarp gap", "Etherwarp gap (ticks)", 0.0f, 40.0f, 8.0f, true)
        val useEtherwarp = BooleanSetting("Etherwarp", true)
        val etherRange = NumberSetting("Etherwarp range", 20.0f, 60.0f, 60.0f)
        val etherMinDistance = NumberSetting("Etherwarp only longer than", 8.0f, 58.0f, 20.0f)

        val showOverlay = BooleanSetting("World overlay", "Gate, route and target in the world", true)
        val showHud = BooleanSetting("Stats card", true)
        val showBackground = BooleanSetting("Card background", true)

        fun gateFields(): List<TextSetting> = listOf(fromX, fromY, fromZ, toX, toY, toZ)

        fun all(): Array<Setting> = arrayOf(
            targetPattern, viewCone, turnFirst, directApproach, assumeOneTap, precomputeNext, fromX, fromY, fromZ, toX, toY, toZ, margin,
            weaponSlot, leftCps, cpsRandomness, sneakChance, ungrabMouse,
            aimHead, aimShoulders, aimUpper, aimCenter, aimLower, aimSpread,
            useBlackHole, blackHoleSlot, finisherHealth, holdBlackHole, rightCps, switchDelay,
            approachDistance, approachVariation, chaseKnockedBack, chaseRange, failsafeSeconds, failsafeDistance, scanRadius,
            useTeleport, autoItem, teleportSlot, teleportDistance, teleportDelay, teleportLead, hopDrop, hopGap, warpGap,
            useEtherwarp, etherRange, etherMinDistance, showOverlay, showHud, showBackground,
        )
    }

    companion object {
        private val OLD_DEFAULT_GATE = listOf("-120", "206", "-63", "-120", "206", "-56")


        private val TOOLTIPS: Map<String, String> = mapOf(
            "Target pattern" to "How the next mob is picked when several are in range: " +
                TargetPattern.values().joinToString(" ") { "${it.label}: ${it.description}" },
            "View cone" to "While any mob is inside this many degrees either side of where you look, the next target is picked from those alone, and mobs behind you wait. A person goes for what is in front of them; turning round for the nearest mob behind every time is what makes a macro look like one. 0 is off.",
            "Run straight at mobs" to "While the target is in sight and the ground between is a plain walk, the macro runs straight at it with the view on it, working out where it is every tick, instead of along a route that is half a second old. Where the ground is not plain (a drop, a wall, a step too high) the route planner takes over.",
            "Turn before walking" to "When the view is further than this many degrees from the way the macro is going, it lets go of the movement keys until the view has turned (at most a second), instead of back-running or strafing at a mob behind it. Between 30 degrees and this the macro runs on along the view with W alone while the view turns. 0 is off.",
            "Assume one tap" to "A hit that would kill the mob counts as a kill at once, and the macro goes on to the next target without waiting for it to die on screen. Meant for the 1 Tap sim: the estimate is your attack damage against the mob's health, with no armor, enchantment or cooldown. Not used for a server's own creatures.",
            "Plan the next mob while fighting" to "While a mob is being fought, the route to the next one is worked out already, so the walk to it starts the moment this one is dead. The next mob and its route are drawn in the world overlay.",
            "Gate from x" to "The two ends of the gate, both included. The gate has to be one straight row along x or z; a gate that is not is not taken. Locked while the macro runs.",
            "Safety margin" to "How many blocks around the gate are shut as well, so the macro never brushes past it.",
            "Left CPS" to "Clicks per second: every click draws its own rate between the two ends.",
            "Click randomness" to "How far the clicking strays from an even draw between the bounds: a rhythm that wanders through the range, and an occasional late click.",
            "Sneak chance per kill" to "The chance, drawn once for every mob when the fight with it begins, that the player is crouched the whole of that kill.",
            "Ungrab mouse while running" to "Lets go of the mouse while a macro runs, so it can be used for other windows; the game does not pause when it loses focus.",
            "Aim spread" to "How much of an aim zone is used. At 0 the crosshair goes to the middle of it every time.",
            "Black Hole" to "Below the health share a mob is finished off with the Black Hole instead of the weapon: right click, in the air, until the mob is gone.",
            "Hold right click" to "The Black Hole is used with the right button held down, instead of clicked at the Black Hole click rate.",
            "Item switch delay (ms)" to "The pause before the held item is changed, for every change the macro makes: drawn between the two each time.",
            "Walk up to the mob until" to "How close the player gets to a mob, eye to hitbox, in blocks. A mob that can be hit from further away is hit on the way in.",
            "Approach variation" to "How far either way, in blocks, the distance drawn for each mob may be from the approach distance.",
            "Run after knocked-out mobs" to "Run after a mob that was knocked out of reach, aiming at it, instead of planning a route to it.",
            "Longest chase" to "How far away a mob may be and still be run after directly, in blocks.",
            "Failsafe seconds" to "When the player has not come the failsafe distance closer to the target in this many seconds, while it cannot be hit, another target is chosen. Under 2 is off.",
            "Teleport jumps" to "Cover distance on the path in jumps with the teleport item.",
            "Scan radius" to "How far the Area Killer looks for mobs, in blocks (up to 1024, 0 for no limit). Routes are planned within 96 blocks of the player; mobs further are walked toward. Mobs further than the server sends to the client are not seen whatever this is. Its own setting: the Pathfinder's scan radius counts only for a /pathfind trip.",
            "Max teleport drop" to "The furthest a teleport may leave you above the ground it lands on, in blocks. A hop that would carry you over an edge into a bigger drop is not taken.",
            "Hop gap" to "The least time between two plain teleport clicks, in ticks, with up to two more at random. 0 is none beyond the teleport pause.",
            "Etherwarp gap" to "The least time between two etherwarps, in ticks, with up to two more at random: a warp is not spammed.",
            "Auto detect item" to "Find the Aspect of the End / Void (or any etherwarp item) on the hotbar and take its slot and how far it goes, tuners included, from the item. Off: the slot and distances below are used.",
            "Teleport pause (ms)" to "The pause after a jump or warp has landed, before the next one is aimed and fired, drawn between the two for every one.",
            "Fall lead" to "Ticks of lead on top of the ping when working out where the player will be when the jump goes off.",
            "Etherwarp" to "The teleport item's second mode, sneak and right click: a jump onto the block looked at, up to the range. Needs Teleport jumps.",
            "Etherwarp only longer than" to "A route shorter than this is walked or jumped, not warped. Kept at least 2 blocks under the range.",
            "Stats card" to "A card with the session's time, kills and kills an hour, and the totals of all sessions. Move it with Move Elements.",
        )

        @JvmField
        val INSTANCE = AreaKillerModule(Carrier())
    }
}
