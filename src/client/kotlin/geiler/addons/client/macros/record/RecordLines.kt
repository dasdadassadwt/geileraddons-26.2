package geiler.addons.client.macros.record

import java.util.Locale

/**
 * The lines of a recording, one JSON object each, built by hand so the recorder allocates little on the frame path. Every
 * line has a kind `k` and a time `t` in milliseconds since the recording began.
 *
 * - `f` frame: the view, once per rendered frame. `yaw`, `pitch`.
 * - `t` tick: position, motion, keys, slot, attack key and what the crosshair is on.
 * - `m` mobs: the mobs of the kill list in range, with distance, bearing off the view and crowd count.
 * - `e` event: a named moment (target, hit, kill) with free text.
 */
object RecordLines {
    class MobSample(
        @JvmField val id: Int,
        @JvmField val type: String,
        @JvmField val distance: Double,
        /** Degrees from where the view points, negative to the left. */
        @JvmField val bearing: Double,
        /** Height of the mob above the player's feet. */
        @JvmField val dy: Double,
        /** How many other listed mobs are within four blocks of it. */
        @JvmField val crowd: Int,
    )

    @JvmStatic
    fun frame(t: Long, yaw: Float, pitch: Float): String =
        String.format(Locale.ROOT, "{\"k\":\"f\",\"t\":%d,\"yaw\":%.4f,\"pitch\":%.4f}", t, yaw, pitch)

    @JvmStatic
    fun tick(
        t: Long, tick: Long, x: Double, y: Double, z: Double, vx: Double, vz: Double, keys: String, slot: Int, attack: Boolean, on: String?,
    ): String = String.format(
        Locale.ROOT,
        "{\"k\":\"t\",\"t\":%d,\"tick\":%d,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"vx\":%.4f,\"vz\":%.4f,\"keys\":%s,\"slot\":%d,\"atk\":%b,\"on\":%s}",
        t, tick, x, y, z, vx, vz, quote(keys), slot, attack, if (on == null) "null" else quote(on),
    )

    @JvmStatic
    fun mobs(t: Long, list: List<MobSample>): String {
        val sb = StringBuilder(64 + list.size * 80)
        sb.append("{\"k\":\"m\",\"t\":").append(t).append(",\"mobs\":[")
        for ((i, m) in list.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(
                String.format(
                    Locale.ROOT, "{\"id\":%d,\"type\":%s,\"dist\":%.2f,\"bearing\":%.1f,\"dy\":%.2f,\"crowd\":%d}",
                    m.id, quote(m.type), m.distance, m.bearing, m.dy, m.crowd,
                ),
            )
        }
        return sb.append("]}").toString()
    }

    @JvmStatic
    fun event(t: Long, name: String, info: String): String =
        "{\"k\":\"e\",\"t\":$t,\"ev\":${quote(name)},\"info\":${quote(info)}}"

    /** The keys held, as letters: W A S D, J jump, C crouch, R sprint. */
    @JvmStatic
    fun keys(forward: Boolean, backward: Boolean, left: Boolean, right: Boolean, jump: Boolean, sneak: Boolean, sprint: Boolean): String {
        val sb = StringBuilder(7)
        if (forward) sb.append('W')
        if (left) sb.append('A')
        if (backward) sb.append('S')
        if (right) sb.append('D')
        if (jump) sb.append('J')
        if (sneak) sb.append('C')
        if (sprint) sb.append('R')
        return sb.toString()
    }

    private fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (ch in text) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
