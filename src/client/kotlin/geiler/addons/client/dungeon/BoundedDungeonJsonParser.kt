package geiler.addons.client.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal

/** Builds a Gson tree while enforcing response depth and node limits before allocating children. */
object BoundedDungeonJsonParser {
    @JvmStatic
    @Throws(IOException::class)
    fun parse(json: String?, maximumDepth: Int, maximumNodes: Int): JsonElement {
        if (json == null) throw IOException("Response body is unavailable")
        if (maximumDepth < 0 || maximumNodes < 1) throw IllegalArgumentException("Invalid JSON limits")
        val reader = JsonReader(StringReader(json)).apply { isLenient = false }
        val budget = Budget(maximumDepth, maximumNodes)
        val root = readValue(reader, 0, budget)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Response contains trailing JSON data")
        return root
    }

    @Throws(IOException::class)
    private fun readValue(reader: JsonReader, depth: Int, budget: Budget): JsonElement {
        if (depth > budget.maximumDepth || !budget.visit()) throw IOException("JSON limits exceeded")
        return when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> readArray(reader, depth, budget)
            JsonToken.BEGIN_OBJECT -> readObject(reader, depth, budget)
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(ParsedNumber(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> readNull(reader)
            else -> throw IOException("Response contains an unexpected JSON token")
        }
    }

    @Throws(IOException::class)
    private fun readArray(reader: JsonReader, depth: Int, budget: Budget): JsonArray {
        val array = JsonArray()
        reader.beginArray()
        while (reader.hasNext()) array.add(readValue(reader, depth + 1, budget))
        reader.endArray()
        return array
    }

    @Throws(IOException::class)
    private fun readObject(reader: JsonReader, depth: Int, budget: Budget): JsonObject {
        val objectValue = JsonObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            objectValue.add(name, readValue(reader, depth + 1, budget))
        }
        reader.endObject()
        return objectValue
    }

    @Throws(IOException::class)
    private fun readNull(reader: JsonReader): JsonNull {
        reader.nextNull()
        return JsonNull.INSTANCE
    }

    private class Budget(val maximumDepth: Int, private val maximumNodes: Int) {
        private var visited = 0
        fun visit(): Boolean = ++visited <= maximumNodes
    }

    /** Keeps original JSON number spelling and converts only when a consumer reads the value. */
    private class ParsedNumber(private val value: String) : Number() {
        override fun toByte(): Byte = BigDecimal(value).toByte()
        override fun toShort(): Short = BigDecimal(value).toShort()
        override fun toInt(): Int = BigDecimal(value).toInt()
        override fun toLong(): Long = BigDecimal(value).toLong()
        override fun toFloat(): Float = value.toFloat()
        override fun toDouble(): Double = value.toDouble()
        override fun toString(): String = value
    }
}
