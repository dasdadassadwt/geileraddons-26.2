package geiler.addons.client.mining

import java.util.Locale

/** Builds the text lines of the Mining panels. Pure, so the exact wording and colors are checked offline. */
object MiningHudFormat {
    @JvmStatic
    fun commissionLines(
        commissions: List<MiningTabText.Commission>,
        hideCompleted: Boolean,
        highlightCompleted: Boolean,
    ): List<String> {
        val shown = if (hideCompleted) commissions.filter { !it.done } else commissions
        if (shown.isEmpty()) return emptyList()
        val out = ArrayList<String>(shown.size + 1)
        out.add("§6Commissions")
        for (commission in shown) {
            if (commission.done) {
                out.add((if (highlightCompleted) "§a" else "§7") + "✔ " + commission.name)
            } else {
                out.add("§7" + commission.name + " " + progressColor(commission.fraction) + percent(commission.fraction))
            }
        }
        return out
    }

    @JvmStatic
    fun powderLines(
        powders: MiningTabText.Powders,
        session: MiningPowderSession,
        nowNanos: Long,
        showGain: Boolean,
        showPerHour: Boolean,
        showGlacite: Boolean,
    ): List<String> {
        val out = ArrayList<String>(3)
        out.add(powderLine("§2Mithril", powders.mithril, session.gainMithril(), session, nowNanos, showGain, showPerHour))
        out.add(powderLine("§dGemstone", powders.gemstone, session.gainGemstone(), session, nowNanos, showGain, showPerHour))
        if (showGlacite) {
            out.add(powderLine("§bGlacite", powders.glacite, session.gainGlacite(), session, nowNanos, showGain, showPerHour))
        }
        return out
    }

    private fun powderLine(
        label: String,
        amount: Long,
        gain: Long,
        session: MiningPowderSession,
        nowNanos: Long,
        showGain: Boolean,
        showPerHour: Boolean,
    ): String {
        val extra = ArrayList<String>(2)
        if (showGain) extra.add("+" + count(gain))
        if (showPerHour) extra.add(count(session.perHour(gain, nowNanos)) + "/h")
        val tail = if (extra.isEmpty()) "" else " §7(" + extra.joinToString(" | ") + ")"
        return "$label §f${count(amount)}$tail"
    }

    @JvmStatic
    fun count(value: Long): String = String.format(Locale.ROOT, "%,d", value)

    @JvmStatic
    fun percent(fraction: Double): String {
        val value = fraction * 100.0
        return if (value == Math.floor(value)) String.format(Locale.ROOT, "%.0f%%", value)
        else String.format(Locale.ROOT, "%.1f%%", value)
    }

    private fun progressColor(fraction: Double): String = when {
        fraction >= 0.75 -> "§a"
        fraction >= 0.25 -> "§e"
        else -> "§c"
    }

    /** The color of a drill fuel bar: green while healthy, yellow below half, red when nearly empty. */
    @JvmStatic
    fun fuelColor(fraction: Double): Int = when {
        fraction > 0.5 -> 0xFF55FF55.toInt()
        fraction > 0.2 -> 0xFFFFD24A.toInt()
        else -> 0xFFFF5555.toInt()
    }
}
