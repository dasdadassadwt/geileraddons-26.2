package geiler.addons.client;

import geiler.addons.client.module.ModuleManager;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;

import java.util.List;
import java.util.stream.Collectors;

/** Guards the registered feature catalog and ordering against the frozen 26.1.2 target. */
public final class RegisteredModuleCatalogChecks {
	private static final List<String> EXPECTED_MODULES = List.of(
		"DebugModule",
		"GeneralModule",
		"SlotIdsModule",
		"CursorTrackerModule",
		"InventoryCaptureModule",
		"KuudraInfoModule",
		"PearlWaypointsModule",
		"SupplyHelperModule",
		"KuudraWaypointsModule",
		"BuildHelperModule",
		"DangerZoneAlertModule",
		"CommandKeybindsModule",
		"MouseLockModule",
		"RareCropTitleModule",
		"ComposterDisplayModule",
		"GardenKeybindsModule",
		"GardenCommandKeybindsModule",
		"VisitorShoppingListModule",
		"GreenhouseHelperModule",
		"VisitorMessageHiderModule",
		"GardenPlotsOverlayModule",
		"I4HelperModule",
		"ExperimentSolverModule",
		"AutoExperimentsModule",
		"EnchantColorsModule",
		"MissingEnchantsModule",
		"InventoryHudModule",
		"AutoKickModule",
		"PartyFinderStatsModule",
		"PartyFinderListingsModule",
		"DungeonHelperModule",
		"BoxDoorsModule",
		"DungeonDoorBlockReplacementModule",
		"DungeonRoomOverlayModule",
		"DungeonRoomAlertModule",
		"DungeonKeyPickupModule",
		"DungeonPuzzleSolversModule",
		"DungeonScorerModule",
		"DungeonRunSplitsModule",
		"DungeonBloodCampModule",
		"DungeonLividSolverModule",
		"DungeonInvincibilityTimerModule",
		"SpiritLeapMenuModule",
		"DungeonTerminalStatsModule",
		"DungeonTerminalTitlesModule",
		"SecretClickedModule",
		"TikiHelperModule",
		"SafariFloorDropsModule",
		"HideyhoFinderModule",
		"SparklingCritterModule",
		"CritterTrackerModule",
		"CritterMarkersModule",
		"CritterAlertsModule",
		"PestHighlighterModule",
		"GardenPlotBordersModule",
		"PestTrackerModule",
		"SlayerTrackerModule",
		"SlayerAlertsModule",
		"SlayerHudModule",
		"SlayerBossHighlightModule",
		"EndermanSlayerModule",
		"BlazeSlayerModule",
		"VampireSlayerModule",
		"CropFeverTimerModule",
		"ChatChannelsModule",
		"ChatModule",
		"PartyCommandsModule",
		"HideAllParticlesModule",
		"HidePlayersModule",
		"HideF7BossSoundsModule",
		"BossBarPercentModule",
		"ItemAnimationsModule",
		"LobbyTargetWatchModule",
		"PlayerInventoryControlsModule",
		"PlayerNamesModule",
		"PlayerSizeModule",
		"ScoreboardModule",
		"SystemClockHudModule",
		"PerformanceHudModule",
		"ThirdPersonCameraModule",
		"TreeTrackerModule",
		"TreeNotifierModule",
		"ForagingHighlightsModule",
		"TemplePuzzlesModule",
		"TunerSolverModule",
		"SweepOverlayModule",
		"ContestTimerHudModule",
		"MacrosModule",
		"VisualModule",
		"MobHighlightModule",
		"DungeonMobEspModule",
		"DungeonBossEspModule",
		"DungeonClassEspModule",
		"ItemRarityBackgroundsModule",
		"NoCursorResetModule",
		"AliasesModule",
		"InstanceRequeueModule",
		"TrailModule",
		"NoLoadingScreenModule",
		"BlockEspModule",
		"InventoryButtonsModule",
		"SnapTapModule",
		"AutoSprintModule",
		"FullbrightModule",
		"CustomMainMenuModule",
		"BlockOverlayModule",
		"ScrollableTooltipModule",
		"FreecamModule",
		"ZoomModule",
		"BorderlessFullscreenModule",
		"NoRenderModule",
		"AnimationFixModule",
		"DoubleUseFixModule",
		"DisconnectFixModule",
		"ItemCooldownsModule",
		"GyroWandModule",
		"MimicAnnouncerModule",
		"WaypointsModule",
		"ChatWaypointsModule",
		"BlessingDisplayModule",
		"TerminalEspModule",
		"MelodyMessageModule",
		"TrappedChestEspModule",
		"ArrowAlignModule",
		"SimonSaysModule",
		"CroesusModule",
		"ChestRollModule",
		"SpeedDisplayModule",
		"QuiverDisplayModule",
		"GhostVisionModule",
		"TerracottaTimerModule",
		"WardrobeKeybindsModule",
		"LoadoutKeybindsModule",
		"PetKeybindsModule",
		"SpringBootsModule",
		"RagnarockModule",
		"TickTimersModule",
		"RelicTimerModule",
		"FireFreezeTimerModule",
		"WitherDragonsModule",
		"KingRelicModule",
		"DragonStackAimerModule",
		"CompactDamageModule",
		"StorageOverlayModule",
		"TerminalSolverModule",
		"AutoTermsModule",
		"LeverAimAssistModule",
		"PriceDisplayModule",
		"ItemListModule",
		"TerminalSimulatorModule",
		"TerminalTimesModule",
		"FishingBobberTimerModule",
		"FishingHookDisplayModule",
		"FishingBaitModule",
		"SeaCreatureAlertModule",
		"SeaCreatureCapModule",
		"GeyserFishingModule",
		"HotspotRadarModule",
		"LavaReplacementModule",
		"MiningCommissionsModule",
		"MiningPowderModule",
		"ForagingPowderModule",
		"HotmTreeHelperModule",
		"HotfTreeHelperModule",
		"PowderChestModule",
		"MineshaftTimerModule",
		"CorpseFinderModule",
		"MetalDetectorModule",
		"GlaciteGuideModule",
		"GoblinHighlighterModule",
		"CrystalHollowsHelperModule",
		"MiningSolversModule",
		"MiningEventsModule",
		"HeatAndColdModule",
		"TabWidgetModule",
		"SpeedSimModule",
		"JumpBoostModule",
		"OneTapModule",
		"EtherwarpSimModule",
		"EtherwarpModule",
		"RotationEngineModule",
		"HumanRecorderModule",
		"PathfinderModule",
		"AreaKillerModule"
	);

	private RegisteredModuleCatalogChecks() { }

	public static void run() {
		checkSettingGroupRecordAbi();
		checkModuleActionRecordAbi();
		List<String> actual = ModuleManager.modules().stream()
			.map(module -> module.getClass().getSimpleName())
			.collect(Collectors.toList());
		if (!EXPECTED_MODULES.equals(actual)) {
			throw new AssertionError("Registered module catalog/order changed: expected "
				+ EXPECTED_MODULES + ", got " + actual);
		}
		checkEverySettingIsShown();
	}

	/** A module with settings but no shown group would give the Click GUI nothing to draw (an empty group() call did that). */
	private static void checkEverySettingIsShown() {
		List<String> hidden = new java.util.ArrayList<>();
		for (geiler.addons.client.module.Module module : ModuleManager.modules()) {
			java.util.Set<Object> shown = new java.util.HashSet<>();
			collect(module.groups(), shown);
			boolean hasSettings = !module.colorSettings().isEmpty() || !module.numberSettings().isEmpty()
				|| !module.booleanSettings().isEmpty() || !module.choiceSettings().isEmpty() || !module.textSettings().isEmpty();
			if (hasSettings && shown.isEmpty()) hidden.add(module.name());
		}
		if (!hidden.isEmpty()) {
			throw new AssertionError("Modules whose settings are all hidden by an empty group list: " + hidden);
		}
	}

	private static void collect(List<SettingGroup> groups, java.util.Set<Object> into) {
		for (SettingGroup group : groups) {
			into.addAll(group.settings());
			if (group.toggle() != null) into.add(group.toggle());
			collect(group.children(), into);
		}
	}

	private static void checkSettingGroupRecordAbi() {
		if (!SettingGroup.class.isRecord()) {
			throw new AssertionError("SettingGroup must retain its Java record ABI.");
		}
		String[] actual = java.util.Arrays.stream(SettingGroup.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new);
		String[] expected = {"name", "toggle", "collapsedByDefault", "settings", "children", "debugOnly", "stateKey"};
		if (!java.util.Arrays.equals(expected, actual)) {
			throw new AssertionError("SettingGroup record components/order changed: "
				+ java.util.Arrays.toString(actual));
		}
		SettingGroup group = new SettingGroup("Heading");
		if (!("SettingGroup[name=Heading, toggle=null, collapsedByDefault=false, settings=[], "
			+ "children=[], debugOnly=false, stateKey=null]").equals(group.toString())) {
			throw new AssertionError("SettingGroup record value formatting changed: " + group);
		}
	}

	private static void checkModuleActionRecordAbi() {
		if (!ModuleAction.class.isRecord()) {
			throw new AssertionError("ModuleAction must retain its Java record ABI.");
		}
		String[] actual = java.util.Arrays.stream(ModuleAction.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new);
		String[] expected = {"label", "description", "onClick"};
		if (!java.util.Arrays.equals(expected, actual)) {
			throw new AssertionError("ModuleAction record components/order changed: "
				+ java.util.Arrays.toString(actual));
		}
		Runnable callback = () -> { };
		ModuleAction action = new ModuleAction("Manage", "Open settings", callback);
		if (!"Manage".equals(action.label()) || !"Open settings".equals(action.description())
			|| action.onClick() != callback || !"Manage".equals(action.name())) {
			throw new AssertionError("ModuleAction accessors and Setting name remain stable.");
		}
		if (new ModuleAction("Simple", callback).description() != null) {
			throw new AssertionError("The short ModuleAction constructor keeps its null description.");
		}
	}
}
