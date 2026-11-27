package geiler.addons.client.hunting

/**
 * Best Hunting Method: ranks huntable shards by coins per hour from the player's own kill rates and Hunting Fortune.
 * The rates come from the user-filled `hunting-methods.json`; a shard without a rate or a price is listed as missing,
 * never guessed. Pure so the ranking can be checked offline.
 */
object HuntingMethods {
    data class Method @JvmOverloads constructor(
        val shardId: String,
        val name: String,
        val mob: String,
        val location: String,
        /** Kills (or catches) per hour; null until the user fills it in. */
        val killsPerHour: Double?,
        /** Shards per kill before Hunting Fortune. */
        val shardsPerKill: Double,
        /** Id of the [Group] this shard is hunted with, or null. */
        val group: String? = null,
    )

    /**
     * Shards obtained together (one place, one method). [killsPerHour] is the group's total: members without their own
     * rate share what the members with a rate leave of it, evenly.
     */
    data class Group(val id: String, val name: String, val killsPerHour: Double?)

    class Ranked(val method: Method, val price: Double, val perHour: Double, val lostPerHour: Double)

    /** Hunting Fortune adds shards like other fortunes: 100 fortune doubles the drop. */
    @JvmStatic
    fun shardsPerHour(method: Method, fortune: Double): Double =
        (method.killsPerHour ?: 0.0) * method.shardsPerKill * (1.0 + fortune.coerceAtLeast(0.0) / 100.0)

    @JvmStatic
    fun rank(methods: List<Method>, prices: Map<String, ShardPrice>, fortune: Double, offers: Boolean): List<Ranked> {
        val scored = methods.mapNotNull { m ->
            val rate = m.killsPerHour ?: return@mapNotNull null
            if (rate <= 0.0) return@mapNotNull null
            val each = prices[m.shardId]?.let { if (offers) it.sellOffer else it.instantSell } ?: return@mapNotNull null
            if (each <= 0.0) return@mapNotNull null
            m to shardsPerHour(m, fortune) * each * (1.0 - ShardValue.TAX)
        }.sortedWith(compareByDescending<Pair<Method, Double>> { it.second }.thenBy { it.first.shardId })
        val best = scored.firstOrNull()?.second ?: 0.0
        return scored.map { (m, perHour) ->
            val p = prices.getValue(m.shardId)
            Ranked(m, if (offers) p.sellOffer else p.instantSell, perHour, best - perHour)
        }
    }

    /** `id: reason` for every shard that could not be ranked. */
    @JvmStatic
    fun unranked(methods: List<Method>, prices: Map<String, ShardPrice>): List<String> = methods.mapNotNull { m ->
        when {
            m.killsPerHour == null || m.killsPerHour <= 0.0 -> "${m.shardId}: no kills/hour yet"
            (prices[m.shardId]?.let { maxOf(it.instantSell, it.sellOffer) } ?: 0.0) <= 0.0 -> "${m.shardId}: no price"
            else -> null
        }
    }

    /**
     * [methods] with each grouped shard's effective kill rate: its own rate, else an even share of the group total
     * left after the members that have a rate (never below 0); null when neither is known.
     */
    @JvmStatic
    fun resolve(methods: List<Method>, groups: List<Group>): List<Method> {
        val byId = groups.associateBy { it.id }
        return methods.map { m ->
            val group = m.group?.let(byId::get) ?: return@map m
            if (m.killsPerHour != null && m.killsPerHour > 0.0) return@map m
            val total = group.killsPerHour?.takeIf { it > 0.0 } ?: return@map m
            val members = methods.filter { it.group == group.id }
            val set = members.mapNotNull { it.killsPerHour?.takeIf { k -> k > 0.0 } }
            val unset = members.size - set.size
            m.copy(killsPerHour = ((total - set.sum()) / unset).coerceAtLeast(0.0))
        }
    }

    /**
     * One screen row: a shard, or a group header ([group] set, its [method] stands for the group). [inGroup] names the
     * group of a member row; [split] marks a member whose rate is its share of the group total. NaN is not known.
     */
    class Row @JvmOverloads constructor(
        val method: Method, val perHour: Double, val lostPerHour: Double,
        val sellOffer: Double, val instantSell: Double, val avg3: Double, val avg7: Double,
        val group: Group? = null, val inGroup: String? = null, val split: Boolean = false,
    ) {
        val ranked: Boolean get() = !perHour.isNaN()
    }

    enum class Column(val label: String) {
        SHARD("Shard"), PER_HOUR("Coins/h"), LOST("Lost/h"), OFFER("Offer"), INSTA("Insta"),
        AVG3("3d avg"), AVG7("7d avg"), KILLS("Kills/h"), SHARDS_PER_KILL("Shards/kill"),
    }

    const val GROUP_PREFIX = "group:"

    /**
     * Every shard as a row, and a header row per group with at least one member. Coins per hour of a group is the sum
     * of its ranked members; lost per hour compares single shards and groups (the units the player chooses between).
     */
    @JvmStatic
    @JvmOverloads
    fun table(
        methods: List<Method>, prices: Map<String, ShardPrice>, fortune: Double, offers: Boolean,
        average: (String, Int) -> Double, groups: List<Group> = emptyList(),
    ): List<Row> {
        val live = groups.filter { g -> methods.any { it.group == g.id } }
        val liveIds = live.map { it.id }.toSet()
        val plain = methods.map { if (it.group != null && it.group !in liveIds) it.copy(group = null) else it }
        val resolved = resolve(plain, live)
        val perHour = resolved.associate { m ->
            val rate = m.killsPerHour?.takeIf { it > 0.0 }
            val each = prices[m.shardId]?.let { if (offers) it.sellOffer else it.instantSell }?.takeIf { it > 0.0 }
            m.shardId to if (rate == null || each == null) Double.NaN else shardsPerHour(m, fortune) * each * (1.0 - ShardValue.TAX)
        }
        val groupPerHour = live.associate { g ->
            val known = resolved.filter { it.group == g.id }.map { perHour.getValue(it.shardId) }.filter { !it.isNaN() }
            g.id to if (known.isEmpty()) Double.NaN else known.sum()
        }
        val units = resolved.filter { it.group == null }.map { perHour.getValue(it.shardId) } + groupPerHour.values
        val best = units.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        fun lost(value: Double) = if (value.isNaN() || best.isNaN()) Double.NaN else best - value
        val rows = ArrayList<Row>()
        for (g in live) {
            val members = plain.filter { it.group == g.id }
            val header = Method(GROUP_PREFIX + g.id, g.name, members.joinToString(", ") { it.mob.ifBlank { it.name } },
                members.map { it.location }.filter { it.isNotBlank() }.distinct().joinToString(", "), g.killsPerHour, 1.0)
            val value = groupPerHour.getValue(g.id)
            rows.add(Row(header, value, lost(value), Double.NaN, Double.NaN, Double.NaN, Double.NaN, group = g))
        }
        for ((m, r) in plain.zip(resolved)) {
            val p = prices[m.shardId]
            val value = perHour.getValue(m.shardId)
            rows.add(Row(r, value, if (m.group == null) lost(value) else Double.NaN,
                p?.sellOffer?.takeIf { it > 0.0 } ?: Double.NaN, p?.instantSell?.takeIf { it > 0.0 } ?: Double.NaN,
                average(m.shardId, 3), average(m.shardId, 7), inGroup = m.group,
                split = m.group != null && (m.killsPerHour ?: 0.0) <= 0.0 && r.killsPerHour != null))
        }
        return rows
    }

    /**
     * Rows whose shard, mob or place contains [query] (any case), sorted by [column]; unknown values go last. A group
     * and its members move as one unit (header first, members by name); a hit in any of them shows the whole unit.
     */
    @JvmStatic
    fun view(rows: List<Row>, query: String, column: Column, descending: Boolean): List<Row> {
        val q = query.trim().lowercase()
        fun hit(r: Row) = listOf(r.method.name, r.method.mob, r.method.location, r.method.shardId).any { it.lowercase().contains(q) }
        val members = rows.filter { it.inGroup != null }.groupBy { it.inGroup }
        val units = rows.filter { it.inGroup == null }.map { lead ->
            listOf(lead) + (lead.group?.let { g -> members[g.id].orEmpty().sortedBy { it.method.name.lowercase() } } ?: emptyList())
        }.filter { unit -> q.isEmpty() || unit.any(::hit) }
        if (column == Column.SHARD) {
            val byName = units.sortedBy { it[0].method.name.lowercase() }
            return (if (descending) byName.reversed() else byName).flatten()
        }
        val value: (Row) -> Double = when (column) {
            Column.PER_HOUR -> { r -> r.perHour }
            Column.LOST -> { r -> r.lostPerHour }
            Column.OFFER -> { r -> r.sellOffer }
            Column.INSTA -> { r -> r.instantSell }
            Column.AVG3 -> { r -> r.avg3 }
            Column.AVG7 -> { r -> r.avg7 }
            Column.KILLS -> { r -> r.method.killsPerHour ?: Double.NaN }
            Column.SHARDS_PER_KILL -> { r -> r.method.shardsPerKill }
            Column.SHARD -> { _ -> 0.0 }
        }
        val known = units.filter { !value(it[0]).isNaN() }
            .sortedWith(compareBy<List<Row>> { value(it[0]) }.thenBy { it[0].method.shardId })
        return ((if (descending) known.reversed() else known) + units.filter { value(it[0]).isNaN() }).flatten()
    }

    /** The starting table is empty: the player adds the shards they hunt from the picker. */
    @JvmStatic
    fun defaults(): List<Method> = emptyList()

    /** [methods] with shard [id] added (no rate yet), unless it is listed already. */
    @JvmStatic
    fun add(methods: List<Method>, id: String, name: String): List<Method> =
        if (methods.any { it.shardId == id }) methods else methods + Method(id, name, "", "", null, 1.0)

    /** [methods] without shard [id]. */
    @JvmStatic
    fun remove(methods: List<Method>, id: String): List<Method> = methods.filter { it.shardId != id }
}
