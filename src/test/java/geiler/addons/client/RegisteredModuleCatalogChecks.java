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
		"I4HelperModule",
		"ExperimentSolverModule",
		"AutoExperimentsModule",
		"AutoKickModule",
		"PartyFinderStatsModule",
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
		"PestHighlighterModule",
		"GardenPlotBordersModule",
		"PestTrackerModule",
		"SlayerTrackerModule",
		"CropFeverTimerModule",
		"ChatChannelsModule",
		"ChatModule",
		"PartyCommandsModule",
		"HideAllParticlesModule",
		"ItemAnimationsModule",
		"LobbyTargetWatchModule",
		"PlayerInventoryControlsModule",
		"PlayerNamesModule",
		"PlayerSizeModule",
		"ScoreboardModule",
		"SystemClockHudModule",
		"ThirdPersonCameraModule",
		"TreeTrackerModule",
		"TreeNotifierModule",
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
		"NoFogModule",
		"FullbrightModule",
		"BlockOverlayModule",
		"ScrollableTooltipModule",
		"FreecamModule",
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
		"SpeedDisplayModule",
		"GhostVisionModule",
		"TerracottaTimerModule",
		"WardrobeKeybindsModule",
		"LoadoutKeybindsModule",
		"SpringBootsModule",
		"RagnarockModule",
		"TickTimersModule",
		"FireFreezeTimerModule",
		"RelicTimerModule",
		"WitherDragonsModule",
		"KingRelicModule",
		"DragonStackAimerModule",
		"CompactDamageModule",
		"TerminalSolverModule",
		"PriceDisplayModule",
		"TerminalSimulatorModule"
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
		List<String> withoutIcon = ModuleManager.modules().stream()
			.map(module -> module.name())
			.filter(name -> geiler.addons.client.gui.ModuleIconTextures.forModule(name) == null)
			.collect(Collectors.toList());
		if (!withoutIcon.isEmpty()) {
			throw new AssertionError("Every registered module needs a bundled icon; missing: " + withoutIcon);
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
