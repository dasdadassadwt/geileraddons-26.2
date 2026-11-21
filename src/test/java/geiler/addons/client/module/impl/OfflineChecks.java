package geiler.addons.client.module.impl;

import geiler.addons.client.GeilerAddonsClient;
import geiler.addons.client.FabricCheckEnvironment;
import geiler.addons.client.command.GeilerAddonsCommandTreeChecks;
import geiler.addons.client.dungeon.DungeonStatsChecks;
import geiler.addons.client.dungeon.DungeonClassChecks;
import geiler.addons.client.dungeon.DungeonFeatureChecks;
import geiler.addons.client.dungeon.DungeonGuideChecks;
import geiler.addons.client.dungeon.DungeonContextDoorChecks;
import geiler.addons.client.dungeon.DungeonKeyAlertChecks;
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementChecks;
import geiler.addons.client.dungeon.SecretWaypointCollectionTrackerChecks;
import geiler.addons.client.dungeon.DungeonRoomFootprintChecks;
import geiler.addons.client.dungeon.helpers.DungeonPuzzleVisualRendererChecks;
import geiler.addons.client.dungeon.helpers.DungeonQuizChecks;
import geiler.addons.client.waypoint.WaypointChecks;
import geiler.addons.client.dungeon.f7.F7FeatureChecks;
import geiler.addons.client.dungeon.f7.CroesusChecks;
import geiler.addons.client.farming.FarmingQolChecks;
import geiler.addons.client.farming.GardenPackChecks;
import geiler.addons.client.kuudra.KuudraChecks;
import geiler.addons.client.misc.InventoryCaptureChecks;
import geiler.addons.client.misc.QuiverChecks;
import geiler.addons.client.misc.SpeedGhostChecks;
import geiler.addons.client.dungeon.DungeonStatsCommand;
import geiler.addons.client.dungeon.DungeonStatsService;
import geiler.addons.client.dungeon.ClientJsonFileFlushChecks;
import geiler.addons.client.collections.FolderTreeChecks;
import geiler.addons.client.net.BoundedHttpBodyReaderChecks;
import geiler.addons.client.config.ConfigProfileChecks;
import geiler.addons.client.config.ConfigSettingKeysChecks;
import geiler.addons.client.config.FlatLegacyConfigMigrationChecks;
import geiler.addons.client.config.GeilerAddonsLogRecoveryChecks;
import geiler.addons.client.config.ModConfig;
import geiler.addons.client.config.ModConfigMigrations;
import geiler.addons.client.config.SpiritLeapModuleEnabledMigrationChecks;
import geiler.addons.client.config.PuzzleSolversMigrationChecks;
import geiler.addons.client.config.ThemeIconColorMigrationChecks;
import geiler.addons.client.gui.ClickGuiMotionChecks;
import geiler.addons.client.gui.ClickGuiScrollChecks;
import geiler.addons.client.gui.BoundedUndoHistoryChecks;
import geiler.addons.client.gui.ScratchBlockShapesChecks;
import geiler.addons.client.gui.SlotIdBadgeLayoutChecks;
import geiler.addons.client.gui.ThemeContrastChecks;
import geiler.addons.client.hud.HudExpiryChecks;
import geiler.addons.client.hud.HudManagerChecks;
import geiler.addons.client.entity.ClientEntitySnapshotChecks;
import geiler.addons.client.entity.NameplatesChecks;
import geiler.addons.client.entity.OfflineEntityFixtures;
import geiler.addons.client.farming.GardenPlotChecks;
import geiler.addons.client.farming.PestPersistenceChecks;
import geiler.addons.client.render.BlockOutlineChecks;
import geiler.addons.client.render.ViewBobChecks;
import geiler.addons.client.render.GeilerAddonsRenderTypesAbiChecks;
import geiler.addons.client.render.GardenPlotCullingChecks;
import geiler.addons.client.update.ReleaseNotesChecks;
import geiler.addons.client.presentation.PlayerPresentationModuleChecks;
import geiler.addons.client.enchanting.ExperimentCell;
import geiler.addons.client.enchanting.ExperimentBoardGeometry;
import geiler.addons.client.enchanting.AutoExperimentAutomation;
import geiler.addons.client.enchanting.AutoExperimentDelayRange;
import geiler.addons.client.enchanting.ChronomatronEvent;
import geiler.addons.client.enchanting.ChronomatronModel;
import geiler.addons.client.enchanting.ExperimentClickGate;
import geiler.addons.client.enchanting.ExperimentMilestone;
import geiler.addons.client.enchanting.ExperimentPhase;
import geiler.addons.client.enchanting.ExperimentSnapshot;
import geiler.addons.client.enchanting.ExperimentSolverEngine;
import geiler.addons.client.enchanting.ExperimentTier;
import geiler.addons.client.enchanting.ExperimentType;
import geiler.addons.client.enchanting.SolverView;
import geiler.addons.client.enchanting.SuperpairsBoard;
import geiler.addons.client.enchanting.UltrasequencerSequenceExecutor;
import geiler.addons.client.location.Island;
import geiler.addons.client.location.HypixelModApiStateChecks;
import geiler.addons.client.location.TorrhusPresenceChecks;
import geiler.addons.client.farming.PestChecks;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.macro.MacroChecks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.decoration.ArmorStand;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small no-server checks for the state boundaries that are easy to regress. */
public final class OfflineChecks {
	private static final String FEL_HEAD_TEXTURE_FOR_CHECKS = "ewogICJ0aW1lc3RhbXAiIDogMTcyMDAyNTQ4Njg2MywKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMjg2ZGFjYjBmMjE0NGQ3YTQxODdiZTM2YmJhYmU4YTk4ODI4ZjdjNzlkZmY1Y2UwMTM2OGI2MzAwMTU1NjYzIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=";

	private OfflineChecks() {
	}

	private interface Check {
		void run() throws Exception;
	}

	private static final java.util.Map<String, Throwable> FAILURES = new java.util.LinkedHashMap<>();

	/** Runs one check and records its failure so later checks still execute and every failure is reported. */
	private static void step(String name, Check check) {
		try {
			check.run();
		} catch (Throwable error) {
			FAILURES.put(name, error);
		}
	}

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		FabricCheckEnvironment.initializeConfigDirectory();
		step("FlatLegacyConfigMigrationChecks.run", () -> FlatLegacyConfigMigrationChecks.run());
		step("DungeonClassChecks.run", () -> DungeonClassChecks.run());
		step("DungeonEspSplitChecks.run", () -> DungeonEspSplitChecks.run());
		step("NoFogMigrationChecks.run", () -> NoFogMigrationChecks.run());
		step("ClickGuiHitRectChecks.run", () -> geiler.addons.client.gui.ClickGuiHitRectChecks.run());
		step("ZoomKeybindMigrationChecks.run", () -> ZoomKeybindMigrationChecks.run());
		step("SlotItemOverlayChecks.run", () -> SlotItemOverlayChecks.run());
		step("ChatTrailRequeueChecks.run", () -> ChatTrailRequeueChecks.run());
		step("AliasBuiltInChecks.run", () -> AliasBuiltInChecks.run());
		step("WindowIconLinkChecks.run", () -> WindowIconLinkChecks.run());
		step("GeilerAddonsRenderTypesAbiChecks.run", () -> GeilerAddonsRenderTypesAbiChecks.run());
		step("PestPersistenceChecks.run", () -> PestPersistenceChecks.run());
		step("GeilerAddonsLogRecoveryChecks.run", () -> GeilerAddonsLogRecoveryChecks.run());
		GeilerAddonsClient.registerModules();
		step("GeilerAddonsCommandTreeChecks.run", () -> GeilerAddonsCommandTreeChecks.run());
		step("DungeonBloodCampModuleChecks.run", () -> DungeonBloodCampModuleChecks.run());
		step("PartyFinderStatsModuleChecks.run", () -> PartyFinderStatsModuleChecks.run());
		step("DungeonRoomOverlayModuleChecks.run", () -> DungeonRoomOverlayModuleChecks.run());
		step("AutoExperimentsModuleChecks.run", () -> AutoExperimentsModuleChecks.run());
		step("PestTrackerModuleChecks.run", () -> PestTrackerModuleChecks.run());
		step("DebugModuleChecks.run", () -> DebugModuleChecks.run());
		step("PlayerNamesModuleChecks.run", () -> PlayerNamesModuleChecks.run());
		step("PlayerPresentationModuleChecks.run", () -> PlayerPresentationModuleChecks.run());
		step("DungeonRoomAlertModuleChecks.run", () -> DungeonRoomAlertModuleChecks.run());
		step("SlotIdsChecks.run", () -> SlotIdsChecks.run());
		step("DungeonTerminalTitlesChecks.run", () -> DungeonTerminalTitlesChecks.run());
		step("LobbyTargetWatchModuleChecks.run", () -> LobbyTargetWatchModuleChecks.run());
		step("GeneralUtilityTimerChecks.run", () -> GeneralUtilityTimerChecks.run());
		step("InvincibilityTimerModuleChecks.run", () -> InvincibilityTimerModuleChecks.run());
		step("LividHelperModuleChecks.run", () -> LividHelperModuleChecks.run());
		step("DungeonTerminalStatsModuleChecks.run", () -> DungeonTerminalStatsModuleChecks.run());
		// The deferred I4 QA run can be skipped without changing the suite's normal default.
		if (!Boolean.getBoolean("geileraddons.skipI4HelperChecks")) {
			step("I4HelperModuleChecks.run", () -> I4HelperModuleChecks.run());
		}
		step("DungeonKeyPickupModuleChecks.run", () -> DungeonKeyPickupModuleChecks.run());
		step("SecretClickedModuleChecks.run", () -> SecretClickedModuleChecks.run());
		step("ChatModuleChecks.run", () -> ChatModuleChecks.run());
		step("PartyCommandsModuleChecks.run", () -> PartyCommandsModuleChecks.run());
		step("PestHighlighterModuleChecks.run", () -> PestHighlighterModuleChecks.run());
		step("SafariFloorDropsChecks.run", () -> SafariFloorDropsChecks.run());
		step("SparklingCritterModuleChecks.run", () -> SparklingCritterModuleChecks.run());
		step("CritterSafariChecks.run", () -> geiler.addons.client.hunting.CritterSafariChecks.run());
		step("FishingBobberChecks.run", () -> geiler.addons.client.fishing.FishingBobberChecks.run());
		step("FishingHookChecks.run", () -> geiler.addons.client.fishing.FishingHookChecks.run());
		step("MiningTabTextChecks.run", () -> geiler.addons.client.mining.MiningTabTextChecks.run());
		step("HotmChecks.run", () -> geiler.addons.client.mining.HotmChecks.run());
		step("PartyFinderListingChecks.run", () -> geiler.addons.client.dungeon.PartyFinderListingChecks.run());
		step("TerminalSplitsChecks.run", () -> geiler.addons.client.dungeon.f7.TerminalSplitsChecks.run());
		step("PowderChestChecks.run", () -> geiler.addons.client.mining.PowderChestChecks.run());
		step("MineshaftChecks.run", () -> geiler.addons.client.mining.MineshaftChecks.run());
		step("MetalDetectorChecks.run", () -> geiler.addons.client.mining.MetalDetectorChecks.run());
		step("GlaciteGuideChecks.run", () -> geiler.addons.client.mining.GlaciteGuideChecks.run());
		step("MiningConditionsChecks.run", () -> geiler.addons.client.mining.MiningConditionsChecks.run());
		step("FossilSolverChecks.run", () -> geiler.addons.client.mining.FossilSolverChecks.run());
		step("CrystalHollowsChecks.run", () -> geiler.addons.client.mining.CrystalHollowsChecks.run());
		step("CrystalHollowsScanChecks.run", () -> geiler.addons.client.mining.CrystalHollowsScanChecks.run());
		step("FishingBaitChecks.run", () -> geiler.addons.client.fishing.FishingBaitChecks.run());
		step("SeaCreatureChecks.run", () -> geiler.addons.client.fishing.SeaCreatureChecks.run());
		step("FishingParticleChecks.run", () -> geiler.addons.client.fishing.FishingParticleChecks.run());
		step("SlayerRulesChecks.run", () -> geiler.addons.client.slayer.SlayerRulesChecks.run());
		step("SlayerHudChecks.run", () -> geiler.addons.client.slayer.SlayerHudChecks.run());
		step("LavaReplacementChecks.run", () -> geiler.addons.client.fishing.LavaReplacementChecks.run());
		step("HudFeatureChecks.run", () -> HudFeatureChecks.run());
		step("checkFreshModuleEnableDefaults", () -> checkFreshModuleEnableDefaults());
		step("SpiritLeapModuleEnabledMigrationChecks.run", () -> SpiritLeapModuleEnabledMigrationChecks.run());
		step("PuzzleSolversMigrationChecks.run", () -> PuzzleSolversMigrationChecks.run());
		step("ConfigSettingKeysChecks.run", () -> ConfigSettingKeysChecks.run());
		step("DungeonPuzzleSolversDefaultsChecks.run", () -> DungeonPuzzleSolversDefaultsChecks.run());
		step("DungeonPuzzleVisualRendererChecks.run", () -> DungeonPuzzleVisualRendererChecks.run());
		step("DungeonQuizChecks.run", () -> DungeonQuizChecks.run());
		step("WaypointChecks.run", () -> WaypointChecks.run());
		step("F7FeatureChecks.run", () -> F7FeatureChecks.run());
		step("LeverAimChecks.run", () -> geiler.addons.client.dungeon.f7.LeverAimChecks.run());
		step("TickTimersChecks.run", () -> geiler.addons.client.dungeon.f7.TickTimersChecks.run());
		step("SpiritLeapMapChecks.run", () -> geiler.addons.client.dungeon.helpers.SpiritLeapMapChecks.run());
		step("WitherDragonChecks.run", () -> geiler.addons.client.dungeon.f7.WitherDragonChecks.run());
		step("DragonAimerChecks.run", () -> geiler.addons.client.dungeon.f7.DragonAimerChecks.run());
		step("CompactDamageChecks.run", () -> CompactDamageChecks.run());
		step("StorageOverlayRulesChecks.run", () -> geiler.addons.client.storage.StorageOverlayRulesChecks.run());
		step("TerminalChecks.run", () -> geiler.addons.client.dungeon.terminal.TerminalChecks.run());
		step("TerminalAnalysisChecks.run", () -> geiler.addons.client.dungeon.terminal.TerminalAnalysisChecks.run());
		step("AutoTermsChecks.run", () -> geiler.addons.client.dungeon.terminal.AutoTermsChecks.run());
		step("PriceChecks.run", () -> geiler.addons.client.pricing.PriceChecks.run());
		step("ItemListChecks.run", () -> geiler.addons.client.itemlist.ItemListChecks.run());
		step("TermSimChecks.run", () -> geiler.addons.client.termsim.TermSimChecks.run());
		step("CroesusChecks.run", () -> CroesusChecks.run());
		step("ChestRollChecks.run", () -> geiler.addons.client.dungeon.f7.ChestRollChecks.run());
		step("SpeedGhostChecks.run", () -> SpeedGhostChecks.run());
		step("QuiverChecks.run", () -> QuiverChecks.run());
		step("InventoryCaptureChecks.run", () -> InventoryCaptureChecks.run());
		step("KuudraChecks.run", () -> KuudraChecks.run());
		step("FarmingQolChecks.run", () -> FarmingQolChecks.run());
		step("GardenPackChecks.run", () -> GardenPackChecks.run());
		step("ViewBobChecks.run", () -> ViewBobChecks.run());
		step("checkLegacyUtilityTimerMigration", () -> checkLegacyUtilityTimerMigration());
		step("checkAdditionalLegacyConfigMigrations", () -> checkAdditionalLegacyConfigMigrations());
		step("checkLegacyPresentationAndSecretMigrations", () -> checkLegacyPresentationAndSecretMigrations());
		step("HudManagerChecks.run", () -> HudManagerChecks.run());
		step("HudExpiryChecks.run", () -> HudExpiryChecks.run());
		step("ProjectedLabelChecks.run", () -> geiler.addons.client.render.ProjectedLabelChecks.run());
		step("DungeonRunSplitsModuleChecks.run", () -> DungeonRunSplitsModuleChecks.run());
		step("ClientJsonFileFlushChecks.run", () -> ClientJsonFileFlushChecks.run());
		step("checkIslandModes", () -> checkIslandModes());
		step("HypixelModApiStateChecks.run", () -> HypixelModApiStateChecks.run());
		step("TorrhusPresenceChecks.run", () -> TorrhusPresenceChecks.run());
		step("checkDungeonStatsCommand", () -> checkDungeonStatsCommand());
		step("checkMacroKeyCapturePolicy", () -> checkMacroKeyCapturePolicy());
		step("MobHighlightChecks.run", () -> MobHighlightChecks.run());
		step("DungeonStatsChecks.run", () -> DungeonStatsChecks.run());
		step("DungeonFeatureChecks.run", () -> DungeonFeatureChecks.run());
		step("DungeonKeyAlertChecks.run", () -> DungeonKeyAlertChecks.run());
		step("DungeonGuideChecks.run", () -> DungeonGuideChecks.run());
		step("DungeonContextDoorChecks.run", () -> DungeonContextDoorChecks.run());
		step("DungeonDoorBlockReplacementChecks.main", () -> DungeonDoorBlockReplacementChecks.main(args));
		step("DungeonDoorBlockReplacementModuleChecks.run", () -> DungeonDoorBlockReplacementModuleChecks.run());
		step("BoxDoorsChecks.run", () -> BoxDoorsChecks.run());
		step("ExperimentSolverModuleChecks.run", () -> ExperimentSolverModuleChecks.run());
		step("SecretWaypointCollectionTrackerChecks.run", () -> SecretWaypointCollectionTrackerChecks.run());
		step("DungeonRoomFootprintChecks.run", () -> DungeonRoomFootprintChecks.run());
		step("DungeonFeatureContextChecks.run", () -> DungeonFeatureContextChecks.run());
		step("DungeonMobEspAcceptanceChecks.run", () -> DungeonMobEspAcceptanceChecks.run());
		step("ShadowAssassinAcceptanceChecks.run", () -> ShadowAssassinAcceptanceChecks.run());
		step("PestChecks.run", () -> PestChecks.run());
		step("GardenPlotChecks.run", () -> GardenPlotChecks.run());
		step("CropFeverTimerModuleChecks.run", () -> CropFeverTimerModuleChecks.run());
		step("DungeonTextHudModuleChecks.run", () -> DungeonTextHudModuleChecks.run());
		step("ClientEntitySnapshotChecks.run", () -> ClientEntitySnapshotChecks.run());
		step("NameplatesChecks.run", () -> NameplatesChecks.run());
		step("HideyhoChecks.run", () -> HideyhoChecks.run());
		step("HideAllParticlesChecks.main", () -> HideAllParticlesChecks.main(args));
		step("SnapTapChecks.main", () -> SnapTapChecks.main(args));
		step("ViewModulesChecks.run", () -> ViewModulesChecks.run());
		step("FreecamChecks.run", () -> FreecamChecks.run());
		step("ScrollableTooltipChecks.run", () -> ScrollableTooltipChecks.run());
		step("NoRenderChecks.run", () -> NoRenderChecks.run());
		step("TabWidgetRulesChecks.run", () -> geiler.addons.client.tabwidget.TabWidgetRulesChecks.run());
		step("GameFixChecks.run", () -> GameFixChecks.run());
		step("ItemCooldownChecks.run", () -> ItemCooldownChecks.run());
		step("DungeonHelpersPortChecks.run", () -> DungeonHelpersPortChecks.run());
		step("ContainerKeybindChecks.run", () -> ContainerKeybindChecks.run());
		step("DungeonMapInfoChecks.run", () -> geiler.addons.client.dungeon.DungeonMapInfoChecks.run());
		step("TreeNotifierChecks.main", () -> TreeNotifierChecks.main(args));
		step("ForagingChecks.run", () -> geiler.addons.client.foraging.ForagingChecks.run());
		step("HotfChecks.run", () -> geiler.addons.client.foraging.HotfChecks.run());
		step("ScoreTitlesChecks.run", () -> ScoreTitlesChecks.run());
		step("PartyFinderLifecycleChecks.run", () -> PartyFinderLifecycleChecks.run());
		step("checkMobHighlightFailClosed", () -> checkMobHighlightFailClosed());
		step("checkPersonalBestBoundaries", () -> checkPersonalBestBoundaries());
		step("checkAutoKickLabels", () -> checkAutoKickLabels());
		step("checkExperimentPreviewConfiguration", () -> checkExperimentPreviewConfiguration());
		step("checkSuperpairsCacheGate", () -> checkSuperpairsCacheGate());
		step("checkEveryStatsToggleCombination", () -> checkEveryStatsToggleCombination());
		step("checkDungeonStatsHovers", () -> checkDungeonStatsHovers());
		step("checkGlobalDebugGate", () -> checkGlobalDebugGate());
		step("checkModuleManagerMembership", () -> checkModuleManagerMembership());
		step("checkPersistedSettings", () -> checkPersistedSettings());
		step("checkThemeIconColorSetting", () -> checkThemeIconColorSetting());
		step("VisualModuleChecks.run", () -> VisualModuleChecks.run());
		step("ThemeIconColorMigrationChecks.run", () -> ThemeIconColorMigrationChecks.run());
		step("CheatGateChecks.run", () -> CheatGateChecks.run());
		step("ReleaseNotesChecks.run", () -> ReleaseNotesChecks.run());
		step("checkModuleKeybinds", () -> checkModuleKeybinds());
		step("checkSettingInputBounds", () -> checkSettingInputBounds());
		step("checkChoiceDirection", () -> checkChoiceDirection());
		step("checkChronomatronModel", () -> checkChronomatronModel());
		step("checkExperimentSolverEngine", () -> checkExperimentSolverEngine());
		step("SequenceSolverChecks.run", () -> SequenceSolverChecks.run());
		step("checkDungeonMobEspOutlineWidthMigration", () -> checkDungeonMobEspOutlineWidthMigration());
		step("checkDungeonRoomOverlayLegacySettings", () -> checkDungeonRoomOverlayLegacySettings());
		step("checkFelTrackingRules", () -> checkFelTrackingRules());
		step("checkFelSkullTextureClassification", () -> checkFelSkullTextureClassification());
		step("checkDungeonMobLabelGrammar", () -> checkDungeonMobLabelGrammar());
		step("checkShadowAssassinPacketOrder", () -> checkShadowAssassinPacketOrder());
		step("checkFelSkullMarkerGeometry", () -> checkFelSkullMarkerGeometry());
		step("checkDungeonMobEncounterHistory", () -> checkDungeonMobEncounterHistory());
		step("checkDungeonMobTargetMemory", () -> checkDungeonMobTargetMemory());
		step("checkDungeonMobEspGroupControls", () -> checkDungeonMobEspGroupControls());
		step("checkAutoExperiments", () -> checkAutoExperiments());
		step("checkExperimentStateEdges", () -> checkExperimentStateEdges());
		step("MacroChecks.run", () -> MacroChecks.run());
		step("FolderTreeChecks.run", () -> FolderTreeChecks.run());
		step("BoundedHttpBodyReaderChecks.run", () -> BoundedHttpBodyReaderChecks.run());
		step("BlockEspChecks.run", () -> BlockEspChecks.run());
		step("TikiHelperChecks.main", () -> TikiHelperChecks.main(args));
		step("AutoKickChecks.main", () -> AutoKickChecks.main(args));
		step("BlockOutlineChecks.run", () -> BlockOutlineChecks.run());
		step("GardenPlotCullingChecks.run", () -> GardenPlotCullingChecks.run());
		step("InventoryButtonChecks.run", () -> InventoryButtonChecks.run());
		step("SlotIdBadgeLayoutChecks.run", () -> SlotIdBadgeLayoutChecks.run());
		step("ScratchBlockShapesChecks.run", () -> ScratchBlockShapesChecks.run());
		step("BoundedUndoHistoryChecks.run", () -> BoundedUndoHistoryChecks.run());
		step("ClickGuiMotionChecks.run", () -> ClickGuiMotionChecks.run());
		step("ThemeContrastChecks.run", () -> ThemeContrastChecks.run());
		step("ClickGuiScrollChecks.run", () -> ClickGuiScrollChecks.run());
		step("ClickGuiFlatChecks.run", () -> geiler.addons.client.gui.ClickGuiFlatChecks.run());
		step("ClickGuiClassicChecks.run", () -> geiler.addons.client.gui.ClickGuiClassicChecks.run());
		step("ClickGuiTextSelectionChecks.run", () -> geiler.addons.client.gui.ClickGuiTextSelectionChecks.run());
		step("ConfigProfileChecks.run", () -> ConfigProfileChecks.run());
		step("TreeTrackerChecks.main", () -> TreeTrackerChecks.main(args));
		step("InventoryButtonsModuleKotlinChecks.run", () -> InventoryButtonsModuleKotlinChecks.run());
		step("DungeonAssistModuleKotlinChecks.run", () -> DungeonAssistModuleKotlinChecks.run());
		step("ModuleDiagnosticsChecks.run", () -> ModuleDiagnosticsChecks.run());
		step("ModuleSettingsSpecChecks.run", () -> geiler.addons.client.module.ModuleSettingsSpecChecks.run());
		step("CodeStyleChecks.run", () -> geiler.addons.client.CodeStyleChecks.run());
		step("ChatReplyPreviewChecks.run", () -> geiler.addons.client.chat.ChatReplyPreviewChecks.run());
		step("DungeonSpawnAnchorChecks.run", () -> geiler.addons.client.dungeon.DungeonSpawnAnchorChecks.run());
		step("DungeonTicTacToeSuggestionHoldChecks.run", () -> geiler.addons.client.dungeon.helpers.DungeonTicTacToeSuggestionHoldChecks.run());
		step("SuperpairsClicksChecks.run", () -> geiler.addons.client.enchanting.SuperpairsClicksChecks.run());
		step("SuperpairsPairColorsChecks.run", () -> geiler.addons.client.enchanting.SuperpairsPairColorsChecks.run());
		step("ChatReplyDraftChecks.run", () -> geiler.addons.client.chat.ChatReplyDraftChecks.run());
		step("ChatReplyTagChecks.run", () -> geiler.addons.client.chat.ChatReplyTagChecks.run());
		step("ChatReplyGapChecks.run", () -> geiler.addons.client.chat.ChatReplyGapChecks.run());
		step("DungeonServerTickChecks.run", () -> geiler.addons.client.dungeon.helpers.DungeonServerTickChecks.run());
		step("KeyMappingMirrorChecks.run", () -> geiler.addons.client.module.KeyMappingMirrorChecks.run());
		step("ClickGuiGroupFlatteningChecks.run", () -> geiler.addons.client.gui.ClickGuiGroupFlatteningChecks.run());
		step("ChatStringSemanticsChecks.run", () -> geiler.addons.client.chat.ChatStringSemanticsChecks.run());
		step("ClickGuiInputChecks.run", () -> geiler.addons.client.gui.ClickGuiInputChecks.run());
		step("ClickGuiWorkspaceChecks.run", () -> geiler.addons.client.gui.ClickGuiWorkspaceChecks.run());
		step("SpikeWatchChecks.run", () -> geiler.addons.client.config.SpikeWatchChecks.run());
		step("RangeSettingChecks.run", () -> geiler.addons.client.module.RangeSettingChecks.run());
		step("EnchantRulesChecks.run", () -> geiler.addons.client.enchanting.EnchantRulesChecks.run());
		step("InventoryHudRulesChecks.run", () -> geiler.addons.client.inventory.InventoryHudRulesChecks.run());
		step("GardenPlotsRulesChecks.run", () -> geiler.addons.client.farming.GardenPlotsRulesChecks.run());
		step("ZoomRulesChecks.run", () -> geiler.addons.client.visual.ZoomRulesChecks.run());
		step("PanoramaChecks.run", () -> geiler.addons.client.visual.PanoramaChecks.run());
		step("CustomMainMenuChecks.run", CustomMainMenuChecks::run);
		step("ForagingWhispersChecks.run", () -> geiler.addons.client.foraging.ForagingWhispersChecks.run());
		step("AutoUpdateRulesChecks.run", () -> geiler.addons.client.update.AutoUpdateRulesChecks.run());
		step("MacroWorkspaceConfigChecks.run", () -> geiler.addons.client.macros.config.MacroWorkspaceConfigChecks.run());
		step("SimChecks.run", () -> geiler.addons.client.macros.sim.SimChecks.run());
		step("EtherwarpSimChecks.run", () -> geiler.addons.client.macros.sim.EtherwarpSimChecks.run());
		step("EtherwarpChecks.run", () -> geiler.addons.client.macros.sim.EtherwarpChecks.run());
		step("TargetPlannerChecks.run", () -> geiler.addons.client.macros.killer.TargetPlannerChecks.run());
		step("EtwChecks.run", () -> geiler.addons.client.macros.etw.EtwChecks.run());
		step("RotatorChecks.run", () -> geiler.addons.client.macros.rotation.RotatorChecks.run());
		step("SneakRuleChecks.run", () -> geiler.addons.client.macros.killer.SneakRuleChecks.run());
		step("RecoveryChecks.run", () -> geiler.addons.client.macros.killer.RecoveryChecks.run());
		step("InstantTransmissionChecks.run", () -> geiler.addons.client.macros.sim.InstantTransmissionChecks.run());
		step("TeleportItemChecks.run", () -> geiler.addons.client.macros.sim.TeleportItemChecks.run());
		step("ClusterChecks.run", () -> geiler.addons.client.macros.killer.ClusterChecks.run());
		step("HopWaitChecks.run", () -> geiler.addons.client.macros.navigation.HopWaitChecks.run());
		step("HopRulesChecks.run", () -> geiler.addons.client.macros.navigation.HopRulesChecks.run());
		step("ScanCutChecks.run", () -> geiler.addons.client.macros.killer.ScanCutChecks.run());
		step("SelectionChecks.run", () -> geiler.addons.client.macros.killer.SelectionChecks.run());
		step("FluidWalkChecks.run", () -> geiler.addons.client.macros.killer.FluidWalkChecks.run());
		step("ShutSweepChecks.run",() -> geiler.addons.client.macros.pathing.ShutSweepChecks.run());
		step("TeleportRulesChecks.run", () -> geiler.addons.client.macros.navigation.TeleportRulesChecks.run());
		step("WalkStatsChecks.run",() -> geiler.addons.client.macros.killer.WalkStatsChecks.run());
		step("ReplanChecks.run",() -> geiler.addons.client.macros.killer.ReplanChecks.run());
		step("GapChecks.run",() -> geiler.addons.client.macros.pathing.GapChecks.run());
		step("JumpGuardChecks.run", () -> geiler.addons.client.macros.input.JumpGuardChecks.run());
		step("PaintedAreaChecks.run", () -> geiler.addons.client.macros.area.PaintedAreaChecks.run());
		step("PaintMeshChecks.run", () -> geiler.addons.client.macros.area.PaintMeshChecks.run());
		step("PaintBrushLevelChecks.run", () -> geiler.addons.client.macros.area.PaintBrushLevelChecks.run());
		step("SpawnPlacementChecks.run", () -> geiler.addons.client.macros.command.SpawnPlacementChecks.run());
		step("EdgeGuardChecks.run", () -> geiler.addons.client.macros.navigation.EdgeGuardChecks.run());
		step("StepIndexChecks.run", () -> geiler.addons.client.macros.pathing.StepIndexChecks.run());
		step("RouteSmootherChecks.run", () -> geiler.addons.client.macros.pathing.RouteSmootherChecks.run());
		step("SearchTierChecks.run", () -> geiler.addons.client.macros.pathing.SearchTierChecks.run());
		step("SegmentRulesChecks.run", () -> geiler.addons.client.macros.killer.SegmentRulesChecks.run());
		step("TargetScenarioChecks.run", () -> geiler.addons.client.macros.killer.TargetScenarioChecks.run());
		step("InstantHopsChecks.run", () -> geiler.addons.client.macros.navigation.InstantHopsChecks.run());
		step("LookFocusChecks.run", () -> geiler.addons.client.macros.killer.LookFocusChecks.run());
		step("RecorderChecks.run", () -> geiler.addons.client.macros.record.RecorderChecks.run());
		step("InputChecks.run", () -> geiler.addons.client.macros.input.InputChecks.run());
		step("WalkKeysChecks.run", () -> geiler.addons.client.macros.input.WalkKeysChecks.run());
		step("PathingChecks.run", () -> geiler.addons.client.macros.pathing.PathingChecks.run());
		step("GateAreaChecks.run", () -> geiler.addons.client.macros.area.GateAreaChecks.run());
		step("KillerChecks.run", () -> geiler.addons.client.macros.killer.KillerChecks.run());
		if (!FAILURES.isEmpty()) {
			System.err.println(FAILURES.size() + " offline check(s) failed:");
			FAILURES.forEach((name, error) -> {
				System.err.println(" - " + name + ": " + error);
				error.printStackTrace();
			});
			System.exit(1);
		}
	}

	private static void checkFreshModuleEnableDefaults() {
		assertFalse(ModuleManager.modules().isEmpty(), "the production module catalog is registered for checks");
		for (Module module : ModuleManager.modules()) {
			assertFalse(module.defaultEnabled(), "registered feature module defaults off for a fresh config: "
				+ module.configName());
		}
		assertFalse(ModConfig.resolveModuleEnabledState(null, false),
			"a missing saved toggle keeps a newly separated module disabled");
		assertTrue(ModConfig.resolveModuleEnabledState(Boolean.TRUE, false),
			"an explicitly saved enabled state survives a false fresh default");
		assertFalse(ModConfig.resolveModuleEnabledState(Boolean.FALSE, true),
			"an explicitly saved disabled state beats a true fresh default");
		assertFalse(ModConfig.resolveModuleEnabledState(null, PlayerNamesModule.INSTANCE.defaultEnabled()),
			"a missing Player Names state keeps the newly separated module off");
		assertFalse(ModConfig.resolveModuleEnabledState(null, PlayerSizeModule.INSTANCE.defaultEnabled()),
			"a missing Player Size state keeps the newly separated module off");
	}

	private static void checkLegacyUtilityTimerMigration() {
		assertEquals(null, ModConfig.resolveLegacyUtilityTimerHud(null, null, null),
			"a fresh config with no legacy timer keys leaves General's HUD default alone");
		assertEquals(Boolean.TRUE, ModConfig.resolveLegacyUtilityTimerHud(null, Boolean.TRUE, null),
			"an enabled legacy timer keeps its default-on HUD when the old child key is missing");
		assertEquals(Boolean.FALSE, ModConfig.resolveLegacyUtilityTimerHud(Boolean.FALSE,
			Boolean.TRUE, Boolean.TRUE), "an explicit saved General HUD false beats legacy values");
		assertEquals(Boolean.TRUE, ModConfig.resolveLegacyUtilityTimerHud(Boolean.TRUE,
			Boolean.FALSE, Boolean.FALSE), "an explicit saved General HUD true beats legacy values");
		assertEquals(null, ModConfig.resolveLegacyUtilityTimerAlert(null, null, null),
			"a fresh config with no legacy alert keys leaves General's alert default alone");
		assertEquals(Boolean.FALSE, ModConfig.resolveLegacyUtilityTimerAlert(null,
			Boolean.FALSE, null), "a disabled legacy timer disables its migrated alert safely");
		assertEquals(Boolean.FALSE, ModConfig.resolveLegacyUtilityTimerAlert(Boolean.FALSE,
			Boolean.TRUE, Boolean.TRUE), "an explicit saved General alert false beats legacy values");
		assertEquals(Boolean.TRUE, ModConfig.resolveLegacyUtilityTimerAlert(Boolean.TRUE,
			Boolean.FALSE, Boolean.FALSE), "an explicit saved General alert true beats legacy values");

		GeneralModule general = GeneralModule.INSTANCE;
		Map<String, Boolean> enabled = new java.util.HashMap<>();
		enabled.put("Utility Timer", true);
		Map<String, Boolean> toggles = new java.util.HashMap<>();
		toggles.put("Utility Timer.Show Timer HUD", false);
		toggles.put("Utility Timer.Completion Alert", true);
		toggles.put("Utility Timer.Chat Alert", true);
		toggles.put("Utility Timer.Title Alert", false);
		toggles.put("Utility Timer.Sound Alert", true);
		Map<String, String> texts = new java.util.HashMap<>();
		texts.put("Utility Timer.Completion Text", "Timer finished");
		Map<String, Float> numbers = new java.util.HashMap<>();
		numbers.put("Utility Timer.Title Duration (ticks)", 37.0f);
		assertTrue(ModConfigMigrations.migrateLegacyUtilityTimer(enabled, toggles, texts, numbers),
			"the Kotlin config migration copies all legacy timer setting types");
		assertEquals(Boolean.FALSE, toggles.get("General." + general.showTimerHud().name()),
			"an explicit legacy timer HUD value is retained");
		assertEquals(Boolean.TRUE, toggles.get("General." + general.timerCompletionAlert().name()),
			"the legacy completion alert is retained");
		assertEquals(Boolean.TRUE, toggles.get("General." + general.timerCompletionChat().name()),
			"legacy chat alert is copied");
		assertEquals(Boolean.FALSE, toggles.get("General." + general.timerCompletionTitle().name()),
			"legacy title alert is copied");
		assertEquals(Boolean.TRUE, toggles.get("General." + general.timerCompletionSound().name()),
			"legacy sound alert is copied");
		assertEquals("Timer finished", texts.get("General." + general.timerCompletionText().name()),
			"legacy completion text is copied");
		assertEquals(37.0f, numbers.get("General." + general.timerTitleDurationTicks().name()),
			"legacy title duration is copied");

		Map<String, Boolean> precedence = new java.util.HashMap<>();
		precedence.put("Utility Timer.Chat Alert", false);
		precedence.put("General." + general.timerCompletionChat().name(), true);
		assertFalse(ModConfigMigrations.migrateLegacyUtilityTimer(new java.util.HashMap<>(), precedence,
			new java.util.HashMap<>(), new java.util.HashMap<>()),
			"already saved General timer settings are not overwritten by legacy values");
		assertEquals(Boolean.TRUE, precedence.get("General." + general.timerCompletionChat().name()),
			"the current General setting takes precedence over a conflicting legacy value");
	}

	private static void checkAdditionalLegacyConfigMigrations() {
		var channels = ChatChannelsModule.INSTANCE;
		var chat = ChatModule.INSTANCE;
		Map<String, Boolean> enabled = new java.util.HashMap<>();
		enabled.put(channels.configName(), true);
		Map<String, Boolean> chatToggles = new java.util.HashMap<>();
		chatToggles.put(channels.configName() + ".Show Channel History HUD", true);
		assertTrue(ModConfigMigrations.migrateLegacyChatHud(enabled, chatToggles)
			&& Boolean.TRUE.equals(enabled.get(chat.configName())),
			"legacy channel history visibility enables its standalone Chat successor");
		Map<String, Boolean> explicitChat = new java.util.HashMap<>();
		explicitChat.put(channels.configName(), true);
		explicitChat.put(chat.configName(), false);
		assertFalse(ModConfigMigrations.migrateLegacyChatHud(explicitChat, chatToggles)
			|| Boolean.TRUE.equals(explicitChat.get(chat.configName())),
			"an explicit standalone Chat disabled state beats the legacy channel HUD");
		Map<String, Boolean> hiddenHistory = new java.util.HashMap<>();
		hiddenHistory.put(channels.configName(), true);
		assertFalse(ModConfigMigrations.migrateLegacyChatHud(hiddenHistory,
			Map.of(channels.configName() + ".Show Channel History HUD", false)),
			"a disabled legacy channel history HUD does not enable standalone Chat");

		var alerts = geiler.addons.client.module.impl.DungeonRoomAlertModule.INSTANCE;
		Map<String, String> alertTexts = new java.util.HashMap<>();
		alertTexts.put(alerts.configName() + ".Sound ID", "legacy:room-alert");
		assertTrue(ModConfigMigrations.migrateDungeonRoomAlertSoundSettings(alertTexts)
			&& "legacy:room-alert".equals(alertTexts.get(alerts.configName() + ".Room Cleared Sound ID")),
			"the old Room Alerts sound ID migrates to the Room Cleared sound");
		alertTexts.put(alerts.configName() + ".Sound ID", "legacy:replacement");
		assertFalse(ModConfigMigrations.migrateDungeonRoomAlertSoundSettings(alertTexts)
			|| !"legacy:room-alert".equals(alertTexts.get(alerts.configName() + ".Room Cleared Sound ID")),
			"an explicit Room Cleared sound remains authoritative");
		Map<String, Boolean> sharedToggles = new java.util.HashMap<>();
		Map<String, Float> sharedNumbers = new java.util.HashMap<>();
		String alertPrefix = alerts.configName() + ".";
		sharedToggles.put(alertPrefix + "Show Title", false);
		sharedToggles.put(alertPrefix + "Play Sound", false);
		sharedNumbers.put(alertPrefix + "Sound Volume", 0.3f);
		sharedNumbers.put(alertPrefix + "Sound Pitch", 1.5f);
		assertTrue(ModConfigMigrations.migrateDungeonRoomAlertSharedSettings(sharedToggles, sharedNumbers)
			&& Boolean.FALSE.equals(sharedToggles.get(alertPrefix + "Secrets Show Title"))
			&& Boolean.FALSE.equals(sharedToggles.get(alertPrefix + "Secrets Play Sound"))
			&& Float.valueOf(0.3f).equals(sharedNumbers.get(alertPrefix + "Secrets Sound Volume"))
			&& Float.valueOf(1.5f).equals(sharedNumbers.get(alertPrefix + "Secrets Sound Pitch")),
			"values the two Room Alerts groups used to share carry over to the Secrets keys");
		sharedToggles.put(alertPrefix + "Secrets Show Title", true);
		assertFalse(ModConfigMigrations.migrateDungeonRoomAlertSharedSettings(sharedToggles, sharedNumbers)
			|| !Boolean.TRUE.equals(sharedToggles.get(alertPrefix + "Secrets Show Title")),
			"an explicit Secrets value stays authoritative");

		var blood = DungeonBloodCampModule.INSTANCE;
		String bloodPrefix = blood.configName() + ".";
		Map<String, Boolean> bloodToggles = new java.util.HashMap<>();
		bloodToggles.put(bloodPrefix + "Position Prediction", true);
		bloodToggles.put(bloodPrefix + "Target Tracer", false);
		bloodToggles.put(bloodPrefix + "World Countdown", true);
		bloodToggles.put(bloodPrefix + "Target Box", false);
		bloodToggles.put(bloodPrefix + "Chicken Man Destination Box", true);
		Map<String, int[]> bloodColors = new java.util.HashMap<>();
		Map<String, Float> bloodNumbers = new java.util.HashMap<>();
		bloodNumbers.put(bloodPrefix + "Marker Scale", 100.0f);
		assertTrue(ModConfigMigrations.migrateLegacyBloodCampSettings(bloodToggles, bloodColors, bloodNumbers),
			"legacy Blood Camp toggles, colors, and marker size migrate");
		assertEquals(Boolean.TRUE, bloodToggles.get(blood.configName() + ".Blood Camp Assist"),
			"legacy position prediction maps to Blood Camp Assist");
		assertEquals(Boolean.FALSE, bloodToggles.get(blood.configName() + ".Line"),
			"legacy target tracer maps to Line");
		assertEquals(Boolean.TRUE, bloodToggles.get(blood.configName() + ".Time Left"),
			"legacy world countdown maps to Time Left");
		int[] hiddenPositionColor = bloodColors.get(blood.configName() + "." + blood.positionColorSetting().name());
		int[] shownSpawnColor = bloodColors.get(blood.configName() + "." + blood.spawnColorSetting().name());
		assertTrue(hiddenPositionColor != null && hiddenPositionColor[0] == blood.positionColorSetting().red()
			&& hiddenPositionColor[1] == blood.positionColorSetting().green()
			&& hiddenPositionColor[2] == blood.positionColorSetting().blue() && hiddenPositionColor[3] == 0,
			"legacy Target Box visibility changes alpha and preserves the new RGB default");
		assertTrue(shownSpawnColor != null && shownSpawnColor[0] == blood.spawnColorSetting().red()
			&& shownSpawnColor[1] == blood.spawnColorSetting().green()
			&& shownSpawnColor[2] == blood.spawnColorSetting().blue() && shownSpawnColor[3] == 255,
			"legacy destination-box visibility retains its default RGB and enables alpha");
		assertEquals(blood.boxSizeSetting().max(), bloodNumbers.get(blood.configName() + ".Box Size"),
			"legacy marker scale is clamped to the current supported box-size range");
		Map<String, Float> invalidScale = new java.util.HashMap<>();
		invalidScale.put(bloodPrefix + "Marker Scale", Float.NaN);
		assertFalse(ModConfigMigrations.migrateLegacyBloodCampSettings(new java.util.HashMap<>(),
			new java.util.HashMap<>(), invalidScale),
			"non-finite legacy marker scales are ignored");

		var markers = CritterMarkersModule.INSTANCE;
		String markersPrefix = markers.configName() + ".";
		Map<String, Boolean> wallEnabled = new java.util.HashMap<>();
		wallEnabled.put("Hideonwall ESP", true);
		Map<String, Boolean> wallToggles = new java.util.HashMap<>();
		Map<String, int[]> wallColors = new java.util.HashMap<>();
		wallColors.put("Hideonwall ESP.Box Color", new int[] { 1, 2, 3, 255 });
		assertTrue(ModConfigMigrations.migrateLegacyHideonwallEsp(wallEnabled, wallToggles, wallColors),
			"the standalone Hideonwall ESP settings migrate into Critter Markers");
		assertEquals(Boolean.TRUE, wallToggles.get(markersPrefix + "Hideonwall Boxes"),
			"an enabled Hideonwall ESP turns on Hideonwall Boxes");
		assertEquals(Boolean.TRUE, wallEnabled.get(markers.configName()),
			"an enabled Hideonwall ESP enables Critter Markers when it had no saved state");
		assertTrue(java.util.Arrays.equals(new int[] { 1, 2, 3, 255 }, wallColors.get(markersPrefix + "Hideonwall Color")),
			"the Hideonwall box colour is copied");
		Map<String, Boolean> savedEnabled = new java.util.HashMap<>();
		savedEnabled.put("Hideonwall ESP", true);
		savedEnabled.put(markers.configName(), false);
		Map<String, Boolean> savedToggles = new java.util.HashMap<>();
		savedToggles.put(markersPrefix + "Hideonwall Boxes", false);
		assertFalse(ModConfigMigrations.migrateLegacyHideonwallEsp(savedEnabled, savedToggles, new java.util.HashMap<>())
			|| Boolean.TRUE.equals(savedEnabled.get(markers.configName()))
			|| Boolean.TRUE.equals(savedToggles.get(markersPrefix + "Hideonwall Boxes")),
			"saved Critter Markers values are never overwritten by the old module");
		Map<String, Boolean> offEnabled = new java.util.HashMap<>();
		offEnabled.put("Hideonwall ESP", false);
		Map<String, Boolean> offToggles = new java.util.HashMap<>();
		ModConfigMigrations.migrateLegacyHideonwallEsp(offEnabled, offToggles, new java.util.HashMap<>());
		assertEquals(Boolean.FALSE, offToggles.get(markersPrefix + "Hideonwall Boxes"),
			"a disabled Hideonwall ESP leaves Hideonwall Boxes off");
		assertFalse(offEnabled.containsKey(markers.configName()),
			"a disabled Hideonwall ESP does not enable Critter Markers");

		Map<String, float[]> hudPositions = new java.util.HashMap<>();
		float[] oldPosition = { 0.25f, 0.75f, 1.2f };
		hudPositions.put("dungeon_odin_helpers", oldPosition);
		assertTrue(ModConfigMigrations.migrateLegacyDungeonHudPosition(hudPositions)
			&& !hudPositions.containsKey("dungeon_odin_helpers")
			&& java.util.Arrays.equals(new float[] { 0.25f, 0.75f, 1.2f }, hudPositions.get("dungeon_run_splits"))
			&& hudPositions.get("dungeon_run_splits") != oldPosition,
			"the old combined dungeon HUD position moves to Run Splits as a defensive copy");
		float[] explicitPosition = { 0.5f, 0.5f, 2.0f };
		Map<String, float[]> positionsWithCurrent = new java.util.HashMap<>();
		positionsWithCurrent.put("dungeon_odin_helpers", oldPosition);
		positionsWithCurrent.put("dungeon_run_splits", explicitPosition);
		assertTrue(ModConfigMigrations.migrateLegacyDungeonHudPosition(positionsWithCurrent)
			&& positionsWithCurrent.get("dungeon_run_splits") == explicitPosition,
			"an explicit Run Splits HUD position takes precedence over the legacy position");
	}

	private static void checkLegacyPresentationAndSecretMigrations() {
		Map<String, Boolean> enabled = new java.util.HashMap<>();
		enabled.put("Player Presentation", true);
		enabled.put(PlayerNamesModule.INSTANCE.configName(), false);
		Map<String, Boolean> presentationToggles = new java.util.HashMap<>();
		presentationToggles.put("Player Presentation.Replace Names", true);
		presentationToggles.put("Player Presentation.Apply Name Styles", false);
		presentationToggles.put("Player Presentation.Apply Sizes", true);
		presentationToggles.put("Player Presentation.Use Hosted Sizes", false);
		assertTrue(ModConfigMigrations.migrateLegacyPlayerPresentation(enabled, presentationToggles),
			"legacy presentation settings migrate into the split modules");
		assertEquals(Boolean.FALSE, enabled.get(PlayerNamesModule.INSTANCE.configName()),
			"an explicit Player Names enable choice takes precedence over the former master toggle");
		assertEquals(null, enabled.get(PlayerSizeModule.INSTANCE.configName()),
			"the former combined enable toggle is not migrated into Player Size");
		assertEquals(Boolean.TRUE, enabled.get("Player Presentation"),
			"the former presentation master toggle remains preserved in the loaded data");
		assertEquals(Boolean.TRUE, presentationToggles.get(PlayerNamesModule.INSTANCE.configName() + ".Replace Names"),
			"the old name replacement setting is copied");
		assertEquals(Boolean.FALSE, presentationToggles.get(PlayerNamesModule.INSTANCE.configName() + ".Apply Name Styles"),
			"the old name style setting is copied");
		assertEquals(Boolean.TRUE, presentationToggles.get(PlayerSizeModule.INSTANCE.configName() + ".Use Hosted Sizes"),
			"the original first size setting wins when two legacy labels map to the same new setting");

		String waypointPrefix = SecretWaypointsModule.INSTANCE.configName() + ".";
		String clickedPrefix = SecretClickedModule.INSTANCE.configName() + ".";
		Map<String, Boolean> toggles = new java.util.HashMap<>();
		toggles.put("Secret Waypoints.Chest Candidates", false);
		toggles.put("Dungeon Secret Waypoints.Chest Candidates", true);
		toggles.put("Dungeon Secret Waypoints.Bat Candidates", true);
		toggles.put("Secret Waypoints.Chime on Marked Click", true);
		Map<String, int[]> colors = new java.util.HashMap<>();
		int[] legacyColor = {12, 34, 56, 255};
		colors.put("Dungeon Secret Waypoints.Candidate Color", legacyColor);
		Map<String, Float> numbers = new java.util.HashMap<>();
		numbers.put("Secret Waypoints.Click Feedback Window (ms)", 175.0f);
		Map<String, String> texts = new java.util.HashMap<>();
		texts.put("Secret Waypoints.Click Chime", "minecraft:block.note_block.pling");
		assertTrue(ModConfigMigrations.migrateSecretSettingNames(toggles, colors, numbers, texts),
			"renamed Secret Waypoint and Secret Clicked settings migrate across their typed maps");
		assertEquals(Boolean.FALSE, toggles.get(waypointPrefix + "Chests"),
			"the current Secret Waypoints key wins over the older dungeon module alias");
		assertEquals(Boolean.TRUE, toggles.get(waypointPrefix + "Bats"),
			"the older dungeon module alias is used when no newer source key exists");
		assertEquals(legacyColor, colors.get(waypointPrefix + "Live Marker Color"),
			"the legacy live marker color is retained in the color setting map");
		assertEquals(Boolean.TRUE, toggles.get(clickedPrefix + "Play Chime"),
			"the marked click chime toggle migrates into Secret Clicked");
		assertEquals(175.0f, numbers.get(clickedPrefix + "Confirmation Window (ms)"),
			"the click feedback confirmation window migrates into the number map");
		assertEquals("minecraft:block.note_block.pling", texts.get(clickedPrefix + "Chime Sound"),
			"the configured click chime migrates into the text map");

		Map<String, Boolean> savedPrecedence = new java.util.HashMap<>();
		savedPrecedence.put("Secret Waypoints.Chest Candidates", false);
		savedPrecedence.put(waypointPrefix + "Chests", true);
		assertFalse(ModConfigMigrations.migrateSecretSettingNames(savedPrecedence,
			new java.util.HashMap<>(), new java.util.HashMap<>(), new java.util.HashMap<>()),
			"an already saved renamed setting is not overwritten by its legacy value");
		assertEquals(Boolean.TRUE, savedPrecedence.get(waypointPrefix + "Chests"),
			"the current Secret Waypoints setting value takes precedence");
	}

	private static void checkModuleKeybinds() {
		try {
			ModuleKeybind.class.getConstructor(InputConstants.Key.class, int.class);
			assertTrue(ModuleKeybind.class.getMethod("key").getReturnType() == InputConstants.Key.class
				&& ModuleKeybind.class.getMethod("modifiers").getReturnType() == int.class,
				"Kotlin keybind keeps the Java constructor and record-style component accessors");
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError("Kotlin keybind keeps the Java constructor and accessors", failure);
		}
		ModuleKeybind bind = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_G, 0, InputConstants.MOD_CONTROL));
		assertTrue(bind.isBound(), "a key event creates a bound module keybind");
		assertTrue(bind.matches(new KeyEvent(InputConstants.KEY_G, 0, InputConstants.MOD_CONTROL)),
			"the matching modifier combination activates the bind");
		assertFalse(bind.matches(new KeyEvent(InputConstants.KEY_G, 0, 0)),
			"a missing modifier does not activate a combination bind");
		assertFalse(new ModuleKeybind(InputConstants.getKey(new KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)), 0).isBound(),
			"Escape is reserved for clearing a bind");
		assertTrue(new ModuleKeybind(null, InputConstants.MOD_CONTROL).equals(ModuleKeybind.NONE),
			"null keys normalize to the unbound key with no modifiers");
		assertTrue(new ModuleKeybind(InputConstants.getKey(new KeyEvent(InputConstants.KEY_G, 0, 0)),
			InputConstants.MOD_CONTROL | 0x80).modifiers() == InputConstants.MOD_CONTROL,
			"unknown modifier bits are discarded by the Java-compatible constructor");
		assertTrue(ModuleKeybind.from(null).equals(ModuleKeybind.NONE)
			&& ModuleKeybind.fromMouse(-1, 0).equals(ModuleKeybind.NONE)
			&& ModuleKeybind.fromMouse(8, 0).equals(ModuleKeybind.NONE),
			"missing keyboard events and out-of-range mouse buttons remain unbound");
		ModuleKeybind mouseBind = ModuleKeybind.fromMouse(2, InputConstants.MOD_SHIFT | 0x80);
		assertTrue(mouseBind.matchesMouse(2, InputConstants.MOD_SHIFT)
			&& !mouseBind.matchesMouse(2, InputConstants.MOD_CONTROL),
			"mouse binds preserve their supported modifier mask and exact matching behavior");
		assertTrue(ModuleKeybind.NONE.displayName().equals("None"), "unbound modules display None");
		ModuleKeybind equalBind = new ModuleKeybind(bind.key(), bind.modifiers());
		assertTrue(bind.equals(equalBind) && bind.hashCode() == equalBind.hashCode()
			&& bind.toString().equals("ModuleKeybind[key=" + bind.key() + ", modifiers=" + bind.modifiers() + "]"),
			"keybind value equality, hash, and record-style string output remain stable");
		checkDungeonMobEspKeybindMigration();
	}

	private static void checkDungeonMobEspKeybindMigration() {
		ModuleKeybind starred = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_G, 0, 0));
		ModuleKeybind miniboss = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_H, 0, 0));
		DungeonMobEspKeybindMigration.Resolution currentWins = DungeonMobEspKeybindMigration.resolve(
			true, starred, miniboss, ModuleKeybind.NONE);
		assertTrue(currentWins.keybind().equals(starred) && !currentWins.migrated() && !currentWins.conflict(),
			"an existing combined Dungeon Mob ESP bind wins over legacy values");
		DungeonMobEspKeybindMigration.Resolution explicitNoneWins = DungeonMobEspKeybindMigration.resolve(
			true, ModuleKeybind.NONE, starred, miniboss);
		assertTrue(explicitNoneWins.keybind().equals(ModuleKeybind.NONE) && !explicitNoneWins.migrated(),
			"an explicitly saved unbound combined module is not repopulated from legacy binds");
		DungeonMobEspKeybindMigration.Resolution oneLegacyBind = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, ModuleKeybind.NONE);
		assertTrue(oneLegacyBind.keybind().equals(starred) && oneLegacyBind.migrated()
			&& !oneLegacyBind.conflict(), "one non-none legacy bind migrates to the combined module");
		DungeonMobEspKeybindMigration.Resolution equalLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, starred);
		assertTrue(equalLegacyBinds.keybind().equals(starred) && equalLegacyBinds.migrated()
			&& !equalLegacyBinds.conflict(), "equal legacy binds migrate once without ambiguity");
		DungeonMobEspKeybindMigration.Resolution conflictingLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, miniboss);
		assertTrue(conflictingLegacyBinds.keybind().equals(ModuleKeybind.NONE)
			&& conflictingLegacyBinds.migrated() && conflictingLegacyBinds.conflict(),
			"conflicting legacy binds resolve to no bind and mark the migration as ambiguous");
		DungeonMobEspKeybindMigration.Resolution noLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, ModuleKeybind.NONE, ModuleKeybind.NONE);
		assertTrue(noLegacyBinds.keybind().equals(ModuleKeybind.NONE) && !noLegacyBinds.migrated(),
			"no legacy bind leaves the combined module unbound");
	}

	private static void checkDungeonMobEspOutlineWidthMigration() {
		DungeonMobEspModule module = DungeonMobEspModule.INSTANCE;
		NumberSetting starred = module.numberSettings().stream()
			.filter(setting -> setting.name().equals("Starred Outline Width"))
			.findFirst().orElseThrow();
		NumberSetting miniboss = module.numberSettings().stream()
			.filter(setting -> setting.name().equals("Miniboss Outline Width"))
			.findFirst().orElseThrow();
		NumberSetting starredRing = module.numberSettings().stream()
			.filter(setting -> setting.name().equals("Starred Ring Line Width"))
			.findFirst().orElseThrow();

		starred.setValue(2.0f);
		miniboss.setValue(2.0f);
		module.restoreLegacySettings(Map.of(
			"Dungeon Mob ESP.Starred Line Width", 12.5f,
			"Dungeon Mob ESP.Miniboss Line Width", 18.5f), Map.of(), Map.of(), Map.of());
		assertEquals(12.5f, starred.value(), "the starred outline width restores its legacy setting key");
		assertEquals(18.5f, miniboss.value(), "the miniboss outline width restores its legacy setting key");
		assertEquals(25.0f, starred.max(), "the non-ring outline width supports the requested maximum");
		assertEquals(25.0f, miniboss.max(), "both non-ring outlines share the expanded range");
		assertEquals(5.0f, starredRing.max(), "the independent ring line-width range is unchanged");

		starred.setValue(7.0f);
		module.restoreLegacySettings(Map.of(
			"Dungeon Mob ESP.Starred Outline Width", 7.0f,
			"Dungeon Mob ESP.Starred Line Width", 18.0f), Map.of(), Map.of(), Map.of());
		assertEquals(7.0f, starred.value(), "a saved new outline-width key wins over the compatibility alias");
		starred.setValue(2.0f);
		miniboss.setValue(2.0f);
	}

	private static void checkDungeonRoomOverlayLegacySettings() {
		DungeonRoomOverlayModule module = DungeonRoomOverlayModule.INSTANCE;
		ChoiceSetting shadowMode = module.choiceSettings().stream()
			.filter(setting -> setting.name().equals("Text Shadow"))
			.findFirst().orElseThrow();
		String originalMode = shadowMode.value();
		String shadowKey = module.configName() + ".Text Shadow";
		try {
			shadowMode.setValue("Outline");
			assertFalse(module.restoreLegacySettings(Map.of(), Map.of(), Map.of()),
				"an empty legacy config does not migrate anything or throw on a missing text-shadow choice");
			assertEquals("Outline", shadowMode.value(), "an absent legacy field preserves the current text-shadow mode");
			assertFalse(module.restoreLegacySettings(null, null, null),
				"null legacy maps are handled without changing current map settings");
			assertEquals("Outline", shadowMode.value(), "null legacy maps preserve the current text-shadow mode");

			assertTrue(module.restoreLegacySettings(Map.of(shadowKey, true), Map.of(), Map.of()),
				"a legacy shadow toggle migrates when no current choice exists");
			assertEquals("Drop", shadowMode.value(), "legacy enabled shadow maps to Drop");

			shadowMode.setValue("Outline");
			assertFalse(module.restoreLegacySettings(Map.of(shadowKey, false), Map.of(),
				Map.of(shadowKey, "Outline")), "a valid current choice suppresses the legacy toggle");
			assertEquals("Outline", shadowMode.value(), "the valid current choice wins over the legacy toggle");

			assertTrue(module.restoreLegacySettings(Map.of(shadowKey, false), Map.of(),
				Map.of(shadowKey, "Invalid")), "an invalid current choice falls back to the legacy toggle");
			assertEquals("None", shadowMode.value(), "legacy disabled shadow maps to None");
		} finally {
			shadowMode.setValue(originalMode);
		}
	}

	private static void checkFelTrackingRules() {
		assertTrue(DungeonMobEspSupport.isFelName("Fel"),
			"the singular nameplate on the spawned Enderman is recognized as a Fel");
		assertTrue(DungeonMobEspSupport.isFelName("✯ Fel 100,000❤"),
			"a starred Fel nameplate is classified before generic starred scanning");
		assertTrue(DungeonMobEspSupport.isFelName("Fels"),
			"the plural Fel label remains supported for older nameplate variants");
		assertFalse(DungeonMobEspSupport.isFelName("Enderman"),
			"ordinary Endermen do not become Fel targets from entity type alone");
		assertTrue(DungeonMobEspSupport.shouldHighlightFel(true, true),
			"a starred Fel is highlighted as soon as its body is detected");
		assertFalse(DungeonMobEspSupport.shouldHighlightFel(true, false),
			"an unstarred Fel is never added to starred highlights");
		assertFalse(DungeonMobEspSupport.shouldHighlightFel(false, true),
			"disabling Fel highlighting removes starred Fels from Fel highlights");
		assertTrue(DungeonMobEspSupport.shouldClassifyFelsForStarFilter(true, false),
			"Starred ESP keeps classifying Fel bodies so disabling the Fel group cannot re-label starred Fels");
		assertFalse(DungeonMobEspSupport.isStarredGroupTarget(true, false),
			"a recognized Fel body is excluded from the generic Starred group even when the Fel group is off");
		assertTrue(DungeonMobEspSupport.isShadowAssassin("✯ Shadow Assassin 100,000❤"),
			"a starred Shadow Assassin nameplate normalizes to the miniboss target");
		assertTrue(DungeonMobEspSupport.isShadowAssassinProfileName("Shadow Assassin"),
			"the exact NPC profile name independently identifies a hidden Shadow Assassin body");
		assertFalse(DungeonMobEspSupport.isShadowAssassinProfileName("ShadowAssassin"),
			"profile fallback does not misclassify a merely similar player username");
		assertFalse(DungeonMobEspSupport.isShadowAssassinProfileName("shadow assassin"),
			"profile fallback preserves the source's exact case-sensitive NPC name");
		assertTrue(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"✯ Shadow Assassin 100,000❤", 20, 19, true, false, 9.0),
			"the exact normalized armor-stand label resolves to its nearby preceding living body");
		assertFalse(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Lost Adventurer", 20, 19, true, false, 1.0),
			"another miniboss label cannot claim the Shadow Assassin relationship");
		assertFalse(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Shadow Assassin", 20, 18, true, false, 1.0),
			"a living entity that is not directly before the label is rejected");
		assertFalse(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Shadow Assassin", 20, 19, false, false, 1.0),
			"non-living entity candidates are rejected");
		assertFalse(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Shadow Assassin", 20, 19, true, true, 1.0),
			"an armor stand cannot be associated as the mob body");
		assertTrue(DungeonMobEspSupport.isNamedShadowAssassinArmorStandAssociation(
			"Shadow Assassin", 20, 19, true, false, 9.01),
			"the exact entity-ID association is not rejected by distance");
		var shadowBounds = DungeonMobEspSupport.fullShadowAssassinBounds(3, 70, -4);
		assertTrue(Math.abs(shadowBounds.getXsize() - 0.8) < 0.0001,
			"the Shadow Assassin box spans the full 0.8-block body width");
		assertTrue(Math.abs(shadowBounds.getYsize() - 2.0) < 0.0001,
			"the Shadow Assassin box spans the full two-block body height");
		ArmorStand interpolatedBody = OfflineEntityFixtures.armorStand(0, 70, 0, 0.8, 2.0, 0.8);
		OfflineEntityFixtures.moveCurrent(interpolatedBody, 4, 72, -2);
		var interpolatedPosition = interpolatedBody.getPosition(0.5f);
		var interpolatedBounds = DungeonMobEspSupport.fullShadowAssassinBounds(interpolatedBody, 0.5f);
		assertTrue(Math.abs(interpolatedPosition.x - (interpolatedBounds.minX + interpolatedBounds.maxX) / 2.0) < 0.0001,
			"the Shadow Assassin render box follows the body's partial-tick X position");
		assertTrue(Math.abs(interpolatedPosition.y - interpolatedBounds.minY) < 0.0001,
			"the Shadow Assassin render box starts at the body's partial-tick Y position");
		assertTrue(Math.abs(interpolatedPosition.z - (interpolatedBounds.minZ + interpolatedBounds.maxZ) / 2.0) < 0.0001,
			"the Shadow Assassin render box follows the body's partial-tick Z position");
		assertTrue(DungeonMobEspSupport.isNamedMiniboss("Shadow Assassin"),
			"Shadow Assassin remains in the normalized miniboss roster");
		assertFalse(DungeonMobEspSupport.isShadowAssassin("Lost Adventurer"),
			"other minibosses do not receive the full Shadow Assassin body bounds");
		assertTrue(DungeonMobEspSupport.shouldRetainRoomFootprint(true, true),
			"temporary map-snapshot loss retains occupancy while the player remains inside the detected room");
		assertFalse(DungeonMobEspSupport.shouldRetainRoomFootprint(true, false),
			"walking outside the retained footprint confirms room exit and clears targets");
		assertFalse(DungeonMobEspSupport.shouldRetainRoomFootprint(false, true),
			"a world change never carries the previous room footprint forward");
	}

	private static void checkFelSkullTextureClassification() {
		assertTrue(DungeonMobEspSupport.isFelSkullMarker(true, true, FEL_HEAD_TEXTURE_FOR_CHECKS),
			"a marker with a head carrying the exact Skyblocker HeadTextures.FEL value is classified");
		assertFalse(DungeonMobEspSupport.isFelSkullMarker(true, true, FEL_HEAD_TEXTURE_FOR_CHECKS + "x"),
			"a partial Fel texture value does not match");
		assertFalse(DungeonMobEspSupport.isFelSkullMarker(true, true, null),
			"a missing texture is unavailable rather than a Fel skull");
		assertFalse(DungeonMobEspSupport.isFelSkullMarker(false, true, FEL_HEAD_TEXTURE_FOR_CHECKS),
			"an ordinary armor stand is not a Fel marker even with the matching head texture");
		assertFalse(DungeonMobEspSupport.isFelSkullMarker(true, false, FEL_HEAD_TEXTURE_FOR_CHECKS),
			"a marker without a head item is not a Fel skull");
	}

	private static void checkDungeonMobLabelGrammar() {
		assertTrue("fels".equals(DungeonMobEspSupport.normalizedMobName("✯ [Lv80] Fels 25k❤")),
			"a star before a supported dungeon level token does not hide a stationary Fel name");
		assertTrue("fels".equals(DungeonMobEspSupport.normalizedMobName("Fels 16k/25k❤")),
			"a current/max health token is stripped as one final token from a Fel nameplate");
		assertTrue("fels".equals(DungeonMobEspSupport.normalizedMobName("\uE073 Healthy Fels 2.6M❤")),
			"a Hypixel private-use attribute glyph is removed before the Healthy prefix is parsed");
		assertTrue(DungeonMobEspSupport.isNamedMiniboss("[Lv80] ✯ Flaming Lost Adventurer 16k/25k❤"),
			"the upstream dungeon level, star, attribute, and current/max-health form matches the exact miniboss roster");
		assertTrue(DungeonMobEspSupport.isNamedMiniboss("✯ [Lv 80] Frozen Adventurer 25k❤"),
			"a rendered level token with an internal separator does not prevent a supported miniboss match");
		assertFalse(DungeonMobEspSupport.isNamedMiniboss("Ancient Lost Adventurer 16k/25k❤"),
			"unknown text is not accepted by fuzzy miniboss substring matching");
	}

	private static void checkShadowAssassinPacketOrder() {
		java.util.UUID profile = java.util.UUID.randomUUID();
		DungeonMinibossProfileTracker tracker = new DungeonMinibossProfileTracker();
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		tracker.onPlayerSpawn(profile, 71, true);
		assertTrue(tracker.isTrackedEntity(71),
			"player-info received before the player spawn is correlated to the Shadow Assassin body");
		tracker.onPlayerSpawn(profile, 71, true);
		assertEquals(1, tracker.trackedCount(), "a duplicate spawn packet does not duplicate the tracked body");
		tracker.onPlayerSpawn(profile, 711, true);
		assertTrue(tracker.isTrackedEntity(711) && !tracker.isTrackedEntity(71)
			&& tracker.trackedCount() == 1,
			"a replacement spawn for the same profile removes its stale entity ID");
		tracker.clear();
		tracker.onPlayerSpawn(profile, 72, true);
		assertFalse(tracker.isTrackedEntity(72),
			"a player spawn waits for its matching profile instead of guessing by proximity");
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		assertTrue(tracker.isTrackedEntity(72),
			"a later player-info packet resolves an earlier pending player spawn");
		tracker.onPlayerSpawn(profile, 721, true);
		assertTrue(tracker.isTrackedEntity(721) && !tracker.isTrackedEntity(72)
			&& tracker.trackedCount() == 1,
			"spawn-after-info replacement also leaves only the newest body ID");
		tracker.onEntityRemoved(72);
		assertTrue(tracker.isTrackedEntity(721), "a stale removal cannot clear the replacement body ID");
		tracker.onEntityRemoved(721);
		assertFalse(tracker.isTrackedEntity(721), "the entity-removal packet clears the correlated body ID");
		tracker.onPlayerSpawn(profile, 73, true);
		tracker.onPlayerInfo(profile, "ShadowAssassin");
		assertFalse(tracker.isTrackedEntity(73), "a similar profile name is not accepted as the exact NPC identity");
		tracker.onPlayerInfo(profile, "Shadow Assassin");
		tracker.onPlayerSpawn(profile, 74, true);
		tracker.clear();
		assertEquals(0, tracker.trackedCount(), "disconnect and world reset clear every tracked NPC body");
	}

	private static void checkFelSkullMarkerGeometry() {
		var bounds = FelSkullMarkerGeometry.bounds(4.0, 70.0, -3.0);
		assertTrue(Math.abs((bounds.maxX() - bounds.minX()) - 0.6) < 0.0001,
			"Fel waypoint uses the upstream 0.6-block X width");
		assertTrue(Math.abs((bounds.maxY() - bounds.minY()) - 0.6) < 0.0001,
			"Fel waypoint uses the upstream 0.6-block Y height");
		assertTrue(Math.abs((bounds.maxZ() - bounds.minZ()) - 0.6) < 0.0001,
			"Fel waypoint uses the upstream 0.6-block Z width");
		assertTrue(Math.abs(bounds.centerX() - 4.0) < 0.0001,
			"Fel waypoint remains horizontally centered on the base entity");
		assertTrue(Math.abs(bounds.centerY() - 71.27) < 0.0001,
			"Fel waypoint uses the skull anchor one block above the base entity");
		assertTrue(Math.abs(bounds.centerZ() - -3.0) < 0.0001,
			"Fel waypoint remains centered in the base entity's block");
	}

	private static void checkDungeonMobTargetMemory() {
		DungeonMobTargetMemory memory = new DungeonMobTargetMemory();
		var bounds = new DungeonMobTargetMemory.Bounds(1, 2, 3, 2, 4, 5);
		var first = new DungeonMobTargetMemory.Target(java.util.UUID.randomUUID(), 7,
			DungeonMobTargetMemory.Group.MINIBOSS, "minecraft:player", "Shadow Assassin", bounds, "F7:room-a", 100);
		assertTrue(memory.update(List.of(first), "F7:room-a", 100,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty(),
			"currently matched dungeon entities render live rather than as ghosts");
		assertTrue(memory.update(List.of(), "F7:room-a", 101,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty(),
			"an unloaded entity is removed immediately after name detection drops");
		assertEquals(0, memory.size(), "an unloaded entity does not leave a hidden ghost entry");
		memory.update(List.of(first), "F7:room-a", 102,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> first);
		assertTrue(memory.update(List.of(), "F7:room-a", 103,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_GONE, ignored -> null).isEmpty(),
			"a loaded anchor with no living matching entity clears the remembered target");
		assertEquals(0, memory.size(), "confirmed loaded absence does not leave a hidden ghost entry");
		var clearedRoomTarget = new DungeonMobTargetMemory.Target(java.util.UUID.randomUUID(), 11,
			DungeonMobTargetMemory.Group.MINIBOSS, "minecraft:mob", "Mini Boss", bounds, "F7:room-a", 150);
		memory.update(List.of(clearedRoomTarget), "F7:room-a", 150,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		memory.clear();
		assertEquals(0, memory.size(), "a confirmed room clear removes all remembered dungeon targets");

		var second = new DungeonMobTargetMemory.Target(first.uuid(), first.entityId(), first.group(), first.type(),
			first.label(), first.bounds(), first.roomKey(), 200);
		memory.update(List.of(second), "F7:room-a", 200,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		var moved = new DungeonMobTargetMemory.Target(second.uuid(), second.entityId(), second.group(), second.type(),
			second.label(), new DungeonMobTargetMemory.Bounds(11, 12, 13, 12, 14, 15), second.roomKey(), 201);
		assertEquals(List.of(moved), memory.update(List.of(), "F7:room-a", 201,
			ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> moved),
			"a still-loaded living entity stays visible and its remembered bounds follow its current position");
		var third = new DungeonMobTargetMemory.Target(first.uuid(), first.entityId(), first.group(), first.type(),
			first.label(), first.bounds(), first.roomKey(), 300);
		memory.update(List.of(third), "F7:room-a", 300,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		assertTrue(memory.update(List.of(), "F7:room-b", 301,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty(),
			"room transitions clear cached positions instead of carrying mobs across rooms");
		var fourth = new DungeonMobTargetMemory.Target(first.uuid(), first.entityId(), first.group(), first.type(),
			first.label(), first.bounds(), first.roomKey(), 400);
		memory.update(List.of(fourth), "F7:room-a", 400,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
		assertTrue(memory.update(List.of(), "F7:room-a", 401,
			ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null).isEmpty(),
			"unloaded targets are removed immediately rather than waiting for a timeout");
		for (DungeonMobTargetMemory.Group group : List.of(DungeonMobTargetMemory.Group.STARRED,
			DungeonMobTargetMemory.Group.FEL_SKULL, DungeonMobTargetMemory.Group.FEL_MOVING)) {
			memory.clear();
			var grouped = new DungeonMobTargetMemory.Target(java.util.UUID.randomUUID(), 9, group,
				"minecraft:mob", group.name(), bounds, "F7:room-a", 500);
			memory.update(List.of(grouped), "F7:room-a", 500,
				ignored -> DungeonMobTargetMemory.Presence.UNLOADED, ignored -> null);
			var refreshedGroup = new DungeonMobTargetMemory.Target(grouped.uuid(), grouped.entityId(), group,
				grouped.type(), grouped.label(), grouped.bounds(), grouped.roomKey(), 501);
			assertEquals(List.of(refreshedGroup), memory.update(List.of(), "F7:room-a", 501,
				ignored -> DungeonMobTargetMemory.Presence.LOADED_ALIVE, ignored -> refreshedGroup),
				"loaded-alive refresh supports independent " + group + " targets");
		}
	}

	private static void checkDungeonMobEncounterHistory() {
		DungeonMobEncounterHistory history = new DungeonMobEncounterHistory();
		history.observe("F7:room-a", 1.1, 70.0, 2.2);
		history.observe("F7:room-a", 1.8, 70.2, 2.9);
		history.observe("F7:room-b", 50.1, 70.0, 50.2);
		assertEquals(1, history.positions("F7:room-a").size(),
			"repeated sightings in the same block cell deduplicate when revisiting a room");
		assertTrue(DungeonMobEspSupport.nearAnyStarredPosition(5, 70, 2,
			history.positions("F7:room-a"), 4),
			"the original room's encounter evidence is available after an intervening room visit");
		assertFalse(DungeonMobEspSupport.nearAnyStarredPosition(5, 70, 2,
			history.positions("F7:room-b"), 4),
			"starred encounter evidence is isolated to the room where it was observed");
		history.clearRoom("F7:room-a");
		assertTrue(history.positions("F7:room-a").isEmpty()
			&& history.positions("F7:room-b").size() == 1,
			"clearing one room removes only that room's starred history");
		for (int index = 0; index <= DungeonMobEncounterHistory.MAX_POSITIONS_PER_ROOM; index++) {
			history.observe("F7:bounded", index + 0.1, 70, 0.1);
		}
		assertEquals(DungeonMobEncounterHistory.MAX_POSITIONS_PER_ROOM,
			history.positions("F7:bounded").size(), "each physical room's encounter history is bounded");
		for (int index = 0; index <= DungeonMobEncounterHistory.MAX_ROOMS; index++) {
			history.observe("room-" + index, index, 70, 0);
		}
		assertEquals(DungeonMobEncounterHistory.MAX_ROOMS, history.roomCount(),
			"the run-level room history has a bounded number of room keys");
	}

	private static void checkDungeonMobEspGroupControls() {
		DungeonMobEspModule module = DungeonMobEspModule.INSTANCE;
		for (String name : List.of("Starred Group Enabled", "Minibosses Enabled", "Fels Enabled",
			"Starred Only Current Room", "Miniboss Only Current Room", "Fels Only Current Room",
			"Highlight Hidden Fel Skulls")) {
			assertTrue(module.booleanSettings().stream().anyMatch(setting -> setting.name().equals(name)),
				"Dungeon Mob ESP exposes an independent group/current-room control: " + name);
		}
		assertTrue(module.choiceSettings().stream().anyMatch(setting -> setting.name().equals("Fel Style")),
			"stationary Fels expose their own ESP style selector");
		for (String name : List.of("Fel Outline Color", "Fel Fill Color", "Fel Tracer Color")) {
			assertTrue(module.colorSettings().stream().anyMatch(setting -> setting.name().equals(name)),
				"stationary Fels expose independent color control: " + name);
		}
		for (String heading : List.of("Starred Mobs", "Minibosses", "Fels")) {
			assertTrue(module.groups().stream().anyMatch(group -> heading.equals(group.name()) && group.toggle() != null),
				"Dungeon Mob ESP presents the group enable setting directly on its header: " + heading);
		}
	}

	private static void checkExperimentStateEdges() {
		assertSame(ExperimentPhase.ROUND_COMPLETE,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "§eRound Complete"),
			"Round Complete is not swallowed by the terminal complete check");
		assertSame(ExperimentPhase.COMPLETE,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "Max clicks reached!"),
			"max-click status remains terminal");
		assertSame(ExperimentPhase.WAITING,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "server is syncing"),
			"unknown sequence status fails closed");
		assertSame(ExperimentPhase.WAITING,
			ExperimentPhase.detect(ExperimentType.CHRONOMATRON, "Correct!"),
			"transient click feedback does not end a Chronomatron round");
		assertTrue(ExperimentType.fromTitle("Chronomatron (Metaphysical)").isPresent(),
			"valid experiment title is recognized");
		assertFalse(ExperimentType.fromTitle("Chronomatron (Not A Tier)").isPresent(),
			"malformed experiment title is ignored");

		ChronomatronModel lagModel = new ChronomatronModel();
		lagModel.observe(ChronomatronEvent.board(17, "red", true));
		lagModel.observe(ChronomatronEvent.status("Timer: 4.0s"));
		assertTrue(lagModel.click("red"), "lag model finishes its round");
		lagModel.observe(ChronomatronEvent.status("Correct!"));
		assertEquals(List.of("red"), lagModel.items(),
			"an unexpected status while ended must not wipe the learned sequence");

		ChronomatronModel endModel = new ChronomatronModel();
		endModel.observe(ChronomatronEvent.board(17, "red", true));
		endModel.observe(ChronomatronEvent.status("Timer: 4.0s"));
		endModel.observe(ChronomatronEvent.board(17, "red", false));
		assertTrue(endModel.click("red"), "Chronomatron reaches its end state after the click");
		endModel.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.REMEMBER, endModel.state(), "next memory starts replay");
		assertEquals(17, endModel.currentRevealSlot(), "replay retains the reveal latch until a clear");
		endModel.observe(ChronomatronEvent.board(17, "red", true));
		assertEquals(0, endModel.chainLengthCount(), "lingering highlight is not a replay pulse");
		endModel.observe(ChronomatronEvent.board(17, "red", false));
		assertEquals(0, endModel.currentRevealSlot(), "real clear releases the reveal latch");

		ExperimentSolverEngine pairEngine = new ExperimentSolverEngine();
		String pairTitle = "Superpairs (High)";
		List<ExperimentCell> hidden = List.of(
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false));
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertTrue(pairEngine.onClick(11).expected(), "Superpairs accepts the first selection");
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", List.of(
			ExperimentCell.token(11, "BOOK", false), hidden.get(1))));
		assertTrue(pairEngine.onClick(12).expected(), "Superpairs accepts the second selection");
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", List.of(
			ExperimentCell.token(11, "BOOK", false), ExperimentCell.token(12, "BOOK", false))));
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertEquals(1, pairEngine.view().superpairs().resolvedPairs(),
			"Superpairs resolves only after both revealed cards hide again");
		pairEngine.observe(new ExperimentSnapshot(pairTitle, "Next button is instantly rewarded!", hidden));
		assertEquals(1, pairEngine.view().superpairs().resolvedPairs(),
			"an instant-reward status update cannot undo a collected Superpairs pair");
	}

	private static void checkChronomatronModel() {
		ChronomatronModel model = new ChronomatronModel();
		model.observe(ChronomatronEvent.board(17, "red", true));
		assertSame(ChronomatronModel.State.WAIT, model.state(),
			"the first new reveal closes Chronomatron memory capture");
		assertEquals(List.of("red"), model.items(), "Chronomatron stores item identity, not slot id");
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red"), model.items(),
			"extra glints during WAIT cannot become clicks");

		model.observe(ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ChronomatronModel.State.SHOW, model.state(), "Timer opens the solve state");
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red"), model.items(),
			"the player's solve-phase glint is never recorded");
		assertTrue(model.click("red"), "the expected live item advances the solve ordinal");
		assertSame(ChronomatronModel.State.END, model.state(), "the completed chain closes the round");

		model.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.REMEMBER, model.state(), "Remember starts the next replay");
		// Skyblocker's model keeps the active reveal slot until that exact slot loses its glint.
		model.observe(ChronomatronEvent.board(17, "red", false));
		model.observe(ChronomatronEvent.board(17, "red", true));
		model.observe(ChronomatronEvent.board(17, "red", true));
		assertEquals(1, model.chainLengthCount(), "duplicate replay packets count only once");
		model.observe(ChronomatronEvent.board(17, "red", false));
		model.observe(ChronomatronEvent.board(18, "blue", true));
		assertEquals(List.of("red", "blue"), model.items(),
			"only the final new reveal is appended in round two");
		model.observe(ChronomatronEvent.board(19, "green", true));
		assertEquals(List.of("red", "blue"), model.items(),
			"a stale extra reveal cannot grow a closed memory round");

		ChronomatronModel statusDuringMemory = new ChronomatronModel();
		statusDuringMemory.observe(ChronomatronEvent.board(17, "red", true));
		statusDuringMemory.observe(ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ChronomatronModel.State.WAIT, statusDuringMemory.state(),
			"a repeated remember label does not restart an in-progress memory reveal");
		statusDuringMemory.observe(ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ChronomatronModel.State.SHOW, statusDuringMemory.state(),
			"the timer still opens solve after a repeated memory status");

		ExperimentSolverEngine roundBoundary = new ExperimentSolverEngine();
		roundBoundary.observe(new ExperimentSnapshot("Chronomatron (High)", "Remember the pattern!",
			List.of(), -1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		var boundary = roundBoundary.observe(new ExperimentSnapshot("Chronomatron (High)",
			"Round Complete", List.of(), -1, 0, -1, 2), ChronomatronEvent.status("Round Complete"));
		assertSame(ExperimentPhase.WAITING, boundary.phase(),
			"only a timer event opens WAIT; an unrelated label cannot override the model");

		ChronomatronModel tenRounds = new ChronomatronModel();
		List<String> colors = List.of("red", "blue", "green", "yellow", "purple", "pink", "cyan",
			"orange", "lime", "light_blue");
		for (int round = 0; round < colors.size(); round++) {
			if (round > 0) {
				tenRounds.observe(ChronomatronEvent.status("Remember the pattern!"));
				tenRounds.observe(ChronomatronEvent.board(tenRounds.currentRevealSlot(), "old", false));
			}
			for (int previous = 0; previous < round; previous++) {
				int slot = 17 + previous;
				tenRounds.observe(ChronomatronEvent.board(slot, colors.get(previous), true));
				tenRounds.observe(ChronomatronEvent.board(slot, colors.get(previous), false));
			}
			int newSlot = 17 + round;
			tenRounds.observe(ChronomatronEvent.board(newSlot, colors.get(round), true));
			tenRounds.observe(ChronomatronEvent.status("Timer: 4.0s"));
			for (String color : colors.subList(0, round + 1)) {
				assertTrue(tenRounds.click(color), "Chronomatron accepts round " + (round + 1) + " click");
			}
		}
		assertEquals(9, tenRounds.completedRounds(),
			"Chronomatron keeps the local completed-round count over ten rounds");
	}

	private static void checkIslandModes() {
		assertSame(Island.PRIVATE_ISLAND, Island.fromMode("dynamic"), "known island mode");
		assertSame(Island.SAFARI, Island.fromMode("SAFARI"), "case-insensitive island mode");
		assertSame(Island.OTHER, Island.fromMode(null), "missing mode");
		assertSame(Island.OTHER, Island.fromMode("unknown_mode"), "unknown mode");
		checkGardenHeightCapture();
	}

	/**
	 * The automatic border floor is taken from a player standing on something, inside the Garden's
	 * build bounds, and only until it has been taken. Without these guards, enabling the module
	 * mid-fall, mid-jump or on the barn roof latched that height for the life of the profile.
	 */
	private static void checkGardenHeightCapture() {
		assertTrue(GardenPlotBordersModule.canCaptureHeight(true, 70.0, false),
			"a standing player's height is a usable border floor");
		assertTrue(GardenPlotBordersModule.canCaptureHeight(true, 0.0, false),
			"the Garden's lowest build height is usable");
		assertTrue(GardenPlotBordersModule.canCaptureHeight(true, 255.9, false),
			"the highest usable height is just under the build limit");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(false, 70.0, false),
			"a height taken mid-air or mid-fall is never latched");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, 256.0, false),
			"a height at the build limit is not a plot floor");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, -1.0, false),
			"a height below the world is not a plot floor");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, Double.NaN, false),
			"a non-finite height is rejected");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, Double.POSITIVE_INFINITY, false),
			"an infinite height is rejected");
		assertFalse(GardenPlotBordersModule.canCaptureHeight(true, 70.0, true),
			"a floor is captured only once per session");
	}

	private static void checkDungeonStatsCommand() {
		assertTrue(DungeonStatsCommand.ParseResult.class.isRecord()
			&& DungeonStatsCommand.ParseResult.class.getRecordComponents().length == 3,
			"dstats parse results retain their Java record boundary");
		DungeonStatsCommand.ParseResult quoted = DungeonStatsCommand.parse("ga dstats \"Notch\"");
		assertTrue(quoted.valid(), "dstats accepts a quoted player name");
		assertEquals("Notch", quoted.name(), "dstats preserves the requested player name");
		DungeonStatsCommand.ParseResult unquoted = DungeonStatsCommand.parse("/ga dstats Notch");
		assertTrue(unquoted.valid(), "dstats accepts an unquoted player name");
		assertEquals("Notch", unquoted.name(), "dstats strips the command slash");
		DungeonStatsCommand.ParseResult local = DungeonStatsCommand.parse("ga dstats");
		assertTrue(local.recognized(), "dstats recognizes an omitted player name");
		assertTrue(local.usesLocalPlayer(), "an omitted dstats name selects the local profile");
		assertTrue(DungeonStatsCommand.parse("/ga dstats").usesLocalPlayer(),
			"the slash form also selects the local profile");
		assertFalse(DungeonStatsCommand.parse("ga other").recognized(), "unrelated ga commands remain unhandled");
		assertFalse(DungeonStatsCommand.parse("ga dstats Notch extra").valid(), "dstats rejects multiple names");
		assertFalse(DungeonStatsCommand.parse("ga dstats \"bad name\"").valid(), "dstats rejects invalid player-name characters");
		assertFalse(DungeonStatsCommand.parse("\u00a0ga dstats\u00a0").recognized(),
			"dstats parsing preserves Java trim behavior for non-breaking spaces");
		assertFalse(DungeonStatsCommand.parse("ga\u2003dstats").recognized(),
			"dstats does not trim Unicode whitespace that Java String.trim preserves");
		assertEquals(List.of("ALAN", "alexa", "alice"), DungeonStatsCommand.suggestions(
			List.of("alice", "Zed", "ALAN", "alexa", "bad name"), "aL"),
			"dstats suggestions prefix-match case-insensitively and sort all listed profile names");
		assertEquals(List.of("ALAN", "alexa", "alice", "Zed"), DungeonStatsCommand.suggestions(
			List.of("Zed", "alice", "ALAN", "alexa"), ""),
			"an empty dstats prefix lists the full sorted name set");
	}

	private static void checkMacroKeyCapturePolicy() {
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LSHIFT),
			"macro Key nodes accept left Shift as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_RSHIFT),
			"macro Key nodes accept right Shift as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LCONTROL),
			"macro Key nodes accept Control as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LALT),
			"macro Key nodes accept Alt as a standalone key");
		assertTrue(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_LSUPER),
			"macro Key nodes accept Super as a standalone key");
		assertFalse(ModuleKeybindManager.canBindMacroStepKey(InputConstants.KEY_ESCAPE),
			"Escape remains reserved to clear a key capture");
		try {
			assertTrue(java.lang.reflect.Modifier.isStatic(ModuleKeybindManager.class
				.getMethod("handleKeyEvent", net.minecraft.client.Minecraft.class, int.class,
					net.minecraft.client.input.KeyEvent.class).getModifiers())
				&& java.lang.reflect.Modifier.isStatic(ModuleKeybindManager.class
					.getMethod("handleMouseButtonEvent", net.minecraft.client.Minecraft.class, int.class,
						net.minecraft.client.input.MouseButtonInfo.class).getModifiers())
				&& java.lang.reflect.Modifier.isStatic(ModuleKeybindManager.class
					.getMethod("bindingScript").getModifiers()),
				"Java Mixin and editor callers retain the static keyboard, mouse, and capture-state APIs");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("module keybind manager Java API changed", exception);
		}
	}

	private static void checkMobHighlightFailClosed() {
		assertFalse(MobHighlight.isKnownSkyBlockIsland(Island.NONE),
			"unknown location keeps Mob Highlight disabled");
		assertFalse(MobHighlight.isKnownSkyBlockIsland(Island.OTHER),
			"non-SkyBlock location keeps Mob Highlight disabled");
	}

	private static void checkPersonalBestBoundaries() {
		assertEquals(402, AutoKickRules.parsePersonalBestLimitSeconds("6:42"), "PB m:ss input parses to seconds");
		assertEquals(362, AutoKickRules.parsePersonalBestLimitSeconds("6:2"), "PB accepts an unpadded seconds part");
		assertEquals(402, AutoKickRules.parsePersonalBestLimitSeconds("402"), "legacy PB seconds remain supported");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("0"), "zero PB limit disables the check");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("6:60"), "invalid PB seconds are rejected");
		assertEquals(0, AutoKickRules.parsePersonalBestLimitSeconds("6:42:1"), "multiple PB separators are rejected");
		assertTrue(AutoKickRules.personalBestPasses(0, 0), "zero limit disables PB check");
		assertTrue(AutoKickRules.personalBestPasses(60, 60), "PB equal to limit passes");
		assertTrue(AutoKickRules.personalBestPasses(59, 60), "faster PB passes");
		assertFalse(AutoKickRules.personalBestPasses(61, 60), "slower PB fails");
		assertFalse(AutoKickRules.personalBestPasses(0, 60), "missing PB fails a configured check");
		ExperimentMilestone chrono = ExperimentMilestone.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, 0).orElseThrow();
		assertEquals(9, ExperimentTier.HIGH.chronomatronThreshold(),
			"High through Transcendent Chronomatron alert threshold");
		assertEquals(7, ExperimentTier.HIGH.ultrasequencerThreshold(),
			"High through Transcendent Ultrasequencer alert threshold");
		assertEquals(12, ExperimentTier.METAPHYSICAL.chronomatronThreshold(),
			"Metaphysical Chronomatron alert threshold");
		assertEquals(9, ExperimentTier.METAPHYSICAL.ultrasequencerThreshold(),
			"Metaphysical Ultrasequencer alert threshold");
		assertFalse(chrono.reached(chrono.displayedSequenceLength() - 1, 0),
			"Chronomatron does not reach max clicks before the final sequence is known");
		assertFalse(chrono.reached(chrono.displayedSequenceLength(), 8),
			"Chronomatron keeps round nine playable before round ten starts");
		assertTrue(chrono.reached(chrono.displayedSequenceLength(), 8, ExperimentPhase.ROUND_COMPLETE),
			"Chronomatron reaches its max-click milestone as soon as target round nine is completed");
		assertTrue(chrono.reached(chrono.displayedSequenceLength(), 9),
			"Chronomatron keeps the next-round counter as a compatible milestone signal");
		assertTrue(chrono.reached(chrono.displayedSequenceLength() + 1, -1),
			"Chronomatron fallback waits for a sequence beyond the target");
	}

	private static void checkAutoKickLabels() {
		List<String> expectedBooleanLabels = List.of("Auto Kick", "Ask Before", "Dupe", "Terminator", "Hyperion", "GDrag");
		List<String> expectedTextLabels = List.of("Min Cata Level", "Min Class Level", "Min Class Avg", "Min Secrets",
			"Min Secret Avg", "Min MP", "PB limit (m:ss or sec; 0 off)", "Min Bank");
		List<BooleanSetting> booleans = AutoKickModule.INSTANCE.booleanSettings();
		List<TextSetting> texts = AutoKickModule.INSTANCE.textSettings();
		// The first BooleanSetting is module diagnostics; the next six are the first floor policy.
		for (int i = 0; i < expectedBooleanLabels.size(); i++) {
			assertEquals(expectedBooleanLabels.get(i), booleans.get(i + 1).displayName(), "AutoKick boolean label " + i);
		}
		for (int i = 0; i < expectedTextLabels.size(); i++) {
			assertEquals(expectedTextLabels.get(i), texts.get(i).displayName(), "AutoKick text label " + i);
		}
		assertEquals("F1 Auto Kick", booleans.get(1).name(), "AutoKick stable boolean key");
		assertEquals("F1 Minimum PB", texts.get(6).name(), "AutoKick stable PB key");
	}

	private static void checkExperimentPreviewConfiguration() {
		ExperimentSolverModule module = ExperimentSolverModule.INSTANCE;
		NumberSetting chronomatron = module.chronomatronFutureClicks();
		NumberSetting ultrasequencer = module.ultrasequencerFutureClicks();
		float oldChronomatron = chronomatron.value();
		float oldUltrasequencer = ultrasequencer.value();
		ColorSetting next = colorSetting(module, "Next Color");
		ColorSetting nextNext = colorSetting(module, "Next Next Color");
		ColorSetting nextNextNext = colorSetting(module, "Next Next Next Color");
		try {
			assertEquals(3, chronomatron.intValue(), "Chronomatron future-click default");
			assertEquals(3, ultrasequencer.intValue(), "Ultrasequencer future-click default");
			assertEquals(4, module.previewSteps(ExperimentType.CHRONOMATRON),
				"Chronomatron preview includes current plus three future clicks by default");
			assertEquals(4, module.previewSteps(ExperimentType.ULTRASEQUENCER),
				"Ultrasequencer preview includes current plus three future clicks by default");
			assertEquals(1.4978355f, numberSetting(module, "Term Size").value(),
				"solver overlay size uses the approved default");
			assertEquals(0, numberSetting(module, "Roundness").intValue(), "solver roundness defaults to zero");
			assertEquals(0, numberSetting(module, "Serums Consumed").intValue(),
				"serums consumed defaults to zero");
			assertEquals(0, numberSetting(module, "Slot Gap").intValue(), "solver slot gap defaults to zero");
			assertEquals(0xF01B1B22, colorSetting(module, "Panel Color").argb(),
				"solver panel uses the approved background color");
			assertEquals(0xFFFFFFFF, colorSetting(module, "Current Color").argb(),
				"solver Order 1 uses the approved color");
			assertEquals(0xFF7D7D7D, next.argb(), "solver Order 2 uses the approved color");
			assertEquals(0xFF3D3D3D, nextNext.argb(), "solver Order 3 uses the approved color");
			assertEquals(0xFF101010, nextNextNext.argb(), "solver Order 4 uses the approved color");
			assertTrue(booleanSetting(module, "Max Click Alert").value(),
				"Max Click Alert is enabled by default");
			assertFalse(booleanSetting(module, "Complete Sounds").value(),
				"Complete Sounds is disabled by default");
			assertFalse(module.booleanSettings().stream().anyMatch(setting -> setting.name().contains("Click Sound"))
				|| module.numberSettings().stream().anyMatch(setting -> setting.name().contains("Click Sound"))
				|| module.textSettings().stream().anyMatch(setting -> setting.name().contains("Click Sound"))
				|| module.colorSettings().stream().anyMatch(setting -> setting.name().contains("Click Sound")),
				"the removed click-sound feature leaves no click-sound setting");
			assertTrue(ExperimentSolverModule.shouldPlayMaxClickMilestoneSound(true, false),
				"Max Click Alert alone enables the selected sound at the maximum-click milestone");
			assertTrue(ExperimentSolverModule.shouldPlayMaxClickMilestoneSound(false, true),
				"Complete Sounds also enables the maximum-click milestone sound");
			assertFalse(ExperimentSolverModule.shouldPlayMaxClickMilestoneSound(false, false),
				"the maximum-click milestone is silent when both sound options are off");

			chronomatron.setValue(-1);
			assertEquals(0, chronomatron.intValue(), "Chronomatron preview clamps its lower bound");
			chronomatron.setValue(4);
			assertEquals(3, chronomatron.intValue(), "Chronomatron preview clamps its upper bound");
			ultrasequencer.setValue(-1);
			assertEquals(0, ultrasequencer.intValue(), "Ultrasequencer preview clamps its lower bound");
			chronomatron.setValue(0);
			assertEquals(1, module.previewSteps(ExperimentType.ULTRASEQUENCER),
				"zero future clicks leaves only the current button");
			assertFalse(module.isSettingVisible(next), "Next color hides when both future previews are zero");
			assertFalse(module.isSettingVisible(nextNext), "Next Next color hides when both future previews are zero");
			assertFalse(module.isSettingVisible(nextNextNext),
				"Next Next Next color hides when both future previews are zero");

			chronomatron.setValue(3);
			assertEquals(4, module.previewSteps(ExperimentType.CHRONOMATRON),
				"three future clicks expose current plus three buttons");
			assertTrue(module.isSettingVisible(next), "Next color shows for a future preview");
			assertTrue(module.isSettingVisible(nextNext), "Next Next color shows for two future previews");
			assertTrue(module.isSettingVisible(nextNextNext),
				"Next Next Next color shows for three future previews");
		} finally {
			chronomatron.setValue(oldChronomatron);
			ultrasequencer.setValue(oldUltrasequencer);
		}
	}

	private static void checkSuperpairsCacheGate() {
		ExperimentSolverModule.SuperpairsCacheGate gate = new ExperimentSolverModule.SuperpairsCacheGate();
		assertTrue(gate.consumeRenderDirty(), "a new Superpairs session builds its initial render cache");
		assertFalse(gate.consumeRenderDirty(), "an unchanged Superpairs frame does not rebuild its render cache");
		assertTrue(gate.consumeObservationDirty(), "a new Superpairs session observes its initial board");
		assertFalse(gate.consumeObservationDirty(), "an unchanged Superpairs frame skips board observation");
		gate.invalidateObservation();
		assertFalse(gate.consumeRenderDirty(), "a status-only change does not rebuild board render metadata");
		assertTrue(gate.consumeObservationDirty(), "a status-only change observes the board once");
		gate.invalidateRender();
		assertTrue(gate.consumeRenderDirty(), "a board change rebuilds render metadata");
		assertTrue(gate.consumeObservationDirty(), "a board change observes the board once");
	}

	private static ColorSetting colorSetting(Module module, String name) {
		for (ColorSetting setting : module.colorSettings()) {
			if (setting.name().equals(name)) return setting;
		}
		throw new AssertionError(module.name() + " is missing color setting " + name);
	}

	private static NumberSetting numberSetting(Module module, String name) {
		for (NumberSetting setting : module.numberSettings()) {
			if (setting.name().equals(name)) return setting;
		}
		throw new AssertionError(module.name() + " is missing number setting " + name);
	}

	private static BooleanSetting booleanSetting(Module module, String name) {
		for (BooleanSetting setting : module.booleanSettings()) {
			if (setting.name().equals(name)) return setting;
		}
		throw new AssertionError(module.name() + " is missing boolean setting " + name);
	}

	private static void checkEveryStatsToggleCombination() {
		String[] markers = {"Cata 42", "Mage 45", "CA 46.25", "MP 720", "SA 11.14", "PB 6:42",
			"Term ✓", "Hype ✓", "GDrag ✓", "Bank 125,000,000"};
		for (int mask = 0; mask < 1 << markers.length; mask++) {
			boolean[] enabled = new boolean[markers.length];
			for (int bit = 0; bit < enabled.length; bit++) enabled[bit] = (mask & (1 << bit)) != 0;
			PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(true,
				enabled[0], true, enabled[1], enabled[2], enabled[3], enabled[4], enabled[5], enabled[6],
				enabled[7], enabled[8], enabled[9]);
			List<Component> lines = PartyFinderStatsModule.CardFormatter.format(null,
				PartyFinderStatsModule.StatsView.preview(), options, 160, List.of());
			String output = lines.get(0).getString();
			for (int bit = 0; bit < markers.length; bit++) {
				if (enabled[bit] != output.contains(markers[bit])) {
					throw new AssertionError("toggle combination " + mask + " rendered marker " + markers[bit]
						+ " incorrectly: " + output);
				}
			}
			if (output.contains("│  │")) throw new AssertionError("empty stat separator in: " + output);
		}
		PartyFinderStatsModule.DisplayOptions statusOptions = new PartyFinderStatsModule.DisplayOptions(true,
			true, true, true, true, true, true, true, true, true, true, true);
		String manualStatus = PartyFinderStatsModule.CardFormatter.format(null,
			PartyFinderStatsModule.StatsView.preview(), statusOptions, 160, List.of(), true, true)
			.getFirst().getString();
		assertTrue(manualStatus.contains("[✦ DStats]"), "manual stats use their own compact heading");
		assertTrue(manualStatus.contains("Island detection API is off"), "stats name the disabled island API");
		assertTrue(manualStatus.contains("Normal PBs") && manualStatus.contains("Master PBs"),
			"manual stats show separate Normal and Master PB summaries");
		assertTrue(PartyFinderStatsModule.StatsView.preview().normalPbs().contains("F7")
			&& !PartyFinderStatsModule.StatsView.preview().normalPbs().contains("M1"),
			"Normal PB summary includes only normal floors");
		assertTrue(PartyFinderStatsModule.StatsView.preview().masterPbs().contains("M7")
			&& !PartyFinderStatsModule.StatsView.preview().masterPbs().contains("F7"),
			"Master PB summary includes only Master floors");
		assertFalse(manualStatus.contains("joined"), "manual stats do not use the Party Finder joined heading");
		assertEquals("Dungeon stats API unavailable: Player (network timeout)",
			PartyFinderStatsModule.apiUnavailableStatus("Player", "network timeout"),
			"profile lookup failure has a named API status");
		assertEquals("Dungeon stats API unavailable: Player (unavailable)",
			PartyFinderStatsModule.apiUnavailableStatus("Player", ""),
			"empty profile lookup errors remain visibly unavailable");
	}

	private static void checkDungeonStatsHovers() {
		String[] expectedDetails = {
			"Total Catacombs XP", "XP in current level", "Tank 38", "Class average",
			"Magical Power", "Total secrets", "Runs:", "F7 personal best",
			"Normal mode personal bests", "Master mode personal bests",
			"Shortbow: Instantly shoots!", "Wither Impact", "LEGENDARY",
			"Item ID: TERMINATOR", "PET_ITEM_TIER_BOOST", "Bank balance"
		};
		PartyFinderStatsModule.StatsView preview = PartyFinderStatsModule.StatsView.preview();
		for (boolean compact : new boolean[] {true, false}) {
			PartyFinderStatsModule.DisplayOptions options = new PartyFinderStatsModule.DisplayOptions(compact,
				true, true, true, true, true, true, true, true, true, true, true);
			List<Component> partyFinder = PartyFinderStatsModule.CardFormatter.format(null, preview,
				options, 160, List.of());
			String partyHovers = statsHoverText(partyFinder);
			for (String expected : expectedDetails) {
				assertTrue(partyHovers.contains(expected),
					(compact ? "compact" : "full") + " Party Finder card has hover detail: " + expected);
			}
			List<Component> manualLookup = PartyFinderStatsModule.CardFormatter.format(null, preview,
				options, 160, List.of(), true, false);
			String manualHovers = statsHoverText(manualLookup);
			for (String expected : expectedDetails) {
				if (expected.equals("F7 personal best")) continue;
				assertTrue(manualHovers.contains(expected),
					(compact ? "compact" : "full") + " /ga dstats card has hover detail: " + expected);
			}
			assertTrue(manualHovers.contains("F7: 6:42"),
				(compact ? "compact" : "full") + " /ga dstats card exposes the queued floor in its PB hover");
		}
		PartyFinderStatsModule.DisplayOptions cataOnly = new PartyFinderStatsModule.DisplayOptions(true,
			true, true, false, false, false, false, false, false, false, false, false);
		assertEquals("50", DungeonStatsService.formatCatacombsLevel(569_809_640L, true),
			"exact level-50 XP stays capped until overflow starts");
		assertEquals("50.00", DungeonStatsService.formatCatacombsLevel(569_809_641L, true),
			"overflow starts with two decimals");
		assertEquals("51.00", DungeonStatsService.formatCatacombsLevel(769_809_640L, true),
			"each 200 million overflow XP adds one level");
		assertEquals("50", DungeonStatsService.formatCatacombsLevel(769_809_640L, false),
			"disabling overflow Cata retains the level-50 cap");
		String overflowCata = PartyFinderStatsModule.CardFormatter.format(null,
			preview.withCatacombsExperience(769_809_640L), cataOnly, 160, List.of(), true, false)
			.getFirst().getString();
		assertTrue(overflowCata.contains("Cata 51.00"),
			"manual /ga dstats output uses the overflow Cata formatter");
		PartyFinderStatsModule.DisplayOptions cappedCata = new PartyFinderStatsModule.DisplayOptions(true,
			true, false, false, false, false, false, false, false, false, false, false);
		String cappedCataOutput = PartyFinderStatsModule.CardFormatter.format(null,
			preview.withCatacombsExperience(769_809_640L), cappedCata, 160, List.of())
			.getFirst().getString();
		assertTrue(cappedCataOutput.contains("Cata 50"),
			"disabling overflow Cata keeps displayed stats capped at 50");
		String overflowHover = statsHoverText(PartyFinderStatsModule.CardFormatter.format(null,
			preview.withCatacombsExperience(569_821_985L), cataOnly, 160, List.of()));
		assertTrue(overflowHover.contains("Overflow XP: 12,345"),
			"Cata hover shows XP beyond level 50");
		PartyFinderStatsModule.StatsView unavailable = new PartyFinderStatsModule.StatsView(
			"Unavailable", null, null, 0, 0, 0, 0, 0, 0, 0, 0,
			false, false, false, false, false, false, false, false,
			"-", "", "", "", false, 0, false, false, false, false, false, false,
			List.of(), List.of());
		PartyFinderStatsModule.DisplayOptions allFields = new PartyFinderStatsModule.DisplayOptions(true,
			true, true, true, true, true, true, true, true, true, true, true);
		String unavailableHovers = statsHoverText(PartyFinderStatsModule.CardFormatter.format(null,
			unavailable, allFields, 160, List.of()));
		assertTrue(unavailableHovers.contains("Catacombs XP data unavailable"),
			"missing Catacombs XP has a clear hover state");
		assertTrue(unavailableHovers.contains("Inventory API data unavailable"),
			"missing weapon inventory has a clear hover state");
		assertTrue(unavailableHovers.contains("Pet API data unavailable"),
			"missing pet data has a clear hover state");
		assertTrue(unavailableHovers.contains("Magical Power data unavailable in profile"),
			"missing magical power has a clear hover state");
		assertTrue(unavailableHovers.contains("Total secrets: unavailable in profile")
			&& unavailableHovers.contains("Runs: unavailable in profile"),
			"missing secret and run values are identified in the average hover");
		assertTrue(unavailableHovers.contains("Bank API data unavailable"),
			"missing bank data has a clear hover state");
		assertTrue(unavailableHovers.contains("Selected dungeon class unavailable in profile"),
			"missing selected class has a clear hover state");
		assertTrue(unavailableHovers.contains("Personal best data unavailable in profile"),
			"missing personal best data has a clear hover state");
		assertTrue(unavailableHovers.contains("Normal mode personal best data unavailable in profile")
			&& unavailableHovers.contains("Master mode personal best data unavailable in profile"),
			"normal and master personal best sections report missing profile data separately");
	}

	private static String statsHoverText(List<Component> components) {
		StringBuilder output = new StringBuilder();
		for (Component component : components) collectStatsHoverText(component, output);
		return output.toString();
	}

	private static void collectStatsHoverText(Component component, StringBuilder output) {
		if (component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText showText) {
			output.append(showText.value().getString()).append('\n');
		}
		for (Component child : component.getSiblings()) collectStatsHoverText(child, output);
	}

	private static void checkGlobalDebugGate() {
		assertFalse(DebugModule.INSTANCE.defaultEnabled(), "Dev Debug module defaults off");
		assertFalse(DebugModule.INSTANCE.isEnabled(), "Dev Debug starts runtime-disabled before config load");
		assertEquals("DEV", DebugModule.INSTANCE.category().name(), "Dev Debug module category");
		assertFalse(SlotIdsModule.INSTANCE.defaultEnabled(), "Slot IDs defaults off for a fresh config");
		assertFalse(SlotIdsModule.INSTANCE.isEnabled(), "Slot IDs starts runtime-disabled before config load");
		assertEquals("DEV", SlotIdsModule.INSTANCE.category().name(), "Slot IDs module category");
		assertEquals("slot_ids", SlotIdsModule.INSTANCE.id(), "Slot IDs keeps its stable module id");
		DebugState.setEnabled(false);
		BooleanSetting debug = BooleanSetting.debug("Debug", true);
		assertFalse(debug.value(), "debug setting is effectively off behind the global gate");
		assertTrue(debug.rawValue(), "debug setting keeps its raw value while gated");

		DebugState.setEnabled(true);
		assertTrue(debug.value(), "debug setting restores its raw value when enabled");
		DebugState.setEnabled(false);
		assertFalse(debug.value(), "debug setting is gated again after disabling diagnostics");
		assertTrue(debug.rawValue(), "disabling diagnostics does not erase the raw value");

		assertDebugSetting(AutoKickModule.INSTANCE, "Debug");
		assertDebugSetting(PartyFinderStatsModule.INSTANCE, "Debug");
		assertDebugSetting(HideyhoFinderModule.INSTANCE, "Debug Mode");
		assertDebugSetting(SparklingCritterModule.INSTANCE, "Debug");
		assertDebugSetting(TikiHelperModule.INSTANCE, "Debug Logging");
		assertDebugGroup(AutoKickModule.INSTANCE, "Diagnostics");
		assertDebugGroup(PartyFinderStatsModule.INSTANCE, "Diagnostics");
		assertDebugGroup(TikiHelperModule.INSTANCE, "Debug Logging");

		// Standalone checks must not leak the gate into a later harness invocation.
		DebugState.setEnabled(false);
	}

	private static void checkSettingInputBounds() {
		NumberSetting number = new NumberSetting("test", 0, 10, 5, true);
		number.setValue(Float.NaN);
		assertEquals(5, number.intValue(), "NaN does not poison a number setting");
		number.setFraction(Float.POSITIVE_INFINITY);
		assertEquals(5, number.intValue(), "infinite slider input is ignored");
		assertFalse(number.prefersTextInput(), "small ranges remain sliders");
		NumberSetting large = new NumberSetting("large", 0, 200, 100, true);
		assertTrue(large.prefersTextInput(), "large ranges use direct numeric input");
		TextSetting text = new TextSetting("test", "value", 8);
		text.setValue(null);
		assertEquals("value", text.value(), "null text input is ignored");
		text.setValue("\u00A08\u00A0");
		assertEquals(17, text.intValue(17), "integer parsing keeps Java trim behavior for non-breaking spaces");
		assertEquals(18L, text.longValue(18L), "long parsing keeps Java trim behavior for non-breaking spaces");
		assertEquals(19.5, text.doubleValue(19.5), "decimal parsing keeps Java trim behavior for non-breaking spaces");
		text.setValue("\t8 \n");
		assertEquals(8, text.intValue(-1), "numeric parsing still trims ASCII whitespace");
		ColorSetting color = new ColorSetting("test", 1, 2, 3, 4);
		assertFalse(color.setHex(null), "null color input is rejected");
		assertFalse(color.setHex("\u00A0FF0000\u00A0"), "hex parsing keeps Java trim behavior for non-breaking spaces");
		assertEquals(1, color.red(), "malformed whitespace-wrapped hex leaves the color unchanged");
		assertTrue(color.setHex("\t#AABBCCDD\r\n"), "hex parsing still trims ASCII whitespace");
		assertEquals(0xDDAABBCC, color.argb(), "ASCII-whitespace hex input keeps its parsed RGBA value");
	}

	private static void checkChoiceDirection() {
		ChoiceSetting choice = new ChoiceSetting("test", "A", "A", "B", "C");
		choice.selectNext();
		assertEquals("B", choice.value(), "choice next selects the following value");
		choice.selectPrevious();
		assertEquals("A", choice.value(), "choice previous selects the preceding value");
		choice.selectPrevious();
		assertEquals("C", choice.value(), "choice previous wraps at the beginning");
		ChoiceSetting nonBreakingSpace = new ChoiceSetting("unicode", "\u00A0", "\u00A0");
		assertEquals("\u00A0", nonBreakingSpace.value(), "choice validation keeps Java isBlank behavior for non-breaking spaces");
		ChoiceSetting nonBreakingSpaceLabel = new ChoiceSetting("unicode-label", "A",
			Map.of("A", "\u00A0"), "A");
		assertEquals("\u00A0", nonBreakingSpaceLabel.displayValue(),
			"display-label validation keeps Java isBlank behavior for non-breaking spaces");
		boolean rejectedJavaWhitespace = false;
		try {
			new ChoiceSetting("unicode-whitespace", "\u2003", "\u2003");
		} catch (IllegalArgumentException expected) {
			rejectedJavaWhitespace = true;
		}
		assertTrue(rejectedJavaWhitespace, "choice validation rejects Java whitespace-only values");
	}

	private static void checkExperimentSolverEngine() {
		ExperimentBoardGeometry chronoGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH);
		assertTrue(chronoGeometry.containsSlot(17), "Chronomatron includes the first playable cell");
		assertTrue(chronoGeometry.containsSlot(25), "Chronomatron includes the last single-row cell");
		assertFalse(chronoGeometry.containsSlot(16), "Chronomatron excludes the frame before the board");
		assertEquals(0, chronoGeometry.column(17), "Chronomatron column mapping");
		assertEquals(8, chronoGeometry.column(25), "Chronomatron last column mapping");
		ExperimentBoardGeometry longChronoGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.CHRONOMATRON, ExperimentTier.METAPHYSICAL);
		assertEquals(17, longChronoGeometry.slotId(0, 0), "Metaphysical Chronomatron first slot");
		assertEquals(34, longChronoGeometry.slotId(8, 1), "Metaphysical Chronomatron last slot");

		ExperimentBoardGeometry ultraGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.ULTRASEQUENCER, ExperimentTier.SUPREME);
		assertEquals(9, ultraGeometry.slotId(0, 0), "Ultrasequencer includes the first board slot");
		assertEquals(44, ultraGeometry.slotId(8, 3), "Ultrasequencer includes the last board slot");
		assertTrue(ultraGeometry.containsSlot(44), "decorative panes remain part of the capture range");
		ExperimentBoardGeometry pairsGeometry = ExperimentBoardGeometry.forExperiment(
			ExperimentType.SUPERPAIRS, ExperimentTier.HIGH);
		assertTrue(pairsGeometry.containsSlot(11), "High Superpairs includes its centered first cell");
		assertFalse(pairsGeometry.containsSlot(10), "High Superpairs excludes the side frame");

		ExperimentSolverEngine engine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(0));
		String chronoTitle = "Chronomatron (Metaphysical)";
		var memory = engine.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 0, -1, 1),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentType.CHRONOMATRON, memory.type(), "Chronomatron title detection");
		assertSame(ExperimentTier.METAPHYSICAL, memory.tier(), "Metaphysical title detection");
		assertSame(ExperimentPhase.MEMORIZE, memory.phase(), "memory phase detection");
		var captured = engine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(), -1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		assertSame(ExperimentPhase.WAITING, captured.phase(),
			"a new reveal immediately closes memory capture like Skyblocker");
		engine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, 0, 2), ChronomatronEvent.status("Timer: 4.0s"));
		var solve = engine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 2));
		assertEquals(1, solve.sequence().size(), "Chronomatron remembers the revealed sequence");
		assertTrue(solve.current().isPresent(), "current solution is exposed");
		assertEquals(List.of(17), solve.sequence().get(0).slotIds(),
			"the remembered item is resolved against the current live board");
		assertEquals(1, solve.upcoming(2).size(), "a one-click round does not invent a future click");
		var transientFeedback = engine.observe(new ExperimentSnapshot(chronoTitle, "Correct!",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 3));
		assertSame(ExperimentPhase.SOLVE, transientFeedback.phase(),
			"transient Chronomatron feedback preserves the trusted solve phase");
		assertTrue(transientFeedback.current().isPresent(),
			"transient Chronomatron feedback keeps the correct button visible");
		var staleChrono = engine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 2),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.SOLVE, staleChrono.phase(),
			"an out-of-order Chronomatron callback cannot erase a trusted solve state");
		assertTrue(staleChrono.current().isPresent(),
			"an out-of-order Chronomatron callback keeps the current button visible");

		// A real Hypixel transition can briefly clear/replace slot 49 while the final reveal is still
		// arriving. That status frame must not suppress the board event or truncate the next round.
		var transientMemory = new ExperimentSolverEngine();
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 1), ChronomatronEvent.status("Remember the pattern!"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 2), ChronomatronEvent.board(17, "red", true));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)),
			-1, 0, -1, 3), ChronomatronEvent.status("Timer: 4.0s"));
		assertTrue(transientMemory.onClick(17).expected(),
			"the first Chronomatron round accepts its remembered click");
		assertTrue(transientMemory.confirmClick(17).visualStateChanged(),
			"the first Chronomatron round reaches its end state");
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Round Complete", List.of(),
			-1, 0, -1, 4), ChronomatronEvent.status("Timer: 0.0s"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 5), ChronomatronEvent.status("Remember the pattern!"));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 5), ChronomatronEvent.board(17, "red", false));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 1, 1, 6), ChronomatronEvent.board(17, "red", true));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "", List.of(),
			-1, 1, 1, 7), ChronomatronEvent.board(17, "red", false));
		transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Correct!", List.of(),
			-1, 1, 1, 8), ChronomatronEvent.board(18, "blue", true));
		var recoveredMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false), ExperimentCell.token(18, "blue", false)),
			-1, 1, 1, 9), ChronomatronEvent.status("Timer: 4.0s"));
		assertEquals(List.of("red", "blue"), recoveredMemory.sequence().stream()
			.map(step -> step.value()).toList(),
			"Chronomatron keeps a reveal that arrived during a transient status frame");
		assertEquals(2, recoveredMemory.upcoming(2).size(),
			"Chronomatron exposes both clicks after a transient status frame");
		assertEquals(0, recoveredMemory.visualIndex(),
			"previous-round progress cannot make Chronomatron repeat or skip click one");
		assertTrue(transientMemory.onClick(17).expected(),
			"round two accepts the first Chronomatron click");
		assertTrue(transientMemory.confirmClick(17).visualStateChanged(),
			"round two advances after its first Chronomatron click");
		var falseChronoMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!",
			List.of(ExperimentCell.token(17, "red", false),
				ExperimentCell.token(18, "blue", false)),
			-1, 1, 0, 10), ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.SOLVE, falseChronoMemory.phase(),
			"a stale memory label cannot end an unfinished Chronomatron solve");
		assertEquals(1, falseChronoMemory.visualIndex(),
			"a stale memory label cannot roll Chronomatron back to click one");
		assertEquals("blue", falseChronoMemory.current().orElseThrow().value(),
			"Chronomatron keeps click two selected after transient memory feedback");
		assertTrue(transientMemory.onClick(18).expected(),
			"Chronomatron's second click remains playable after transient feedback");
		assertTrue(transientMemory.confirmClick(18).visualStateChanged(),
			"Chronomatron completes the two-click round");
		var staleCompletedMemory = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 1, 0, 11));
		assertSame(ExperimentPhase.ROUND_COMPLETE, staleCompletedMemory.phase(),
			"a polled old memory label cannot restart a completed Chronomatron round");
		assertEquals(2, staleCompletedMemory.visualIndex(),
			"stale polling cannot roll back a completed Chronomatron cursor");
		var nextChronoRound = transientMemory.observe(new ExperimentSnapshot(chronoTitle,
			"Remember the pattern!", List.of(), -1, 2, 2, 12),
			ChronomatronEvent.status("Remember the pattern!"));
		assertSame(ExperimentPhase.MEMORIZE, nextChronoRound.phase(),
			"Chronomatron accepts memory after the prior sequence is complete");
		assertEquals(0, nextChronoRound.visualIndex(),
			"Chronomatron resets its cursor at the real next-round boundary");
		// A complete two-round trace. The stale timer/polling frames between the final click and the
		// next Remember callback must not reopen click one or truncate the next sequence.
		var directChrono = new ExperimentSolverEngine();
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 20), ChronomatronEvent.status("Remember the pattern!"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 21), ChronomatronEvent.board(17, "red", true));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, -1, 22), ChronomatronEvent.status("Timer: 4.0s"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 22));
		assertTrue(directChrono.onClick(17).expected(), "round one accepts its expected tile");
		assertTrue(directChrono.confirmClick(17).visualStateChanged(),
			"round one advances after the vanilla click dispatch");
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Round Complete", List.of(),
			-1, 0, -1, 23), ChronomatronEvent.status("Timer: 0.0s"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 24), ChronomatronEvent.status("Remember the pattern!"));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 25), ChronomatronEvent.board(17, "red", false));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 26), ChronomatronEvent.board(17, "red", true));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 27), ChronomatronEvent.board(17, "red", false));
		directChrono.observe(new ExperimentSnapshot(chronoTitle, "Correct!", List.of(),
			-1, 0, -1, 28), ChronomatronEvent.board(18, "blue", true));
		var directRoundTwo = directChrono.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(20, "red", false), ExperimentCell.token(21, "blue", false)),
			-1, 0, -1, 29), ChronomatronEvent.status("Timer: 4.0s"));
		assertEquals(List.of("red", "blue"), directRoundTwo.sequence().stream()
			.map(step -> step.value()).toList(), "round two keeps the old item and appends one new item");
		assertEquals(List.of(20), directRoundTwo.sequence().get(0).slotIds(),
			"Chronomatron resolves click one against the live board");
		assertEquals(List.of(21), directRoundTwo.sequence().get(1).slotIds(),
			"Chronomatron resolves click two against the live board");
		assertEquals(0, directRoundTwo.visualIndex(), "the next round starts at click one");
		assertTrue(directChrono.onClick(20).expected(), "round two accepts click one");
		assertTrue(directChrono.confirmClick(20).visualStateChanged(), "round two advances to click two");
		var staleTimer = directChrono.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!",
			List.of(ExperimentCell.token(20, "red", false), ExperimentCell.token(21, "blue", false)),
			-1, 0, 0, 30));
		assertEquals(1, staleTimer.visualIndex(), "a stale memory label cannot roll the cursor back");
		assertSame(ExperimentPhase.SOLVE, staleTimer.phase(),
			"a stale memory label cannot end an unfinished solve");

		var predictionEngine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(0));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 31), ChronomatronEvent.board(17, "red", true));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, -1, 32), ChronomatronEvent.status("Timer: 4.0s"));
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, -1, 32));
		var pendingPrediction = predictionEngine.onClick(17);
		assertTrue(pendingPrediction.expected(), "sequence click is accepted for vanilla dispatch");
		assertFalse(pendingPrediction.predicted(), "sequence clicks never use local 0 Ping prediction");
		predictionEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 3.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 33));
		assertEquals(0, predictionEngine.view().visualIndex(),
			"a later snapshot leaves the cursor unchanged until confirmation");
		assertTrue(predictionEngine.confirmClick(17).visualStateChanged(),
			"vanilla dispatch confirmation advances the sequence cursor");

		var normalEngine = new ExperimentSolverEngine(
			new ExperimentSolverEngine.Configuration(0));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Remember the pattern!", List.of(),
			-1, 0, -1, 1), ChronomatronEvent.board(17, "red", true));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s", List.of(),
			-1, 0, 0, 2), ChronomatronEvent.status("Timer: 4.0s"));
		normalEngine.observe(new ExperimentSnapshot(chronoTitle, "Timer: 4.0s",
			List.of(ExperimentCell.token(17, "red", false)), -1, 0, 0, 2));
		assertTrue(normalEngine.onClick(17).expected(), "normal Chronomatron click is accepted");
		assertTrue(normalEngine.confirmClick(17).visualStateChanged(),
			"normal click confirmation advances without an item-stack diff");
		assertEquals(1, normalEngine.view().visualIndex(), "normal click advances the authoritative cursor");

		var ultra = new ExperimentSolverEngine(new ExperimentSolverEngine.Configuration(3));
		var ultraMemory = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Remember the pattern!", List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2),
				ExperimentCell.number(32, 3), ExperimentCell.number(33, 4), ExperimentCell.number(34, 5),
				ExperimentCell.number(35, 6)), 6, 5, -1, 1));
		assertEquals(6, ultraMemory.sequence().size(), "Ultrasequencer remembers numbers by order");
		var ultraHandoff = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Remember the pattern!", List.of(), -1, 5, -1, 2));
		assertEquals(6, ultraHandoff.sequence().size(),
			"Ultrasequencer retains its sequence while numbered items become panes");
		var ultraView = ultra.observe(new ExperimentSnapshot("Ultrasequencer (Metaphysical)",
			"Timer: 1.0s", List.of(), -1, 5, 0, 2));
		assertEquals(List.of(30), ultraView.sequence().get(0).slotIds(),
			"Ultrasequencer keeps the remembered slot for click 1 during SOLVE");
		assertEquals(List.of(31), ultraView.sequence().get(1).slotIds(),
			"Ultrasequencer keeps the remembered slot for click 2 during SOLVE");
		assertEquals(3, ultraView.upcoming(3).size(), "future preview is capped to available steps");
		assertEquals(List.of(1, 2, 3), ultraView.upcoming(3).stream()
			.map(step -> step.index() + 1).toList(), "future clicks expose their absolute order");
		assertFalse(ultraView.milestoneReached(),
			"Ultrasequencer does not infer completed rounds from a stale snapshot counter");
		ExperimentMilestone milestone = ultraView.milestone().orElseThrow();
		assertEquals(6, milestone.displayedSequenceLength(), "serums lower the displayed target");
		assertEquals(5, milestone.completedRoundThreshold(), "Ultrasequencer target is one round earlier");

		var ultraPending = new ExperimentSolverEngine(new ExperimentSolverEngine.Configuration(0));
		String ultraTitle = "Ultrasequencer (High)";
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2)), "black", 50));
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Timer: 1.0s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 51));
		assertFalse(ultraPending.onClick(30).predicted(),
			"Ultrasequencer does not advance through local 0 Ping prediction");
		ultraPending.observe(new ExperimentSnapshot(ultraTitle, "Timer: 0.5s",
			List.of(ExperimentCell.token(30, "white", false)), "white", 52));
		assertEquals(0, ultraPending.view().visualIndex(),
			"Ultrasequencer waits for dispatch confirmation");
		assertTrue(ultraPending.confirmClick(30).visualStateChanged(),
			"Ultrasequencer advances after dispatch confirmation");

		SuperpairsBoard board = new SuperpairsBoard();
		board.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertEquals(11, board.suggestedMatch(10).orElseThrow(), "Superpairs remembers a matching card");
		assertSame(SuperpairsBoard.CardState.MATCH, board.view().cards().get(0).state(),
			"known Superpairs pair is exposed to the renderer");

		SuperpairsBoard delayedPair = new SuperpairsBoard();
		delayedPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		delayedPair.click(10);
		delayedPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		delayedPair.click(11);
		delayedPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		assertSame(SuperpairsBoard.CardState.MATCH, delayedPair.view().cards().get(0).state(),
			"Superpairs marks a revealed matching pair before it is collected");
		delayedPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertSame(SuperpairsBoard.CardState.RESOLVED, delayedPair.view().cards().get(0).state(),
			"Superpairs resolves a pair after both cards hide again");
		assertEquals(1, delayedPair.view().resolvedPairs(), "Superpairs exposes completed pair count");
		assertEquals(2, delayedPair.view().knownCount(),
			"Superpairs keeps the discovered card count in a partial board fixture");
		SuperpairsBoard hiddenKnownPair = new SuperpairsBoard();
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertTrue(hiddenKnownPair.click(10), "Superpairs can select a remembered hidden card");
		assertTrue(hiddenKnownPair.click(11), "Superpairs can select its remembered match");
		assertEquals(0, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs does not resolve a hidden pair before server reveals");
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		assertEquals(0, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs keeps a matching pair visible until it hides again");
		hiddenKnownPair.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(1, hiddenKnownPair.view().resolvedPairs(),
			"Superpairs resolves a remembered pair after both cards hide");
		SuperpairsBoard instantReward = new SuperpairsBoard();
		instantReward.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false)));
		instantReward.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertTrue(instantReward.click(10) && instantReward.click(11),
			"known Superpairs cards remain selectable after the board hides them");
		instantReward.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(1, instantReward.view().resolvedPairs(),
			"an instant-reward pair completes without requiring an intermediate visible frame");
		SuperpairsBoard noLocalPair = new SuperpairsBoard();
		noLocalPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		noLocalPair.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "BOOK", -1, true, false, false),
			new ExperimentCell(12, "SWORD", -1, true, false, false)));
		assertEquals(0, noLocalPair.view().resolvedPairs(),
			"Superpairs does not count a pair without two local clicks");
		SuperpairsBoard rapidClicks = new SuperpairsBoard();
		rapidClicks.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertTrue(rapidClicks.click(10), "Superpairs accepts the first rapid click");
		assertFalse(rapidClicks.click(11), "Superpairs blocks a second click while the first reveal is pending");
		assertFalse(rapidClicks.click(12), "Superpairs blocks later clicks while the first reveal is pending");
		rapidClicks.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, null, -1, false, false, false),
			new ExperimentCell(12, null, -1, false, false, false)));
		assertTrue(rapidClicks.click(11), "Superpairs accepts the next click after the reveal arrives");

		SuperpairsBoard mismatch = new SuperpairsBoard();
		mismatch.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "SWORD", -1, true, false, false)));
		mismatch.click(10);
		mismatch.click(11);
		mismatch.observe(List.of(new ExperimentCell(10, "BOOK", -1, true, false, false),
			new ExperimentCell(11, "SWORD", -1, true, false, false)));
		mismatch.observe(List.of(new ExperimentCell(10, null, -1, false, false, false),
			new ExperimentCell(11, null, -1, false, false, false)));
		assertEquals(-1, mismatch.view().selectedSlot(), "Superpairs clears a failed selection after hiding");
		assertSame(SuperpairsBoard.CardState.KNOWN, mismatch.view().cards().get(0).state(),
			"an unmatched hidden card remains playable instead of becoming resolved");
		assertSame(ExperimentPhase.SOLVE,
			new ExperimentSnapshot("Superpairs (Metaphysical)", "Next button is instantly rewarded!",
				List.of()).phase(), "Superpairs instant-reward status keeps the game playable");
		assertSame(ExperimentPhase.COMPLETE,
			new ExperimentSnapshot("Superpairs (Metaphysical)", "Max clicks reached!",
				List.of()).phase(), "Superpairs max-click status is recognized");
		var completedPairs = new ExperimentSolverEngine();
		completedPairs.observe(new ExperimentSnapshot("Superpairs (High)", "Max clicks reached!",
			List.of(ExperimentCell.token(10, "BOOK", false)),
			-1, -1, -1, 1));
		var restartedPairs = completedPairs.observe(new ExperimentSnapshot("Superpairs (High)",
			"Next button is instantly rewarded!", List.of(ExperimentCell.token(11, "SWORD", false)),
			-1, -1, -1, 2));
		assertEquals(1, restartedPairs.superpairs().knownCount(),
			"Superpairs clears a completed board when Hypixel starts the next game");

		assertSame(ExperimentPhase.IDLE, engine.observe(null).phase(), "null snapshot resets the engine");
		assertFalse(ExperimentType.fromTitle("Chest").isPresent(), "unrelated title is ignored");
		assertTrue(ExperimentSolverModule.INSTANCE.supports(ExperimentType.CHRONOMATRON),
			"solver module exposes Chronomatron configuration");
	}

	private static void checkAutoExperiments() {
		AutoExperimentsModule module = AutoExperimentsModule.INSTANCE;
		assertFalse(module.defaultEnabled(), "Auto Experiments defaults off for a fresh config");
		assertFalse(module.isEnabled(), "Auto Experiments starts runtime-disabled before config load");
		assertTrue(module.chronomatron().value(), "Chronomatron automation defaults on per game");
		assertTrue(module.ultrasequencer().value(), "Ultrasequencer automation defaults on per game");
		assertFalse(booleanSetting(module, "Debug Automation").value(),
			"Auto Experiments debug defaults off");
		assertEquals(500, module.firstClickDelay().intValue(), "fresh first-click delay matches the approved default");
		assertEquals(250, module.minimumClickDelay().intValue(), "fresh minimum click delay matches the approved default");
		assertEquals(350, module.maximumClickDelay().intValue(), "fresh maximum click delay matches the approved default");
		assertFalse(module.supports(ExperimentType.SUPERPAIRS), "Auto Experiments excludes Superpairs");

		AutoExperimentDelayRange standardRange = new AutoExperimentDelayRange(190, 260);
		long[] requestedBound = {-1L};
		assertEquals(190, standardRange.sampleMillis(bound -> {
			requestedBound[0] = bound;
			return 0L;
		}), "random delay can deterministically select the inclusive minimum");
		assertEquals(71L, requestedBound[0], "inclusive sampling requests one offset per millisecond value");
		assertEquals(260, standardRange.sampleMillis(bound -> bound - 1L),
			"random delay can deterministically select the inclusive maximum");
		AutoExperimentDelayRange reversedRange = new AutoExperimentDelayRange(260, 190);
		assertEquals(190, reversedRange.minimumMillis(), "reversed endpoints normalize before sampling");
		assertEquals(260, reversedRange.maximumMillis(), "reversed endpoints retain the larger maximum");
		assertEquals(221, new AutoExperimentDelayRange(221, 221).sampleMillis(bound -> {
			assertEquals(1L, bound, "an equal endpoint range has exactly one choice");
			return 0L;
		}), "an equal delay range always samples its one value");
		assertEquals(new AutoExperimentDelayRange(190, 260),
			AutoExperimentDelayRange.fromLegacyDelay(190), "legacy delay migrates to itself plus 70 ms");
		assertEquals(new AutoExperimentDelayRange(1_970, 2_000),
			AutoExperimentDelayRange.fromLegacyDelay(1_970), "legacy maximum is capped at 2,000 ms");
		assertEquals(new AutoExperimentDelayRange(220, 260),
			AutoExperimentDelayRange.restore(190f, 220f, null, 190, 260),
			"saved new minimum wins while the missing maximum migrates from the legacy value");
		assertEquals(new AutoExperimentDelayRange(220, 240),
			AutoExperimentDelayRange.restore(190f, 220f, 240f, 190, 260),
			"saved new endpoints take precedence over the old single delay");
		assertEquals(new AutoExperimentDelayRange(250, 350),
			AutoExperimentDelayRange.restore(null, null, null, 250, 350),
			"a fresh config retains the approved module default endpoints");
		assertSame(ExperimentType.CHRONOMATRON,
			AutoExperimentsModule.hintedSequenceType("Chronomatron (Unrecognized Tier)"),
			"malformed Chronomatron tier titles can be paused without becoming click-eligible");
		assertSame(ExperimentType.ULTRASEQUENCER,
			AutoExperimentsModule.hintedSequenceType("Ultrasequencer (Unrecognized Tier)"),
			"malformed Ultrasequencer tier titles can be paused without becoming click-eligible");
		assertSame(null, AutoExperimentsModule.hintedSequenceType("Superpairs (Unrecognized Tier)"),
			"unknown Superpairs titles remain outside Auto Experiments");
		assertTrue(ExperimentController.hasActiveOwner(ExperimentType.CHRONOMATRON,
			true, true, true, true), "Solver and Auto share one Chronomatron owner path when both are enabled");
		assertSame(ExperimentController.INSTANCE.engine(), ExperimentSolverModule.INSTANCE.engine(),
			"the Solver facade exposes the controller's single shared engine");
		assertTrue(ExperimentController.hasActiveOwner(ExperimentType.ULTRASEQUENCER,
			false, true, true, true), "Auto can own Ultrasequencer observation while Solver is off");
		assertFalse(ExperimentController.hasActiveOwner(ExperimentType.SUPERPAIRS,
			false, false, true, true), "Auto never takes ownership of Superpairs");

		ExperimentSolverEngine clickEngine = new ExperimentSolverEngine();
		String ultraTitle = "Ultrasequencer (High)";
		List<ExperimentCell> ultraCells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2));
		ExperimentSnapshot clickSnapshot = new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			ultraCells, "black", 1);
		ExperimentSnapshot solveSnapshot = new ExperimentSnapshot(ultraTitle, "Timer: 1.0s",
			ultraCells, "white", 2);
		clickEngine.observe(clickSnapshot);
		var clickView = clickEngine.observe(solveSnapshot);
		assertSame(ExperimentPhase.SOLVE, clickView.phase(), "Ultrasequencer test fixture enters solve phase");
		long ultraNowNanos = 5_000_000_000L;
		UltrasequencerSequenceExecutor ultraAuto = new UltrasequencerSequenceExecutor();
		Object ultraScreen = new Object();
		Object ultraMenu = new Object();
		var armedUltraStep = ultraAuto.tick(ultraScreen, ultraMenu, 1, true, clickView,
			ultraNowNanos, 0L);
		assertSame(UltrasequencerSequenceExecutor.Action.CLICK, armedUltraStep.action(),
			"the solver's complete Ultrasequencer solution arms its first slot");
		ultraAuto.dispatchFinished(true, ultraNowNanos, 0L);
		SolverView milestoneView = new SolverView(clickView.type(), clickView.tier(), clickView.phase(),
			clickView.currentSequenceLength(), clickView.completedRounds(), clickView.sequence(),
			clickView.authoritativeIndex(), clickView.predictedIndex(), clickView.visualIndex(),
			clickView.current(), clickView.next(), clickView.nextNext(), clickView.superpairs(),
			clickView.milestone(), true);
		assertSame(UltrasequencerSequenceExecutor.Action.CLOSE_MENU,
			ultraAuto.tick(ultraScreen, ultraMenu, 1, true, milestoneView,
				ultraNowNanos + 1, 0L).action(),
			"a milestone reached after an Auto-dispatched step requests one menu close");
		assertSame(UltrasequencerSequenceExecutor.Action.STOPPED,
			ultraAuto.tick(ultraScreen, ultraMenu, 1, true, milestoneView,
				ultraNowNanos + 2, 0L).action(), "Ultrasequencer milestone closure is one-shot");
		UltrasequencerSequenceExecutor manualUltraMilestone = new UltrasequencerSequenceExecutor();
		assertSame(UltrasequencerSequenceExecutor.Action.STOPPED,
			manualUltraMilestone.tick(ultraScreen, ultraMenu, 2, true, milestoneView,
				ultraNowNanos, 0L).action(),
			"a milestone reached without an Auto click stops without closing the menu");

		ExperimentClickGate clickGate = new ExperimentClickGate();
		int[] vanillaClicks = {0};
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 30, false,
			ignored -> vanillaClicks[0]++), "a replaced screen/menu context blocks a click");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 31, true,
			ignored -> vanillaClicks[0]++), "a future or stale slot never dispatches");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 1, 30, true,
			ignored -> vanillaClicks[0]++), "an expected slot paired with a stale index never dispatches");
		assertEquals(0, vanillaClicks[0], "rejected clicks never reach vanilla");
		assertEquals(0, clickEngine.view().visualIndex(), "rejected clicks do not advance the shared cursor");
		assertTrue(clickGate.dispatchSequenceClick(clickEngine, 30, true,
			ignored -> vanillaClicks[0]++), "the exact current slot dispatches through the common click gate");
		assertEquals(1, vanillaClicks[0], "one accepted action invokes vanilla exactly once");
		assertEquals(1, clickEngine.view().visualIndex(), "one accepted vanilla dispatch confirms one engine step");
		assertFalse(clickGate.dispatchSequenceClick(clickEngine, 30, true,
			ignored -> vanillaClicks[0]++), "a previously current slot becomes stale after confirmation");
		assertEquals(1, vanillaClicks[0], "a stale repeated request is not retried");
		assertFalse(clickGate.dispatchSequenceClick(new ExperimentSolverEngine(), 30, true,
			ignored -> vanillaClicks[0]++), "an unknown or idle engine never dispatches");

		ExperimentSolverEngine repeatedSlotEngine = repeatedChronomatronEngine();
		assertEquals(List.of(17), repeatedSlotEngine.view().sequence().get(0).slotIds(),
			"first repeated Chronomatron step maps to the shared slot");
		assertEquals(List.of(17), repeatedSlotEngine.view().sequence().get(1).slotIds(),
			"second repeated Chronomatron step maps to the same shared slot");

		ExperimentSolverEngine emptyPaneEngine = repeatedChronomatronEngine();
		assertTrue(emptyPaneEngine.onClick(17).expected(), "first repeated pane click is sequence-valid");
		assertTrue(emptyPaneEngine.confirmClick(0, 17).visualStateChanged(),
			"the first repeated pane click advances to the second sequence position");
		emptyPaneEngine.observe(new ExperimentSnapshot("Chronomatron (High)", "Correct!",
			List.of(new ExperimentCell(17, null, -1, true, false, true)), null, 9, false), null);
		assertEquals(List.of(), emptyPaneEngine.view().sequence().get(1).slotIds(),
			"a blank pane snapshot does not claim that a repeated color is currently clickable");
		assertFalse(emptyPaneEngine.onClick(17).expected(),
			"the solver refuses a repeated click while the live slot has no recognized value");
		assertFalse(emptyPaneEngine.confirmClick(1, 17).visualStateChanged(),
			"an unrecognized pane cannot advance the repeated sequence position");
		assertEquals(1, emptyPaneEngine.view().visualIndex(),
			"a transient empty pane leaves the Chronomatron cursor at the current step");

		long millis = 1_000_000L;
		long start = 5_000_000_000L;
		Object screen = new Object();
		Object menu = new Object();
		AutoExperimentAutomation.Snapshot manualFirstStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 0, false);
		AutoExperimentAutomation.Snapshot manuallyAdvancedStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Correct!", 1, 2, 18, 0, false);
		AutoExperimentAutomation staleManualStep = new AutoExperimentAutomation();
		staleManualStep.tick(manualFirstStep, start, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			staleManualStep.tick(manuallyAdvancedStep, start + 100 * millis, 360 * millis).action(),
			"an unsynchronized manual advance would trip the old delayed-index watchdog");
		AutoExperimentAutomation synchronizedManualStep = new AutoExperimentAutomation();
		synchronizedManualStep.tick(manualFirstStep, start, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			synchronizedManualStep.synchronizeAfterManualProgress(manuallyAdvancedStep,
				start + 100 * millis, 190 * millis).action(),
			"a correct confirmed manual Chronomatron step re-anchors the pending delay");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			synchronizedManualStep.tick(manuallyAdvancedStep, start + 289 * millis, 360 * millis).action(),
			"manual progress synchronization retains the normal inter-click delay");
		var synchronizedClick = synchronizedManualStep.tick(manuallyAdvancedStep,
			start + 290 * millis, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, synchronizedClick.action(),
			"the newly current Chronomatron step becomes due after its re-anchored delay");
		assertEquals(1, synchronizedClick.sequenceIndex(), "manual resynchronization queues the next sequence index");
		assertEquals(18, synchronizedClick.slotId(), "manual resynchronization targets the next expected slot");
		AutoExperimentAutomation automation = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot firstStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start, 360 * millis, 190 * millis).action(),
			"automation arms a solve phase before its first-click delay");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start + 359 * millis, 360 * millis, 190 * millis).action(),
			"first-click delay does not fire early");
		var firstClick = automation.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, firstClick.action(),
			"the first step becomes due exactly at the configured delay");
		assertEquals(0, firstClick.sequenceIndex(), "the pending click carries its sequence index");
		assertEquals(17, firstClick.slotId(), "automation requests the current expected slot");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis).action(),
			"a pending click cannot be emitted twice before its dispatch result");
		AutoExperimentAutomation.Snapshot secondStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Correct!", 1, 2, 18, 8, false);
		automation.clickResult(true, secondStep, start + 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(secondStep, start + 549 * millis, 360 * millis, 190 * millis).action(),
			"between-click delay does not fire early, including during transient Correct feedback");
		var secondClick = automation.tick(secondStep, start + 550 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, secondClick.action(),
			"the next step becomes due exactly at the between-click delay");
		assertEquals(18, secondClick.slotId(), "the next request follows the current shared solver step");
		AutoExperimentAutomation revisionGated = new AutoExperimentAutomation();
		revisionGated.tickWithMenuRevision(firstStep, start, 0L, 20);
		var revisionClick = revisionGated.tickWithMenuRevision(firstStep, start, 0L, 20);
		assertSame(AutoExperimentAutomation.Action.CLICK, revisionClick.action(),
			"revision-gated Chronomatron sends one validated vanilla click");
		revisionGated.clickResultWithMenuRevision(true, secondStep, start, 190 * millis, 20, 17);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			revisionGated.tickWithMenuRevision(secondStep, start + 200 * millis, 0L, 20).action(),
			"the local Chronomatron cursor cannot trigger the next click before a new menu update");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			revisionGated.tickWithMenuRevision(secondStep, start + 200 * millis, 0L, 21).action(),
			"a menu update starts a fresh between-click delay");
		var afterMenuUpdate = revisionGated.tickWithMenuRevision(
			secondStep, start + 390 * millis, 0L, 21);
		assertSame(AutoExperimentAutomation.Action.CLICK, afterMenuUpdate.action(),
			"the next Chronomatron slot becomes due only after update and delay");
		AutoExperimentAutomation missingMenuUpdate = new AutoExperimentAutomation();
		missingMenuUpdate.tickWithMenuRevision(firstStep, start, 0L, 30);
		missingMenuUpdate.tickWithMenuRevision(firstStep, start, 0L, 30);
		missingMenuUpdate.clickResultWithMenuRevision(true, secondStep, start, 0L, 30, 17);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			missingMenuUpdate.tickWithMenuRevision(secondStep, start + 15_000 * millis, 0L, 30).action(),
			"a missing Chronomatron menu update times out without retrying the dispatched click");

		AutoExperimentAutomation repeatedSlotAutomation = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot repeatedFirstStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 0, false);
		repeatedSlotAutomation.tick(repeatedFirstStep, start, 360 * millis);
		var repeatedFirstClick = repeatedSlotAutomation.tick(repeatedFirstStep,
			start + 360 * millis, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, repeatedFirstClick.action(),
			"the first click for two repeated slots is queued");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			repeatedSlotAutomation.tick(repeatedFirstStep, start + 360 * millis, 360 * millis).action(),
			"the same sequence index cannot emit a duplicate while its click is pending");
		ExperimentClickGate repeatedSlotGate = new ExperimentClickGate();
		int[] repeatedVanillaClicks = {0};
		assertTrue(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedFirstClick.sequenceIndex(), repeatedFirstClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the first expected Chronomatron sequence index dispatches its slot");
		assertFalse(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedFirstClick.sequenceIndex(), repeatedFirstClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the same slot is rejected when requested again at its stale sequence index");
		AutoExperimentAutomation.Snapshot repeatedSecondStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Correct!", 1, 2, 17, 0, false);
		repeatedSlotAutomation.clickResult(true, repeatedSecondStep,
			start + 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			repeatedSlotAutomation.tick(repeatedSecondStep, start + 549 * millis, 360 * millis).action(),
			"the same slot still observes the selected between-click delay");
		var repeatedSecondClick = repeatedSlotAutomation.tick(repeatedSecondStep,
			start + 550 * millis, 360 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, repeatedSecondClick.action(),
			"the same slot is eligible after the sequence index advances");
		assertEquals(1, repeatedSecondClick.sequenceIndex(), "the second click carries the advanced sequence index");
		assertEquals(17, repeatedSecondClick.slotId(), "the second click can reuse the first slot id");
		assertTrue(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedSecondClick.sequenceIndex(), repeatedSecondClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the same Chronomatron slot dispatches again for the next sequence index");
		assertEquals(2, repeatedVanillaClicks[0], "repeated slot flow performs exactly two vanilla dispatches");
		assertFalse(repeatedSlotGate.dispatchSequenceClick(repeatedSlotEngine,
			repeatedSecondClick.sequenceIndex(), repeatedSecondClick.slotId(), true,
			ignored -> repeatedVanillaClicks[0]++),
			"the second sequence index also cannot be dispatched twice");
		AutoExperimentAutomation.Snapshot repeatedRoundComplete = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 2, 2, -1, 0, false);
		repeatedSlotAutomation.clickResult(true, repeatedRoundComplete,
			start + 550 * millis, 190 * millis);

		assertSame(AutoExperimentAutomation.Action.WAIT,
			new AutoExperimentAutomation().tick(autoSnapshot(new Object(), new Object(),
				ExperimentType.ULTRASEQUENCER, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
				"Timer: 0.0s", 0, 2, 31, 0, false), start, 0L).action(),
			"the Chronomatron scheduler cannot execute an Ultrasequencer snapshot");
		AutoExperimentAutomation.Snapshot completedRound = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 2, 2, -1, 8, false);
		automation.clickResult(true, completedRound, start + 550 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(completedRound, start + 15_549 * millis, 360 * millis, 190 * millis).action(),
			"stage watchdog tolerates a laggy server for the full fifteen-second transition window");
		var timedOut = automation.tick(completedRound, start + 15_550 * millis,
			360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.PAUSED, timedOut.action(),
			"a stalled stage pauses instead of retrying the final click");
		assertTrue(timedOut.explanation().contains("no click was retried"),
			"watchdog explains that no click was retried");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			automation.tick(completedRound, start + 15_600 * millis, 360 * millis, 190 * millis).action(),
			"a watchdog pause reports only once and stays paused");

		AutoExperimentAutomation manualTakeover = new AutoExperimentAutomation();
		manualTakeover.tick(firstStep, start, 360 * millis, 190 * millis);
		manualTakeover.tick(firstStep, start + 360 * millis, 360 * millis, 190 * millis);
		assertTrue(!manualTakeover.pauseForManualInput().isBlank(), "manual takeover explains the pause once");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			manualTakeover.tick(firstStep, start + 500 * millis, 360 * millis, 190 * millis).action(),
			"manual input cancels a pending Auto click");
		assertTrue(manualTakeover.pauseForManualInput().isBlank(), "manual takeover notification is not repeated");
		manualTakeover.reset();
		manualTakeover.tick(firstStep, start + 1_000 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK,
			manualTakeover.tick(firstStep, start + 1_360 * millis, 360 * millis, 190 * millis).action(),
			"toggling off and on clears the pause and arms a fresh delay");
		AutoExperimentAutomation disabled = new AutoExperimentAutomation();
		disabled.tick(firstStep, start, 360 * millis, 190 * millis);
		AutoExperimentAutomation.Snapshot switchedOff = new AutoExperimentAutomation.Snapshot(false, true,
			true, screen, menu, ExperimentType.CHRONOMATRON, ExperimentTier.HIGH,
			ExperimentPhase.SOLVE, "Timer: 4.0s", true, 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			disabled.tick(switchedOff, start + 350 * millis, 360 * millis, 190 * millis).action(),
			"disabling Auto cancels the queued step");
		disabled.tick(firstStep, start + 500 * millis, 360 * millis, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			disabled.tick(firstStep, start + 859 * millis, 360 * millis, 190 * millis).action(),
			"re-enabling Auto starts a fresh delay instead of using stale queued work");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			disabled.tick(firstStep, start + 860 * millis, 360 * millis, 190 * millis).action(),
			"re-enabled automation clicks only after its new first delay");
		AutoExperimentAutomation rejectedDispatch = new AutoExperimentAutomation();
		rejectedDispatch.tick(firstStep, start, 0L, 190 * millis);
		var rejectedClick = rejectedDispatch.tick(firstStep, start, 0L, 190 * millis);
		assertSame(AutoExperimentAutomation.Action.CLICK, rejectedClick.action(),
			"a zero-delay click still passes through the one-action queue");
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			rejectedDispatch.clickResult(false, firstStep, start, 190 * millis).action(),
			"a rejected by-slot dispatch pauses without retrying");

		AutoExperimentAutomation replacement = new AutoExperimentAutomation();
		replacement.tick(firstStep, start, 360 * millis, 190 * millis);
		Object replacementScreen = new Object();
		AutoExperimentAutomation.Snapshot replacedContext = autoSnapshot(replacementScreen, new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 8, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			replacement.tick(replacedContext, start + 350 * millis, 360 * millis, 190 * millis).action(),
			"screen or menu replacement cancels a stale scheduled slot");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			replacement.tick(replacedContext, start + 709 * millis, 360 * millis, 190 * millis).action(),
			"replacement context receives its own full first delay");
		assertSame(AutoExperimentAutomation.Action.CLICK,
			replacement.tick(replacedContext, start + 710 * millis, 360 * millis, 190 * millis).action(),
			"only the expected slot from the current menu becomes eligible");
		AutoExperimentAutomation changedSlot = new AutoExperimentAutomation();
		changedSlot.tick(firstStep, start, 360 * millis);
		AutoExperimentAutomation.Snapshot unexpectedSlot = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 18, 8, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			changedSlot.tick(unexpectedSlot, start + 360 * millis, 360 * millis).action(),
			"a changed expected slot during a delay pauses without dispatching or retrying");

		AutoExperimentAutomation unknownTier = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot unknown = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.UNKNOWN, ExperimentPhase.SOLVE,
			"Timer: 4.0s", 0, 2, 17, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknown, start, 360 * millis, 190 * millis).action(),
			"an unknown tier pauses before any automatic click");
		assertSame(AutoExperimentAutomation.Action.WAIT,
			unknownTier.tick(unknown, start + 500 * millis, 360 * millis, 190 * millis).action(),
			"an unknown-tier pause is not repeatedly announced or retried");
		unknownTier.reset();
		AutoExperimentAutomation.Snapshot unknownState = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.IDLE,
			"server is syncing", 0, 0, -1, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknownState, start, 360 * millis, 190 * millis).action(),
			"an unavailable sequence state pauses without clicking");
		unknownTier.reset();
		AutoExperimentAutomation.Snapshot unknownSolveStatus = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"server is syncing", 0, 2, 17, 0, false);
		assertSame(AutoExperimentAutomation.Action.PAUSED,
			unknownTier.tick(unknownSolveStatus, start, 360 * millis, 190 * millis).action(),
			"an unknown server state cannot authorize a click using stale solver context");

		AutoExperimentAutomation milestoneStop = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot milestone = autoSnapshot(new Object(), new Object(),
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 9, 9, -1, 8, true);
		assertSame(AutoExperimentAutomation.Action.STOPPED,
			milestoneStop.tick(milestone, start, 0L, 0L).action(),
			"a milestone reached without an accepted Auto click stops without closing a menu");
		AutoExperimentAutomation milestoneClose = new AutoExperimentAutomation();
		AutoExperimentAutomation.Snapshot lastAutoStep = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Timer: 1.0s", 8, 9, 17, 8, false);
		milestoneClose.tick(lastAutoStep, start, 0L);
		var lastAutoClick = milestoneClose.tick(lastAutoStep, start, 0L);
		assertSame(AutoExperimentAutomation.Action.CLICK, lastAutoClick.action(),
			"the final requested milestone click enters the one-click queue");
		AutoExperimentAutomation.Snapshot reachedAfterAcceptedClick = autoSnapshot(screen, menu,
			ExperimentType.CHRONOMATRON, ExperimentTier.HIGH, ExperimentPhase.ROUND_COMPLETE,
			"Round Complete", 9, 9, -1, 8, true);
		assertSame(AutoExperimentAutomation.Action.CLOSE_MENU,
			milestoneClose.clickResult(true, reachedAfterAcceptedClick, start, 190 * millis).action(),
			"an accepted click that reaches the shared milestone requests normal menu closure");
		assertSame(AutoExperimentAutomation.Action.STOPPED,
			milestoneClose.tick(reachedAfterAcceptedClick, start + 1 * millis, 0L).action(),
			"milestone menu closure is emitted once even if the screen has not left yet");
		AutoExperimentAutomation.Snapshot superpairs = autoSnapshot(new Object(), new Object(),
			ExperimentType.SUPERPAIRS, ExperimentTier.HIGH, ExperimentPhase.SOLVE,
			"Next button", 0, 1, 10, -1, false);
		assertSame(AutoExperimentAutomation.Action.WAIT,
			milestoneStop.tick(superpairs, start + 1_000 * millis, 0L, 0L).action(),
			"Auto Experiments ignores Superpairs completely");

		// The Solver retains its complete solution and phase when the board repaints its pane colours.
		ExperimentSolverEngine paneChurn = new ExperimentSolverEngine();
		List<ExperimentCell> rememberCells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2), ExperimentCell.number(32, 3));
		paneChurn.markUltrasequencerDirty(List.of("gray"));
		paneChurn.observe(new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			rememberCells, "gray", 200));
		paneChurn.observe(new ExperimentSnapshot(ultraTitle, "Timer: 10.0s", rememberCells, "gray", 201));
		assertSame(ExperimentPhase.SOLVE, paneChurn.view().phase(),
			"the Ultrasequencer fixture enters its solve");
		assertEquals(3, paneChurn.view().sequence().size(),
			"the Solver exposes the complete remembered sequence as one ordered solution");
		assertEquals(0, paneChurn.view().visualIndex(),
			"Auto execution does not need to mutate the Solver cursor");
		paneChurn.markUltrasequencerDirty(List.of("white"));
		paneChurn.markUltrasequencerDirty(List.of("gray"));
		assertSame(ExperimentPhase.SOLVE, paneChurn.view().phase(),
			"a pane repaint during the solve cannot close the round");
		assertEquals(0, paneChurn.view().visualIndex(),
			"a pane repaint does not mutate the Solver's read-only execution source");
		assertEquals(3, paneChurn.view().sequence().size(),
			"the ordered solution remains complete after the pane repaint");
		paneChurn.observe(new ExperimentSnapshot(ultraTitle, "Remember the pattern!",
			List.of(ExperimentCell.number(30, 1), ExperimentCell.number(31, 2),
				ExperimentCell.number(32, 3)), "gray", 202));
		assertEquals(3, paneChurn.view().sequence().size(),
			"the next memory notice replaces the finished round");
		assertEquals(0, paneChurn.view().completedRounds(),
			"Auto's separate cursor does not fabricate Solver-confirmed round progress");

		// Regression: the phase transition used to depend on the exact status spelling. If Hypixel
		// trims, reformats or slightly rewords either notice, the model stayed in WAIT forever while
		// the Solver kept rendering the solution, so Auto never queued a click.
		for (String rememberNotice : List.of("Remember the pattern!", "Remember the pattern",
			"\u00A7eRemember the pattern!")) {
			ExperimentSolverEngine relaxed = relaxedUltraEngine(rememberNotice, "Timer: 1.0s");
			assertSame(ExperimentPhase.SOLVE, relaxed.view().phase(),
				"Ultrasequencer accepts the memory notice " + rememberNotice);
			assertEquals(30, relaxed.view().current().orElseThrow().slotIds().get(0),
				"the remembered first slot stays resolvable for " + rememberNotice);
		}
		for (String timerNotice : List.of("Timer: 1.0s", "Timer:1.0s", "\u00A7eTimer: 1.0s")) {
			ExperimentSolverEngine relaxed = relaxedUltraEngine("Remember the pattern!", timerNotice);
			assertSame(ExperimentPhase.SOLVE, relaxed.view().phase(),
				"Ultrasequencer accepts the solve notice " + timerNotice);
		}
		ExperimentSolverEngine unreadable = relaxedUltraEngine("Remember the pattern!", "Server syncing");
		assertSame(ExperimentPhase.WAITING, unreadable.view().phase(),
			"an unrecognised status still cannot open the solve");
		assertFalse(unreadable.view().milestoneReached(),
			"an unreadable solve status never reports a reached milestone");
	}

	/** Builds one Ultrasequencer round from the two status notices under test. */
	private static ExperimentSolverEngine relaxedUltraEngine(String rememberNotice, String solveNotice) {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Ultrasequencer (High)";
		List<ExperimentCell> cells = List.of(ExperimentCell.number(30, 1),
			ExperimentCell.number(31, 2));
		engine.markUltrasequencerDirty(List.of("gray"));
		engine.observe(new ExperimentSnapshot(title, rememberNotice, cells, "gray", 300));
		engine.observe(new ExperimentSnapshot(title, solveNotice, cells, "gray", 301));
		return engine;
	}

	private static AutoExperimentAutomation.Snapshot autoSnapshot(Object screen, Object menu,
		ExperimentType type, ExperimentTier tier, ExperimentPhase phase, String status,
		int currentIndex, int sequenceLength, int expectedSlot, int completedRounds,
		boolean milestoneReached) {
		return new AutoExperimentAutomation.Snapshot(true, true, true, screen, menu, type, tier,
			phase, status, sequenceLength > 0, currentIndex, sequenceLength, expectedSlot,
			completedRounds, milestoneReached);
	}

	private static ExperimentSolverEngine repeatedChronomatronEngine() {
		ExperimentSolverEngine engine = new ExperimentSolverEngine();
		String title = "Chronomatron (High)";
		observeRepeatedSlotChronomatron(engine, title, 1, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 2, ChronomatronEvent.status("Timer: 4.0s"));
		assertTrue(engine.onClick(17).expected(), "the first one-step round can be completed to build a repeated trace");
		assertTrue(engine.confirmClick(17).visualStateChanged(), "the first one-step round advances locally");
		observeRepeatedSlotChronomatron(engine, title, 3, ChronomatronEvent.status("Remember the pattern!"));
		observeRepeatedSlotChronomatron(engine, title, 4, ChronomatronEvent.board(17, "red", false));
		observeRepeatedSlotChronomatron(engine, title, 5, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 6, ChronomatronEvent.board(17, "red", false));
		observeRepeatedSlotChronomatron(engine, title, 7, ChronomatronEvent.board(17, "red", true));
		observeRepeatedSlotChronomatron(engine, title, 8, ChronomatronEvent.status("Timer: 4.0s"));
		assertSame(ExperimentPhase.SOLVE, engine.view().phase(),
			"repeated Chronomatron trace reaches the solve phase");
		assertEquals(2, engine.view().sequence().size(), "repeated Chronomatron trace has two steps");
		return engine;
	}

	private static void observeRepeatedSlotChronomatron(ExperimentSolverEngine engine, String title,
		long revision, ChronomatronEvent event) {
		String status = event.kind() == ChronomatronEvent.Kind.STATUS
			? event.status() : "Remember the pattern!";
		boolean highlighted = event.kind() == ChronomatronEvent.Kind.BOARD && event.highlighted();
		engine.observe(new ExperimentSnapshot(title, status,
			List.of(ExperimentCell.token(17, "red", highlighted)), null, revision,
			event.kind() == ChronomatronEvent.Kind.STATUS), event);
	}

	/**
	 * Settings that must stay in the persisted lists, and one that must stay out of them.
	 *
	 * <p>A setting is persisted only because it was passed to the module's own constructor, so a row
	 * that is created, grouped and rendered but never passed looks like it works and silently reverts
	 * after a restart. That is exactly what happened to the macro/inventory colour toggle.
	 */
	private static void checkPersistedSettings() {
		assertTrue(VisualModule.INSTANCE.booleanSettings().stream()
				.anyMatch(setting -> setting.name().equals("Theme Macro + Inventory Colors")),
			"the macro and inventory colour toggle is registered as a real setting");
		assertTrue(GeneralModule.INSTANCE.booleanSettings().stream()
				.anyMatch(setting -> setting.name().equals("Island Detection API")),
			"island detection is a panel row rather than a config-file-only value");
		assertTrue(VisualModule.INSTANCE.booleanSettings().stream()
				.noneMatch(setting -> setting.name().equals("Theme Macro Colors")),
			"the renamed toggle no longer offers its old key for writing");
		// The border floor is session state, not a choice, and must never be written to the config.
		assertTrue(GardenPlotBordersModule.INSTANCE.booleanSettings().stream()
				.noneMatch(setting -> setting.name().equals("Height Captured")),
			"the garden height latch is not persisted as a user setting");
	}

	private static void checkModuleManagerMembership() {
		Module general = GeneralModule.INSTANCE;
		if (!ModuleManager.contains(general.category(), general)) ModuleManager.register(general);
		for (Module module : ModuleManager.modules()) {
			assertTrue(ModuleManager.contains(module.category(), module)
				== ModuleManager.modules(module.category()).contains(module),
				"the allocation-free registry lookup matches category-list membership for " + module.name());
		}
		assertFalse(ModuleManager.contains(null, general),
			"a module is not found under a different category");
		assertFalse(ModuleManager.contains(general.category(), null),
			"a null module is never registered");
		Module unregistered = new Module("Offline Unregistered Fixture", "", general.category()) { };
		assertFalse(ModuleManager.contains(unregistered.category(), unregistered),
			"category membership still rejects an unregistered module");
	}

	/** The icon tint belongs to Theme, follows its presets, and remains a normal editable color. */
	private static void checkThemeIconColorSetting() {
		VisualModule theme = VisualModule.INSTANCE;
		ColorSetting iconColor = theme.iconColorSetting();
		ColorSetting text = theme.colorSettings().stream()
			.filter(setting -> setting.name().equals("Text"))
			.findFirst().orElseThrow();
		assertTrue(iconColor.argb() == text.argb(),
			"the fresh-install icon color starts at the Theme Text color");
		assertTrue("Theme.Icon color".equals(theme.configName() + "." + iconColor.name()),
			"Theme icon color keeps its persisted config key");
		assertTrue(theme.colorSettings().contains(iconColor),
			"Theme icon color is registered as a persisted setting");
		SettingGroup colours = theme.groups().stream()
			.filter(group -> "Colours".equals(group.name()))
			.findFirst().orElseThrow();
		assertTrue(colours.settings().contains(iconColor),
			"Theme icon color is visible in the Colours group");
		assertTrue(theme.groups().stream().noneMatch(group -> "Icons".equals(group.name())),
			"Theme no longer has a separate Icons group");
		assertTrue(GeneralModule.INSTANCE.colorSettings().stream()
			.noneMatch(setting -> setting.name().equals("Icon color")),
			"General no longer registers the moved icon color setting");

		int[][] originalColors = new int[theme.colorSettings().size()][4];
		for (int i = 0; i < theme.colorSettings().size(); i++) {
			ColorSetting setting = theme.colorSettings().get(i);
			originalColors[i] = new int[]{setting.red(), setting.green(), setting.blue(), setting.alpha()};
		}
		try {
			Map<String, VisualModule.Preset> presets = Map.of(
				"Onyx", VisualModule.Preset.ONYX,
				"Tracker", VisualModule.Preset.TRACKER,
				"Amethyst", VisualModule.Preset.AMETHYST,
				"Midnight", VisualModule.Preset.MIDNIGHT,
				"Forest", VisualModule.Preset.FOREST,
				"Aurora", VisualModule.Preset.AURORA,
				"Ember", VisualModule.Preset.EMBER,
				"Orchid", VisualModule.Preset.ORCHID,
				"Ice Glass", VisualModule.Preset.ICE_GLASS,
				"Rose Glass", VisualModule.Preset.ROSE_GLASS);
			assertTrue(theme.actions().size() == 10
				&& theme.actions().stream().allMatch(action -> presets.containsKey(action.name())),
				"all ten built-in theme presets are registered");
			for (var preset : presets.entrySet()) {
				VisualModule.applyPreset(preset.getValue());
				assertTrue(iconColor.argb() == text.argb(),
					"each built-in preset initializes Icon color from its Text color: " + preset.getKey());
			}
			iconColor.set(12, 34, 56, 255);
			assertTrue(iconColor.red() == 12 && iconColor.green() == 34 && iconColor.blue() == 56,
				"the RGB icon color remains user-editable after selecting a preset");
		} finally {
			for (int i = 0; i < theme.colorSettings().size(); i++) {
				int[] rgba = originalColors[i];
				theme.colorSettings().get(i).set(rgba[0], rgba[1], rgba[2], rgba[3]);
			}
			theme.refreshTheme();
		}
	}

	private static void assertDebugSetting(Module module, String name) {
		for (BooleanSetting setting : module.booleanSettings()) {
			if (setting.name().equals(name)) {
				assertTrue(setting.isDebugOnly(), module.name() + " marks " + name + " as debug-only");
				return;
			}
		}
		throw new AssertionError(module.name() + " is missing debug setting " + name);
	}

	private static void assertDebugGroup(Module module, String name) {
		for (SettingGroup group : module.groups()) {
			if (group.name() != null && group.name().equals(name)) {
				assertTrue(group.debugOnly(), module.name() + " marks " + name + " as debug-only");
				return;
			}
		}
		throw new AssertionError(module.name() + " is missing debug group " + name);
	}

	private static void assertSame(Object expected, Object actual, String label) {
		if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
