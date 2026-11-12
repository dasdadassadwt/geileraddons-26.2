package geiler.addons.client.scoreboard

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.util.Optional

/** Bounded, testable JSON codec for the user-owned layout. */
object ScoreboardLayoutCodec {
    private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()
    const val MAX_JSON_CHARS = 131_072

    @JvmStatic
    fun encode(layout: ScoreboardLayout?): String {
        val safe = layout ?: ScoreboardLayout()
        safe.normalize()
        return GSON.toJson(safe)
    }

    @JvmStatic
    fun decode(json: String?): Optional<ScoreboardLayout> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS) return Optional.empty()
        try {
            val layout: ScoreboardLayout? = GSON.fromJson(json, ScoreboardLayout::class.java)
            if (layout == null) return Optional.empty()
            layout.normalize()
            return Optional.of(layout)
        } catch (_: JsonParseException) {
            return Optional.empty()
        } catch (_: IllegalStateException) {
            return Optional.empty()
        } catch (_: NumberFormatException) {
            return Optional.empty()
        }
    }
}
