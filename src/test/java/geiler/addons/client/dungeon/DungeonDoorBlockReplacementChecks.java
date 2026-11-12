package geiler.addons.client.dungeon;

import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.impl.DungeonDoorBlockReplacementModule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;

/** Focused offline checks for door replacement defaults, picker controls, eligibility, and geometry. */
public final class DungeonDoorBlockReplacementChecks {
	private DungeonDoorBlockReplacementChecks() { }

	public static void main(String[] args) {
		rulesInteropAbi();
		DungeonDoorBlockOverlayControllerChecks.main(args);
		renderSnapshotInvalidation();
		startDoorMemoryLifecycle();
		pickerControls();
		materialEligibility();
		doorBounds();
		sourceVolumeCoverage();
		startGapDetectionRules();
		spawnStartDoorVolumeSelection();
		spawnStartForwardDistanceRange();
		startDoorAnchorCaptureRules();
		startSelectionLifecycle();
		retainedMaskLifecycle();
		dungeonSessionCleanup();
		ownershipAndFailureDiagnostics();
		levelIdentityLifecycle();
		detailedLogBudgetAndDeduplication();
		detectorRefreshLifecycle();
		entranceDoorClassification();
	}

	private static void renderSnapshotInvalidation() {
		var before = java.util.Map.of(1L, "stone", 2L, "glass");
		var after = java.util.Map.of(1L, "stone", 2L, "air", 3L, "stone");
		var changed = DungeonDoorRenderSnapshotRules.changedPositions(before, after);
		check(changed.equals(java.util.Set.of(2L, 3L)),
			"only added, removed, or changed render source positions are invalidated");
		check(DungeonDoorRenderSnapshotRules.changedPositions(null, null).isEmpty(),
			"null snapshots behave as empty snapshots");
		check(DungeonDoorRenderSnapshotRules.invalidationPositions(before, after, true).equals(changed),
			"same-level invalidation uses only the position diff");
		check(DungeonDoorRenderSnapshotRules.invalidationPositions(before, after, false)
			.equals(after.keySet()), "new levels invalidate every active replacement position");
		try {
			changed.clear();
			throw new AssertionError("render invalidation snapshots remain immutable");
		} catch (UnsupportedOperationException expected) {
			// The render caller receives an immutable invalidation set.
		}
	}

	private static void startDoorMemoryLifecycle() {
		var memory = new DungeonStartDoorBlockMemory<String>();
		memory.remember(1L, "first");
		memory.remember(2L, "second");
		memory.remember(1L, "refreshed");
		check(memory.snapshot().get(1L).equals("refreshed"), "remember refreshes a position's value");
		check(new java.util.ArrayList<>(memory.snapshot().keySet()).equals(java.util.List.of(2L, 1L)),
			"remember refreshes insertion order");
		memory.remember(3L, null);
		memory.observeAir(1L, false);
		check(memory.snapshot().containsKey(1L), "non-air observations retain remembered positions");
		memory.observeAir(1L, true);
		check(!memory.snapshot().containsKey(1L), "observed air retires a remembered position");
		try {
			memory.snapshot().clear();
			throw new AssertionError("start-door snapshots remain immutable");
		} catch (UnsupportedOperationException expected) {
			// Snapshot mutation must not reach the tracker.
		}

		for (long position = 0; position < 129; position++) memory.remember(position, "source-" + position);
		check(memory.snapshot().size() == 128 && !memory.snapshot().containsKey(0L)
			&& memory.snapshot().containsKey(128L), "remembered source positions remain capped at 128");
		memory.remember(1L, "refreshed");
		memory.remember(129L, "newest");
		check(memory.snapshot().size() == 128 && !memory.snapshot().containsKey(2L)
			&& memory.snapshot().containsKey(1L), "refreshing a position protects it from oldest-entry eviction");
		memory.clear();
		check(memory.snapshot().isEmpty(), "clear drops all remembered positions");
	}

	private static void rulesInteropAbi() {
		var rulesType = DungeonDoorBlockReplacementRules.class;
		check(java.lang.reflect.Modifier.isFinal(rulesType.getModifiers()),
			"the rule facade remains final");
		try {
			check(java.lang.reflect.Modifier.isPrivate(rulesType.getDeclaredConstructor().getModifiers()),
				"the rule facade retains its private constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the rule facade retains its no-argument constructor", exception);
		}

		Class<?> doorKind = DungeonDoorBlockReplacementRules.DoorKind.class;
		Class<?> surface = DungeonDoorBlockReplacementRules.Surface.class;
		Class<?> toggles = DungeonDoorBlockReplacementRules.Toggles.class;
		Class<?> glass = DungeonDoorBlockReplacementRules.Glass.class;
		Class<?> startScan = DungeonDoorBlockReplacementRules.StartDoorVolumeScan.class;
		Class<?> startDepth = DungeonDoorBlockReplacementRules.StartDoorDepthSlice.class;
		Class<?> spawnScan = DungeonDoorBlockReplacementRules.SpawnStartDoorVolumeScan.class;
		Class<?> doorScan = DungeonDoorBlockReplacementRules.DoorSourceVolumeScan.class;
		Class<?> doorDepth = DungeonDoorBlockReplacementRules.DoorDepthSlice.class;
		assertStaticMethod("replacement", glass, doorKind, surface, toggles);
		assertStaticMethod("replacement", glass, doorKind, surface, toggles, glass, glass, glass);
		assertStaticMethod("defaultReplacement", glass, doorKind);
		assertStaticMethod("glassChoices", String[].class);
		assertStaticMethod("glassDisplayValues", java.util.Map.class);
		assertStaticMethod("surface", surface, String.class);
		assertStaticMethod("isStartGapCandidate", boolean.class, boolean.class, startScan);
		assertStaticMethod("uniqueEntranceStartEdge", int.class, boolean.class, java.util.List.class);
		assertStaticMethod("startDoorVolumePositions", java.util.List.class, BlockPos.class, boolean.class);
		assertStaticMethod("spawnStartDoorVolumePositions", java.util.List.class, BlockPos.class, boolean.class);
		assertStaticMethod("scanSpawnStartDoorVolume", spawnScan, BlockPos.class, boolean.class,
			java.util.Set.class, java.util.Map.class);
		assertStaticMethod("scanStartDoorVolume", startScan, BlockPos.class, boolean.class,
			java.util.Set.class, java.util.Map.class);
		assertStaticMethod("doorSourceVolumePositions", java.util.List.class, BlockPos.class, boolean.class);
		assertStaticMethod("scanDoorSourceVolume", doorScan, BlockPos.class, boolean.class,
			java.util.Set.class, java.util.Map.class);
		assertStaticMethod("sourceDoorKind", doorKind, doorScan);
		assertStaticMethod("isStartSelectionSource", boolean.class, surface);
		assertStaticMethod("isMortIdentity", boolean.class, String.class);
		assertStaticMethod("contains", boolean.class, int.class, int.class, int.class, int.class, int.class,
			int.class, int.class, int.class, int.class);
		assertStaticMethod("touchesEntranceCell", boolean.class, int.class, int.class, int.class);

		assertValueTypeAbi(startDepth,
			new String[]{"depthOffset", "loadedCount", "materialCounts", "sourcePositions"},
			new Class<?>[]{int.class, int.class, java.util.Map.class, java.util.List.class});
		assertValueTypeAbi(startScan,
			new String[]{"complete", "loadedCount", "sourcePositions", "depthSlices"},
			new Class<?>[]{boolean.class, int.class, java.util.List.class, java.util.List.class});
		assertValueTypeAbi(spawnScan,
			new String[]{"complete", "loadedCount", "sourcePositions", "candidatePanels", "depthSlices"},
			new Class<?>[]{boolean.class, int.class, java.util.List.class, java.util.List.class, java.util.List.class});
		assertValueTypeAbi(doorDepth,
			new String[]{"depthOffset", "loadedCount", "materialCounts", "coalPositions", "redTerracottaPositions"},
			new Class<?>[]{int.class, int.class, java.util.Map.class, java.util.List.class, java.util.List.class});
		assertValueTypeAbi(doorScan,
			new String[]{"complete", "loadedCount", "coalPositions", "redTerracottaPositions", "depthSlices"},
			new Class<?>[]{boolean.class, int.class, java.util.List.class, java.util.List.class, java.util.List.class});
		assertRecordAbi(DungeonDoorBlockReplacementRules.DoorStageCounts.class,
			new String[]{"detectedDoors", "eligibleSourceBlocks", "publishedReplacements"},
			new Class<?>[]{int.class, int.class, int.class});
		assertRecordAbi(toggles,
			new String[]{"start", "wither", "blood"},
			new Class<?>[]{boolean.class, boolean.class, boolean.class});
		try {
			var fromBlockPath = glass.getDeclaredMethod("fromBlockPath", String.class, glass);
			check(java.lang.reflect.Modifier.isPublic(fromBlockPath.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(fromBlockPath.getModifiers())
				&& fromBlockPath.getReturnType() == glass,
				"the glass enum retains its static block-path lookup signature");
			check(java.lang.reflect.Modifier.isPublic(glass.getDeclaredMethod("blockPath").getModifiers())
				&& glass.getDeclaredMethod("blockPath").getReturnType() == String.class
				&& java.lang.reflect.Modifier.isPublic(glass.getDeclaredMethod("displayName").getModifiers())
				&& glass.getDeclaredMethod("displayName").getReturnType() == String.class,
				"the glass enum retains its Java block-path and display-name accessors");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("the glass enum retains its Java-callable helpers", exception);
		}

		var startDepthDefaults = new DungeonDoorBlockReplacementRules.StartDoorDepthSlice(0, 0, null, null);
		check(startDepthDefaults.materialCounts().isEmpty() && startDepthDefaults.sourcePositions().isEmpty(),
			"Start depth values preserve null-to-empty collection normalization");
		var startScanDefaults = new DungeonDoorBlockReplacementRules.StartDoorVolumeScan(false, 0, null, null);
		check(startScanDefaults.sourcePositions().isEmpty() && startScanDefaults.depthSlices().isEmpty(),
			"Start scan values preserve null-to-empty collection normalization");
		var spawnScanDefaults =
			new DungeonDoorBlockReplacementRules.SpawnStartDoorVolumeScan(false, 0, null, null, null);
		check(spawnScanDefaults.sourcePositions().isEmpty() && spawnScanDefaults.candidatePanels().isEmpty()
			&& spawnScanDefaults.depthSlices().isEmpty(),
			"spawn scan values preserve null-to-empty collection normalization");
		var doorDepthDefaults = new DungeonDoorBlockReplacementRules.DoorDepthSlice(0, 0, null, null, null);
		check(doorDepthDefaults.materialCounts().isEmpty() && doorDepthDefaults.coalPositions().isEmpty()
			&& doorDepthDefaults.redTerracottaPositions().isEmpty(),
			"door depth values preserve null-to-empty collection normalization");
		var doorScanDefaults =
			new DungeonDoorBlockReplacementRules.DoorSourceVolumeScan(false, 0, null, null, null);
		check(doorScanDefaults.coalPositions().isEmpty() && doorScanDefaults.redTerracottaPositions().isEmpty()
			&& doorScanDefaults.depthSlices().isEmpty(),
			"door scan values preserve null-to-empty collection normalization");

		var mutableMaterials = new java.util.HashMap<String, Integer>();
		mutableMaterials.put("minecraft:z", 2);
		mutableMaterials.put("minecraft:a", 1);
		var mutableSources = new java.util.ArrayList<BlockPos>();
		var source = new BlockPos(1, 2, 3);
		mutableSources.add(source);
		var copied = new DungeonDoorBlockReplacementRules.StartDoorDepthSlice(
			0, 1, mutableMaterials, mutableSources);
		mutableMaterials.clear();
		mutableSources.clear();
		equals(java.util.List.of(source), copied.sourcePositions(),
			"value constructors retain defensive source-list copies");
		equals(java.util.List.of("minecraft:a", "minecraft:z"),
			new java.util.ArrayList<>(copied.materialCounts().keySet()),
			"value constructors retain sorted defensive material-map copies");
		var expectedCopied = new DungeonDoorBlockReplacementRules.StartDoorDepthSlice(
			0, 1, java.util.Map.of("minecraft:a", 1, "minecraft:z", 2), java.util.List.of(source));
		equals(expectedCopied, copied, "Kotlin value classes retain structural equality after input mutation");
		equals(expectedCopied.hashCode(), copied.hashCode(), "equal Kotlin value classes retain equal hash codes");
		equals("StartDoorDepthSlice[depthOffset=0, loadedCount=1, materialCounts={minecraft:a=1, minecraft:z=2}, "
			+ "sourcePositions=[" + source + "]]", copied.toString(),
			"Kotlin depth-slice values preserve the previous record-style diagnostic representation");
		boolean sourceListReadOnly = false;
		try {
			copied.sourcePositions().add(source);
		} catch (UnsupportedOperationException expected) {
			sourceListReadOnly = true;
		}
		check(sourceListReadOnly, "value source lists remain unmodifiable");
		boolean materialMapReadOnly = false;
		try {
			copied.materialCounts().put("minecraft:b", 3);
		} catch (UnsupportedOperationException expected) {
			materialMapReadOnly = true;
		}
		check(materialMapReadOnly, "value material maps remain unmodifiable");

		var mutablePanel = new java.util.ArrayList<BlockPos>();
		mutablePanel.add(source);
		var mutablePanels = new java.util.ArrayList<java.util.List<BlockPos>>();
		mutablePanels.add(mutablePanel);
		var copiedPanels = new DungeonDoorBlockReplacementRules.SpawnStartDoorVolumeScan(
			true, 1, java.util.List.of(source), mutablePanels, java.util.List.of());
		mutablePanel.clear();
		mutablePanels.clear();
		equals(java.util.List.of(java.util.List.of(source)), copiedPanels.candidatePanels(),
			"spawn scan values retain defensive copies of nested panels");
		boolean panelReadOnly = false;
		try {
			copiedPanels.candidatePanels().getFirst().add(source);
		} catch (UnsupportedOperationException expected) {
			panelReadOnly = true;
		}
		check(panelReadOnly, "nested spawn panel lists remain unmodifiable");
		equals(copiedPanels.candidatePanels().getFirst(), copiedPanels.uniquePanelPositions(),
			"a unique selected panel preserves the immutable component list and its order");
		boolean selectedPanelReadOnly = false;
		try {
			copiedPanels.uniquePanelPositions().add(source);
		} catch (UnsupportedOperationException expected) {
			selectedPanelReadOnly = true;
		}
		check(selectedPanelReadOnly, "unique panel results remain unmodifiable");
	}

	private static void assertStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			var method = DungeonDoorBlockReplacementRules.class.getDeclaredMethod(name, parameterTypes);
			check(java.lang.reflect.Modifier.isPublic(method.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(method.getModifiers())
				&& method.getReturnType() == returnType,
				"the static Java rule ABI remains available for " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("missing static Java rule ABI method " + name, exception);
		}
	}

	private static void assertRecordAbi(Class<?> type, String[] names, Class<?>[] componentTypes) {
		check(type.isRecord(), type.getSimpleName() + " remains a Java record");
		var components = type.getRecordComponents();
		check(components != null && components.length == names.length && names.length == componentTypes.length,
			type.getSimpleName() + " retains its record component count");
		for (int index = 0; index < names.length; index++) {
			equals(names[index], components[index].getName(),
				type.getSimpleName() + " retains record component " + index);
			equals(componentTypes[index], components[index].getType(),
				type.getSimpleName() + " retains component type " + names[index]);
		}
		try {
			check(java.lang.reflect.Modifier.isPublic(type.getConstructor(componentTypes).getModifiers()),
				type.getSimpleName() + " retains its public canonical constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " is missing its public canonical constructor", exception);
		}
	}

	private static void assertValueTypeAbi(Class<?> type, String[] names, Class<?>[] componentTypes) {
		check(java.lang.reflect.Modifier.isFinal(type.getModifiers()),
			type.getSimpleName() + " remains an immutable final value type");
		check(names.length == componentTypes.length,
			type.getSimpleName() + " value accessors have matching component declarations");
		try {
			check(java.lang.reflect.Modifier.isPublic(type.getConstructor(componentTypes).getModifiers()),
				type.getSimpleName() + " retains its public component constructor");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError(type.getSimpleName() + " is missing its public component constructor", exception);
		}
		for (int index = 0; index < names.length; index++) {
			try {
				var accessor = type.getDeclaredMethod(names[index]);
				check(java.lang.reflect.Modifier.isPublic(accessor.getModifiers())
					&& accessor.getReturnType() == componentTypes[index],
					type.getSimpleName() + " retains Java component accessor " + names[index]);
			} catch (NoSuchMethodException exception) {
				throw new AssertionError(type.getSimpleName() + " is missing component accessor " + names[index],
					exception);
			}
		}
	}

	private static void pickerControls() {
		var module = DungeonDoorBlockReplacementModule.INSTANCE;
		equals("Dungeon Door Block Glass", module.configName(),
			"Glass Doors keeps its existing persisted config key");
		equals("glass", replacementSetting(module, "Start Door Replacement").value(),
			"the Start picker keeps its clear-glass default");
		equals("black_stained_glass", replacementSetting(module, "Wither Door Replacement").value(),
			"the Wither picker keeps its black-stained-glass default");
		equals("red_stained_glass", replacementSetting(module, "Blood Door Replacement").value(),
			"the Blood picker keeps its red-stained-glass default");
		check(!module.defaultEnabled(), "Glass Doors remains off in a fresh configuration");
		check(module.booleanSettings().stream().anyMatch(setting ->
			setting.name().equals("Use Mort-Based Start Detection") && !setting.value()),
			"the retired Mort key remains registered with its old default for saved-config compatibility");

		SettingGroup doorTypes = module.groups().stream().filter(group -> "Door Types".equals(group.name()))
			.findFirst().orElse(null);
		check(doorTypes != null && doorTypes.settings().size() == 3
			&& doorTypes.settings().stream().allMatch(BooleanSetting.class::isInstance),
			"Start, Wither, and Blood replacement toggles remain independent door-type settings");
		check(module.groups().stream().noneMatch(group -> group.settings().stream().anyMatch(setting ->
			setting.name().equals("Use Mort-Based Start Detection"))),
			"the retired Mort key is inert and hidden from active settings");
		for (String name : java.util.List.of("Start Door Glass", "Wither Door Glass", "Blood Door Glass")) {
			check(module.booleanSettings().stream().anyMatch(setting -> setting.name().equals(name) && setting.value()),
				name + " remains enabled by default");
		}

		SettingGroup replacementBlocks = module.groups().stream()
			.filter(group -> "Replacement Blocks".equals(group.name())).findFirst().orElse(null);
		check(replacementBlocks != null, "replacement choices appear in their own settings group");
		if (replacementBlocks == null) return;
		var controls = replacementBlocks.settings().stream().filter(ModuleAction.class::isInstance)
			.map(ModuleAction.class::cast).toList();
		equals(java.util.List.of(
			"Start Door Replacement: minecraft:glass",
			"Wither Door Replacement: minecraft:black_stained_glass",
			"Blood Door Replacement: minecraft:red_stained_glass"),
			controls.stream().map(ModuleAction::label).toList(),
			"each replacement is exposed as a searchable block-picker control with its current ID");
		check(controls.size() == 3 && controls.stream().allMatch(action -> action.description() != null
			&& action.description().contains("registered block") && action.onClick() != null),
			"each per-door picker has registry-selection guidance and a click action");
		check(replacementBlocks.settings().stream().noneMatch(ChoiceSetting.class::isInstance),
			"the old inline choice cycling is hidden behind the reusable picker controls");
		for (String name : java.util.List.of("Start Door Replacement", "Wither Door Replacement", "Blood Door Replacement")) {
			check(replacementSetting(module, name).choices().contains("minecraft:stone"),
				name + " accepts the same registered block IDs as the shared picker");
		}
	}

	private static ChoiceSetting replacementSetting(DungeonDoorBlockReplacementModule module, String name) {
		return module.choiceSettings().stream().filter(setting -> setting.name().equals(name)).findFirst()
			.orElseThrow(() -> new AssertionError("Missing replacement setting: " + name));
	}

	private static void materialEligibility() {
		var allEnabled = new DungeonDoorBlockReplacementRules.Toggles(true, true, true);
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.surface("coal_block"), allEnabled),
			"Start search rejects coal as an initial source");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.surface("orange_terracotta"), allEnabled),
			"Start search rejects ordinary terracotta as an initial source");
		equals(DungeonDoorBlockReplacementRules.Glass.BLACK,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
				DungeonDoorBlockReplacementRules.surface("coal_block"), allEnabled),
			"Wither coal renders as black glass");
		equals(DungeonDoorBlockReplacementRules.Glass.RED,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
				DungeonDoorBlockReplacementRules.surface("red_terracotta"), allEnabled),
			"Blood red terracotta renders as red glass");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.surface("red_terracotta"), allEnabled),
			"Wither box does not recolor a non-coal block");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
			DungeonDoorBlockReplacementRules.surface("orange_terracotta"), allEnabled),
			"Blood box does not recolor a non-red terracotta block");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.NORMAL,
			DungeonDoorBlockReplacementRules.surface("coal_block"), allEnabled),
			"ordinary door ESP boxes do not turn normal door blocks into glass");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.GLASS,
			DungeonDoorBlockReplacementRules.surface("white_terracotta"), allEnabled),
			"already-glass doors are excluded from the separate glass substitution module");
		equals(DungeonDoorBlockReplacementRules.Glass.CLEAR,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
				DungeonDoorBlockReplacementRules.surface("chiseled_stone_bricks"), allEnabled),
			"the selected Chiseled Stone Bricks Start source renders as its existing clear-glass default");
		equals(DungeonDoorBlockReplacementRules.Glass.CLEAR,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
				DungeonDoorBlockReplacementRules.surface("infested_chiseled_stone_bricks"), allEnabled),
			"the visually identical Infested Chiseled Stone Bricks Start source is also selected");
		check(DungeonDoorBlockReplacementRules.isStartSelectionSource(
			DungeonDoorBlockReplacementRules.surface("chiseled_stone_bricks"))
			&& DungeonDoorBlockReplacementRules.isStartSelectionSource(
				DungeonDoorBlockReplacementRules.surface("infested_chiseled_stone_bricks")),
			"both supported initial Start materials are classified by their exact registry IDs");
		check(!DungeonDoorBlockReplacementRules.isStartSelectionSource(
			DungeonDoorBlockReplacementRules.surface("stone"))
			&& !DungeonDoorBlockReplacementRules.isStartSelectionSource(
				DungeonDoorBlockReplacementRules.surface("stone_brick_wall")),
			"ordinary blocks and walls are excluded from initial Start selection");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.surface("chiseled_stone_bricks"), allEnabled),
			"Chiseled Stone Bricks are not eligible in Wither regions");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
			DungeonDoorBlockReplacementRules.surface("chiseled_stone_bricks"), allEnabled),
			"Chiseled Stone Bricks are not eligible in Blood regions");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.surface("red_glazed_terracotta"), allEnabled),
			"glazed terracotta is not eligible");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.surface("coal_ore"), allEnabled),
			"coal ore is not eligible");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
			DungeonDoorBlockReplacementRules.surface("red_terracotta"),
			new DungeonDoorBlockReplacementRules.Toggles(true, true, false)),
			"Blood toggle independently disables replacement");
		isNull(DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.surface("coal_block"),
			new DungeonDoorBlockReplacementRules.Toggles(false, true, true)),
			"Start toggle independently disables replacement");
		equals(17, DungeonDoorBlockReplacementRules.glassChoices().length,
			"the legacy glass list still contains the 17 vanilla clear and stained glass variants");
		equals("glass", DungeonDoorBlockReplacementRules.defaultReplacement(
			DungeonDoorBlockReplacementRules.DoorKind.START).blockPath(),
			"Start replacement defaults to clear glass");
		equals("black_stained_glass", DungeonDoorBlockReplacementRules.defaultReplacement(
			DungeonDoorBlockReplacementRules.DoorKind.WITHER).blockPath(),
			"Wither replacement defaults to black stained glass");
		equals("red_stained_glass", DungeonDoorBlockReplacementRules.defaultReplacement(
			DungeonDoorBlockReplacementRules.DoorKind.BLOOD).blockPath(),
			"Blood replacement defaults to red stained glass");
		check(java.util.Arrays.stream(DungeonDoorBlockReplacementRules.glassChoices())
			.allMatch(path -> path.equals("glass") || path.endsWith("_stained_glass")),
			"replacement choices exclude non-glass blocks");
		var custom = new DungeonDoorBlockReplacementRules.Toggles(true, true, true);
		equals(DungeonDoorBlockReplacementRules.Glass.WHITE,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.START,
			DungeonDoorBlockReplacementRules.Surface.CHISELED_STONE_BRICKS, custom,
				DungeonDoorBlockReplacementRules.Glass.WHITE, DungeonDoorBlockReplacementRules.Glass.GREEN,
				DungeonDoorBlockReplacementRules.Glass.BLUE),
		"Start door replacement follows its independently selected glass block");
		equals(DungeonDoorBlockReplacementRules.Glass.GREEN,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
				DungeonDoorBlockReplacementRules.Surface.COAL_BLOCK, custom,
				DungeonDoorBlockReplacementRules.Glass.WHITE, DungeonDoorBlockReplacementRules.Glass.GREEN,
				DungeonDoorBlockReplacementRules.Glass.BLUE),
			"Wither door replacement follows its independently selected glass block");
		equals(DungeonDoorBlockReplacementRules.Glass.BLUE,
			DungeonDoorBlockReplacementRules.replacement(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
				DungeonDoorBlockReplacementRules.Surface.RED_TERRACOTTA, custom,
				DungeonDoorBlockReplacementRules.Glass.WHITE, DungeonDoorBlockReplacementRules.Glass.GREEN,
				DungeonDoorBlockReplacementRules.Glass.BLUE),
			"Blood door replacement follows its independently selected glass block");
		equals(DungeonDoorBlockReplacementRules.Glass.BLACK,
			DungeonDoorBlockReplacementRules.Glass.fromBlockPath("black_stained_glass", null),
			"the existing Wither default maps to the stable black-glass config value");
		equals(DungeonDoorBlockReplacementRules.Glass.RED,
			DungeonDoorBlockReplacementRules.Glass.fromBlockPath("red_stained_glass", null),
			"the existing Blood default maps to the stable red-glass config value");
	}

	private static void doorBounds() {
		check(DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, 10, 20, 30), "minimum corner is inside");
		check(DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, 12, 23, 32), "maximum included block is inside");
		check(!DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, 13, 22, 31), "max X edge is exclusive");
		check(!DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, 11, 24, 31), "max Y edge is exclusive");
		check(!DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, 11, 22, 33), "max Z edge is exclusive");
		check(!DungeonDoorBlockReplacementRules.contains(10, 20, 30, 0, 4, 3, 10, 20, 30), "zero-sized box contains nothing");
		check(!DungeonDoorBlockReplacementRules.contains(10, 20, 30, -1, 4, 3, 10, 20, 30), "negative-sized box contains nothing");
		check(DungeonDoorBlockReplacementRules.contains(Integer.MAX_VALUE - 1, 0, 0, 4, 1, 1,
			Integer.MAX_VALUE, 0, 0), "large positive bounds do not overflow");
		check(!DungeonDoorBlockReplacementRules.contains(Integer.MAX_VALUE - 1, 0, 0, 4, 1, 1,
			Integer.MIN_VALUE, 0, 0), "large positive bounds do not wrap around to negative coordinates");
		int covered = 0;
		for (int x = 10; x < 13; x++) for (int y = 20; y < 24; y++) for (int z = 30; z < 33; z++) {
			if (DungeonDoorBlockReplacementRules.contains(10, 20, 30, 3, 4, 3, x, y, z)) covered++;
		}
		equals(36, covered, "the render box covers every block in the full 3 by 4 by 3 door volume");
		BlockPos center = new BlockPos(-154, 69, -42);
		var alongX = DungeonDoorBlockReplacementRules.startDoorVolumePositions(center, true);
		var alongZ = DungeonDoorBlockReplacementRules.startDoorVolumePositions(center, false);
		equals(48, alongX.size(), "the X-oriented Start volume contains 4 wide × 4 high × 3 deep positions");
		equals(48L, alongX.stream().map(BlockPos::asLong).distinct().count(),
			"the X-oriented Start volume contains no duplicate positions");
		equals(new BlockPos(-155, 69, -43), alongX.stream().min(java.util.Comparator.<BlockPos>
			comparingInt(position -> position.getX()).thenComparingInt(position -> position.getY())
			.thenComparingInt(position -> position.getZ())).orElseThrow(),
			"the X-oriented volume offsets its plane by three depth and four tangent blocks");
		check(alongX.contains(new BlockPos(-153, 72, -40)) && alongX.contains(new BlockPos(-155, 69, -43)),
			"the X-oriented volume covers its inclusive bounds");
		check(alongZ.contains(new BlockPos(-155, 69, -43)) && alongZ.contains(new BlockPos(-152, 72, -41)),
			"the perpendicular Z-oriented volume uses the same 4×4×3 transform");
	}

	private static void sourceVolumeCoverage() {
		BlockPos center = new BlockPos(-170, 69, -186);
		var alongX = DungeonDoorBlockReplacementRules.doorSourceVolumePositions(center, true);
		equals(36, alongX.size(), "the source scan covers all 36 blocks in the existing door bounds");
		equals(36L, alongX.stream().map(BlockPos::asLong).distinct().count(),
			"the source scan visits each bounded block once");
		for (int depth = -1; depth <= 1; depth++) {
			int slice = depth + 1;
			int expectedX = center.getX() + depth;
			var slicePositions = alongX.subList(slice * 12, slice * 12 + 12);
			check(slicePositions.stream().allMatch(position -> position.getX() == expectedX),
				"an X-oriented door's depth slice stays on its corresponding X plane");
		}
		var alongZ = DungeonDoorBlockReplacementRules.doorSourceVolumePositions(center, false);
		equals(36, alongZ.size(), "the perpendicular doorway orientation also covers all 36 bounded blocks");
		for (int depth = -1; depth <= 1; depth++) {
			int slice = depth + 1;
			int expectedZ = center.getZ() + depth;
			var slicePositions = alongZ.subList(slice * 12, slice * 12 + 12);
			check(slicePositions.stream().allMatch(position -> position.getZ() == expectedZ),
				"a Z-oriented door's depth slice stays on its corresponding Z plane");
		}

		var loaded = new java.util.HashSet<Long>();
		var materials = new java.util.HashMap<Long, String>();
		for (BlockPos position : alongX) {
			loaded.add(position.asLong());
			materials.put(position.asLong(), "minecraft:air");
		}
		BlockPos coalOuter = alongX.get(0);
		BlockPos coalMiddle = alongX.get(16);
		BlockPos redMiddle = alongX.get(17);
		BlockPos redInner = alongX.get(35);
		BlockPos otherOuter = alongX.get(2);
		materials.put(coalOuter.asLong(), "minecraft:coal_block");
		materials.put(coalMiddle.asLong(), "minecraft:coal_block");
		materials.put(redMiddle.asLong(), "minecraft:red_terracotta");
		materials.put(redInner.asLong(), "minecraft:red_terracotta");
		materials.put(otherOuter.asLong(), "minecraft:stone");
		var scan = DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, true, loaded, materials);
		check(scan.complete(), "all loaded door positions produce a complete source scan");
		equals(java.util.List.of(coalOuter, coalMiddle), scan.coalPositions(),
			"every coal source is selected across the outer and middle depth slices");
		equals(java.util.List.of(redMiddle, redInner), scan.redTerracottaPositions(),
			"every Blood source is selected separately across the middle and inner depth slices");
		equals(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.sourceDoorKind(scan),
			"the existing Wither-before-Blood classification remains stable when both materials occur");
		equals(java.util.Map.of("minecraft:coal_block", 1, "minecraft:stone", 1, "minecraft:air", 10),
			scan.depthSlices().get(0).materialCounts(), "outer slice diagnostics count each observed material");
		equals(java.util.Map.of("minecraft:coal_block", 1, "minecraft:red_terracotta", 1, "minecraft:air", 10),
			scan.depthSlices().get(1).materialCounts(), "middle slice diagnostics keep coal and Blood counts distinct");
		equals(java.util.Map.of("minecraft:red_terracotta", 1, "minecraft:air", 11),
			scan.depthSlices().get(2).materialCounts(), "inner slice diagnostics count all 12 positions");
		check(scan.diagnosticSummary().contains("depth-1") && scan.diagnosticSummary().contains("depth0")
			&& scan.diagnosticSummary().contains("depth1")
			&& scan.diagnosticSummary().contains(coalOuter.toShortString()),
			"automatic source diagnostics include each depth and selected source positions");

		var outerOnlyMaterials = new java.util.HashMap<>(materials);
		outerOnlyMaterials.replaceAll((packed, id) -> "minecraft:air");
		outerOnlyMaterials.put(coalOuter.asLong(), "minecraft:coal_block");
		var outerOnly = DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, true, loaded,
			outerOnlyMaterials);
		isNull(DungeonDoorBlockReplacementRules.sourceDoorKind(outerOnly),
			"a Wither source in an outer slice alone cannot classify the door");
		equals(java.util.List.of(coalOuter), outerOnly.coalPositions(),
			"outer-slice coal is still discovered for the full-volume source mask");
		outerOnlyMaterials.put(coalMiddle.asLong(), "minecraft:coal_block");
		var centrallyClassified = DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, true, loaded,
			outerOnlyMaterials);
		equals(DungeonDoorBlockReplacementRules.DoorKind.WITHER,
			DungeonDoorBlockReplacementRules.sourceDoorKind(centrallyClassified),
			"a central-plane Wither source independently classifies the door");
		equals(java.util.List.of(coalOuter, coalMiddle), centrallyClassified.coalPositions(),
			"once classified centrally, the Wither source mask also includes the outer slice");

		var bloodOnlyMaterials = new java.util.HashMap<>(materials);
		bloodOnlyMaterials.replaceAll((packed, id) -> id.equals("minecraft:coal_block") ? "minecraft:air" : id);
		var bloodOnly = DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, true, loaded,
			bloodOnlyMaterials);
		equals(DungeonDoorBlockReplacementRules.DoorKind.BLOOD,
			DungeonDoorBlockReplacementRules.sourceDoorKind(bloodOnly),
			"Blood classification selects red terracotta without admitting coal sources");
		check(bloodOnly.coalPositions().isEmpty() && bloodOnly.redTerracottaPositions().size() == 2,
			"source lists remain separated by exact material type");

		var incompleteLoaded = new java.util.HashSet<>(loaded);
		incompleteLoaded.remove(coalOuter.asLong());
		var incomplete = DungeonDoorBlockReplacementRules.scanDoorSourceVolume(center, true,
			incompleteLoaded, materials);
		check(!incomplete.complete(), "one unloaded position prevents a complete 3×4×3 source scan");
		equals(35, incomplete.loadedCount(), "unloaded scan diagnostics report the exact available count");
		check(incomplete.coalPositions().isEmpty()
			&& DungeonDoorBlockReplacementRules.sourceDoorKind(incomplete) == null,
			"an incomplete volume never selects a partial source mask");
	}

	private static void entranceDoorClassification() {
		check(DungeonDoorBlockReplacementRules.touchesEntranceCell(7, 7, 8),
			"the Entrance cell is adjacent to its neighboring map cell");
		check(DungeonDoorBlockReplacementRules.touchesEntranceCell(8, 7, 8),
			"Entrance adjacency is checked from either side of a map edge");
		check(!DungeonDoorBlockReplacementRules.touchesEntranceCell(7, 8, 9),
			"a non-Entrance edge is excluded from Start gap selection");
		check(!DungeonDoorBlockReplacementRules.touchesEntranceCell(-1, 7, 8),
			"missing entrance evidence cannot promote a door to start");
		equals(17, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(true, java.util.List.of(17)),
			"one matching fully sampled gap adjacent to Entrance is uniquely selected");
		equals(-1, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(true, java.util.List.of()),
			"no matching gap means there is no Start selection");
		equals(-1, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(true, java.util.List.of(17, 18)),
			"multiple matching adjacent gaps remain ambiguous and are not selected");
		equals(-1, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(false, java.util.List.of(17)),
			"any unloaded adjacent gap prevents a unique-looking candidate selection");
	}

	private static void startGapDetectionRules() {
		check(DungeonDoorBlockReplacementRules.isMortIdentity("\u00a7cMort")
			&& DungeonDoorBlockReplacementRules.isMortIdentity("Mo\u200brt"),
			"diagnostic Mort identity normalization remains available");
		check(!DungeonDoorBlockReplacementRules.isMortIdentity("Mort the NPC")
			&& !DungeonDoorBlockReplacementRules.isMortIdentity("morty"),
			"diagnostic Mort matching requires an exact normalized entity name");

		for (boolean alongX : new boolean[]{true, false}) {
			BlockPos center = new BlockPos(-120, 69, -80);
			var positions = DungeonDoorBlockReplacementRules.startDoorVolumePositions(center, alongX);
			var loaded = new java.util.HashSet<Long>();
			var materials = new java.util.HashMap<Long, String>();
			for (BlockPos position : positions) {
				loaded.add(position.asLong());
				materials.put(position.asLong(), "minecraft:stone");
			}
			BlockPos normal = positions.getFirst();
			BlockPos infested = positions.getLast();
			materials.put(normal.asLong(), "minecraft:chiseled_stone_bricks");
			materials.put(infested.asLong(), "minecraft:infested_chiseled_stone_bricks");
			var scan = DungeonDoorBlockReplacementRules.scanStartDoorVolume(center, alongX, loaded, materials);
			check(scan.complete(), "both orientations require all 48 loaded positions");
			check(DungeonDoorBlockReplacementRules.isStartGapCandidate(true, scan),
				"a loaded Entrance-adjacent volume with at least one supported source qualifies");
			check(!DungeonDoorBlockReplacementRules.isStartGapCandidate(false, scan),
				"a matching volume away from Entrance is never selected");
			equals(java.util.List.of(normal, infested), scan.sourcePositions(),
				"only regular and infested chiseled blocks are selected at either orientation's extremes");
			check(scan.diagnosticSummary().contains("depth-1") && scan.diagnosticSummary().contains("depth1")
				&& scan.diagnosticSummary().contains(normal.toShortString()),
				"Start scan diagnostics report per-depth material counts and selected coordinates");

			var oneUnloaded = new java.util.HashSet<>(loaded);
			oneUnloaded.remove(positions.get(10).asLong());
			var incomplete = DungeonDoorBlockReplacementRules.scanStartDoorVolume(center, alongX, oneUnloaded, materials);
			check(!incomplete.complete() && incomplete.loadedCount() == 47
				&& !DungeonDoorBlockReplacementRules.isStartGapCandidate(true, incomplete),
				"an unloaded position prevents Start selection but retains diagnostic source observations");
		}

		BlockPos center = new BlockPos(-120, 69, -80);
		var positions = DungeonDoorBlockReplacementRules.startDoorVolumePositions(center, true);
		var loaded = positions.stream().map(BlockPos::asLong).collect(java.util.stream.Collectors.toSet());
		var otherMaterials = new java.util.HashMap<Long, String>();
		for (BlockPos position : positions) otherMaterials.put(position.asLong(), "minecraft:stone");
		var unrelated = DungeonDoorBlockReplacementRules.scanStartDoorVolume(center, true, loaded, otherMaterials);
		check(!DungeonDoorBlockReplacementRules.isStartGapCandidate(true, unrelated),
			"a complete volume without chiseled source blocks is rejected");
		var oneSource = new java.util.HashMap<>(otherMaterials);
		oneSource.put(positions.get(20).asLong(), "minecraft:chiseled_stone_bricks");
		check(DungeonDoorBlockReplacementRules.isStartGapCandidate(true,
			DungeonDoorBlockReplacementRules.scanStartDoorVolume(center, true, loaded, oneSource)),
			"one chiseled block suffices as the specified source-material threshold");
		equals(21, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(true, java.util.List.of(21)),
			"a single supported adjacent gap is selected");
		equals(-1, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(true, java.util.List.of(21, 22)),
			"two supported adjacent gaps are rejected as ambiguous");
		equals(-1, DungeonDoorBlockReplacementRules.uniqueEntranceStartEdge(false, java.util.List.of(21)),
			"unloaded adjacent gaps make the scan unresolved");
	}

	private static void startDoorAnchorCaptureRules() {
		var boundsComponents = StartDoorAnchorCaptureRules.Bounds.class.getRecordComponents();
		check(boundsComponents.length == 6
			&& boundsComponents[0].getName().equals("minX")
			&& boundsComponents[5].getName().equals("maxZ"),
			"inclusive capture bounds retain their Java record components");
		check(!StartDoorAnchorCaptureRules.GridRelation.class.isRecord(),
			"grid relation keeps its normalizing constructor and Java-style accessors in Kotlin");
		try {
			StartDoorAnchorCaptureRules.class.getMethod("bounds", BlockPos.class, BlockPos.class);
			StartDoorAnchorCaptureRules.class.getMethod("supportedSource", String.class);
			StartDoorAnchorCaptureRules.class.getMethod("sameSession", Object.class, long.class, String.class, int.class,
				Object.class, long.class, String.class, int.class);
			StartDoorAnchorCaptureRules.class.getMethod("gridRelation", StartDoorAnchorCaptureRules.Bounds.class,
				int.class, int.class);
			StartDoorAnchorCaptureRules.GridRelation.class.getMethod("summary");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Start-door capture Java API changed", error);
		}
		BlockPos first = new BlockPos(-154, 72, -39);
		BlockPos second = new BlockPos(-151, 69, -42);
		var bounds = StartDoorAnchorCaptureRules.bounds(first, second);
		check(bounds != null, "opposite captured corners create inclusive bounds");
		if (bounds != null) {
			equals(-154, bounds.minX(), "captured bounds normalize either corner order on X");
			equals(72, bounds.maxY(), "captured bounds normalize either corner order on Y");
			equals(4, bounds.width(), "capture bounds include both X corners");
			equals(4, bounds.height(), "capture bounds include both Y corners");
			equals(4, bounds.depth(), "capture bounds include both Z corners");
		}
		check(StartDoorAnchorCaptureRules.bounds(first, first) == null,
			"a repeated identical target point is rejected");
		check(StartDoorAnchorCaptureRules.bounds(null, second) == null,
			"a missing captured corner is rejected");
		check(StartDoorAnchorCaptureRules.supportedSource("minecraft:chiseled_stone_bricks")
			&& StartDoorAnchorCaptureRules.supportedSource("minecraft:infested_chiseled_stone_bricks"),
			"normal and infested chiseled blocks are supported capture sources");
		check(!StartDoorAnchorCaptureRules.supportedSource("minecraft:glass")
			&& !StartDoorAnchorCaptureRules.supportedSource("minecraft:air")
			&& !StartDoorAnchorCaptureRules.supportedSource("example:chiseled_stone_bricks")
			&& !StartDoorAnchorCaptureRules.supportedSource(null),
			"glass, air, and non-vanilla source IDs are rejected");

		Object level = new Object();
		check(StartDoorAnchorCaptureRules.sameSession(level, 4, "91", 7, level, 4, "91", 7),
			"capture corners share one level, run, map, and Entrance cell");
		check(!StartDoorAnchorCaptureRules.sameSession(level, 4, "91", 7, new Object(), 4, "91", 7)
			&& !StartDoorAnchorCaptureRules.sameSession(level, 4, "91", 7, level, 5, "91", 7)
			&& !StartDoorAnchorCaptureRules.sameSession(level, 4, "91", 7, level, 4, "92", 7)
			&& !StartDoorAnchorCaptureRules.sameSession(level, 4, "91", 7, level, 4, "91", 8),
			"capture rejects a changed world, run generation, map ID, or Entrance cell");

		var positiveX = StartDoorAnchorCaptureRules.gridRelation(
			new StartDoorAnchorCaptureRules.Bounds(30, 69, 14, 32, 72, 17), 0, 0);
		equals("gap-axis-X", positiveX.orientation(), "map grid reports an X-oriented Entrance gap");
		equals(java.util.List.of(31), positiveX.xGapLines(), "positive X gap is one block before next cell");
		var negativeX = StartDoorAnchorCaptureRules.gridRelation(
			new StartDoorAnchorCaptureRules.Bounds(-2, 69, 14, 0, 72, 17), 0, 0);
		equals(java.util.List.of(-1), negativeX.xGapLines(), "negative X gap is one block before Entrance cell");
		var positiveZ = StartDoorAnchorCaptureRules.gridRelation(
			new StartDoorAnchorCaptureRules.Bounds(14, 69, 30, 17, 72, 32), 0, 0);
		equals("gap-axis-Z", positiveZ.orientation(), "map grid reports a Z-oriented Entrance gap");
		equals(java.util.List.of(31), positiveZ.zGapLines(), "positive Z gap is one block before next cell");
		var noGap = StartDoorAnchorCaptureRules.gridRelation(
			new StartDoorAnchorCaptureRules.Bounds(5, 69, 9, 7, 72, 11), 0, 0);
		equals("does-not-cross-a-grid-gap", noGap.orientation(), "region inside a cell is not mislabeled as a door gap");
		equals("unavailable", StartDoorAnchorCaptureRules.gridRelation(null, 0, 0).orientation(),
			"missing capture bounds produce an unavailable grid relation");

		var mutableGaps = new java.util.ArrayList<>(java.util.List.of(31));
		var normalizedRelation = new StartDoorAnchorCaptureRules.GridRelation(
			0, 1, 0, 1, 0, 0, 0, 0, mutableGaps, null, null);
		mutableGaps.add(63);
		var equivalentRelation = new StartDoorAnchorCaptureRules.GridRelation(
			0, 1, 0, 1, 0, 0, 0, 0, java.util.List.of(31), java.util.List.of(), "unknown");
		var expectedRelation = new ExpectedGridRelation(
			0, 1, 0, 1, 0, 0, 0, 0, java.util.List.of(31), java.util.List.of(), "unknown");
		check(normalizedRelation.equals(equivalentRelation) && equivalentRelation.equals(normalizedRelation)
			&& normalizedRelation.hashCode() == expectedRelation.hashCode()
			&& normalizedRelation.toString().equals(expectedRelation.toString().replaceFirst("^ExpectedGridRelation", "GridRelation")),
			"grid relation keeps record-style equality, hash, and text behavior");
		equals(java.util.List.of(31), normalizedRelation.xGapLines(),
			"grid relations defensively copy their input gap lines");
		equals(java.util.List.of(), normalizedRelation.zGapLines(),
			"null gap lines normalize to an empty list");
		equals("unknown", normalizedRelation.orientation(), "null grid orientation normalizes to unknown");
		try {
			normalizedRelation.xGapLines().add(95);
			check(false, "grid relation gap lines are immutable");
		} catch (UnsupportedOperationException expected) {
			check(true, "grid relation gap lines are immutable");
		}
		try {
			new StartDoorAnchorCaptureRules.GridRelation(0, 0, 0, 0, 0, 0, 0, 0,
				java.util.Arrays.asList((Integer) null), java.util.List.of(), "unknown");
			check(false, "grid relation gap lines reject null entries as the former record did");
		} catch (NullPointerException expected) {
			check(true, "grid relation gap lines reject null entries as the former record did");
		}

		StartDoorAnchorCaptureRules.DiagnosticBudget budget = new StartDoorAnchorCaptureRules.DiagnosticBudget();
		budget.switchRun(4);
		for (int i = 0; i < StartDoorAnchorCaptureRules.MAX_DIAGNOSTIC_EVENTS_PER_RUN; i++)
			check(budget.allowDetail(), "capture diagnostic remains available within its per-run cap");
		check(!budget.allowDetail() && budget.suppressed() == 1,
			"capture diagnostics are bounded and count suppressed detail after the cap");
		equals(1L, budget.switchRun(5), "the next run reports the prior run's suppressed detail");
		check(budget.allowDetail(), "a new run receives a fresh capture diagnostic budget");
	}

	private static void spawnStartDoorVolumeSelection() {
		BlockPos center = new BlockPos(-121, 69, -40);
		for (boolean alongX : new boolean[] { true, false }) {
			var positions = DungeonDoorBlockReplacementRules.spawnStartDoorVolumePositions(center, alongX);
			equals(60, positions.size(), "the spawn selector samples the complete 5×4×3 volume");
			var loaded = new java.util.HashSet<Long>();
			var materials = new java.util.HashMap<Long, String>();
			for (BlockPos position : positions) {
				loaded.add(position.asLong());
				materials.put(position.asLong(), "minecraft:air");
			}
			java.util.List<BlockPos> expectedPanel = new java.util.ArrayList<>();
			for (int depth = -1; depth <= 1; depth++) {
				for (int side = -1; side <= 1; side++) {
					for (int height = 0; height <= 3; height++) {
						BlockPos source = alongX
							? new BlockPos(center.getX() + depth, center.getY() + height, center.getZ() + side)
							: new BlockPos(center.getX() + side, center.getY() + height, center.getZ() + depth);
						expectedPanel.add(source);
						materials.put(source.asLong(), (depth + side + height) % 2 == 0
							? "minecraft:chiseled_stone_bricks" : "minecraft:infested_chiseled_stone_bricks");
					}
				}
			}
			var scan = DungeonDoorBlockReplacementRules.scanSpawnStartDoorVolume(center, alongX, loaded, materials);
			check(scan.complete(), "a fully loaded volume is eligible for Start selection");
			equals(1, scan.candidatePanels().size(),
				"one face-connected source panel spanning all three depth slices is one 3D component");
			equals("selected-single-3d-component", scan.selectionOutcome(),
				"a complete, unique 3D component is marked selected on its first scan");
			equals(new java.util.HashSet<>(expectedPanel), new java.util.HashSet<>(scan.uniquePanelPositions()),
				"the first complete scan publishes every regular or infested chiseled source in the panel");
			check(scan.diagnosticSummary().contains("componentCount3D=1")
				&& scan.diagnosticSummary().contains("selection=selected-single-3d-component"),
				"Start diagnostics report 3D component count and the selection outcome");

			var disconnectedMaterials = new java.util.HashMap<>(materials);
			disconnectedMaterials.replaceAll((packed, material) -> "minecraft:air");
			BlockPos first = alongX
				? new BlockPos(center.getX() - 1, center.getY(), center.getZ() - 2)
				: new BlockPos(center.getX() - 2, center.getY(), center.getZ() - 1);
			BlockPos second = alongX
				? new BlockPos(center.getX() + 1, center.getY() + 3, center.getZ() + 2)
				: new BlockPos(center.getX() + 2, center.getY() + 3, center.getZ() + 1);
			disconnectedMaterials.put(first.asLong(), "minecraft:chiseled_stone_bricks");
			disconnectedMaterials.put(second.asLong(), "minecraft:infested_chiseled_stone_bricks");
			var disconnected = DungeonDoorBlockReplacementRules.scanSpawnStartDoorVolume(center, alongX,
				loaded, disconnectedMaterials);
			equals(2, disconnected.candidatePanels().size(), "face-disconnected sources remain separate 3D panels");
			check(disconnected.uniquePanelPositions().isEmpty(), "multiple disconnected panels are rejected");
			equals("rejected-multiple-3d-components", disconnected.selectionOutcome(),
				"ambiguous component rejection is explicit in diagnostics");

			var unsupportedMaterials = new java.util.HashMap<Long, String>();
			for (BlockPos position : positions) unsupportedMaterials.put(position.asLong(), "minecraft:stone");
			var unsupported = DungeonDoorBlockReplacementRules.scanSpawnStartDoorVolume(center, alongX,
				loaded, unsupportedMaterials);
			check(unsupported.uniquePanelPositions().isEmpty(), "unsupported-only material cannot become a Start target");
			equals("rejected-no-supported-source", unsupported.selectionOutcome(),
				"unsupported-only scans report the material rejection outcome");

			var incompleteLoaded = new java.util.HashSet<>(loaded);
			incompleteLoaded.remove(expectedPanel.getFirst().asLong());
			var incomplete = DungeonDoorBlockReplacementRules.scanSpawnStartDoorVolume(center, alongX,
				incompleteLoaded, materials);
			check(!incomplete.complete() && incomplete.uniquePanelPositions().isEmpty(),
				"a unique panel is rejected until all 60 positions are loaded");
			equals("rejected-incomplete-volume", incomplete.selectionOutcome(),
				"incomplete scans report that selection is waiting on loaded positions");
		}
	}

	private static void spawnStartForwardDistanceRange() {
		check(DungeonDoorTracker.isSpawnStartForwardDistanceInRange(16.0),
			"the observed 16-block Start edge distance is accepted");
		check(DungeonDoorTracker.isSpawnStartForwardDistanceInRange(17.0),
			"the previous minimum remains accepted");
		check(DungeonDoorTracker.isSpawnStartForwardDistanceInRange(20.0),
			"the maximum Start edge distance remains accepted");
		check(!DungeonDoorTracker.isSpawnStartForwardDistanceInRange(15.0),
			"Start edge distances below 16 blocks remain rejected");
		check(!DungeonDoorTracker.isSpawnStartForwardDistanceInRange(21.0),
			"Start edge distances above 20 blocks remain rejected");
	}

	private static void startSelectionLifecycle() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var glass = Blocks.GLASS.defaultBlockState();
		var positions = DungeonDoorBlockReplacementRules.startDoorVolumePositions(new BlockPos(-154, 69, -42), true);
		var normalPosition = positions.getFirst();
		var infestedPosition = positions.get(1);
		var ordinaryPosition = positions.get(2);
		var wallPosition = positions.get(3);
		var persistentPosition = positions.get(4);
		java.util.Map<Long, DungeonDoorBlockOverlayController.Projection> desired = new java.util.HashMap<>();
		world.serverSet(normalPosition, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
		world.serverSet(infestedPosition, Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultBlockState());
		world.serverSet(ordinaryPosition, Blocks.STONE.defaultBlockState());
		world.serverSet(wallPosition, Blocks.STONE_BRICK_WALL.defaultBlockState());
		world.serverSet(persistentPosition, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
		check(engine.rememberStartSource(world, normalPosition, world.getBlockState(normalPosition)),
			"a map-selected regular chiseled source is remembered");
		check(engine.rememberStartSource(world, infestedPosition, world.getBlockState(infestedPosition)),
			"a map-selected infested chiseled source is remembered");
		check(engine.rememberStartSource(world, persistentPosition, world.getBlockState(persistentPosition)),
			"a third selected source can be retained independently");
		check(!engine.rememberStartSource(world, ordinaryPosition, world.getBlockState(ordinaryPosition))
			&& !engine.rememberStartSource(world, wallPosition, world.getBlockState(wallPosition)),
			"ordinary blocks and walls are not remembered as Start sources");
		var outside = new BlockPos(-156, 69, -42);
		check(!positions.contains(outside),
			"the generated map-selected volume excludes a chiseled block beyond its X-depth bound");
		desired.put(normalPosition.asLong(), new DungeonDoorBlockOverlayController.Projection(
			DungeonDoorBlockReplacementRules.DoorKind.START, glass));
		desired.put(infestedPosition.asLong(), new DungeonDoorBlockOverlayController.Projection(
			DungeonDoorBlockReplacementRules.DoorKind.START, glass));
		desired.put(persistentPosition.asLong(), new DungeonDoorBlockOverlayController.Projection(
			DungeonDoorBlockReplacementRules.DoorKind.START, glass));
		engine.reconcile(world, desired);
		equals(18, world.lastFlags, "local projection writes use the client update flag combination");
		var stats = engine.stats(world).get(DungeonDoorBlockReplacementRules.DoorKind.START);
		equals(3, stats.targets(), "only selected supported Start masks become targets");
		equals(3, stats.loaded(), "status counts only selected loaded Start targets");
		equals(3, stats.sourceNonAir(), "all selected materials are non-air source states");
		equals(3, stats.active(), "each selected source receives the configured local glass state");
		same(glass, world.getBlockState(normalPosition), "regular Chiseled Stone Bricks is locally glass");
		same(glass, world.getBlockState(infestedPosition), "Infested Chiseled Stone Bricks is locally glass");
		same(Blocks.STONE.defaultBlockState(), world.getBlockState(ordinaryPosition),
			"ordinary Stone in the Start gap volume remains unchanged");
		same(Blocks.STONE_BRICK_WALL.defaultBlockState(), world.getBlockState(wallPosition),
			"a stone-brick wall in the Start gap volume remains unchanged");
		same(Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),
			engine.miningState(world, normalPosition, glass),
			"mining sees the full original regular Chiseled Stone Bricks state behind owned glass");
		var afterMining = engine.stats(world).get(DungeonDoorBlockReplacementRules.DoorKind.START);
		equals(1L, afterMining.miningReads(), "tracked mining queries are counted per Start type");
		equals(1L, afterMining.miningSourceReturns(), "owned glass returns the authoritative source for mining");
		same(Blocks.STONE.defaultBlockState(), engine.miningState(world, ordinaryPosition,
			Blocks.STONE.defaultBlockState()), "unselected mining reads are passed through unchanged");
		equals(1L, engine.stats(world).get(DungeonDoorBlockReplacementRules.DoorKind.START).miningReads(),
			"untracked positions do not affect per-type mining diagnostics");
		same(Blocks.DIRT.defaultBlockState(), engine.miningState(world, normalPosition,
			Blocks.DIRT.defaultBlockState()), "an observed state mismatch is passed through instead of mining a stale source");
		equals(1L, engine.stats(world).get(DungeonDoorBlockReplacementRules.DoorKind.START)
			.miningOwnershipMismatches(), "owned-projection mining mismatches are counted");

		world.serverSet(normalPosition, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, normalPosition, Blocks.BARRIER.defaultBlockState());
		same(glass, world.getBlockState(normalPosition), "a selected target keeps glass over Barrier");
		same(Blocks.BARRIER.defaultBlockState(), engine.authoritativeState(world, normalPosition),
			"the full Barrier source is retained independently behind Start glass");
		world.serverSet(normalPosition, Blocks.AIR.defaultBlockState());
		engine.onServerBlockState(world, normalPosition, Blocks.AIR.defaultBlockState());
		same(Blocks.AIR.defaultBlockState(), world.getBlockState(normalPosition),
			"an incoming server Air update immediately clears the local projection");
		check(!engine.retainedPositions(world, DungeonDoorBlockReplacementRules.DoorKind.START)
			.contains(normalPosition.asLong()), "authoritative Air retires and forgets the selected Start source");
		same(Blocks.AIR.defaultBlockState(), engine.miningState(world, normalPosition,
			Blocks.AIR.defaultBlockState()), "Air remains the mining state after the source mask is retired");
		world.serverSet(normalPosition, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, normalPosition, Blocks.BARRIER.defaultBlockState());
		engine.reconcile(world, desired);
		same(Blocks.BARRIER.defaultBlockState(), world.getBlockState(normalPosition),
			"a later non-air update remains unprojected after source Air retirement");

		world.serverSet(infestedPosition, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, infestedPosition, Blocks.BARRIER.defaultBlockState());
		same(glass, world.getBlockState(infestedPosition), "a selected Start source keeps glass over Barrier");
		same(Blocks.BARRIER.defaultBlockState(), engine.miningState(world, infestedPosition, glass),
			"mining observes the original full Barrier state behind active Start glass");
		world.serverSet(infestedPosition, Blocks.AIR.defaultBlockState());
		engine.onChunkInstalled(world, infestedPosition.getX() >> 4, infestedPosition.getZ() >> 4);
		check(!engine.retainedPositions(world, DungeonDoorBlockReplacementRules.DoorKind.START)
			.contains(infestedPosition.asLong()), "a full chunk image containing Air retires the Start source");
		same(Blocks.AIR.defaultBlockState(), world.getBlockState(infestedPosition),
			"a full chunk refresh keeps authoritative Air visible without glass");
		world.serverSet(infestedPosition, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
		check(!engine.rememberStartSource(world, infestedPosition, world.getBlockState(infestedPosition)),
			"a non-air update after chunk Air retirement cannot reselect the same source");

		world.serverSet(persistentPosition, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, persistentPosition, Blocks.BARRIER.defaultBlockState());
		world.serverSet(persistentPosition, Blocks.BARRIER.defaultBlockState());
		world.serverSet(infestedPosition, Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultBlockState());
		engine.onChunkInstalled(world, persistentPosition.getX() >> 4, persistentPosition.getZ() >> 4);
		same(glass, world.getBlockState(persistentPosition),
			"a full chunk refresh re-seeds and projects a retained non-air Start mask");

		engine.reconcile(world, java.util.Map.of());
		same(Blocks.BARRIER.defaultBlockState(), world.getBlockState(persistentPosition),
			"disabling restores the latest authoritative Start source");
		same(Blocks.BARRIER.defaultBlockState(), engine.miningState(world, persistentPosition,
			Blocks.BARRIER.defaultBlockState()), "disabled masks do not substitute mining state without an active projection");
		check(engine.retainedPositions(world, DungeonDoorBlockReplacementRules.DoorKind.START)
			.contains(persistentPosition.asLong()), "same-level disable preserves the selected Start source mask");
		int writesBeforeUnload = world.writeCalls;
		engine.onChunkUnloaded(world, persistentPosition.getX() >> 4, persistentPosition.getZ() >> 4);
		check(engine.retainedPositions(world, DungeonDoorBlockReplacementRules.DoorKind.START).isEmpty(),
			"an actual chunk unload clears selected Start masks for the unloaded chunk");
		equals(writesBeforeUnload, world.writeCalls, "chunk unload evicts without writing or forcing a restore");
	}

	private static void retainedMaskLifecycle() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var position = new BlockPos(-170, 69, -186);
		long packed = position.asLong();
		var kind = DungeonDoorBlockReplacementRules.DoorKind.WITHER;
		var blackGlass = Blocks.STAINED_GLASS.pick(DyeColor.BLACK).defaultBlockState();
		world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		engine.rememberDoorSource(world, kind, position);
		engine.reconcile(world, java.util.Map.of(packed,
			new DungeonDoorBlockOverlayController.Projection(kind, blackGlass)));
		same(blackGlass, world.getBlockState(position), "a coal source seeds the existing Wither mask");
		same(Blocks.COAL_BLOCK.defaultBlockState(), engine.authoritativeState(world, position),
			"the authoritative coal source stays available to ESP while the local state is glass");

		world.serverSet(position, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, position, Blocks.BARRIER.defaultBlockState());
		same(blackGlass, world.getBlockState(position), "the same mask survives a non-air Barrier update");
		same(Blocks.BARRIER.defaultBlockState(), engine.authoritativeState(world, position),
			"the complete Barrier source state is retained behind the Wither glass");
		world.serverSet(position, Blocks.AIR.defaultBlockState());
		engine.onServerBlockState(world, position, Blocks.AIR.defaultBlockState());
		same(Blocks.AIR.defaultBlockState(), world.getBlockState(position), "server Air remains immediately visible");
		check(!engine.retainedPositions(world, kind).contains(packed),
			"authoritative Air immediately retires a Wither mask");
		world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		engine.onServerBlockState(world, position, Blocks.COAL_BLOCK.defaultBlockState());
		check(!engine.rememberDoorSource(world, kind, position),
			"a stale shared-door source cannot reselect a position retired by Air");
		engine.reconcile(world, java.util.Map.of(packed,
			new DungeonDoorBlockOverlayController.Projection(kind, blackGlass)));
		same(Blocks.COAL_BLOCK.defaultBlockState(), world.getBlockState(position),
			"a later coal update stays unprojected for the remainder of the same session");
		engine.reconcile(world, java.util.Map.of());
		same(Blocks.COAL_BLOCK.defaultBlockState(), world.getBlockState(position),
			"disabling projection leaves the latest server source visible");
		check(engine.retainedPositions(world, kind).isEmpty(),
			"a retired source remains absent from the Wither mask after projection disable");

		int chunkX = position.getX() >> 4, chunkZ = position.getZ() >> 4;
		engine.onChunkUnloaded(world, chunkX, chunkZ);
		world.loadedChunks.add(FakeWorld.chunkKey(chunkX, chunkZ));
		world.serverSet(position, Blocks.BARRIER.defaultBlockState());
		engine.onChunkInstalled(world, chunkX, chunkZ);
		same(Blocks.BARRIER.defaultBlockState(), world.getBlockState(position),
			"a chunk reload does not clear the same-session Air retirement or project a Barrier");
		world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		engine.onChunkInstalled(world, chunkX, chunkZ);
		same(Blocks.COAL_BLOCK.defaultBlockState(), world.getBlockState(position),
			"a chunk reload does not reselect a retired coal source");
		int writesBeforeUnload = world.writeCalls;
		engine.onChunkUnloaded(world, chunkX, chunkZ);
		check(engine.retainedPositions(world, kind).isEmpty(), "chunk unload clears the same-chunk mask");
		equals(writesBeforeUnload, world.writeCalls,
			"chunk unload evicts caches without writing blocks or forcing a restore");
		engine.onChunkUnloaded(world, position.getX() >> 4, position.getZ() >> 4);
		equals(writesBeforeUnload, world.writeCalls, "a duplicate chunk unload remains an idempotent no-write eviction");
		engine.restoreAndClear(world);
		world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		check(engine.rememberDoorSource(world, kind, position),
			"the defined dungeon/world cleanup lifecycle clears Air retirement");
	}

	private static void dungeonSessionCleanup() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var position = new BlockPos(-170, 69, -186);
		var kind = DungeonDoorBlockReplacementRules.DoorKind.BLOOD;
		var glass = Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState();
		world.serverSet(position, Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState());
		engine.rememberDoorSource(world, kind, position);
		engine.reconcile(world, java.util.Map.of(position.asLong(),
			new DungeonDoorBlockOverlayController.Projection(kind, glass)));
		world.serverSet(position, Blocks.BARRIER.defaultBlockState());
		engine.onServerBlockState(world, position, Blocks.BARRIER.defaultBlockState());
		same(glass, world.getBlockState(position), "the dungeon session owns the local Blood projection before exit");

		engine.restoreAndClear(world);
		same(Blocks.BARRIER.defaultBlockState(), world.getBlockState(position),
			"same-world dungeon exit restores the latest authoritative non-air source");
		check(engine.retainedPositions(world, kind).isEmpty(),
			"same-world dungeon exit clears retained typed door source masks");
		equals(0, engine.stats(world).get(kind).active(),
			"same-world dungeon exit drops active local overlay ownership");
	}

	private static void ownershipAndFailureDiagnostics() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var position = new BlockPos(-20, 70, -20);
		long packed = position.asLong();
		var kind = DungeonDoorBlockReplacementRules.DoorKind.WITHER;
		var glass = Blocks.STAINED_GLASS.pick(DyeColor.BLACK).defaultBlockState();
		world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		engine.rememberDoorSource(world, kind, position);
		var projection = new DungeonDoorBlockOverlayController.Projection(kind, glass);
		engine.reconcile(world, java.util.Map.of(packed, projection));
		world.serverSet(position, Blocks.DIRT.defaultBlockState());
		same(Blocks.DIRT.defaultBlockState(), engine.miningState(world, position,
			Blocks.DIRT.defaultBlockState()), "an unowned client mutation is passed through to mining unchanged");
		check(engine.stats(world).get(kind).miningOwnershipMismatches() > 0,
			"mining records that an external local mutation invalidated projection ownership");
		engine.reconcile(world, java.util.Map.of());
		same(Blocks.DIRT.defaultBlockState(), world.getBlockState(position),
			"restoration never overwrites a block the controller no longer owns");
		check(engine.stats(world).get(kind).failures() > 0,
			"an ownership mismatch increments the bounded failure diagnostic counter");

		var rejected = new BlockPos(-19, 70, -20);
		world.serverSet(rejected, Blocks.COAL_BLOCK.defaultBlockState());
		engine.rememberDoorSource(world, kind, rejected);
		world.rejectWrites = true;
		engine.reconcile(world, java.util.Map.of(rejected.asLong(), projection));
		world.rejectWrites = false;
		var stats = engine.stats(world).get(kind);
		check(stats.failures() >= 2, "failed client-world writes are counted by door type");
		check(!world.getBlockState(rejected).equals(glass), "a rejected local write does not claim an active projection");

		var unloadedEngine = new DungeonDoorBlockOverlayController.Engine();
		var unloadedWorld = new FakeWorld();
		unloadedWorld.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		unloadedEngine.rememberDoorSource(unloadedWorld, kind, position);
		unloadedWorld.allChunksLoaded = false;
		int writesBeforeMissingChunk = unloadedWorld.writeCalls;
		unloadedEngine.reconcile(unloadedWorld, java.util.Map.of(packed, projection));
		equals(writesBeforeMissingChunk, unloadedWorld.writeCalls,
			"unloaded target chunks are skipped without forcing a load or attempting a write");
		equals(0, unloadedEngine.stats(unloadedWorld).get(kind).loaded(),
			"status distinguishes an unavailable target chunk from an active local overlay");
	}

	private static void levelIdentityLifecycle() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var oldWorld = new FakeWorld();
		var position = new BlockPos(-170, 69, -186);
		var kind = DungeonDoorBlockReplacementRules.DoorKind.WITHER;
		var glass = Blocks.STAINED_GLASS.pick(DyeColor.BLACK).defaultBlockState();
		oldWorld.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		engine.rememberDoorSource(oldWorld, kind, position);
		engine.reconcile(oldWorld, java.util.Map.of(position.asLong(),
			new DungeonDoorBlockOverlayController.Projection(kind, glass)));
		same(glass, oldWorld.getBlockState(position),
			"the old level owns an applied Wither projection before teardown");

		var nextWorld = new FakeWorld();
		nextWorld.serverSet(position, Blocks.DIRT.defaultBlockState());
		int writesBeforeMismatch = nextWorld.writeCalls;
		engine.restoreAndClear(nextWorld);
		equals(writesBeforeMismatch, nextWorld.writeCalls,
			"a level identity mismatch never restores old projection state into the new level");
		same(Blocks.DIRT.defaultBlockState(), nextWorld.getBlockState(position),
			"the new level's unrelated source block remains untouched");
		check(engine.retainedPositions(nextWorld, kind).isEmpty(),
			"a level identity mismatch clears old same-world Wither masks");
		for (var stats : engine.stats(nextWorld).values()) equals(0, stats.active(),
			"a level identity mismatch drops stale projection ownership before new-level status");
		int writesBeforeDuplicate = nextWorld.writeCalls;
		engine.restoreAndClear(nextWorld);
		equals(writesBeforeDuplicate, nextWorld.writeCalls,
			"a duplicate restore after identity mismatch is harmless");

		var unavailableEngine = new DungeonDoorBlockOverlayController.Engine();
		var unavailableWorld = new FakeWorld();
		unavailableWorld.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
		unavailableEngine.rememberDoorSource(unavailableWorld, kind, position);
		unavailableEngine.reconcile(unavailableWorld, java.util.Map.of(position.asLong(),
			new DungeonDoorBlockOverlayController.Projection(kind, glass)));
		unavailableEngine.restoreAndClearWithoutWorld();
		same(glass, unavailableWorld.getBlockState(position),
			"when no level handle remains, cleanup reports lost ownership without touching an unavailable world");
		check(unavailableEngine.stats(null).get(kind).failures() > 0,
			"unavailable-world cleanup increments the bounded ownership-loss diagnostic");
	}

	private static void detailedLogBudgetAndDeduplication() {
		var engine = new DungeonDoorBlockOverlayController.Engine();
		var world = new FakeWorld();
		var kind = DungeonDoorBlockReplacementRules.DoorKind.WITHER;
		var glass = Blocks.STAINED_GLASS.pick(DyeColor.BLACK).defaultBlockState();
		var repeatedPosition = new BlockPos(-200, 70, -20);
		world.serverSet(repeatedPosition, Blocks.COAL_BLOCK.defaultBlockState());
		engine.rememberDoorSource(world, kind, repeatedPosition);
		engine.reconcile(world, java.util.Map.of(repeatedPosition.asLong(),
			new DungeonDoorBlockOverlayController.Projection(kind, glass)));
		world.serverSet(repeatedPosition, Blocks.COAL_BLOCK.defaultBlockState());
		engine.onServerBlockState(world, repeatedPosition, Blocks.COAL_BLOCK.defaultBlockState());
		equals(1, engine.detailLogsThisTick(),
			"reasserting an unchanged source still reprojects but does not repeat the same detail log");
		equals(2L, engine.stats(world).get(kind).writes(),
			"deduplicating unchanged log text does not suppress a successful local write or counter");

		java.util.Map<Long, DungeonDoorBlockOverlayController.Projection> many = new java.util.HashMap<>();
		for (int index = 0; index < 139; index++) {
			var position = new BlockPos(-300 - index, 70, -20);
			world.serverSet(position, Blocks.COAL_BLOCK.defaultBlockState());
			engine.rememberDoorSource(world, kind, position);
			many.put(position.asLong(), new DungeonDoorBlockOverlayController.Projection(kind, glass));
		}
		many.put(repeatedPosition.asLong(), new DungeonDoorBlockOverlayController.Projection(kind, glass));
		engine.reconcile(world, many);
		equals(128, engine.detailLogsThisTick(), "detailed controller logging is capped at 128 events per game tick");
		equals(13L, engine.suppressedDetailLogs(), "deduplicated and capped event volume is counted for the next bounded summary");
		equals(13L, engine.totalSuppressedDetailLogs(), "status can retain a bounded cumulative suppressed-detail count");
		engine.recordCoalescedDetailSuppressions(world, 2);
		equals(15L, engine.totalSuppressedDetailLogs(), "coalesced status/layout snapshots contribute to suppressed detail totals");
		world.tick++;
		engine.stats(world);
		equals(0L, engine.suppressedDetailLogs(), "the next game tick flushes and clears the suppression summary count");
		equals(0, engine.detailLogsThisTick(), "the detail event budget resets on the next game tick");
		equals(15L, engine.totalSuppressedDetailLogs(), "reported suppression volume remains available to the manual status action");
	}

	private static final class FakeWorld implements DungeonDoorBlockOverlayController.BlockAccess {
		private final Object identity = new Object();
		private final java.util.Map<Long, net.minecraft.world.level.block.state.BlockState> states =
			new java.util.HashMap<>();
		private final java.util.Set<Long> loadedChunks = new java.util.HashSet<>();
		private boolean allChunksLoaded = true;
		private boolean rejectWrites;
		private int writeCalls;
		private int lastFlags;
		private long tick;

		@Override public Object levelIdentity() { return identity; }
		@Override public long gameTick() { return tick; }
		@Override public boolean hasChunkAt(BlockPos position) {
			return allChunksLoaded || loadedChunks.contains(chunkKey(position.getX() >> 4, position.getZ() >> 4));
		}
		@Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos position) {
			return states.getOrDefault(position.asLong(), Blocks.AIR.defaultBlockState());
		}
		@Override public boolean setBlock(BlockPos position,
			net.minecraft.world.level.block.state.BlockState state, int flags) {
			writeCalls++;
			lastFlags = flags;
			if (rejectWrites || !hasChunkAt(position) || getBlockState(position).equals(state)) return false;
			states.put(position.asLong(), state);
			return true;
		}
		void serverSet(BlockPos position, net.minecraft.world.level.block.state.BlockState state) {
			states.put(position.asLong(), state);
		}
		private static long chunkKey(int x, int z) {
			return ((long) x << 32) | (z & 0xFFFF_FFFFL);
		}
	}

	private static void detectorRefreshLifecycle() {
		check(!DungeonDoorRefreshRules.shouldRescanAvailability(false, false, false),
			"the initial unavailable state does not create duplicate work beside the background scan");
		check(!DungeonDoorRefreshRules.shouldRescanAvailability(false, false, true),
			"initial loaded edges are covered by the background scan and player-room priority");
		check(DungeonDoorRefreshRules.shouldRescanAvailability(true, false, true),
			"a newly loaded edge queues a targeted scan for the next tracker tick");
		check(DungeonDoorRefreshRules.shouldRescanAvailability(true, true, false),
			"an unloaded edge queues a refresh while its typed door record remains available");
		check(!DungeonDoorRefreshRules.shouldRescanAvailability(true, true, true),
			"stable loaded edges do not rescan every tick");

		check(DungeonDoorRefreshRules.changedBlockTouchesEdge(true, -169, -185, 69, -169, 72, -187),
			"a change to the doorway frame queues its horizontal edge");
		check(DungeonDoorRefreshRules.changedBlockTouchesEdge(false, -185, -169, 69, -187, 70, -169),
			"a change to the doorway plane queues its vertical edge");
		check(DungeonDoorRefreshRules.changedBlockTouchesEdge(true, -169, -185, 69, -168, 70, -185),
			"a block in an outer depth slice queues the door-volume rescan");
		check(!DungeonDoorRefreshRules.changedBlockTouchesEdge(true, -169, -185, 69, -167, 70, -185),
			"a block beyond the source volume and doorway frame does not queue an unrelated edge");
		check(!DungeonDoorRefreshRules.changedBlockTouchesEdge(true, -169, -185, 69, -169, 73, -185),
			"a block above the scanned door opening does not queue an unrelated edge");

		var coalSource = new BlockPos(-169, 70, -185);
		var secondCoalSource = new BlockPos(-169, 71, -185);
		var oldDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.WITHER, 7, 8,
			-170, 69, -186, 3, 4, 3, java.util.List.of(coalSource));
		var unrelatedDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD, 8, 9,
			-138, 69, -186, 3, 4, 3, java.util.List.of(new BlockPos(-137, 70, -185)));
		var snapshot = java.util.List.of(oldDoor, unrelatedDoor);
		var cleared = DungeonDoorRefreshRules.replaceEdge(snapshot, 7, 8, null);
		equals(java.util.List.of(unrelatedDoor), cleared,
			"explicit edge cleanup removes a door record from the published detector snapshot");
		var changedDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.GLASS, 7, 8,
			-170, 69, -186, 3, 4, 3);
		var replaced = DungeonDoorRefreshRules.replaceEdge(snapshot, 7, 8, changedDoor);
		equals(java.util.List.of(unrelatedDoor, changedDoor), replaced,
			"a targeted rescan replaces the prior edge classification without disturbing neighboring doors");

		var retainedOnEmpty = DungeonDoorRefreshRules.refreshEdge(snapshot, 7, 8, null);
		equals(snapshot, retainedOnEmpty,
			"open, barrier, incomplete, and chunk-unavailable rescans retain the typed Wither record and exact source positions");
		var afterChunkUnload = DungeonDoorRefreshRules.onChunkUnload(snapshot, 7, 8);
		equals(snapshot, afterChunkUnload,
			"chunk unload keeps the Wither record, bounds, and immutable source positions until dungeon exit");
		check(DungeonDoorRefreshRules.shouldRescanAvailability(true, false, true),
			"loading the edge's chunks schedules a fresh scan against the retained record");
		equals(snapshot, DungeonDoorRefreshRules.refreshEdge(afterChunkUnload, 7, 8, null),
			"a post-load scan with non-source states retains the original typed record and source positions");
		equals(snapshot, DungeonDoorRefreshRules.onChunkUnload(snapshot, 8, 9),
			"chunk unload keeps a Blood record and its source positions until dungeon exit");
		var retainedOnOtherDoor = DungeonDoorRefreshRules.refreshEdge(snapshot, 7, 8, changedDoor);
		equals(snapshot, retainedOnOtherDoor,
			"a non-key rediscovery cannot relabel or remove a retained Wither record");
		var conflictingBlood = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD, 7, 8,
			-170, 69, -186, 3, 4, 3, java.util.List.of(secondCoalSource));
		equals(snapshot, DungeonDoorRefreshRules.refreshEdge(snapshot, 7, 8, conflictingBlood),
			"a conflicting Blood scan cannot change a stable Wither edge identity");
		var expandedWither = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.WITHER, 7, 8,
			-170, 69, -186, 3, 4, 3, java.util.List.of(secondCoalSource));
		var refreshed = DungeonDoorRefreshRules.refreshEdge(snapshot, 7, 8, expandedWither);
		var expectedRefreshed = oldDoor.withSourcePositions(java.util.List.of(coalSource, secondCoalSource));
		equals(unrelatedDoor, refreshed.getFirst(), "same-type refresh leaves the unrelated edge in its existing order");
		var refreshedEdge = refreshed.get(1);
		equals(expectedRefreshed, refreshedEdge,
			"same-type rediscovery unions exact source positions without changing record identity or bounds");
		equals(oldDoor.identity(), refreshedEdge.identity(),
			"a rediscovered record keeps the same adjacent-cell edge identity");
		equals(DungeonDoorTracker.Type.WITHER, refreshedEdge.type(),
			"same-type rediscovery preserves the edge's Wither classification");
		equals(java.util.List.of(coalSource, secondCoalSource), refreshedEdge.sourcePositions(),
			"same-type rediscovery keeps the union of the original and newly observed source positions");
		var normal = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.NORMAL, 7, 8,
			-170, 69, -186, 3, 4, 3);
		equals(java.util.List.of(), DungeonDoorRefreshRules.refreshEdge(java.util.List.of(normal), 7, 8, null),
			"non-key door records still disappear after an empty edge rescan");
		equals(java.util.List.of(), DungeonDoorRefreshRules.onChunkUnload(java.util.List.of(normal), 7, 8),
			"transient non-key door records are removed when their chunk unloads");
		var bloodDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD, 9, 10,
			-138, 69, -186, 3, 4, 3, java.util.List.of(new BlockPos(-137, 71, -185)));
		equals(java.util.List.of(bloodDoor), DungeonDoorRefreshRules.refreshEdge(java.util.List.of(bloodDoor), 9, 10, null),
			"Blood door records retain their type and source positions over non-source updates");
	}

	private record ExpectedGridRelation(int relativeMinX, int relativeMaxX, int relativeMinZ, int relativeMaxZ,
		int minCellOffsetX, int maxCellOffsetX, int minCellOffsetZ, int maxCellOffsetZ,
		java.util.List<Integer> xGapLines, java.util.List<Integer> zGapLines, String orientation) { }

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void isNull(Object value, String message) {
		if (value != null) throw new AssertionError(message + ": expected null, got " + value);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}

	private static void same(Object expected, Object actual, String message) {
		if (expected != actual) throw new AssertionError(message);
	}
}
