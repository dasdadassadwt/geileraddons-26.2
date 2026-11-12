package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.collections.FolderTree
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.entity.Nameplates
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
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Supplier

/**
 * Boxes any mob whose name or type matches text the user typed.
 *
 * Holds a list of highlights rather than one set of settings, because the whole point is running
 * several at once with different colours. The list is built in the settings panel itself - each
 * highlight is a section of it - so there is no separate management screen to keep in step.
 */
class MobHighlightModule private constructor(
    private val createAction: ModuleAction,
    private val foldersAction: ModuleAction,
    private val labelSize: NumberSetting,
) : Module(
    "Mob Highlight",
    "Boxes mobs whose name or type matches your text.",
    Category.VISUAL,
    createAction,
    foldersAction,
    labelSize,
) {
    private val highlights = ArrayList<MobHighlight>()
    private val folders = FolderTree()
    private var nextId = 0
    private var lastLevel: ClientLevel? = null

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0L
    private var lastScan = "not-scanned"
    private var lastWorldRender = "not-rendered"
    private var lastHudRender = "not-rendered"

    private constructor() : this(
        ModuleAction("Create Mob Highlight", Runnable { INSTANCE.create() }),
        ModuleAction("Manage Folders", "Organize highlights into nested folders.", Runnable { INSTANCE.openFolders() }),
        NumberSetting("Label Size", 0.5f, 4f, 1f),
    )

    fun folders(): FolderTree = folders

    /** Snapshot, so Java config and transfer callers cannot mutate the live list. */
    fun highlights(): List<MobHighlight> = java.util.List.copyOf(highlights)

    /** Adds an empty highlight, which the user then fills in - there is no dialog to answer. */
    private fun create() {
        if (highlights.size >= MAX_HIGHLIGHTS) return
        highlights += MobHighlight(nextId++)
        ModConfig.markDirty()
    }

    internal fun remove(highlight: MobHighlight) {
        highlights.remove(highlight)
        ModConfig.markDirty()
    }

    /** Replaces the list from the config; ids come back as saved so collapsed sections still match. */
    fun restore(restored: List<MobHighlight?>?) {
        highlights.clear()
        for (highlight in restored.orEmpty()) {
            if (highlight == null || highlights.size >= MAX_HIGHLIGHTS) break
            highlights += highlight
        }
        nextId = 0
        for (highlight in highlights) {
            nextId = maxOf(nextId, highlight.id() + 1)
        }
    }

    /** Builds a highlight the config can fill in, without handing out the constructor. */
    fun blank(id: Int): MobHighlight = MobHighlight(id)

    /** Rebuilt on every call because the settings sections come and go while the panel is open. */
    override fun groups(): List<SettingGroup> {
        val groups = ArrayList<SettingGroup>(highlights.size + folders.folders().size + 1)
        groups += SettingGroup(null, createAction, foldersAction, labelSize)
        for (folder in folders.childrenOf(null)) groups += folderGroup(folder)
        for (highlight in highlights) {
            if (!folders.contains(highlight.folderId())) groups += highlight.group()
        }
        return groups
    }

    private fun folderGroup(folder: FolderTree.Folder): SettingGroup {
        val children = ArrayList<SettingGroup>()
        for (child in folders.childrenOf(folder.id)) children += folderGroup(child)
        for (highlight in highlights) {
            if (folder.id == highlight.folderId()) children += highlight.group()
        }
        return SettingGroup(
            folder.name,
            null,
            false,
            emptyList(),
            children,
            false,
            "mob-highlight-folder:${folder.id}",
        )
    }

    private fun openFolders() {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(
            FolderManagerScreen(
                minecraft.gui.screen(),
                "Mob Highlight",
                folders,
                Supplier {
                    highlights.map { highlight ->
                        FolderManagerScreen.Entry(
                            highlight.id().toString(),
                            "#${highlight.id()} ${highlight.displayName().value()}",
                            highlight.folderId(),
                        )
                    }
                },
                BiConsumer { entryId, folderId ->
                    val id = entryId?.toIntOrNull()
                    if (id != null) highlights.firstOrNull { it.id() == id }?.setFolderId(folderId)
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
        lastScan = "not-scanned"
        lastWorldRender = "not-rendered"
        lastHudRender = "not-rendered"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        GeilerAddonsLog.write(
            Category.VISUAL,
            name(),
            diagnosticTick,
            "STATUS enabled=true,lifecycle=enabled,highlights=${highlights.size},listLimit=$MAX_HIGHLIGHTS,lineLimit=$MAX_DIAGNOSTIC_LINE_LENGTH",
        )
    }

    override fun onDisable() {
        clearMatches()
        lastWorldRender = "skipped-disabled"
        lastHudRender = "skipped-disabled"
        GeilerAddonsLog.write(
            Category.VISUAL,
            name(),
            diagnosticTick,
            "STATUS enabled=false,matchesCleared=true,scan={$lastScan},world={$lastWorldRender},hud={$lastHudRender}," +
                "suppressedChanges=$suppressedDiagnosticChanges,lineLimit=$MAX_DIAGNOSTIC_LINE_LENGTH",
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), false)
    }

    fun tick() {
        if (!isEnabled()) return

        diagnosticTick++
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        val range = minOf(minecraft.options.getEffectiveRenderDistance() * 16.0, MAX_RANGE.toDouble())
        val currentIsland = HypixelModApi.currentIsland()
        if (highlights.isEmpty()) {
            lastScan = "skipped-no-highlights"
            reportDiagnostics(minecraft, range, currentIsland)
            return
        }

        if (level != lastLevel) {
            lastLevel = level
            clearMatches()
        }
        if (level == null || player == null) {
            lastScan = "skipped-no-level-or-player"
            reportDiagnostics(minecraft, range, currentIsland)
            return
        }

        val active = ArrayList<MobHighlight>()
        val needles = IdentityHashMap<MobHighlight, String>()
        var dueHighlights = 0
        var blankNeedles = 0
        for (highlight in highlights) {
            // Switching a rule off or walking off its island takes its boxes down immediately.
            if (!highlight.enabled().value() || !highlight.appliesOn(currentIsland)) {
                highlight.clearMatches()
                continue
            }
            if (!highlight.dueForScan()) continue
            dueHighlights++
            val needle = normalizeNeedle(highlight.matchText().value())
            if (needle.isEmpty()) {
                blankNeedles++
                highlight.clearMatches()
                continue
            }
            active += highlight
            needles[highlight] = needle
        }
        if (active.isEmpty()) {
            lastScan = "due=$dueHighlights,active=0,blankNeedles=$blankNeedles,skipped=no-active-needle"
            reportDiagnostics(minecraft, range, currentIsland)
            return
        }

        // One nearby query and one entity pass serve every due highlight. Search needles are
        // normalized once and body resolution is shared so extra highlights do not repeat it.
        val nearby = ClientEntitySnapshot.nearby(level, player, range)
        val nearbyBodies = nearby.filterIsInstance<LivingEntity>()
        val matchesByHighlight = IdentityHashMap<MobHighlight, MutableList<Entity>>()
        for (highlight in active) matchesByHighlight[highlight] = ArrayList()
        val resolvedBodies = IdentityHashMap<Entity, Entity>()
        val unresolvedBodies: MutableSet<Entity> = Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())
        var matchingEntities = 0
        for (entity in nearby) {
            if (entity === player || !entity.isAlive()) continue
            for (highlight in active) {
                if (!matches(entity, needles.getValue(highlight), highlight.matchName().value())) continue
                matchingEntities++
                if (unresolvedBodies.contains(entity)) continue
                var target = resolvedBodies[entity]
                if (target == null && !resolvedBodies.containsKey(entity)) {
                    target = Nameplates.resolveBody(entity, nearbyBodies, false)
                    if (target == null) unresolvedBodies += entity else resolvedBodies[entity] = target
                }
                if (target != null) {
                    val targets = matchesByHighlight.getValue(highlight)
                    if (!targets.contains(target)) targets += target
                }
            }
        }
        var matchedTargets = 0
        for (highlight in active) {
            val found = matchesByHighlight.getValue(highlight)
            highlight.setMatches(found)
            matchedTargets += found.size
        }
        lastScan = "due=$dueHighlights,active=${active.size},blankNeedles=$blankNeedles," +
            "nearby=${nearby.size},living=${nearbyBodies.size},matchingEntities=$matchingEntities," +
            "matchedTargets=$matchedTargets"
        reportDiagnostics(minecraft, range, currentIsland)
    }

    /** @param byName true to match the mob's own name, false to match what kind of thing it is. */
    private fun matches(entity: Entity, needle: String, byName: Boolean): Boolean {
        if (byName) {
            val custom = entity.getCustomName()
            return matchesNameText((custom ?: entity.getName()).getString(), needle)
        }
        // Accept both the English registry suffix and the visible localized entity label.
        val type: EntityType<*> = entity.getType()
        return matchesTypeText(type.getDescriptionId(), type.getDescription().getString(), needle)
    }

    private fun clearMatches() {
        for (highlight in highlights) highlight.clearMatches()
    }

    /** Renders match boxes into the current 26.2 world submission buffer. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || highlights.isEmpty()) {
            lastWorldRender = if (!isEnabled()) "skipped-disabled" else "skipped-no-highlights"
            return
        }

        val cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        var submittedBoxes = 0

        for (highlight in highlights) {
            if (highlight.matches().isEmpty()) continue
            val fill = highlight.fillColor().argb()
            val outline = highlight.outlineColor().argb()
            val depth = highlight.depthCheck().value()
            for (entity in highlight.matches()) {
                if (entity.isRemoved()) continue
                val box: AABB = entity.getBoundingBox()
                val bounds = MobHighlightGeometry.relativeBounds(box, cameraPosition)
                EspRenderer.renderBox(
                    poseStack,
                    bufferSource,
                    bounds.minX,
                    bounds.minY,
                    bounds.minZ,
                    bounds.sizeX(),
                    bounds.sizeY(),
                    bounds.sizeZ(),
                    fill,
                    outline,
                    BOX_LINE_WIDTH,
                    depth,
                )
                submittedBoxes++
            }
        }
        if (submittedBoxes > 0) GeilerAddonsRenderTypes.endBatches(bufferSource)
        lastWorldRender = "geometry=entity-aabb,submittedBoxes=$submittedBoxes,flushed=${submittedBoxes > 0}"
    }

    /** The highlight's name above each match, projected onto the HUD in Minecraft's real font. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || highlights.isEmpty()) {
            lastHudRender = if (!isEnabled()) "skipped-disabled" else "skipped-no-highlights"
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null || minecraft.player == null || minecraft.gui.hud.isHidden()) {
            lastHudRender = if (minecraft.gui.hud.isHidden()) "skipped-hidden-hud" else "skipped-no-world-or-player"
            return
        }

        val camera: Camera = minecraft.gameRenderer.mainCamera()
        val scale = labelSize.value()
        var labelSubmissions = 0
        for (highlight in highlights) {
            val label = highlight.displayName().value()
            if (label.isBlank() || highlight.matches().isEmpty()) continue
            val color = highlight.outlineColor().argb()
            for (entity in highlight.matches()) {
                if (entity.isRemoved()) continue
                val box: AABB = entity.getBoundingBox()
                val center: Vec3 = box.getCenter()
                val world = Vec3(center.x, box.maxY + LABEL_HEIGHT, center.z)
                ProjectedLabelRenderer.draw(graphics, camera, minecraft.font, world, label, color, scale)
                labelSubmissions++
            }
        }
        lastHudRender = "labelSubmissions=$labelSubmissions"
    }

    private fun reportDiagnostics(minecraft: Minecraft, range: Double, currentIsland: Island?) {
        val enabledHighlights = highlights.count { it.enabled().value() }
        val applicableHighlights = highlights.count { it.enabled().value() && it.appliesOn(currentIsland) }
        val samples = highlights.take(MAX_DIAGNOSTIC_HIGHLIGHT_SAMPLES).joinToString("|") { highlight ->
            val enabled = highlight.enabled().value()
            val applicable = enabled && highlight.appliesOn(currentIsland)
            val selectedIslands = highlight.islands().values.count { it.value() }
            val flags = listOf(highlight.matchName().value(), highlight.depthCheck().value())
                .joinToString("") { if (it) "1" else "0" }
            "${highlight.id()}:enabled=${if (enabled) 1 else 0},applies=${if (applicable) 1 else 0}," +
                "mode=${if (highlight.matchName().value()) "name" else "type"}," +
                "needleLength=${highlight.matchText().value().length},interval=${highlight.scanInterval().intValue()}," +
                "islands=$selectedIslands,flags=$flags,outline=${highlight.outlineColor().argb()}," +
                "fill=${highlight.fillColor().argb()},labelLength=${highlight.displayName().value().length}," +
                "matches=${highlight.matches().size}"
        }
        val state = buildString {
            append("enabled=true,level=").append(minecraft.level != null)
            append(",player=").append(minecraft.player != null)
            append(",hypixelLocation=").append(HypixelModApi.hasLocation())
            append(",island=").append(currentIsland?.name ?: "none")
            append(",range=").append(range)
            append(",highlights=").append(highlights.size)
            append(",enabledHighlights=").append(enabledHighlights)
            append(",applicableHighlights=").append(applicableHighlights)
            append(",folders=").append(folders.folders().size)
            append(",labelSize=").append(labelSize.value())
            append(",settingsHash=").append(java.lang.Long.toHexString(settingsFingerprint()))
            append(",scan{").append(lastScan).append('}')
            append(",world{").append(lastWorldRender).append('}')
            append(",hud{").append(lastHudRender).append('}')
            append(",highlightSamples=").append(samples.ifEmpty { "none" })
            if (highlights.size > MAX_DIAGNOSTIC_HIGHLIGHT_SAMPLES) {
                append(",omittedHighlightSamples=").append(highlights.size - MAX_DIAGNOSTIC_HIGHLIGHT_SAMPLES)
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
        GeilerAddonsLog.write(Category.VISUAL, name(), diagnosticTick, line)
        lastDiagnosticState = state
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0
    }

    private fun settingsFingerprint(): Long {
        var hash = FNV_OFFSET
        for (highlight in highlights) {
            hash = mix(hash, highlight.id().toLong())
            hash = mix(hash, highlight.folderId()?.hashCode()?.toLong() ?: 0L)
            hash = mix(hash, highlight.matchText().value().hashCode().toLong())
            hash = mix(hash, highlight.displayName().value().hashCode().toLong())
            hash = mix(hash, highlight.scanInterval().intValue().toLong())
            hash = mix(hash, highlight.outlineColor().argb().toLong())
            hash = mix(hash, highlight.fillColor().argb().toLong())
            listOf(
                highlight.enabled().value(),
                highlight.matchName().value(),
                highlight.depthCheck().value(),
            ).forEach { hash = mix(hash, if (it) 1L else 0L) }
            for ((island, setting) in highlight.islands()) {
                hash = mix(hash, island.ordinal.toLong())
                hash = mix(hash, if (setting.value()) 1L else 0L)
            }
        }
        return mix(hash, labelSize.value().toBits().toLong())
    }

    private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

    companion object {
        @JvmField
        val INSTANCE = MobHighlightModule()

        const val MAX_HIGHLIGHTS = 32
        private const val MAX_RANGE = 64
        private const val BOX_LINE_WIDTH = 2f
        private const val LABEL_HEIGHT = 0.4
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_HIGHLIGHT_SAMPLES = 8
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 4096
        private const val MAX_SUPPRESSED_CHANGES = 1_000_000L
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001B3L

        /** Normalize the configured substring exactly once before a scan. */
        @JvmStatic
        fun normalizeNeedle(text: String): String = ChatText.plain(text).trim().lowercase(Locale.ROOT)

        /** Match a visible or custom entity name using the same plain-text containment rule. */
        @JvmStatic
        fun matchesNameText(visibleName: String, needle: String): Boolean =
            ChatText.plain(visibleName).lowercase(Locale.ROOT).contains(needle)

        /** Match either the English entity ID suffix or the localized visible type description. */
        @JvmStatic
        fun matchesTypeText(descriptionId: String, visibleDescription: String, needle: String): Boolean {
            val suffix = descriptionId.substring(descriptionId.lastIndexOf('.') + 1)
            return suffix.lowercase(Locale.ROOT).contains(needle) ||
                ChatText.plain(visibleDescription).lowercase(Locale.ROOT).contains(needle)
        }
    }
}
