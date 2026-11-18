package geiler.addons.client.farming

/** Garden keybind rules, after SkyHanni's custom keybinds: they apply on the Garden while a farming tool is held, off the barn and greenhouse. */
object GardenKeybindRules {
    private val CONTROLS = setOf("key.attack", "key.use", "key.left", "key.right", "key.forward", "key.back", "key.jump", "key.sneak")
    private val TOOL_WORDS = setOf("HOE", "DICER", "VACUUM")
    private val TOOLS = setOf("COCO_CHOPPER", "CACTUS_KNIFE", "FUNGI_CUTTER", "SQUEAKY_MOUSEMAT")

    /** Hoes, dicers, the other crop tools, the mousemat and pest vacuums, by SkyBlock id. */
    @JvmStatic
    fun isFarmingTool(skyblockId: String?): Boolean {
        if (skyblockId == null) return false
        return skyblockId in TOOLS || skyblockId.split('_').any { it in TOOL_WORDS }
    }

    @JvmStatic
    fun isExcludedPlot(plotName: String?): Boolean =
        plotName != null && (plotName.equals("The Barn", true) || plotName.contains("greenhouse", true))

    /** A click is a rise from released to held. */
    @JvmStatic
    fun edge(wasHeld: Boolean, isHeld: Boolean): Boolean = !wasHeld && isHeld

    @JvmStatic
    fun isControl(mappingName: String): Boolean = mappingName in CONTROLS
}

/** The Greenhouse `Crop Diagnostics` menu, after SkyHanni's growth cycle timer and harvestable highlight. */
object GreenhouseRules {
    const val GREEN = 0x8055FF55.toInt()
    const val YELLOW = 0x80FFFF55.toInt()
    const val RED = 0x80FF5555.toInt()
    const val TIMER_SLOT = 20
    const val WATER_SLOT = 21
    const val STATUS_SLOT = 24

    private val NEXT_STAGE = Regex("Next Stage: ((?:[0-9]{1,2}[hms] ?)+)")
    private val PART = Regex("([0-9]{1,2})([hms])")
    private val FORMATTING = Regex("§.")

    @JvmStatic
    fun isDiagnosticsTitle(title: String): Boolean = title.trim() == "Crop Diagnostics"

    /** Seconds until the next growth stage from the timer item's lore, or null. */
    @JvmStatic
    fun nextStageSeconds(lore: List<String>): Long? {
        for (raw in lore) {
            val match = NEXT_STAGE.find(raw.replace(FORMATTING, "").trim()) ?: continue
            return PART.findAll(match.groupValues[1]).sumOf {
                it.groupValues[1].toLong() * when (it.groupValues[2]) { "h" -> 3600L; "m" -> 60L; else -> 1L }
            }
        }
        return null
    }

    /** The status beacon's colour: green when harvestable, yellow when it lists drops or rewards, otherwise red. */
    @JvmStatic
    fun harvestColor(lore: List<String>): Int {
        var color = RED
        for (raw in lore) {
            val line = raw.replace(FORMATTING, "").trim()
            if (line == "Status: Harvestable") color = GREEN
            else if (line.startsWith("Drops: ") || line.startsWith("Rewards: ")) color = YELLOW
        }
        return color
    }

    @JvmStatic
    fun waterOk(lore: List<String>): Boolean =
        lore.any { val line = it.replace(FORMATTING, "").trim(); line == "This crop has enough water to" || line == "This crop doesn't need water!" }

    @JvmStatic
    fun timerText(secondsLeft: Long): String {
        if (secondsLeft <= 0) return "§cOVERDUE"
        val hours = secondsLeft / 3600
        val minutes = secondsLeft % 3600 / 60
        val seconds = secondsLeft % 60
        return when {
            hours > 0 -> "§a${hours}h ${minutes}m"
            minutes > 0 -> "§a${minutes}m ${seconds}s"
            else -> "§a${seconds}s"
        }
    }
}

/** The Bazaar search a shopping list click starts, after SkyHanni's `searchForBazaarItem`: `/bz <name>`, with the amount put on the clipboard. */
object GardenBazaarRules {
    @JvmStatic
    fun command(itemName: String): String = "bz ${itemName.trim()}"

    /** The list row under the mouse, or -1: rows are `lineHeight + 1` tall, all scaled, starting at [top]. */
    @JvmStatic
    fun lineAt(mouseX: Double, mouseY: Double, left: Int, top: Int, width: Int, lineHeight: Int, scale: Float, lineCount: Int): Int {
        val row = (lineHeight + 1) * scale
        if (mouseX < left || mouseX >= left + width * scale || mouseY < top || row <= 0f) return -1
        val index = ((mouseY - top) / row).toInt()
        return if (index in 0 until lineCount) index else -1
    }
}
