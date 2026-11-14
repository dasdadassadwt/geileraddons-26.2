package geiler.addons.client.macros.etw

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The session and sample bookkeeping of `/etw`, free of game state.
 *
 * It only records. A click arrives as a finished [EtwSample]; a server packet arrives as a [PacketObservation]. A
 * packet completes the **oldest** open sample that accepts its kind (Mode A waits for a position packet, Mode B for a
 * position or a rotation packet), and the sample is flagged `ambiguous` when more than one was open at that moment,
 * because the packet itself does not say which click it answers. Every packet is also listed on every open sample and on
 * the latest clicked one, so overlapping clicks can be re-attributed offline. There is no timeout: a sample that never
 * gets an answer is saved as `no_server_update` when the session ends.
 */
class EtwRecorder(private val clock: () -> Long, private val randomHex: () -> String) {
    /** Which sample a packet completed, if any, for the log line. */
    class PacketResult(val sampleId: String?, val ambiguous: Boolean, val mode: EtwMode?)

    private class Entry(val id: String, val sample: EtwSample) {
        val click: JsonObject = sample.click.deepCopy()
        val targeting: JsonObject? = sample.targeting?.deepCopy()
        val sphere: JsonObject? = sample.sphere?.deepCopy()
        var status = STATUS_OPEN
        var ambiguous = false
        var serverUpdate: JsonObject? = null
        var diff: JsonObject? = null
        val observed = JsonArray()
    }

    private var sessionId: String? = null
    private var startedAt = 0L
    private var context = JsonObject()
    private val entries = ArrayList<Entry>()
    private val unattributed = JsonArray()

    val isActive: Boolean get() = sessionId != null

    fun sessionId(): String? = sessionId

    fun openCount(): Int = entries.count { it.status == STATUS_OPEN }

    fun sampleCount(): Int = entries.size

    /** Starts a session; null when one is already running. [worldContext] is copied into the saved document. */
    fun start(worldContext: JsonObject): String? {
        if (sessionId != null) return null
        startedAt = clock()
        val stamp = FORMAT.format(Instant.ofEpochMilli(startedAt))
        sessionId = "etw-$stamp-${randomHex()}"
        context = worldContext.deepCopy()
        entries.clear()
        while (unattributed.size() > 0) unattributed.remove(0)
        return sessionId
    }

    /** Records one click; the sample id, or null outside a session. */
    fun click(sample: EtwSample): String? {
        if (sessionId == null) return null
        val id = "S" + (entries.size + 1).toString().padStart(4, '0')
        entries.add(Entry(id, sample))
        return id
    }

    /** Offers a server packet to the open samples. */
    fun packet(observation: PacketObservation): PacketResult {
        if (sessionId == null) return PacketResult(null, false, null)
        val open = entries.filter { it.status == STATUS_OPEN }
        val target = open.firstOrNull { accepts(it.sample.mode, observation.kind) }
        val ambiguous = open.size > 1
        val json = observation.toJson()
        val listening = LinkedHashSet<Entry>(open)
        entries.lastOrNull()?.let { listening.add(it) }
        for (entry in listening) entry.observed.add(json.deepCopy())
        if (target == null) {
            unattributed.add(json.deepCopy())
            return PacketResult(null, false, null)
        }
        complete(target, observation, ambiguous)
        return PacketResult(target.id, ambiguous, target.sample.mode)
    }

    /** Finishes the session and returns the document to save; null when none was running. */
    fun end(): JsonObject? {
        if (sessionId == null) return null
        for (entry in entries) {
            if (entry.status == STATUS_OPEN) entry.status = STATUS_NO_UPDATE
        }
        val document = document(clock())
        sessionId = null
        entries.clear()
        return document
    }

    /** The session as it stands, with open samples still open; for the crash-safe partial file. */
    fun snapshot(): JsonObject? = if (sessionId == null) null else document(null)

    private fun complete(entry: Entry, observation: PacketObservation, ambiguous: Boolean) {
        val numbers = entry.sample.numbers
        entry.status = STATUS_COMPLETED
        entry.ambiguous = ambiguous
        val update = JsonObject()
        update.addProperty("detected", true)
        update.addProperty("packetType", observation.kind.label)
        update.addProperty("ticksSinceClick", observation.tick - numbers.tick)
        update.addProperty("millisSinceClick", (observation.nanoTime - numbers.nanoTime) / 1.0e6)
        update.addProperty("tick", observation.tick)
        update.addProperty("epochMs", observation.epochMs)
        update.add("raw", observation.raw.deepCopy())
        update.add("before", observation.before.toJson())
        update.add("after", observation.after.toJson())
        entry.serverUpdate = update

        val after = observation.after
        val diff = JsonObject()
        diff.add("position", EtwMath.positionDiff(numbers.position, after.position))
        val yaw = after.yaw - numbers.yaw
        diff.addProperty("yaw", yaw)
        diff.addProperty("pitch", after.pitch - numbers.pitch)
        diff.addProperty("yawWrapped180", EtwMath.wrapDegrees(yaw))
        if (entry.sample.mode == EtwMode.A) {
            val block = numbers.targetBlock
            val hit = numbers.hitLocation
            diff.add("resultRelativeToTargetBlock", if (block == null) JsonNull.INSTANCE else delta(after.position - Xyz(block.x.toDouble(), block.y.toDouble(), block.z.toDouble())))
            diff.add("resultRelativeToHit", if (hit == null) JsonNull.INSTANCE else delta(after.position - hit))
        }
        entry.diff = diff
    }

    private fun delta(value: Xyz): JsonObject {
        val json = JsonObject()
        json.addProperty("dx", value.x)
        json.addProperty("dy", value.y)
        json.addProperty("dz", value.z)
        return json
    }

    private fun document(endedAt: Long?): JsonObject {
        val document = JsonObject()
        document.addProperty("schemaVersion", SCHEMA_VERSION)
        document.addProperty("sessionId", sessionId)
        document.addProperty("startedAtEpochMs", startedAt)
        if (endedAt == null) document.add("endedAtEpochMs", JsonNull.INSTANCE) else document.addProperty("endedAtEpochMs", endedAt)
        for ((key, value) in context.entrySet()) document.add(key, value.deepCopy())
        document.addProperty("sampleCount", entries.size)
        document.add("unattributedPackets", unattributed.deepCopy())
        val samples = JsonArray()
        for (entry in entries) samples.add(sampleJson(entry))
        document.add("samples", samples)
        return document
    }

    private fun sampleJson(entry: Entry): JsonObject {
        val json = JsonObject()
        json.addProperty("sampleId", entry.id)
        json.addProperty("mode", entry.sample.mode.name)
        json.addProperty("status", entry.status)
        json.addProperty("ambiguous", entry.ambiguous)
        json.add("click", entry.click.deepCopy())
        json.add("targeting", entry.targeting?.deepCopy() ?: JsonNull.INSTANCE)
        json.add("sphere", entry.sphere?.deepCopy() ?: JsonNull.INSTANCE)
        json.add("serverUpdate", entry.serverUpdate?.deepCopy() ?: JsonNull.INSTANCE)
        json.add("diff", entry.diff?.deepCopy() ?: JsonNull.INSTANCE)
        json.add("observedPacketsSinceClick", entry.observed.deepCopy())
        val unavailable = JsonArray()
        for (field in entry.sample.unavailable) unavailable.add(field)
        json.add("unavailableFields", unavailable)
        return json
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val STATUS_OPEN = "open"
        private const val STATUS_COMPLETED = "completed"
        private const val STATUS_NO_UPDATE = "no_server_update"
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

        /** Sneaking at the instant of the click selects Mode A; a normal click selects Mode B. */
        @JvmStatic
        fun modeFor(sneaking: Boolean): EtwMode = if (sneaking) EtwMode.A else EtwMode.B

        private fun accepts(mode: EtwMode, kind: PacketKind): Boolean =
            kind == PacketKind.POSITION || mode == EtwMode.B
    }
}
