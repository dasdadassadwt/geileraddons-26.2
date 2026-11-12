package geiler.addons.client.scoreboard

import java.util.Optional

/** Pure decisions for replacing or resetting the active layout. */
class ScoreboardLayoutState private constructor() {
    @JvmRecord
    data class ReloadResult(val accepted: Boolean, val layout: ScoreboardLayout)

    companion object {
        /** Keeps the current layout on a failed read/decode, and accepts a parsed candidate atomically. */
        @JvmStatic
        fun reload(current: ScoreboardLayout?, candidate: Optional<ScoreboardLayout>?): ReloadResult {
            val active = current ?: ScoreboardLayout()
            if (candidate == null || candidate.isEmpty) return ReloadResult(false, active)
            val replacement = candidate.get() ?: return ReloadResult(false, active)
            replacement.normalize()
            return ReloadResult(true, replacement)
        }

        /** A reset creates a fresh bundled layout; an unchecked area preserves object identity and contents. */
        @JvmStatic
        fun reset(current: ScoreboardLayout?, selected: Boolean): ScoreboardLayout =
            if (!selected) current ?: ScoreboardLayout() else ScoreboardLayout()
    }
}
