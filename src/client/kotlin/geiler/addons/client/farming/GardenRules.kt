package geiler.addons.client.farming

import java.util.Locale

/** The `RARE CROP!` drop lines, after SkyHanni's Burrowing Spores notifier (`VERY RARE CROP! Burrowing Spores`), generalised to any item. */
object RareCropRules {
    private val DROP = Regex("^(VERY )?RARE CROP! (\\S.*)$")
    private val TRAILING_BRACKET = Regex("\\s*[(\\[].*$")

    /** The item name of a drop line, or null for any other line. */
    @JvmStatic
    fun name(plain: String): String? {
        val match = DROP.find(plain.trim()) ?: return null
        return match.groupValues[2].replace(TRAILING_BRACKET, "").trim().takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun isVeryRare(plain: String): Boolean = DROP.find(plain.trim())?.groupValues?.get(1)?.isNotEmpty() == true
}

/** The Composter tab-list widget (`Organic Matter`, `Fuel`, `Time Left`, `Stored Compost` rows), as SkyHanni's Composter Display reads it. */
object ComposterRules {
    class Data(val organicMatter: Long?, val fuel: Long?, val timeLeft: String?, val stored: String?)

    private val FORMATTING = Regex("§.")
    private val ROW = Regex("^(Organic Matter|Fuel|Time Left|Stored Compost):\\s*(.+)$")
    private const val MAX_ROWS = 6

    /** The block after the `Composter:` header, or null when the tab list has none. */
    @JvmStatic
    fun parse(lines: List<String>): Data? {
        val start = lines.indexOfFirst { clean(it) == "Composter:" }
        if (start < 0) return null
        var organic: Long? = null
        var fuel: Long? = null
        var time: String? = null
        var stored: String? = null
        for (raw in lines.drop(start + 1).take(MAX_ROWS)) {
            val row = clean(raw)
            if (row.isEmpty()) break
            val match = ROW.find(row) ?: break
            val value = match.groupValues[2]
            when (match.groupValues[1]) {
                "Organic Matter" -> organic = amount(value)
                "Fuel" -> fuel = amount(value)
                "Time Left" -> time = value
                else -> stored = value
            }
        }
        if (organic == null && fuel == null && time == null && stored == null) return null
        return Data(organic, fuel, time, stored)
    }

    /** `12.5k` and `1,234` as numbers; null when it is neither. */
    @JvmStatic
    fun amount(text: String): Long? {
        val value = text.trim().lowercase(Locale.ROOT).replace(",", "")
        val (digits, factor) = when {
            value.endsWith("k") -> value.dropLast(1) to 1_000.0
            value.endsWith("m") -> value.dropLast(1) to 1_000_000.0
            else -> value to 1.0
        }
        return digits.toDoubleOrNull()?.let { Math.round(it * factor) }
    }

    /** The first of Organic Matter and Fuel that is under its threshold (a threshold of 0 or less is off), or null. */
    @JvmStatic
    fun lowResource(data: Data, organicMin: Long, fuelMin: Long): String? = when {
        organicMin > 0 && data.organicMatter != null && data.organicMatter < organicMin -> "Organic Matter"
        fuelMin > 0 && data.fuel != null && data.fuel < fuelMin -> "Fuel"
        else -> null
    }

    @JvmStatic
    fun lines(data: Data): List<String> = listOf(
        "§bComposter",
        "Time Left: §f${data.timeLeft ?: "?"}",
        "Organic Matter: §a${data.organicMatter?.let { "%,d".format(Locale.ROOT, it) } ?: "?"}  §7Fuel: §a${data.fuel?.let { "%,d".format(Locale.ROOT, it) } ?: "?"}",
        "Stored Compost: §f${data.stored ?: "?"}",
    )

    private fun clean(line: String): String = line.replace(FORMATTING, "").trim()
}
