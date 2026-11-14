package geiler.addons.client.macros.command

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.pathing.Terrain
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/pathfind snapshot [radius]`: writes the walkable surfaces around the player as a small JSON file and nothing else of
 * the world: every place the pathfinder's own terrain rules say a player can stand, as x, y, z of the feet. The file is
 * `<config>/geileraddons/macros/snapshots/latest.json` (overwritten) and a time-stamped copy, so a bug report can be
 * checked against the ground the planner saw.
 */
object WalkableSnapshot {
    const val DEFAULT_RADIUS = 24
    const val MAX_RADIUS = 64

    /** How far above and below the player the ground is read. */
    private const val SPAN_UP = 12
    private const val SPAN_DOWN = 24
    private val TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun directory(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("macros").resolve("snapshots")

    @JvmStatic
    fun write(radius: Int, say: (String) -> Unit, fail: (String) -> Unit): Int {
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            fail("Join a world first.")
            return 0
        }
        val terrain = Terrain(level, player, MacroRuntime.config)
        val centre = player.blockPosition()
        val stands = JsonArray()
        var count = 0
        val started = System.nanoTime()
        for (x in centre.x - radius..centre.x + radius) {
            for (z in centre.z - radius..centre.z + radius) {
                val dx = x - centre.x
                val dz = z - centre.z
                if (dx * dx + dz * dz > radius * radius) continue
                for (y in centre.y + SPAN_UP downTo centre.y - SPAN_DOWN) {
                    if (terrain.standAt(x, y, z) == null) continue
                    val cell = JsonArray(3)
                    cell.add(x)
                    cell.add(y)
                    cell.add(z)
                    stands.add(cell)
                    count++
                }
            }
        }
        val json = JsonObject()
        json.addProperty("schemaVersion", 1)
        json.addProperty("dimension", level.dimension().identifier().toString())
        json.addProperty("takenAt", LocalDateTime.now().toString())
        val origin = JsonArray(3)
        origin.add(centre.x)
        origin.add(centre.y)
        origin.add(centre.z)
        json.add("origin", origin)
        json.addProperty("radius", radius)
        json.addProperty("note", "Each entry is the block position of the feet of a place a player can stand, by the pathfinder's rules.")
        json.addProperty("standCount", count)
        json.add("stands", stands)
        val directory = directory()
        val latest = directory.resolve("latest.json")
        val stamped = directory.resolve("snapshot-" + LocalDateTime.now().format(TIME) + ".json")
        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, "snapshot-", ".tmp")
            Files.writeString(temporary, GsonBuilder().create().toJson(json))
            Files.copy(temporary, stamped, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary, latest, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: java.io.IOException) {
            fail("Could not write the snapshot: ${error.message}")
            return 0
        }
        val millis = (System.nanoTime() - started) / 1_000_000L
        say("Snapshot: $count standing places within $radius blocks, written to $latest")
        AreaKillerMacro.diagnostics("snapshot standCount=$count radius=$radius origin=${centre.x},${centre.y},${centre.z} millis=$millis file=${latest.fileName}")
        return 1
    }
}
