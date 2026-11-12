package geiler.addons.client.module.impl

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ClientKeyMappingRegistrar
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

/** Rear third-person distance, collision, name-display, and free-look controls. */
class ThirdPersonCameraModule private constructor(settings: Settings) : Module(
    "Third Person Camera",
    "Adjusts rear third-person distance, camera clipping, and name display.",
    Category.VISUAL,
    false,
    settings.frontFacing,
    settings.noCameraClip,
    settings.cameraDistance,
    settings.showOwnPlayerName,
    settings.freeLookEnabled,
    settings.freeLookKeybind,
) {
    private val frontFacing = settings.frontFacing
    private val noCameraClip = settings.noCameraClip
    private val showOwnPlayerName = settings.showOwnPlayerName
    private val freeLookEnabled = settings.freeLookEnabled
    private val cameraDistance = settings.cameraDistance
    private val freeLookKeybind = settings.freeLookKeybind

    private var appliedFreeLookKey: String? = null
    private var wasFreeLooking = false
    private var freeLookYaw = 0.0f
    private var freeLookPitch = 0.0f
    private var clientTick = 0L
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, 100L, 1024)

    init {
        group(
            SettingGroup("Camera", frontFacing, noCameraClip, cameraDistance, showOwnPlayerName),
            SettingGroup("Free Look", freeLookEnabled, freeLookKeybind),
        )
    }

    /** Keeps the persisted keybind current and samples this module's bounded automatic trace. */
    fun tick(clientTick: Long) {
        this.clientTick = clientTick
        syncFreeLookKeybind()
        if (!isEnabled()) return

        diagnostics.advance()
        diagnostics.statusOnChange(20L) { "${automaticDiagnosticStatus()} sampleTicks=20 heartbeatTicks=100" }
    }

    /** Applies the module's persisted key in Minecraft's controls table. */
    fun syncFreeLookKeybind() {
        var serialized = freeLookKeybind.value()
        if (serialized.isBlank()) serialized = InputConstants.UNKNOWN.getName()
        if (serialized.indexOf('.') < 0 && serialized.length == 1) serialized = "key.keyboard.$serialized"
        if (serialized == appliedFreeLookKey) return
        val key: InputConstants.Key? = try {
            InputConstants.getKey(serialized)
        } catch (_: RuntimeException) {
            InputConstants.UNKNOWN
        }
        FREE_LOOK_KEY.setKey(key ?: InputConstants.UNKNOWN)
        KeyMapping.resetMapping()
        appliedFreeLookKey = serialized
    }

    /** True only while the camera module and free-look option are enabled, gameplay has focus, and the key is held. */
    fun holdingFreeLook(): Boolean {
        syncFreeLookKeybind()
        val client = Minecraft.getInstance()
        val held = isEnabled() && freeLookEnabled.value() && client != null && client.player != null &&
            client.gui.screen() == null && FREE_LOOK_KEY.isDown
        if (held != wasFreeLooking) {
            wasFreeLooking = held
            freeLookYaw = 0.0f
            freeLookPitch = 0.0f
            if (isEnabled()) diagnostics.event("freeLookHeld=$held screenOpen=${client.gui.screen() != null}")
        }
        return held
    }

    /** Temporarily captures player-turn input for the camera while free-look is held. */
    fun captureFreeLookTurn(yawDelta: Double, pitchDelta: Double): Boolean {
        if (!holdingFreeLook()) return false
        freeLookYaw += yawDelta.toFloat() * 0.15f
        freeLookPitch = Math.max(-90.0f, Math.min(90.0f, freeLookPitch + pitchDelta.toFloat() * 0.15f))
        return true
    }

    fun freeLookCameraYaw(vanillaYaw: Float): Float =
        if (holdingFreeLook()) vanillaYaw + freeLookYaw else vanillaYaw

    fun freeLookCameraPitch(vanillaPitch: Float): Float =
        if (holdingFreeLook()) Math.max(-90.0f, Math.min(90.0f, vanillaPitch + freeLookPitch)) else vanillaPitch

    fun cameraType(vanilla: CameraType?): CameraType? {
        if (!isEnabled() || frontFacing.value() || vanilla != CameraType.THIRD_PERSON_FRONT) return vanilla
        return CameraType.FIRST_PERSON
    }

    /** Skips the front-facing mode when vanilla cycles the perspective with F5. */
    fun constrainCameraTypeRequest(requested: CameraType?): CameraType? {
        if (!isEnabled() || frontFacing.value() || requested != CameraType.THIRD_PERSON_FRONT) return requested
        val current = currentCameraType()
        diagnostics.event(
            "cameraTypeRequest current=${cameraTypeName(current)} " +
                "requested=THIRD_PERSON_FRONT applied=FIRST_PERSON reason=frontViewUnavailable",
        )
        return CameraType.FIRST_PERSON
    }

    fun distance(vanilla: Float): Float = if (isEnabled()) cameraDistance.value() else vanilla

    fun bypassCollisionClip(): Boolean = isEnabled() && noCameraClip.value()

    fun showOwnNameInThirdPerson(): Boolean {
        val client = minecraftOrNull()
        return isEnabled() && showOwnPlayerName.value() && client != null && client.player != null &&
            client.options != null && client.options.getCameraType() != CameraType.FIRST_PERSON
    }

    fun frontFacingSetting(): BooleanSetting = frontFacing

    fun noCameraClipSetting(): BooleanSetting = noCameraClip

    fun cameraDistanceSetting(): NumberSetting = cameraDistance

    private fun automaticDiagnosticStatus(): String =
        "enabled=true camera=${cameraTypeName(currentCameraType())}" +
            " allowFrontCamera=${frontFacing.value()}" +
            " cameraDistance=${cameraDistance.value()}" +
            " noCameraClip=${noCameraClip.value()}" +
            " showOwnPlayerName=${showOwnPlayerName.value()}" +
            " freeLookEnabled=${freeLookEnabled.value()}" +
            " freeLookHeld=$wasFreeLooking"

    private fun currentCameraType(): CameraType? {
        val client = minecraftOrNull()
        return if (client == null || client.options == null) null else client.options.getCameraType()
    }

    // Preserve the Java implementation's null guard for early client/bootstrap callers.
    private fun minecraftOrNull(): Minecraft? = try {
        Minecraft.getInstance()
    } catch (_: NullPointerException) {
        null
    }

    override fun onEnable() {
        diagnostics.enable("allowFrontCamera=${frontFacing.value()}")
    }

    override fun onDisable() {
        diagnostics.disable()
        wasFreeLooking = false
        freeLookYaw = 0.0f
        freeLookPitch = 0.0f
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Third Person Camera")
        val frontFacing = spec.toggle("frontFacing")
        val noCameraClip = spec.toggle("noCameraClip")
        val cameraDistance = spec.slider("cameraDistance")
        val showOwnPlayerName = spec.toggle("showOwnPlayerName")
        val freeLookEnabled = spec.toggle("freeLookEnabled")
        val freeLookKeybind = spec.text("freeLookKeybind")
    }

    companion object {
        @JvmField
        val INSTANCE = ThirdPersonCameraModule(Settings())

        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16

        private val FREE_LOOK_KEY = ClientKeyMappingRegistrar.register(
            KeyMapping(
                "key.geileraddons.free_look",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_C,
                KeyMapping.Category.GAMEPLAY,
            ),
        )

        @JvmStatic
        fun freeLookKey(): KeyMapping = FREE_LOOK_KEY

        private fun cameraTypeName(type: CameraType?): String = type?.name ?: "unavailable"
    }
}
