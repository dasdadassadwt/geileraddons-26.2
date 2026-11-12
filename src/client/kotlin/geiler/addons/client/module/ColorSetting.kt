package geiler.addons.client.module

import net.minecraft.util.ARGB
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ColorSetting private constructor(
    private val settingName: String,
    private val label: String,
    private val alphaEditable: Boolean,
    red: Int,
    green: Int,
    blue: Int,
    alpha: Int,
) : Setting {
    enum class Channel { RED, GREEN, BLUE, ALPHA }

    private val defaultRed = clamp(red)
    private val defaultGreen = clamp(green)
    private val defaultBlue = clamp(blue)
    private val defaultAlpha = if (alphaEditable) clamp(alpha) else 255
    private var currentRed = 0
    private var currentGreen = 0
    private var currentBlue = 0
    private var currentAlpha = 255

    /** The last hue chosen in the picker remains visible when the resulting colour is gray. */
    private var currentHue = 0f
    private var currentSaturation = 0f
    private var currentBrightness = 0f

    constructor(name: String, red: Int, green: Int, blue: Int, alpha: Int) :
        this(name, name, true, red, green, blue, alpha)

    /** Creates an opaque RGB colour whose alpha cannot be edited or persisted. */
    constructor(name: String, red: Int, green: Int, blue: Int) :
        this(name, name, false, red, green, blue, 255)

    constructor(name: String, displayName: String, red: Int, green: Int, blue: Int, alpha: Int) :
        this(name, displayName, true, red, green, blue, alpha)

    /** Creates an opaque RGB colour with a separate persisted key and display label. */
    constructor(name: String, displayName: String, red: Int, green: Int, blue: Int) :
        this(name, displayName, false, red, green, blue, 255)

    init {
        set(red, green, blue, alpha)
    }

    override fun name(): String = settingName

    override fun displayName(): String = label

    fun red(): Int = currentRed

    fun green(): Int = currentGreen

    fun blue(): Int = currentBlue

    fun alpha(): Int = currentAlpha

    fun alphaEditable(): Boolean = alphaEditable

    fun channel(channel: Channel?): Int = when (channel) {
        Channel.RED -> currentRed
        Channel.GREEN -> currentGreen
        Channel.BLUE -> currentBlue
        Channel.ALPHA -> currentAlpha
        null -> 0
    }

    fun setChannel(channel: Channel?, value: Int) {
        if (channel == null || channel == Channel.ALPHA && !alphaEditable) return
        val bounded = clamp(value)
        when (channel) {
            Channel.RED -> currentRed = bounded
            Channel.GREEN -> currentGreen = bounded
            Channel.BLUE -> currentBlue = bounded
            Channel.ALPHA -> currentAlpha = bounded
        }
        if (channel != Channel.ALPHA) syncHsv()
    }

    fun set(red: Int, green: Int, blue: Int, alpha: Int) {
        currentRed = clamp(red)
        currentGreen = clamp(green)
        currentBlue = clamp(blue)
        currentAlpha = if (alphaEditable) clamp(alpha) else 255
        syncHsv()
    }

    fun reset() = set(defaultRed, defaultGreen, defaultBlue, defaultAlpha)

    fun hue(): Float = currentHue

    fun saturation(): Float = currentSaturation

    fun brightness(): Float = currentBrightness

    fun setHue(hue: Float) = setHsv(hue, currentSaturation, currentBrightness)

    fun setSaturationBrightness(saturation: Float, brightness: Float) =
        setHsv(currentHue, saturation, brightness)

    /** Sets the colour from HSV while retaining the selected hue for gray results. */
    fun setHsv(hue: Float, saturation: Float, brightness: Float) {
        currentHue = wrap(hue)
        currentSaturation = clamp01(saturation)
        currentBrightness = clamp01(brightness)

        val chroma = currentBrightness * currentSaturation
        val sector = currentHue * 6.0f
        val second = chroma * (1 - abs(sector % 2 - 1))
        val floor = currentBrightness - chroma
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        currentRed = Math.round((r + floor) * 255)
        currentGreen = Math.round((g + floor) * 255)
        currentBlue = Math.round((b + floor) * 255)
    }

    /** RRGGBBAA for editable-alpha values, otherwise RRGGBB. */
    fun hex(): String = if (alphaEditable) {
        String.format("%02X%02X%02X%02X", currentRed, currentGreen, currentBlue, currentAlpha)
    } else {
        String.format("%02X%02X%02X", currentRed, currentGreen, currentBlue)
    }

    /** Returns false when the text is malformed, leaving the colour untouched. */
    fun setHex(text: String?): Boolean {
        if (text == null) return false
        var digits = text.trim { it <= ' ' }
        if (digits.startsWith("#")) digits = digits.substring(1)
        if (digits.length != 6 && (!alphaEditable || digits.length != 8)) return false
        return try {
            val red = digits.substring(0, 2).toInt(16)
            val green = digits.substring(2, 4).toInt(16)
            val blue = digits.substring(4, 6).toInt(16)
            val alpha = if (alphaEditable && digits.length == 8) digits.substring(6, 8).toInt(16) else currentAlpha
            set(red, green, blue, alpha)
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    private fun syncHsv() {
        val maxChannel = max(currentRed, max(currentGreen, currentBlue))
        val minChannel = min(currentRed, min(currentGreen, currentBlue))
        currentBrightness = maxChannel / 255.0f
        currentSaturation = if (maxChannel == 0) 0f else (maxChannel - minChannel) / maxChannel.toFloat()

        if (maxChannel == minChannel) return
        val span = (maxChannel - minChannel).toFloat()
        val derived = when (maxChannel) {
            currentRed -> ((currentGreen - currentBlue) / span) % 6
            currentGreen -> (currentBlue - currentRed) / span + 2
            else -> (currentRed - currentGreen) / span + 4
        }
        currentHue = wrap(derived / 6.0f)
    }

    /** Packed ARGB int (0xAARRGGBB), matching Minecraft's GUI color APIs. */
    fun argb(): Int = ARGB.color(currentAlpha, currentRed, currentGreen, currentBlue)

    /** Same color at full opacity, for swatch previews. */
    fun opaqueArgb(): Int = ARGB.color(255, currentRed, currentGreen, currentBlue)

    private fun clamp01(value: Float): Float = if (!value.isFinite()) 0f else value.coerceIn(0f, 1f)

    private fun wrap(hue: Float): Float {
        if (!hue.isFinite()) return 0f
        val wrapped = hue % 1.0f
        return if (wrapped < 0) wrapped + 1.0f else wrapped
    }

    companion object {
        private fun clamp(value: Int): Int = value.coerceIn(0, 255)
    }
}
