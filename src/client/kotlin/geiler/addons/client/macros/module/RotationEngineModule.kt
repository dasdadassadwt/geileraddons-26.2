package geiler.addons.client.macros.module

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.macros.config.MacroWorkspaceConfig
import geiler.addons.client.macros.rotation.RotationConfig
import geiler.addons.client.macros.rotation.Rotator
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.RangeSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Util

/**
 * How the view turns, for every macro: one engine with speed and time ranges, reaction delay, humanization, presets and
 * tuning. The settings here are the ones the Click GUI edits; [pull] copies them into the [RotationConfig.current]
 * settings the frame pump reads, and loading a preset writes a preset's settings back into them. Saved presets, the
 * distance curve and the active preset are saved by [Persistence] in the Macros workspace file.
 */
class RotationEngineModule private constructor(private val c: Carrier) : Module(
    "Rotation Engine",
    "How the view turns for every macro: one engine with speed and turn-time ranges, reaction delay, curves, " +
        "overshoots, hesitations, aim drift, distance-based scaling, presets and instant turns.",
    Category.ENGINE,
    false,
    *c.all(),
) {
    private val config = RotationConfig()
    private val diagnostics = ModuleDiagnostics(Category.ENGINE, { name() })
    private val graph = DistanceGraphPreview({ config.settings }, { c.distance.value() }, GROUP_DISTANCE)
    private val instantRow = HoldSwitchPreview(
        { c.instant.rawValue() }, { value -> setInstant(value) }, GROUP_INSTANT,
        "On - the camera jumps.", "Off - rotations are animated.",
    )
    private var diagnosticsStarted = false
    private var appliedPreset = NONE
    private var deleteArmedUntil = 0L

    init {
        RotationConfig.use(config)
        MacroWorkspaceConfig.registerSection(Persistence())
        Rotator.diagnostics = { line -> if (diagnosticsStarted) diagnostics.event(line) }
    }

    /** The settings in use, as the frame pump reads them. */
    fun settings(): RotationConfig.Settings = config.settings

    override fun showsToggleControl(): Boolean = false

    override fun showsKeybindControl(): Boolean = false

    /** Always in force: this module has no switch, like General. */
    override fun isEnabled(): Boolean = true

    override fun extraPreviews(): List<ModulePreview> = listOf(graph, instantRow)

    private var cachedGroups: List<SettingGroup>? = null

    /** Built once, so the layout keeps one identity per section. */
    override fun groups(): List<SettingGroup> = cachedGroups ?: buildGroups().also { cachedGroups = it }

    private fun buildGroups(): List<SettingGroup> {
        val groups = ArrayList<SettingGroup>()
        groups.add(SettingGroup("Presets", c.preset, c.presetName, c.saveAction, c.deleteAction))
        groups.add(SettingGroup("Speed and timing", c.pace, c.time, c.reaction))
        groups.add(SettingGroup("Human effects", c.humanized, c.overshootChance, c.overshootSize, c.curveStrength, c.speedNoise, c.pauses, c.wander))
        groups.add(SettingGroup.switched("Distance-Based Scaling", c.distance, c.resetDistance))
        groups.add(SettingGroup("Advanced", c.vertical, c.intensity, c.newTurn, c.resetTuning))
        groups.add(SettingGroup(GROUP_INSTANT, null, false, emptyList(), emptyList()).keyed("instant"))
        return groups
    }

    override fun settingTooltip(setting: Setting): String? = TOOLTIPS[setting.name()]

    /** Called every client tick: copies the edited settings into the live ones and applies a newly chosen preset. */
    fun tick() {
        val chosen = c.preset.value()
        if (chosen != appliedPreset) applyChosenPreset(chosen)
        pull()
        if (!diagnosticsStarted) {
            diagnosticsStarted = true
            diagnostics.enable(summary())
        }
        diagnostics.advance()
        diagnostics.status { suppressed -> summary() + " suppressedEvents=$suppressed" }
    }

    private fun summary(): String {
        val s = config.settings
        return "humanized=${s.humanized} instant=${s.instant} preset='${config.preset}' " +
            "pace=${fmt(s.paceMin)}..${fmt(s.paceMax)} time=${fmt(s.timeMinMs)}..${fmt(s.timeMaxMs)}ms " +
            "reaction=${fmt(s.reactionMinMs)}..${fmt(s.reactionMaxMs)}ms intensity=${fmt(s.effectIntensity)} " +
            "vertical=${fmt(s.verticalMovement)} overshoot=${fmt(s.overshootChanceMin)}..${fmt(s.overshootChance)}%/" +
            "${fmt(s.overshootSizeMin)}..${fmt(s.overshootSize)}% curve=${fmt(s.curveStrengthMin)}..${fmt(s.curveStrength)} " +
            "noise=${fmt(s.speedNoiseMin)}..${fmt(s.speedNoise)} pauses=${fmt(s.pausesPerSecondMin)}..${fmt(s.pausesPerSecond)}/s " +
            "wander=${fmt(s.aimWanderMin)}..${fmt(s.aimWander)} newTurn=${fmt(s.newTurnDegrees)} distanceScaling=${s.distanceScaling}"
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    private fun setInstant(value: Boolean) {
        c.instant.setValue(value)
        pull()
        diagnostics.event("instant=$value")
    }

    /** The settings pages' values into the settings in use. */
    private fun pull() {
        val s = config.settings
        s.humanized = c.humanized.rawValue()
        s.paceMin = c.pace.low().toDouble()
        s.paceMax = c.pace.high().toDouble()
        s.timeMinMs = c.time.low().toDouble()
        s.timeMaxMs = c.time.high().toDouble()
        s.reactionMinMs = c.reaction.low().toDouble()
        s.reactionMaxMs = c.reaction.high().toDouble()
        s.effectIntensity = c.intensity.value().toDouble()
        s.verticalMovement = c.vertical.value().toDouble()
        s.distanceScaling = c.distance.rawValue()
        s.overshootChanceMin = c.overshootChance.low().toDouble()
        s.overshootChance = c.overshootChance.high().toDouble()
        s.overshootSizeMin = c.overshootSize.low().toDouble()
        s.overshootSize = c.overshootSize.high().toDouble()
        s.curveStrengthMin = c.curveStrength.low().toDouble()
        s.curveStrength = c.curveStrength.high().toDouble()
        s.speedNoiseMin = c.speedNoise.low().toDouble()
        s.speedNoise = c.speedNoise.high().toDouble()
        s.pausesPerSecondMin = c.pauses.low().toDouble()
        s.pausesPerSecond = c.pauses.high().toDouble()
        s.aimWanderMin = c.wander.low().toDouble()
        s.aimWander = c.wander.high().toDouble()
        s.newTurnDegrees = c.newTurn.value().toDouble()
        s.instant = c.instant.rawValue()
        s.normalize()
    }

    /** A saved set of settings into the settings pages. */
    private fun push(s: RotationConfig.Settings) {
        c.humanized.setValue(s.humanized)
        c.pace.set(s.paceMin.toFloat(), s.paceMax.toFloat())
        c.time.set(s.timeMinMs.toFloat(), s.timeMaxMs.toFloat())
        c.reaction.set(s.reactionMinMs.toFloat(), s.reactionMaxMs.toFloat())
        c.intensity.setValue(s.effectIntensity.toFloat())
        c.vertical.setValue(s.verticalMovement.toFloat())
        c.distance.setValue(s.distanceScaling)
        c.overshootChance.set(s.overshootChanceMin.toFloat(), s.overshootChance.toFloat())
        c.overshootSize.set(s.overshootSizeMin.toFloat(), s.overshootSize.toFloat())
        c.curveStrength.set(s.curveStrengthMin.toFloat(), s.curveStrength.toFloat())
        c.speedNoise.set(s.speedNoiseMin.toFloat(), s.speedNoise.toFloat())
        c.pauses.set(s.pausesPerSecondMin.toFloat(), s.pausesPerSecond.toFloat())
        c.wander.set(s.aimWanderMin.toFloat(), s.aimWander.toFloat())
        c.newTurn.setValue(s.newTurnDegrees.toFloat())
        c.instant.setValue(s.instant)
    }

    /** `None` keeps the settings as they are; a name loads that preset's settings. */
    private fun applyChosenPreset(chosen: String) {
        appliedPreset = chosen
        if (chosen == NONE) {
            config.preset = ""
            diagnostics.event("preset=none settings kept")
            return
        }
        val preset = config.find(chosen) ?: return
        config.preset = preset.name
        config.settings = preset.settings.copy()
        push(config.settings)
        diagnostics.event("preset loaded name='${preset.name}' builtIn=${config.isBuiltIn(preset.name)}")
    }

    private fun rebuildPresetChoices() {
        c.preset.setChoices(null, NONE, *config.presetNames().toTypedArray())
        val value = if (config.preset.isEmpty()) NONE else config.preset
        c.preset.setValue(if (value in c.preset.choices()) value else NONE)
        appliedPreset = c.preset.value()
    }

    private fun savePreset() {
        pull()
        config.saveAs(c.presetName.value())
        rebuildPresetChoices()
        diagnostics.event("preset saved name='${config.preset}' count=${config.presets.size}")
        message("Saved preset '${config.preset}'.")
    }

    /** First click arms the button for three seconds; the second click inside that time deletes the chosen preset. */
    private fun deletePreset() {
        val chosen = config.find(config.preset)
        if (chosen == null) {
            message("No preset is chosen.")
            return
        }
        if (config.isBuiltIn(chosen.name)) {
            message("'${chosen.name}' is built in and cannot be deleted. Save your own settings under another name.")
            return
        }
        val now = Util.getMillis()
        if (now > deleteArmedUntil) {
            deleteArmedUntil = now + DELETE_CONFIRM_MILLIS
            message("Click Delete preset again within 3 seconds to delete '${chosen.name}'.")
            return
        }
        deleteArmedUntil = 0L
        val name = chosen.name
        config.deletePreset()
        rebuildPresetChoices()
        diagnostics.event("preset deleted name='$name' count=${config.presets.size}")
        message("Deleted preset '$name'.")
    }

    private fun resetTuning() {
        c.intensity.reset()
        c.vertical.reset()
        pull()
    }

    private fun resetDistance() {
        config.settings.resetDistance()
    }

    private fun message(text: String) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[Rotation Engine] $text"))
    }

    /** Everything that is not a plain setting: the saved presets, the active preset and the distance curve. */
    private inner class Persistence : MacroWorkspaceConfig.Section {
        override fun name(): String = "rotation"

        override fun save(): JsonElement {
            val json = JsonObject()
            val presets = JsonArray()
            for (preset in config.presets) {
                val entry = JsonObject()
                entry.addProperty("name", preset.name)
                entry.add("settings", GSON.toJsonTree(preset.settings))
                presets.add(entry)
            }
            json.add("presets", presets)
            json.addProperty("preset", config.preset)
            json.add("distancePoints", GSON.toJsonTree(config.settings.distancePoints))
            return json
        }

        override fun load(element: JsonElement?) {
            config.presets.clear()
            config.preset = ""
            config.settings.distancePoints = RotationConfig.defaultDistancePoints()
            val json = element?.takeIf { it.isJsonObject }?.asJsonObject
            if (json != null) {
                try {
                    json.get("presets")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { entry ->
                        val name = entry.asJsonObject.get("name")?.asString ?: return@forEach
                        val settings = GSON.fromJson(entry.asJsonObject.get("settings"), RotationConfig.Settings::class.java)
                            ?: return@forEach
                        config.presets.add(RotationConfig.Preset(name, settings))
                    }
                    json.get("distancePoints")?.takeIf { it.isJsonArray }?.let {
                        val points = GSON.fromJson(it, Array<DoubleArray>::class.java)
                        if (points != null) config.settings.distancePoints = points.toMutableList()
                    }
                    config.preset = json.get("preset")?.asString ?: ""
                } catch (error: RuntimeException) {
                    GeilerAddons.LOGGER.warn("Ignoring unreadable rotation presets in the Macros workspace config", error)
                    config.presets.clear()
                    config.preset = ""
                }
            }
            config.normalize()
            rebuildPresetChoices()
        }
    }

    /** All the settings, created before the module so they can be handed to its constructor. */
    private class Carrier {
        val preset = ChoiceSetting("Preset", NONE, NONE)
        val presetName = TextSetting("Preset name", "Preset name", "", 24)
        val humanized = BooleanSetting("Humanized Rotations", true)
        val pace = RangeSetting("Speed", "Speed min", "Speed max", 0.25f, 3.0f, 0.8f, 1.2f)
        val time = RangeSetting("Turn time (ms)", "Turn time min", "Turn time max", 30.0f, 3000.0f, 90.0f, 650.0f, true)
        val reaction = RangeSetting("Reaction delay (ms)", "Reaction min", "Reaction max", 0.0f, 800.0f, 70.0f, 230.0f, true)
        val intensity = NumberSetting("Effect intensity", 0.0f, 2.0f, 1.0f)
        val vertical = NumberSetting("Vertical movement", 0.0f, 2.0f, 1.0f)
        val distance = BooleanSetting("Distance-based scaling", "On", false)
        val overshootChance = RangeSetting("Overshoot chance", "Overshoot chance min", "Overshoot chance", 0.0f, 100.0f, 15.0f, 25.0f, true)
        val overshootSize = RangeSetting("Overshoot size", "Overshoot size min", "Overshoot size", 0.0f, 20.0f, 3.6f, 6.0f)
        val curveStrength = RangeSetting("Curve strength", "Curve strength min", "Curve strength", 0.0f, 3.0f, 0.6f, 1.0f)
        val speedNoise = RangeSetting("Speed variation", "Speed variation min", "Speed variation", 0.0f, 2.0f, 0.6f, 1.0f)
        val pauses = RangeSetting("Hesitations per second", "Hesitations per second min", "Hesitations per second", 0.0f, 3.0f, 0.3f, 0.5f)
        val wander = RangeSetting("Aim drift", "Aim drift min", "Aim drift", 0.0f, 0.5f, 0.05f, 0.08f)
        val newTurn = NumberSetting("New turn above", 0.5f, 30.0f, 10.0f)
        val instant = BooleanSetting("Instant Rotations", false)

        val saveAction = ModuleAction("Save as...", "Saves the settings in use under the name above. A blank name becomes Preset N; an existing name is overwritten.") { INSTANCE.savePreset() }
        val deleteAction = ModuleAction("Delete preset", "Deletes the chosen saved preset after a second click. Built-in presets stay. The settings in use are kept.") { INSTANCE.deletePreset() }
        val resetTuning = ModuleAction("Reset tuning", null) { INSTANCE.resetTuning() }
        val resetDistance = ModuleAction("Reset distance curve", null) { INSTANCE.resetDistance() }

        fun all(): Array<Setting> = arrayOf<Setting>(
            preset, presetName, humanized, pace, time, reaction, intensity, vertical,
            distance, overshootChance, overshootSize, curveStrength, speedNoise, pauses, wander, newTurn, instant,
            saveAction, deleteAction, resetTuning, resetDistance,
        )
    }

    companion object {
        private const val NONE = "None"
        private const val GROUP_DISTANCE = "Distance-Based Scaling"
        private const val GROUP_INSTANT = "Instant Rotations"
        private const val DELETE_CONFIRM_MILLIS = 3000L
        private val GSON = Gson()

        private val TOOLTIPS: Map<String, String> = mapOf(
            "Preset" to "Human, Relaxed, Fast and Precise are built in. Loading a preset replaces the settings below; changing them afterwards does not change the preset until it is saved again under its name.",
            "Humanized Rotations" to "Off turns on a clean smooth path with none of the effects below. On adds reaction delay, bend, overshoot, hesitation, speed variation and aim drift.",
            "Speed" to "Each turn picks a random peak speed between the two ends, as a multiple of the activity's own speed. A wider range means less repeatable turns.",
            "Turn time (ms)" to "No turn is quicker than the low end, however small or fast, and none takes longer than the high end; slow settings are sped up to fit.",
            "Reaction delay (ms)" to "The pause before turning toward a new target when the view was still, drawn between the two ends. Does not apply while the view is already moving.",
            "Effect intensity" to "How strong every humanization effect is, all together.",
            "Vertical movement" to "How much of the sideways effects (bend, overshoot, drift) land on the pitch. Only as much pitch as the target needs is ever used, so on flat ground the view stays level whatever this says.",
            "Distance-based scaling" to "Turn speed depends on how far away the target is: 1x is the normal speed. Double-click the graph adds a point, drag moves it, right-click deletes it, shift-drag moves all.",
            "Overshoot chance" to "Every turn draws its own chance between the two ends. How often a turn of more than 12 degrees goes past its target and comes back. The two teleport activities never overshoot.",
            "Overshoot size" to "Drawn per turn between the two ends. How far a turn goes past its target, in percent of the turn.",
            "Curve strength" to "Drawn per turn between the two ends. How far turns bend off the straight line.",
            "Speed variation" to "Drawn per turn between the two ends. How much the speed wanders during a turn.",
            "Hesitations per second" to "Drawn per turn between the two ends. How often a long turn slows for a moment, per second of turning.",
            "Aim drift" to "Drawn per turn between the two ends. How far the aim drifts around the target while holding it, in degrees. The teleport activities never drift.",
            "New turn above" to "A target that jumps further than this starts a new turn with its own speed and timing; less is a moving target followed.",
            "Instant Rotations" to "The camera jumps to the target in a single frame: no path, no travel time. Nothing a human can do. Applies to every macro.",
        )

        @JvmField
        val INSTANCE = RotationEngineModule(Carrier())
    }
}
