package geiler.addons.client.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import geiler.addons.client.module.impl.CosmeticsModule
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player

/**
 * Draws a player's cosmetics as voxel geometry attached to the body and head parts, so they follow every
 * pose, plus the reshaped torso (the vanilla torso is hidden by [geiler.addons.client.mixin.PlayerModelBodyShapeMixin]).
 */
class CosmeticsRenderLayer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) :
    RenderLayer<AvatarRenderState, PlayerModel>(parent) {

    override fun submit(
        pose: PoseStack,
        collector: SubmitNodeCollector,
        light: Int,
        state: AvatarRenderState,
        yRot: Float,
        xRot: Float,
    ) {
        if (state.isSpectator) return
        val module = CosmeticsModule.INSTANCE
        try {
            val player = Minecraft.getInstance().level?.getEntity(state.id) as? Player ?: return
            var loadout = module.loadoutFor(player.gameProfile.name)
            if (loadout.isEmpty()) return
            // A worn helmet (or any head item) replaces the hat.
            if (!state.headEquipment.isEmpty) loadout = loadout.withoutSlot(CosmeticSlot.HAT)
            val model = parentModel
            val body = loadout.body()
            // The torso stays visible while invisible only if the vanilla one would; skip it like vanilla does.
            if (body != null && !state.isInvisible) torso(pose, collector, light, state, model, body)
            if (state.isInvisible) return
            var motion = TRACKER.update(
                state.id,
                state.ageInTicks / 20f,
                state.walkAnimationSpeed,
                state.isFallFlying || !player.onGround(),
            )
            CosmeticRigA.noteTurn(state.id, state.ageInTicks / 20f, state.bodyRot)
            if (loadout.piece(CosmeticSlot.HAT) != null || loadout.piece(CosmeticSlot.HEAD) != null) {
                CosmeticHeadMotion.update(state.id, state.ageInTicks / 20f, state.bodyRot + yRot, xRot, motion.moving, motion.air)
            }
            if (loadout.piece(CosmeticSlot.PET) != null) {
                // The pet chases a roaming spot (shoulders, head, floor) in world space; its body-frame pose goes in
                // with the motion. It keeps off the head while a hat or head piece is worn there.
                val headFree = loadout.piece(CosmeticSlot.HAT) == null && loadout.piece(CosmeticSlot.HEAD) == null
                motion = motion.copy(
                    pet = PET_FOLLOW.update(
                        state.id, state.ageInTicks / 20f, state.x, state.y, state.z, state.bodyRot, state.bodyRot + yRot,
                        state.scale, motion.moving, motion.air, headFree,
                    ),
                )
            }
            // Skirts follow the model's real legs; set only while this loadout is placed.
            SkirtRig.setLegs(model.rightLeg.xRot, model.leftLeg.xRot)
            val placements = try {
                CosmeticRig.place(loadout, motion, true)
            } finally {
                SkirtRig.clearLegs()
            }
            LiveCosmetics.append(placements, loadout, motion, state, player)
            for (placement in placements) {
                pose.pushPose()
                when (placement.anchor) {
                    CosmeticRig.Anchor.HEAD -> model.head.translateAndRotate(pose)
                    // Trail particles are positioned in the player's model space directly (they stay put in the world).
                    CosmeticRig.Anchor.WORLD -> Unit
                    // Vanilla pushes the cape out a little when a chestplate is worn, before the body's own pose.
                    CosmeticRig.Anchor.CAPE_ARMOR -> {
                        pose.translate(0f, -0.053125f, 0.06875f)
                        model.body.translateAndRotate(pose)
                    }
                    else -> model.body.translateAndRotate(pose)
                }
                pose.scale(PIXEL, PIXEL, PIXEL)
                pose.mulPose(placement.transform)
                val mesh = placement.mesh
                val color = placement.color
                val color2 = placement.color2
                val alpha = placement.alpha
                val type = when (placement.kind) {
                    CosmeticRig.Kind.SOLID -> SOLID
                    CosmeticRig.Kind.TRANSLUCENT -> TRANSLUCENT
                    CosmeticRig.Kind.GLOW -> GLOW
                }
                val faceLight = if (placement.kind == CosmeticRig.Kind.GLOW) FULL_BRIGHT else light
                collector.submitCustomGeometry(pose, type) { last, consumer -> emit(mesh, last, consumer, color, color2, alpha, faceLight) }
                pose.popPose()
            }
            module.noteRendered()
        } catch (error: RuntimeException) {
            module.noteRenderFailure(error)
        }
    }

    private fun torso(
        pose: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, model: PlayerModel, body: BodyShape,
    ) {
        val skin = state.skin.body().texturePath()
        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0f)
        pose.pushPose()
        model.body.translateAndRotate(pose)
        pose.scale(PIXEL, PIXEL, PIXEL)
        val base = BodyShapeGeometry.cached(body, false)
        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(skin)) { last, consumer ->
            emitTextured(base, last, consumer, light, overlay)
        }
        if (state.showJacket) {
            val jacket = BodyShapeGeometry.cached(body, true)
            collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(skin)) { last, consumer ->
                emitTextured(jacket, last, consumer, light, overlay)
            }
        }
        pose.popPose()
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private const val FULL_BRIGHT = 0xF000F0
        private val WHITE = Identifier.fromNamespaceAndPath("geileraddons", "textures/cosmetics/white.png")

        // Not back-face culled: the right-hand pieces are mirrored meshes.
        private val SOLID: RenderType = RenderTypes.entityCutout(WHITE)
        private val TRANSLUCENT: RenderType = RenderTypes.entityTranslucent(WHITE)
        private val GLOW: RenderType = RenderTypes.entityTranslucentEmissive(WHITE)

        /** Shared by every player renderer; keyed by entity id. Render thread only. */
        private val TRACKER = CosmeticMotionTracker()
        private val PET_FOLLOW = PetFollow()

        /** The skin models of the player renderers; player armor uses other PlayerModel instances. */
        private val SKIN_MODELS: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

        @JvmStatic
        fun isSkinModel(model: Any): Boolean = model in SKIN_MODELS

        @JvmStatic
        fun register() {
            LivingEntityRenderLayerRegistrationCallback.EVENT.register { _, renderer, helper, context ->
                LiveCosmetics.equipmentAssets = context.equipmentAssets
                if (renderer is AvatarRenderer<*>) {
                    SKIN_MODELS.add(renderer.model)
                    @Suppress("UNCHECKED_CAST")
                    helper.register(CosmeticsRenderLayer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
                }
            }
        }

        private fun emit(
            mesh: VoxelMesh, last: PoseStack.Pose, consumer: VertexConsumer, color: Int, color2: Int, alpha: Int, light: Int,
        ) {
            val positions = mesh.positions
            val normal = FloatArray(3)
            for (face in 0 until mesh.faceCount()) {
                val tint = mesh.tint(face, color, color2)
                val shade = mesh.shades[face]
                val argb = (alpha.coerceIn(0, 255) shl 24) or
                    (channel(tint shr 16, shade) shl 16) or (channel(tint shr 8, shade) shl 8) or channel(tint, shade)
                mesh.normal(face, normal)
                val nx = normal[0]
                val ny = normal[1]
                val nz = normal[2]
                var base = face * 12
                for (corner in 0 until 4) {
                    consumer.addVertex(last, positions[base], positions[base + 1], positions[base + 2])
                        .setColor(argb)
                        .setUv(0.5f, 0.5f)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(last, nx, ny, nz)
                    base += 3
                }
            }
        }

        private fun emitTextured(quads: FloatArray, last: PoseStack.Pose, consumer: VertexConsumer, light: Int, overlay: Int) {
            var i = 0
            while (i < quads.size) {
                val n = i + 20
                for (corner in 0 until 4) {
                    val c = i + corner * 5
                    consumer.addVertex(last, quads[c], quads[c + 1], quads[c + 2])
                        .setColor(-1)
                        .setUv(quads[c + 3] / BodyShapeGeometry.SKIN_SIZE, quads[c + 4] / BodyShapeGeometry.SKIN_SIZE)
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(last, quads[n], quads[n + 1], quads[n + 2])
                }
                i += BodyShapeGeometry.FLOATS_PER_QUAD
            }
        }

        private fun channel(value: Int, shade: Float): Int = ((value and 0xFF) * shade).toInt().coerceIn(0, 255)
    }
}
