package geiler.addons.client.macros.etw

import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

/**
 * `/etw start` and `/etw end`: a recording session that observes right clicks and the server's resulting position and
 * rotation packets, to study how the server's etherwarp behaves. It observes only: nothing is cancelled, sent,
 * simulated or changed, so it works on any server and is not limited to singleplayer.
 *
 * The hooks are called on the game thread from three small mixins (the use hook of `Minecraft.startUseItem` and the two
 * packet handlers of `ClientPacketListener`); outside a session each returns at once.
 */
object EtwController {
    private val recorder = EtwRecorder({ System.currentTimeMillis() }, {
        Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000)).padStart(4, '0')
    })
    private val diagnostics = ModuleDiagnostics(Category.DEV, { "ETW Recorder" })
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "GeilerAddons ETW writer").apply { isDaemon = true } }

    /** Read by the mixins before anything else, so an idle recorder costs one volatile read. */
    @Volatile private var active = false
    private var clientTicks = 0L
    private var useAttempts = 0
    private var pendingBefore: PlayerState? = null

    @JvmStatic
    fun init() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("etw")
                    .then(ClientCommands.literal("start").executes { context -> start(context.source) })
                    .then(ClientCommands.literal("end").executes { context -> end(context.source) }),
            )
        }
    }

    /** Once per client tick; keeps the diagnostic clock and the tick counter the samples use. */
    @JvmStatic
    fun tick() {
        clientTicks++
        if (!active) return
        diagnostics.advance()
        diagnostics.status { suppressed ->
            "session=${recorder.sessionId()} samples=${recorder.sampleCount()} open=${recorder.openCount()} suppressedEvents=$suppressed"
        }
    }

    private fun start(source: FabricClientCommandSource): Int {
        val client = Minecraft.getInstance()
        val level = client.level
        if (client.player == null || level == null) {
            source.sendError(Component.literal("[ETW] Join a world first."))
            return 0
        }
        val context = JsonObject()
        context.addProperty("minecraftVersion", versionOf("minecraft"))
        context.addProperty("modVersion", versionOf("geileraddons"))
        val server = JsonObject()
        server.addProperty("address", client.currentServer?.ip ?: "local")
        server.addProperty("singleplayer", client.hasSingleplayerServer())
        context.add("server", server)
        context.addProperty("dimension", level.dimension().identifier().toString())
        val id = recorder.start(context)
        if (id == null) {
            source.sendError(Component.literal("[ETW] A recording is already running: use /etw end first."))
            return 0
        }
        useAttempts = 0
        pendingBefore = null
        active = true
        diagnostics.enable("session=$id mode=observe-only")
        source.sendFeedback(Component.literal("[ETW] Recording $id. Right-click to sample (sneak = mode A, normal = mode B); /etw end saves."))
        return 1
    }

    private fun end(source: FabricClientCommandSource): Int {
        if (!active) {
            source.sendError(Component.literal("[ETW] No recording is running."))
            return 0
        }
        active = false
        pendingBefore = null
        val sessionId = recorder.sessionId()
        val document = recorder.end()!!
        val directory = EtwStore.directory()
        val file = EtwStore.uniqueFile(directory, sessionId!!)
        val samples = document.get("sampleCount").asInt
        for (sample in document.getAsJsonArray("samples")) {
            val json = sample.asJsonObject
            if (json.get("status").asString == "no_server_update") {
                diagnostics.event("sample=${json.get("sampleId").asString} phase=open_at_end mode=${json.get("mode").asString} missing=[serverUpdate]")
            }
        }
        diagnostics.event("session=$sessionId phase=ended samples=$samples file=${file.fileName}")
        diagnostics.disable()
        writer.execute {
            try {
                EtwStore.writeAtomically(file, document)
                java.nio.file.Files.deleteIfExists(directory.resolve("$sessionId.partial.json"))
            } catch (error: IOException) {
                GeilerAddons.LOGGER.error("Failed to save the ETW recording to {}", file, error)
            }
        }
        source.sendFeedback(Component.literal("[ETW] Saved $samples sample(s) to $file"))
        return 1
    }

    /** `Minecraft.startUseItem` HEAD: one right-click attempt, before the game acts on it. */
    @JvmStatic
    fun onUseItem() {
        if (!active) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        val player = client.player ?: return
        val level = client.level ?: return
        val mode = EtwRecorder.modeFor(player.isShiftKeyDown)
        val sample = EtwCapture.captureClick(client, player, level, mode, level.gameTime, clientTicks, ++useAttempts)
        val id = recorder.click(sample) ?: return
        val numbers = sample.numbers
        val target = numbers.targetBlock
        diagnostics.event(
            "sample=$id mode=${mode.name} phase=clicked sneaking=${player.isShiftKeyDown} " +
                "pos=${numbers.position.x},${numbers.position.y},${numbers.position.z} yaw=${numbers.yaw} pitch=${numbers.pitch}" +
                (if (mode == EtwMode.A) {
                    " target=${if (target == null) "none" else "${target.x},${target.y},${target.z}"}" +
                        " sphere=${sample.sphere?.get("count")?.asInt ?: 0}"
                } else "") +
                " missing=${sample.unavailable}",
        )
    }

    @JvmStatic
    fun beforePosition(packet: ClientboundPlayerPositionPacket) = before()

    @JvmStatic
    fun afterPosition(packet: ClientboundPlayerPositionPacket) = after(PacketKind.POSITION) { EtwCapture.rawPosition(packet) }

    @JvmStatic
    fun beforeRotation(packet: ClientboundPlayerRotationPacket) = before()

    @JvmStatic
    fun afterRotation(packet: ClientboundPlayerRotationPacket) = after(PacketKind.ROTATION) { EtwCapture.rawRotation(packet) }

    private fun before() {
        if (!active) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        pendingBefore = client.player?.let { EtwCapture.playerState(it) }
    }

    private fun after(kind: PacketKind, raw: () -> JsonObject) {
        if (!active) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        val player = client.player ?: return
        val level = client.level ?: return
        val before = pendingBefore ?: return
        pendingBefore = null
        val observation = PacketObservation(
            kind, level.gameTime, System.nanoTime(), System.currentTimeMillis(), raw(), before, EtwCapture.playerState(player),
        )
        val result = recorder.packet(observation)
        val delta = EtwMath.positionDiff(before.position, observation.after.position)
        if (result.sampleId == null) {
            diagnostics.event(
                "phase=packet_unattributed packet=${kind.name.lowercase()} open=${recorder.openCount()} " +
                    "dpos=${delta.get("total").asDouble} yaw=${observation.after.yaw} pitch=${observation.after.pitch}",
            )
            return
        }
        diagnostics.event(
            "sample=${result.sampleId} mode=${result.mode?.name} phase=completed packet=${kind.name.lowercase()} " +
                "pos=${observation.after.position.x},${observation.after.position.y},${observation.after.position.z} " +
                "yaw=${observation.after.yaw} pitch=${observation.after.pitch} ambiguous=${result.ambiguous} missing=[]",
        )
        val snapshot = recorder.snapshot() ?: return
        val file = EtwStore.directory().resolve("${recorder.sessionId()}.partial.json")
        writer.execute {
            try {
                EtwStore.writeAtomically(file, snapshot)
            } catch (error: IOException) {
                GeilerAddons.LOGGER.warn("Failed to write the ETW partial recording {}", file, error)
            }
        }
    }

    private fun versionOf(modId: String): String =
        FabricLoader.getInstance().getModContainer(modId).map { it.metadata.version.friendlyString }.orElse("unknown")
}
