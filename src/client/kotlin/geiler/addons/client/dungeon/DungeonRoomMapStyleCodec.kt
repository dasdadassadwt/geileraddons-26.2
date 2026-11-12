package geiler.addons.client.dungeon

import geiler.addons.client.module.Setting

/** Clipboard codec entry points and Java-compatible record values. */
object DungeonRoomMapStyleCodec {
    @JvmStatic
    fun encode(settings: List<Setting?>?): String? = DungeonRoomMapStyleCodecLogic.encode(settings)

    @JvmStatic
    fun encodeBase64(settings: List<Setting?>?): String? = DungeonRoomMapStyleCodecLogic.encodeBase64(settings)

    /** Accepts exported Base64 or directly editable JSON. Invalid inputs never partially apply. */
    @JvmStatic
    fun decodeClipboard(clipboard: String?): DecodeResult = DungeonRoomMapStyleCodecLogic.decodeClipboard(clipboard)

    @JvmStatic
    fun appliesTo(settings: List<Setting?>?, decoded: Decoded?): Boolean =
        DungeonRoomMapStyleCodecLogic.appliesTo(settings, decoded)

    @JvmStatic
    fun apply(settings: List<Setting?>?, decoded: Decoded?) =
        DungeonRoomMapStyleCodecLogic.apply(settings, decoded)

    /** Restores every compatible setting in a style list to its declared default. */
    @JvmStatic
    fun reset(settings: List<Setting?>?): Boolean = DungeonRoomMapStyleCodecLogic.reset(settings)

    @JvmRecord
    data class Rgba(val red: Int, val green: Int, val blue: Int, val alpha: Int)

    @JvmRecord
    data class Decoded(
        val toggles: Map<String?, Boolean?>?,
        val numbers: Map<String?, Float?>?,
        val choices: Map<String?, String?>?,
        val colors: Map<String?, Rgba?>?,
    ) {
        fun isEmpty(): Boolean = toggles!!.isEmpty() && numbers!!.isEmpty() && choices!!.isEmpty() && colors!!.isEmpty()
    }

    @JvmRecord
    data class DecodeResult(val success: Boolean, val decoded: Decoded?, val error: String?)
}
