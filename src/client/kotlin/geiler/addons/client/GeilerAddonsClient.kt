package geiler.addons.client

import geiler.addons.GeilerAddons
import geiler.addons.client.chat.ChatChannelsLocalStore
import geiler.addons.client.chat.ChatCommandReplyBackend
import geiler.addons.client.command.GeilerAddonsCommand
import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.module.AreaKillerModule
import geiler.addons.client.macros.module.PathfinderModule
import geiler.addons.client.macros.module.EtherwarpModule
import geiler.addons.client.macros.module.HumanRecorderModule
import geiler.addons.client.macros.module.EtherwarpSimModule
import geiler.addons.client.macros.module.JumpBoostModule
import geiler.addons.client.macros.module.OneTapModule
import geiler.addons.client.macros.module.RotationEngineModule
import geiler.addons.client.macros.module.SpeedSimModule
import geiler.addons.client.macros.sim.SimModules
import geiler.addons.client.macros.sim.SimRuntime
import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.DungeonGuideChatDispatch
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.dungeon.DungeonQueueFloorTracker
import geiler.addons.client.dungeon.DungeonRoomTracker
import geiler.addons.client.dungeon.DungeonSplitPersonalBestStore
import geiler.addons.client.dungeon.DungeonStatsService
import geiler.addons.client.dungeon.odin.DungeonLividSnapshotGate
import geiler.addons.client.dungeon.odin.DungeonLividTracker
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerMap
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerRoster
import geiler.addons.client.dungeon.odin.SpiritLeapRoster
import geiler.addons.client.hud.HudManager
import geiler.addons.client.hud.MacroTitleOverlay
import geiler.addons.client.inventory.PlayerInventoryControls
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.location.SafariBiome
import geiler.addons.client.location.TorrhusPresence
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleManager
import geiler.addons.client.module.impl.AutoExperimentsModule
import geiler.addons.client.module.impl.AutoKickModule
import geiler.addons.client.module.impl.BlockEspModule
import geiler.addons.client.module.impl.BoxDoorsModule
import geiler.addons.client.module.impl.ChatChannelsModule
import geiler.addons.client.module.impl.ChatModule
import geiler.addons.client.module.impl.CropFeverTimerModule
import geiler.addons.client.module.impl.DebugModule
import geiler.addons.client.module.impl.DungeonBloodCampModule
import geiler.addons.client.module.impl.DungeonDoorBlockReplacementModule
import geiler.addons.client.module.impl.DungeonHelperModule
import geiler.addons.client.module.impl.DungeonInvincibilityTimerModule
import geiler.addons.client.module.impl.DungeonKeyPickupModule
import geiler.addons.client.module.impl.DungeonLividSolverModule
import geiler.addons.client.module.impl.DungeonBossEspModule
import geiler.addons.client.module.impl.AliasesModule
import geiler.addons.client.window.WindowIconController
import geiler.addons.client.module.impl.DungeonClassEspModule
import geiler.addons.client.module.impl.InstanceRequeueModule
import geiler.addons.client.module.impl.TrailModule
import geiler.addons.client.module.impl.ItemRarityBackgroundsModule
import geiler.addons.client.module.impl.NoCursorResetModule
import geiler.addons.client.module.impl.NoLoadingScreenModule
import geiler.addons.client.module.impl.DungeonMobEspModule
import geiler.addons.client.module.impl.DungeonOdinAssistModule
import geiler.addons.client.module.impl.DungeonPuzzleSolversModule
import geiler.addons.client.module.impl.DungeonRoomAlertModule
import geiler.addons.client.module.impl.DungeonRoomOverlayModule
import geiler.addons.client.module.impl.DungeonRunSplitsModule
import geiler.addons.client.module.impl.DungeonScorerModule
import geiler.addons.client.module.impl.DungeonTerminalStatsModule
import geiler.addons.client.module.impl.DungeonTerminalTitlesModule
import geiler.addons.client.module.impl.ExperimentSolverModule
import geiler.addons.client.module.impl.GardenPlotBordersModule
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.BossBarPercentModule
import geiler.addons.client.module.impl.HideAllParticlesModule
import geiler.addons.client.module.impl.HideF7BossSoundsModule
import geiler.addons.client.module.impl.HidePlayersModule
import geiler.addons.client.module.impl.AnimationFixModule
import geiler.addons.client.module.impl.DisconnectFixModule
import geiler.addons.client.module.impl.DoubleUseFixModule
import geiler.addons.client.module.impl.GyroWandModule
import geiler.addons.client.module.impl.ItemCooldownsModule
import geiler.addons.client.module.impl.LoadoutKeybindsModule
import geiler.addons.client.module.impl.MimicAnnouncerModule
import geiler.addons.client.waypoint.ChatWaypointsModule
import geiler.addons.client.waypoint.WaypointsModule
import geiler.addons.client.dungeon.f7.F7DeviceCallbacks
import geiler.addons.client.module.impl.ArrowAlignModule
import geiler.addons.client.module.impl.CroesusModule
import geiler.addons.client.module.impl.GhostVisionModule
import geiler.addons.client.module.impl.SpeedDisplayModule
import geiler.addons.client.module.impl.BlessingDisplayModule
import geiler.addons.client.module.impl.MelodyMessageModule
import geiler.addons.client.module.impl.SimonSaysModule
import geiler.addons.client.module.impl.TerminalEspModule
import geiler.addons.client.module.impl.TickTimersModule
import geiler.addons.client.module.impl.RelicTimerModule
import geiler.addons.client.module.impl.FireFreezeTimerModule
import geiler.addons.client.module.impl.FishingBaitModule
import geiler.addons.client.module.impl.FishingBobberTimerModule
import geiler.addons.client.module.impl.FishingHookDisplayModule
import geiler.addons.client.module.impl.CorpseFinderModule
import geiler.addons.client.module.impl.GeyserFishingModule
import geiler.addons.client.module.impl.CrystalHollowsHelperModule
import geiler.addons.client.module.impl.GlaciteGuideModule
import geiler.addons.client.module.impl.GoblinHighlighterModule
import geiler.addons.client.module.impl.HeatAndColdModule
import geiler.addons.client.module.impl.MiningEventsModule
import geiler.addons.client.module.impl.MiningSolversModule
import geiler.addons.client.module.impl.HotspotRadarModule
import geiler.addons.client.module.impl.HotmTreeHelperModule
import geiler.addons.client.module.impl.MiningCommissionsModule
import geiler.addons.client.module.impl.MetalDetectorModule
import geiler.addons.client.module.impl.MineshaftTimerModule
import geiler.addons.client.module.impl.MiningPowderModule
import geiler.addons.client.module.impl.PowderChestModule
import geiler.addons.client.module.impl.LavaReplacementModule
import geiler.addons.client.module.impl.SeaCreatureAlertModule
import geiler.addons.client.module.impl.WitherDragonsModule
import geiler.addons.client.module.impl.KingRelicModule
import geiler.addons.client.module.impl.DragonStackAimerModule
import geiler.addons.client.module.impl.CompactDamageModule
import geiler.addons.client.module.impl.TerminalSolverModule
import geiler.addons.client.module.impl.PriceDisplayModule
import geiler.addons.client.module.impl.TerminalSimulatorModule
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import geiler.addons.client.module.impl.TrappedChestEspModule
import geiler.addons.client.module.impl.NoRenderModule
import geiler.addons.client.module.impl.RagnarockModule
import geiler.addons.client.module.impl.SpringBootsModule
import geiler.addons.client.module.impl.TerracottaTimerModule
import geiler.addons.client.module.impl.WardrobeKeybindsModule
import geiler.addons.client.module.impl.SnapTapModule
import geiler.addons.client.module.impl.AutoSprintModule
import geiler.addons.client.module.impl.BlockOverlayModule
import geiler.addons.client.module.impl.FreecamModule
import geiler.addons.client.module.impl.FullbrightModule
import geiler.addons.client.module.impl.NoFogModule
import geiler.addons.client.module.impl.ScrollableTooltipModule
import geiler.addons.client.module.impl.HideyhoFinderModule
import geiler.addons.client.module.impl.I4HelperModule
import geiler.addons.client.module.impl.InventoryButtonsModule
import geiler.addons.client.module.impl.ItemAnimationsModule
import geiler.addons.client.module.impl.LobbyTargetWatchModule
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.MobHighlightModule
import geiler.addons.client.module.impl.PartyCommandsModule
import geiler.addons.client.module.impl.PartyFinderStatsModule
import geiler.addons.client.module.impl.PestHighlighterModule
import geiler.addons.client.module.impl.PestTrackerModule
import geiler.addons.client.module.impl.PlayerInventoryControlsModule
import geiler.addons.client.module.impl.PlayerNamesModule
import geiler.addons.client.module.impl.PlayerSizeModule
import geiler.addons.client.module.impl.SafariFloorDropsModule
import geiler.addons.client.module.impl.ScoreboardModule
import geiler.addons.client.module.impl.SecretClickedModule
import geiler.addons.client.module.impl.SecretWaypointsModule
import geiler.addons.client.module.impl.SlayerTrackerModule
import geiler.addons.client.module.impl.SlayerAlertsModule
import geiler.addons.client.module.impl.SlayerBossHighlightModule
import geiler.addons.client.module.impl.EndermanSlayerModule
import geiler.addons.client.module.impl.BlazeSlayerModule
import geiler.addons.client.module.impl.VampireSlayerModule
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.module.impl.SlotIdsModule
import geiler.addons.client.module.impl.SparklingCritterModule
import geiler.addons.client.module.impl.SpiritLeapMenuModule
import geiler.addons.client.module.impl.SpiritLeapResourceReloadListener
import geiler.addons.client.module.impl.SystemClockHudModule
import geiler.addons.client.module.impl.ThirdPersonCameraModule
import geiler.addons.client.module.impl.TikiHelperModule
import geiler.addons.client.module.impl.TreeNotifierModule
import geiler.addons.client.module.impl.TreeTrackerModule
import geiler.addons.client.module.impl.UtilityTimerModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.scoreboard.ScoreboardBackend
import geiler.addons.client.scoreboard.ScoreboardHudElement
import geiler.addons.client.tree.ChatText
import geiler.addons.client.update.UpdateChecker
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

class GeilerAddonsClient : ClientModInitializer {
    private var reportedLogFailure: String? = null
    private var clientTick: Long = 0L

    override fun onInitializeClient() {
        GeilerAddonsCommand.register()
        SpiritLeapResourceReloadListener.register()
        // Register first so ModConfig can restore the global gate before loading module state.
        registerModules()
        SimRuntime.init()
        MacroRuntime.init()
        HudManager.register(TreeTrackerModule.INSTANCE, 0.01f, 0.10f)
        HudManager.register(DungeonHelperModule.INSTANCE, 0.50f, 0.03f)
        HudManager.register(DungeonRoomOverlayModule.INSTANCE, 0.77f, 0.55f)
        HudManager.register(DungeonRunSplitsModule.INSTANCE, 0.03f, 0.22f)
        HudManager.register(DungeonLividSolverModule.INSTANCE, 0.03f, 0.44f)
        HudManager.register(DungeonInvincibilityTimerModule.INSTANCE, 0.03f, 0.54f)
        HudManager.register(ItemCooldownsModule.INSTANCE, 0.03f, 0.74f)
        HudManager.register(SpringBootsModule.INSTANCE, 0.45f, 0.62f)
        HudManager.register(DungeonTerminalStatsModule.INSTANCE, 0.03f, 0.64f)
        HudManager.register(BlessingDisplayModule.INSTANCE, 0.03f, 0.58f)
        HudManager.register(CroesusModule.INSTANCE, 0.0f, 0.05f)
        HudManager.setScale(CroesusModule.INSTANCE, 0.75f)
        HudManager.register(SpeedDisplayModule.INSTANCE, 0.01f, 0.08f)
        HudManager.register(FishingBobberTimerModule.INSTANCE, 0.45f, 0.55f)
        HudManager.register(FishingBaitModule.INSTANCE, 0.45f, 0.60f)
        HudManager.register(MiningCommissionsModule.INSTANCE, Category.MINING, 0.02f, 0.30f)
        HudManager.register(MiningPowderModule.INSTANCE, Category.MINING, 0.02f, 0.45f)
        HudManager.register(PowderChestModule.INSTANCE, Category.MINING, 0.45f, 0.50f)
        HudManager.register(MineshaftTimerModule.INSTANCE, Category.MINING, 0.02f, 0.60f)
        HudManager.register(MiningEventsModule.INSTANCE, Category.MINING, 0.02f, 0.68f)
        HudManager.register(MiningSolversModule.INSTANCE, Category.MINING, 0.02f, 0.76f)
        HudManager.register(CroesusModule.INSTANCE.chestCountHud, 0.03f, 0.52f)
        F7DeviceCallbacks.register()
        HudManager.register(PestTrackerModule.INSTANCE, 0.02f, 0.55f)
        HudManager.register(SlayerTrackerModule.INSTANCE, 0.02f, 0.72f)
        HudManager.register(CropFeverTimerModule.INSTANCE, 0.50f, 0.88f)
        HudManager.register(ScoreboardHudElement, Category.VISUAL, 0.98f, 0.30f)
        HudManager.register(SystemClockHudModule.INSTANCE, 0.01f, 0.03f)
        HudManager.register(UtilityTimerModule.INSTANCE, Category.MISCELLANEOUS, 0.50f, 0.82f)
        HudManager.register(SlotIdsModule.INSTANCE, 0.72f, 0.72f)
        HudManager.register(TickTimersModule.INSTANCE.goldorHud, Category.F7, 0.62f, 0.42f)
        HudManager.register(TickTimersModule.INSTANCE.padHud, Category.F7, 0.62f, 0.46f)
        HudManager.register(TickTimersModule.INSTANCE.lightningHud, Category.F7, 0.62f, 0.50f)
        HudManager.register(TickTimersModule.INSTANCE.pyHud, Category.F7, 0.62f, 0.54f)
        HudManager.register(TickTimersModule.INSTANCE.stormTickHud, Category.F7, 0.62f, 0.58f)
        HudManager.register(FireFreezeTimerModule.INSTANCE, Category.F7, 0.62f, 0.62f)
        HudManager.register(RelicTimerModule.INSTANCE, 0.46f, 0.40f)
        HudManager.register(WitherDragonsModule.INSTANCE.timerHudElement, Category.F7, 0.60f, 0.40f)
        HudManager.register(DragonStackAimerModule.INSTANCE.hudElement, Category.F7, 0.48f, 0.47f)
        // Must come after registration: this restores saved settings, HUD positions and gift counts.
        ModConfig.load()
        DungeonSplitPersonalBestStore.initialize()
        // Feature-local stores load after module config restores their enable/settings state.
        ChatChannelsLocalStore.instance()
        ChatModule.INSTANCE.initializeAfterConfig()
        PlayerInventoryControls.INSTANCE.settings()
        ScoreboardBackend.initialize()
        PlayerNamesModule.INSTANCE.tick()
        PlayerSizeModule.INSTANCE.tick()
        UpdateChecker.start()
        // Subscribe before joining a server; the official API owns channel registration.
        HypixelModApi.init()

        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            (!PartyCommandsModule.INSTANCE.isEnabled() || !PartyCommandsModule.handleLocalChatCommand(message)) &&
                !AliasesModule.INSTANCE.intercept(message, false)
        }
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            PriceDisplayModule.INSTANCE.addToTooltip(stack, lines)
        }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command -> !AliasesModule.INSTANCE.intercept(command, true) }
        ClientSendMessageEvents.COMMAND.register(ChatCommandReplyBackend::onOutgoingCommand)
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            val content = ChatText.plain(message.string).trim()
            DungeonContextTracker.onChatMessage(content)
            DungeonBloodCampModule.INSTANCE.onChatMessage(content, System.nanoTime(), clientTick)
            DungeonRoomOverlayModule.INSTANCE.onDungeonChatMessage(content)
            DungeonRoomOverlayModule.INSTANCE.onActionbar(content, overlay)
            PartyListBackend.onChatMessage(content)
            ChatCommandReplyBackend.onIncoming(content, overlay, clientTick)
            GardenPlotBordersModule.INSTANCE.onChatMessage(content)
            PartyFinderStatsModule.INSTANCE.onChatMessage(content)
            val guideFloor = DungeonContextTracker.currentFloor()
            DungeonGuideChatDispatch.dispatch(
                guideFloor,
                DungeonQueueFloorTracker.currentFloor(),
                content,
                { eventFloor, event -> DungeonHelperModule.INSTANCE.observePhaseOutput(eventFloor, event) },
                DungeonHelperModule.INSTANCE::onChatMessage,
            )
            BoxDoorsModule.INSTANCE.onChatMessage(content)
            DungeonKeyPickupModule.INSTANCE.onChatMessage(content)
            SecretWaypointsModule.INSTANCE.onChatMessage(content)
            PestTrackerModule.INSTANCE.onChatMessage(content)
            SlayerTrackerModule.INSTANCE.onChatMessage(content)
            CropFeverTimerModule.INSTANCE.onChatMessage(content)
            TickTimersModule.INSTANCE.onChatMessage(content)
            RelicTimerModule.INSTANCE.onChatMessage(content)
            FireFreezeTimerModule.INSTANCE.onChatMessage(content)
            WitherDragonsModule.INSTANCE.onChatMessage(content)
            DragonStackAimerModule.INSTANCE.onChatMessage(content)
            TerminalSolverModule.INSTANCE.onChatMessage(content)
            TerminalEspModule.INSTANCE.onChatMessage(content)
            if (!overlay) SeaCreatureAlertModule.INSTANCE.onChatMessage(content)
            MetalDetectorModule.INSTANCE.onMessage(content, overlay)
            if (!overlay) {
                GoblinHighlighterModule.INSTANCE.onChatMessage(content)
                CrystalHollowsHelperModule.INSTANCE.onChatMessage(content)
                MiningEventsModule.INSTANCE.onChatMessage(content)
                MiningSolversModule.INSTANCE.onChatMessage(content)
            }
            val odin = DungeonOdinAssistModule.INSTANCE
            odin.onChat(content, System.nanoTime(), clientTick)
            val proc = odin.takeProcAlert()
            if (proc != null) DungeonInvincibilityTimerModule.INSTANCE.onProc(proc)
            if (odin.takeLeapSound()) odin.playLeapSound()
            val partyAnnouncement = odin.takePartyAnnouncement()
            val player = Minecraft.getInstance().player
            if (!partyAnnouncement.isNullOrBlank() && player != null) {
                player.connection.sendCommand(partyAnnouncement)
            }
            InstanceRequeueModule.INSTANCE.onChatMessage(content)
            if (overlay) ItemCooldownsModule.INSTANCE.onActionBar(content)
            else {
                MimicAnnouncerModule.INSTANCE.onChatMessage(content)
                ChatWaypointsModule.INSTANCE.onChatMessage(content)
                CroesusModule.INSTANCE.onChatMessage(content)
                RagnarockModule.INSTANCE.onChatMessage(content)
                ItemCooldownsModule.INSTANCE.onChatMessage(content)
            }
            I4HelperModule.INSTANCE.onChatMessage(content)
            TikiHelperModule.INSTANCE.onChatMessage(content)
            TreeTrackerModule.INSTANCE.onChatMessage(content)
            MacroRunner.onChatMessage(content)
            // Observers receive normalized lines even for overlays; vanilla still owns overlay rendering.
            val suppress = TreeNotifierModule.INSTANCE.onChatMessage(content, message)
            overlay || !suppress
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Pause live feature mutation during profile transactions so periodic writers cannot race them.
            if (ConfigTransferService.isBusy()) {
                ChatCommandReplyBackend.clear()
                return@register
            }
            clientTick++
            ChatModule.INSTANCE.tick(clientTick)
            ThirdPersonCameraModule.INSTANCE.tick(clientTick)
            val dungeonMobEspEnabled = DungeonMobEspModule.INSTANCE.isEnabled()
                || DungeonBossEspModule.INSTANCE.isEnabled()
                || DungeonClassEspModule.INSTANCE.isEnabled()
            val puzzleGeometryConsumerEnabled = DungeonPuzzleSolversModule.INSTANCE.hasEnabledSolvers()
            val secretConsumerEnabled = SecretWaypointsModule.INSTANCE.isEnabled()
                || SecretClickedModule.INSTANCE.isEnabled()
            val mapGeometryConsumerEnabled = dungeonMobEspEnabled
                || BoxDoorsModule.INSTANCE.isEnabled()
                || DungeonDoorBlockReplacementModule.INSTANCE.isEnabled()
                || DungeonRoomOverlayModule.INSTANCE.isEnabled()
                || DungeonRoomAlertModule.INSTANCE.isEnabled()
                || secretConsumerEnabled
                || DungeonHelperModule.INSTANCE.isEnabled()
                || puzzleGeometryConsumerEnabled
            val mapEntryScanEnabled = dungeonMobEspEnabled
            DungeonContextTracker.tick(client, mapGeometryConsumerEnabled, mapEntryScanEnabled)
            // Keep dungeon door observations through feature toggles and chunk transitions.
            DungeonDoorTracker.tick(client)
            DungeonDoorBlockReplacementModule.INSTANCE.tick()
            val logFailure = GeilerAddonsLog.failure()
            if (logFailure == null) reportedLogFailure = null
            if (logFailure != null && logFailure != reportedLogFailure && client.gui != null) {
                reportedLogFailure = logFailure
                client.gui.hud.chat.addClientSystemMessage(
                    Component.literal("[GeilerAddons] Diagnostic logging stopped: $logFailure"),
                )
            }
            PartyListBackend.tick()
            PartyCommandsModule.INSTANCE.tickDiagnostics()
            ChatCommandReplyBackend.tick(client, clientTick)
            AutoKickModule.INSTANCE.tick()
            PartyFinderStatsModule.INSTANCE.tick()
            DungeonRoomTracker.tick(client, mapGeometryConsumerEnabled)
            DungeonHelperModule.INSTANCE.tick()
            BoxDoorsModule.INSTANCE.tick(false)
            DungeonOdinAssistModule.INSTANCE.tick(
                clientTick,
                System.nanoTime(),
                DungeonContextTracker.inDungeon() && !DungeonContextTracker.isMobEspPhase(),
            )
            TickTimersModule.INSTANCE.tick()
            RelicTimerModule.INSTANCE.tick()
            FireFreezeTimerModule.INSTANCE.tick()
            WitherDragonsModule.INSTANCE.tick()
            KingRelicModule.INSTANCE.tick()
            DragonStackAimerModule.INSTANCE.tick()
            TerminalSolverModule.INSTANCE.tick()
            PriceDisplayModule.INSTANCE.tick()
            DungeonBloodCampModule.INSTANCE.tick(client, clientTick, System.nanoTime())
            updateOdinClientObservations(client)
            DungeonRoomOverlayModule.INSTANCE.tick()
            DungeonRoomAlertModule.INSTANCE.tick()
            DungeonKeyPickupModule.INSTANCE.tick()
            SecretWaypointsModule.INSTANCE.tickAutomaticDiagnostics(clientTick)
            if (SecretWaypointsModule.INSTANCE.hasRuntimeConsumer()) SecretWaypointsModule.INSTANCE.tick()
            SafariBiome.tick()
            TorrhusPresence.tick()
            UpdateChecker.tick()
            I4HelperModule.INSTANCE.tick()
            ExperimentSolverModule.INSTANCE.tick()
            AutoExperimentsModule.INSTANCE.tick()
            TikiHelperModule.INSTANCE.tick()
            SafariFloorDropsModule.INSTANCE.tick()
            HideyhoFinderModule.INSTANCE.tick()
            SparklingCritterModule.INSTANCE.tick()
            PestHighlighterModule.INSTANCE.tick()
            GardenPlotBordersModule.INSTANCE.tick()
            PestTrackerModule.INSTANCE.tick()
            SlayerTrackerModule.INSTANCE.tick()
            if (SlayerBossHighlightModule.INSTANCE.isEnabled() || EndermanSlayerModule.INSTANCE.isEnabled() ||
                BlazeSlayerModule.INSTANCE.isEnabled() || VampireSlayerModule.INSTANCE.isEnabled() ||
                SlayerAlertsModule.INSTANCE.isEnabled()
            ) {
                SlayerBossTracker.tick(
                    if (SlayerBossHighlightModule.INSTANCE.isEnabled()) SlayerBossHighlightModule.INSTANCE.trackerRange() else 48.0,
                )
            }
            SlayerBossHighlightModule.INSTANCE.tick()
            EndermanSlayerModule.INSTANCE.tick()
            BlazeSlayerModule.INSTANCE.tick()
            VampireSlayerModule.INSTANCE.tick()
            SlayerAlertsModule.INSTANCE.tick()
            CropFeverTimerModule.INSTANCE.tick()
            HideAllParticlesModule.INSTANCE.tick(clientTick)
            HidePlayersModule.INSTANCE.tick()
            HideF7BossSoundsModule.INSTANCE.tick()
            BossBarPercentModule.INSTANCE.tick()
            SnapTapModule.INSTANCE.tick(clientTick)
            AutoSprintModule.INSTANCE.tick()
            NoFogModule.INSTANCE.tick()
            FullbrightModule.INSTANCE.tick()
            BlockOverlayModule.INSTANCE.tick()
            ScrollableTooltipModule.INSTANCE.tick()
            FreecamModule.INSTANCE.tick()
            NoRenderModule.INSTANCE.tick()
            AnimationFixModule.INSTANCE.tick()
            DoubleUseFixModule.INSTANCE.tick()
            DisconnectFixModule.INSTANCE.tick()
            ItemCooldownsModule.INSTANCE.tick()
            GyroWandModule.INSTANCE.tick()
            MimicAnnouncerModule.INSTANCE.tick()
            WaypointsModule.INSTANCE.tick()
            ChatWaypointsModule.INSTANCE.tick()
            BlessingDisplayModule.INSTANCE.tick()
            TerminalEspModule.INSTANCE.tick()
            MelodyMessageModule.INSTANCE.tick()
            TrappedChestEspModule.INSTANCE.tick()
            ArrowAlignModule.INSTANCE.tick()
            SimonSaysModule.INSTANCE.tick()
            CroesusModule.INSTANCE.tick()
            SpeedDisplayModule.INSTANCE.tick()
            FishingBobberTimerModule.INSTANCE.tick()
            FishingHookDisplayModule.INSTANCE.tick()
            FishingBaitModule.INSTANCE.tick()
            SeaCreatureAlertModule.INSTANCE.tick()
            GeyserFishingModule.INSTANCE.tick()
            HotspotRadarModule.INSTANCE.tick()
            MiningCommissionsModule.INSTANCE.tick()
            MiningPowderModule.INSTANCE.tick()
            HotmTreeHelperModule.INSTANCE.tick()
            PowderChestModule.INSTANCE.tick()
            MineshaftTimerModule.INSTANCE.tick()
            MetalDetectorModule.INSTANCE.tick()
            GlaciteGuideModule.INSTANCE.tick()
            HeatAndColdModule.INSTANCE.tick()
            CrystalHollowsHelperModule.INSTANCE.tick()
            GoblinHighlighterModule.INSTANCE.tick()
            MiningEventsModule.INSTANCE.tick()
            MiningSolversModule.INSTANCE.tick()
            CorpseFinderModule.INSTANCE.tick()
            LavaReplacementModule.INSTANCE.tick()
            GhostVisionModule.INSTANCE.tick()
            TerracottaTimerModule.INSTANCE.tick()
            WardrobeKeybindsModule.INSTANCE.tick()
            LoadoutKeybindsModule.INSTANCE.tick()
            SpringBootsModule.INSTANCE.tick()
            RagnarockModule.INSTANCE.tick()
            SimModules.tick()
            EtherwarpModule.INSTANCE.tick()
            RotationEngineModule.INSTANCE.tick()
            HumanRecorderModule.INSTANCE.tick()
            MacroRuntime.tick()
            LobbyTargetWatchModule.INSTANCE.tick()
            UtilityTimerModule.INSTANCE.tick()
            PlayerNamesModule.INSTANCE.tick()
            PlayerSizeModule.INSTANCE.tick()
            ScoreboardBackend.tick(client)
            MobHighlightModule.INSTANCE.tick()
            DungeonMobEspModule.INSTANCE.tick()
            DungeonBossEspModule.INSTANCE.tick()
            DungeonClassEspModule.INSTANCE.tick()
            ItemRarityBackgroundsModule.INSTANCE.tick()
            NoCursorResetModule.INSTANCE.tick()
            WindowIconController.sync(GeneralModule.INSTANCE.customWindowIcon().value())
            AliasesModule.INSTANCE.tick()
            InstanceRequeueModule.INSTANCE.tick()
            TrailModule.INSTANCE.tick()
            NoLoadingScreenModule.INSTANCE.tick()
            BlockEspModule.INSTANCE.tick()
            InventoryButtonsModule.INSTANCE.tick()
            // Release a chat line held while deciding whether it opened a gift block.
            TreeNotifierModule.INSTANCE.tick()
            MacrosModule.INSTANCE.tick()
            DungeonGuideStore.tick()
            ModConfig.flushIfDirty()
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { _ ->
            MacroRunner.cancel("client stopping")
            DungeonMobEspModule.INSTANCE.clearDungeonMinibossProfiles()
            DungeonStatsService.close()
            ModConfig.flushNow()
            DungeonGuideStore.flush()
            ScoreboardBackend.flush()
            PestTrackerModule.INSTANCE.persistFinalTotalsSnapshot()
            SlayerTrackerModule.INSTANCE.persistFinalTotalsSnapshot()
            // Drain queued writes from feature-local stores and caches.
            ClientJsonFile.flush()
            GeilerAddonsLog.close()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { handler, client ->
            ChatCommandReplyBackend.clear()
            DungeonRoomOverlayModule.INSTANCE.onDisconnect()
            DungeonHelperModule.INSTANCE.onDisconnect()
            clearLividHighlightSnapshot(null)
            DungeonMobEspModule.INSTANCE.clearDungeonMinibossProfiles()
            DungeonOdinAssistModule.INSTANCE.resetAfterDisconnect(handler, client)
            GardenPlotBordersModule.INSTANCE.onWorldChanged()
            PestTrackerModule.INSTANCE.onDisconnect()
            SlayerTrackerModule.INSTANCE.onWorldChanged()
            SlayerBossTracker.reset()
            CropFeverTimerModule.INSTANCE.onWorldChanged()
            LobbyTargetWatchModule.INSTANCE.onWorldChanged()
            ScoreboardBackend.tick(null)
        }

        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            val scope = WorldRenderBuffer.bind(context.submitNodeCollector())
            try {
                DungeonOdinAssistModule.INSTANCE.render(context)
                DungeonBloodCampModule.INSTANCE.render(context)
                I4HelperModule.INSTANCE.render(context)
                TikiHelperModule.INSTANCE.render(context)
                SafariFloorDropsModule.INSTANCE.render(context)
                HideyhoFinderModule.INSTANCE.render(context)
                SparklingCritterModule.INSTANCE.render(context)
                SeaCreatureAlertModule.INSTANCE.render(context)
                GeyserFishingModule.INSTANCE.render(context)
                HotspotRadarModule.INSTANCE.render(context)
                PowderChestModule.INSTANCE.render(context)
                CorpseFinderModule.INSTANCE.render(context)
                MetalDetectorModule.INSTANCE.render(context)
                GlaciteGuideModule.INSTANCE.render(context)
                GoblinHighlighterModule.INSTANCE.render(context)
                CrystalHollowsHelperModule.INSTANCE.render(context)
                MiningSolversModule.INSTANCE.render(context)
                PestHighlighterModule.INSTANCE.render(context)
                GardenPlotBordersModule.INSTANCE.render(context)
                DungeonHelperModule.INSTANCE.render(context)
                BoxDoorsModule.INSTANCE.render(context)
                DungeonKeyPickupModule.INSTANCE.render(context)
                WaypointsModule.INSTANCE.render(context)
                ChatWaypointsModule.INSTANCE.render(context)
                TerminalEspModule.INSTANCE.render(context)
                TrappedChestEspModule.INSTANCE.render(context)
                SimonSaysModule.INSTANCE.render(context)
                GhostVisionModule.INSTANCE.render(context)
                if (SecretWaypointsModule.INSTANCE.hasRuntimeConsumer()) SecretWaypointsModule.INSTANCE.render(context)
                renderLividHighlight(context)
                MobHighlightModule.INSTANCE.render(context)
                DungeonMobEspModule.INSTANCE.render(context)
                DungeonBossEspModule.INSTANCE.render(context)
                SlayerBossHighlightModule.INSTANCE.render(context)
                EndermanSlayerModule.INSTANCE.render(context)
                BlazeSlayerModule.INSTANCE.render(context)
                VampireSlayerModule.INSTANCE.render(context)
                DungeonClassEspModule.INSTANCE.render(context)
                TrailModule.INSTANCE.render(context)
                BlockEspModule.INSTANCE.render(context)
                BlockOverlayModule.INSTANCE.render(context)
                GyroWandModule.INSTANCE.render(context)
                SpringBootsModule.INSTANCE.render(context)
                WitherDragonsModule.INSTANCE.render(context)
                KingRelicModule.INSTANCE.render(context)
                DragonStackAimerModule.INSTANCE.render(context)
            } finally {
                scope.close()
            }
        }
        HudElementRegistry.addFirst(GeilerAddons.id("projected_label_frame")) { _, _ ->
            ProjectedLabelRenderer.beginFrame()
        }
        // Projected solver labels use the HUD because 26.2 has no applicable in-world text stage.
        HudElementRegistry.addLast(GeilerAddons.id("wither_dragon_labels")) { graphics, _ ->
            WitherDragonsModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("tiki_labels")) { graphics, _ ->
            TikiHelperModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("terracotta_labels")) { graphics, _ ->
            TerracottaTimerModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("blood_camp_labels")) { graphics, _ ->
            DungeonBloodCampModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("livid_timer_labels")) { graphics, _ ->
            DungeonOdinAssistModule.INSTANCE.renderLividHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("water_board_labels")) { graphics, _ ->
            DungeonOdinAssistModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("mob_highlight_labels")) { graphics, _ ->
            MobHighlightModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("dungeon_mob_esp")) { graphics, _ ->
            DungeonMobEspModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("enderman_slayer_labels")) { graphics, _ ->
            EndermanSlayerModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("dungeon_class_esp_titles")) { graphics, _ ->
            DungeonClassEspModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("block_esp_labels")) { graphics, _ ->
            BlockEspModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("sparkling_labels")) { graphics, _ ->
            SparklingCritterModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("fishing_hook_display")) { graphics, _ ->
            FishingHookDisplayModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("hotspot_radar_labels")) { graphics, _ ->
            HotspotRadarModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("powder_chest_labels")) { graphics, _ ->
            PowderChestModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("corpse_finder_labels")) { graphics, _ ->
            CorpseFinderModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("metal_detector_labels")) { graphics, _ ->
            MetalDetectorModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("glacite_guide_labels")) { graphics, _ ->
            GlaciteGuideModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("mining_solver_labels")) { graphics, _ ->
            MiningSolversModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("crystal_hollows_labels")) { graphics, _ ->
            CrystalHollowsHelperModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("heat_and_cold_overlay")) { graphics, _ ->
            HeatAndColdModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("pest_highlighter_labels")) { graphics, _ ->
            PestHighlighterModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("garden_plot_borders")) { graphics, _ ->
            GardenPlotBordersModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("secret_waypoint_labels")) { graphics, _ ->
            SecretWaypointsModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("waypoint_labels")) { graphics, _ ->
            WaypointsModule.INSTANCE.renderHud(graphics)
            ChatWaypointsModule.INSTANCE.renderHud(graphics)
            ArrowAlignModule.INSTANCE.renderHud(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("hud_elements")) { graphics, _ ->
            HudManager.render(graphics)
        }
        HudElementRegistry.addLast(GeilerAddons.id("macro_title")) { graphics, _ ->
            MacroTitleOverlay.render(graphics)
        }
    }

    companion object {
        private val LIVID_MARKER_WOOL = BlockPos(5, 108, 43)

        @Volatile
        private var lividHighlightSnapshot = LividHighlightSnapshot.empty(null)

        /** Registers the same module catalog used at client startup; kept separate for offline checks. */
        @JvmStatic
        fun registerModules() {
            registerModule(DebugModule.INSTANCE)
            registerModule(GeneralModule.INSTANCE)
            registerModule(SlotIdsModule.INSTANCE)
            registerModule(I4HelperModule.INSTANCE)
            registerModule(ExperimentSolverModule.INSTANCE)
            registerModule(AutoExperimentsModule.INSTANCE)
            registerModule(AutoKickModule.INSTANCE)
            registerModule(PartyFinderStatsModule.INSTANCE)
            registerModule(DungeonHelperModule.INSTANCE)
            registerModule(BoxDoorsModule.INSTANCE)
            registerModule(DungeonDoorBlockReplacementModule.INSTANCE)
            registerModule(DungeonRoomOverlayModule.INSTANCE)
            registerModule(DungeonRoomAlertModule.INSTANCE)
            registerModule(DungeonKeyPickupModule.INSTANCE)
            registerModule(DungeonPuzzleSolversModule.INSTANCE)
            registerModule(DungeonScorerModule.INSTANCE)
            registerModule(DungeonRunSplitsModule.INSTANCE)
            registerModule(DungeonBloodCampModule.INSTANCE)
            registerModule(DungeonLividSolverModule.INSTANCE)
            registerModule(DungeonInvincibilityTimerModule.INSTANCE)
            registerModule(SpiritLeapMenuModule.INSTANCE)
            registerModule(DungeonTerminalStatsModule.INSTANCE)
            registerModule(DungeonTerminalTitlesModule.INSTANCE)
            registerModule(SecretClickedModule.INSTANCE)
            registerModule(TikiHelperModule.INSTANCE)
            registerModule(SafariFloorDropsModule.INSTANCE)
            registerModule(HideyhoFinderModule.INSTANCE)
            registerModule(SparklingCritterModule.INSTANCE)
            registerModule(PestHighlighterModule.INSTANCE)
            registerModule(GardenPlotBordersModule.INSTANCE)
            registerModule(PestTrackerModule.INSTANCE)
            registerModule(SlayerTrackerModule.INSTANCE)
            registerModule(SlayerAlertsModule.INSTANCE)
            registerModule(SlayerBossHighlightModule.INSTANCE)
            registerModule(EndermanSlayerModule.INSTANCE)
            registerModule(BlazeSlayerModule.INSTANCE)
            registerModule(VampireSlayerModule.INSTANCE)
            registerModule(CropFeverTimerModule.INSTANCE)
            registerModule(ChatChannelsModule.INSTANCE)
            registerModule(ChatModule.INSTANCE)
            registerModule(PartyCommandsModule.INSTANCE)
            registerModule(HideAllParticlesModule.INSTANCE)
            registerModule(HidePlayersModule.INSTANCE)
            registerModule(HideF7BossSoundsModule.INSTANCE)
            registerModule(BossBarPercentModule.INSTANCE)
            registerModule(ItemAnimationsModule.INSTANCE)
            registerModule(LobbyTargetWatchModule.INSTANCE)
            registerModule(PlayerInventoryControlsModule.INSTANCE)
            registerModule(PlayerNamesModule.INSTANCE)
            registerModule(PlayerSizeModule.INSTANCE)
            registerModule(ScoreboardModule.INSTANCE)
            registerModule(SystemClockHudModule.INSTANCE)
            registerModule(ThirdPersonCameraModule.INSTANCE)
            registerModule(TreeTrackerModule.INSTANCE)
            registerModule(TreeNotifierModule.INSTANCE)
            registerModule(MacrosModule.INSTANCE)
            registerModule(VisualModule.INSTANCE)
            registerModule(MobHighlightModule.INSTANCE)
            registerModule(DungeonMobEspModule.INSTANCE)
            registerModule(DungeonBossEspModule.INSTANCE)
            registerModule(DungeonClassEspModule.INSTANCE)
            registerModule(ItemRarityBackgroundsModule.INSTANCE)
            registerModule(NoCursorResetModule.INSTANCE)
            registerModule(AliasesModule.INSTANCE)
            registerModule(InstanceRequeueModule.INSTANCE)
            registerModule(TrailModule.INSTANCE)
            registerModule(NoLoadingScreenModule.INSTANCE)
            registerModule(BlockEspModule.INSTANCE)
            registerModule(InventoryButtonsModule.INSTANCE)
            registerModule(SnapTapModule.INSTANCE)
            registerModule(AutoSprintModule.INSTANCE)
            registerModule(NoFogModule.INSTANCE)
            registerModule(FullbrightModule.INSTANCE)
            registerModule(BlockOverlayModule.INSTANCE)
            registerModule(ScrollableTooltipModule.INSTANCE)
            registerModule(FreecamModule.INSTANCE)
            registerModule(NoRenderModule.INSTANCE)
            registerModule(AnimationFixModule.INSTANCE)
            registerModule(DoubleUseFixModule.INSTANCE)
            registerModule(DisconnectFixModule.INSTANCE)
            registerModule(ItemCooldownsModule.INSTANCE)
            registerModule(GyroWandModule.INSTANCE)
            registerModule(MimicAnnouncerModule.INSTANCE)
            registerModule(WaypointsModule.INSTANCE)
            registerModule(ChatWaypointsModule.INSTANCE)
            registerModule(BlessingDisplayModule.INSTANCE)
            registerModule(TerminalEspModule.INSTANCE)
            registerModule(MelodyMessageModule.INSTANCE)
            registerModule(TrappedChestEspModule.INSTANCE)
            registerModule(ArrowAlignModule.INSTANCE)
            registerModule(SimonSaysModule.INSTANCE)
            registerModule(CroesusModule.INSTANCE)
            registerModule(SpeedDisplayModule.INSTANCE)
            registerModule(GhostVisionModule.INSTANCE)
            registerModule(TerracottaTimerModule.INSTANCE)
            registerModule(WardrobeKeybindsModule.INSTANCE)
            registerModule(LoadoutKeybindsModule.INSTANCE)
            registerModule(SpringBootsModule.INSTANCE)
            registerModule(RagnarockModule.INSTANCE)
            registerModule(TickTimersModule.INSTANCE)
            registerModule(RelicTimerModule.INSTANCE)
            registerModule(FireFreezeTimerModule.INSTANCE)
            registerModule(WitherDragonsModule.INSTANCE)
            registerModule(KingRelicModule.INSTANCE)
            registerModule(DragonStackAimerModule.INSTANCE)
            registerModule(CompactDamageModule.INSTANCE)
            registerModule(TerminalSolverModule.INSTANCE)
            registerModule(PriceDisplayModule.INSTANCE)
            registerModule(TerminalSimulatorModule.INSTANCE)
            registerModule(FishingBobberTimerModule.INSTANCE)
            registerModule(FishingHookDisplayModule.INSTANCE)
            registerModule(FishingBaitModule.INSTANCE)
            registerModule(SeaCreatureAlertModule.INSTANCE)
            registerModule(GeyserFishingModule.INSTANCE)
            registerModule(HotspotRadarModule.INSTANCE)
            registerModule(LavaReplacementModule.INSTANCE)
            registerModule(MiningCommissionsModule.INSTANCE)
            registerModule(MiningPowderModule.INSTANCE)
            registerModule(HotmTreeHelperModule.INSTANCE)
            registerModule(PowderChestModule.INSTANCE)
            registerModule(MineshaftTimerModule.INSTANCE)
            registerModule(CorpseFinderModule.INSTANCE)
            registerModule(MetalDetectorModule.INSTANCE)
            registerModule(GlaciteGuideModule.INSTANCE)
            registerModule(GoblinHighlighterModule.INSTANCE)
            registerModule(CrystalHollowsHelperModule.INSTANCE)
            registerModule(MiningSolversModule.INSTANCE)
            registerModule(MiningEventsModule.INSTANCE)
            registerModule(HeatAndColdModule.INSTANCE)
            // The Macros workspace: its modules live in their own categories and their own config file.
            registerModule(SpeedSimModule.INSTANCE)
            registerModule(JumpBoostModule.INSTANCE)
            registerModule(OneTapModule.INSTANCE)
            registerModule(EtherwarpSimModule.INSTANCE)
            registerModule(EtherwarpModule.INSTANCE)
            registerModule(RotationEngineModule.INSTANCE)
            registerModule(HumanRecorderModule.INSTANCE)
            registerModule(PathfinderModule.INSTANCE)
            registerModule(AreaKillerModule.INSTANCE)
        }

        private fun registerModule(module: Module) {
            if (!ModuleManager.contains(module.category(), module)) ModuleManager.register(module)
        }

        /** Learns Livid's marker from the server block update, not from the player's crosshair. */
        @JvmStatic
        fun onLividMarkerBlockUpdate(position: BlockPos?, state: BlockState?) {
            if (position == null || position != LIVID_MARKER_WOOL) return
            val client = Minecraft.getInstance()
            if (!client.isSameThread) return
            val level = client.level ?: return
            val floor = DungeonContextTracker.currentFloor()
            val odin = DungeonOdinAssistModule.INSTANCE
            if (!DungeonLividSnapshotGate.shouldScan(
                    odin.isLividHighlightEnabled(),
                    DungeonContextTracker.inDungeon(),
                    floor?.number() ?: -1,
                    DungeonContextTracker.isMobEspPhase(),
                )
            ) return
            val dye = state?.let(::lividWoolDye)
            val markerBlock = state?.let { BuiltInRegistries.BLOCK.getKey(it.block).path } ?: "unknown"
            odin.observeLividMarkerEvidence(markerBlock, dye, "block-update", true)
        }

        private fun updateOdinClientObservations(client: Minecraft) {
            val odin = DungeonOdinAssistModule.INSTANCE
            val level = client.level
            val player = client.player
            if (lividHighlightSnapshot.level !== level) clearLividHighlightSnapshot(level)
            if (player == null || level == null || !DungeonContextTracker.inDungeon()) {
                odin.updateLeapRoster(emptyList(), null)
                odin.skipLividCandidateScan(
                    when {
                        player == null -> "player-unavailable"
                        level == null -> "world-unavailable"
                        else -> "outside-dungeon"
                    },
                )
                clearLividHighlightSnapshot(level)
                return
            }

            val localName = player.name.string
            val party = PartyListBackend.snapshot()
            val members = ArrayList<SpiritLeapRoster.Member>()
            val tabPlayersByName = LinkedHashMap<String, OdinDungeonPlayerMap.TabPlayer>()
            if (SpiritLeapMenuModule.INSTANCE.isEnabled()) {
                for (tabPlayer in OdinDungeonPlayerRoster.snapshot()) {
                    if (tabPlayer != null && tabPlayer.name() != null && tabPlayer.dungeonClass() != null) {
                        tabPlayersByName.putIfAbsent(tabPlayer.name().lowercase(java.util.Locale.ROOT), tabPlayer)
                    }
                }
            }
            if (party.inParty()) {
                for (member in party.members()) {
                    val memberName = member.name() ?: continue
                    val online = level.players().firstOrNull {
                        it.name.string.equals(memberName, ignoreCase = true)
                    }
                    val tabPlayer = tabPlayersByName[memberName.lowercase(java.util.Locale.ROOT)]
                    val tabClass = tabPlayer?.dungeonClass()
                    val parsedTabClass = DungeonClass.parse(tabClass)
                    val dungeonClass = parsedTabClass?.displayName()
                        ?: member.dungeonClass()?.displayName().orEmpty()
                    val health = if (online == null || online.maxHealth <= 0.0f) 0
                        else Math.round(online.health / online.maxHealth * 100.0f)
                    val dead = (tabPlayer?.dead() == true)
                        || (online != null && (!online.isAlive || online.health <= 0.0f))
                    members += SpiritLeapRoster.Member(memberName, dungeonClass, health, online != null, dead)
                }
            }
            odin.updateLeapRoster(members, localName)

            val floor = DungeonContextTracker.currentFloor()
            val lividRoom = DungeonLividSnapshotGate.shouldScan(
                odin.isLividHighlightEnabled(),
                true,
                floor?.number() ?: -1,
                DungeonContextTracker.isMobEspPhase(),
            )
            val trackWatcher = DungeonRunSplitsModule.INSTANCE.isEnabled()
            if (!lividRoom) {
                odin.skipLividCandidateScan(
                    when {
                        !odin.isLividHighlightEnabled() -> "feature-disabled"
                        floor == null -> "floor-unknown"
                        floor.number() != 5 -> "outside-f5"
                        DungeonContextTracker.isMobEspPhase() -> "pre-boss"
                        else -> "gate-closed"
                    },
                )
            }
            if (!lividRoom && !trackWatcher) {
                clearLividHighlightSnapshot(level)
                return
            }
            if (lividRoom) {
                if (level.hasChunkAt(LIVID_MARKER_WOOL)) {
                    val markerState = level.getBlockState(LIVID_MARKER_WOOL)
                    val markerBlock = BuiltInRegistries.BLOCK.getKey(markerState.block).path
                    odin.observeLividMarkerEvidence(markerBlock, lividWoolDye(markerState), "loaded-block", true)
                } else {
                    odin.observeLividMarkerEvidence("unloaded", null, "loaded-block", false)
                }
            }

            val entitiesById = LinkedHashMap<Int, LivingEntity>()
            var scannedLividEntities = 0
            var namedLividCandidates = 0
            var markerMatches = 0
            val candidateSamples = ArrayList<String>()
            for (entity in level.getEntitiesOfClass(LivingEntity::class.java, player.boundingBox.inflate(64.0))) {
                val displayName = entity.customName?.string ?: entity.name.string
                if (trackWatcher) odin.observeWatcherName(displayName)
                if (lividRoom) {
                    scannedLividEntities++
                    if (DungeonLividTracker.isLividEntityName(displayName)) {
                        namedLividCandidates++
                        if (candidateSamples.size < 6) candidateSamples += "$displayName:${entity.id}"
                        if (odin.observeLividPlayerEntity(entity.uuid, entity.id, displayName)) markerMatches++
                    }
                    entitiesById[entity.id] = entity
                }
            }
            if (lividRoom) {
                odin.observeLividTargetPresence(entitiesById)
                odin.recordLividCandidateScan(
                    scannedLividEntities,
                    namedLividCandidates,
                    markerMatches,
                    candidateSamples,
                )
                lividHighlightSnapshot = LividHighlightSnapshot(level, entitiesById)
            } else {
                clearLividHighlightSnapshot(level)
            }
        }

        private fun lividWoolDye(state: BlockState): String? = when {
            state.`is`(Blocks.WOOL.pick(DyeColor.WHITE)) -> "white"
            state.`is`(Blocks.WOOL.pick(DyeColor.MAGENTA)) -> "magenta"
            state.`is`(Blocks.WOOL.pick(DyeColor.YELLOW)) -> "yellow"
            state.`is`(Blocks.WOOL.pick(DyeColor.LIME)) -> "lime"
            state.`is`(Blocks.WOOL.pick(DyeColor.GRAY)) -> "gray"
            state.`is`(Blocks.WOOL.pick(DyeColor.PURPLE)) -> "purple"
            state.`is`(Blocks.WOOL.pick(DyeColor.BLUE)) -> "blue"
            state.`is`(Blocks.WOOL.pick(DyeColor.GREEN)) -> "green"
            state.`is`(Blocks.WOOL.pick(DyeColor.RED)) -> "red"
            else -> null
        }

        private fun renderLividHighlight(context: LevelRenderContext) {
            val odin = DungeonOdinAssistModule.INSTANCE
            odin.setLividHudLabel(null, null)
            val client = Minecraft.getInstance()
            val floor = DungeonContextTracker.currentFloor()
            val lividRoom = DungeonLividSnapshotGate.shouldScan(
                odin.isLividHighlightEnabled(),
                DungeonContextTracker.inDungeon(),
                floor?.number() ?: -1,
                DungeonContextTracker.isMobEspPhase(),
            )
            val player = client.player
            val level = client.level
            if (!lividRoom || player == null || level == null) {
                val reason = when {
                    !odin.isLividHighlightEnabled() -> "feature-disabled"
                    !DungeonContextTracker.inDungeon() -> "outside-dungeon"
                    floor == null -> "floor-unknown"
                    floor.number() != 5 -> "outside-f5"
                    DungeonContextTracker.isMobEspPhase() -> "pre-boss"
                    player == null -> "player-unavailable"
                    else -> "world-unavailable"
                }
                odin.recordLividRenderDiagnostic("skip=$reason")
                clearLividHighlightSnapshot(level)
                return
            }
            val snapshot = lividHighlightSnapshot
            if (snapshot.level !== level) {
                odin.recordLividRenderDiagnostic("skip=snapshot-level-mismatch")
                clearLividHighlightSnapshot(level)
                return
            }
            val blinded = player.hasEffect(MobEffects.BLINDNESS)
            val target = odin.publishedLividTarget(blinded)
            if (target == null) {
                odin.recordLividRenderDiagnostic("skip=${if (blinded) "blinded" else "no-published-target"}")
                return
            }
            val entity = snapshot.entitiesById[target.entityId]
            if (entity == null) {
                odin.recordLividRenderDiagnostic("skip=target-entity-missing id=${target.entityId}")
                return
            }
            if (entity.isRemoved || !entity.isAlive || target.uuid != entity.uuid) {
                odin.recordLividRenderDiagnostic("skip=target-entity-invalid id=${target.entityId}")
                return
            }
            val camera = client.gameRenderer.mainCamera().position()
            val pose = context.poseStack()
            val buffers = WorldRenderBuffer.current()
            val range = player.boundingBox.inflate(64.0)
            val bounds = entity.boundingBox
            if (!bounds.intersects(range)) {
                odin.recordLividRenderDiagnostic("skip=target-out-of-range id=${target.entityId}")
                return
            }
            val lividModule = DungeonLividSolverModule.INSTANCE
            val useTargetColor = lividModule.useTargetColor()
            val selectedColor = if (useTargetColor) target.colorArgb else lividModule.tracerColorArgb()
            val fillColor = 0x24000000 or (selectedColor and 0x00FFFFFF)
            EspRenderer.renderBox(
                pose,
                buffers,
                bounds.minX - camera.x,
                bounds.minY - camera.y,
                bounds.minZ - camera.z,
                bounds.xsize,
                bounds.ysize,
                bounds.zsize,
                fillColor,
                selectedColor,
                2.5f,
                true,
            )
            val tracerSubmitted = lividModule.showTracer()
            if (tracerSubmitted) {
                val targetCenter = bounds.center
                EspRenderer.renderLine(
                    pose,
                    buffers,
                    0.0,
                    0.0,
                    0.0,
                    targetCenter.x - camera.x,
                    targetCenter.y - camera.y,
                    targetCenter.z - camera.z,
                    selectedColor,
                    lividModule.tracerWidth(),
                    false,
                )
            }
            val timerLabel = odin.lividWorldTimerLabel()
            if (timerLabel != null) {
                val targetCenter = bounds.center
                odin.setLividHudLabel(Vec3(targetCenter.x, bounds.maxY + 0.25, targetCenter.z), timerLabel)
            }
            GeilerAddonsRenderTypes.endBatches(buffers)
            odin.recordLividRenderDiagnostic(
                "box=submitted tracer=${if (tracerSubmitted) "submitted" else "disabled"}" +
                    " timerLabel=${if (timerLabel == null) "hidden" else "submitted"}" +
                    " target=${target.entityName}:${target.entityId} colorSource=" +
                    "${if (useTargetColor) "target" else "custom"} width=${lividModule.tracerWidth()} origin=camera",
            )
        }

        private fun clearLividHighlightSnapshot(level: ClientLevel?) {
            lividHighlightSnapshot = LividHighlightSnapshot.empty(level)
        }
    }

    private class LividHighlightSnapshot(level: ClientLevel?, entitiesById: Map<Int, LivingEntity>?) {
        val level: ClientLevel? = level
        val entitiesById: Map<Int, LivingEntity> =
            entitiesById?.let { java.util.Map.copyOf(it) } ?: emptyMap()

        companion object {
            fun empty(level: ClientLevel?) = LividHighlightSnapshot(level, emptyMap())
        }
    }
}
