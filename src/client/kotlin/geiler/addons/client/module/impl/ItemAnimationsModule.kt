package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffectUtil
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import java.util.ArrayDeque

/** Adjustable first-person item transforms and swing behavior, ported as local settings and hooks. */
class ItemAnimationsModule private constructor(settings: Settings) : Module(
    "Animations",
    "Adjusts first-person item position, rotation, size, and swing behavior.",
    Category.VISUAL,
    false,
    settings.xOffset,
    settings.yOffset,
    settings.zOffset,
    settings.yawOffset,
    settings.pitchOffset,
    settings.rollOffset,
    settings.scale,
    settings.swingSpeed,
    settings.changeHand,
    settings.changeMap,
    settings.ignoreEffects,
    settings.noReequipReset,
    settings.inPlaceSwing,
    settings.noSwing,
    settings.noSwingTerm,
    settings.noHandSway,
    settings.reset,
) {
    private val xOffset = settings.xOffset
    private val yOffset = settings.yOffset
    private val zOffset = settings.zOffset
    private val yawOffset = settings.yawOffset
    private val pitchOffset = settings.pitchOffset
    private val rollOffset = settings.rollOffset
    private val scale = settings.scale
    private val swingSpeed = settings.swingSpeed
    private val changeHand = settings.changeHand
    private val changeMap = settings.changeMap
    private val ignoreEffects = settings.ignoreEffects
    private val noReequipReset = settings.noReequipReset
    private val inPlaceSwing = settings.inPlaceSwing
    private val noSwing = settings.noSwing
    private val noSwingTerm = settings.noSwingTerm
    private val noHandSway = settings.noHandSway

    private var lastLevel: Any? = null
    private val itemScaleScopes = ThreadLocal.withInitial { ArrayDeque<Boolean>() }
    private var swinging = false
    private var swingTimeTick = 0
    private var attackAnimation = 0.0f
    private var previousAttackAnimation = 0.0f

    private val diagnostics = ModuleDiagnostics(
        Category.VISUAL, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, STATUS_HEARTBEAT_TICKS, MAX_DIAGNOSTIC_LINE_LENGTH,
    )
    private var transformCallsSinceStatus = 0
    private var transformsAppliedSinceStatus = 0
    private var scaleScopesSinceStatus = 0
    private var eligibleScaleScopesSinceStatus = 0
    private var deferredScalesAppliedSinceStatus = 0
    private var mapScaleCallsSinceStatus = 0
    private var mapScalesAppliedSinceStatus = 0
    private var swingProgressCallsSinceStatus = 0
    private var swingProgressSuppressedSinceStatus = 0

    init {
        group(
            SettingGroup(
                "Transform",
                settings.xOffset,
                settings.yOffset,
                settings.zOffset,
                settings.yawOffset,
                settings.pitchOffset,
                settings.rollOffset,
                settings.scale,
                settings.reset,
            ),
            SettingGroup(
                "Swing",
                settings.swingSpeed,
                settings.ignoreEffects,
                settings.noReequipReset,
                settings.inPlaceSwing,
                settings.noSwing,
                settings.noSwingTerm,
                settings.noHandSway,
            ),
            SettingGroup("Apply To", settings.changeHand, settings.changeMap),
        )
    }

    override fun configName(): String = "Item Animations"

    override fun onEnable() {
        resetDiagnosticCounters()
        diagnostics.enable(diagnosticSettings())
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    private class Settings {
        val xOffset = NumberSetting("xOffset", "X Offset", -1.0f, 1.0f, 0.0f)
        val yOffset = NumberSetting("yOffset", "Y Offset", -1.0f, 1.0f, 0.14f)
        val zOffset = NumberSetting("zOffset", "Z Offset", -1.0f, 1.0f, 0.0f)
        val yawOffset = angle("yawOffset", "Yaw Rotation", -2.068693338301614f)
        val pitchOffset = angle("pitchOffset", "Pitch Rotation", 0.0f)
        val rollOffset = angle("rollOffset", "Roll Rotation", -2.068693338301614f)
        val scale = NumberSetting("scale", "Scale", -4.0f, 4.0f, -1.53f)
        val swingSpeed = NumberSetting("swingSpeed", "Swing Speed", -4.0f, 4.0f, 0.08f)
        val changeHand = BooleanSetting("changeHand", "Change Empty Hand", true)
        val changeMap = BooleanSetting("changeMap", "Change Holding Map", true)
        val ignoreEffects = BooleanSetting("ignoreEffects", "Ignore Haste and Fatigue", true)
        val noReequipReset = BooleanSetting("noReequipReset", "No Reequip Reset", true)
        val inPlaceSwing = BooleanSetting("inplaceSwing", "In Place Swing", true)
        val noSwing = BooleanSetting("noSwing", "Disable Swing", false)
        val noSwingTerm = BooleanSetting("noSwingTerm", "Disable Terminator Swing", false)
        val noHandSway = BooleanSetting("noHandSway", "No Hand Sway", true)
        val reset = ModuleAction("Reset Values", Runnable { ItemAnimationsModule.INSTANCE.resetValues() })

        private fun angle(name: String, label: String, value: Float) =
            NumberSetting(name, label, -180.0f, 180.0f, value, false, false)
    }

    private fun resetValues() {
        xOffset.reset()
        yOffset.reset()
        zOffset.reset()
        yawOffset.reset()
        pitchOffset.reset()
        rollOffset.reset()
        scale.reset()
        swingSpeed.reset()
    }

    fun affects(stack: ItemStack?): Boolean {
        if (!isEnabled()) return false
        if (stack == null || stack.isEmpty) return changeHand.value()
        return !stack.has(DataComponents.MAP_ID) || changeMap.value()
    }

    fun applyTransform(pose: PoseStack?, stack: ItemStack?) {
        if (isEnabled()) transformCallsSinceStatus = incrementCount(transformCallsSinceStatus)
        if (pose == null || !affects(stack)) return
        if (isEnabled()) transformsAppliedSinceStatus = incrementCount(transformsAppliedSinceStatus)
        pose.mulPose(Axis.XP.rotationDegrees(pitchOffset.value()))
        pose.mulPose(Axis.YP.rotationDegrees(yawOffset.value()))
        pose.mulPose(Axis.ZP.rotationDegrees(rollOffset.value()))
        pose.translate(xOffset.value(), yOffset.value(), zOffset.value())
    }

    /** Marks the synchronous vanilla held-item submission so scaling can follow its model transform. */
    fun beginItemScale(stack: ItemStack?, displayContext: ItemDisplayContext?) {
        if (isEnabled()) scaleScopesSinceStatus = incrementCount(scaleScopesSinceStatus)
        val firstPerson = displayContext == ItemDisplayContext.FIRST_PERSON_LEFT_HAND ||
            displayContext == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
        val eligible = firstPerson && affects(stack)
        if (isEnabled() && eligible) {
            eligibleScaleScopesSinceStatus = incrementCount(eligibleScaleScopesSinceStatus)
        }
        itemScaleScopes.get().push(eligible)
    }

    fun endItemScale() {
        val scopes = itemScaleScopes.get()
        if (scopes.isNotEmpty()) scopes.pop()
        if (scopes.isEmpty()) itemScaleScopes.remove()
    }

    /** Called after vanilla's model/display transform, so size changes stay centered on the item model. */
    fun applyDeferredItemScale(pose: PoseStack?) {
        val scopes = itemScaleScopes.get()
        if (scopes.isEmpty()) {
            itemScaleScopes.remove()
            return
        }
        if (isEnabled()) {
            if (pose != null && scopes.peek() == true) {
                deferredScalesAppliedSinceStatus = incrementCount(deferredScalesAppliedSinceStatus)
            }
        }
        if (pose == null || scopes.peek() != true || !isEnabled()) return
        val multiplier = scaleMultiplier()
        pose.translate(ITEM_MODEL_CENTER, ITEM_MODEL_CENTER, ITEM_MODEL_CENTER)
        pose.scale(multiplier, multiplier, multiplier)
        pose.translate(-ITEM_MODEL_CENTER, -ITEM_MODEL_CENTER, -ITEM_MODEL_CENTER)
    }

    /** Maps use a dedicated renderer; scale around their center after its 128-pixel transform is established. */
    fun applyMapScaleAtCenter(pose: PoseStack?, stack: ItemStack?) {
        if (isEnabled()) mapScaleCallsSinceStatus = incrementCount(mapScaleCallsSinceStatus)
        if (pose == null || !affects(stack)) return
        if (isEnabled()) mapScalesAppliedSinceStatus = incrementCount(mapScalesAppliedSinceStatus)
        val multiplier = scaleMultiplier()
        pose.translate(64.0f, 64.0f, 0.0f)
        pose.scale(multiplier, multiplier, multiplier)
        pose.translate(-64.0f, -64.0f, 0.0f)
    }

    private fun scaleMultiplier(): Float = Math.pow(2.0, scale.value().toDouble()).toFloat()

    /** Local-player hook: maintains a small independent swing clock so speed/effect controls are local. */
    fun onLocalSwing() {
        if (!isEnabled()) return
        val ignored = swinging && swingTimeTick >= 0 &&
            swingTimeTick * swingMultiplier() < currentSwingDuration() / 2.0f
        if (ignored) {
            diagnostics.event("SWING outcome=ignored reason=early-retrigger swingTimeTick=$swingTimeTick")
            return
        }
        swingTimeTick = -1
        swinging = true
        diagnostics.event("SWING outcome=started")
    }

    /** Local-player tick hook; called on the client thread. */
    fun tickSwing() {
        val client = Minecraft.getInstance()
        val diagnosticsEnabled = isEnabled()
        if (diagnosticsEnabled) diagnostics.advance()
        if (client.level !== lastLevel) {
            lastLevel = client.level
            swinging = false
            swingTimeTick = 0
            attackAnimation = 0.0f
            previousAttackAnimation = 0.0f
            if (diagnosticsEnabled) {
                diagnostics.event("WORLD_CHANGED levelPresent=${client.level != null} swingClockReset=true")
            }
        }
        if (!isEnabled() || client.player == null) {
            swinging = false
            swingTimeTick = 0
            attackAnimation = 0.0f
            previousAttackAnimation = 0.0f
            if (diagnosticsEnabled) reportDiagnostics(client)
            return
        }
        previousAttackAnimation = attackAnimation
        val duration = currentSwingDuration()
        if (swinging) {
            swingTimeTick++
            if (swingTimeTick * swingMultiplier() >= duration) {
                swingTimeTick = 0
                swinging = false
            }
        } else {
            swingTimeTick = 0
        }
        attackAnimation = swingTimeTick * swingMultiplier() / duration
        if (diagnosticsEnabled) reportDiagnostics(client)
    }

    fun swingProgress(partialTick: Float, vanillaProgress: Float): Float {
        if (!isEnabled()) return vanillaProgress
        swingProgressCallsSinceStatus = incrementCount(swingProgressCallsSinceStatus)
        val player = Minecraft.getInstance().player
        val held = if (player == null) ItemStack.EMPTY else player.mainHandItem
        if (noSwing.value() || (noSwingTerm.value() && isTerminator(held))) {
            swingProgressSuppressedSinceStatus = incrementCount(swingProgressSuppressedSinceStatus)
            return 0.0f
        }
        var delta = attackAnimation - previousAttackAnimation
        if (delta < 0.0f) delta++
        return previousAttackAnimation + delta * Math.max(0.0f, Math.min(1.0f, partialTick))
    }

    private fun currentSwingDuration(): Float {
        if (ignoreEffects.value()) return 6.0f
        val player = Minecraft.getInstance().player ?: return 6.0f
        if (MobEffectUtil.hasDigSpeed(player)) {
            return Math.max(1.0f, 6.0f - (1 + MobEffectUtil.getDigSpeedAmplification(player)))
        }
        val fatigue: MobEffectInstance? = player.getEffect(MobEffects.MINING_FATIGUE)
        return if (fatigue == null) 6.0f else 6.0f + (1 + fatigue.amplifier) * 2.0f
    }

    private fun swingMultiplier(): Float = Math.pow(2.0, swingSpeed.value().toDouble()).toFloat()

    private fun isTerminator(stack: ItemStack?): Boolean = stack != null && !stack.isEmpty &&
        ChatText.plain(stack.hoverName.string).trim().equals("Terminator", ignoreCase = true)

    fun noReequipReset(): Boolean = isEnabled() && noReequipReset.value()

    fun inPlaceSwing(): Boolean = isEnabled() && inPlaceSwing.value()

    fun noHandSway(): Boolean = isEnabled() && noHandSway.value()

    fun xOffset(): NumberSetting = xOffset

    fun yOffset(): NumberSetting = yOffset

    fun zOffset(): NumberSetting = zOffset

    fun yawOffset(): NumberSetting = yawOffset

    fun pitchOffset(): NumberSetting = pitchOffset

    fun rollOffset(): NumberSetting = rollOffset

    fun scale(): NumberSetting = scale

    fun swingSpeed(): NumberSetting = swingSpeed

    private fun reportDiagnostics(client: Minecraft) {
        val levelPresent = client.level != null
        val playerPresent = client.player != null
        diagnostics.statusOnChangeBy(
            STATUS_MIN_INTERVAL_TICKS,
            { statusSignature(levelPresent, playerPresent) },
            {
                val signature = statusSignature(levelPresent, playerPresent)
                val scopeStack = itemScaleScopes.get()
                val scopeDepth = scopeStack.size
                if (scopeStack.isEmpty()) itemScaleScopes.remove()
                val line = "$signature;scopeDepth=$scopeDepth;" +
                    "calls{transform=$transformCallsSinceStatus/$transformsAppliedSinceStatus;" +
                    "scaleScope=$scaleScopesSinceStatus/$eligibleScaleScopesSinceStatus;" +
                    "deferredScale=$deferredScalesAppliedSinceStatus;" +
                    "mapScale=$mapScaleCallsSinceStatus/$mapScalesAppliedSinceStatus;" +
                    "swingProgress=$swingProgressCallsSinceStatus/$swingProgressSuppressedSinceStatus}"
                resetDiagnosticCounters()
                line
            },
        )
    }

    private fun statusSignature(levelPresent: Boolean, playerPresent: Boolean): String =
        "enabled=${isEnabled()};levelPresent=$levelPresent;playerPresent=$playerPresent;" +
            "swinging=$swinging;swingTimeTick=$swingTimeTick;attack=${attackAnimation};" +
            "previousAttack=${previousAttackAnimation};duration=${currentSwingDuration()};" +
            "scaleMultiplier=${scaleMultiplier()};swingMultiplier=${swingMultiplier()};${diagnosticSettings()}"

    private fun diagnosticSettings(): String =
        "settings{xOffset=${xOffset.value()};yOffset=${yOffset.value()};zOffset=${zOffset.value()};" +
            "yaw=${yawOffset.value()};pitch=${pitchOffset.value()};roll=${rollOffset.value()};" +
            "scaleExponent=${scale.value()};swingSpeedExponent=${swingSpeed.value()};" +
            "changeHand=${changeHand.value()};changeMap=${changeMap.value()};ignoreEffects=${ignoreEffects.value()};" +
            "noReequipReset=${noReequipReset.value()};inPlaceSwing=${inPlaceSwing.value()};" +
            "noSwing=${noSwing.value()};noSwingTerm=${noSwingTerm.value()};noHandSway=${noHandSway.value()}}"

    private fun resetDiagnosticCounters() {
        transformCallsSinceStatus = 0
        transformsAppliedSinceStatus = 0
        scaleScopesSinceStatus = 0
        eligibleScaleScopesSinceStatus = 0
        deferredScalesAppliedSinceStatus = 0
        mapScaleCallsSinceStatus = 0
        mapScalesAppliedSinceStatus = 0
        swingProgressCallsSinceStatus = 0
        swingProgressSuppressedSinceStatus = 0
    }

    private fun incrementCount(value: Int): Int = (value + 1).coerceAtMost(MAX_DIAGNOSTIC_COUNT)

    companion object {
        private const val ITEM_MODEL_CENTER = 0.5f
        private const val STATUS_MIN_INTERVAL_TICKS = 20L
        private const val STATUS_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 16
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 1024

        @JvmField
        val INSTANCE = ItemAnimationsModule(Settings())
    }
}
