package geiler.addons.client.dungeon

import geiler.addons.client.tree.ChatText
import geiler.addons.client.chat.ChatStringSemantics
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.item.TooltipFlag
import java.time.Duration
import java.util.Locale
import java.util.regex.Pattern

/** Captures the selected party floor and promotes it only after server confirmation. */
object DungeonQueueFloorTracker {
    private val candidateMaxAgeNanos = Duration.ofSeconds(15).toNanos()
    private val currentlySelected = Pattern.compile("(?i)^Currently Selected:\\s*(.+)$")
    private val floorPattern = Pattern.compile("(?i)^(?:Floor\\s*)?(?:(F|M)\\s*)?(Entrance|VII|VI|IV|V|III|II|I|[1-7])$")
    private val listingFloorPattern = Pattern.compile(
        "(?i)^Floor:?\\s*(?:Floor\\s*)?(?:(F|M)\\s*)?(Entrance|VII|VI|IV|V|III|II|I|[1-7])$",
    )

    private var candidate: DungeonFloor? = null
    private var candidateCapturedAt = 0L
    private var joinedListingCandidate: DungeonFloor? = null
    private var joinedListingCapturedAt = 0L
    private var current: DungeonFloor? = null
    private var trackedLevel: Any? = null

    /** Reads a validated Group Builder confirmation click before the menu closes. */
    @JvmStatic
    fun captureConfirmation(
        screen: AbstractContainerScreen<*>?,
        clickedSlot: Slot?,
        button: Int,
        input: ContainerInput?,
    ): CaptureResult {
        if (screen == null || clickedSlot == null || button != 0 || input != ContainerInput.PICKUP) {
            return result(attempted = false)
        }
        if (!clean(screen.title.string).equals("Group Builder", ignoreCase = true)) {
            return result(attempted = false)
        }

        val entries = menuEntries(Minecraft.getInstance(), screen)
        val clicked = entries.firstOrNull { it.slot == clickedSlot.index }
        if (clicked == null || !clicked.emeraldBlock || !clicked.name.equals("Confirm Group", ignoreCase = true)) {
            return result(attempted = false)
        }
        if (clicked.tooltip.none { it.equals("Click to confirm!", ignoreCase = true) }) {
            clearCandidate()
            return result(attempted = true, error = "the Confirm Group item did not contain its confirmation lore")
        }

        val dungeonType = selectedValue(entries, "Select Dungeon Type")
        val floorValue = selectedValue(entries, "Select Floor")
        if (dungeonType == null || floorValue == null) {
            clearCandidate()
            return result(attempted = true, error = "the selected dungeon type or floor was missing from the menu lore")
        }

        val selected = normalizeGroupBuilderSelection(dungeonType, floorValue)
        if (selected.error != null) {
            clearCandidate()
            return result(attempted = true, error = selected.error)
        }

        val captured = selected.floor
        if (captured == null) {
            clearCandidate()
            return result(attempted = true, error = "the selected floor could not be normalized")
        }
        candidate = captured
        candidateCapturedAt = System.nanoTime()
        return result(attempted = true, floor = captured)
    }

    /** Captures the advertised floor from the Party Finder listing the local player clicks. */
    @JvmStatic
    fun captureJoinedListing(
        screen: AbstractContainerScreen<*>?,
        clickedSlot: Slot?,
        button: Int,
        input: ContainerInput?,
    ): CaptureResult {
        if (screen == null || clickedSlot == null || button != 0 || input != ContainerInput.PICKUP) {
            return result(attempted = false)
        }
        if (!clean(screen.title.string).lowercase(Locale.ROOT).contains("party finder")) {
            return result(attempted = false)
        }

        val entries = menuEntries(Minecraft.getInstance(), screen)
        val clicked = entries.firstOrNull { it.slot == clickedSlot.index }
        if (clicked == null || !clicked.playerHead) return result(attempted = false)

        val listing = normalizeListingFloor(clicked.tooltip)
        if (listing.error != null) {
            clearJoinedListingCandidate()
            return result(attempted = true, error = listing.error)
        }
        val captured = listing.floor
        if (captured == null) {
            clearJoinedListingCandidate()
            return result(attempted = true, error = "the clicked listing floor could not be normalized")
        }
        joinedListingCandidate = captured
        joinedListingCapturedAt = System.nanoTime()
        return result(attempted = true, floor = captured)
    }

    /** Promotes only a fresh Group Builder selection after the exact queue-success chat message. */
    @JvmStatic
    fun confirmQueued(): DungeonFloor? {
        val now = System.nanoTime()
        current = candidate.takeIf { isFreshCandidate(candidateCapturedAt, now, candidateMaxAgeNanos) }
        clearCandidate()
        clearJoinedListingCandidate()
        return current
    }

    /** Promotes a freshly clicked listing when the server says the local player joined it. */
    @JvmStatic
    fun confirmJoinedListing(): DungeonFloor? {
        val now = System.nanoTime()
        current = joinedListingCandidate.takeIf {
            isFreshCandidate(joinedListingCapturedAt, now, candidateMaxAgeNanos)
        }
        clearJoinedListingCandidate()
        clearCandidate()
        return current
    }

    @JvmStatic
    fun currentFloor(): DungeonFloor? = current

    /** Prevents a floor from leaking into another server/world. */
    @JvmStatic
    fun tick(client: Minecraft?): Boolean {
        val level = client?.level
        if (level === trackedLevel) return false
        trackedLevel = level
        val changed = current != null || candidate != null || joinedListingCandidate != null
        current = null
        clearCandidate()
        clearJoinedListingCandidate()
        return changed
    }

    @JvmStatic
    fun clear(): Boolean {
        val changed = current != null || candidate != null || joinedListingCandidate != null
        current = null
        clearCandidate()
        clearJoinedListingCandidate()
        return changed
    }

    /** Package-level regression seam, used by the Java offline check harness. */
    @JvmStatic
    fun parseGroupBuilderSelection(dungeonType: String?, floorValue: String?): DungeonFloor? =
        normalizeGroupBuilderSelection(dungeonType, floorValue).floor

    /** Package-level regression seam, used by the Java offline check harness. */
    @JvmStatic
    fun parseJoinedListing(tooltip: List<String>): DungeonFloor? = normalizeListingFloor(tooltip).floor

    /** Package-level regression seam for the 15-second monotonic candidate boundary. */
    @JvmStatic
    fun isFreshCandidate(capturedAt: Long, now: Long, maximumAge: Long): Boolean =
        capturedAt > 0L && now >= capturedAt && now - capturedAt <= maximumAge

    private fun result(
        attempted: Boolean,
        floor: DungeonFloor? = null,
        error: String? = null,
    ) = CaptureResult(attempted, floor, error)

    private fun menuEntries(client: Minecraft?, screen: AbstractContainerScreen<*>): List<MenuEntry> {
        val currentClient = client ?: return emptyList()
        val player = currentClient.player ?: return emptyList()
        val level = currentClient.level
        val context = if (level == null) Item.TooltipContext.EMPTY else Item.TooltipContext.of(level)
        val entries = ArrayList<MenuEntry>()
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory || slot.item.isEmpty) continue
            val tooltip = ArrayList<String>()
            try {
                slot.item.getTooltipLines(context, player, TooltipFlag.NORMAL)
                    .forEach { line -> tooltip.add(clean(line.string)) }
            } catch (_: RuntimeException) {
                continue
            }
            entries.add(
                MenuEntry(
                    slot.index,
                    clean(slot.item.hoverName.string),
                    tooltip.toList(),
                    slot.item.`is`(Items.EMERALD_BLOCK),
                    slot.item.`is`(Items.PLAYER_HEAD),
                ),
            )
        }
        return entries
    }

    private fun selectedValue(entries: List<MenuEntry>, itemName: String): String? {
        for (entry in entries) {
            if (!entry.name.equals(itemName, ignoreCase = true)) continue
            for (line in entry.tooltip) {
                val matcher = currentlySelected.matcher(line)
                if (matcher.matches()) return ChatStringSemantics.trim(matcher.group(1))
            }
        }
        return null
    }

    private fun normalizeGroupBuilderSelection(dungeonType: String?, floorValue: String?): FloorParseResult {
        if (dungeonType == null || floorValue == null) {
            return FloorParseResult(null, "the selected dungeon type or floor was missing from the menu lore")
        }
        val normalizedType = clean(dungeonType).lowercase(Locale.ROOT)
        if (!normalizedType.contains("catacombs")) {
            return FloorParseResult(null, "the selected dungeon type was not Catacombs")
        }

        val floorMatcher = floorPattern.matcher(clean(floorValue))
        if (!floorMatcher.matches()) {
            return FloorParseResult(null, "the selected floor was not F1-F7 or M1-M7: ${clean(floorValue)}")
        }
        val entrance = floorMatcher.group(2).equals("Entrance", ignoreCase = true)
        val floorNumber = if (entrance) 0 else DungeonFloor.romanFloor(floorMatcher.group(2))
        val master = normalizedType.contains("master mode") || normalizedType.contains("master catacombs") ||
            floorMatcher.group(1)?.equals("M", ignoreCase = true) == true
        val floor = DungeonFloor.parse(if (entrance) "Entrance" else (if (master) "M" else "F") + floorNumber)
        return if (floor == null) FloorParseResult(null, "the selected floor could not be normalized")
        else FloorParseResult(floor, null)
    }

    private fun normalizeListingFloor(tooltip: List<String>): FloorParseResult {
        var catacombs = false
        var master = false
        var entrance = false
        var floorFound = false
        var number = 0
        for (line in tooltip) {
            val lower = line.lowercase(Locale.ROOT)
            if (lower.startsWith("dungeon:") && lower.contains("catacombs")) {
                catacombs = true
                master = lower.contains("master mode") || lower.contains("master catacombs")
            }
            val floorMatcher = listingFloorPattern.matcher(line)
            if (floorMatcher.matches()) {
                entrance = floorMatcher.group(2).equals("Entrance", ignoreCase = true)
                floorFound = true
                number = if (entrance) 0 else DungeonFloor.romanFloor(floorMatcher.group(2))
                if (floorMatcher.group(1)?.equals("M", ignoreCase = true) == true) master = true
            }
        }
        if (!catacombs || !floorFound) {
            return FloorParseResult(null, "the clicked listing did not contain a Catacombs floor")
        }
        val floor = DungeonFloor.parse(if (entrance) "Entrance" else (if (master) "M" else "F") + number)
        return if (floor == null) FloorParseResult(null, "the clicked listing floor could not be normalized")
        else FloorParseResult(floor, null)
    }

    private fun clearCandidate() {
        candidate = null
        candidateCapturedAt = 0L
    }

    private fun clearJoinedListingCandidate() {
        joinedListingCandidate = null
        joinedListingCapturedAt = 0L
    }


    private fun clean(value: String?) = ChatStringSemantics.trim(ChatText.plain(value ?: ""))

    private data class MenuEntry(
        val slot: Int,
        val name: String,
        val tooltip: List<String>,
        val emeraldBlock: Boolean,
        val playerHead: Boolean,
    )

    private data class FloorParseResult(val floor: DungeonFloor?, val error: String?)

    @JvmRecord
    data class CaptureResult(val attempted: Boolean, val floor: DungeonFloor?, val error: String?)
}
