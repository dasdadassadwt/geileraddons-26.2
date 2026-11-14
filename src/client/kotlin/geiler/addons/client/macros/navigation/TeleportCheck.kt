package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.pathing.Region
import geiler.addons.client.macros.sim.EtherwarpMath
import geiler.addons.client.macros.sim.EtherwarpSim
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3

/**
 * The truth about a warp: what the Etherwarp Sim - the module that paints the block green - says about the ray from the crouching eyes,
 * and whether the way to the block is clear of what is shut. The planners use it to take only blocks the item will really put the
 * player on, and the click uses it again with the view as it is that tick, because the view never rests exactly on the point asked for.
 */
object TeleportCheck {
    /** What the check said: "ok" or the reason it is refused, and where the item would put the player, if anywhere. */
    class Verdict(@JvmField val reason: String, @JvmField val landing: Vec3?) {
        val ok: Boolean get() = reason == "ok"
    }

    private const val BODY_HEIGHT = 1.8

    /** The look from the crouching eyes at [feet] to [point]. */
    fun lookTo(feet: Vec3, point: Vec3): Vec3 = point.subtract(feet.add(0.0, EtherwarpMath.SNEAK_EYE_HEIGHT, 0.0)).normalize()

    /**
     * The check for a warp from [feet] along [look] over [range] blocks, against the landing it was planned for ([plan], null for none).
     */
    fun warp(level: ClientLevel, player: LocalPlayer, region: Region, feet: Vec3, look: Vec3, range: Double, plan: Vec3?): Verdict {
        val result = EtherwarpSim.traceFrom(level, feet.x, feet.y, feet.z, EtherwarpMath.SNEAK_EYE_HEIGHT, look, range)
        val landing = if (result.valid) Vec3(result.landX, result.landY, result.landZ) else null
        val shut = landing != null && region.crossesShut(feet, landing, player.bbWidth * 0.5, BODY_HEIGHT)
        val reason = WarpVerdict.decide(
            result.valid, result.reason, result.landX, result.landY, result.landZ,
            plan?.x ?: Double.NaN, plan?.y ?: Double.NaN, plan?.z ?: Double.NaN, shut,
        )
        return Verdict(reason, landing)
    }
}
