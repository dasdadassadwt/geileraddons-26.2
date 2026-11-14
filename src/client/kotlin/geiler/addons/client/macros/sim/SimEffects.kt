package geiler.addons.client.macros.sim

import net.minecraft.resources.Identifier
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.AttributeModifier

/** The real Minecraft effects and attribute modifier the Sim features apply. */
object SimEffects {
    @JvmField
    val SPEED_MODIFIER_ID: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "speed_sim")

    /** One fixed modifier whose amount is the Speed stat's share of vanilla speed; it multiplies with vanilla's own. */
    @JvmStatic
    fun speedModifier(stat: Double): AttributeModifier =
        AttributeModifier(SPEED_MODIFIER_ID, SimMapping.speedModifierValue(stat), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)

    /** Jump Boost at the selected level, for as long as the feature stays on. */
    @JvmStatic
    fun jumpEffect(level: Int): MobEffectInstance =
        MobEffectInstance(MobEffects.JUMP_BOOST, MobEffectInstance.INFINITE_DURATION, SimMapping.jumpAmplifier(level))

    /** Strength level 255, for as long as the feature stays on. */
    @JvmStatic
    fun oneTapEffect(): MobEffectInstance =
        MobEffectInstance(MobEffects.STRENGTH, MobEffectInstance.INFINITE_DURATION, SimMapping.ONE_TAP_AMPLIFIER)
}
