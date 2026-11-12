package geiler.addons.client.dungeon

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import geiler.addons.GeilerAddons
import geiler.addons.client.chat.ChatStringSemantics
import net.fabricmc.loader.api.FabricLoader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Comparator
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.function.IntConsumer
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** One route file per floor, phase, and route name, with a small manifest for discovery. */
class DungeonGuideStore private constructor() {
    companion object {
        private const val DEFAULT_NEXT_KEY = "key.keyboard.right.bracket"
        private const val DEFAULT_PREVIOUS_KEY = "key.keyboard.left.bracket"
        private const val TRANSACTION_JOURNAL_NAME = ".pending-transaction.json"
        private const val TRANSACTION_DIRECTORY_NAME = ".transactions"
        private val MAX_TRANSACTION_ENTRIES = ClientJsonFile.MAX_ENTRIES + 1
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        private val configDirectory: Path? = try {
            FabricLoader.getInstance().configDir
        } catch (_: RuntimeException) {
            null
        }
        private val ROOT: Path = configDirectory?.resolve("geileraddons/dungeon-guides")
            ?: Path.of(".", ".geileraddons-offline", "dungeon-guides")
        private val INDEX: Path = ROOT.resolve("index.json")
        private val LEGACY: Path = configDirectory?.resolve("geileraddons/dungeon-guides.json")
            ?: Path.of(".", ".geileraddons-offline", "dungeon-guides.json")

        private val SAVE_EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "GeilerAddons dungeon guide writer").apply { isDaemon = true }
        }
        private val NODES = ArrayList<DungeonGuideNode>()
        private var nodesSnapshot: List<DungeonGuideNode>? = null
        private val ACTIVE_ROUTES = LinkedHashMap<String, String>()
        private val ROUTE_NAMES = LinkedHashMap<String, String>()
        private val ROUTE_TIMINGS = LinkedHashMap<String, TransitionTiming>()
        private val CUSTOM_SEGMENTS = ArrayList<DungeonGuideCustomSegment>()
        private var loaded = false
        private var dirty = false
        private var persistenceBlocked = configDirectory == null
        private var saveDelayTicks = 0
        private var mutationVersion = 0L
        private var guideRevision = 0L
        private var pendingVersion = 0L
        private var pendingSave: Future<SaveResult>? = null
        private var nextKey = DEFAULT_NEXT_KEY
        private var previousKey = DEFAULT_PREVIOUS_KEY

        @JvmStatic
        fun load() {
            if (loaded) return
            loaded = true
            if (persistenceBlocked) return
            val recovery = recoverPendingTransaction(ROOT)
            if (!recovery.success) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error(
                    "Dungeon Guide save recovery failed; route files are left untouched and persistence is blocked: {}",
                    recovery.detail,
                )
                return
            }
            val indexResult = ClientJsonFile.readResult(INDEX, Index::class.java)
            if (indexResult.exists) {
                if (!indexResult.valid()) {
                    persistenceBlocked = true
                    GeilerAddons.LOGGER.error("Dungeon Guide route index is invalid; leaving the files untouched")
                    return
                }
                val loadedIndex = indexResult.value
                if (loadedIndex == null) {
                    persistenceBlocked = true
                    GeilerAddons.LOGGER.error("Dungeon Guide route index is invalid; leaving the files untouched")
                    return
                }
                val indexRoutes = loadedIndex.routes
                if ((loadedIndex.version != 1 && loadedIndex.version != 2) ||
                    indexRoutes == null || indexRoutes.size > ClientJsonFile.MAX_ENTRIES
                ) {
                    persistenceBlocked = true
                    GeilerAddons.LOGGER.error("Dungeon Guide route index is invalid; leaving the files untouched")
                    return
                }
                val loadedVersion = loadedIndex.version
                nextKey = validKey(loadedIndex.nextKey, nextKey)
                previousKey = validKey(loadedIndex.previousKey, previousKey)
                loadedIndex.activeRoutes?.let(ACTIVE_ROUTES::putAll)
                loadedIndex.routeNames?.let(ROUTE_NAMES::putAll)
                loadedIndex.customSegments?.forEach { segment ->
                    if (segment == null) {
                        persistenceBlocked = true
                        return@forEach
                    }
                    segment.sanitize()
                    if (CUSTOM_SEGMENTS.any { it.id == segment.id }) {
                        persistenceBlocked = true
                        return@forEach
                    }
                    CUSTOM_SEGMENTS.add(segment)
                }
                val ids = HashSet<String>()
                for (candidate in indexRoutes) {
                    if (!validEntry(candidate)) {
                        persistenceBlocked = true
                        continue
                    }
                    val entry = candidate!!
                    ROUTE_TIMINGS[routeIdentity(entry.floor, entry.phase, entry.name)] =
                        TransitionTiming.parse(entry.timing)
                    val path = ROOT.resolve(entry.file!!).normalize()
                    if (!path.startsWith(ROOT.normalize())) {
                        persistenceBlocked = true
                        continue
                    }
                    val routeResult = ClientJsonFile.readResult(path, RouteFile::class.java)
                    if (!routeResult.exists || !routeResult.valid()) {
                        persistenceBlocked = true
                        continue
                    }
                    val file = routeResult.value
                    val routeNodes = file?.nodes
                    if (file == null || file.version != 1 || routeNodes == null ||
                        routeNodes.size > ClientJsonFile.MAX_ENTRIES
                    ) {
                        persistenceBlocked = true
                        continue
                    }
                    for (node in routeNodes) {
                        if (node == null) {
                            persistenceBlocked = true
                            continue
                        }
                        node.sanitize()
                        if (node.floor != entry.floor || node.phase != entry.phase ||
                            node.routeName != entry.name || !ids.add(node.id)
                        ) {
                            persistenceBlocked = true
                            continue
                        }
                        NODES.add(node)
                    }
                }
                if (persistenceBlocked) {
                    GeilerAddons.LOGGER.error("One or more Dungeon Guide route files could not be read; saves are paused to protect them")
                }
                if (!persistenceBlocked && loadedVersion == 2) {
                    for (node in NODES) {
                        val selected = ROUTE_NAMES.putIfAbsent(node.floor, node.routeName)
                        if (selected != null && selected != node.routeName) persistenceBlocked = true
                    }
                    if (persistenceBlocked) {
                        GeilerAddons.LOGGER.error("Dungeon Guide v2 contains more than one route name for a floor; saves are paused to protect it")
                    }
                }
                if (!persistenceBlocked && loadedVersion == 1) {
                    if (!archiveLegacyFiles(loadedIndex)) {
                        persistenceBlocked = true
                        GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide routes; migration is paused to protect them")
                        return
                    }
                    migrateLegacyRoutes(loadedIndex)
                }
                return
            }

            val legacyResult = ClientJsonFile.readResult(LEGACY, LegacyCatalog::class.java)
            if (legacyResult.exists && !legacyResult.valid()) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error("Legacy Dungeon Guide catalog is invalid; leaving it untouched")
                return
            }
            val legacy = legacyResult.value
            if (legacy == null || legacy.version != 1) return
            if (!archiveLegacyCatalog()) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error("Could not archive the legacy Dungeon Guide catalog; migration is paused to protect it")
                return
            }
            val legacyNodes = legacy.nodes
            if (legacyNodes != null && legacyNodes.size <= ClientJsonFile.MAX_ENTRIES) {
                val ids = HashSet<String>()
                for (node in legacyNodes) {
                    if (node == null) continue
                    node.sanitize()
                    if (ids.add(node.id)) {
                        NODES.add(node)
                        ROUTE_TIMINGS.putIfAbsent(
                            routeIdentity(node.floor, node.phase, node.routeName),
                            TransitionTiming.NEXT_OBJECTIVE,
                        )
                    }
                }
            }
            nextKey = validKey(legacy.nextKey, nextKey)
            previousKey = validKey(legacy.previousKey, previousKey)
            normalizeRouteNames()
            dirty = true
            mutationVersion++
            saveDelayTicks = 0
        }

        @JvmStatic
        fun all(): List<DungeonGuideNode> {
            load()
            if (nodesSnapshot == null) nodesSnapshot = java.util.List.copyOf(NODES)
            return nodesSnapshot!!
        }

        /** Changes only when route data or phase ordering can affect a published guide snapshot. */
        @JvmStatic fun guideRevision(): Long { load(); return guideRevision }
        @JvmStatic fun at(index: Int): DungeonGuideNode? { load(); return if (index < 0 || index >= NODES.size) null else NODES[index] }

        @JvmStatic
        fun add(node: DungeonGuideNode?) {
            load()
            if (node == null || NODES.size >= ClientJsonFile.MAX_ENTRIES) return
            node.sanitize()
            node.routeName = routeName(node.floor)
            node.order = (NODES.filter { it.floor == node.floor && it.phase == node.phase }.maxOfOrNull { it.order } ?: -1) + 1
            NODES.add(node)
            ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE)
            markGuideDataChanged()
            markDirty()
        }

        @JvmStatic
        fun remove(id: String?) {
            load()
            if (NODES.removeIf { it.id == id }) {
                markGuideDataChanged()
                markDirty()
            }
        }

        @JvmStatic
        fun changed() {
            load()
            NODES.forEach(DungeonGuideNode::sanitize)
            for (node in NODES) {
                node.routeName = routeName(node.floor)
                ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE)
            }
            markGuideDataChanged()
            markDirty()
        }

        @JvmStatic fun nextKey(): String { load(); return nextKey }
        @JvmStatic fun previousKey(): String { load(); return previousKey }
        @JvmStatic fun setNextKey(key: String?) { load(); nextKey = validKey(key, nextKey); markDirty() }
        @JvmStatic fun setPreviousKey(key: String?) { load(); previousKey = validKey(key, previousKey); markDirty() }

        @JvmStatic
        fun routes(): List<Route> {
            load()
            return java.util.List.copyOf(
                NODES.map { Route(it.floor, it.phase, routeName(it.floor)) }.distinct()
                    .sortedWith(compareBy<Route> { it.floor }.thenBy { phaseOrder(it.floor, it.phase) }.thenBy { it.phase }),
            )
        }

        @JvmStatic
        fun routeName(floor: String?): String {
            load()
            return floor?.let { ROUTE_NAMES.getOrDefault(it, "Dungeon Route") } ?: "Dungeon Route"
        }

        @JvmStatic
        fun setRouteName(floor: String?, name: String?): Boolean {
            load()
            if (DungeonFloor.parse(floor) == null || name == null) return false
            val cleaned = ChatStringSemantics.trim(name.replace(Regex("[\\p{Cntrl}]"), ""))
            if (ChatStringSemantics.isBlank(cleaned) || cleaned.length > 48) return false
            val old = routeName(floor)
            if (old == cleaned) return true
            ROUTE_NAMES[floor!!] = cleaned
            for (node in NODES) if (node.floor == floor) node.routeName = cleaned
            val migratedTimings = LinkedHashMap<String, TransitionTiming>()
            for ((key, timing) in ROUTE_TIMINGS) {
                val route = parseRouteIdentity(key)
                if (route == null || route.floor != floor) migratedTimings[key] = timing
                else migratedTimings[routeIdentity(floor, route.phase, cleaned)] = timing
            }
            ROUTE_TIMINGS.clear()
            ROUTE_TIMINGS.putAll(migratedTimings)
            markGuideDataChanged()
            markDirty()
            return true
        }

        @JvmStatic
        fun phaseOrder(floorName: String?, phase: String?): Int {
            val floor = DungeonFloor.parse(floorName)
            if (floor == null || phase == null) return Int.MAX_VALUE
            val builtIn = DungeonGuideSegments.builtIn(floor)
            var order = 0
            for (i in builtIn.indices) {
                if (builtIn[i].id == phase) return order
                order++
                for (custom in CUSTOM_SEGMENTS) {
                    if (custom.floor != floor.displayName() || custom.afterBuiltInIndex != i) continue
                    if (custom.id == phase) return order
                    order++
                }
            }
            return Int.MAX_VALUE
        }

        @JvmStatic
        fun customSegments(floor: DungeonFloor?): List<DungeonGuideCustomSegment> {
            load()
            return java.util.List.copyOf(CUSTOM_SEGMENTS.filter { floor != null && it.floor == floor.displayName() })
        }

        @JvmStatic
        fun addCustomSegment(segment: DungeonGuideCustomSegment?): Boolean {
            load()
            if (segment == null || CUSTOM_SEGMENTS.size >= ClientJsonFile.MAX_ENTRIES) return false
            segment.sanitize()
            if (CUSTOM_SEGMENTS.any { it.id == segment.id }) return false
            CUSTOM_SEGMENTS.add(segment)
            markGuideDataChanged()
            markDirty()
            return true
        }

        @JvmStatic
        fun customSegmentChanged() {
            load()
            CUSTOM_SEGMENTS.forEach(DungeonGuideCustomSegment::sanitize)
            markGuideDataChanged()
            markDirty()
        }

        @JvmStatic
        fun moveCustomSegment(id: String?, direction: Int): Boolean {
            load()
            if (id == null || direction == 0) return false
            for (i in CUSTOM_SEGMENTS.indices) {
                val segment = CUSTOM_SEGMENTS[i]
                if (id != segment.id) continue
                val neighbor = i + Integer.signum(direction)
                if (neighbor >= 0 && neighbor < CUSTOM_SEGMENTS.size) {
                    val other = CUSTOM_SEGMENTS[neighbor]
                    if (other.floor == segment.floor && other.afterBuiltInIndex == segment.afterBuiltInIndex) {
                        java.util.Collections.swap(CUSTOM_SEGMENTS, i, neighbor)
                        markGuideDataChanged()
                        markDirty()
                        return true
                    }
                }
                val max = DungeonGuideSegments.builtIn(DungeonFloor.parse(segment.floor)).size - 1
                val next = (segment.afterBuiltInIndex + Integer.signum(direction)).coerceIn(0, max)
                if (next == segment.afterBuiltInIndex) return false
                segment.afterBuiltInIndex = next
                markGuideDataChanged()
                markDirty()
                return true
            }
            return false
        }

        @JvmStatic
        fun removeCustomSegment(id: String?): Boolean {
            load()
            if (id == null || CUSTOM_SEGMENTS.none { it.id == id }) return false
            if (NODES.any { it.phase == id }) return false
            val removed = CUSTOM_SEGMENTS.removeIf { it.id == id }
            if (removed) { markGuideDataChanged(); markDirty() }
            return removed
        }

        @JvmStatic
        fun createRoute(floor: String?, phase: String?, name: String?, timing: TransitionTiming?): Boolean {
            load()
            if (DungeonFloor.parse(floor) == null || phase == null || !Regex("[A-Z][A-Z0-9_]{0,31}").matches(phase) ||
                name == null || ChatStringSemantics.isBlank(name) || name.length > 48 || timing == null
            ) return false
            if (routeName(floor) != "Dungeon Route") return false
            val cleaned = ChatStringSemantics.trim(name)
            ROUTE_NAMES[floor!!] = cleaned
            ROUTE_TIMINGS[routeIdentity(floor, phase, cleaned)] = timing
            markGuideDataChanged()
            markDirty()
            return true
        }

        @JvmStatic
        fun routeTiming(floor: String?, phase: String?, name: String?): TransitionTiming {
            load()
            return ROUTE_TIMINGS.getOrDefault(routeIdentity(floor, phase, name), TransitionTiming.NEXT_OBJECTIVE)
        }

        @JvmStatic
        fun setRouteTiming(floor: String?, phase: String?, name: String?, timing: TransitionTiming?): Boolean {
            load()
            val key = routeIdentity(floor, phase, name)
            if (!ROUTE_TIMINGS.containsKey(key) || timing == null) return false
            ROUTE_TIMINGS[key] = timing
            markDirty()
            return true
        }

        @JvmStatic fun activeRoute(floor: String?, phase: String?): String = routeName(floor)
        @JvmStatic fun setActiveRoute(floor: String?, phase: String?, name: String?): Boolean = setRouteName(floor, name)

        /** Migrates absent per-ring fields from the former module-wide settings after config load. */
        @JvmStatic
        fun migrateLegacyRingAppearance(count: Int, height: Float, radius: Float, speed: Float, width: Float): Boolean {
            load()
            if (persistenceBlocked) return false
            var changed = false
            for (node in NODES) changed = node.migrateMissingRingAppearance(count, height, radius, speed, width) || changed
            if (changed) { markGuideDataChanged(); markDirty() }
            return changed
        }

        @JvmStatic
        fun tick() {
            resolvePending()
            if (!dirty || persistenceBlocked || pendingSave != null) return
            if (--saveDelayTicks <= 0) save()
        }

        @JvmStatic fun flush() { flushAndReport() }

        /** Waits for the current route snapshot and reports any persistence failure to config tools. */
        @JvmStatic
        fun flushAndReport(): Boolean {
            load()
            if (persistenceBlocked) return false
            repeat(2) {
                if (!resolvePending(true) || persistenceBlocked) return false
                if (!dirty) return true
                if (pendingSave != null) return@repeat
                save()
            }
            return resolvePending(true) && !dirty && !persistenceBlocked
        }

        /** Starts any needed writer using a client-thread snapshot; callers may await it off-thread. */
        @JvmStatic
        fun prepareFlush() {
            load()
            resolvePending()
            if (!persistenceBlocked && dirty && pendingSave == null) save()
        }

        /** Clears routes and route-level settings after the user confirms a factory reset. */
        @JvmStatic
        fun resetToFactoryDefaults(): Boolean {
            load()
            if (persistenceBlocked || pendingSave != null) return false
            NODES.clear()
            ACTIVE_ROUTES.clear()
            ROUTE_NAMES.clear()
            ROUTE_TIMINGS.clear()
            CUSTOM_SEGMENTS.clear()
            nextKey = DEFAULT_NEXT_KEY
            previousKey = DEFAULT_PREVIOUS_KEY
            markGuideDataChanged()
            markDirty()
            saveDelayTicks = 0
            return true
        }

        /** Replaces the live route cache with the already-validated profile now installed on disk. */
        @JvmStatic
        fun reloadFromDisk(): Boolean {
            // The config transfer flow drains this store before installing the new files. Flushing here
            // would serialize the old in-memory routes over the imported profile.
            if (pendingSave != null || dirty) return false
            NODES.clear()
            ACTIVE_ROUTES.clear()
            ROUTE_NAMES.clear()
            ROUTE_TIMINGS.clear()
            CUSTOM_SEGMENTS.clear()
            nodesSnapshot = null
            loaded = false
            persistenceBlocked = false
            dirty = false
            pendingSave = null
            nextKey = DEFAULT_NEXT_KEY
            previousKey = DEFAULT_PREVIOUS_KEY
            load()
            markGuideDataChanged()
            return !persistenceBlocked
        }

        @JvmStatic fun exportPackage(): String? { load(); return DungeonPackageCodec.encodeGuides(NODES) }

        @JvmStatic
        fun importPackage(encoded: String?): ImportResult {
            load()
            val decoded = DungeonPackageCodec.decodeGuides(encoded)
            if (!decoded.success) return ImportResult(false, 0, decoded.error)
            val entries = decoded.entries
                ?: return ImportResult(false, 0, "Package data is invalid.")
            val ids = HashSet<String>()
            NODES.forEach { ids.add(it.id) }
            var imported = 0
            for (node in entries) {
                if (node == null || NODES.size >= ClientJsonFile.MAX_ENTRIES) continue
                node.sanitize()
                if (!ids.add(node.id)) node.id = UUID.randomUUID().toString()
                node.routeName = routeName(node.floor)
                NODES.add(node)
                ROUTE_TIMINGS.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE)
                imported++
            }
            if (imported > 0) { markGuideDataChanged(); markDirty() }
            return ImportResult(true, imported, null)
        }

        private fun save() {
            if (persistenceBlocked) return
            val version = mutationVersion
            val grouped = LinkedHashMap<Route, MutableList<DungeonGuideNode>>()
            NODES.sortedWith(
                compareBy<DungeonGuideNode> { it.floor }
                    .thenBy { it.phase }
                    .thenComparator { left, right -> String.CASE_INSENSITIVE_ORDER.compare(left.routeName, right.routeName) }
                    .thenBy { it.order },
            ).forEach { node ->
                grouped.getOrPut(Route(node.floor, node.phase, node.routeName)) { ArrayList() }.add(node)
            }
            for (route in routes()) grouped.getOrPut(route) { ArrayList() }
            val writes = ArrayList<ClientJsonFile.Write>()
            val currentNextKey = nextKey
            val currentPreviousKey = previousKey
            val index = Index().apply {
                this.version = 2
                nextKey = currentNextKey
                previousKey = currentPreviousKey
                activeRoutes = LinkedHashMap(ACTIVE_ROUTES)
                routeNames = LinkedHashMap(ROUTE_NAMES)
                customSegments = ArrayList(CUSTOM_SEGMENTS)
            }
            for ((route, nodes) in grouped) {
                val relative = "${route.floor}/${route.phase}/${routeFileName(route.name)}"
                val routeFile = RouteFile().apply {
                    this.version = 1
                    this.nodes = ArrayList<DungeonGuideNode?>(nodes)
                }
                val routeEntry = RouteEntry().apply {
                    floor = route.floor
                    phase = route.phase
                    name = route.name
                    this.file = relative
                    timing = routeTiming(route.floor, route.phase, route.name).name
                }
                writes.add(
                    ClientJsonFile.Write(
                        ROOT.resolve(relative),
                        GSON.toJson(routeFile).toByteArray(StandardCharsets.UTF_8),
                    ),
                )
                index.routes!!.add(routeEntry)
            }
            writes.add(ClientJsonFile.Write(INDEX, GSON.toJson(index).toByteArray(StandardCharsets.UTF_8)))
            pendingVersion = version
            pendingSave = SAVE_EXECUTOR.submit<SaveResult> {
                writeTransactionalBatch(writes, ROOT, IntConsumer { })
            }
        }

        /**
         * Stages every route file and the index before replacing any of them. If a replacement fails,
         * restore the original bytes for every attempted target (or remove targets that did not exist).
         */
        @JvmStatic
        fun writeTransactionalBatch(
            writes: List<ClientJsonFile.Write>?,
            rootDirectory: Path?,
            afterReplacement: IntConsumer?,
        ): SaveResult {
            val prepared = ArrayList<PreparedWrite>()
            val uniqueTargets = HashSet<Path>()
            var transactionDirectory: Path? = null
            var journal: Path? = null
            var journalPublished = false
            var attempted = 0
            try {
                if (writes.isNullOrEmpty()) throw java.io.IOException("empty Dungeon Guide save batch")
                if (rootDirectory == null) throw java.io.IOException("missing Dungeon Guide root")
                val root = rootDirectory.toAbsolutePath().normalize()
                Files.createDirectories(root)
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    throw java.io.IOException("Dungeon Guide root is not an ordinary directory")
                }
                val recovery = recoverPendingTransaction(root)
                if (!recovery.success) {
                    return SaveResult(false, false, "pending Dungeon Guide transaction cannot be recovered: ${recovery.detail}")
                }
                cleanupOrphanTransactions(root)
                val transactionRoot = root.resolve(TRANSACTION_DIRECTORY_NAME)
                if (Files.exists(transactionRoot, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(transactionRoot) || !Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS)) {
                        throw java.io.IOException("Dungeon Guide transaction path is not an ordinary directory")
                    }
                } else {
                    Files.createDirectory(transactionRoot)
                }
                transactionDirectory = transactionRoot.resolve(UUID.randomUUID().toString())
                Files.createDirectory(transactionDirectory)
                journal = root.resolve(TRANSACTION_JOURNAL_NAME)
                val manifest = PendingTransaction().apply {
                    version = 1
                    backupDirectory = "$TRANSACTION_DIRECTORY_NAME/${transactionDirectory!!.fileName}"
                    files = ArrayList()
                }
                for (write in writes) {
                    if (write == null || write.path == null || write.bytes == null ||
                        write.bytes.size > ClientJsonFile.MAX_FILE_BYTES
                    ) throw java.io.IOException("invalid or oversized Dungeon Guide save entry")
                    val target = write.path.toAbsolutePath().normalize()
                    if (!target.startsWith(root) || target == root || !uniqueTargets.add(target) ||
                        target == journal || target.startsWith(transactionRoot)
                    ) throw java.io.IOException("Dungeon Guide save target escaped the root or was duplicated")
                    val parent = target.parent ?: throw java.io.IOException("Dungeon Guide target has no parent directory")
                    ensureSafeParent(root, parent)
                    val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    if (existed && (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))) {
                        throw java.io.IOException("Dungeon Guide target is not a regular file")
                    }
                    var previous: ByteArray? = null
                    if (existed) {
                        val size = Files.size(target)
                        if (size > ClientJsonFile.MAX_FILE_BYTES) {
                            throw java.io.IOException("existing Dungeon Guide target exceeds the file size limit")
                        }
                        previous = Files.readAllBytes(target)
                    }
                    prepared.add(PreparedWrite(target, write.bytes.copyOf(), existed, previous))
                    manifest.files!!.add(
                        PendingEntry().apply {
                            this.target = root.relativize(target).toString().replace('\\', '/')
                            this.existed = existed
                            sha256 = if (existed) sha256(previous!!) else null
                        },
                    )
                }
                for (index in prepared.indices) {
                    val write = prepared[index]
                    if (write.existed) writeForced(transactionDirectory.resolve(backupFileName(index)), write.previousBytes, true)
                }
                forceDirectory(transactionDirectory)
                forceDirectory(transactionDirectory.parent)
                for (write in prepared) {
                    write.temporary = Files.createTempFile(write.target.parent, "geiler-guide-", ".tmp")
                    writeForced(write.temporary!!, write.nextBytes, false)
                }
                var journalTemporary: Path? = Files.createTempFile(root, "geiler-guide-journal-", ".tmp")
                try {
                    val bytes = GSON.toJson(manifest).toByteArray(StandardCharsets.UTF_8)
                    if (bytes.isEmpty() || bytes.size > ClientJsonFile.MAX_FILE_BYTES) {
                        throw java.io.IOException("Dungeon Guide transaction journal exceeds the size limit")
                    }
                    writeForced(journalTemporary!!, bytes, false)
                    replaceFile(journalTemporary, journal!!)
                    journalTemporary = null
                    forceDirectory(root)
                    journalPublished = true
                } finally {
                    if (journalTemporary != null) Files.deleteIfExists(journalTemporary)
                }
                for (write in prepared) {
                    attempted++
                    replaceFile(write.temporary!!, write.target)
                    write.temporary = null
                    forceDirectory(write.target.parent)
                    afterReplacement?.accept(attempted)
                }
                Files.delete(journal!!)
                journalPublished = false
                forceDirectory(root)
                try {
                    cleanupTransactionDirectory(root, transactionDirectory)
                } catch (_: java.io.IOException) {
                } catch (_: RuntimeException) {
                }
                return SaveResult(true, true, "")
            } catch (error: Exception) {
                val root = rootDirectory?.toAbsolutePath()?.normalize()
                val activeJournal = journal
                if (activeJournal != null && Files.exists(activeJournal, LinkOption.NOFOLLOW_LINKS)) journalPublished = true
                val restored = restorePriorFiles(prepared, attempted)
                var finalRestored = restored
                if (finalRestored && journalPublished && activeJournal != null) {
                    try {
                        Files.deleteIfExists(activeJournal)
                        forceDirectory(activeJournal.parent)
                        journalPublished = false
                    } catch (_: java.io.IOException) {
                        finalRestored = false
                    } catch (_: RuntimeException) {
                        finalRestored = false
                    }
                }
                val activeTransaction = transactionDirectory
                if (finalRestored && activeTransaction != null && root != null) {
                    try {
                        cleanupTransactionDirectory(root, activeTransaction)
                    } catch (_: java.io.IOException) {
                    } catch (_: RuntimeException) {
                    }
                }
                val detail = error.message ?: error.javaClass.simpleName
                return SaveResult(false, finalRestored, detail)
            } finally {
                for (write in prepared) {
                    val temporary = write.temporary ?: continue
                    try {
                        Files.deleteIfExists(temporary)
                    } catch (_: java.io.IOException) {
                    }
                }
            }
        }

        /** Restores the previous generation before any index or route file is read. */
        @JvmStatic
        fun recoverPendingTransaction(rootDirectory: Path?): RecoveryResult {
            if (rootDirectory == null) return RecoveryResult(false, "missing Dungeon Guide root")
            val root = rootDirectory.toAbsolutePath().normalize()
            val journal = root.resolve(TRANSACTION_JOURNAL_NAME)
            try {
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return RecoveryResult(true, "")
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                    return RecoveryResult(false, "Dungeon Guide root is not an ordinary directory")
                }
                if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    cleanupOrphanTransactions(root)
                    return RecoveryResult(true, "")
                }
                if (Files.isSymbolicLink(journal) || !Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
                    return RecoveryResult(false, "transaction journal is not an ordinary file")
                }
                val journalSize = Files.size(journal)
                if (journalSize <= 0 || journalSize > ClientJsonFile.MAX_FILE_BYTES) {
                    return RecoveryResult(false, "transaction journal size is outside the allowed range")
                }
                val journalBytes = Files.readAllBytes(journal)
                if (journalBytes.size.toLong() != journalSize) return RecoveryResult(false, "transaction journal changed while being read")
                val manifest = try {
                    GSON.fromJson(String(journalBytes, StandardCharsets.UTF_8), PendingTransaction::class.java)
                } catch (_: RuntimeException) {
                    return RecoveryResult(false, "transaction journal JSON is invalid")
                }
                val plan = validateRecoveryPlan(root, manifest)
                    ?: return RecoveryResult(false, "transaction journal failed path or backup validation")
                for (index in plan.entries.indices.reversed()) {
                    val entry = plan.entries[index]
                    if (!entry.existed) {
                        Files.deleteIfExists(entry.target)
                        continue
                    }
                    var temporary: Path? = Files.createTempFile(entry.target.parent, "geiler-guide-recovery-", ".tmp")
                    try {
                        val previous = readBoundedFile(entry.backup!!)
                        if (sha256(previous) != entry.sha256) throw java.io.IOException("transaction backup checksum changed during recovery")
                        writeForced(temporary!!, previous, false)
                        replaceFile(temporary, entry.target)
                        temporary = null
                        forceDirectory(entry.target.parent)
                    } finally {
                        if (temporary != null) Files.deleteIfExists(temporary)
                    }
                }
                Files.delete(journal)
                forceDirectory(root)
                cleanupTransactionDirectory(root, plan.transactionDirectory)
                return RecoveryResult(true, "")
            } catch (error: Exception) {
                return RecoveryResult(false, error.message ?: error.javaClass.simpleName)
            }
        }

        private fun validateRecoveryPlan(root: Path, manifest: PendingTransaction?): RecoveryPlan? {
            if (manifest == null || manifest.version != 1 || manifest.files.isNullOrEmpty() ||
                manifest.files!!.size > MAX_TRANSACTION_ENTRIES || manifest.backupDirectory == null ||
                !Regex("\\.transactions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    .matches(manifest.backupDirectory!!)
            ) return null
            val uuidText = manifest.backupDirectory!!.substring((TRANSACTION_DIRECTORY_NAME + "/").length)
            try {
                if (UUID.fromString(uuidText).toString() != uuidText) return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val transactionRoot = root.resolve(TRANSACTION_DIRECTORY_NAME)
            val transactionDirectory = root.resolve(manifest.backupDirectory!!).normalize()
            if (!transactionDirectory.startsWith(transactionRoot) || transactionDirectory == transactionRoot ||
                Files.isSymbolicLink(transactionRoot) || !Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(transactionDirectory) || !Files.isDirectory(transactionDirectory, LinkOption.NOFOLLOW_LINKS) ||
                !transactionDirectory.toRealPath().startsWith(root.toRealPath())
            ) return null
            val journal = root.resolve(TRANSACTION_JOURNAL_NAME)
            val entries = ArrayList<RecoveryEntry>(manifest.files!!.size)
            val targets = HashSet<Path>()
            for (index in manifest.files!!.indices) {
                val pending = manifest.files!![index]
                if (pending == null || pending.existed == null || !validJournalTarget(pending.target)) return null
                val relative = Path.of(pending.target!!).normalize()
                if (relative.isAbsolute || relative.startsWith("..")) return null
                val target = root.resolve(relative).normalize()
                if (!target.startsWith(root) || target == root || target == journal ||
                    target.startsWith(transactionRoot) || !targets.add(target)
                ) return null
                val parent = target.parent
                if (parent == null || !parent.startsWith(root)) return null
                validateExistingParent(root, parent)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                    (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                ) return null
                var backup: Path? = null
                var checksum: String? = null
                if (pending.existed == true) {
                    if (pending.sha256 == null || !Regex("[0-9a-f]{64}").matches(pending.sha256!!)) return null
                    backup = transactionDirectory.resolve(backupFileName(index))
                    if (Files.isSymbolicLink(backup) || !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) return null
                    if (Files.size(backup) > ClientJsonFile.MAX_FILE_BYTES) return null
                    if (sha256(readBoundedFile(backup)) != pending.sha256) return null
                    checksum = pending.sha256
                } else if (pending.sha256 != null) {
                    return null
                }
                entries.add(RecoveryEntry(target, backup, pending.existed == true, checksum))
            }
            return RecoveryPlan(transactionDirectory, entries)
        }

        private fun validJournalTarget(relative: String?): Boolean {
            if (relative == null || ChatStringSemantics.isBlank(relative) || relative.length > 256 || relative.startsWith("/") ||
                relative.contains('\\') || relative.contains(':') || relative.contains('\u0000')
            ) return false
            for (segment in relative.split('/')) {
                if (segment.isEmpty() || segment == "." || segment == ".." ||
                    !Regex("[A-Za-z0-9._-]{1,128}").matches(segment)
                ) return false
                val deviceName = segment.substringBefore('.').uppercase(Locale.ROOT)
                if (deviceName in setOf(
                        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
                        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
                    )
                ) return false
            }
            return relative == "index.json" ||
                Regex("F[1-7]/[A-Z][A-Z0-9_]{0,31}/[A-Za-z0-9._-]{1,64}\\.json").matches(relative)
        }

        private fun validateExistingParent(root: Path, parent: Path) {
            var current = root
            for (segment in root.relativize(parent)) {
                current = current.resolve(segment)
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw java.io.IOException("transaction target parent contains a non-directory or symbolic link")
                }
            }
            if (!parent.toRealPath().startsWith(root.toRealPath())) throw java.io.IOException("transaction target parent escaped the guide root")
        }

        private fun readBoundedFile(path: Path): ByteArray {
            val size = Files.size(path)
            if (size > ClientJsonFile.MAX_FILE_BYTES) throw java.io.IOException("transaction backup exceeds the file size limit")
            val bytes = Files.readAllBytes(path)
            if (bytes.size.toLong() != size) throw java.io.IOException("transaction backup changed while being read")
            return bytes
        }

        private fun sha256(bytes: ByteArray): String {
            try {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", impossible)
            }
        }

        private fun writeForced(path: Path, bytes: ByteArray?, createNew: Boolean) {
            if (bytes == null || bytes.size > ClientJsonFile.MAX_FILE_BYTES) throw java.io.IOException("transaction file exceeds the size limit")
            val options = if (createNew) {
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } else {
                setOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            }
            FileChannel.open(path, options).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }

        private fun forceDirectory(directory: Path?) {
            if (directory == null) return
            try {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: java.io.IOException) {
            } catch (_: UnsupportedOperationException) {
            }
        }

        private fun backupFileName(index: Int): String = "backup-${"%05d".format(Locale.ROOT, index)}.bin"

        private fun cleanupOrphanTransactions(root: Path) {
            val transactionRoot = root.resolve(TRANSACTION_DIRECTORY_NAME)
            if (!Files.exists(transactionRoot, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isSymbolicLink(transactionRoot) || !Files.isDirectory(transactionRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw java.io.IOException("Dungeon Guide transaction path is not an ordinary directory")
            }
            val directories = Files.list(transactionRoot).use { it.toList() }
            for (directory in directories) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                val name = directory.fileName.toString()
                if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(name)) continue
                cleanupTransactionDirectory(root, directory)
            }
            try {
                Files.deleteIfExists(transactionRoot)
            } catch (_: DirectoryNotEmptyException) {
            }
        }

        private fun cleanupTransactionDirectory(root: Path, directory: Path?) {
            val transactionRoot = root.resolve(TRANSACTION_DIRECTORY_NAME)
            if (directory == null || !directory.normalize().startsWith(transactionRoot.normalize()) ||
                directory == transactionRoot || Files.isSymbolicLink(directory) ||
                !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
            ) return
            val files = Files.list(directory).use { it.toList() }
            for (file in files) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                    !Regex("backup-[0-9]{5}\\.bin").matches(file.fileName.toString())
                ) return
            }
            for (file in files) Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
            try {
                Files.deleteIfExists(transactionRoot)
            } catch (_: DirectoryNotEmptyException) {
            }
        }

        /** Creates only ordinary directories beneath the configured guide root; nested symlinks are rejected. */
        private fun ensureSafeParent(root: Path, parent: Path) {
            if (!parent.startsWith(root)) throw java.io.IOException("Dungeon Guide parent escaped the root")
            var current = root
            for (segment in root.relativize(parent)) {
                current = current.resolve(segment)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                        throw java.io.IOException("Dungeon Guide parent contains a non-directory or symbolic link")
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
            if (!parent.toRealPath().startsWith(root.toRealPath())) {
                throw java.io.IOException("Dungeon Guide parent resolves outside the guide root")
            }
        }

        private fun replaceFile(source: Path, target: Path) {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun restorePriorFiles(writes: List<PreparedWrite>, attempted: Int): Boolean {
            var restored = true
            for (index in minOf(attempted, writes.size) - 1 downTo 0) {
                val write = writes[index]
                var rollback: Path? = null
                try {
                    if (!write.existed) {
                        Files.deleteIfExists(write.target)
                        continue
                    }
                    rollback = Files.createTempFile(write.target.parent, "geiler-guide-rollback-", ".tmp")
                    Files.write(rollback, write.previousBytes!!)
                    replaceFile(rollback, write.target)
                    rollback = null
                } catch (error: java.io.IOException) {
                    restored = false
                    GeilerAddons.LOGGER.error("Could not restore prior Dungeon Guide file {}", write.target, error)
                } catch (error: RuntimeException) {
                    restored = false
                    GeilerAddons.LOGGER.error("Could not restore prior Dungeon Guide file {}", write.target, error)
                } finally {
                    if (rollback != null) {
                        try {
                            Files.deleteIfExists(rollback)
                        } catch (_: java.io.IOException) {
                        }
                    }
                }
            }
            return restored
        }

        private fun resolvePending(): Boolean = resolvePending(false)

        private fun resolvePending(wait: Boolean): Boolean {
            val current = pendingSave ?: return true
            if (!wait && !current.isDone) return false
            var result: SaveResult
            try {
                result = current.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (error: ExecutionException) {
                persistenceBlocked = true
                GeilerAddons.LOGGER.error("Dungeon Guide route save failed unexpectedly; future writes are blocked", error.cause)
                result = SaveResult(false, false, "save worker failed unexpectedly")
            }
            if (pendingSave === current) pendingSave = null
            if (result.success && pendingVersion == mutationVersion) {
                dirty = false
            } else {
                dirty = true
                saveDelayTicks = 40
                if (!result.priorStateRestored) {
                    persistenceBlocked = true
                    GeilerAddons.LOGGER.error("Dungeon Guide save failed and rollback was incomplete; writes are blocked: {}", result.detail)
                } else if (!result.success) {
                    GeilerAddons.LOGGER.error("Dungeon Guide save failed; previous files were restored and the save will be retried: {}", result.detail)
                }
            }
            return true
        }

        private fun markDirty() {
            if (persistenceBlocked) return
            dirty = true
            mutationVersion++
            saveDelayTicks = 20
        }

        private fun markGuideDataChanged() {
            guideRevision++
            nodesSnapshot = null
        }

        private fun archiveLegacyFiles(index: Index): Boolean {
            val archive = ROOT.resolve("archive/legacy-v1")
            try {
                Files.createDirectories(archive)
                copyIfAbsent(INDEX, archive.resolve("index.json"))
                for (candidate in index.routes!!) {
                    if (!validEntry(candidate)) return false
                    val entry = candidate!!
                    val source = ROOT.resolve(entry.file!!).normalize()
                    if (!source.startsWith(ROOT.normalize())) return false
                    val target = archive.resolve("routes").resolve(entry.file!!).normalize()
                    if (!target.startsWith(archive.resolve("routes").normalize())) return false
                    copyIfAbsent(source, target)
                }
                return true
            } catch (error: java.io.IOException) {
                GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide files", error)
                return false
            }
        }

        private fun archiveLegacyCatalog(): Boolean {
            val archive = ROOT.resolve("archive/legacy-v1/dungeon-guides.json")
            return try {
                Files.createDirectories(archive.parent)
                copyIfAbsent(LEGACY, archive)
                true
            } catch (error: java.io.IOException) {
                GeilerAddons.LOGGER.error("Could not archive legacy Dungeon Guide catalog", error)
                false
            }
        }

        private fun copyIfAbsent(source: Path?, target: Path) {
            if (source == null || !Files.isRegularFile(source)) return
            Files.createDirectories(target.parent)
            if (!Files.exists(target)) Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
        }

        private fun migrateLegacyRoutes(legacy: Index) {
            val floors = LinkedHashSet<String>()
            for (candidate in legacy.routes!!) {
                if (validEntry(candidate)) floors.add(candidate!!.floor!!)
            }
            NODES.forEach { floors.add(it.floor) }
            for (floor in floors) {
                val names = LinkedHashSet<String>()
                NODES.filter { it.floor == floor }.forEach { names.add(it.routeName) }
                legacy.routes!!.filter { validEntry(it) && it!!.floor == floor }.forEach { names.add(it!!.name!!) }
                val commonName = when (names.size) {
                    0 -> "Dungeon Route"
                    1 -> names.first()
                    else -> "Combined Route"
                }
                ROUTE_NAMES[floor] = commonName
            }
            normalizeRouteNames()
            markGuideDataChanged()
            markDirty()
            saveDelayTicks = 0
            GeilerAddons.LOGGER.info("Migrated {} Dungeon Guide steps into one named route per floor", NODES.size)
        }

        private fun normalizeRouteNames() {
            normalizeRouteNamesAndOrder(NODES, ROUTE_NAMES)
            val timings = LinkedHashMap<String, TransitionTiming>()
            for ((key, timing) in ROUTE_TIMINGS) {
                val route = parseRouteIdentity(key) ?: continue
                val name = ROUTE_NAMES.getOrDefault(route.floor, "Dungeon Route")
                timings.putIfAbsent(routeIdentity(route.floor, route.phase, name), timing)
            }
            for (node in NODES) {
                timings.putIfAbsent(routeIdentity(node.floor, node.phase, node.routeName), TransitionTiming.NEXT_OBJECTIVE)
            }
            ROUTE_TIMINGS.clear()
            ROUTE_TIMINGS.putAll(timings)
            ACTIVE_ROUTES.clear()
        }

        /** Preserves a sole legacy name and combines multi-route floors in stable phase/name/order order. */
        @JvmStatic
        fun normalizeRouteNamesAndOrder(nodes: MutableList<DungeonGuideNode>?, routeNames: MutableMap<String, String>?) {
            val source = nodes ?: return
            val namesByFloor = LinkedHashMap<String, MutableSet<String>>()
            for (node in source) {
                if (node == null) continue
                val name = ChatStringSemantics.trim(node.routeName ?: "")
                if (name.isNotEmpty()) {
                    namesByFloor.getOrPut(node.floor) { TreeSet(String.CASE_INSENSITIVE_ORDER) }.add(name)
                }
            }
            val routeNamesTarget = routeNames ?: return
            for (node in source) {
                if (node == null) continue
                val names = namesByFloor[node.floor] ?: emptySet()
                val selected = when (names.size) {
                    0 -> "Dungeon Route"
                    1 -> names.first()
                    else -> "Combined Route"
                }
                routeNamesTarget.putIfAbsent(node.floor, selected)
            }
            source.sortWith(
                compareBy<DungeonGuideNode> { DungeonFloor.parse(it.floor)!!.ordinal }
                    .thenBy { phaseOrder(it.floor, it.phase) }
                    .thenBy { it.phase }
                    .thenComparator { left, right -> String.CASE_INSENSITIVE_ORDER.compare(left.routeName, right.routeName) }
                    .thenBy { it.order },
            )
            for (node in source) node.routeName = routeNamesTarget.getOrPut(node.floor) { "Dungeon Route" }
            val nextOrder = LinkedHashMap<String, Int>()
            for (node in source) {
                val key = routeKey(node.floor, node.phase)
                node.order = nextOrder[key] ?: 0
                nextOrder[key] = node.order + 1
            }
        }

        private fun validEntry(entry: RouteEntry?): Boolean {
            if (entry == null || DungeonFloor.parse(entry.floor) == null || entry.phase == null ||
                !Regex("[A-Z][A-Z0-9_]{0,31}").matches(entry.phase!!) || entry.name == null ||
                ChatStringSemantics.isBlank(entry.name!!) || entry.file == null
            ) return false
            val file = entry.file!!
            if (!Regex("[A-Za-z0-9._/-]{1,256}").matches(file) ||
                file.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                !file.lowercase(Locale.ROOT).endsWith(".json")
            ) return false
            return true
        }

        private fun routeFileName(name: String): String {
            var slug = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-")
            while (slug.startsWith('.')) slug = slug.substring(1)
            if (ChatStringSemantics.isBlank(slug)) slug = "route"
            if (slug.length > 48) slug = slug.substring(0, 48)
            return "$slug-${Integer.toUnsignedString(name.hashCode(), 36)}.json"
        }

        private fun routeKey(floor: String, phase: String): String = "$floor|$phase"

        private fun routeIdentity(floor: String?, phase: String?, name: String?): String =
            "${floor}\u001f${phase}\u001f${name}"

        private fun parseRouteIdentity(key: String): Route? {
            val parts = key.split('\u001f', limit = 3)
            return if (parts.size == 3) Route(parts[0], parts[1], parts[2]) else null
        }

        private fun validKey(value: String?, fallback: String): String {
            if (value == null || ChatStringSemantics.isBlank(value)) return fallback
            val cleaned = ChatStringSemantics.trim(value)
            return if (cleaned.length > 64) fallback else cleaned
        }
    }

    @JvmRecord
    data class SaveResult(val success: Boolean, val priorStateRestored: Boolean, val detail: String)

    @JvmRecord
    data class RecoveryResult(val success: Boolean, val detail: String)

    @JvmRecord
    data class Route(val floor: String, val phase: String, val name: String)

    enum class TransitionTiming {
        NEXT_OBJECTIVE, EVENT_SEGMENT;

        companion object {
            @JvmStatic
            fun parse(value: String?): TransitionTiming = try {
                if (value == null) NEXT_OBJECTIVE else valueOf(value)
            } catch (_: IllegalArgumentException) {
                NEXT_OBJECTIVE
            }
        }
    }

    @JvmRecord
    data class ImportResult(val success: Boolean, val imported: Int, val error: String?)

    private class PendingTransaction {
        @JvmField var version: Int? = null
        @JvmField var backupDirectory: String? = null
        @JvmField var files: MutableList<PendingEntry?>? = null
    }

    private class PendingEntry {
        @JvmField var target: String? = null
        @JvmField var existed: Boolean? = null
        @JvmField var sha256: String? = null
    }

    private data class RecoveryEntry(val target: Path, val backup: Path?, val existed: Boolean, val sha256: String?)
    private data class RecoveryPlan(val transactionDirectory: Path, val entries: List<RecoveryEntry>)

    private class PreparedWrite(
        val target: Path,
        val nextBytes: ByteArray,
        val existed: Boolean,
        val previousBytes: ByteArray?,
    ) {
        var temporary: Path? = null
    }

    private class Index {
        @JvmField var version: Int = 0
        @JvmField var routes: MutableList<RouteEntry?>? = ArrayList()
        @JvmField var nextKey: String? = null
        @JvmField var previousKey: String? = null
        @JvmField var activeRoutes: MutableMap<String, String>? = LinkedHashMap()
        @JvmField var routeNames: MutableMap<String, String>? = LinkedHashMap()
        @JvmField var customSegments: MutableList<DungeonGuideCustomSegment?>? = ArrayList()
    }

    private class RouteEntry {
        @JvmField var floor: String? = null
        @JvmField var phase: String? = null
        @JvmField var name: String? = null
        @JvmField var file: String? = null
        @JvmField var timing: String? = null
    }

    private class RouteFile {
        @JvmField var version: Int = 0
        @JvmField var nodes: MutableList<DungeonGuideNode?>? = null
    }

    private class LegacyCatalog {
        @JvmField var version: Int = 0
        @JvmField var nodes: MutableList<DungeonGuideNode?>? = null
        @JvmField var nextKey: String? = null
        @JvmField var previousKey: String? = null
    }
}


