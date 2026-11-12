package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.InventoryButtonOverlay
import geiler.addons.client.gui.InventoryButtonPickerScreen
import geiler.addons.client.gui.InventoryButtonTextScreen
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/** Local, player-inventory-only macro buttons. Layout is deliberately not part of macro transfer. */
class InventoryButtonsModule private constructor(private val editLayout: ModuleAction) : Module(
    "Inventory Buttons",
    "Place local macro buttons on the standard player inventory screen.",
    Category.VISUAL,
    editLayout,
) {
    enum class TextActionDispatchOutcome { EMPTY, NO_CONNECTION, COMMAND_SUBMITTED, CHAT_SUBMITTED }

    private val placements = ArrayList<InventoryButtonPlacement>()
    private var nextId = 0

    private var diagnosticTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var detailEventTick = Long.MIN_VALUE
    private var detailEventsThisTick = 0
    private var suppressedDetailEvents = 0
    private var lastPlacementDiagnostics = emptyMap<Int, PlacementDiagnosticState>()

    private data class PlacementDiagnosticState(
        val macroId: Int,
        val gridX: Int,
        val gridY: Int,
        val slotAligned: Boolean,
        val appearance: InventoryButtonPlacement.Appearance,
        val appearanceValueFingerprint: Int,
        val hasTextAction: Boolean,
        val textActionFingerprint: Int,
        val hasHoverTooltip: Boolean,
        val hoverTooltipFingerprint: Int,
    )

    override fun onEnable() {
        diagnosticTick = 0L
        lastStatusTick = diagnosticTick
        lastDiagnosticState = diagnosticState()
        detailEventTick = Long.MIN_VALUE
        detailEventsThisTick = 0
        suppressedDetailEvents = 0
        lastPlacementDiagnostics = placementDiagnosticSnapshot()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        recordDiagnostic("FEATURE ENABLED $lastDiagnosticState")
    }

    override fun onDisable() {
        recordDiagnostic(
            "FEATURE DISABLED ${diagnosticState()} suppressedEvents=$suppressedDetailEvents",
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), false)
        lastDiagnosticState = ""
        lastStatusTick = Long.MIN_VALUE
        suppressedDetailEvents = 0
        lastPlacementDiagnostics = emptyMap()
    }

    /** Immutable point-in-time view, matching the former List.copyOf Java contract. */
    fun placements(): List<InventoryButtonPlacement> =
        Collections.unmodifiableList(ArrayList(placements))

    fun placement(id: Int): InventoryButtonPlacement? =
        placements.firstOrNull { it.id() == id }

    internal fun recordTextActionDispatch(
        placementId: Int,
        kind: InventoryButtonTextAction.Kind?,
        actionLength: Int,
        outcome: TextActionDispatchOutcome,
    ) {
        recordEvent(formatTextActionDispatchDiagnostic(placementId, kind, actionLength, outcome))
    }

    fun create(
        macroId: Int,
        gridX: Int,
        gridY: Int,
        appearance: InventoryButtonPlacement.Appearance?,
        value: String?,
    ): InventoryButtonPlacement {
        val placement = InventoryButtonPlacement(nextId++)
        placement.setMacroId(macroId)
        placement.setGrid(gridX, gridY)
        placement.setAppearance(appearance, value)
        placements.add(placement)
        ModConfig.markDirty()
        recordEvent(
            "PLACEMENT CREATED id=${placement.id()} macroId=${placement.macroId()} " +
                "gridX=${placement.gridX()} gridY=${placement.gridY()} " +
                "appearance=${placement.appearance().name} textAction=${placement.hasTextAction()}",
        )
        return placement
    }

    fun createTextAction(
        action: String?,
        gridX: Int,
        gridY: Int,
        appearance: InventoryButtonPlacement.Appearance?,
        value: String?,
    ): InventoryButtonPlacement {
        val placement = create(-1, gridX, gridY, appearance, value)
        placement.setTextAction(action)
        ModConfig.markDirty()
        recordEvent(
            "TEXT ACTION SET id=${placement.id()} present=${placement.hasTextAction()}",
        )
        return placement
    }

    fun restore(restored: List<InventoryButtonPlacement?>?) {
        placements.clear()
        val ids = HashSet<Int>()
        var rejectedNull = 0
        var rejectedDuplicate = 0
        if (restored != null) {
            for (placement in restored) {
                if (placement == null) {
                    rejectedNull++
                    continue
                }
                if (!ids.add(placement.id())) {
                    rejectedDuplicate++
                    continue
                }
                placements.add(InventoryButtonPlacement(placement))
            }
        }
        nextId = 0
        for (placement in placements) nextId = maxOf(nextId, placement.id() + 1)
        recordEvent(
            "RESTORE candidates=${restored?.size ?: 0} accepted=${placements.size} " +
                "rejectedNull=$rejectedNull rejectedDuplicate=$rejectedDuplicate nextId=$nextId",
        )
    }

    fun remove(id: Int) {
        val removed = placements.removeAll { it.id() == id }
        if (removed) {
            ModConfig.markDirty()
            recordEvent("PLACEMENT REMOVED id=$id")
        }
    }

    fun unassignMacro(macroId: Int) {
        var changed = 0
        for (placement in placements) {
            if (placement.macroId() == macroId) {
                placement.setMacroId(-1)
                changed++
            }
        }
        if (changed > 0) {
            ModConfig.markDirty()
            recordEvent("MACRO UNASSIGNED macroId=$macroId placements=$changed")
        }
    }

    /** Accepts only a single PNG filename within the dedicated user icon directory. */
    fun isPngIconAllowed(fileName: String?): Boolean {
        if (fileName == null || fileName.isBlank()) return false
        val name = try {
            Path.of(fileName)
        } catch (_: RuntimeException) {
            return false
        }
        if (name.isAbsolute || name.nameCount != 1 || name.fileName.toString() != fileName) return false
        return fileName.lowercase(Locale.ROOT).endsWith(".png")
    }

    fun iconDirectory(): Path =
        FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("inventory-button-icons")

    override fun groups(): List<SettingGroup> {
        val groups = ArrayList<SettingGroup>(placements.size + 1)
        groups.add(
            SettingGroup(
                null,
                ModuleAction(
                    "Edit Layout · ${placements.size} buttons",
                    editLayout.description,
                    editLayout.onClick,
                ),
            ),
        )
        for (placement in placements) {
            val macro: MacroDefinition? = MacrosModule.INSTANCE.macro(placement.macroId())
            val target = if (placement.hasTextAction()) "Text Action" else macro?.name() ?: "Unassigned"
            val edit = ModuleAction(
                "Edit in Inventory",
                "Open the layout editor with this button selected.",
                Runnable { openEditor(placement.id()) },
            )
            val assign = ModuleAction(
                "Choose Macro",
                "Bind this button to an existing macro.",
                Runnable {
                    val minecraft = Minecraft.getInstance()
                    val parent = minecraft.gui.screen()
                    minecraft.gui.setScreen(
                        InventoryButtonPickerScreen(parent, InventoryButtonPickerScreen.Mode.MACRO) { value ->
                            var parsed = true
                            try {
                                placement.setMacroId(Integer.parseInt(value))
                                placement.setTextAction(null)
                            } catch (_: NumberFormatException) {
                                parsed = false
                                placement.setMacroId(-1)
                            }
                            ModConfig.markDirty()
                            recordEvent(
                                "MACRO ASSIGNMENT id=${placement.id()} parsed=$parsed " +
                                    "macroId=${placement.macroId()} textAction=${placement.hasTextAction()}",
                            )
                        },
                    )
                },
            )
            val tooltip = ModuleAction(
                "Set Hover Tooltip",
                "Edit the custom hover text shown with macro status.",
                Runnable { openTooltipEditor(placement) },
            )
            val textAction = ModuleAction(
                "Set Text Action",
                "Send a command or chat message when this button is clicked.",
                Runnable { openTextActionEditor(placement) },
            )
            val clearTextAction = ModuleAction(
                "Clear Text Action",
                "Remove the direct command or chat action from this button.",
                Runnable {
                    placement.setTextAction(null)
                    ModConfig.markDirty()
                    recordEvent("TEXT ACTION CLEARED id=${placement.id()}")
                },
            )
            val unassign = ModuleAction(
                "Unassign Macro",
                "Keep this placement but clear its macro binding.",
                Runnable {
                    val previousMacroId = placement.macroId()
                    placement.setMacroId(-1)
                    ModConfig.markDirty()
                    recordEvent("MACRO UNASSIGNED macroId=$previousMacroId placements=1")
                },
            )
            val delete = ModuleAction(
                "Delete Button",
                "Remove this local placement.",
                Runnable { remove(placement.id()) },
            )
            groups.add(
                SettingGroup(
                    "Button ${placement.id()} · $target",
                    null,
                    true,
                    listOf<Setting>(edit, assign, textAction, clearTextAction, tooltip, unassign, delete),
                    emptyList(),
                    false,
                    "inventory-button:${placement.id()}",
                ),
            )
        }
        return groups
    }

    private fun openEditor() = openEditor(-1)

    private fun openEditor(placementId: Int) {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        if (player == null) {
            recordEvent("EDITOR OPEN result=no_player placementId=$placementId")
            return
        }
        val editorScreen = InventoryScreen(player)
        InventoryButtonOverlay.beginEditing(placementId, minecraft.gui.screen(), editorScreen)
        minecraft.gui.setScreen(editorScreen)
        recordEvent("EDITOR OPEN result=requested placementId=$placementId")
    }

    private fun openTooltipEditor(placement: InventoryButtonPlacement) {
        val minecraft = Minecraft.getInstance()
        val parent: Screen? = minecraft.gui.screen()
        minecraft.gui.setScreen(
            InventoryButtonTextScreen(
                parent,
                "Inventory Button Tooltip",
                "Shown above the macro name and eligibility when the button is hovered.",
                placement.hoverTooltip(),
            ) { value ->
                placement.setHoverTooltip(value)
                ModConfig.markDirty()
                recordEvent("TOOLTIP UPDATED id=${placement.id()} present=${placement.hoverTooltip().isNotEmpty()}")
            },
        )
        recordEvent("TEXT EDITOR OPEN kind=tooltip id=${placement.id()}")
    }

    private fun openTextActionEditor(placement: InventoryButtonPlacement) {
        val minecraft = Minecraft.getInstance()
        val parent: Screen? = minecraft.gui.screen()
        minecraft.gui.setScreen(
            InventoryButtonTextScreen(
                parent,
                "Inventory Button Action",
                "Start with / to send a command; otherwise the text is sent as a chat message.",
                placement.textAction(),
            ) { value ->
                placement.setMacroId(-1)
                placement.setTextAction(value)
                ModConfig.markDirty()
                recordEvent(
                    "TEXT ACTION UPDATED id=${placement.id()} present=${placement.hasTextAction()}",
                )
            },
        )
        recordEvent("TEXT EDITOR OPEN kind=action id=${placement.id()}")
    }

    fun tick() {
        InventoryButtonOverlay.tick()
        tickDiagnostics()
    }

    private fun tickDiagnostics() {
        if (!isEnabled()) return
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        if (diagnosticTick % DIAGNOSTIC_SAMPLE_INTERVAL_TICKS != 0L) return

        val placementSnapshot = placementDiagnosticSnapshot()
        recordPlacementChanges(placementSnapshot)
        lastPlacementDiagnostics = placementSnapshot

        val state = diagnosticState()
        val changed = state != lastDiagnosticState
        val heartbeat = lastStatusTick == Long.MIN_VALUE ||
            diagnosticTick - lastStatusTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat && suppressedDetailEvents == 0) return

        recordDiagnostic(
            "STATUS $state changed=$changed heartbeat=$heartbeat " +
                "suppressedEvents=$suppressedDetailEvents sampleTicks=$DIAGNOSTIC_SAMPLE_INTERVAL_TICKS " +
                "heartbeatTicks=$DIAGNOSTIC_HEARTBEAT_TICKS",
        )
        lastDiagnosticState = state
        lastStatusTick = diagnosticTick
        suppressedDetailEvents = 0
    }

    /** This trace contains counts and fixed enums only; it excludes all action, tooltip, and icon text. */
    private fun diagnosticState(): String {
        var macroAssignments = 0
        var textActions = 0
        var pngAppearances = 0
        var overflowHash = 1
        for (index in placements.indices) {
            val placement = placements[index]
            if (placement.macroId() >= 0) macroAssignments++
            if (placement.hasTextAction()) textActions++
            if (placement.appearance() == InventoryButtonPlacement.Appearance.PNG) pngAppearances++
            if (index >= MAX_TRACKED_PLACEMENTS) {
                overflowHash = 31 * overflowHash + placement.id()
                overflowHash = 31 * overflowHash + placement.macroId()
                overflowHash = 31 * overflowHash + placement.gridX()
                overflowHash = 31 * overflowHash + placement.gridY()
                overflowHash = 31 * overflowHash + placement.slotAligned().hashCode()
                overflowHash = 31 * overflowHash + placement.appearance().ordinal
                overflowHash = 31 * overflowHash + placement.hasTextAction().hashCode()
                overflowHash = 31 * overflowHash + placement.hoverTooltip().isNotEmpty().hashCode()
            }
        }
        if (placements.size <= MAX_TRACKED_PLACEMENTS) overflowHash = 0
        val trackedPlacements = minOf(placements.size, MAX_TRACKED_PLACEMENTS)
        val omittedPlacements = maxOf(0, placements.size - trackedPlacements)
        return "enabled=${isEnabled()} placements=${placements.size} nextId=$nextId " +
            "macroAssignments=$macroAssignments textActions=$textActions pngAppearances=$pngAppearances " +
            "trackedPlacements=$trackedPlacements omittedPlacements=$omittedPlacements overflowStateHash=$overflowHash"
    }

    private fun placementDiagnosticSnapshot(): Map<Int, PlacementDiagnosticState> {
        val snapshot = LinkedHashMap<Int, PlacementDiagnosticState>(
            minOf(placements.size, MAX_TRACKED_PLACEMENTS),
        )
        for (placement in placements) {
            if (snapshot.size >= MAX_TRACKED_PLACEMENTS) break
            val tooltip = placement.hoverTooltip()
            val textAction = placement.textAction()
            snapshot[placement.id()] = PlacementDiagnosticState(
                placement.macroId(),
                placement.gridX(),
                placement.gridY(),
                placement.slotAligned(),
                placement.appearance(),
                placement.value().hashCode(),
                placement.hasTextAction(),
                textAction?.hashCode() ?: 0,
                tooltip.isNotEmpty(),
                tooltip.hashCode(),
            )
        }
        return snapshot
    }

    private fun recordPlacementChanges(current: Map<Int, PlacementDiagnosticState>) {
        for ((id, state) in current) {
            val previous = lastPlacementDiagnostics[id]
            if (previous == null) {
                recordEvent(
                    "PLACEMENT OBSERVED id=$id macroId=${state.macroId} gridX=${state.gridX} " +
                        "gridY=${state.gridY} slotAligned=${state.slotAligned} " +
                        "appearance=${state.appearance.name} textActionPresent=${state.hasTextAction} " +
                        "hoverTooltipPresent=${state.hasHoverTooltip}",
                )
                continue
            }
            val changes = ArrayList<String>(8)
            if (previous.gridX != state.gridX || previous.gridY != state.gridY) {
                changes.add("grid=${previous.gridX},${previous.gridY}->${state.gridX},${state.gridY}")
            }
            if (previous.macroId != state.macroId) {
                changes.add("macroId=${previous.macroId}->${state.macroId}")
            }
            if (previous.slotAligned != state.slotAligned) changes.add("slotAligned=${state.slotAligned}")
            if (previous.appearance != state.appearance) {
                changes.add("appearance=${previous.appearance.name}->${state.appearance.name}")
            }
            if (previous.appearanceValueFingerprint != state.appearanceValueFingerprint) {
                changes.add("appearanceValueChanged=true")
            }
            if (previous.hasTextAction != state.hasTextAction) {
                changes.add("textActionPresent=${state.hasTextAction}")
            } else if (previous.textActionFingerprint != state.textActionFingerprint) {
                changes.add("textActionChanged=true")
            }
            if (previous.hasHoverTooltip != state.hasHoverTooltip) {
                changes.add("hoverTooltipPresent=${state.hasHoverTooltip}")
            } else if (previous.hoverTooltipFingerprint != state.hoverTooltipFingerprint) {
                changes.add("hoverTooltipChanged=true")
            }
            if (changes.isNotEmpty()) recordEvent("PLACEMENT UPDATED id=$id ${changes.joinToString(" ")}")
        }
    }

    private fun recordEvent(event: String) {
        if (!isEnabled()) return
        if (detailEventTick != diagnosticTick) {
            detailEventTick = diagnosticTick
            detailEventsThisTick = 0
        }
        if (detailEventsThisTick >= MAX_DETAIL_EVENTS_PER_TICK) {
            if (suppressedDetailEvents < MAX_SUPPRESSED_EVENTS) suppressedDetailEvents++
            return
        }
        detailEventsThisTick++
        val suppressed = suppressedDetailEvents
        suppressedDetailEvents = 0
        val suppression = if (suppressed > 0) " suppressed=$suppressed" else ""
        recordDiagnostic("EVENT $event$suppression")
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.VISUAL, name(), diagnosticTick, line.take(MAX_DIAGNOSTIC_LINE_CHARS))
    }

    companion object {
        private const val DIAGNOSTIC_SAMPLE_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DETAIL_EVENTS_PER_TICK = 16
        private const val MAX_SUPPRESSED_EVENTS = 1_000_000
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 1_024
        private const val MAX_TRACKED_PLACEMENTS = 128
        private const val MAX_TEXT_ACTION_CHARS = 256

        @JvmStatic
        fun formatTextActionDispatchDiagnostic(
            placementId: Int,
            kind: InventoryButtonTextAction.Kind?,
            actionLength: Int,
            outcome: TextActionDispatchOutcome,
        ): String =
            "TEXT ACTION DISPATCH id=$placementId kind=${kind?.name ?: "NONE"} " +
                "chars=${actionLength.coerceIn(0, MAX_TEXT_ACTION_CHARS)} outcome=${outcome.name}"

        @JvmField
        val INSTANCE = InventoryButtonsModule(
            ModuleAction(
                "Edit Inventory Layout",
                "Open the player inventory and place or move 18×18 macro buttons.",
                Runnable { openEditorFromAction() },
            ),
        )

        private fun openEditorFromAction() {
            INSTANCE.openEditor()
        }
    }
}
