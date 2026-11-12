package geiler.addons.client.command

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.StartDoorAnchorCaptureRules
import geiler.addons.client.module.impl.DungeonDoorBlockReplacementModule
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/** Temporary, client-only two-corner capture for diagnosing the physical Start-door panel. */
object StartDoorAnchorCaptureCommand {
    private val diagnosticBudget = StartDoorAnchorCaptureRules.DiagnosticBudget()
    private var diagnosticRunGeneration = Long.MIN_VALUE
    private var pending: PendingMark? = null

    @JvmStatic
    fun mark(): Int {
        val client = Minecraft.getInstance()
        if (!client.isSameThread()) {
            client.execute { announce(client, markOnClientThread(client)) }
            return 1
        }
        return announce(client, markOnClientThread(client))
    }

    @JvmStatic
    fun cancel(): Int {
        val client = Minecraft.getInstance()
        if (!client.isSameThread()) {
            client.execute { announce(client, cancelOnClientThread(client)) }
            return 1
        }
        return announce(client, cancelOnClientThread(client))
    }

    @JvmStatic
    fun usage(): Int {
        val client = Minecraft.getInstance()
        if (client.gui != null) {
            client.gui.hud.getChat().addClientSystemMessage(
                Component.literal(
                    "[GeilerAddons] Use /ga startdoor mark on two opposite chiseled-door corners; /ga startdoor cancel clears capture.",
                ),
            )
        }
        return 1
    }

    private fun announce(client: Minecraft, result: CaptureResult): Int {
        if (client.gui != null) {
            client.gui.hud.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] ${result.message}"))
        }
        return if (result.success) 1 else 0
    }

    private fun markOnClientThread(client: Minecraft): CaptureResult {
        if (!client.isSameThread()) {
            return CaptureResult(false, "Start-door capture must run on the client thread.")
        }
        val level = client.level
        val runGeneration = DungeonDoorTracker.dungeonSessionGeneration()
        val frame = DungeonDoorTracker.startDoorCaptureFrame(level)
        val frameSummary = frame.diagnosticSummary ?: "frame-summary-unavailable"
        if (!DungeonContextTracker.inDungeon()) {
            return reject(level, runGeneration, "dungeon-context-inactive", frameSummary)
        }
        if (!frame.ready) {
            return reject(level, frame.runGeneration, frame.rejectionReason ?: "frame-not-ready", frameSummary)
        }

        if (pending != null && !pending!!.matches(level, frame)) {
            val stale = pending!!.summary()
            pending = null
            return reject(
                level,
                frame.runGeneration,
                "first-mark-belongs-to-another-run-or-map",
                "discardedFirstMark={$stale}",
                frameSummary,
            )
        }

        val hitResult = client.hitResult
        if (level == null || hitResult !is BlockHitResult || hitResult.type != HitResult.Type.BLOCK) {
            return reject(level, frame.runGeneration, "crosshair-has-no-block-target", frameSummary)
        }
        val hit = hitResult
        val position = hit.blockPos.immutable()
        if (!level.hasChunkAt(position)) {
            return reject(
                level,
                frame.runGeneration,
                "target-chunk-not-loaded position=${position.toShortString()}",
                frameSummary,
            )
        }
        val authoritative = DungeonDoorBlockOverlayController.authoritativeState(level, position)
        if (authoritative == null) {
            return reject(
                level,
                frame.runGeneration,
                "authoritative-target-state-unavailable position=${position.toShortString()}",
                frameSummary,
            )
        }
        val authoritativeId = blockId(authoritative)
        val observedId = blockId(level.getBlockState(position))
        if (!StartDoorAnchorCaptureRules.supportedSource(authoritativeId)) {
            return rejectUnsupportedSource(
                level,
                frame.runGeneration,
                position,
                authoritativeId,
                observedId,
                frameSummary,
            )
        }

        val mark = CapturedMark(position, authoritativeId, observedId)
        val first = pending
        if (first == null) {
            pending = PendingMark(level, frame, mark)
            log(
                level,
                frame.runGeneration,
                "MARK 1/2 pending firstPos=${position.toShortString()} firstSource=$authoritativeId " +
                    "localObserved=$observedId frame={${frame.diagnosticSummary}}",
            )
            return CaptureResult(
                true,
                "Start-door corner 1/2 recorded at ${position.toShortString()} ($authoritativeId). " +
                    "Target the opposite corner and run /ga startdoor mark again.",
            )
        }
        if (first.mark.position == position) {
            return reject(
                level,
                frame.runGeneration,
                "same-point; target-the-opposite-corner position=${position.toShortString()}",
                frameSummary,
            )
        }

        val bounds = StartDoorAnchorCaptureRules.bounds(first.mark.position, position)
            ?: return reject(level, frame.runGeneration, "invalid-or-identical-corners", frameSummary)
        val relation = StartDoorAnchorCaptureRules.gridRelation(
            bounds,
            frame.entranceBaseX,
            frame.entranceBaseZ,
        )
        pending = null
        val evidence = "COMPLETE firstPos=${first.mark.position.toShortString()} " +
            "firstSource=${first.mark.authoritativeId} firstLocalObserved=${first.mark.observedId} " +
            "secondPos=${position.toShortString()} secondSource=$authoritativeId secondLocalObserved=$observedId " +
            "boundsInclusive=x[${bounds.minX}..${bounds.maxX}],y[${bounds.minY}..${bounds.maxY}]," +
            "z[${bounds.minZ}..${bounds.maxZ}] dimensions=${bounds.width()}x${bounds.height()}x${bounds.depth()} " +
            "EntranceBase=${frame.entranceBaseX},${frame.entranceBaseZ} " +
            "relativeToEntrance={${relation.summary()}} frame={${frame.diagnosticSummary}}"
        log(level, frame.runGeneration, evidence)
        return CaptureResult(
            true,
            "Start-door capture complete: ${bounds.width()}x${bounds.height()}x${bounds.depth()} blocks, " +
                "${relation.orientation()}. Evidence was added to Glass_Doors.log.",
        )
    }

    private fun cancelOnClientThread(client: Minecraft): CaptureResult {
        if (!client.isSameThread()) {
            return CaptureResult(false, "Start-door capture reset must run on the client thread.")
        }
        val level = client.level
        val runGeneration = DungeonDoorTracker.dungeonSessionGeneration()
        val discarded = pending?.summary() ?: "none"
        pending = null
        flushSuppressed(level)
        log(level, runGeneration, "RESET pendingMark=$discarded")
        return CaptureResult(
            true,
            if (discarded == "none") {
                "Start-door capture cleared; no corner was pending."
            } else {
                "Start-door capture cancelled; pending corner cleared."
            },
        )
    }

    private fun reject(level: ClientLevel?, runGeneration: Long, reason: String?, frame: String): CaptureResult =
        reject(level, runGeneration, reason, null, frame)

    private fun reject(
        level: ClientLevel?,
        runGeneration: Long,
        reason: String?,
        detail: String?,
        frame: String,
    ): CaptureResult {
        val safeReason = reason ?: "unknown"
        log(level, runGeneration, "REJECT reason=$safeReason${detail?.let { " $it" } ?: ""} frame={$frame}")
        return CaptureResult(false, "Start-door capture rejected: $safeReason.")
    }

    private fun rejectUnsupportedSource(
        level: ClientLevel?,
        runGeneration: Long,
        position: BlockPos,
        authoritativeId: String,
        observedId: String,
        frame: String,
    ): CaptureResult {
        val reason = "unsupported-source position=${position.toShortString()} " +
            "clickedBlock=$observedId authoritative=$authoritativeId"
        log(level, runGeneration, "REJECT reason=$reason frame={$frame}")
        return CaptureResult(
            false,
            "Start-door mark ignored: clicked block $observedId (authoritative source $authoritativeId) is " +
                "unsupported. Manual marks are diagnostic-only and do not select replacement targets.",
        )
    }

    private fun log(level: ClientLevel?, runGeneration: Long, details: String) {
        if (runGeneration != diagnosticRunGeneration) {
            val previousRun = diagnosticRunGeneration
            val suppressed = diagnosticBudget.switchRun(runGeneration)
            if (suppressed > 0) {
                DungeonDoorBlockReplacementModule.recordStartDoorCaptureDiagnostic(
                    level,
                    "DETAILS_SUPPRESSED previousRun=$previousRun count=$suppressed " +
                        "limit=${StartDoorAnchorCaptureRules.MAX_DIAGNOSTIC_EVENTS_PER_RUN}",
                )
            }
            diagnosticRunGeneration = runGeneration
        }
        if (diagnosticBudget.allowDetail()) {
            DungeonDoorBlockReplacementModule.recordStartDoorCaptureDiagnostic(level, details)
        }
    }

    private fun flushSuppressed(level: ClientLevel?) {
        val suppressed = diagnosticBudget.takeSuppressed()
        if (suppressed <= 0) return
        DungeonDoorBlockReplacementModule.recordStartDoorCaptureDiagnostic(
            level,
            "DETAILS_SUPPRESSED run=$diagnosticRunGeneration count=$suppressed " +
                "limit=${StartDoorAnchorCaptureRules.MAX_DIAGNOSTIC_EVENTS_PER_RUN}",
        )
    }

    private fun blockId(state: BlockState): String =
        BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "unknown"

    private data class CapturedMark(val position: BlockPos, val authoritativeId: String, val observedId: String)

    private data class PendingMark(
        val level: ClientLevel,
        val runGeneration: Long,
        val mapId: String,
        val entranceCell: Int,
        val mark: CapturedMark,
        val frameSummary: String,
    ) {
        constructor(level: ClientLevel, frame: DungeonDoorTracker.StartDoorCaptureFrame, mark: CapturedMark) : this(
            level,
            frame.runGeneration,
            frame.mapId ?: "unknown",
            frame.entranceCell,
            mark,
            frame.diagnosticSummary ?: "frame-summary-unavailable",
        )

        fun matches(currentLevel: ClientLevel?, frame: DungeonDoorTracker.StartDoorCaptureFrame): Boolean =
            StartDoorAnchorCaptureRules.sameSession(
                level,
                runGeneration,
                mapId,
                entranceCell,
                currentLevel,
                frame.runGeneration,
                frame.mapId,
                frame.entranceCell,
            )

        fun summary(): String = "pos=${mark.position.toShortString()} source=${mark.authoritativeId} " +
            "run=$runGeneration mapId=$mapId entranceCell=$entranceCell frame={$frameSummary}"
    }

    private data class CaptureResult(val success: Boolean, val message: String)
}
