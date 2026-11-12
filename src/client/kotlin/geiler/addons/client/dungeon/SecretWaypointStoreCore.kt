package geiler.addons.client.dungeon

import geiler.addons.GeilerAddons
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

/** Persistence and runtime state for bundled and player-authored secret waypoints. */
object SecretWaypointStoreCore {
    private const val RESOURCE = "/data/geileraddons/secret-waypoints.json"
    private val maxWaypoints = ClientJsonFile.MAX_ENTRIES
    private var persistenceBlocked = false
    private val file: Path? = configPath()
    private var profileTransactionActive = false

    @kotlin.jvm.Volatile
    private var defaults: List<SecretWaypointStore.Waypoint> = java.util.List.of()

    @kotlin.jvm.Volatile
    private var roomTemplates: List<DevonianDungeonWaypointCatalog.Marker> = java.util.List.of()

    @kotlin.jvm.Volatile
    private var runtimeDefaults: List<SecretWaypointStore.Waypoint> = java.util.List.of()

    @kotlin.jvm.Volatile
    private var personal: List<SecretWaypointStore.Waypoint> = java.util.List.of()

    @kotlin.jvm.Volatile
    private var overrides: Map<String, SecretWaypointStore.WaypointOverride> = java.util.Map.of()

    @kotlin.jvm.Volatile
    private var collected: Set<CollectionKey> = java.util.Set.of()

    @kotlin.jvm.Volatile
    private var catalogStatus: String? = "Built-in waypoint catalog has not loaded"

    @kotlin.jvm.Volatile
    private var loaded = false

    fun load() {
        synchronized(SecretWaypointStore::class.java) {
            if (loaded) return
            loaded = true
            loadDefaults()
            loadRoomTemplates()
            val target = file
            if (persistenceBlocked || target == null) return
            val result = SecretWaypointLocalPersistenceLogic.read(target)
            if (!result.valid()) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error(
                    "Personal secret waypoint file is invalid; leaving it untouched: {}",
                    result.error,
                )
                return
            }
            if (!result.exists || result.value == null) return
            val localFile = result.value
            if (!validLocalFile(localFile)) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error(
                    "Personal secret waypoint file has an unsupported schema or invalid entries; leaving it untouched",
                )
                return
            }
            personal = sanitize(localFile.personal)
            val loadedOverrides = LinkedHashMap<String, SecretWaypointStore.WaypointOverride>()
            for (savedOverride in localFile.overrides) {
                if (!defaultIds().contains(savedOverride.id()) &&
                    !SecretWaypointRoomCatalog.isRoomInstanceMarkerId(savedOverride.id())
                ) continue
                val cleanOverride = sanitizeOverride(savedOverride)
                if (cleanOverride != null) loadedOverrides[cleanOverride.id()] = cleanOverride
            }
            overrides = java.util.Map.copyOf(loadedOverrides)
            val loadedCollected = LinkedHashSet<CollectionKey>()
            val fileCollected = localFile.collected
            if (fileCollected != null) {
                for (marker in fileCollected) loadedCollected.add(CollectionKey.of(marker))
            }
            collected = java.util.Set.copyOf(loadedCollected)
        }
    }

    /** Reloads the installed profile file transactionally; failed reads retain the current runtime cache. */
    fun reloadFromDisk(): Boolean {
        val target = file ?: return false
        val previous = snapshotRuntimeState()
        defaults = java.util.List.of()
        roomTemplates = java.util.List.of()
        runtimeDefaults = java.util.List.of()
        personal = java.util.List.of()
        overrides = java.util.Map.of()
        collected = java.util.Set.of()
        catalogStatus = "Built-in waypoint catalog has not loaded"
        persistenceBlocked = false
        loaded = false
        try {
            load()
        } catch (failure: RuntimeException) {
            restoreRuntimeState(previous)
            return false
        }
        if (persistenceBlocked || !loaded) {
            restoreRuntimeState(previous)
            return false
        }
        return true
    }

    fun snapshotRuntimeState(): SecretWaypointStore.RuntimeSnapshot {
        load()
        return SecretWaypointStore.RuntimeSnapshot(
            defaults,
            roomTemplates,
            runtimeDefaults,
            personal,
            overrides,
            collectionRecords(collected),
            catalogStatus,
            persistenceBlocked,
            loaded,
        )
    }

    /** Pauses local edits while a profile transaction replaces this file. */
    fun beginProfileTransaction() {
        profileTransactionActive = true
    }

    fun endProfileTransaction() {
        profileTransactionActive = false
    }

    /** Restores a pre-transaction cache when an installed profile could not be applied or recovered. */
    fun restoreRuntimeState(snapshot: SecretWaypointStore.RuntimeSnapshot?) {
        if (snapshot == null) return
        defaults = snapshot.defaults()
        roomTemplates = snapshot.roomTemplates()
        runtimeDefaults = snapshot.runtimeDefaults()
        personal = snapshot.personal()
        overrides = snapshot.overrides()
        val nextCollected = LinkedHashSet<CollectionKey>()
        for (marker in snapshot.collected()) nextCollected.add(CollectionKey.of(marker))
        collected = java.util.Set.copyOf(nextCollected)
        catalogStatus = snapshot.catalogStatus()
        persistenceBlocked = snapshot.persistenceBlocked()
        loaded = snapshot.loaded()
    }

    fun defaults(): List<SecretWaypointStore.Waypoint> {
        load()
        return allDefaultWaypoints()
    }

    fun personal(): List<SecretWaypointStore.Waypoint> {
        load()
        return personal
    }

    fun roomTemplates(): List<DevonianDungeonWaypointCatalog.Marker> {
        load()
        return roomTemplates
    }

    fun catalogStatus(): String? {
        load()
        return catalogStatus
    }

    fun canPersistChanges(): Boolean {
        load()
        return !persistenceBlocked && file != null && !profileTransactionActive
    }

    /** Replaces the bounded, current-room materialized catalog view; local saved edits remain authoritative. */
    fun setRuntimeDefaults(values: List<SecretWaypointStore.Waypoint>?) {
        load()
        if (values == null || values.size > maxWaypoints) {
            runtimeDefaults = java.util.List.of()
            return
        }
        val safe = LinkedHashMap<String, SecretWaypointStore.Waypoint>()
        for (value in values) {
            val clean = sanitizeWaypoint(value, true) ?: continue
            if (!SecretWaypointRoomCatalog.isRoomInstanceMarkerId(clean.id())) continue
            safe.putIfAbsent(clean.id(), clean)
            if (safe.size >= maxWaypoints) break
        }
        runtimeDefaults = java.util.List.copyOf(safe.values)
    }

    fun visibleFor(floor: String?, roomId: String?): List<SecretWaypointStore.Waypoint> {
        load()
        val visible = LinkedHashMap<String, SecretWaypointStore.Waypoint>()
        for (waypoint in allDefaultWaypoints()) {
            if (!matches(waypoint, floor, roomId)) continue
            val savedOverride = overrides[waypoint.id()]
            if (savedOverride == null) {
                visible[waypoint.id()] = waypoint
            } else if (!savedOverride.hidden() && effectiveReplacement(waypoint, savedOverride) != null) {
                visible[waypoint.id()] = effectiveReplacement(waypoint, savedOverride)!!
            } else if (!savedOverride.hidden()) {
                visible[waypoint.id()] = waypoint
            }
        }
        for (waypoint in personal) {
            if (matches(waypoint, floor, roomId)) visible[waypoint.id()] = waypoint
        }
        return java.util.List.copyOf(visible.values)
    }

    /** All room markers for editing, including hidden defaults and markers currently marked collected. */
    fun editableForRooms(floor: String?, roomIds: List<String>?): List<SecretWaypointStore.EditableWaypoint> {
        load()
        val parsedFloor = DungeonFloor.parse(floor)
        val rooms = safeRooms(roomIds)
        if (parsedFloor == null || rooms.isEmpty()) return java.util.List.of()
        val safeFloor = parsedFloor.displayName()
        val result = LinkedHashMap<String, SecretWaypointStore.EditableWaypoint>()
        for (waypoint in allDefaultWaypoints()) {
            if (!waypoint.floor().equals(safeFloor, ignoreCase = true) || !rooms.contains(waypoint.roomId())) continue
            val savedOverride = overrides[waypoint.id()]
            val effective = if (savedOverride == null) {
                waypoint
            } else {
                effectiveReplacement(waypoint, savedOverride) ?: waypoint
            }
            result[waypoint.id()] = SecretWaypointStore.EditableWaypoint(
                effective,
                false,
                savedOverride != null && savedOverride.hidden(),
                isCollected(safeFloor, waypoint.roomId(), waypoint.id()),
            )
        }
        for (waypoint in personal) {
            if (!waypoint.floor().equals(safeFloor, ignoreCase = true) || !rooms.contains(waypoint.roomId())) continue
            result[waypoint.id()] = SecretWaypointStore.EditableWaypoint(
                waypoint,
                true,
                false,
                isCollected(safeFloor, waypoint.roomId(), waypoint.id()),
            )
        }
        return java.util.List.copyOf(result.values)
    }

    fun addPersonal(
        floor: String?,
        roomId: String?,
        name: String?,
        x: Int,
        y: Int,
        z: Int,
        color: Int,
    ): String? {
        load()
        if (persistenceBlocked || file == null || personal.size >= maxWaypoints) return null
        val id = "personal:" + UUID.randomUUID()
        val waypoint = SecretWaypointStore.Waypoint(id, canonicalFloor(floor), roomId, clean(name, 64), x, y, z, color)
        if (!SecretWaypointPackageCodec.validWaypoint(waypoint, true)) return null
        val next = ArrayList(personal)
        next.add(waypoint)
        return if (commit(next, overrides, collected)) id else null
    }

    fun renamePersonal(id: String?, name: String?): Boolean {
        load()
        if (persistenceBlocked || file == null) return false
        val safeName = clean(name, 64)
        if (id == null || safeName.isEmpty()) return false
        val next = ArrayList(personal)
        for (index in next.indices) {
            val waypoint = next[index]
            if (waypoint.id() != id) continue
            next[index] = SecretWaypointStore.Waypoint(
                waypoint.id(),
                waypoint.floor(),
                waypoint.roomId(),
                safeName,
                waypoint.x(),
                waypoint.y(),
                waypoint.z(),
                waypoint.color(),
            )
            return commit(next, overrides, collected)
        }
        return false
    }

    fun updatePersonal(
        id: String?,
        floor: String?,
        roomId: String?,
        name: String?,
        x: Int,
        y: Int,
        z: Int,
        color: Int,
    ): Boolean {
        load()
        if (persistenceBlocked || file == null || id == null) return false
        val replacement = SecretWaypointStore.Waypoint(id, canonicalFloor(floor), roomId, clean(name, 64), x, y, z, color)
        if (!SecretWaypointPackageCodec.validWaypoint(replacement, true)) return false
        val next = ArrayList(personal)
        for (index in next.indices) {
            if (next[index].id() != id) continue
            next[index] = replacement
            return commit(next, overrides, collected)
        }
        return false
    }

    fun removePersonal(id: String?): Boolean {
        load()
        if (persistenceBlocked || file == null || id == null) return false
        val next = personal.filter { waypoint -> waypoint.id() != id }
        if (next.size == personal.size) return false
        val nextCollected = LinkedHashSet(collected)
        nextCollected.removeIf { key -> key.markerId == id }
        return commit(next, overrides, nextCollected)
    }

    fun hideDefault(id: String?): Boolean {
        load()
        if (persistenceBlocked || file == null || id == null || !defaultIds().contains(id)) return false
        val next = LinkedHashMap(overrides)
        next[id] = SecretWaypointStore.WaypointOverride(id, true, null)
        return commit(personal, next, collected)
    }

    fun replaceDefault(id: String?, replacement: SecretWaypointStore.Waypoint?): Boolean {
        load()
        if (persistenceBlocked || file == null || id == null || !defaultIds().contains(id) || replacement == null) return false
        val original = findDefault(id)
        if (original == null) return false
        val edited = SecretWaypointStore.Waypoint(
            id,
            original.floor(),
            original.roomId(),
            clean(replacement.name(), 64),
            replacement.x(),
            replacement.y(),
            replacement.z(),
            replacement.color(),
        )
        if (!SecretWaypointPackageCodec.validWaypoint(edited, true)) return false
        val next = LinkedHashMap(overrides)
        next[id] = SecretWaypointStore.WaypointOverride(id, false, edited)
        return commit(personal, next, collected)
    }

    fun restoreDefault(id: String?): Boolean {
        load()
        if (persistenceBlocked || file == null || id == null || !overrides.containsKey(id)) return false
        val next = LinkedHashMap(overrides)
        next.remove(id)
        return commit(personal, next, collected)
    }

    /** Restores only bundled defaults in the selected rooms; personal markers and collection history remain. */
    fun resetRoomOverrides(floor: String?, roomIds: List<String>?): Int {
        load()
        if (persistenceBlocked || file == null || DungeonFloor.parse(floor) == null) return 0
        val rooms = safeRooms(roomIds)
        if (rooms.isEmpty()) return 0
        val safeFloor = canonicalFloor(floor)
        val next = LinkedHashMap(overrides)
        val before = next.size
        next.entries.removeIf { entry ->
            val original = findDefault(entry.key)
            if (original != null && original.floor().equals(safeFloor, ignoreCase = true) &&
                rooms.contains(original.roomId())
            ) {
                true
            } else {
                val identity = SecretWaypointRoomCatalog.roomInstanceIdentity(entry.key)
                identity != null && identity.floor().displayName().equals(safeFloor, ignoreCase = true) &&
                    rooms.contains(identity.anchorRoomId())
            }
        }
        val restored = before - next.size
        if (restored > 0 && !commit(personal, next, collected)) return 0
        return restored
    }

    fun isCollected(floor: String?, roomId: String?, markerId: String?): Boolean {
        load()
        if (DungeonFloor.parse(floor) == null || !SecretWaypointPackageCodec.validRoomId(roomId) || markerId == null) {
            return false
        }
        val validRoomId = roomId ?: return false
        return collected.contains(CollectionKey(canonicalFloor(floor), validRoomId, markerId))
    }

    /** Records all authored markers in a room only after the map has confirmed its green clear state. */
    fun markRoomsCollected(floor: String?, roomIds: List<String>?): Int {
        load()
        if (persistenceBlocked || file == null || DungeonFloor.parse(floor) == null) return 0
        val safeFloor = canonicalFloor(floor)
        val rooms = safeRooms(roomIds)
        if (rooms.isEmpty()) return 0
        val next = LinkedHashSet(collected)
        for (waypoint in allDefaultWaypoints()) {
            if (waypoint.floor().equals(safeFloor, ignoreCase = true) && rooms.contains(waypoint.roomId())) {
                next.add(CollectionKey(safeFloor, waypoint.roomId(), waypoint.id()))
            }
        }
        for (waypoint in personal) {
            if (waypoint.floor().equals(safeFloor, ignoreCase = true) && rooms.contains(waypoint.roomId())) {
                next.add(CollectionKey(safeFloor, waypoint.roomId(), waypoint.id()))
            }
        }
        val added = next.size - collected.size
        if (added <= 0 || next.size > maxWaypoints) return 0
        return if (commit(personal, overrides, next)) added else 0
    }

    fun exportPackage(): String? {
        load()
        return SecretWaypointPackageCodec.encode(personal, java.util.List.copyOf(overrides.values), collectionRecords(collected))
    }

    /** Merges a validated package; colliding personal IDs are remapped without replacing local markers. */
    fun importPackage(encoded: String?): SecretWaypointStore.ImportResult {
        load()
        if (persistenceBlocked || file == null) return failure("Local waypoint persistence is unavailable.")
        val decoded = SecretWaypointPackageCodec.decode(encoded)
        if (!decoded.success()) return failure(decoded.error())
        val nextPersonal = ArrayList(personal)
        val personalIds = HashSet<String>()
        for (waypoint in personal) personalIds.add(waypoint.id())
        val remappedIds = LinkedHashMap<String, String>()
        for (waypoint in decoded.personal()) {
            if (nextPersonal.size >= maxWaypoints) {
                return failure("Waypoint limit reached; no package data was imported.")
            }
            var id = waypoint.id()
            if (personalIds.contains(id)) {
                var replacementId: String
                do {
                    replacementId = "personal:" + UUID.randomUUID()
                } while (personalIds.contains(replacementId))
                remappedIds[id] = replacementId
                id = replacementId
            }
            personalIds.add(id)
            nextPersonal.add(
                SecretWaypointStore.Waypoint(
                    id,
                    canonicalFloor(waypoint.floor()),
                    waypoint.roomId(),
                    waypoint.name(),
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    waypoint.color(),
                ),
            )
        }
        val nextOverrides = LinkedHashMap(overrides)
        var importedOverrides = 0
        for (savedOverride in decoded.overrides()) {
            if (!defaultIds().contains(savedOverride.id()) &&
                !SecretWaypointRoomCatalog.isRoomInstanceMarkerId(savedOverride.id())
            ) continue
            val cleanOverride = sanitizeOverride(savedOverride)
                ?: return failure("A built-in waypoint override is invalid; no package data was imported.")
            nextOverrides[cleanOverride.id()] = cleanOverride
            importedOverrides++
        }
        val nextCollected = LinkedHashSet(collected)
        var importedCollected = 0
        for (marker in decoded.collected()) {
            val markerId: String
            if (marker.markerId().startsWith("personal:")) {
                if (decoded.personal().none { waypoint -> waypoint.id() == marker.markerId() }) {
                    return failure("Collection state references a missing personal marker; no package data was imported.")
                }
                markerId = remappedIds[marker.markerId()] ?: marker.markerId()
            } else {
                val defaultMarker = findDefault(marker.markerId())
                if (defaultMarker != null) {
                    if (!defaultMarker.floor().equals(canonicalFloor(marker.floor()), ignoreCase = true) ||
                        defaultMarker.roomId() != marker.roomId()
                    ) continue
                } else {
                    val identity = SecretWaypointRoomCatalog.roomInstanceIdentity(marker.markerId())
                    if (identity == null ||
                        !identity.floor().displayName().equals(canonicalFloor(marker.floor()), ignoreCase = true)
                    ) continue
                }
                markerId = marker.markerId()
            }
            val key = CollectionKey(canonicalFloor(marker.floor()), marker.roomId(), markerId)
            if (nextCollected.add(key)) importedCollected++
        }
        if (nextCollected.size > maxWaypoints) {
            return failure("Waypoint collection-state limit reached; no package data was imported.")
        }
        if (!commit(nextPersonal, nextOverrides, nextCollected)) {
            return failure("Waypoint data exceeds the local save limit.")
        }
        return SecretWaypointStore.ImportResult(true, decoded.personal().size, importedOverrides, importedCollected, null)
    }

    private fun loadDefaults() {
        try {
            ClientJsonFile.openBundledResource(SecretWaypointStore::class.java, RESOURCE).use { stream ->
                val bytes = stream.readNBytes(ClientJsonFile.MAX_PACKAGE_BYTES + 1)
                if (bytes.size > ClientJsonFile.MAX_PACKAGE_BYTES) throw IOException("bundled catalog is oversized")
                val decoded = SecretWaypointCatalogCodec.decode(String(bytes, StandardCharsets.UTF_8))
                if (!decoded.success()) throw IOException(decoded.error())
                defaults = decoded.waypoints()
                catalogStatus = if (defaults.isEmpty()) {
                    "Built-in waypoint catalog contains no verified entries"
                } else {
                    "Loaded " + defaults.size + " built-in waypoint(s)"
                }
            }
        } catch (error: IOException) {
            catalogUnavailable(error)
        } catch (error: RuntimeException) {
            catalogUnavailable(error)
        }
    }

    private fun catalogUnavailable(error: Exception) {
        defaults = java.util.List.of()
        catalogStatus = "Built-in waypoint catalog unavailable: " + clean(error.message, 120)
    }

    private fun loadRoomTemplates() {
        val catalog = DevonianDungeonWaypointCatalog.bundled()
        if (catalog.available()) {
            roomTemplates = catalog.markers()
            catalogStatus = "Loaded " + defaults.size + " fixed and " + roomTemplates.size +
                " Devonian room marker(s) across " + catalog.roomCount() + " named room(s)"
        } else {
            roomTemplates = java.util.List.of()
            catalogStatus = "Devonian room waypoint catalog unavailable: " + clean(catalog.reason(), 120)
            GeilerAddons.LOGGER.error("Bundled Devonian room waypoint catalog is unavailable: {}", catalog.reason())
        }
    }

    private fun allDefaultWaypoints(): List<SecretWaypointStore.Waypoint> {
        if (runtimeDefaults.isEmpty()) return defaults
        val all = ArrayList<SecretWaypointStore.Waypoint>(defaults.size + runtimeDefaults.size)
        all.addAll(defaults)
        all.addAll(runtimeDefaults)
        return java.util.List.copyOf(all)
    }

    private fun validLocalFile(localFile: SecretWaypointStore.LocalFile): Boolean {
        if ((localFile.version != 1 && localFile.version != 2) ||
            localFile.personal == null ||
            localFile.overrides == null ||
            (localFile.version == 2 && localFile.collected == null) ||
            localFile.personal.size > maxWaypoints ||
            localFile.overrides.size > maxWaypoints ||
            (localFile.collected != null && localFile.collected.size > maxWaypoints)
        ) return false

        val ids = HashSet<String>()
        for (waypoint in localFile.personal) {
            if (!SecretWaypointPackageCodec.validWaypoint(waypoint, false) ||
                !waypoint.id().startsWith("personal:") ||
                !ids.add(waypoint.id())
            ) return false
        }
        ids.clear()
        for (savedOverride in localFile.overrides) {
            if (savedOverride == null ||
                !boundedText(savedOverride.id(), 128) ||
                !ids.add(savedOverride.id()) ||
                (!defaultIds().contains(savedOverride.id()) &&
                    !SecretWaypointRoomCatalog.isRoomInstanceMarkerId(savedOverride.id()))
            ) return false
            val replacement = savedOverride.replacement()
            if (savedOverride.hidden()) {
                if (replacement != null) return false
            } else if (replacement != null) {
                if (!SecretWaypointPackageCodec.validWaypoint(replacement, true) ||
                    savedOverride.id() != replacement.id()
                ) return false
                val original = findDefault(savedOverride.id())
                if (original != null &&
                    (!sameFloor(replacement.floor(), original.floor()) ||
                        replacement.roomId() != original.roomId() ||
                        !SecretWaypointPackageCodec.matchesRoom(original.roomId(), replacement.x(), replacement.z()))
                ) return false
                if (original == null && SecretWaypointRoomCatalog.isRoomInstanceMarkerId(savedOverride.id())) {
                    val identity = SecretWaypointRoomCatalog.roomInstanceIdentity(savedOverride.id())
                    if (identity == null || !sameFloor(replacement.floor(), identity.floor().displayName())) return false
                }
            } else if (localFile.version >= 2) {
                return false
            }
        }
        ids.clear()
        val localCollected = localFile.collected
        if (localCollected != null) {
            for (marker in localCollected) {
                if (!validCollected(marker) || !ids.add(CollectionKey.of(marker).storageKey())) return false
                if (marker.markerId().startsWith("personal:")) {
                    val personalWaypoint = localFile.personal.firstOrNull { waypoint -> waypoint.id() == marker.markerId() }
                    if (personalWaypoint == null ||
                        !sameFloor(marker.floor(), personalWaypoint.floor()) ||
                        marker.roomId() != personalWaypoint.roomId()
                    ) return false
                } else {
                    val defaultWaypoint = findDefault(marker.markerId())
                    if (defaultWaypoint != null &&
                        (!sameFloor(marker.floor(), defaultWaypoint.floor()) ||
                            marker.roomId() != defaultWaypoint.roomId())
                    ) return false
                    if (defaultWaypoint == null &&
                        SecretWaypointRoomCatalog.isRoomInstanceMarkerId(marker.markerId())
                    ) {
                        val identity = SecretWaypointRoomCatalog.roomInstanceIdentity(marker.markerId())
                        if (identity == null || !sameFloor(marker.floor(), identity.floor().displayName())) return false
                    }
                }
            }
        }
        return true
    }

    private fun validCollected(marker: SecretWaypointStore.CollectedWaypoint?): Boolean {
        return marker != null &&
            DungeonFloor.parse(marker.floor()) != null &&
            SecretWaypointPackageCodec.validRoomId(marker.roomId()) &&
            boundedText(marker.markerId(), 128)
    }

    private fun sanitize(values: List<SecretWaypointStore.Waypoint>?): List<SecretWaypointStore.Waypoint> {
        if (values == null) return java.util.List.of()
        val unique = LinkedHashMap<String, SecretWaypointStore.Waypoint>()
        for (waypoint in values) {
            if (waypoint == null) continue
            val clean = sanitizeWaypoint(waypoint, false)
            if (clean != null) unique.putIfAbsent(clean.id(), clean)
            if (unique.size >= maxWaypoints) break
        }
        return java.util.List.copyOf(unique.values)
    }

    private fun sanitizeWaypoint(
        waypoint: SecretWaypointStore.Waypoint?,
        requireRoomCoordinates: Boolean,
    ): SecretWaypointStore.Waypoint? {
        if (waypoint == null) return null
        val id = clean(waypoint.id(), 128)
        val floor = canonicalFloor(waypoint.floor())
        val room = clean(waypoint.roomId(), 96)
        val name = clean(waypoint.name(), 64)
        val sanitized = SecretWaypointStore.Waypoint(
            id,
            floor,
            room,
            name,
            waypoint.x(),
            waypoint.y(),
            waypoint.z(),
            waypoint.color(),
        )
        return if (SecretWaypointPackageCodec.validWaypoint(sanitized, requireRoomCoordinates)) sanitized else null
    }

    private fun sanitizeOverride(value: SecretWaypointStore.WaypointOverride?): SecretWaypointStore.WaypointOverride? {
        if (value == null) return null
        val id = clean(value.id(), 128)
        val original = findDefault(id)
        val dynamicIdentity = SecretWaypointRoomCatalog.roomInstanceIdentity(id)
        if (id.isEmpty() || (original == null && dynamicIdentity == null)) return null
        var replacement: SecretWaypointStore.Waypoint? = null
        val supplied = value.replacement()
        if (supplied != null) {
            val name = clean(supplied.name(), 64)
            if (name.isNotEmpty() && SecretWaypointPackageCodec.validWaypoint(supplied, true)) {
                if (original != null) {
                    replacement = SecretWaypointStore.Waypoint(
                        id,
                        original.floor(),
                        original.roomId(),
                        name,
                        supplied.x(),
                        supplied.y(),
                        supplied.z(),
                        supplied.color(),
                    )
                } else if (dynamicIdentity != null &&
                    sameFloor(supplied.floor(), dynamicIdentity.floor().displayName())
                ) {
                    replacement = SecretWaypointStore.Waypoint(
                        id,
                        dynamicIdentity.floor().displayName(),
                        supplied.roomId(),
                        name,
                        supplied.x(),
                        supplied.y(),
                        supplied.z(),
                        supplied.color(),
                    )
                }
            }
        }
        return if (value.hidden() || replacement != null) {
            SecretWaypointStore.WaypointOverride(id, value.hidden(), replacement)
        } else {
            null
        }
    }

    private fun matches(
        waypoint: SecretWaypointStore.Waypoint?,
        floor: String?,
        roomId: String?,
    ): Boolean {
        return waypoint != null &&
            waypoint.floor().equals(floor ?: "", ignoreCase = true) &&
            waypoint.roomId().equals(roomId ?: "", ignoreCase = true)
    }

    private fun findDefault(id: String?): SecretWaypointStore.Waypoint? {
        if (id == null) return null
        return allDefaultWaypoints().firstOrNull { waypoint -> waypoint.id() == id }
    }

    private fun defaultIds(): Set<String> = allDefaultWaypoints().map { waypoint -> waypoint.id() }.toSet()

    private fun effectiveReplacement(
        original: SecretWaypointStore.Waypoint,
        savedOverride: SecretWaypointStore.WaypointOverride?,
    ): SecretWaypointStore.Waypoint? {
        val replacement = savedOverride?.replacement()
        return if (replacement != null &&
            sameFloor(replacement.floor(), original.floor()) &&
            replacement.roomId() == original.roomId()
        ) replacement else null
    }

    private fun collectionRecords(values: Set<CollectionKey>): List<SecretWaypointStore.CollectedWaypoint> {
        return java.util.List.copyOf(
            values.map { key -> SecretWaypointStore.CollectedWaypoint(key.floor, key.roomId, key.markerId) },
        )
    }

    private fun commit(
        nextPersonal: List<SecretWaypointStore.Waypoint>,
        nextOverrides: Map<String, SecretWaypointStore.WaypointOverride>,
        nextCollected: Set<CollectionKey>,
    ): Boolean {
        val target = file
        if (profileTransactionActive ||
            persistenceBlocked ||
            target == null ||
            nextPersonal.size > maxWaypoints ||
            nextOverrides.size > maxWaypoints ||
            nextCollected.size > maxWaypoints
        ) return false
        val localFile = SecretWaypointStore.LocalFile()
        localFile.personal = ArrayList(nextPersonal)
        localFile.overrides = ArrayList(nextOverrides.values)
        localFile.collected = ArrayList(collectionRecords(nextCollected))
        val json = SecretWaypointLocalPersistenceLogic.encode(localFile) ?: return false
        personal = java.util.List.copyOf(nextPersonal)
        overrides = java.util.Map.copyOf(nextOverrides)
        collected = java.util.Set.copyOf(nextCollected)
        SecretWaypointLocalPersistenceLogic.writeEncoded(target, json)
        return true
    }

    private fun safeRooms(roomIds: List<String>?): Set<String> {
        if (roomIds == null || roomIds.isEmpty()) return java.util.Set.of()
        val rooms = LinkedHashSet<String>()
        for (roomId in roomIds) {
            if (SecretWaypointPackageCodec.validRoomId(roomId)) rooms.add(roomId)
        }
        return java.util.Set.copyOf(rooms)
    }

    private fun sameFloor(left: String?, right: String?): Boolean {
        return DungeonFloor.parse(left) != null && DungeonFloor.parse(left) == DungeonFloor.parse(right)
    }

    private fun canonicalFloor(floor: String?): String {
        val parsed = DungeonFloor.parse(floor)
        return parsed?.displayName() ?: clean(floor, 32)
    }

    private fun clean(value: String?, maximum: Int): String {
        if (value == null) return ""
        val trimmed = value.trim { character -> character <= ' ' }
        return if (trimmed.length > maximum) trimmed.substring(0, maximum) else trimmed
    }

    private fun boundedText(value: String?, maximum: Int): Boolean {
        return value != null &&
            !javaBlank(value) &&
            value.length <= maximum &&
            value == value.trim { character -> character <= ' ' }
    }

    private fun javaBlank(value: String): Boolean {
        return value.isEmpty() || value.all { character -> java.lang.Character.isWhitespace(character) }
    }

    private fun configPath(): Path? {
        return try {
            net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("geileraddons/secret-waypoints.json")
        } catch (unavailable: RuntimeException) {
            persistenceBlocked = true
            null
        }
    }

    private fun failure(error: String?): SecretWaypointStore.ImportResult {
        return SecretWaypointStore.ImportResult(false, 0, 0, 0, error)
    }

    private data class CollectionKey(val floor: String, val roomId: String, val markerId: String) {
        fun storageKey(): String = floor + "|" + roomId + "|" + markerId

        companion object {
            fun of(marker: SecretWaypointStore.CollectedWaypoint): CollectionKey {
                return CollectionKey(canonicalFloor(marker.floor()), marker.roomId(), marker.markerId())
            }
        }
    }
}
