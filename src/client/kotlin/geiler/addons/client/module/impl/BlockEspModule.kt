package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.BlockPickerScreen
import geiler.addons.client.gui.FolderManagerScreen
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Collections
import java.util.function.BiConsumer
import java.util.function.Supplier

/** Exact registered-block ESP with incremental loaded-section scanning and connected voxel rendering. */
class BlockEspModule private constructor(
    private val createAction: ModuleAction,
    private val manageFoldersAction: ModuleAction,
    private val labelSize: NumberSetting,
) : Module(
    "Block ESP",
    "Highlights selected registered block types within loaded chunks.",
    Category.VISUAL,
    createAction,
    manageFoldersAction,
    labelSize,
) {
    private val entries = ArrayList<BlockEspEntry>()
    private val folders = FolderTree()
    private val scanner = BlockEspScanner()
    private var nextId = 0

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0L
    private var lastWorldRender = "not-rendered"
    private var lastHudRender = "not-rendered"

    init {
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            scanner.invalidateChunk(level, chunk.pos.x, chunk.pos.z, true)
        }
        ClientChunkEvents.CHUNK_UNLOAD.register { level, chunk ->
            scanner.invalidateChunk(level, chunk.pos.x, chunk.pos.z, false)
        }
    }

    private constructor() : this(
        ModuleAction(
            "Create Block ESP Entry",
            "Add one exact block type to highlight.",
            Runnable { INSTANCE.createEntry() },
        ),
        ModuleAction(
            "Manage Folders",
            "Organize Block ESP entries into nested folders.",
            Runnable { INSTANCE.openFolders() },
        ),
        NumberSetting("Label Size", 0.5f, 3.0f, 1.0f),
    )

    fun entries(): List<BlockEspEntry> = Collections.unmodifiableList(ArrayList(entries))
    fun folders(): FolderTree = folders

    private fun createEntry() {
        if (entries.size >= MAX_ENTRIES) return
        entries += BlockEspEntry(nextId++)
        ModConfig.markDirty()
    }

    internal fun remove(entry: BlockEspEntry) {
        entries.remove(entry)
        scanner.clear(entries)
        ModConfig.markDirty()
    }

    fun blank(id: Int): BlockEspEntry = BlockEspEntry(id)

    fun restore(restored: List<BlockEspEntry>?) {
        entries.clear()
        for (entry in restored.orEmpty()) {
            if (entry == null || entries.size >= MAX_ENTRIES) break
            entries += entry
        }
        nextId = maxOf(0, entries.maxOfOrNull { it.id() + 1 } ?: 0)
        scanner.clear(entries)
    }

    override fun groups(): List<SettingGroup> = buildList {
        add(SettingGroup(null, createAction, manageFoldersAction, labelSize))
        folders.childrenOf(null).forEach { add(folderGroup(it)) }
        entries.filterNot { folders.contains(it.folderId()) }.forEach { add(it.group()) }
    }

    private fun folderGroup(folder: FolderTree.Folder): SettingGroup {
        val children = ArrayList<SettingGroup>()
        folders.childrenOf(folder.id).forEach { children += folderGroup(it) }
        entries.filter { folder.id == it.folderId() }.forEach { children += it.group() }
        return SettingGroup(folder.name, null, false, emptyList(), children, false, "block-esp-folder:${folder.id}")
    }

    internal fun openPicker(entry: BlockEspEntry) {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(BlockPickerScreen(minecraft.gui.screen()) { id ->
            entry.setBlockId(id)
            ModConfig.markDirty()
        })
    }

    private fun openFolders() {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(
            FolderManagerScreen(
                minecraft.gui.screen(),
                "Block ESP",
                folders,
                Supplier {
                    entries.map { entry ->
                        val displayName = entry.displayName().value()
                        FolderManagerScreen.Entry(
                            entry.id().toString(),
                            "#${entry.id()} ${if (BlockEspStringSemantics.isJavaBlank(displayName)) entry.blockId() else displayName}",
                            entry.folderId(),
                        )
                    }
                },
                BiConsumer { entryId, folderId ->
                    entryId?.toIntOrNull()?.let { id -> entries.firstOrNull { it.id() == id }?.setFolderId(folderId) }
                },
                Runnable { ModConfig.markDirty() },
            ),
        )
    }

    override fun onEnable() {
        diagnosticTick = 0
        lastDiagnosticTick = Long.MIN_VALUE
        lastDiagnosticState = ""
        suppressedDiagnosticChanges = 0
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
    }

    override fun onDisable() {
        scanner.clear(entries)
        GeilerAddonsLog.write(Category.VISUAL, name(), diagnosticTick, "STATUS enabled=false;scanner=cleared")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), false)
    }

    fun tick() {
        if (!isEnabled()) {
            scanner.clear(entries)
            return
        }
        val minecraft = Minecraft.getInstance()
        val renderDistanceBlocks = minecraft.options.effectiveRenderDistance * 16
        scanner.tick(minecraft.level, minecraft.player, renderDistanceBlocks, entries)
        diagnosticTick++
        reportDiagnostics(minecraft, renderDistanceBlocks)
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled()) {
            lastWorldRender = "skipped-disabled"
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null || minecraft.player == null) {
            lastWorldRender = "skipped-no-world-or-player"
            return
        }
        val snapshot = scanner.snapshot()
        if (snapshot.results().isEmpty()) {
            lastWorldRender = "skipped-empty-snapshot"
            return
        }

        val mainCamera = minecraft.gameRenderer.mainCamera()
        val cameraPosition = mainCamera.position()
        val currentIsland = HypixelModApi.currentIsland()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var eligibleEntries = 0
        var boxCandidates = 0
        var clusters = 0
        var tracerLines = 0
        var drew = false

        snapshot.results().forEach { result ->
            val entry = entry(result.entryId()) ?: return@forEach
            if (!entry.enabled().value() || !entry.appliesOn(currentIsland)) return@forEach
            val radius = BlockEspScanner.effectiveRadius(
                minecraft.options.effectiveRenderDistance * 16,
                entry.useCustomRange().value(),
                entry.customRange().intValue(),
            )
            if (result.island() != currentIsland || result.blockId() != entry.blockId() ||
                result.radius() != radius || result.connectTouching() != entry.connectTouching().value()
            ) return@forEach

            eligibleEntries++
            if (entry.box().value() && (entry.fill().value() || entry.outline().value())) {
                boxCandidates += result.blocks().size
                if (entry.connectTouching().value()) {
                    val renderCache = checkNotNull(result.renderCache())
                    EspRenderer.renderBlockUnionPrepared(
                        pose,
                        buffers,
                        result.blocks(),
                        renderCache.membership(),
                        if (entry.outline().value()) renderCache.outline() else emptyList(),
                        cameraPosition.x,
                        cameraPosition.y,
                        cameraPosition.z,
                        entry.fill().value(),
                        entry.outline().value(),
                        entry.fillColor().argb(),
                        entry.outlineColor().argb(),
                        OUTLINE_WIDTH,
                        entry.depthCheck().value(),
                    )
                } else if (result.blocks().isNotEmpty()) {
                    EspRenderer.renderIndividualBlocks(
                        pose,
                        buffers,
                        result.blocks(),
                        cameraPosition.x,
                        cameraPosition.y,
                        cameraPosition.z,
                        entry.fill().value(),
                        entry.outline().value(),
                        entry.fillColor().argb(),
                        entry.outlineColor().argb(),
                        OUTLINE_WIDTH,
                        entry.depthCheck().value(),
                    )
                }
            }

            if (result.connectTouching()) {
                clusters += result.clusters().size
                result.clusters().forEach { cluster ->
                    if (entry.tracer().value()) {
                        val endpoints = BlockEspTracerGeometry.endpoints(
                            cameraPosition,
                            mainCamera.forwardVector(),
                            cluster.center()!!,
                            TRACER_START_DISTANCE,
                        )
                        EspRenderer.renderLine(
                            pose,
                            buffers,
                            endpoints.start.x,
                            endpoints.start.y,
                            endpoints.start.z,
                            endpoints.end.x,
                            endpoints.end.y,
                            endpoints.end.z,
                            entry.outlineColor().argb(),
                            TRACER_WIDTH,
                            entry.depthCheck().value(),
                        )
                        tracerLines++
                    }
                    drew = true
                }
            } else if (result.blocks().isNotEmpty()) {
                drew = true
                if (entry.tracer().value()) {
                    result.blocks().forEach { block ->
                        val center = Vec3(block.x + 0.5, block.y + 0.5, block.z + 0.5)
                        val endpoints = BlockEspTracerGeometry.endpoints(
                            cameraPosition,
                            mainCamera.forwardVector(),
                            center,
                            TRACER_START_DISTANCE,
                        )
                        EspRenderer.renderLine(
                            pose,
                            buffers,
                            endpoints.start.x,
                            endpoints.start.y,
                            endpoints.start.z,
                            endpoints.end.x,
                            endpoints.end.y,
                            endpoints.end.z,
                            entry.outlineColor().argb(),
                            TRACER_WIDTH,
                            entry.depthCheck().value(),
                        )
                        tracerLines++
                    }
                }
            }
        }

        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
        lastWorldRender = "eligible=$eligibleEntries,boxCandidates=$boxCandidates,clusters=$clusters," +
            "tracerLines=$tracerLines,flushed=$drew"
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled()) {
            lastHudRender = "skipped-disabled"
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden()) {
            lastHudRender = "skipped-hidden-hud"
            return
        }
        if (minecraft.level == null || minecraft.player == null) {
            lastHudRender = "skipped-no-world-or-player"
            return
        }
        val camera = minecraft.gameRenderer.mainCamera()
        val currentIsland = HypixelModApi.currentIsland()
        val scale = labelSize.value()
        var labelAttempts = 0
        var eligibleEntries = 0

        scanner.snapshot().results().forEach { result ->
            val entry = entry(result.entryId()) ?: return@forEach
            if (!entry.enabled().value() || !entry.showLabel().value() || !entry.appliesOn(currentIsland)) {
                return@forEach
            }
            val radius = BlockEspScanner.effectiveRadius(
                minecraft.options.effectiveRenderDistance * 16,
                entry.useCustomRange().value(),
                entry.customRange().intValue(),
            )
            if (result.island() != currentIsland || result.blockId() != entry.blockId() ||
                result.radius() != radius || result.connectTouching() != entry.connectTouching().value()
            ) return@forEach
            val text = label(entry)
            if (BlockEspStringSemantics.isJavaBlank(text)) return@forEach
            eligibleEntries++
            val color = entry.outlineColor().argb()
            if (result.connectTouching()) {
                result.clusters().forEach { cluster ->
                    ProjectedLabelRenderer.draw(
                        graphics,
                        camera,
                        minecraft.font,
                        cluster.center()!!.add(0.0, LABEL_HEIGHT, 0.0),
                        text,
                        color,
                        scale,
                    )
                    labelAttempts++
                }
            } else {
                result.blocks().forEach { block ->
                    ProjectedLabelRenderer.draw(
                        graphics,
                        camera,
                        minecraft.font,
                        Vec3(block.x + 0.5, block.y + 0.5 + LABEL_HEIGHT, block.z + 0.5),
                        text,
                        color,
                        scale,
                    )
                    labelAttempts++
                }
            }
        }
        lastHudRender = "eligible=$eligibleEntries,labelAttempts=$labelAttempts"
    }

    private fun reportDiagnostics(minecraft: Minecraft, renderDistanceBlocks: Int) {
        val currentIsland = HypixelModApi.currentIsland()
        val enabledEntries = entries.count { it.enabled().value() }
        val selectedEntries = entries.count { !BlockEspStringSemantics.isJavaBlank(it.blockId()) }
        val applicableEntries = entries.count { it.enabled().value() && it.appliesOn(currentIsland) }
        val settingsHash = settingsFingerprint()
        val samples = entries.take(MAX_DIAGNOSTIC_ENTRY_SAMPLES).joinToString("|") { entry ->
            val blockId = entry.blockId().replace(',', '_').replace(';', '_')
                .replace('\n', '_').replace('\r', '_').take(96)
            val islandCount = entry.islands().values.count { it.value() }
            val radius = BlockEspScanner.effectiveRadius(
                renderDistanceBlocks,
                entry.useCustomRange().value(),
                entry.customRange().intValue(),
            )
            val flags = listOf(
                entry.box().value(), entry.fill().value(), entry.outline().value(),
                entry.showLabel().value(), entry.tracer().value(), entry.connectTouching().value(),
                entry.depthCheck().value(), entry.useCustomRange().value(),
            ).joinToString("") { if (it) "1" else "0" }
            "${entry.id()}:$blockId/r=$radius/islands=$islandCount/flags=$flags/" +
                "colors=${entry.outlineColor().argb()},${entry.fillColor().argb()}/matches=${entry.matchCount()}"
        }
        val state = buildString {
            append("enabled=true,level=").append(minecraft.level != null)
            append(",player=").append(minecraft.player != null)
            append(",hypixelLocation=").append(HypixelModApi.hasLocation())
            append(",island=").append(currentIsland?.name ?: "none")
            append(",renderDistanceBlocks=").append(renderDistanceBlocks)
            append(",entries=").append(entries.size)
            append(",enabledEntries=").append(enabledEntries)
            append(",selectedEntries=").append(selectedEntries)
            append(",applicableEntries=").append(applicableEntries)
            append(",folders=").append(folders.folders().size)
            append(",labelSize=").append(labelSize.value())
            append(",settingsHash=").append(java.lang.Long.toHexString(settingsHash))
            append(",scan{").append(scanner.diagnosticSummary()).append('}')
            append(",world{").append(lastWorldRender).append('}')
            append(",hud{").append(lastHudRender).append('}')
            append(",entrySamples=").append(samples.ifEmpty { "none" })
            if (entries.size > MAX_DIAGNOSTIC_ENTRY_SAMPLES) {
                append(",omittedEntrySamples=").append(entries.size - MAX_DIAGNOSTIC_ENTRY_SAMPLES)
            }
        }.take(MAX_DIAGNOSTIC_LINE_LENGTH)

        val changed = state != lastDiagnosticState
        val heartbeat = lastDiagnosticTick == Long.MIN_VALUE ||
            diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat) return
        if (changed && lastDiagnosticTick != Long.MIN_VALUE &&
            diagnosticTick - lastDiagnosticTick < DIAGNOSTIC_MIN_INTERVAL_TICKS
        ) {
            suppressedDiagnosticChanges = (suppressedDiagnosticChanges + 1).coerceAtMost(MAX_SUPPRESSED_CHANGES)
            return
        }

        val line = "STATUS $state,suppressedChanges=$suppressedDiagnosticChanges,lineLimit=$MAX_DIAGNOSTIC_LINE_LENGTH"
            .take(MAX_DIAGNOSTIC_LINE_LENGTH)
        GeilerAddonsLog.write(
            Category.VISUAL,
            name(),
            diagnosticTick,
            line,
        )
        lastDiagnosticState = state
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0
    }

    private fun settingsFingerprint(): Long {
        var hash = FNV_OFFSET
        entries.forEach { entry ->
            hash = mix(hash, entry.id().toLong())
            hash = mix(hash, entry.blockId().hashCode().toLong())
            hash = mix(hash, entry.displayName().value().hashCode().toLong())
            hash = mix(hash, entry.folderId()?.hashCode()?.toLong() ?: 0L)
            hash = mix(hash, entry.customRange().intValue().toLong())
            hash = mix(hash, entry.outlineColor().argb().toLong())
            hash = mix(hash, entry.fillColor().argb().toLong())
            listOf(
                entry.enabled().value(), entry.box().value(), entry.fill().value(), entry.outline().value(),
                entry.showLabel().value(), entry.tracer().value(), entry.connectTouching().value(),
                entry.depthCheck().value(), entry.useCustomRange().value(),
            ).forEach { hash = mix(hash, if (it) 1L else 0L) }
            entry.islands().forEach { (island, setting) ->
                hash = mix(hash, island.ordinal.toLong())
                hash = mix(hash, if (setting.value()) 1L else 0L)
            }
        }
        return mix(hash, labelSize.value().toBits().toLong())
    }

    private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

    private fun entry(id: Int): BlockEspEntry? = entries.firstOrNull { it.id() == id }

    private fun label(entry: BlockEspEntry): String {
        val customName = entry.displayName().value()
        if (!BlockEspStringSemantics.isJavaBlank(customName) && customName != "New Block") return customName
        return BlockEspRules.resolve(entry.blockId())?.name?.string ?: entry.blockId()
    }

    companion object {
        @JvmField
        val INSTANCE = BlockEspModule()

        const val MAX_ENTRIES = 32
        private const val OUTLINE_WIDTH = 1.3f
        private const val TRACER_WIDTH = 1.4f
        private const val TRACER_START_DISTANCE = 0.5
        private const val LABEL_HEIGHT = 0.45
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_ENTRY_SAMPLES = 8
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 4096
        private const val MAX_SUPPRESSED_CHANGES = 1_000_000L
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001B3L
    }
}
