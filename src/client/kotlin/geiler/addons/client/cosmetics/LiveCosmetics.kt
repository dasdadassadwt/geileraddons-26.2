package geiler.addons.client.cosmetics

import geiler.addons.client.module.impl.CosmeticsModule
import geiler.addons.client.module.impl.PlayerSizeModule
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.resources.model.EquipmentAssetManager
import net.minecraft.client.resources.model.EquipmentClientInfo
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * The cosmetics that depend on the real world, added to the render layer's placements every frame: trails (the
 * player's walked path, [TrailHistory], drawn in world space by [TrailRig]) and capes (vanilla lean and flap,
 * [CapeRig]). Menu pictures use the synthetic versions in [CosmeticRig.place] instead. Render thread only.
 */
object LiveCosmetics {
    private val history = TrailHistory()
    private val frame = TrailRig.Frame()

    /** Set when the player renderers are created; used to ask whether the chest item draws wings (an elytra). */
    @JvmField
    var equipmentAssets: EquipmentAssetManager? = null

    fun append(
        out: MutableList<CosmeticRig.Placement>, loadout: CosmeticLoadout, motion: CosmeticRig.Motion,
        state: AvatarRenderState, player: Player,
    ) {
        loadout.piece(CosmeticSlot.TRAIL)?.let { trail(out, it, motion, state, player) }
        loadout.piece(CosmeticSlot.CAPE)?.let { cape(out, it, motion, state) }
    }

    private fun trail(
        out: MutableList<CosmeticRig.Placement>, piece: CosmeticPiece, motion: CosmeticRig.Motion, state: AvatarRenderState, player: Player,
    ) {
        val seconds = state.ageInTicks / 20.0
        // Swimming, gliding, dying or upside down tilt the whole model: the path would no longer line up, so the
        // trail pauses (and keeps aging) instead of drawing in the wrong place.
        val upright = (state.pose == Pose.STANDING || state.pose == Pose.CROUCHING) && state.deathTime <= 0f && !state.isUpsideDown
        if (!upright) {
            history.touch(state.id, seconds)
            return
        }
        val track = history.update(
            state.id, piece.style, seconds, state.x, state.y, state.z, player.onGround(), motion.seed, piece.back / 16f,
        )
        val size = PlayerSizeModule.INSTANCE.sizeScale(player.gameProfile.name)
        frame.set(state.x, state.y, state.z, state.bodyRot, state.scale, size.x, size.y, size.z, seconds)
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        frame.camera(camera.x, camera.y, camera.z)
        TrailRig.live(piece, track, motion, frame, out)
    }

    private fun cape(out: MutableList<CosmeticRig.Placement>, piece: CosmeticPiece, motion: CosmeticRig.Motion, state: AvatarRenderState) {
        // Like vanilla: no cape over an elytra; under chest armor it sits a little further out.
        if (hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.WINGS)) return
        val anchor = if (hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.HUMANOID)) CosmeticRig.Anchor.CAPE_ARMOR
        else CosmeticRig.Anchor.CAPE
        CapeRig.place(piece, motion, state.capeLean, state.capeFlap, state.capeLean2, anchor, out)
    }

    /** True when this player wears a cosmetic cape, so the vanilla cape layer must not draw its own. */
    @JvmStatic
    fun replacesVanillaCape(state: AvatarRenderState): Boolean {
        val player = Minecraft.getInstance().level?.getEntity(state.id) as? Player ?: return false
        return CosmeticsModule.INSTANCE.loadoutFor(player.gameProfile.name).piece(CosmeticSlot.CAPE) != null
    }

    /** The same test as vanilla's cape layer: does the item's equipment asset have a layer of this type? */
    private fun hasLayer(stack: ItemStack, type: EquipmentClientInfo.LayerType): Boolean {
        val equippable = stack.get(DataComponents.EQUIPPABLE) ?: return false
        val assetId = equippable.assetId()
        val assets = equipmentAssets
        if (assetId.isEmpty || assets == null) return false
        return assets.get(assetId.get()).getLayers(type).isNotEmpty()
    }

    fun trackedPlayers(): Int = history.players()
}
