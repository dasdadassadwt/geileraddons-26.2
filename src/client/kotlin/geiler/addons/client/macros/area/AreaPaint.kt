package geiler.addons.client.macros.area

import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The painted areas on disk and in memory: one text file per area under `config/geileraddons/areas/`. An area is used by the Area
 * Killer when its name is set in the config; one is shown in the world while it is painted or when asked for.
 */
object PaintStore {
    private val diagnostics = ModuleDiagnostics(Category.AREA_KILLER, { "Area Paint" })
    private val areas = HashMap<String, PaintedArea>()
    private val dirty = HashSet<String>()
    private var lastEditMillis = 0L
    private var scanned = false
    private var shown: String? = null
    private var ticks = 0
    private var logging = false

    private fun directory(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("areas")

    /** A name is letters, digits, dash and underscore, up to 32, lower case. Null when it is none of that. */
    @JvmStatic
    fun clean(name: String): String? {
        val lower = name.trim().lowercase(Locale.ROOT)
        return if (Regex("[a-z0-9_-]{1,32}").matches(lower)) lower else null
    }

    @JvmStatic
    fun names(): List<String> {
        val found = HashSet<String>(areas.keys)
        try {
            if (Files.isDirectory(directory())) {
                Files.list(directory()).use { stream ->
                    stream.forEach { file ->
                        val name = file.fileName.toString()
                        if (name.endsWith(EXTENSION)) clean(name.removeSuffix(EXTENSION))?.let { found.add(it) }
                    }
                }
            }
        } catch (exception: java.io.IOException) {
            event("list failed ${exception.javaClass.simpleName}")
        }
        scanned = true
        return found.sorted()
    }

    /** The area of this name, read from its file the first time; null when there is none. */
    @JvmStatic
    fun get(name: String): PaintedArea? {
        val key = clean(name) ?: return null
        areas[key]?.let { return it }
        val file = directory().resolve(key + EXTENSION)
        if (!Files.isRegularFile(file)) return null
        return try {
            val area = PaintedArea.parse(key, String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            if (area == null) event("read refused name=$key reason=not an area file") else areas[key] = area
            area
        } catch (exception: java.io.IOException) {
            event("read failed name=$key ${exception.javaClass.simpleName}")
            null
        }
    }

    @JvmStatic
    fun getOrCreate(name: String): PaintedArea? {
        val key = clean(name) ?: return null
        return get(key) ?: PaintedArea(key).also {
            areas[key] = it
            dirty.add(key)
            event("created name=$key")
        }
    }

    @JvmStatic
    fun delete(name: String): Boolean {
        val key = clean(name) ?: return false
        areas.remove(key)
        dirty.remove(key)
        if (shown == key) shown = null
        return try {
            val gone = Files.deleteIfExists(directory().resolve(key + EXTENSION))
            event("deleted name=$key existed=$gone")
            gone
        } catch (exception: java.io.IOException) {
            event("delete failed name=$key ${exception.javaClass.simpleName}")
            false
        }
    }

    @JvmStatic
    fun touched(area: PaintedArea) {
        dirty.add(area.name)
        lastEditMillis = System.currentTimeMillis()
    }

    @JvmStatic
    fun show(name: String?) {
        shown = name
    }

    /** The area drawn in the world: the one being painted, else the one asked for, else the one the macro uses. */
    @JvmStatic
    fun visible(): PaintedArea? {
        val name = PaintBrush.painting()?.name ?: shown ?: MacroRuntime.config.paintArea.takeIf { it.isNotEmpty() }
        return name?.let { areas[it] ?: get(it) }
    }

    @JvmStatic
    fun saveAll() {
        for (key in dirty.toList()) {
            val area = areas[key] ?: continue
            try {
                Files.createDirectories(directory())
                Files.write(directory().resolve(key + EXTENSION), area.serialize().toByteArray(StandardCharsets.UTF_8))
                event("saved name=$key cells=${area.size()}")
            } catch (exception: java.io.IOException) {
                event("save failed name=$key ${exception.javaClass.simpleName}")
            }
        }
        dirty.clear()
    }

    /** Called every client tick: keeps the log's budget and writes edits a moment after the last one. */
    @JvmStatic
    fun tick() {
        ticks++
        if (logging) {
            diagnostics.advance()
            diagnostics.status { suppressed ->
                "painting=${PaintBrush.painting()?.name ?: "none"} areas=${areas.size} dirty=${dirty.size} suppressedEvents=$suppressed"
            }
        }
        if (dirty.isNotEmpty() && System.currentTimeMillis() - lastEditMillis > SAVE_AFTER_MILLIS) saveAll()
    }

    @JvmStatic
    fun event(text: String) {
        if (logging) diagnostics.event(text)
    }

    /** One line for a command, in a log that is opened for it and closed again. */
    @JvmStatic
    fun once(text: String) {
        val wasLogging = logging
        if (!wasLogging) enableLog("command")
        diagnostics.event(text)
        diagnostics.flush()
        if (!wasLogging) disableLog()
    }

    @JvmStatic
    fun enableLog(summary: String) {
        logging = true
        diagnostics.enable(summary)
    }

    @JvmStatic
    fun disableLog() {
        logging = false
        diagnostics.disable()
    }

    private const val EXTENSION = ".area"
    private const val SAVE_AFTER_MILLIS = 1500L
}

/**
 * The brush: while armed, every right click stamps the paint on the ground in a circle round the block looked at (the click itself is
 * taken, so nothing is used or placed). Painting finds the stands of the ground, the same ones the pathfinder uses, so each painted
 * cell is a place the macro could stand.
 */
object PaintBrush {
    class Brush(val area: PaintedArea, val radius: Int, val paint: Paint?)

    private var brush: Brush? = null

    @JvmStatic
    fun painting(): PaintedArea? = brush?.area

    @JvmStatic
    fun active(): Brush? = brush

    @JvmStatic
    fun start(area: PaintedArea, radius: Int, paint: Paint?) {
        if (brush == null) PaintStore.enableLog("brush armed")
        brush = Brush(area, radius.coerceIn(1, MAX_RADIUS), paint)
        PaintStore.event("brush area=${area.name} radius=${brush!!.radius} paint=${paint?.label ?: "erase"}")
    }

    @JvmStatic
    fun stop() {
        if (brush == null) return
        PaintStore.event("brush off area=${brush!!.area.name} cells=${brush!!.area.size()}")
        brush = null
        PaintStore.saveAll()
        PaintStore.disableLog()
    }

    /** The block cell the brush would be centred on: the air next to the block looked at, or null when nothing is. */
    @JvmStatic
    fun aim(client: Minecraft): BlockPos? {
        val player = client.player ?: return null
        val level = client.level ?: return null
        val eye = player.eyePosition
        val hit = level.clip(ClipContext(eye, eye.add(player.lookAngle.scale(REACH)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type != HitResult.Type.BLOCK) return null
        return hit.blockPos.relative(hit.direction)
    }

    /** `Minecraft.startUseItem` HEAD: returns true when the click was the brush's. */
    @JvmStatic
    fun onUse(): Boolean {
        val current = brush ?: return false
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.gui.screen() != null) return false
        val player = client.player ?: return false
        val level = client.level ?: return false
        val centre = aim(client) ?: return true
        val area = current.area
        val r = current.radius
        val terrain = Terrain(level, player, MacroRuntime.config)
        val stands = standsAround(terrain, centre, r)
        val reference = BrushLevel.reference(stands, centre.x, centre.y, centre.z)
        var changed = 0
        if (current.paint == null) {
            // Erasing works on the same level as painting: the cells of the disc within a cell of the reference's height.
            val levelY = reference?.y ?: centre.y
            for (dx in -r..r) for (dz in -r..r) {
                if (dx * dx + dz * dz > r * r) continue
                for (y in levelY - BrushLevel.TOLERANCE..levelY + BrushLevel.TOLERANCE) {
                    if (area.typeAt(centre.x + dx, y, centre.z + dz) != null) {
                        area.erase(centre.x + dx, y, centre.z + dz)
                        changed++
                    }
                }
            }
        } else {
            val chosen = BrushLevel.select(stands, reference)
            for (stand in chosen) {
                if (area.size() >= PaintedArea.MAX_CELLS) break
                val before = area.revision()
                area.put(stand.x, stand.y, stand.z, current.paint, ((stand.floorY - stand.y) * 8.0).roundToInt().coerceIn(0, 8))
                if (area.revision() != before) changed++
            }
            PaintStore.event(
                "brush level aim=${centre.x},${centre.y},${centre.z} candidates=${stands.size} reference=" +
                    (reference?.let { "${it.x},${it.y},${it.z}" } ?: "none") + " painted=${chosen.size}",
            )
        }
        if (changed > 0) {
            PaintStore.touched(area)
            PaintStore.event("stamp area=${area.name} at=${centre.x},${centre.y},${centre.z} radius=$r paint=${current.paint?.label ?: "erase"} changed=$changed cells=${area.size()}")
        }
        if (area.size() >= PaintedArea.MAX_CELLS) {
            client.gui.hud.getChat().addClientSystemMessage(Component.literal("§6[Area] §rThe area '${area.name}' is full (${PaintedArea.MAX_CELLS} cells)"))
        }
        return true
    }

    /** Every stand of the disc within a few cells of the aim's height: what [BrushLevel] picks the level and the cells from. */
    private fun standsAround(terrain: Terrain, centre: BlockPos, r: Int): List<BrushLevel.Stand> {
        val found = ArrayList<BrushLevel.Stand>()
        val span = BrushLevel.AIM_REACH + BrushLevel.TOLERANCE
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz > r * r) continue
            for (y in centre.y - span..centre.y + span) {
                val stand = terrain.standAt(centre.x + dx, y, centre.z + dz) ?: continue
                found.add(BrushLevel.Stand(centre.x + dx, y, centre.z + dz, stand.floorY))
            }
        }
        return found
    }

    /** The feet cell height the brush paints at when clicked now, for drawing its ring; null with no ground at the aim. */
    @JvmStatic
    fun levelY(client: Minecraft, centre: BlockPos): Int? {
        val player = client.player ?: return null
        val level = client.level ?: return null
        val terrain = Terrain(level, player, MacroRuntime.config)
        val column = ArrayList<BrushLevel.Stand>()
        val span = BrushLevel.AIM_REACH
        for (y in centre.y - span..centre.y + span) {
            val stand = terrain.standAt(centre.x, y, centre.z) ?: continue
            column.add(BrushLevel.Stand(centre.x, y, centre.z, stand.floorY))
        }
        return BrushLevel.reference(column, centre.x, centre.y, centre.z)?.y
    }

    const val MAX_RADIUS = 16
    private const val REACH = 64.0
}
