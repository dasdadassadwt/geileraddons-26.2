package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.DoorDepthSlice
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.DoorKind
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.DoorSourceVolumeScan
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.Glass
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.SpawnStartDoorVolumeScan
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.StartDoorDepthSlice
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.StartDoorVolumeScan
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.Surface
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.Toggles
import geiler.addons.client.tree.ChatText
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TreeMap
import java.util.stream.Collectors
import net.minecraft.core.BlockPos

/** Java-callable rule API used by the module, mixins, and focused checks. */
class DungeonDoorBlockReplacementRules private constructor() {
    companion object {
        @JvmStatic
        fun replacement(door: DoorKind?, surface: Surface?, toggles: Toggles?): Glass? =
            DungeonDoorBlockReplacementRulesLogic.replacement(door, surface, toggles)

        @JvmStatic
        fun defaultReplacement(door: DoorKind?): Glass? =
            DungeonDoorBlockReplacementRulesLogic.defaultReplacement(door)

        @JvmStatic
        fun replacement(
            door: DoorKind?,
            surface: Surface?,
            toggles: Toggles?,
            startGlass: Glass?,
            witherGlass: Glass?,
            bloodGlass: Glass?,
        ): Glass? = DungeonDoorBlockReplacementRulesLogic.replacement(
            door, surface, toggles, startGlass, witherGlass, bloodGlass,
        )

        /** Only vanilla clear and stained glass blocks are exposed as replacement choices. */
        @JvmStatic
        fun glassChoices(): Array<String> = DungeonDoorBlockReplacementRulesLogic.glassChoices()

        @JvmStatic
        fun glassDisplayValues(): Map<String, String> = DungeonDoorBlockReplacementRulesLogic.glassDisplayValues()

        /** Maps supported source block IDs to their door-replacement categories. */
        @JvmStatic
        fun surface(blockPath: String?): Surface = DungeonDoorBlockReplacementRulesLogic.surface(blockPath)

        /** A map-adjacent Start gap needs one supported source in its complete loaded 4×4×3 volume. */
        @JvmStatic
        fun isStartGapCandidate(entranceAdjacent: Boolean, scan: StartDoorVolumeScan?): Boolean =
            DungeonDoorBlockReplacementRulesLogic.isStartGapCandidate(entranceAdjacent, scan)

        /** Returns the sole matching entrance edge only after every applicable doorway plane was sampled. */
        @JvmStatic
        fun uniqueEntranceStartEdge(scanComplete: Boolean, matchingEdges: List<Int>?): Int =
            DungeonDoorBlockReplacementRulesLogic.uniqueEntranceStartEdge(scanComplete, matchingEdges)

        /** Returns the 48-block Start candidate volume around an edge. */
        @JvmStatic
        fun startDoorVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> =
            DungeonDoorBlockReplacementRulesLogic.startDoorVolumePositions(planeCenter, alongX)

        /** Returns the approximate 5×4×3 spawn-front candidate volume without loading chunks. */
        @JvmStatic
        fun spawnStartDoorVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> =
            DungeonDoorBlockReplacementRulesLogic.spawnStartDoorVolumePositions(planeCenter, alongX)

        /** Reads the full spawn-front volume and groups touching sources across all dimensions. */
        @JvmStatic
        fun scanSpawnStartDoorVolume(
            planeCenter: BlockPos?,
            alongX: Boolean,
            loadedPositions: Set<Long>?,
            materialIds: Map<Long, String>?,
        ): SpawnStartDoorVolumeScan = DungeonDoorBlockReplacementRulesLogic.scanSpawnStartDoorVolume(
            planeCenter, alongX, loadedPositions, materialIds,
        )

        /** Reads the full candidate volume and preserves partial evidence for diagnostics. */
        @JvmStatic
        fun scanStartDoorVolume(
            planeCenter: BlockPos?,
            alongX: Boolean,
            loadedPositions: Set<Long>?,
            materialIds: Map<Long, String>?,
        ): StartDoorVolumeScan = DungeonDoorBlockReplacementRulesLogic.scanStartDoorVolume(
            planeCenter, alongX, loadedPositions, materialIds,
        )

        /** All 36 positions in the existing 3×4×3 doorway bounds. */
        @JvmStatic
        fun doorSourceVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> =
            DungeonDoorBlockReplacementRulesLogic.doorSourceVolumePositions(planeCenter, alongX)

        /** Incomplete volumes report observations but do not select partial source masks. */
        @JvmStatic
        fun scanDoorSourceVolume(
            planeCenter: BlockPos?,
            alongX: Boolean,
            loadedPositions: Set<Long>?,
            materialIds: Map<Long, String>?,
        ): DoorSourceVolumeScan = DungeonDoorBlockReplacementRulesLogic.scanDoorSourceVolume(
            planeCenter, alongX, loadedPositions, materialIds,
        )

        /** Preserves Wither-before-Blood classification from the central 3×4 sample. */
        @JvmStatic
        fun sourceDoorKind(scan: DoorSourceVolumeScan?): DoorKind? =
            DungeonDoorBlockReplacementRulesLogic.sourceDoorKind(scan)

        /** Initial Start selection is limited to the visually identical chiseled brick blocks. */
        @JvmStatic
        fun isStartSelectionSource(surface: Surface?): Boolean =
            DungeonDoorBlockReplacementRulesLogic.isStartSelectionSource(surface)

        /** Mort is matched by cleaned display identity, independent of its Minecraft entity type. */
        @JvmStatic
        fun isMortIdentity(rawName: String?): Boolean = DungeonDoorBlockReplacementRulesLogic.isMortIdentity(rawName)

        /** Half-open block bounds used by the door mesh substitution. */
        @JvmStatic
        fun contains(
            x: Int,
            y: Int,
            z: Int,
            width: Int,
            height: Int,
            depth: Int,
            blockX: Int,
            blockY: Int,
            blockZ: Int,
        ): Boolean = DungeonDoorBlockReplacementRulesLogic.contains(
            x, y, z, width, height, depth, blockX, blockY, blockZ,
        )

        /** True when a grid edge directly separates Entrance from its neighboring cell. */
        @JvmStatic
        fun touchesEntranceCell(entranceCell: Int, firstCell: Int, secondCell: Int): Boolean =
            DungeonDoorBlockReplacementRulesLogic.touchesEntranceCell(entranceCell, firstCell, secondCell)
    }

    enum class DoorKind { START, WITHER, BLOOD, NORMAL, GLASS }

    enum class Surface {
        COAL_BLOCK,
        TERRACOTTA,
        RED_TERRACOTTA,
        CHISELED_STONE_BRICKS,
        INFESTED_CHISELED_STONE_BRICKS,
        OTHER,
    }

    @JvmRecord
    data class DoorStageCounts(val detectedDoors: Int, val eligibleSourceBlocks: Int, val publishedReplacements: Int)

    enum class Glass(
        private val blockPathValue: String,
        private val displayNameValue: String,
    ) {
        CLEAR("glass", "Clear Glass"),
        WHITE("white_stained_glass", "White Stained Glass"),
        ORANGE("orange_stained_glass", "Orange Stained Glass"),
        MAGENTA("magenta_stained_glass", "Magenta Stained Glass"),
        LIGHT_BLUE("light_blue_stained_glass", "Light Blue Stained Glass"),
        YELLOW("yellow_stained_glass", "Yellow Stained Glass"),
        LIME("lime_stained_glass", "Lime Stained Glass"),
        PINK("pink_stained_glass", "Pink Stained Glass"),
        GRAY("gray_stained_glass", "Gray Stained Glass"),
        LIGHT_GRAY("light_gray_stained_glass", "Light Gray Stained Glass"),
        CYAN("cyan_stained_glass", "Cyan Stained Glass"),
        PURPLE("purple_stained_glass", "Purple Stained Glass"),
        BLUE("blue_stained_glass", "Blue Stained Glass"),
        BROWN("brown_stained_glass", "Brown Stained Glass"),
        GREEN("green_stained_glass", "Green Stained Glass"),
        RED("red_stained_glass", "Red Stained Glass"),
        BLACK("black_stained_glass", "Black Stained Glass");

        fun blockPath(): String = blockPathValue

        fun displayName(): String = displayNameValue

        companion object {
            @JvmStatic
            fun fromBlockPath(path: String?, fallback: Glass?): Glass? {
                if (path == null) return fallback
                return values().firstOrNull { it.blockPathValue == path } ?: fallback
            }
        }
    }

    @JvmRecord
    data class Toggles(val start: Boolean, val wither: Boolean, val blood: Boolean)

    /** Immutable Java-callable value type that normalizes and snapshots its collection components. */
    class StartDoorDepthSlice private constructor(
        private val depthOffsetValue: Int,
        private val loadedCountValue: Int,
        private val materialCountsValue: Map<String, Int>,
        private val sourcePositionsValue: List<BlockPos>,
        @Suppress("unused") private val normalizationMarker: Unit,
    ) {
        constructor(
            depthOffset: Int,
            loadedCount: Int,
            materialCounts: Map<String, Int>?,
            sourcePositions: List<BlockPos>?,
        ) : this(
            depthOffset,
            loadedCount,
            Collections.unmodifiableMap(TreeMap(materialCounts ?: emptyMap())),
            java.util.List.copyOf(sourcePositions ?: emptyList()),
            Unit,
        )

        fun depthOffset(): Int = depthOffsetValue
        fun loadedCount(): Int = loadedCountValue
        fun materialCounts(): Map<String, Int> = materialCountsValue
        fun sourcePositions(): List<BlockPos> = sourcePositionsValue

        override fun equals(other: Any?): Boolean = other is StartDoorDepthSlice &&
            depthOffsetValue == other.depthOffsetValue && loadedCountValue == other.loadedCountValue &&
            materialCountsValue == other.materialCountsValue && sourcePositionsValue == other.sourcePositionsValue

        override fun hashCode(): Int = java.util.Objects.hash(
            depthOffsetValue, loadedCountValue, materialCountsValue, sourcePositionsValue,
        )

        override fun toString(): String =
            "StartDoorDepthSlice[depthOffset=$depthOffsetValue, loadedCount=$loadedCountValue, " +
                "materialCounts=$materialCountsValue, sourcePositions=$sourcePositionsValue]"
    }

    class StartDoorVolumeScan private constructor(
        private val completeValue: Boolean,
        private val loadedCountValue: Int,
        private val sourcePositionsValue: List<BlockPos>,
        private val depthSlicesValue: List<StartDoorDepthSlice>,
        @Suppress("unused") private val normalizationMarker: Unit,
    ) {
        constructor(
            complete: Boolean,
            loadedCount: Int,
            sourcePositions: List<BlockPos>?,
            depthSlices: List<StartDoorDepthSlice>?,
        ) : this(
            complete,
            loadedCount,
            java.util.List.copyOf(sourcePositions ?: emptyList()),
            java.util.List.copyOf(depthSlices ?: emptyList()),
            Unit,
        )

        fun complete(): Boolean = completeValue
        fun loadedCount(): Int = loadedCountValue
        fun sourcePositions(): List<BlockPos> = sourcePositionsValue
        fun depthSlices(): List<StartDoorDepthSlice> = depthSlicesValue

        fun diagnosticSummary(): String =
            "loaded=$loadedCountValue/48 depthSlices=" + depthSlicesValue.map { slice ->
                "depth${slice.depthOffset()}{loaded=${slice.loadedCount()}/16 materials=${slice.materialCounts()}}"
            } + " selectedChiseled=" + sourcePositionsValue.map { it.toShortString() }

        override fun equals(other: Any?): Boolean = other is StartDoorVolumeScan &&
            completeValue == other.completeValue && loadedCountValue == other.loadedCountValue &&
            sourcePositionsValue == other.sourcePositionsValue && depthSlicesValue == other.depthSlicesValue

        override fun hashCode(): Int = java.util.Objects.hash(
            completeValue, loadedCountValue, sourcePositionsValue, depthSlicesValue,
        )

        override fun toString(): String =
            "StartDoorVolumeScan[complete=$completeValue, loadedCount=$loadedCountValue, " +
                "sourcePositions=$sourcePositionsValue, depthSlices=$depthSlicesValue]"
    }

    class SpawnStartDoorVolumeScan private constructor(
        private val completeValue: Boolean,
        private val loadedCountValue: Int,
        private val sourcePositionsValue: List<BlockPos>,
        private val candidatePanelsValue: List<List<BlockPos>>,
        private val depthSlicesValue: List<StartDoorDepthSlice>,
        @Suppress("unused") private val normalizationMarker: Unit,
    ) {
        constructor(
            complete: Boolean,
            loadedCount: Int,
            sourcePositions: List<BlockPos>?,
            candidatePanels: List<List<BlockPos>>?,
            depthSlices: List<StartDoorDepthSlice>?,
        ) : this(
            complete,
            loadedCount,
            java.util.List.copyOf(sourcePositions ?: emptyList()),
            java.util.List.copyOf((candidatePanels ?: emptyList()).map { java.util.List.copyOf(it) }),
            java.util.List.copyOf(depthSlices ?: emptyList()),
            Unit,
        )

        fun complete(): Boolean = completeValue
        fun loadedCount(): Int = loadedCountValue
        fun sourcePositions(): List<BlockPos> = sourcePositionsValue
        fun candidatePanels(): List<List<BlockPos>> = candidatePanelsValue
        fun depthSlices(): List<StartDoorDepthSlice> = depthSlicesValue

        /** One fully loaded, three-dimensional face-connected source component is eligible. */
        fun uniquePanelPositions(): List<BlockPos> =
            DungeonDoorBlockReplacementRulesLogic.uniqueSpawnStartDoorPanelPositions(
                completeValue, candidatePanelsValue,
            )

        fun selectionOutcome(): String = DungeonDoorBlockReplacementRulesLogic.spawnStartDoorSelectionOutcome(
            completeValue, sourcePositionsValue, candidatePanelsValue,
        )

        fun diagnosticSummary(): String =
            "loaded=$loadedCountValue/60 componentCount3D=${candidatePanelsValue.size} selection=${selectionOutcome()}" +
                " depthSlices=" + depthSlicesValue.map { slice ->
                    "depth${slice.depthOffset()}{loaded=${slice.loadedCount()}/20 materials=${slice.materialCounts()} " +
                        "chiseled=${slice.sourcePositions().map { it.toShortString() }}}"
                } + " chiseledCandidates=" + sourcePositionsValue.map { it.toShortString() } +
                if (selectionOutcome() == "selected-single-3d-component") {
                    " selectedPanel=" + uniquePanelPositions().map { it.toShortString() }
                } else {
                    ""
                }

        override fun equals(other: Any?): Boolean = other is SpawnStartDoorVolumeScan &&
            completeValue == other.completeValue && loadedCountValue == other.loadedCountValue &&
            sourcePositionsValue == other.sourcePositionsValue && candidatePanelsValue == other.candidatePanelsValue &&
            depthSlicesValue == other.depthSlicesValue

        override fun hashCode(): Int = java.util.Objects.hash(
            completeValue, loadedCountValue, sourcePositionsValue, candidatePanelsValue, depthSlicesValue,
        )

        override fun toString(): String =
            "SpawnStartDoorVolumeScan[complete=$completeValue, loadedCount=$loadedCountValue, " +
                "sourcePositions=$sourcePositionsValue, candidatePanels=$candidatePanelsValue, " +
                "depthSlices=$depthSlicesValue]"
    }

    class DoorDepthSlice private constructor(
        private val depthOffsetValue: Int,
        private val loadedCountValue: Int,
        private val materialCountsValue: Map<String, Int>,
        private val coalPositionsValue: List<BlockPos>,
        private val redTerracottaPositionsValue: List<BlockPos>,
        @Suppress("unused") private val normalizationMarker: Unit,
    ) {
        constructor(
            depthOffset: Int,
            loadedCount: Int,
            materialCounts: Map<String, Int>?,
            coalPositions: List<BlockPos>?,
            redTerracottaPositions: List<BlockPos>?,
        ) : this(
            depthOffset,
            loadedCount,
            Collections.unmodifiableMap(TreeMap(materialCounts ?: emptyMap())),
            java.util.List.copyOf(coalPositions ?: emptyList()),
            java.util.List.copyOf(redTerracottaPositions ?: emptyList()),
            Unit,
        )

        fun depthOffset(): Int = depthOffsetValue
        fun loadedCount(): Int = loadedCountValue
        fun materialCounts(): Map<String, Int> = materialCountsValue
        fun coalPositions(): List<BlockPos> = coalPositionsValue
        fun redTerracottaPositions(): List<BlockPos> = redTerracottaPositionsValue

        override fun equals(other: Any?): Boolean = other is DoorDepthSlice &&
            depthOffsetValue == other.depthOffsetValue && loadedCountValue == other.loadedCountValue &&
            materialCountsValue == other.materialCountsValue && coalPositionsValue == other.coalPositionsValue &&
            redTerracottaPositionsValue == other.redTerracottaPositionsValue

        override fun hashCode(): Int = java.util.Objects.hash(
            depthOffsetValue, loadedCountValue, materialCountsValue, coalPositionsValue, redTerracottaPositionsValue,
        )

        override fun toString(): String =
            "DoorDepthSlice[depthOffset=$depthOffsetValue, loadedCount=$loadedCountValue, " +
                "materialCounts=$materialCountsValue, coalPositions=$coalPositionsValue, " +
                "redTerracottaPositions=$redTerracottaPositionsValue]"
    }

    class DoorSourceVolumeScan private constructor(
        private val completeValue: Boolean,
        private val loadedCountValue: Int,
        private val coalPositionsValue: List<BlockPos>,
        private val redTerracottaPositionsValue: List<BlockPos>,
        private val depthSlicesValue: List<DoorDepthSlice>,
        @Suppress("unused") private val normalizationMarker: Unit,
    ) {
        constructor(
            complete: Boolean,
            loadedCount: Int,
            coalPositions: List<BlockPos>?,
            redTerracottaPositions: List<BlockPos>?,
            depthSlices: List<DoorDepthSlice>?,
        ) : this(
            complete,
            loadedCount,
            java.util.List.copyOf(coalPositions ?: emptyList()),
            java.util.List.copyOf(redTerracottaPositions ?: emptyList()),
            java.util.List.copyOf(depthSlices ?: emptyList()),
            Unit,
        )

        fun complete(): Boolean = completeValue
        fun loadedCount(): Int = loadedCountValue
        fun coalPositions(): List<BlockPos> = coalPositionsValue
        fun redTerracottaPositions(): List<BlockPos> = redTerracottaPositionsValue
        fun depthSlices(): List<DoorDepthSlice> = depthSlicesValue

        fun diagnosticSummary(): String =
            "loaded=$loadedCountValue/36 depthSlices=" + depthSlicesValue.map { slice ->
                "depth${slice.depthOffset()}{loaded=${slice.loadedCount()}/12 materials=${slice.materialCounts()}}"
            } + " selectedCoal=" + coalPositionsValue.map { it.toShortString() } +
                " selectedRedTerracotta=" + redTerracottaPositionsValue.map { it.toShortString() }

        override fun equals(other: Any?): Boolean = other is DoorSourceVolumeScan &&
            completeValue == other.completeValue && loadedCountValue == other.loadedCountValue &&
            coalPositionsValue == other.coalPositionsValue &&
            redTerracottaPositionsValue == other.redTerracottaPositionsValue && depthSlicesValue == other.depthSlicesValue

        override fun hashCode(): Int = java.util.Objects.hash(
            completeValue, loadedCountValue, coalPositionsValue, redTerracottaPositionsValue, depthSlicesValue,
        )

        override fun toString(): String =
            "DoorSourceVolumeScan[complete=$completeValue, loadedCount=$loadedCountValue, " +
                "coalPositions=$coalPositionsValue, redTerracottaPositions=$redTerracottaPositionsValue, " +
                "depthSlices=$depthSlicesValue]"
    }
}

/** Kotlin rule implementation behind the static Java-callable methods. */
object DungeonDoorBlockReplacementRulesLogic {
    @JvmStatic
    fun replacement(door: DoorKind?, surface: Surface?, toggles: Toggles?): Glass? =
        replacement(
            door,
            surface,
            toggles,
            defaultReplacement(DoorKind.START),
            defaultReplacement(DoorKind.WITHER),
            defaultReplacement(DoorKind.BLOOD),
        )

    @JvmStatic
    fun defaultReplacement(door: DoorKind?): Glass? {
        if (door == null) return null
        return when (door) {
            DoorKind.START -> Glass.CLEAR
            DoorKind.WITHER -> Glass.BLACK
            DoorKind.BLOOD -> Glass.RED
            DoorKind.NORMAL, DoorKind.GLASS -> null
        }
    }

    /** Selects the configured render-only glass independently for each detected door type. */
    @JvmStatic
    fun replacement(
        door: DoorKind?,
        surface: Surface?,
        toggles: Toggles?,
        startGlass: Glass?,
        witherGlass: Glass?,
        bloodGlass: Glass?,
    ): Glass? {
        if (door == null || surface == null || toggles == null || surface == Surface.OTHER) return null
        return when (door) {
            DoorKind.START ->
                if (toggles.start && isStartSelectionSource(surface)) {
                    startGlass ?: defaultReplacement(door)
                } else {
                    null
                }
            DoorKind.WITHER ->
                if (toggles.wither && surface == Surface.COAL_BLOCK) {
                    witherGlass ?: defaultReplacement(door)
                } else {
                    null
                }
            DoorKind.BLOOD ->
                if (toggles.blood && surface == Surface.RED_TERRACOTTA) {
                    bloodGlass ?: defaultReplacement(door)
                } else {
                    null
                }
            DoorKind.NORMAL, DoorKind.GLASS -> null
        }
    }

    /** Only vanilla clear/stained glass blocks are exposed as replacement choices. */
    @JvmStatic
    fun glassChoices(): Array<String> = Glass.values().map { it.blockPath() }.toTypedArray()

    @JvmStatic
    fun glassDisplayValues(): Map<String, String> {
        val blockPathMapper = java.util.function.Function<Glass, String> { glass -> glass.blockPath() }
        val displayNameMapper = java.util.function.Function<Glass, String> { glass -> glass.displayName() }
        return Arrays.stream(Glass.values())
            .collect(Collectors.toUnmodifiableMap(blockPathMapper, displayNameMapper))
    }

    /** Maps the supported source block IDs to their door-replacement categories. */
    @JvmStatic
    fun surface(blockPath: String?): Surface {
        if (blockPath == null) return Surface.OTHER
        return when (blockPath) {
            "coal_block" -> Surface.COAL_BLOCK
            "red_terracotta" -> Surface.RED_TERRACOTTA
            "chiseled_stone_bricks" -> Surface.CHISELED_STONE_BRICKS
            "infested_chiseled_stone_bricks" -> Surface.INFESTED_CHISELED_STONE_BRICKS
            "terracotta",
            "white_terracotta",
            "orange_terracotta",
            "magenta_terracotta",
            "light_blue_terracotta",
            "yellow_terracotta",
            "lime_terracotta",
            "pink_terracotta",
            "gray_terracotta",
            "light_gray_terracotta",
            "cyan_terracotta",
            "purple_terracotta",
            "blue_terracotta",
            "brown_terracotta",
            "green_terracotta",
            "black_terracotta" -> Surface.TERRACOTTA
            else -> Surface.OTHER
        }
    }

    /** A map-adjacent Start gap needs one supported source in its complete loaded 4×4×3 volume. */
    @JvmStatic
    fun isStartGapCandidate(entranceAdjacent: Boolean, scan: StartDoorVolumeScan?): Boolean =
        entranceAdjacent && scan != null && scan.complete() && scan.sourcePositions().isNotEmpty()

    /** Returns the sole matching entrance edge only after every applicable doorway plane was sampled. */
    @JvmStatic
    fun uniqueEntranceStartEdge(scanComplete: Boolean, matchingEdges: List<Int>?): Int =
        if (scanComplete && matchingEdges != null && matchingEdges.size == 1) matchingEdges[0] else -1

    /**
     * Returns the 48-block Start candidate volume around an edge. It preserves the existing
     * three-block tangent span at offsets -1..1 and extends that span one block at its positive end
     * (-1..2); depth remains three blocks and height remains y=69..72.
     */
    @JvmStatic
    fun startDoorVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> {
        if (planeCenter == null) return java.util.List.of()
        val positions = ArrayList<BlockPos>(48)
        for (depth in -1..1) {
            for (side in -1..2) {
                for (height in 0..3) {
                    positions.add(
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        },
                    )
                }
            }
        }
        return java.util.List.copyOf(positions)
    }

    /** Returns the approximate 5×4×3 spawn-front candidate volume, without loading chunks. */
    @JvmStatic
    fun spawnStartDoorVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> {
        if (planeCenter == null) return java.util.List.of()
        val positions = ArrayList<BlockPos>(60)
        for (depth in -1..1) {
            for (side in -2..2) {
                for (height in 0..3) {
                    positions.add(
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        },
                    )
                }
            }
        }
        return java.util.List.copyOf(positions)
    }

    /** Reads the full spawn-front volume and groups touching supported sources across all three dimensions. */
    @JvmStatic
    fun scanSpawnStartDoorVolume(
        planeCenter: BlockPos?,
        alongX: Boolean,
        loadedPositions: Set<Long>?,
        materialIds: Map<Long, String>?,
    ): SpawnStartDoorVolumeScan {
        if (planeCenter == null) {
            return SpawnStartDoorVolumeScan(false, 0, java.util.List.of(), java.util.List.of(), java.util.List.of())
        }
        val loaded = loadedPositions ?: emptySet()
        val materials = materialIds ?: emptyMap()
        val slices = ArrayList<StartDoorDepthSlice>(3)
        val sources = ArrayList<BlockPos>()
        val panels = ArrayList<List<BlockPos>>()
        var loadedCount = 0
        for (depth in -1..1) {
            val counts = TreeMap<String, Int>()
            val sliceSources = ArrayList<BlockPos>()
            var sliceLoaded = 0
            for (side in -2..2) {
                for (height in 0..3) {
                    val position =
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        }
                    if (!loaded.contains(position.asLong())) continue
                    sliceLoaded++
                    loadedCount++
                    val material = materials.getOrDefault(position.asLong(), "minecraft:air")
                    counts[material] = (counts[material] ?: 0) + 1
                    if (isChiseledStartBlock(material)) sliceSources.add(position.immutable())
                }
            }
            sources.addAll(sliceSources)
            slices.add(StartDoorDepthSlice(depth, sliceLoaded, counts, sliceSources))
        }
        panels.addAll(connectedStartSourceComponents(sources))
        return SpawnStartDoorVolumeScan(
            loadedCount == 60,
            loadedCount,
            java.util.List.copyOf(sources),
            java.util.List.copyOf(panels),
            java.util.List.copyOf(slices),
        )
    }

    /** Selects a spawn Start panel only when the complete volume has one 3D component. */
    @JvmStatic
    fun uniqueSpawnStartDoorPanelPositions(
        complete: Boolean,
        candidatePanels: List<List<BlockPos>>,
    ): List<BlockPos> =
        if (complete && candidatePanels.size == 1) candidatePanels.first() else java.util.List.of()

    /** Reports why spawn-front Start panel selection did or did not produce a unique target. */
    @JvmStatic
    fun spawnStartDoorSelectionOutcome(
        complete: Boolean,
        sourcePositions: List<BlockPos>,
        candidatePanels: List<List<BlockPos>>,
    ): String {
        if (!complete) return "rejected-incomplete-volume"
        if (sourcePositions.isEmpty()) return "rejected-no-supported-source"
        if (candidatePanels.size != 1) return "rejected-multiple-3d-components"
        return "selected-single-3d-component"
    }

    private fun connectedStartSourceComponents(sourcePositions: List<BlockPos?>?): List<List<BlockPos>> {
        if (sourcePositions == null || sourcePositions.isEmpty()) return java.util.List.of()
        val byPosition = HashMap<Long, BlockPos>()
        for (position in sourcePositions) {
            if (position != null) byPosition.putIfAbsent(position.asLong(), position.immutable())
        }
        val remaining = LinkedHashSet(byPosition.keys)
        val components = ArrayList<List<BlockPos>>()
        while (remaining.isNotEmpty()) {
            val first = remaining.iterator().next()
            remaining.remove(first)
            val pending = ArrayDeque<BlockPos>()
            val component = ArrayList<BlockPos>()
            pending.addLast(byPosition[first])
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                component.add(current)
                for (neighbor in listOf(current.north(), current.south(), current.east(), current.west(), current.above(), current.below())) {
                    val key = neighbor.asLong()
                    if (remaining.remove(key)) pending.addLast(byPosition[key])
                }
            }
            components.add(java.util.List.copyOf(component))
        }
        return java.util.List.copyOf(components)
    }

    /** Reads the full candidate volume while preserving partial material evidence for diagnostics. */
    @JvmStatic
    fun scanStartDoorVolume(
        planeCenter: BlockPos?,
        alongX: Boolean,
        loadedPositions: Set<Long>?,
        materialIds: Map<Long, String>?,
    ): StartDoorVolumeScan {
        if (planeCenter == null) return StartDoorVolumeScan(false, 0, java.util.List.of(), java.util.List.of())
        val loaded = loadedPositions ?: emptySet()
        val materials = materialIds ?: emptyMap()
        val slices = ArrayList<StartDoorDepthSlice>(3)
        val sources = ArrayList<BlockPos>()
        var loadedCount = 0
        for (depth in -1..1) {
            val counts = TreeMap<String, Int>()
            val sliceSources = ArrayList<BlockPos>()
            var sliceLoaded = 0
            for (side in -1..2) {
                for (height in 0..3) {
                    val position =
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        }
                    if (!loaded.contains(position.asLong())) continue
                    sliceLoaded++
                    loadedCount++
                    val material = materials.getOrDefault(position.asLong(), "minecraft:air")
                    counts[material] = (counts[material] ?: 0) + 1
                    if (isChiseledStartBlock(material)) sliceSources.add(position.immutable())
                }
            }
            sources.addAll(sliceSources)
            slices.add(StartDoorDepthSlice(depth, sliceLoaded, counts, sliceSources))
        }
        val complete = loadedCount == 48
        return StartDoorVolumeScan(complete, loadedCount, java.util.List.copyOf(sources), java.util.List.copyOf(slices))
    }

    private fun isChiseledStartBlock(material: String?): Boolean {
        if (material == null) return false
        val separator = material.indexOf(':')
        if (separator >= 0 && material.substring(0, separator) != "minecraft") return false
        val path = if (separator < 0) material else material.substring(separator + 1)
        return path == "chiseled_stone_bricks" || path == "infested_chiseled_stone_bricks"
    }

    /** All 36 positions in the existing 3×4×3 doorway bounds around the detected door plane. */
    @JvmStatic
    fun doorSourceVolumePositions(planeCenter: BlockPos?, alongX: Boolean): List<BlockPos> {
        if (planeCenter == null) return java.util.List.of()
        val positions = ArrayList<BlockPos>(36)
        for (depth in -1..1) {
            for (side in -1..1) {
                for (height in 0..3) {
                    positions.add(
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        },
                    )
                }
            }
        }
        return java.util.List.copyOf(positions)
    }

    /**
     * Summarizes loaded authoritative materials in all three depth slices. An incomplete volume
     * reports its observations but does not select partial source masks.
     */
    @JvmStatic
    fun scanDoorSourceVolume(
        planeCenter: BlockPos?,
        alongX: Boolean,
        loadedPositions: Set<Long>?,
        materialIds: Map<Long, String>?,
    ): DoorSourceVolumeScan {
        if (planeCenter == null) {
            return DoorSourceVolumeScan(
                false,
                0,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
            )
        }
        val loaded = loadedPositions ?: emptySet()
        val materials = materialIds ?: emptyMap()
        val slices = ArrayList<DoorDepthSlice>(3)
        val coal = ArrayList<BlockPos>()
        val redTerracotta = ArrayList<BlockPos>()
        var loadedCount = 0
        for (depth in -1..1) {
            val counts = TreeMap<String, Int>()
            val sliceCoal = ArrayList<BlockPos>()
            val sliceRed = ArrayList<BlockPos>()
            var sliceLoaded = 0
            for (side in -1..1) {
                for (height in 0..3) {
                    val position =
                        if (alongX) {
                            BlockPos(
                                planeCenter.getX() + depth,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + side,
                            )
                        } else {
                            BlockPos(
                                planeCenter.getX() + side,
                                planeCenter.getY() + height,
                                planeCenter.getZ() + depth,
                            )
                        }
                    if (!loaded.contains(position.asLong())) continue
                    sliceLoaded++
                    loadedCount++
                    val material = materials.getOrDefault(position.asLong(), "minecraft:air")
                    counts[material] = (counts[material] ?: 0) + 1
                    if (material == "minecraft:coal_block") sliceCoal.add(position.immutable())
                    else if (material == "minecraft:red_terracotta") sliceRed.add(position.immutable())
                }
            }
            coal.addAll(sliceCoal)
            redTerracotta.addAll(sliceRed)
            slices.add(
                DoorDepthSlice(
                    depth,
                    sliceLoaded,
                    java.util.Map.copyOf(counts),
                    java.util.List.copyOf(sliceCoal),
                    java.util.List.copyOf(sliceRed),
                ),
            )
        }
        val complete = loadedCount == 36
        return DoorSourceVolumeScan(
            complete,
            loadedCount,
            if (complete) java.util.List.copyOf(coal) else java.util.List.of(),
            if (complete) java.util.List.copyOf(redTerracotta) else java.util.List.of(),
            java.util.List.copyOf(slices),
        )
    }

    /** Preserves the existing Wither-before-Blood classification from the central 3×4 sample. */
    @JvmStatic
    fun sourceDoorKind(scan: DoorSourceVolumeScan?): DoorKind? {
        if (scan == null || !scan.complete()) return null
        val centralSlice = scan.depthSlices().firstOrNull { slice -> slice.depthOffset() == 0 }
        if (centralSlice == null || centralSlice.loadedCount() != 12) return null
        if (centralSlice.coalPositions().isNotEmpty()) return DoorKind.WITHER
        if (centralSlice.redTerracottaPositions().isNotEmpty()) return DoorKind.BLOOD
        return null
    }

    /** Initial Start selection is limited to the two visually identical chiseled brick blocks. */
    @JvmStatic
    fun isStartSelectionSource(surface: Surface?): Boolean =
        surface == Surface.CHISELED_STONE_BRICKS || surface == Surface.INFESTED_CHISELED_STONE_BRICKS

    /** Mort is matched by its cleaned display identity, independent of its Minecraft entity type. */
    @JvmStatic
    fun isMortIdentity(rawName: String?): Boolean =
        ChatText.stripForMatch(rawName ?: "").lowercase(Locale.ROOT) == "mort"

    /** Half-open block bounds used by the door mesh substitution; invalid boxes contain nothing. */
    @JvmStatic
    fun contains(
        x: Int,
        y: Int,
        z: Int,
        width: Int,
        height: Int,
        depth: Int,
        blockX: Int,
        blockY: Int,
        blockZ: Int,
    ): Boolean =
        width > 0 &&
            height > 0 &&
            depth > 0 &&
            blockX >= x &&
            blockX.toLong() < x.toLong() + width.toLong() &&
            blockY >= y &&
            blockY.toLong() < y.toLong() + height.toLong() &&
            blockZ >= z &&
            blockZ.toLong() < z.toLong() + depth.toLong()

    /** True when a grid edge directly separates the Entrance cell from its neighboring cell. */
    @JvmStatic
    fun touchesEntranceCell(entranceCell: Int, firstCell: Int, secondCell: Int): Boolean =
        entranceCell >= 0 && (firstCell == entranceCell || secondCell == entranceCell)
}
