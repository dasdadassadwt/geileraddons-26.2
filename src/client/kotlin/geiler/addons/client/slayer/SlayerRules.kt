package geiler.addons.client.slayer

import java.util.Locale

/**
 * Pure text rules for the Slayer boss modules. Boss names, miniboss names, label markers and the Nukekubi skull
 * texture are game facts; the behaviour references are Skyblocker's slayer features and SkyHanni's slayer
 * features (see `docs/provenance/external-sources.md`). Nothing here touches Minecraft state.
 */
object SlayerRules {
    enum class Family { REVENANT, TARANTULA, SVEN, VOIDGLOOM, DEMONLORD, VAMPIRE }

    /** Blaze Slayer shield attunements with the colour each one is highlighted in (ARGB, opaque). */
    enum class Attunement(val argb: Int) {
        ASHEN(0xFF000000.toInt()),
        SPIRIT(0xFFFFFFFF.toInt()),
        CRYSTAL(0xFF5555FF.toInt()),
        AURIC(0xFFFFFF55.toInt()),
        IMMUNE(0xFFFF5555.toInt()),
    }

    private const val NUKEKUBI_TEXTURE =
        "eyJ0aW1lc3RhbXAiOjE1MzQ5NjM0MzU5NjIsInByb2ZpbGVJZCI6ImQzNGFhMmI4MzFkYTRkMjY5NjU1ZTMzYzE0M2YwOTZjIiwicHJvZmlsZU5hbWUiOiJFbmRlckRyYWdvbiIsInNpZ25hdHVyZVJlcXVpcmVkIjp0cnVlLCJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWIwNzU5NGUyZGYyNzM5MjFhNzdjMTAxZDBiZmRmYTExMTVhYmVkNWI5YjIwMjllYjQ5NmNlYmE5YmRiYjRiMyJ9fX0="

    private const val BLOOD_ICHOR_HASH = "c0340923a6de4825a176813d133503eff186db0896e32b6704928c2a2bf68422"
    private const val KILLER_SPRING_HASH = "77f7a7bc8ac86f23ca7bf98afeb76960227e1832fe209a3026f6ceb8bde74f54"

    private val BOSS_NAMES = listOf(
        "revenant horror" to Family.REVENANT,
        "atoned horror" to Family.REVENANT,
        "tarantula broodfather" to Family.TARANTULA,
        "conjoined brood" to Family.TARANTULA,
        "sven packmaster" to Family.SVEN,
        "voidgloom seraph" to Family.VOIDGLOOM,
        "inferno demonlord" to Family.DEMONLORD,
        "riftstalker bloodfiend" to Family.VAMPIRE,
        "bloodfiend" to Family.VAMPIRE,
    )

    private val MINIBOSS_NAMES = mapOf(
        Family.REVENANT to listOf(
            "revenant sycophant", "revenant champion", "deformed revenant", "atoned champion", "atoned revenant",
        ),
        Family.TARANTULA to listOf(
            "tarantula vermin", "tarantula beast", "mutant tarantula", "primordial jockey", "primordial viscount",
        ),
        Family.SVEN to listOf("pack enforcer", "sven follower", "sven alpha"),
        Family.VOIDGLOOM to listOf("voidling devotee", "voidling radical", "voidcrazed maniac"),
        Family.DEMONLORD to listOf("flare demon", "kindleheart demon", "burningsoul demon"),
    )

    private val ATTUNEMENT = Regex("ASHEN|SPIRIT|CRYSTAL|AURIC|IMMUNE")
    private val FIRE_PILLAR = Regex("(\\d{1,3})s \\d+ hits")

    /** Which Slayer a boss name label belongs to, or null when the text names no boss. */
    @JvmStatic
    fun bossFamily(plain: String?): Family? {
        if (plain.isNullOrEmpty()) return null
        val lower = plain.lowercase(Locale.ROOT)
        for ((name, family) in BOSS_NAMES) if (lower.contains(name)) return family
        return null
    }

    /** A boss name label that still has health: dead bosses keep their label at `0❤` for a moment. */
    @JvmStatic
    fun isLiveBossLabel(plain: String?): Boolean =
        bossFamily(plain) != null && plain!!.trimEnd().endsWith(" 0❤").not()

    /** The stand above a boss that names its owner; boss labels are excluded so a name inside one cannot match. */
    @JvmStatic
    fun isSpawnedByLabel(plain: String?, playerName: String?): Boolean =
        !plain.isNullOrEmpty() && !playerName.isNullOrEmpty() && bossFamily(plain) == null && plain.contains(playerName)

    /** Whether [plain] names a miniboss of [family], or of any family when [family] is null. */
    @JvmStatic
    fun isMinibossName(plain: String?, family: Family?): Boolean {
        if (plain.isNullOrEmpty()) return false
        val lower = plain.lowercase(Locale.ROOT)
        val names = if (family != null) MINIBOSS_NAMES[family].orEmpty() else MINIBOSS_NAMES.values.flatten()
        return names.any { lower.contains(it) }
    }

    @JvmStatic
    fun attunement(plain: String?): Attunement? {
        if (plain.isNullOrEmpty()) return null
        val found = ATTUNEMENT.find(plain) ?: return null
        return Attunement.valueOf(found.value)
    }

    /** Seconds left on a Blaze Slayer fire pillar label such as `5s 3 hits`, or -1 when [plain] is not one. */
    @JvmStatic
    fun firePillarSeconds(plain: String?): Int {
        if (plain.isNullOrEmpty()) return -1
        val match = FIRE_PILLAR.matchEntire(plain) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }

    @JvmStatic
    fun hasTwinclaws(plain: String?): Boolean = plain?.contains("TWINCLAWS") == true

    /** The glyph shown on the Bloodfiend label while Steak Stake can finish it. */
    @JvmStatic
    fun hasStakeMarker(plain: String?): Boolean = plain?.contains('҉') == true

    @JvmStatic
    fun isNukekubiTexture(texture: String?): Boolean = texture == NUKEKUBI_TEXTURE

    /** Blood Ichor skull stands fired by the Bloodfiend; the skin hash is the one SkyHanni's skull data lists. */
    @JvmStatic
    fun isBloodIchorTexture(texture: String?): Boolean = textureHasHash(texture, BLOOD_ICHOR_HASH)

    /** Killer Spring skull stands the Bloodfiend leaves behind; same source as [isBloodIchorTexture]. */
    @JvmStatic
    fun isKillerSpringTexture(texture: String?): Boolean = textureHasHash(texture, KILLER_SPRING_HASH)

    private fun textureHasHash(texture: String?, hash: String): Boolean {
        if (texture.isNullOrEmpty()) return false
        val decoded = try {
            String(java.util.Base64.getDecoder().decode(texture), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            return false
        }
        return decoded.contains(hash)
    }

    /**
     * The plain miniboss name inside a label such as `Voidling Devotee 12M❤`, title-cased, or null when [plain]
     * names no miniboss. Alerts show only this name, never the health that follows it.
     */
    @JvmStatic
    fun minibossName(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        val lower = plain.lowercase(Locale.ROOT)
        val match = MINIBOSS_NAMES.values.flatten().firstOrNull { lower.contains(it) } ?: return null
        return match.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    /** Sven pup labels (`Sven Pup ...`). The exact live wording is unverified, so the match is by phrase only. */
    @JvmStatic
    fun isPupLabel(plain: String?): Boolean = plain?.lowercase(Locale.ROOT)?.contains("sven pup") == true
}

/**
 * The Voidgloom laser phase countdown. A phase starts when the boss becomes mounted (the rising edge), lasts
 * [DURATION_TICKS], and a new start is ignored until [COOLDOWN_TICKS] after the previous one. The mounted signal
 * is the reference mods' own and is unverified here; the module logs each start as evidence.
 */
class LaserPhaseTimer {
    private var startTick = Long.MIN_VALUE
    private var wasMounted = false

    fun update(mounted: Boolean, tick: Long) {
        if (mounted && !wasMounted && (startTick == Long.MIN_VALUE || tick - startTick >= COOLDOWN_TICKS)) {
            startTick = tick
        }
        wasMounted = mounted
    }

    fun remainingTicks(tick: Long): Int {
        if (startTick == Long.MIN_VALUE) return 0
        val left = DURATION_TICKS - (tick - startTick)
        return if (left > 0) left.toInt() else 0
    }

    fun active(tick: Long): Boolean = remainingTicks(tick) > 0

    fun reset() {
        startTick = Long.MIN_VALUE
        wasMounted = false
    }

    companion object {
        const val DURATION_TICKS = 160
        const val COOLDOWN_TICKS = 200
    }
}

/** Answers whether the block at a position is a beacon; the module backs it with the client level. */
fun interface BlockProbe {
    fun isBeacon(x: Int, y: Int, z: Int): Boolean
}

/**
 * Follows thrown Yang Glyphs. A flying glyph is an armor stand wearing a beacon; when it vanishes the beacon
 * block appears at its last position. This tracker reports newly seen stands, resolves each vanished stand to a
 * landed beacon within [LANDING_WINDOW_TICKS], and keeps each beacon for [BEACON_TICKS] while its block exists.
 */
class YangGlyphTracker(private val probe: BlockProbe) {
    class Beacon(val x: Int, val y: Int, val z: Int, val startTick: Long)

    private class Pending(val x: Double, val y: Double, val z: Double, val vanishTick: Long)

    private val stands = HashMap<Int, DoubleArray>()
    private val pending = ArrayList<Pending>()
    private val landed = ArrayList<Beacon>()

    /** [current] maps beacon-stand entity ids to positions. Returns how many ids were not seen last call. */
    fun update(current: Map<Int, DoubleArray>, tick: Long): Int {
        var fresh = 0
        for ((id, pos) in current) {
            if (!stands.containsKey(id)) fresh++
            stands[id] = pos
        }
        val iterator = stands.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (current.containsKey(entry.key)) continue
            if (pending.size < MAX_PENDING) pending.add(Pending(entry.value[0], entry.value[1], entry.value[2], tick))
            iterator.remove()
        }
        val pendingIterator = pending.iterator()
        while (pendingIterator.hasNext()) {
            val glyph = pendingIterator.next()
            val found = findBeacon(glyph)
            if (found != null) {
                if (landed.size < MAX_BEACONS) landed.add(Beacon(found[0], found[1], found[2], tick))
                pendingIterator.remove()
            } else if (tick - glyph.vanishTick > LANDING_WINDOW_TICKS) {
                pendingIterator.remove()
            }
        }
        landed.removeAll { !probe.isBeacon(it.x, it.y, it.z) }
        return fresh
    }

    private fun findBeacon(glyph: Pending): IntArray? {
        val bx = Math.floor(glyph.x).toInt()
        val by = Math.floor(glyph.y).toInt()
        val bz = Math.floor(glyph.z).toInt()
        for (dx in -1..1) for (dz in -1..1) for (y in by - 2..by + 1) {
            val x = bx + dx
            val z = bz + dz
            if (landed.any { it.x == x && it.y == y && it.z == z }) continue
            if (probe.isBeacon(x, y, z)) return intArrayOf(x, y, z)
        }
        return null
    }

    /** Landed beacons that are younger than [BEACON_TICKS] at [tick]. */
    fun beacons(tick: Long): List<Beacon> = landed.filter { tick - it.startTick < BEACON_TICKS }

    fun pendingCount(): Int = pending.size

    fun reset() {
        stands.clear()
        pending.clear()
        landed.clear()
    }

    companion object {
        const val BEACON_TICKS = 100
        const val LANDING_WINDOW_TICKS = 40
        private const val MAX_PENDING = 16
        private const val MAX_BEACONS = 16
    }
}
