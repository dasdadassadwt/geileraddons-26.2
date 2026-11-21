package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonClassTab
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.hud.HudElement
import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Cooldowns of ability items, learned from the held item's lore (`Cooldown: Ns`) when the action bar shows its mana
 * cost. Shown as a small number and a ready check on the item, as a movable box, or both.
 */
class ItemCooldownsModule private constructor() : Module(
    "Item Cooldowns",
    "Shows ability item cooldowns on the item and in a box, with a check when ready.",
    Category.VISUAL,
    false,
    *spec.settings.toTypedArray(),
), HudElement, ModulePreview {
    private val mode = spec.choice("mode")
    private val readyCheck = spec.toggle("readyCheck")
    private val checkSeconds = spec.slider("checkSeconds")
    private val mageReduction = spec.toggle("mageReduction")

    private val tracker = ItemCooldownTracker()
    private val icons = HashMap<String, ItemStack>()
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)
    private var started = 0L
    private var ignoredRunning = 0L
    private var actionBars = 0L
    private var unmatchedSamples = 0
    private var confirmed = 0L
    private var relabelled = 0L
    private var synced = 0L
    private var cancelled = 0L
    private var lastKey = ""
    private var lastAbility = ""
    private var lastStartMs = 0L
    private val drops = LinkedHashMap<String, Long>()
    private var cachedMultiplier = 1.0
    private var multiplierTick = Long.MIN_VALUE

    override fun onEnable() {
        started = 0L
        ignoredRunning = 0L
        actionBars = 0L
        unmatchedSamples = 0
        confirmed = 0L
        relabelled = 0L
        synced = 0L
        cancelled = 0L
        lastKey = ""
        lastAbility = ""
        lastStartMs = 0L
        drops.clear()
        tracker.clear()
        icons.clear()
        diagnostics.enable("mode=${mode.value()} readyCheck=${readyCheck.value()} checkSeconds=${checkSeconds.intValue()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE started=$started")
        diagnostics.disable()
        tracker.clear()
        icons.clear()
    }

    override fun isSettingVisible(setting: Setting): Boolean =
        if (setting === checkSeconds) readyCheck.value() else true

    /** Called by the shared client tick registration: bounded status and pruning of icons without a timer. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        icons.keys.removeIf { tracker.reading(it, nowMs(), checkMs()) == null }
        diagnostics.status {
            "enabled=true mode=${mode.value()} tracked=${tracker.size()} started=$started ignoredRunning=$ignoredRunning " +
                "actionBars=$actionBars confirmed=$confirmed relabelled=$relabelled synced=$synced cancelled=$cancelled " +
                "drops=$drops suppressedEvents=$it"
        }
    }

    /**
     * `Minecraft.startUseItem`: a right click with the held item starts the cooldown of its first right-click ability
     * that states one. The action bar and the server's chat lines then correct it. Holding right click repeats this call;
     * the tracker ignores a use while the cooldown runs.
     */
    fun onUse() {
        if (!isEnabled()) return
        val player = Minecraft.getInstance().player ?: return
        val held = player.mainHandItem
        if (held.isEmpty) { count("noHeld"); return }
        val key = itemKey(held)
        if (key.isEmpty()) { count("noId"); return }
        val abilities = ItemCooldownTracker.rightClickAbilities(SkyblockItems.loreLines(held))
        if (abilities.isEmpty()) { count("noAbility"); return }
        val ability = abilities.first()
        if (!startFor(key, held, ability.name, ability.seconds)) return
        lastKey = key
        lastAbility = ability.name
        lastStartMs = nowMs()
        diagnostics.event("use item=$key ability=${ability.name.take(40)} seconds=${ability.seconds} abilities=${abilities.size}")
    }

    /** The action bar's `-N Mana (<Ability>)`: names the ability a multi-ability item really used, or starts it. */
    fun onActionBar(plain: String) {
        if (!isEnabled()) return
        actionBars++
        val ability = ItemCooldownTracker.abilityFromActionBar(plain)
        if (ability == null) {
            if (plain.contains("Mana") && unmatchedSamples < MAX_SAMPLES) {
                unmatchedSamples++
                diagnostics.event("sample noActionBarMatch text=${plain.take(120)}")
            }
            return
        }
        val player = Minecraft.getInstance().player ?: return
        val held = player.mainHandItem
        if (held.isEmpty) { count("noHeld"); return }
        val key = itemKey(held)
        if (key.isEmpty()) { count("noId"); return }
        val seconds = ItemCooldownTracker.cooldownSeconds(SkyblockItems.loreLines(held), ability)
        if (seconds == null || seconds <= 0.0) { count("noCooldownLine"); diagnostics.event("noCooldown item=$key ability=${ability.take(40)}"); return }
        val now = nowMs()
        if (key == lastKey && now - lastStartMs < CONFIRM_WINDOW_MS) {
            if (ability == lastAbility) { confirmed++; return }
            tracker.replace(key, ability, totalMs(seconds), lastStartMs)
            lastAbility = ability
            relabelled++
            diagnostics.event("relabel item=$key ability=${ability.take(40)} seconds=$seconds")
            return
        }
        if (startFor(key, held, ability, seconds)) {
            lastKey = key
            lastAbility = ability
            lastStartMs = now
            diagnostics.event("start source=actionbar item=$key ability=${ability.take(40)} seconds=$seconds")
        }
    }

    /** Chat lines: the server's own remaining time wins, and a refused use (no mana) cancels the timer. */
    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val now = nowMs()
        val remaining = ItemCooldownTracker.cooldownFromChat(plain)
        if (remaining != null) {
            val key = Minecraft.getInstance().player?.mainHandItem?.let { if (it.isEmpty) "" else itemKey(it) }.orEmpty()
                .ifEmpty { if (now - lastStartMs < CONFIRM_WINDOW_MS) lastKey else "" }
            if (key.isNotEmpty()) {
                tracker.setRemaining(key, (remaining * 1000.0).toLong(), now)
                synced++
                diagnostics.event("sync item=$key remaining=$remaining")
            }
            return
        }
        if (plain.contains(NO_MANA) && lastKey.isNotEmpty() && now - lastStartMs < CONFIRM_WINDOW_MS) {
            tracker.cancel(lastKey)
            cancelled++
            diagnostics.event("cancel item=$lastKey reason=no-mana")
            lastKey = ""
        }
    }

    private fun itemKey(stack: ItemStack): String =
        ItemBackgrounds.info(stack).skyblockId.ifEmpty { SkyblockItems.skyBlockId(stack) }

    private fun totalMs(seconds: Double): Long {
        val multiplier = if (mageReduction.value() && DungeonContextTracker.inDungeon()) mageMultiplier() else 1.0
        return (seconds * 1000.0 * multiplier).toLong()
    }

    private fun startFor(key: String, held: ItemStack, ability: String, seconds: Double): Boolean {
        if (!tracker.start(key, ability, totalMs(seconds), nowMs())) {
            ignoredRunning++
            return false
        }
        started++
        icons[key] = held.copyWithCount(1)
        return true
    }

    private fun count(reason: String) {
        drops.merge(reason, 1L, Long::plus)
        if (drops[reason] == 1L) diagnostics.event("drop reason=$reason first=true")
    }

    /** Draws the countdown or ready check at the bottom right of an item; [x] and [y] are the item's top-left. */
    fun drawOnItem(graphics: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int) {
        if (!isEnabled() || stack.isEmpty || !onItemMode() || tracker.size() == 0) return
        val key = itemKey(stack)
        if (key.isEmpty()) return
        val reading = tracker.reading(key, nowMs(), checkMs()) ?: return
        val text = if (reading.ready) READY_GLYPH else ItemCooldownTracker.format(reading.remainingMs)
        val color = if (reading.ready) READY_COLOR else COOLDOWN_COLOR
        val font = Minecraft.getInstance().font
        val scale = ITEM_TEXT_SCALE
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x + 16f - font.width(text) * scale, y + 16f - font.lineHeight * scale)
        pose.scale(scale, scale)
        graphics.text(font, text, 0, 0, color, true)
        pose.popMatrix()
    }

    private fun onItemMode(): Boolean = mode.value() == "ON_ITEM" || mode.value() == "BOTH"

    private fun boxMode(): Boolean = mode.value() == "BOX" || mode.value() == "BOTH"

    private fun checkMs(): Long = if (readyCheck.value()) checkSeconds.intValue() * 1000L else -1L

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** The Mage factor from the tab list, refreshed about once a second. */
    private fun mageMultiplier(): Double {
        val tick = diagnostics.tick
        if (multiplierTick != Long.MIN_VALUE && tick - multiplierTick < 20L) return cachedMultiplier
        multiplierTick = tick
        val client = Minecraft.getInstance()
        val rows = DungeonClassTab.parseAll(DungeonAssistModule.scoreTabLines(client))
        cachedMultiplier = DungeonClassTab.mageCooldownMultiplier(rows, client.player?.name?.string)
        return cachedMultiplier
    }

    private fun boxRows(): List<ItemCooldownTracker.Reading> = tracker.readings(nowMs(), checkMs())

    // --- HudElement ---------------------------------------------------------------------------------------------

    override fun id(): String = "item_cooldowns"

    override fun displayName(): String = "Item Cooldowns"

    override fun visible(): Boolean = isEnabled() && boxMode() && boxRows().isNotEmpty()

    override fun previewMode(): String = if (boxRows().isEmpty()) "sample" else "live"

    private fun sampleRows(): List<ItemCooldownTracker.Reading> = listOf(
        ItemCooldownTracker.Reading("ASPECT_OF_THE_VOID", "Instant Transmission", 4_200L, 5_000L, false),
        ItemCooldownTracker.Reading("GYROKINETIC_WAND", "Gravity Storm", 0L, 30_000L, true),
    )

    private fun displayRows(): List<ItemCooldownTracker.Reading> = boxRows().ifEmpty { sampleRows() }

    override fun width(font: Font?): Int =
        ICON_SIZE + ICON_GAP + (displayRows().maxOfOrNull { font!!.width(rowText(it)) } ?: 1)

    override fun height(font: Font?): Int = maxOf(1, displayRows().size * ROW_HEIGHT)

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (boxMode()) drawRows(graphics, font, x, y, boxRows())
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) =
        drawRows(graphics, font, x, y, displayRows())

    private fun rowText(reading: ItemCooldownTracker.Reading): String =
        if (reading.ready) READY_GLYPH else ItemCooldownTracker.format(reading.remainingMs) + "s"

    private fun drawRows(
        graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, rows: List<ItemCooldownTracker.Reading>,
    ) {
        for ((index, row) in rows.withIndex()) {
            val rowY = y + index * ROW_HEIGHT
            graphics.item(icons[row.key] ?: ItemStack(Items.BLAZE_ROD), x, rowY)
            graphics.text(
                font, Component.literal(rowText(row)), x + ICON_SIZE + ICON_GAP,
                rowY + (ICON_SIZE - font.lineHeight) / 2 + 1, if (row.ready) READY_COLOR else COOLDOWN_COLOR,
            )
        }
    }

    override fun previewLines(font: Font, width: Int): List<Component> = listOf(
        Component.literal("Mode: ${mode.displayValue()}"),
        Component.literal("Instant Transmission · 4.2s"),
        Component.literal("Gravity Storm · ready"),
    )

    companion object {
        private val spec = ModuleSettingsSpec.of("Item Cooldowns")

        private const val CONFIRM_WINDOW_MS = 2000L
        private const val MAX_SAMPLES = 5
        private const val NO_MANA = "You do not have enough mana"
        private const val READY_GLYPH = "✔"
        private const val READY_COLOR = 0xFF55FF55.toInt()
        private const val COOLDOWN_COLOR = 0xFFFF5555.toInt()
        private const val ITEM_TEXT_SCALE = 0.5f
        private const val ICON_SIZE = 16
        private const val ICON_GAP = 3
        private const val ROW_HEIGHT = 17

        @JvmField
        val INSTANCE = ItemCooldownsModule()
    }
}
