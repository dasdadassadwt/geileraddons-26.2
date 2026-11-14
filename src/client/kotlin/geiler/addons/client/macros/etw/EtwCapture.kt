package geiler.addons.client.macros.etw

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Reads the game for `/etw`: the player at the instant of a click, what the crosshair and a long ray hit, the sphere of
 * blocks around the targeted block, and the raw fields of the server packets. Reads only; nothing here changes the game.
 */
object EtwCapture {
    /** Far beyond any etherwarp range, so the ray never ends before the real target does. */
    const val LONG_RANGE = 128.0

    @JvmStatic
    fun xyz(vec: Vec3): Xyz = Xyz(vec.x, vec.y, vec.z)

    @JvmStatic
    fun playerState(player: LocalPlayer): PlayerState =
        PlayerState(xyz(player.position()), xyz(player.getEyePosition()), player.yRot.toDouble(), player.xRot.toDouble(), xyz(player.deltaMovement))

    /** Everything about one right click, taken synchronously at the click. */
    @JvmStatic
    fun captureClick(
        client: Minecraft,
        player: LocalPlayer,
        level: ClientLevel,
        mode: EtwMode,
        tick: Long,
        clientTick: Long,
        useAttemptIndex: Int,
    ): EtwSample {
        val nanoTime = System.nanoTime()
        val epochMs = System.currentTimeMillis()
        val position = xyz(player.position())
        val eye = player.getEyePosition()
        val look = player.getViewVector(1.0f)
        val yaw = player.yRot.toDouble()
        val pitch = player.xRot.toDouble()

        val click = JsonObject()
        click.addProperty("tick", tick)
        click.addProperty("clientTick", clientTick)
        click.addProperty("epochMs", epochMs)
        click.addProperty("nanoTime", nanoTime)
        click.addProperty("useAttemptIndex", useAttemptIndex)
        click.addProperty("sneaking", player.isShiftKeyDown)
        click.addProperty("inputShiftFlag", player.input.keyPresses.shift())
        click.addProperty("mainHandItem", BuiltInRegistries.ITEM.getKey(player.mainHandItem.item).toString())
        click.addProperty("partialTick", client.deltaTracker.getGameTimeDeltaPartialTick(true).toDouble())
        click.add("position", position.toJson())
        click.add("previousPosition", Xyz(player.xo, player.yo, player.zo).toJson())
        click.add("eyePosition", xyz(eye).toJson())
        click.add("eyePositionPartialTick", xyz(player.getEyePosition(client.deltaTracker.getGameTimeDeltaPartialTick(true))).toJson())
        click.addProperty("yaw", yaw)
        click.addProperty("pitch", pitch)
        click.addProperty("previousYaw", player.yRotO.toDouble())
        click.addProperty("previousPitch", player.xRotO.toDouble())
        click.add("lookVector", xyz(look).toJson())

        if (mode == EtwMode.B) {
            val numbers = ClickNumbers(position, xyz(eye), yaw, pitch, null, null, tick, nanoTime)
            return EtwSample(mode, click, null, null, numbers, listOf("targeting", "sphere"))
        }

        val unavailable = ArrayList<String>()
        val end = eye.add(look.scale(LONG_RANGE))
        val outline = level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        val collider = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))

        val targeting = JsonObject()
        targeting.add("vanillaHit", vanillaHit(client.hitResult))
        val longRange = JsonObject()
        longRange.addProperty("range", LONG_RANGE)
        longRange.add("outline", blockHit(outline))
        longRange.add("collider", blockHit(collider))
        targeting.add("longRange", longRange)
        if (outline.type == HitResult.Type.MISS) unavailable.add("targeting.longRange.outline")
        if (collider.type == HitResult.Type.MISS) unavailable.add("targeting.longRange.collider")

        // The targeted block is the one the outline ray hits; the collider ray stands in when only it hits.
        val chosen = when {
            outline.type == HitResult.Type.BLOCK -> outline
            collider.type == HitResult.Type.BLOCK -> collider
            else -> null
        }
        var sphere: JsonObject? = null
        var hitLocation: Xyz? = null
        var targetBlock: IntXyz? = null
        if (chosen == null) {
            targeting.add("targetBlock", JsonNull.INSTANCE)
            unavailable.add("targeting.targetBlock")
            unavailable.add("sphere")
        } else {
            val pos = chosen.blockPos
            hitLocation = xyz(chosen.location)
            targetBlock = IntXyz(pos.x, pos.y, pos.z)
            val state = level.getBlockState(pos)
            val target = JsonObject()
            target.addProperty("source", if (chosen === outline) "outline" else "collider")
            target.add("worldPos", targetBlock.toJson())
            target.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(state.block).toString())
            target.add("properties", properties(state))
            targeting.add("targetBlock", target)
            sphere = captureSphere(level, pos)
        }
        val numbers = ClickNumbers(position, xyz(eye), yaw, pitch, hitLocation, targetBlock, tick, nanoTime)
        return EtwSample(mode, click, targeting, sphere, numbers, unavailable)
    }

    private fun vanillaHit(hit: HitResult?): JsonObject {
        val json = JsonObject()
        if (hit == null) {
            json.addProperty("type", "NONE")
            return json
        }
        json.addProperty("type", hit.type.name)
        json.add("location", xyz(hit.location).toJson())
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            json.add("blockPos", IntXyz(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z).toJson())
            json.addProperty("face", hit.direction.name)
            json.addProperty("inside", hit.isInside)
            json.add("relativeHit", EtwMath.relativeHit(xyz(hit.location), IntXyz(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z)).toJson())
        } else if (hit is EntityHitResult) {
            json.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(hit.entity.type).toString())
        }
        return json
    }

    private fun blockHit(hit: BlockHitResult): JsonObject {
        val json = JsonObject()
        val found = hit.type == HitResult.Type.BLOCK
        json.addProperty("hit", found)
        if (!found) return json
        val pos = IntXyz(hit.blockPos.x, hit.blockPos.y, hit.blockPos.z)
        json.add("blockPos", pos.toJson())
        json.addProperty("face", hit.direction.name)
        json.add("location", xyz(hit.location).toJson())
        json.add("relativeHit", EtwMath.relativeHit(xyz(hit.location), pos).toJson())
        json.addProperty("inside", hit.isInside)
        return json
    }

    /** Every block within the radius-3 sphere around [centre], loaded or not, with what is needed to analyse it. */
    @JvmStatic
    fun captureSphere(level: ClientLevel, centre: BlockPos): JsonObject {
        val blocks = JsonArray()
        val offsets = EtwMath.sphereOffsets(SPHERE_RADIUS)
        for (offset in offsets) {
            val pos = centre.offset(offset[0], offset[1], offset[2])
            val json = JsonObject()
            json.add("worldPos", IntXyz(pos.x, pos.y, pos.z).toJson())
            val relative = JsonObject()
            relative.addProperty("dx", offset[0])
            relative.addProperty("dy", offset[1])
            relative.addProperty("dz", offset[2])
            json.add("relativePos", relative)
            if (!level.hasChunkAt(pos)) {
                json.addProperty("loaded", false)
                json.add("blockId", JsonNull.INSTANCE)
                blocks.add(json)
                continue
            }
            val state = level.getBlockState(pos)
            json.addProperty("loaded", true)
            json.addProperty("blockId", BuiltInRegistries.BLOCK.getKey(state.block).toString())
            json.add("properties", properties(state))
            json.addProperty("isAir", state.isAir)
            val fluid = state.fluidState
            json.add("fluid", if (fluid.isEmpty) JsonNull.INSTANCE else com.google.gson.JsonPrimitive(BuiltInRegistries.FLUID.getKey(fluid.type).toString()))
            val boxes = JsonArray()
            for (box in state.getCollisionShape(level, pos).toAabbs()) {
                val values = JsonArray()
                values.add(box.minX)
                values.add(box.minY)
                values.add(box.minZ)
                values.add(box.maxX)
                values.add(box.maxY)
                values.add(box.maxZ)
                boxes.add(values)
            }
            json.add("collisionBoxes", boxes)
            blocks.add(json)
        }
        val sphere = JsonObject()
        sphere.addProperty("radiusBlocks", SPHERE_RADIUS)
        sphere.addProperty("condition", "dx*dx+dy*dy+dz*dz<=${SPHERE_RADIUS * SPHERE_RADIUS}")
        sphere.add("centre", IntXyz(centre.x, centre.y, centre.z).toJson())
        sphere.addProperty("count", offsets.size)
        sphere.add("blocks", blocks)
        return sphere
    }

    private const val SPHERE_RADIUS = 3

    private fun properties(state: BlockState): JsonObject {
        val json = JsonObject()
        state.values.forEach { entry -> json.addProperty(entry.property().name, entry.valueName()) }
        return json
    }

    @JvmStatic
    fun rawPosition(packet: ClientboundPlayerPositionPacket): JsonObject {
        val change = packet.change()
        val json = JsonObject()
        json.addProperty("teleportId", packet.id())
        json.add("position", xyz(change.position()).toJson())
        json.add("deltaMovement", xyz(change.deltaMovement()).toJson())
        json.addProperty("yaw", change.yRot().toDouble())
        json.addProperty("pitch", change.xRot().toDouble())
        val flags = JsonArray()
        for (relative in packet.relatives()) flags.add(relative.name)
        json.add("relativeFlags", flags)
        return json
    }

    @JvmStatic
    fun rawRotation(packet: ClientboundPlayerRotationPacket): JsonObject {
        val json = JsonObject()
        json.addProperty("yaw", packet.yRot().toDouble())
        json.addProperty("yawRelative", packet.relativeY())
        json.addProperty("pitch", packet.xRot().toDouble())
        json.addProperty("pitchRelative", packet.relativeX())
        return json
    }
}
