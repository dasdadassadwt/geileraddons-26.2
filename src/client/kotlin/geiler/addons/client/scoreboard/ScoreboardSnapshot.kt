package geiler.addons.client.scoreboard

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Immutable, client-thread snapshot consumed by both the HUD and its preview. */
class ScoreboardSnapshot(active: Boolean, title: Component?, entries: List<Entry>?) {
    private val activeValue = active
    private val titleValue = title ?: Component.empty()
    private val entriesValue: List<Entry> = entries?.let { java.util.List.copyOf(it) } ?: java.util.List.of()

    fun active(): Boolean = activeValue

    fun title(): Component = titleValue

    fun entries(): List<Entry> = entriesValue

    override fun equals(other: Any?): Boolean =
        this === other || other is ScoreboardSnapshot && activeValue == other.activeValue
            && titleValue == other.titleValue && entriesValue == other.entriesValue

    override fun hashCode(): Int {
        var result = activeValue.hashCode()
        result = 31 * result + titleValue.hashCode()
        result = 31 * result + entriesValue.hashCode()
        return result
    }

    override fun toString(): String =
        "ScoreboardSnapshot[active=$activeValue, title=$titleValue, entries=$entriesValue]"

    /** Java-callable value with the same constructor and accessor names as the frozen record. */
    class Entry(owner: String?, label: Component?, value: Component?) {
        @get:kotlin.jvm.JvmName("owner")
        val ownerValue: String? = owner

        @get:kotlin.jvm.JvmName("label")
        val labelValue: Component? = label

        @get:kotlin.jvm.JvmName("value")
        val valueValue: Component? = value

        override fun equals(other: Any?): Boolean =
            this === other || other is Entry && ownerValue == other.ownerValue
                && labelValue == other.labelValue && valueValue == other.valueValue

        override fun hashCode(): Int {
            var result = ownerValue.hashCodeOrZero()
            result = 31 * result + labelValue.hashCodeOrZero()
            result = 31 * result + valueValue.hashCodeOrZero()
            return result
        }

        override fun toString(): String = "Entry[owner=$ownerValue, label=$labelValue, value=$valueValue]"
    }

    companion object {
        private val EMPTY = ScoreboardSnapshot(false, Component.empty(), java.util.List.of())
        private val PREVIEW = ScoreboardSnapshot(
            true,
            Component.literal("Example Server"),
            java.util.List.of(
                Entry("preview-1", Component.literal("Profile"), Component.literal("Level 42")),
                Entry("preview-2", Component.literal("Coins"), Component.literal("1,234,567")),
                Entry("preview-3", Component.literal("Location"), Component.literal("Hub")),
            ),
        )

        @JvmStatic
        fun empty(): ScoreboardSnapshot = EMPTY

        @JvmStatic
        fun preview(): ScoreboardSnapshot = PREVIEW

        /** Called from the client tick only; no world lookup happens during the render callback. */
        @JvmStatic
        fun capture(minecraft: Minecraft?): ScoreboardSnapshot = ScoreboardSnapshotCapture.capture(minecraft)
    }
}

private fun Any?.hashCodeOrZero(): Int = this?.hashCode() ?: 0

// Captured scoreboard rows always have values; these preserve non-null Kotlin call sites while the
// Java-facing property accessors retain the frozen record's ability to carry null components.
fun ScoreboardSnapshot.Entry.owner(): String = ownerValue!!

fun ScoreboardSnapshot.Entry.label(): Component = labelValue!!

fun ScoreboardSnapshot.Entry.value(): Component = valueValue!!
