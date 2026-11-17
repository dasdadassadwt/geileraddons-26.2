package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.CrystalHollowsRules
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import geiler.addons.client.mining.CrystalHollowsScan
import geiler.addons.client.utility.PlayerAlerts

/**
 * Crystal Hollows waypoints, after Skyblocker's locations manager (LGPL-3.0) without its server sharing: a structure
 * is marked when a few blocks of chunks you already have loaded show its block column ([CrystalHollowsScan]), where
 * you stand when the sidebar names it, or where you accept coordinates a chat message shared (the mod asks what they
 * are); `/ga hollows` adds, removes and shares markers; the Crystal Nucleus is always marked; and a treasure chest you
 * uncover is boxed through walls.
 */
class CrystalHollowsHelperModule private constructor(settings: Settings) : Module(
    "Crystal Hollows Helper",
    "Waypoints for Crystal Hollows structures and the Nucleus, and a box on uncovered treasure chests.",
    Category.MINING,
    false,
    settings.structures, settings.scan, settings.chatCoordinates, settings.nucleus, settings.labels, settings.chests, settings.chestColor,
) {
    private val scan = settings.scan
    private val structures = settings.structures
    private val chatCoordinates = settings.chatCoordinates
    private val nucleus = settings.nucleus
    private val labels = settings.labels
    private val chests = settings.chests
    private val chestColor = settings.chestColor

    private class Waypoint(val name: String, val argb: Int, val x: Int, val y: Int, val z: Int, val structure: CrystalHollowsRules.Structure?)

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private val waypoints = LinkedHashMap<String, Waypoint>()
    private val chestSpots = LinkedHashSet<BlockPos>()
    private var waitingForChest = 0
    private var tickCount = 0L
    private var chatHits = 0L
    private var customCount = 0
    private var lastAdded: String? = null
    private val prompted = ArrayDeque<String>()

    private class Candidate(val signature: CrystalHollowsScan.Signature, val second: Block?)

    private val scanTries = CrystalHollowsScan.ChunkTries()
    private var scanLevel: Level? = null
    private var scannedChunks = 0L
    private var scanNanos = 0L
    private var scanWorstNanos = 0L
    private val candidates: Map<Block, List<Candidate>> by lazy {
        val map = HashMap<Block, MutableList<Candidate>>()
        for (signature in CrystalHollowsScan.signatures) {
            val first = block(signature.column.first()) ?: continue
            map.getOrPut(first) { ArrayList() }.add(Candidate(signature, signature.column.getOrNull(1)?.let(::block)))
        }
        map
    }

    init {
        group(SettingGroup("Waypoints", structures, scan, chatCoordinates, nucleus, labels), SettingGroup("Treasure Chests", chests, chestColor))
    }

    private fun block(id: String?): Block? = id?.let { Identifier.tryParse(it) }?.let(BuiltInRegistries.BLOCK::getValue)

    private fun settingsText(): String =
        "structures=${structures.value()};scan=${scan.value()};chat=${chatCoordinates.value()};nucleus=${nucleus.value()};chests=${chests.value()}"

    override fun onEnable() {
        reset()
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        waypoints.clear()
        chestSpots.clear()
        prompted.clear()
        scanTries.clear()
        scannedChunks = 0L
        scanNanos = 0L
        scanWorstNanos = 0L
        waitingForChest = 0
        customCount = 0
        lastAdded = null
    }

    private fun add(waypoint: Waypoint, source: String) {
        waypoints[waypoint.name] = waypoint
        lastAdded = waypoint.name
        diagnostics.event("WAYPOINT source=$source name=\"${waypoint.name}\" x=${waypoint.x} y=${waypoint.y} z=${waypoint.z} total=${waypoints.size}")
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(plain) }
            return
        }
        if (!MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        if (chests.value() && plain == CrystalHollowsRules.CHEST_MESSAGE) {
            waitingForChest++
            diagnostics.event("CHEST_MESSAGE waiting=$waitingForChest")
            return
        }
        if (!chatCoordinates.value()) return
        val colon = plain.indexOf(':')
        if (colon < 0) return
        val body = plain.substring(colon + 1)
        val at = CrystalHollowsRules.parseCoordinates(body) ?: return
        if (!CrystalHollowsRules.inBounds(at[0], at[1], at[2])) return
        chatHits++
        if (waypoints.values.any { CrystalHollowsRules.sameSpot(it.x, it.y, it.z, at[0], at[1], at[2]) }) return
        val key = "${at[0]},${at[1]},${at[2]}"
        if (key in prompted) return
        prompted.addLast(key)
        if (prompted.size > MAX_PROMPTED) prompted.removeFirst()
        val guess = CrystalHollowsRules.structureIn(body)
        diagnostics.event("CHAT_PROMPT x=${at[0]} y=${at[1]} z=${at[2]} guess=${guess?.key ?: "none"}")
        prompt(at, guess)
    }

    /** Asks in chat what the coordinates are: one click adds the marker, nothing is added on its own. */
    private fun prompt(at: IntArray, guess: CrystalHollowsRules.Structure?) {
        val chat = Minecraft.getInstance().gui?.hud?.chat ?: return
        val coords = "${at[0]} ${at[1]} ${at[2]}"
        val line = Component.literal("§6[GeilerAddons] §fCoordinates §e$coords§f in chat. Add as: ")
        val ordered = listOfNotNull(guess) + CrystalHollowsRules.Structure.entries.filter { it != guess }
        for (structure in ordered) {
            line.append(button(structure.label, "/ga hollows add ${structure.key} $coords", if (structure == guess) ChatFormatting.GREEN else ChatFormatting.AQUA)).append(" ")
        }
        line.append(button("Other", "/ga hollows add other $coords", ChatFormatting.GRAY))
        chat.addClientSystemMessage(line)
    }

    private fun button(label: String, command: String, color: ChatFormatting): Component =
        Component.literal("[$label]").withStyle { style ->
            style.withColor(color).withUnderlined(true)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Add as $label")))
        }

    /** `/ga hollows add`: marks [structure] (null = a plain shared location) at the spot. Returns the marker name. */
    fun addWaypoint(structure: CrystalHollowsRules.Structure?, x: Int, y: Int, z: Int): String? {
        if (!isEnabled() || waypoints.size >= MAX_WAYPOINTS && structure == null) return null
        val waypoint = if (structure != null) {
            Waypoint(structure.label, structure.argb, x, y, z, structure)
        } else {
            Waypoint(UNKNOWN + " " + (++customCount), WHITE, x, y, z, null)
        }
        add(waypoint, "command")
        return waypoint.name
    }

    /** `/ga hollows remove`: by name or command key; returns whether a marker went. */
    fun removeWaypoint(name: String): Boolean {
        val wanted = name.trim()
        val hit = waypoints.values.firstOrNull { it.name.equals(wanted, true) || it.structure?.key.equals(wanted, true) } ?: return false
        waypoints.remove(hit.name)
        diagnostics.event("WAYPOINT_REMOVED name=\"${hit.name}\" total=${waypoints.size}")
        return true
    }

    fun clearWaypoints(): Int {
        val count = waypoints.size
        waypoints.clear()
        diagnostics.event("WAYPOINTS_CLEARED count=$count")
        return count
    }

    fun waypointNames(): List<String> = waypoints.keys.toList()

    fun describe(): List<String> = waypoints.values.map { "${it.name} ${it.x} ${it.y} ${it.z}" }

    /** The chat text for `/ga hollows share`: the named marker, or the one added last when [name] is blank. */
    fun shareText(name: String): String? {
        val wanted = name.trim()
        val hit = (if (wanted.isEmpty()) lastAdded?.let { waypoints[it] } else null)
            ?: waypoints.values.firstOrNull { it.name.equals(wanted, true) || it.structure?.key.equals(wanted, true) }
            ?: return null
        return CrystalHollowsRules.shareText(hit.name, hit.x, hit.y, hit.z)
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        tickCount++
        val player = mc.player
        val level = mc.level
        if (level !== scanLevel) {
            if (scanLevel != null) {
                diagnostics.event("NEW_LEVEL waypoints=${waypoints.size} scanned=$scannedChunks")
                reset()
            }
            scanLevel = level
        }
        if (level == null || player == null || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) {
            if (waypoints.isNotEmpty() || chestSpots.isNotEmpty() || scannedChunks > 0) {
                diagnostics.event("LEFT_HOLLOWS waypoints=${waypoints.size} chests=${chestSpots.size} scanned=$scannedChunks")
                reset()
            }
            diagnostics.statusOnChange { "state=outside-hollows" }
            return
        }
        if (tickCount % AREA_TICKS == 0L && structures.value()) {
            val structure = CrystalHollowsRules.structureForArea(MiningContext.sidebarLines())
            if (structure != null && !waypoints.containsKey(structure.label)) {
                add(Waypoint(structure.label, structure.argb, player.blockX, player.blockY, player.blockZ, structure), "area")
                waypoints.keys.removeIf { it.startsWith(UNKNOWN) && close(waypoints[it]!!, player.blockX, player.blockY, player.blockZ) }
            }
        }
        if (tickCount % SCAN_TICKS == 0L && scan.value() && structures.value()) scanVisible(mc, level, player.blockX shr 4, player.blockZ shr 4)
        diagnostics.statusOnChange {
            "state=ready waypoints=${waypoints.size} chests=${chestSpots.size} waiting=$waitingForChest chatHits=$chatHits " +
                "scanned=$scannedChunks scanMs=${scanNanos / 1_000_000} worstMs=${scanWorstNanos / 1_000_000}"
        }
    }

    /**
     * Reads the nearest loaded chunks not yet read (at most [CHUNKS_PER_SCAN] per call, each chunk at most
     * [CrystalHollowsScan.MAX_TRIES] times a lobby) for the structures' block columns. Only blocks the client has.
     */
    private fun scanVisible(mc: Minecraft, level: Level, pcx: Int, pcz: Int) {
        val radius = mc.options.effectiveRenderDistance
        var budget = CHUNKS_PER_SCAN
        for (ring in 0..radius) {
            for (dx in -ring..ring) {
                for (dz in -ring..ring) {
                    if (maxOf(Math.abs(dx), Math.abs(dz)) != ring) continue
                    val cx = pcx + dx
                    val cz = pcz + dz
                    if (!CrystalHollowsScan.chunkInHollows(cx, cz)) continue
                    val key = CrystalHollowsScan.chunkKey(cx, cz)
                    val chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false) ?: continue
                    if (!scanTries.claim(key, tickCount)) continue
                    val start = System.nanoTime()
                    scanChunk(chunk, cx, cz)
                    val spent = System.nanoTime() - start
                    scannedChunks++
                    scanNanos += spent
                    if (spent > scanWorstNanos) scanWorstNanos = spent
                    if (--budget <= 0) return
                }
            }
        }
    }

    private fun scanChunk(chunk: ChunkAccess, cx: Int, cz: Int) {
        val pos = BlockPos.MutableBlockPos()
        val lookup = CrystalHollowsScan.BlockLookup { x, y, z -> BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos.set(x, y, z)).block).path }
        val sections = chunk.sections
        for (i in sections.indices) {
            val section = sections[i]
            val baseY = chunk.getSectionYFromSectionIndex(i) shl 4
            if (section == null || baseY + 15 < CrystalHollowsScan.MIN_Y || baseY > CrystalHollowsScan.MAX_Y || section.hasOnlyAir() ||
                !section.maybeHas { candidates.containsKey(it.block) }
            ) continue
            for (y in maxOf(CrystalHollowsScan.MIN_Y, baseY)..minOf(CrystalHollowsScan.MAX_Y, baseY + 15)) {
                for (x in 0 until 16) for (z in 0 until 16) {
                    val list = candidates[section.getBlockState(x, y and 15, z).block] ?: continue
                    val wx = (cx shl 4) + x
                    val wz = (cz shl 4) + z
                    for (candidate in list) {
                        val signature = candidate.signature
                        if (!CrystalHollowsScan.quarterAllows(signature, wx, y, wz)) continue
                        if (candidate.second != null && chunk.getBlockState(pos.set(wx, y + 1, wz)).block !== candidate.second) continue
                        if (CrystalHollowsScan.matches(signature, wx, y, wz, lookup)) found(signature, wx, y, wz)
                    }
                }
            }
        }
    }

    private fun found(signature: CrystalHollowsScan.Signature, x: Int, y: Int, z: Int) {
        val at = CrystalHollowsScan.markAt(signature, x, y, z)
        val structure = signature.structure
        if (waypoints.values.any { it.structure == structure && CrystalHollowsScan.sameFind(signature, it.x, it.z, at[0], at[2]) }) return
        if (waypoints.size >= MAX_WAYPOINTS) return
        var name = structure.label
        var n = 1
        while (waypoints.containsKey(name)) name = structure.label + " " + (++n)
        add(Waypoint(name, structure.argb, at[0], at[1], at[2], structure), "scan")
        PlayerAlerts.chat("§6[GeilerAddons] §fFound §e$name§f at §6${at[0]}§r, §3${at[1]}§r, §d${at[2]}")
    }

    private fun close(w: Waypoint, x: Int, y: Int, z: Int): Boolean = CrystalHollowsRules.sameSpot(w.x, w.y, w.z, x, y, z)

    /** `handleBlockUpdate` and section updates: a chest appearing near you after the uncover message is the treasure. */
    fun onBlockChange(pos: BlockPos, new: BlockState) {
        if (!isEnabled() || !chests.value()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        val player = mc.player ?: return
        if (waitingForChest > 0 && new.`is`(Blocks.CHEST)) {
            if (player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > CHEST_RANGE_SQ) return
            chestSpots.add(pos.immutable())
            waitingForChest--
            diagnostics.event("CHEST_BOXED x=${pos.x} y=${pos.y} z=${pos.z} boxed=${chestSpots.size}")
        } else if (new.isAir && chestSpots.remove(pos)) {
            diagnostics.event("CHEST_GONE x=${pos.x} y=${pos.y} z=${pos.z}")
        }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        val client = Minecraft.getInstance()
        val cam = client.gameRenderer.mainCamera().position()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        for (w in waypoints.values) {
            EspRenderer.renderBox(
                pose, buffer, w.x - 0.5 - cam.x, w.y - cam.y, w.z - 0.5 - cam.z, 1.0, 1.0, 1.0,
                (w.argb and 0x00FFFFFF) or FILL_ALPHA, w.argb, LINE_WIDTH,
            )
        }
        if (nucleus.value()) {
            EspRenderer.renderBox(
                pose, buffer, CrystalHollowsRules.NUCLEUS_X - 0.5 - cam.x, CrystalHollowsRules.NUCLEUS_Y - cam.y,
                CrystalHollowsRules.NUCLEUS_Z - 0.5 - cam.z, 1.0, 1.0, 1.0, (NUCLEUS_COLOR and 0x00FFFFFF) or FILL_ALPHA, NUCLEUS_COLOR, LINE_WIDTH,
            )
        }
        if (chests.value()) {
            val color = chestColor.opaqueArgb()
            for (spot in chestSpots) {
                EspRenderer.renderBox(
                    pose, buffer, spot.x - cam.x, spot.y - cam.y, spot.z - cam.z, 1.0, 1.0, 1.0,
                    (color and 0x00FFFFFF) or FILL_ALPHA, color, LINE_WIDTH,
                )
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !labels.value() || !MiningContext.on(Island.CRYSTAL_HOLLOWS)) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val camera = client.gameRenderer.mainCamera()
        for (w in waypoints.values) label(graphics, camera, client, player, w.name, w.argb, w.x, w.y, w.z)
        if (nucleus.value()) {
            label(
                graphics, camera, client, player, "Crystal Nucleus", NUCLEUS_COLOR,
                CrystalHollowsRules.NUCLEUS_X, CrystalHollowsRules.NUCLEUS_Y, CrystalHollowsRules.NUCLEUS_Z,
            )
        }
    }

    private fun label(
        graphics: GuiGraphicsExtractor, camera: net.minecraft.client.Camera, client: Minecraft,
        player: net.minecraft.world.entity.player.Player, text: String, argb: Int, x: Int, y: Int, z: Int,
    ) {
        val distance = Math.sqrt(player.distanceToSqr(x + 0.0, y + 0.0, z + 0.0)).toInt()
        ProjectedLabelRenderer.draw(graphics, camera, client.font, Vec3(x + 0.0, y + 1.6, z + 0.0), "$text §7${distance}m", argb, 1.4f)
    }

    private class Settings {
        val structures = BooleanSetting("Structure Waypoints", true)
        val scan = BooleanSetting("Scan Loaded Chunks", true)
        val chatCoordinates = BooleanSetting("Ask About Chat Coordinates", true)
        val nucleus = BooleanSetting("Crystal Nucleus", true)
        val labels = BooleanSetting("Labels", true)
        val chests = BooleanSetting("Highlight Treasure Chests", true)
        val chestColor = ColorSetting("Chest Color", 255, 170, 0, 255)
    }

    companion object {
        private const val AREA_TICKS = 20L
        private const val SCAN_TICKS = 10L
        private const val CHUNKS_PER_SCAN = 2
        private const val MAX_WAYPOINTS = 12
        private const val MAX_PROMPTED = 20
        private const val CHEST_RANGE_SQ = 100.0
        private const val FILL_ALPHA = 0x40000000
        private const val LINE_WIDTH = 3.0f
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val NUCLEUS_COLOR = 0xFF55FFFF.toInt()
        private const val UNKNOWN = "Shared Location"

        @JvmField
        val INSTANCE = CrystalHollowsHelperModule(Settings())
    }
}
