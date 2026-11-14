package geiler.addons.client.macros.record

import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.killer.TargetScanner
import geiler.addons.client.macros.rotation.FrameRotations
import geiler.addons.client.macros.rotation.FrameSteered
import geiler.addons.client.macros.rotation.SmoothRotation
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.EntityHitResult
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Records how a person plays the Area Killer's job by hand: where the view points every frame, the keys and position every
 * tick, which mobs of the kill list are around and where, what the crosshair is on, every click and every kill. It only reads
 * the client's own state and writes one local file (see [RecordingStore], bounded); nothing is sent anywhere and nothing is
 * changed in the game. The file is what the Rotation Engine and the target planner are tuned against, with
 * `tools/analyze_recording.py`.
 *
 * Started and stopped by the Human Recorder module (`/ga record start|stop`).
 */
object HumanRecorder : FrameSteered {
    /** The most the recording takes on disk. The oldest samples are rotated out beyond it. */
    private const val MAX_BYTES = 20L * 1024L * 1024L
    private const val MOB_SAMPLE_TICKS = 5
    private const val MOB_RANGE = 48.0
    private const val MOB_LIMIT = 40
    private const val CROWD_RADIUS = 4.0

    /** Mobs this close are watched for dying, tick by tick. */
    private const val KILL_WATCH_RANGE = 12.0
    private const val FLUSH_TICKS = 20L

    /** Receives one line per start, stop, click, target change and kill, for the module's diagnostics. */
    @JvmField
    var diagnostics: (String) -> Unit = {}

    private val scanner = TargetScanner()
    private var store: RecordingStore? = null
    private var file: Path? = null
    private var startNanos = 0L
    private var ticks = 0L
    private var frames = 0L
    private var clicks = 0
    private var kills = 0
    private var lastOn: String? = null
    private var lastAttack = false
    private var lastKillNanos = 0L
    private val watched = HashMap<Int, Entity>()

    init {
        FrameRotations.register(this)
    }

    fun isRecording(): Boolean = store != null

    /** The newest recording's first segment, kept after it stops so the command can say where it is. */
    fun lastFile(): Path? = file

    fun status(): String =
        if (store == null) {
            "not recording" + (file?.let { "; last: ${it.parent}" } ?: "")
        } else {
            "recording ticks=$ticks frames=$frames clicks=$clicks kills=$kills bytes=${store?.bytes()} dir=${file?.parent}"
        }

    /** Starts a recording in the config folder; returns the folder, or null when there is no world or the file cannot be made. */
    fun start(): Path? {
        val client = Minecraft.getInstance()
        val player = client.player ?: return null
        stop("restarted")
        val id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val dir = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("recordings")
        val mobs = MacroRuntime.config.mobs
        return try {
            val header = RecordLines.event(0L, "meta", "session=$id format=1 mobs=${mobs.joinToString(",")} player=${player.name.string.length}chars")
            store = RecordingStore(dir, id, MAX_BYTES, header)
            file = dir.resolve("$id.0.jsonl")
            startNanos = System.nanoTime()
            ticks = 0L
            frames = 0L
            clicks = 0
            kills = 0
            lastOn = null
            lastAttack = false
            lastKillNanos = startNanos
            watched.clear()
            diagnostics("recording started dir=$dir session=$id mobs=${mobs.size} limitMB=${MAX_BYTES / 1024 / 1024}")
            dir
        } catch (e: IOException) {
            store = null
            diagnostics("recording could not start: ${e.message}")
            null
        }
    }

    fun stop(reason: String) {
        val open = store ?: return
        val summary = "recording stopped reason='$reason' ticks=$ticks frames=$frames clicks=$clicks kills=$kills bytes=${open.bytes()} dir=${open.directory()}"
        try {
            append(RecordLines.event(elapsed(), "stop", "reason=$reason clicks=$clicks kills=$kills"))
            open.close()
        } catch (_: IOException) {
            // Closing a file that cannot be written is not worth more than the line below.
        }
        store = null
        watched.clear()
        diagnostics(summary)
    }

    private fun elapsed(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun append(line: String) {
        val open = store ?: return
        try {
            open.append(line)
        } catch (e: IOException) {
            store = null
            watched.clear()
            diagnostics("recording stopped: write failed: ${e.message}")
        }
    }

    /** One line per rendered frame: the view, from the same point of the frame at which the mouse is read. */
    override fun advance(player: LocalPlayer, deltaSeconds: Float) {
        if (store == null) return
        frames++
        append(RecordLines.frame(elapsed(), player.yRot, player.xRot))
    }

    private fun label(entity: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path + "#" + entity.id

    /** Once per client tick while recording. */
    fun tick(client: Minecraft) {
        val open = store ?: return
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            stop("left the world")
            return
        }
        ticks++
        val t = elapsed()

        val hit = client.hitResult
        val on = (hit as? EntityHitResult)?.entity
        val onLabel = on?.let { label(it) }
        val attack = client.options.keyAttack.isDown
        val keys = player.input.keyPresses
        append(
            RecordLines.tick(
                t, ticks, player.x, player.y, player.z, player.deltaMovement.x, player.deltaMovement.z,
                RecordLines.keys(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint()),
                player.inventory.selectedSlot, attack, onLabel,
            ),
        )

        if (onLabel != lastOn) {
            if (on != null && scanner.isValid(level, on, MacroRuntime.config.mobs)) {
                val wanted = SmoothRotation.lookAt(player.eyePosition, on.boundingBox.center)
                val line = "mob=$onLabel dist=${one(player.distanceTo(on).toDouble())} " +
                    "offYaw=${one(Mth.wrapDegrees(wanted.yaw - player.yRot).toDouble())} offPitch=${one((wanted.pitch - player.xRot).toDouble())}"
                append(RecordLines.event(t, "target", line))
                diagnostics("target $line")
            }
            lastOn = onLabel
        }
        if (attack && !lastAttack) {
            clicks++
            append(RecordLines.event(t, "click", "on=${onLabel ?: "none"} slot=${player.inventory.selectedSlot}"))
        }
        lastAttack = attack

        if (ticks % MOB_SAMPLE_TICKS == 0L) sampleMobs(player, level, t)
        watchKills(player, t)
        if (ticks % FLUSH_TICKS == 0L) {
            try {
                open.flush()
            } catch (e: IOException) {
                stop("flush failed: ${e.message}")
            }
        }
    }

    private fun one(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

    private fun sampleMobs(player: LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel, t: Long) {
        val found = level.getEntities(player, player.boundingBox.inflate(MOB_RANGE)) { scanner.isValid(level, it, MacroRuntime.config.mobs) }
            .sortedBy { player.distanceToSqr(it) }
            .take(MOB_LIMIT)
        val list = ArrayList<RecordLines.MobSample>(found.size)
        for (entity in found) {
            val toYaw = Math.toDegrees(atan2(-(entity.x - player.x), entity.z - player.z))
            val crowd = found.count { it !== entity && hypot(it.x - entity.x, it.z - entity.z) <= CROWD_RADIUS }
            list.add(
                RecordLines.MobSample(
                    entity.id, BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path, player.distanceTo(entity).toDouble(),
                    Mth.wrapDegrees(toYaw - player.yRot), entity.y - player.y, crowd,
                ),
            )
            if (player.distanceTo(entity) <= KILL_WATCH_RANGE) watched[entity.id] = entity
        }
        append(RecordLines.mobs(t, list))
    }

    /** A watched mob that is dead or gone from beside the player was a kill; it is told apart from a chunk going by the death. */
    private fun watchKills(player: LocalPlayer, t: Long) {
        val iterator = watched.values.iterator()
        while (iterator.hasNext()) {
            val entity = iterator.next()
            val dead = entity is LivingEntity && entity.isDeadOrDying || entity.removalReason == Entity.RemovalReason.KILLED
            if (!dead && entity.isAlive && !entity.isRemoved) continue
            iterator.remove()
            if (!dead) continue
            kills++
            val now = System.nanoTime()
            val since = (now - lastKillNanos) / 1_000_000L
            lastKillNanos = now
            val line = "mob=${label(entity)} sinceLastKillMs=$since dist=${one(player.distanceTo(entity).toDouble())}"
            append(RecordLines.event(t, "kill", line))
            diagnostics("kill $line")
        }
    }
}
