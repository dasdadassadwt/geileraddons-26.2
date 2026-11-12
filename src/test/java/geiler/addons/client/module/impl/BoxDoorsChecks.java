package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.DungeonDoorTracker;
import geiler.addons.client.dungeon.BloodDoorTraceRules;
import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonGridRules;
import geiler.addons.client.dungeon.DungeonMapGeometry;
import geiler.addons.client.dungeon.odin.OdinDungeonMapGrid;
import geiler.addons.client.chat.ChatStringSemantics;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Focused offline checks for Box Doors highlight selection and legacy setting compatibility. */
public final class BoxDoorsChecks {
	private BoxDoorsChecks() { }

	public static void run() {
		publicRouteApiAbi();
		routeRecordValueSemantics();
		moduleLocalRecordAbi();
		moduleLocalRecordValueSemantics();
		highlightTypes();
		closedDoorSourceStates();
		closedDoorGate();
		tracerRoomFallbackAndConflict();
		tracerRangeRules();
		fairyRouteSelection();
		directFairyBloodExitSelection();
		normalIntermediaryRoute();
		trackerFallbackSelection();
		legacyRoomBoundaryOverloads();
		promptScanRules();
		bloodRushTabClassRows();
		visibleGroupsAndLegacySettings();
	}

	private static void publicRouteApiAbi() {
		checkConstructorAndAccessorShape(BoxDoorsFairyRouteRules.LocalSelection.class,
			"status,target,candidateCount,rejectionReason", BoxDoorsFairyRouteRules.LocalStatus.class,
			DungeonDoorTracker.Door.class, int.class, String.class);
		checkConstructorAndAccessorShape(BoxDoorsFairyRouteRules.TraceChoice.class,
			"target,source,localCandidateCount,rejectionReason", DungeonDoorTracker.Door.class,
			BoxDoorsFairyRouteRules.TargetSource.class, int.class, String.class);
		checkConstructorAndAccessorShape(BoxDoorsFairyRouteRules.MapCellResolution.class,
			"cell,rejectionReason", int.class, String.class);
		checkConstructorAndAccessorShape(BoxDoorsFairyRouteRules.Selection.class,
			"status,transition,currentMapCell,mapFromCell,mapToCell,fromCell,toCell,mapEdgeType,expectedType,target," +
				"offsetX,offsetZ,targetRoomFootprint,currentRoomFootprint,routeRoomCount,routeEdgeCount,routeState,rejectionReason",
			BoxDoorsFairyRouteRules.Status.class, BoxDoorsFairyRouteRules.Transition.class,
			int.class, int.class, int.class, int.class, int.class, OdinDungeonMapGrid.DoorType.class,
			DungeonDoorTracker.Type.class, DungeonDoorTracker.Door.class, int.class, int.class,
			List.class, List.class, int.class, int.class, String.class, String.class);

		checkStaticMethod("mapCellAtMarker", BoxDoorsFairyRouteRules.MapCellResolution.class,
			OdinDungeonMapGrid.ScanResult.class, DungeonMapGeometry.MapPoint.class);
		checkStaticMethod("select", BoxDoorsFairyRouteRules.Selection.class,
			OdinDungeonMapGrid.ScanResult.class, int.class, int.class, List.class);
		checkStaticMethod("select", BoxDoorsFairyRouteRules.Selection.class,
			OdinDungeonMapGrid.ScanResult.class, int.class, int.class, List.class, java.util.function.Function.class);
		checkStaticMethod("select", BoxDoorsFairyRouteRules.Selection.class,
			OdinDungeonMapGrid.ScanResult.class, int.class, int.class, int.class, List.class);
		checkStaticMethod("select", BoxDoorsFairyRouteRules.Selection.class,
			OdinDungeonMapGrid.ScanResult.class, int.class, int.class, int.class, List.class,
			java.util.function.Function.class);
		checkStaticMethod("chooseTracerTarget", BoxDoorsFairyRouteRules.TraceChoice.class,
			BoxDoorsFairyRouteRules.Selection.class, List.class, DungeonDoorTracker.Door.class,
			java.util.function.Function.class);
		checkStaticMethod("chooseTracerTarget", BoxDoorsFairyRouteRules.TraceChoice.class,
			BoxDoorsFairyRouteRules.Selection.class, List.class, DungeonDoorTracker.Door.class);
		checkStaticMethod("chooseTracerTarget", BoxDoorsFairyRouteRules.TraceChoice.class,
			new Class<?>[] { BoxDoorsFairyRouteRules.Selection.class, List.class, Set.class }, true);
		checkStaticMethod("selectLocal", BoxDoorsFairyRouteRules.LocalSelection.class,
			new Class<?>[] { List.class, Set.class }, true);
		checkStaticMethod("mapToWorldCell", int.class, int.class, int.class, int.class);
		checkStaticMethod("fairyRoomCenter", net.minecraft.world.phys.Vec3.class, List.class);

		check(java.util.Arrays.equals(BoxDoorsFairyRouteRules.Status.values(), new BoxDoorsFairyRouteRules.Status[] {
			BoxDoorsFairyRouteRules.Status.MAP_UNAVAILABLE,
			BoxDoorsFairyRouteRules.Status.TRANSFORM_UNAVAILABLE,
			BoxDoorsFairyRouteRules.Status.CURRENT_CELL_UNAVAILABLE,
			BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE,
			BoxDoorsFairyRouteRules.Status.ROUTE_AMBIGUOUS,
			BoxDoorsFairyRouteRules.Status.CURRENT_ROOM_OFF_ROUTE,
			BoxDoorsFairyRouteRules.Status.ROUTE_COMPLETE,
			BoxDoorsFairyRouteRules.Status.NO_KEY_DOOR_ON_NEXT_EDGE,
			BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY,
			BoxDoorsFairyRouteRules.Status.SELECTED_EXIT,
			BoxDoorsFairyRouteRules.Status.SELECTED_BLOOD_EXIT,
			BoxDoorsFairyRouteRules.Status.SELECTED_ROUTE_STEP,
			BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING,
			BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_AMBIGUOUS,
			BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_OPEN,
			BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_STATE_UNKNOWN,
		}), "route status enum ordering stays compatible");
		check(java.util.Arrays.equals(BoxDoorsFairyRouteRules.Transition.values(), new BoxDoorsFairyRouteRules.Transition[] {
			BoxDoorsFairyRouteRules.Transition.NONE,
			BoxDoorsFairyRouteRules.Transition.ENTER_FAIRY,
			BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY,
			BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD,
			BoxDoorsFairyRouteRules.Transition.FOLLOW_ROUTE,
		}), "route transition enum ordering stays compatible");
		check(java.util.Arrays.equals(BoxDoorsFairyRouteRules.TargetSource.values(), new BoxDoorsFairyRouteRules.TargetSource[] {
			BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE,
			BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE,
			BoxDoorsFairyRouteRules.TargetSource.ROUTE_HAS_NO_TARGET,
			BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE,
			BoxDoorsFairyRouteRules.TargetSource.TRACKER_BLOOD_DOOR,
			BoxDoorsFairyRouteRules.TargetSource.TRACKER_ROUTE_COMPLETE,
			BoxDoorsFairyRouteRules.TargetSource.LOCAL_FALLBACK,
			BoxDoorsFairyRouteRules.TargetSource.LOCAL_NONE,
			BoxDoorsFairyRouteRules.TargetSource.LOCAL_AMBIGUOUS,
		}), "tracer target-source enum ordering stays compatible");
		check(BoxDoorsFairyRouteRules.LocalSelection.class.isAnnotationPresent(Deprecated.class)
			&& BoxDoorsFairyRouteRules.LocalStatus.class.isAnnotationPresent(Deprecated.class),
			"legacy local-selection types remain deprecated");
		try {
			check(BoxDoorsFairyRouteRules.TargetSource.class.getField("LOCAL_FALLBACK").isAnnotationPresent(Deprecated.class)
				&& BoxDoorsFairyRouteRules.TargetSource.class.getField("LOCAL_NONE").isAnnotationPresent(Deprecated.class)
				&& BoxDoorsFairyRouteRules.TargetSource.class.getField("LOCAL_AMBIGUOUS").isAnnotationPresent(Deprecated.class),
				"legacy local-selection target sources remain deprecated");
		} catch (NoSuchFieldException missingLegacySource) {
			check(false, "legacy local-selection target sources remain deprecated");
		}
	}

	private static void routeRecordValueSemantics() {
		BoxDoorsFairyRouteRules.MapCellResolution missingReason =
			new BoxDoorsFairyRouteRules.MapCellResolution(-1, null);
		check(missingReason.rejectionReason().equals("unknown") && !missingReason.available(),
			"the route API normalizes a missing map-cell reason and rejects its invalid cell");
		BoxDoorsFairyRouteRules.MapCellResolution normalizedReason =
			new BoxDoorsFairyRouteRules.MapCellResolution(-1, "unknown");
		check(missingReason.equals(normalizedReason) && missingReason.hashCode() == normalizedReason.hashCode()
			&& missingReason.toString().equals("MapCellResolution[cell=-1, rejectionReason=unknown]"),
			"map-cell value semantics use the normalized constructor value");

		BoxDoorsFairyRouteRules.LocalSelection local = new BoxDoorsFairyRouteRules.LocalSelection(
			BoxDoorsFairyRouteRules.LocalStatus.SELECTED, null, 1, "none");
		BoxDoorsFairyRouteRules.LocalSelection sameLocal = new BoxDoorsFairyRouteRules.LocalSelection(
			BoxDoorsFairyRouteRules.LocalStatus.SELECTED, null, 1, "none");
		check(local.equals(sameLocal) && local.hashCode() == sameLocal.hashCode()
			&& local.toString().equals("LocalSelection[status=SELECTED, target=null, candidateCount=1, rejectionReason=none]")
			&& !local.equals(new BoxDoorsFairyRouteRules.LocalSelection(
				BoxDoorsFairyRouteRules.LocalStatus.NONE, null, 1, "none")),
			"legacy local-selection keeps record-style value semantics");

		BoxDoorsFairyRouteRules.TraceChoice choice = new BoxDoorsFairyRouteRules.TraceChoice(
			null, BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE, 1, "none");
		BoxDoorsFairyRouteRules.TraceChoice sameChoice = new BoxDoorsFairyRouteRules.TraceChoice(
			null, BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE, 1, "none");
		check(choice.equals(sameChoice) && choice.hashCode() == sameChoice.hashCode()
			&& choice.candidateCount() == choice.localCandidateCount()
			&& choice.toString().equals(
				"TraceChoice[target=null, source=MAP_ROUTE, localCandidateCount=1, rejectionReason=none]")
			&& !choice.equals(new BoxDoorsFairyRouteRules.TraceChoice(
				null, BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE, 1, "none")),
			"tracer choice keeps record-style value semantics and its candidate-count alias");

		List<Integer> targetFootprint = new ArrayList<>(List.of(9, 8));
		List<Integer> currentFootprint = new ArrayList<>(List.of(8, 7));
		BoxDoorsFairyRouteRules.Selection selection = selectionWithFootprints(targetFootprint, currentFootprint);
		targetFootprint.add(10);
		currentFootprint.clear();
		boolean immutable = false;
		try {
			selection.targetRoomFootprint().add(11);
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		check(selection.targetRoomFootprint().equals(List.of(9, 8))
			&& selection.currentRoomFootprint().equals(List.of(8, 7)) && immutable,
			"route footprints preserve input order in defensive immutable snapshots");
		BoxDoorsFairyRouteRules.Selection sameSelection = selectionWithFootprints(List.of(9, 8), List.of(8, 7));
		check(selection.equals(sameSelection) && selection.hashCode() == sameSelection.hashCode()
			&& selection.toString().equals("Selection[status=SELECTED_ENTRY, transition=ENTER_FAIRY, " +
				"currentMapCell=7, mapFromCell=8, mapToCell=9, fromCell=8, toCell=9, mapEdgeType=null, " +
				"expectedType=null, target=null, offsetX=0, offsetZ=0, targetRoomFootprint=[9, 8], " +
				"currentRoomFootprint=[8, 7], routeRoomCount=2, routeEdgeCount=1, routeState=unique, rejectionReason=none]")
			&& !selection.equals(selectionWithFootprints(List.of(9, 8), List.of(8, 6))),
			"route selection keeps record-style equality, hash code, and string form");

		BoxDoorsFairyRouteRules.Selection missingFootprints = new BoxDoorsFairyRouteRules.Selection(
			BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE, BoxDoorsFairyRouteRules.Transition.NONE,
			-1, -1, -1, -1, -1, null, null, null, 0, 0, null, null,
			0, 0, "incomplete", "unknown");
		check(missingFootprints.targetRoomFootprint().isEmpty()
			&& missingFootprints.currentRoomFootprint().isEmpty(),
			"missing route footprints normalize to empty lists");
		BoxDoorsFairyRouteRules.Selection nullableComponents = new BoxDoorsFairyRouteRules.Selection(
			null, null, 0, 0, 0, 0, 0, null, null, null, 0, 0, null, null, 0, 0, null, null);
		check(nullableComponents.status() == null && nullableComponents.transition() == null
			&& nullableComponents.mapEdgeType() == null && nullableComponents.expectedType() == null
			&& nullableComponents.target() == null && nullableComponents.routeState() == null
			&& nullableComponents.rejectionReason() == null,
			"route selection retains nullable non-list record components");
		BoxDoorsFairyRouteRules.LocalSelection nullableLocal =
			new BoxDoorsFairyRouteRules.LocalSelection(null, null, 0, null);
		BoxDoorsFairyRouteRules.TraceChoice nullableChoice =
			new BoxDoorsFairyRouteRules.TraceChoice(null, null, 0, null);
		check(nullableLocal.status() == null && nullableLocal.target() == null
			&& nullableLocal.rejectionReason() == null && nullableChoice.target() == null
			&& nullableChoice.source() == null && nullableChoice.rejectionReason() == null,
			"route compatibility values retain nullable record components");

		List<Integer> malformedFootprint = new ArrayList<>();
		malformedFootprint.add(null);
		boolean rejectsTargetNull = false;
		try {
			selectionWithFootprints(malformedFootprint, List.of());
		} catch (NullPointerException expected) {
			rejectsTargetNull = true;
		}
		boolean rejectsCurrentNull = false;
		try {
			selectionWithFootprints(List.of(), malformedFootprint);
		} catch (NullPointerException expected) {
			rejectsCurrentNull = true;
		}
		check(rejectsTargetNull && rejectsCurrentNull,
			"route footprint snapshots preserve List.copyOf null-element rejection");
	}

	private static BoxDoorsFairyRouteRules.Selection selectionWithFootprints(
		List<Integer> targetFootprint, List<Integer> currentFootprint) {
		return new BoxDoorsFairyRouteRules.Selection(
			BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY, BoxDoorsFairyRouteRules.Transition.ENTER_FAIRY,
			7, 8, 9, 8, 9, null, null, null, 0, 0, targetFootprint, currentFootprint,
			2, 1, "unique", "none");
	}

	private static void moduleLocalRecordValueSemantics() {
		BoxDoorsModule.BloodRushTabClassResolution classResolution =
			new BoxDoorsModule.BloodRushTabClassResolution(DungeonClass.TANK, "resolved");
		BoxDoorsModule.BloodRushTabClassResolution sameClassResolution =
			new BoxDoorsModule.BloodRushTabClassResolution(DungeonClass.TANK, "resolved");
		check(classResolution.equals(sameClassResolution)
			&& classResolution.hashCode() == sameClassResolution.hashCode()
			&& classResolution.playerClass() == DungeonClass.TANK
			&& "resolved".equals(classResolution.resolution()),
			"Blood Rush class resolution keeps the old record's component accessors and value equality");
		check(classResolution.toString().equals(
			"BloodRushTabClassResolution[playerClass=TANK, resolution=resolved]"),
			"Blood Rush class resolution keeps the old record component names and string form");
		check(!classResolution.equals(new BoxDoorsModule.BloodRushTabClassResolution(DungeonClass.MAGE, "resolved"))
			&& !classResolution.equals(new BoxDoorsModule.BloodRushTabClassResolution(DungeonClass.TANK, "other")),
			"each Blood Rush class resolution component participates in equality");
		BoxDoorsModule.BloodRushTabClassResolution nullableResolution =
			new BoxDoorsModule.BloodRushTabClassResolution(null, null);
		check(nullableResolution.playerClass() == null && nullableResolution.resolution() == null
			&& nullableResolution.toString().equals(
				"BloodRushTabClassResolution[playerClass=null, resolution=null]"),
			"Blood Rush class resolution preserves nullable record components");

		DungeonGridRules.Cell physicalCell = new DungeonGridRules.Cell(1, 1, -168, -168);
		DungeonGridRules.Cell mapCell = new DungeonGridRules.Cell(2, 3, -120, -96);
		DungeonGridRules.Cell resolvedCell = new DungeonGridRules.Cell(1, 1, -168, -168);
		DungeonMapGeometry.CellOffset origin = new DungeonMapGeometry.CellOffset(0, 0);
		DungeonMapGeometry.CellOffset east = new DungeonMapGeometry.CellOffset(1, 0);
		ArrayList<DungeonMapGeometry.CellOffset> mutableOffsets = new ArrayList<>(List.of(origin, east));
		BoxDoorsModule.TracerRoomContext tracerContext = new BoxDoorsModule.TracerRoomContext(
			physicalCell, mapCell, resolvedCell, mutableOffsets, null, null);
		mutableOffsets.add(new DungeonMapGeometry.CellOffset(2, 0));
		BoxDoorsModule.TracerRoomContext sameTracerContext = new BoxDoorsModule.TracerRoomContext(
			physicalCell, mapCell, resolvedCell, List.of(origin, east), "unknown", null);
		boolean immutableOffsets = false;
		try {
			tracerContext.footprintOffsets().add(new DungeonMapGeometry.CellOffset(3, 0));
		} catch (UnsupportedOperationException expected) {
			immutableOffsets = true;
		}
		check(tracerContext.footprintOffsets().equals(List.of(origin, east))
			&& immutableOffsets && "unknown".equals(tracerContext.source())
			&& tracerContext.physicalCell().equals(physicalCell)
			&& tracerContext.mapCell().equals(mapCell)
			&& tracerContext.cell().equals(resolvedCell)
			&& tracerContext.rejectionReason() == null,
			"tracer room accessors retain defensive list copying and null-source normalization");
		check(tracerContext.equals(sameTracerContext)
			&& tracerContext.hashCode() == sameTracerContext.hashCode(),
			"tracer room equality and hash code use the normalized source and copied footprint list");
		check(tracerContext.toString().equals("TracerRoomContext[physicalCell=" +
			"Cell[componentX=1, componentZ=1, baseX=-168, baseZ=-168], " +
			"mapCell=Cell[componentX=2, componentZ=3, baseX=-120, baseZ=-96], " +
			"cell=Cell[componentX=1, componentZ=1, baseX=-168, baseZ=-168], " +
			"footprintOffsets=[CellOffset[x=0, z=0], CellOffset[x=1, z=0]], " +
			"source=unknown, rejectionReason=null]"),
			"tracer room context keeps the old record component names, order, and string form: " + tracerContext);
		check(!tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
			new DungeonGridRules.Cell(0, 1, -216, -168), mapCell, resolvedCell, List.of(origin, east), "unknown", null))
			&& !tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
				physicalCell, new DungeonGridRules.Cell(3, 3, -72, -96), resolvedCell,
				List.of(origin, east), "unknown", null))
			&& !tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
				physicalCell, mapCell, new DungeonGridRules.Cell(0, 1, -216, -168),
				List.of(origin, east), "unknown", null))
			&& !tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
				physicalCell, mapCell, resolvedCell, List.of(origin), "unknown", null))
			&& !tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
				physicalCell, mapCell, resolvedCell, List.of(origin, east), "map-fallback", null))
			&& !tracerContext.equals(new BoxDoorsModule.TracerRoomContext(
				physicalCell, mapCell, resolvedCell, List.of(origin, east), "unknown", "conflict")),
			"each tracer room context component participates in equality");
		BoxDoorsModule.TracerRoomContext emptyNormalized = new BoxDoorsModule.TracerRoomContext(
			null, null, null, null, null, null);
		BoxDoorsModule.TracerRoomContext emptyExplicit = new BoxDoorsModule.TracerRoomContext(
			null, null, null, List.of(), "unknown", null);
		check(emptyNormalized.equals(emptyExplicit)
			&& emptyNormalized.hashCode() == emptyExplicit.hashCode()
			&& emptyNormalized.footprintOffsets().isEmpty()
			&& "unknown".equals(emptyNormalized.source()),
			"null tracer footprint and source components normalize to the same empty record value");
	}

	private static void moduleLocalRecordAbi() {
		checkRecordShape(BoxDoorsModule.BloodRushTabClassResolution.class,
			"playerClass,resolution", DungeonClass.class, String.class);
		checkConstructorAndAccessorShape(BoxDoorsModule.TracerRoomContext.class,
			"physicalCell,mapCell,cell,footprintOffsets,source,rejectionReason",
			DungeonGridRules.Cell.class, DungeonGridRules.Cell.class, DungeonGridRules.Cell.class,
			List.class, String.class, String.class);
	}

	private static void checkRecordShape(Class<?> recordType, String expectedComponents,
		Class<?>... expectedComponentTypes) {
		check(recordType.isRecord(), recordType.getSimpleName() + " retains Java record metadata");
		java.lang.reflect.RecordComponent[] components = recordType.getRecordComponents();
		String actualComponents = java.util.Arrays.stream(components)
			.map(java.lang.reflect.RecordComponent::getName)
			.collect(java.util.stream.Collectors.joining(","));
		check(actualComponents.equals(expectedComponents), recordType.getSimpleName() + " keeps its record component ABI");
		Class<?>[] componentTypes = java.util.Arrays.stream(components)
			.map(java.lang.reflect.RecordComponent::getType)
			.toArray(Class<?>[]::new);
		check(expectedComponentTypes.length == 0
			|| java.util.Arrays.equals(componentTypes, expectedComponentTypes),
			recordType.getSimpleName() + " keeps its record component descriptors");
		try {
			java.lang.reflect.Constructor<?> canonical = recordType.getDeclaredConstructor(componentTypes);
			check(java.util.Arrays.equals(canonical.getParameterTypes(), componentTypes),
				recordType.getSimpleName() + " keeps its canonical constructor descriptor");
		} catch (NoSuchMethodException missingCanonicalConstructor) {
			check(false, recordType.getSimpleName() + " keeps its canonical constructor descriptor");
		}
		for (int index = 0; index < components.length; index++) {
			java.lang.reflect.RecordComponent component = components[index];
			java.lang.reflect.Method accessor = component.getAccessor();
			check(accessor.getName().equals(component.getName()) && accessor.getParameterCount() == 0
				&& accessor.getReturnType().equals(componentTypes[index]),
				recordType.getSimpleName() + " keeps accessor descriptor for " + component.getName());
		}
	}

	private static void checkConstructorAndAccessorShape(Class<?> type, String expectedComponents,
		Class<?>... expectedComponentTypes) {
		String[] componentNames = expectedComponents.split(",");
		check(componentNames.length == expectedComponentTypes.length,
			type.getSimpleName() + " check declares matching component names and descriptors");
		check(java.lang.reflect.Modifier.isPublic(type.getModifiers()),
			type.getSimpleName() + " stays a public nested API type");
		try {
			java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(expectedComponentTypes);
			check(java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
				&& java.util.Arrays.equals(constructor.getParameterTypes(), expectedComponentTypes),
				type.getSimpleName() + " keeps its constructor descriptor");
			for (int index = 0; index < componentNames.length; index++) {
				java.lang.reflect.Method accessor = type.getDeclaredMethod(componentNames[index]);
				check(java.lang.reflect.Modifier.isPublic(accessor.getModifiers())
					&& accessor.getParameterCount() == 0
					&& accessor.getReturnType().equals(expectedComponentTypes[index]),
					type.getSimpleName() + " keeps accessor descriptor for " + componentNames[index]);
			}
		} catch (NoSuchMethodException missingAbiMember) {
			check(false, type.getSimpleName() + " keeps its constructor and accessor descriptors");
		}
	}

	private static void checkStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		checkStaticMethod(name, returnType, parameterTypes, false);
	}

	private static void checkStaticMethod(String name, Class<?> returnType,
		Class<?>[] parameterTypes, boolean deprecated) {
		try {
			java.lang.reflect.Method method = BoxDoorsFairyRouteRules.class.getDeclaredMethod(name, parameterTypes);
			check(java.lang.reflect.Modifier.isPublic(method.getModifiers())
				&& java.lang.reflect.Modifier.isStatic(method.getModifiers())
				&& method.getReturnType().equals(returnType)
				&& (!deprecated || method.isAnnotationPresent(Deprecated.class)),
				"route API keeps public static method " + name + java.util.Arrays.toString(parameterTypes));
		} catch (NoSuchMethodException missingMethod) {
			check(false, "route API keeps public static method " + name + java.util.Arrays.toString(parameterTypes));
		}
	}

	private static void bloodRushTabClassRows() {
		checkBloodRushTabClass("[12] [MVP+] ExamplePlayer (Tank 36)", DungeonClass.TANK,
			"a numbered tab row resolves Tank");
		checkBloodRushTabClass("[MVP+] ExamplePlayer (Healer 42)", DungeonClass.HEALER,
			"a tab row without a numbered prefix resolves Healer");
		checkBloodRushTabClass("ExamplePlayer (Mage)", DungeonClass.MAGE,
			"a plain tab row resolves Mage without a level");
		checkBloodRushTabClass("[VIP] ExamplePlayer (Berserk 38)", DungeonClass.BERSERK,
			"a ranked tab row resolves Berserk");
		checkBloodRushTabClass("[MVP++] ExamplePlayer (Archer 41)", DungeonClass.ARCHER,
			"a ranked tab row resolves Archer");
		checkBloodRushTabClass("\u2003[MVP+] ExamplePlayer (Mage 42)\u2003", DungeonClass.MAGE,
			"tab class parsing preserves Java String.strip handling for Unicode whitespace");

		var nonBreakingSpaceWrapped = BoxDoorsModule.parseBloodRushClassFromTabLine(
			"\u00a0[MVP+] ExamplePlayer (Tank 36)\u00a0");
		check(nonBreakingSpaceWrapped.playerClass() == null
			&& nonBreakingSpaceWrapped.resolution().equals("tab-row-unrecognized"),
			"tab parsing keeps Java String.strip behavior for nonbreaking spaces");
		var nonBreakingSpaceClass = BoxDoorsModule.parseBloodRushClassFromTabLine(
			"ExamplePlayer (\u00a0Tank 36)");
		check(nonBreakingSpaceClass.playerClass() == null
			&& nonBreakingSpaceClass.resolution().equals("tab-class-unrecognized"),
			"class parsing does not trim nonbreaking spaces that Java String.strip preserves");

		String paddedBloodDoorOpen = ChatStringSemantics.INSTANCE.trim(
			geiler.addons.client.tree.ChatText.plain("\u0001The BLOOD DOOR has been opened!\u0001"));
		check(paddedBloodDoorOpen.equalsIgnoreCase("The BLOOD DOOR has been opened!"),
			"Blood door-open chat normalization retains Java String.trim handling for low controls");

		var dead = BoxDoorsModule.parseBloodRushClassFromTabLine("[MVP+] ExamplePlayer (DEAD)");
		check(dead.playerClass() == null && dead.resolution().equals("resolved-dead-row"),
			"dead tab rows remain unresolved for the fail-closed class filter");
		var unknownClass = BoxDoorsModule.parseBloodRushClassFromTabLine("ExamplePlayer (Paladin 20)");
		check(unknownClass.playerClass() == null && unknownClass.resolution().equals("tab-class-unrecognized"),
			"unknown tab classes remain unresolved");
		var missingClass = BoxDoorsModule.parseBloodRushClassFromTabLine("[MVP+] ExamplePlayer");
		check(missingClass.playerClass() == null && missingClass.resolution().equals("tab-row-unrecognized"),
			"tab rows without a class suffix remain unresolved");
	}

	private static void checkBloodRushTabClass(String row, DungeonClass expected, String message) {
		var parsed = BoxDoorsModule.parseBloodRushClassFromTabLine(row);
		check(parsed.playerClass() == expected && parsed.resolution().equals("resolved"), message);
	}

	private static void fairyRouteSelection() {
		FairyRouteFixture fixture = fairyRouteFixture();
		BoxDoorsFairyRouteRules.MapCellResolution markerInside = BoxDoorsFairyRouteRules.mapCellAtMarker(
			fixture.map(), new DungeonMapGeometry.MapPoint(52, 52));
		BoxDoorsFairyRouteRules.MapCellResolution markerInGap = BoxDoorsFairyRouteRules.mapCellAtMarker(
			fixture.map(), new DungeonMapGeometry.MapPoint(62, 52));
		List<OdinDungeonMapGrid.Tile> unknownTiles = new ArrayList<>(fixture.map().tiles());
		unknownTiles.set(7, new OdinDungeonMapGrid.Tile(7, 1, 1, OdinDungeonMapGrid.MapRoomType.UNKNOWN,
			OdinDungeonMapGrid.MapRoomType.UNKNOWN.mapColor(), OdinDungeonMapGrid.MapRoomType.UNKNOWN.mapColor(),
			OdinDungeonMapGrid.Checkmark.UNDISCOVERED));
		OdinDungeonMapGrid.ScanResult unknownTileMap = new OdinDungeonMapGrid.ScanResult(true,
			fixture.map().layout(), unknownTiles, fixture.map().rooms(), fixture.map().doors(), "");
		BoxDoorsFairyRouteRules.MapCellResolution markerOnUnknown = BoxDoorsFairyRouteRules.mapCellAtMarker(
			unknownTileMap, new DungeonMapGeometry.MapPoint(52, 52));
		OdinDungeonMapGrid.ScanResult offsetLayout = new OdinDungeonMapGrid.ScanResult(true,
			new OdinDungeonMapGrid.Layout(true, 18, 20, 2, 2, null), fixture.map().tiles(),
			fixture.map().rooms(), fixture.map().doors(), "");
		BoxDoorsFairyRouteRules.MapCellResolution markerOutsideGrid = BoxDoorsFairyRouteRules.mapCellAtMarker(
			offsetLayout, new DungeonMapGeometry.MapPoint(122, 22));
		BoxDoorsFairyRouteRules.MapCellResolution markerOutOfBounds = BoxDoorsFairyRouteRules.mapCellAtMarker(
			fixture.map(), new DungeonMapGeometry.MapPoint(154, 22));
		check(markerInside.available() && markerInside.cell() == 7
			&& markerInside.rejectionReason().equals("inside-recognized-tile")
			&& markerInGap.cell() == -1 && markerInGap.rejectionReason().equals("player-map-marker-in-room-gap")
			&& !markerInGap.available()
			&& markerOnUnknown.cell() == -1
			&& markerOnUnknown.rejectionReason().equals("player-map-marker-on-unknown-tile")
			&& markerOutsideGrid.cell() == -1
			&& markerOutsideGrid.rejectionReason().equals("player-map-marker-outside-grid")
			&& markerOutOfBounds.cell() == -1
			&& markerOutOfBounds.rejectionReason().equals("player-map-marker-out-of-bounds"),
			"map marker conversion accepts tile interiors and rejects gaps, unknown tiles, and invalid positions");
		check(BoxDoorsFairyRouteRules.mapToWorldCell(7, 1, 0) == 8
			&& BoxDoorsFairyRouteRules.mapToWorldCell(7, -2, 0) == -1
			&& BoxDoorsFairyRouteRules.mapToWorldCell(36, 0, 0) == -1,
			"map-to-world conversion preserves valid offsets and rejects invalid or out-of-grid cells");

		BoxDoorsFairyRouteRules.Selection entry = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1, fixture.observedDoors());
		check(entry.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY
			&& entry.transition() == BoxDoorsFairyRouteRules.Transition.ENTER_FAIRY
			&& entry.target() == fixture.entryDoor()
			&& entry.mapFromCell() == fixture.entryFromCell() && entry.mapToCell() == fixture.fairyCell()
			&& entry.fromCell() == fixture.entryFromCell() + 1 && entry.toCell() == fixture.fairyCell() + 1
			&& entry.offsetX() == 1 && entry.offsetZ() == 0
			&& entry.targetRoomFootprint().equals(List.of(9))
			&& entry.currentRoomFootprint().equals(List.of(2, 8))
			&& entry.diagnostic().equals("route=unique,rooms=6,edges=5,transform=offset(1,0)"
				+ ",currentMap=1,mapEdge=7-8,worldEdge=8-9,mapType=fairy,expected=wither"
				+ ",status=selected_entry,transition=enter_fairy,observed=wither,rejection=none"),
			"a multi-cell predecessor selects the exact translated Wither edge from its non-player segment");
		BoxDoorsFairyRouteRules.TraceChoice entryChoice = BoxDoorsFairyRouteRules.chooseTracerTarget(
			entry, fixture.observedDoors(), Set.of());
		check(entryChoice.source() == BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE
			&& entryChoice.target() == fixture.entryDoor(),
			"the room before Fairy uses the exact observed closed Wither route edge");
		BoxDoorsFairyRouteRules.Selection markerRoute = BoxDoorsFairyRouteRules.select(
			fixture.map(), markerInside.cell(), 8, fixture.observedDoors());
		check(markerRoute.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY
			&& markerRoute.target() == fixture.entryDoor(),
			"a marker inside the predecessor room tile resolves to its exact translated Fairy-entry Wither door");
		var entryCenter = BoxDoorsFairyRouteRules.fairyRoomCenter(entry.targetRoomFootprint());
		check(entryCenter != null && entryCenter.x == -88.5 && entryCenter.y == 70.0 && entryCenter.z == -152.5
			&& BoxDoorsFairyRouteRules.fairyRoomCenter(List.of(9, 10)).x == -72.5,
			"the Fairy waypoint centers the translated room footprint, including multi-cell rooms");
		List<OdinDungeonMapGrid.Room> multiFairyRooms = new ArrayList<>(fixture.rooms());
		multiFairyRooms.replaceAll(segment -> segment.type() == OdinDungeonMapGrid.MapRoomType.FAIRY
			? BoxDoorsChecks.room(List.of(8, 9), OdinDungeonMapGrid.MapRoomType.FAIRY) : segment);
		BoxDoorsFairyRouteRules.Selection multiFairyEntry = BoxDoorsFairyRouteRules.select(
			map(multiFairyRooms, fixture.mapDoors()), fixture.predecessor(),
			fixture.predecessor() + 1, fixture.observedDoors());
		check(multiFairyEntry.targetRoomFootprint().equals(List.of(9, 10))
			&& BoxDoorsFairyRouteRules.fairyRoomCenter(multiFairyEntry.targetRoomFootprint()).x == -72.5,
			"the route transforms every segment of a grouped Fairy room before centering its waypoint");

		BoxDoorsFairyRouteRules.Selection exit = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.fairyCell(), fixture.fairyCell() + 1, fixture.observedDoors());
		check(exit.status() == BoxDoorsFairyRouteRules.Status.SELECTED_EXIT
			&& exit.transition() == BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY
			&& exit.target() == fixture.exitDoor()
			&& exit.fromCell() == fixture.fairyCell() + 1
			&& exit.currentRoomFootprint().equals(List.of(fixture.fairyCell() + 1))
			&& exit.toCell() == fixture.afterFairy() + 1,
			"the Fairy room selects its exact observed Wither exit edge for a vertical map edge");
		BoxDoorsFairyRouteRules.Selection multiFairyExit = BoxDoorsFairyRouteRules.select(
			map(multiFairyRooms, fixture.mapDoors()), fixture.fairyCell() + 1,
			fixture.fairyCell() + 2, fixture.observedDoors());
		check(multiFairyExit.target() == fixture.exitDoor()
			&& multiFairyExit.currentRoomFootprint().equals(List.of(9, 10))
			&& multiFairyExit.fromCell() == 9 && multiFairyExit.toCell() == 15,
			"a non-origin segment in a multi-cell Fairy room keeps its translated exact exit edge");

		BoxDoorsFairyRouteRules.Selection afterFairy = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.afterFairy(), fixture.afterFairy() + 1, fixture.observedDoors());
		check(afterFairy.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ROUTE_STEP
			&& afterFairy.target() == fixture.nextDoor()
			&& afterFairy.transition() == BoxDoorsFairyRouteRules.Transition.FOLLOW_ROUTE,
			"the route continues with the exact next Wither door after leaving Fairy");

		FairyRouteFixture decoy = withDecoyBranch(fixture);
		BoxDoorsFairyRouteRules.Selection avoidsDecoy = BoxDoorsFairyRouteRules.select(
			decoy.map(), decoy.predecessor(), decoy.predecessor() + 1,
			append(decoy.observedDoors(), observedWither(4, 3, 99)));
		check(avoidsDecoy.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY
			&& avoidsDecoy.target() == fixture.entryDoor(),
			"a dead-end Wither decoy does not replace the unique Entrance-to-Blood route edge");

		List<DungeonDoorTracker.Door> withoutEntry = fixture.observedDoors().stream()
			.filter(door -> door != fixture.entryDoor()).toList();
		BoxDoorsFairyRouteRules.Selection unobserved = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1, withoutEntry);
		check(unobserved.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING
			&& unobserved.target() == null && unobserved.fromCell() == 8 && unobserved.toCell() == 9
			&& unobserved.expectedType() == DungeonDoorTracker.Type.WITHER,
			"an unobserved exact route edge remains missing and does not select a nearby fallback");
		BoxDoorsFairyRouteRules.Selection duplicateObservedEdge = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1,
			append(fixture.observedDoors(), fixture.entryDoor()));
		check(duplicateObservedEdge.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_AMBIGUOUS
			&& BoxDoorsFairyRouteRules.chooseTracerTarget(duplicateObservedEdge, fixture.observedDoors(), Set.of()).source()
				== BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE,
			"duplicate observed records for the exact expected edge remain hidden and do not activate local fallback");
		check(BoxDoorsFairyRouteRules.select(fixture.map(), fixture.predecessor(), -1,
			fixture.observedDoors()).status() == BoxDoorsFairyRouteRules.Status.TRANSFORM_UNAVAILABLE,
			"missing calibrated world coordinates fail closed before target selection");
		check(BoxDoorsFairyRouteRules.select(null, fixture.predecessor(), fixture.predecessor() + 1,
			fixture.observedDoors()).status() == BoxDoorsFairyRouteRules.Status.MAP_UNAVAILABLE,
			"an unavailable map reports no route target");

		OdinDungeonMapGrid.ScanResult incomplete = map(fixture.rooms().stream()
			.filter(room -> room.type() != OdinDungeonMapGrid.MapRoomType.BLOOD).toList(), fixture.mapDoors());
		BoxDoorsFairyRouteRules.Selection noBloodRoom = BoxDoorsFairyRouteRules.select(
			incomplete, fixture.predecessor(), fixture.predecessor() + 1, fixture.observedDoors());
		check(noBloodRoom.status() == BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE
			&& noBloodRoom.target() == null && noBloodRoom.diagnostic().contains("rejection=Entrance-groups=1;Blood-groups=0"),
			"an incomplete map without one Blood endpoint fails closed");

		List<OdinDungeonMapGrid.Room> ambiguousRooms = new ArrayList<>(fixture.rooms());
		ambiguousRooms.add(room(6, OdinDungeonMapGrid.MapRoomType.NORMAL));
		List<OdinDungeonMapGrid.Door> ambiguousDoors = new ArrayList<>(fixture.mapDoors());
		ambiguousDoors.add(mapDoor(0, 6, OdinDungeonMapGrid.DoorType.NORMAL));
		ambiguousDoors.add(mapDoor(6, 7, OdinDungeonMapGrid.DoorType.NORMAL));
		BoxDoorsFairyRouteRules.Selection ambiguous = BoxDoorsFairyRouteRules.select(
			map(ambiguousRooms, ambiguousDoors), fixture.predecessor(), fixture.predecessor() + 1,
			fixture.observedDoors());
		check(ambiguous.status() == BoxDoorsFairyRouteRules.Status.ROUTE_AMBIGUOUS
			&& ambiguous.target() == null && ambiguous.routeState().equals("ambiguous"),
			"a second simple Entrance-to-Blood branch makes the route ambiguous and suppresses tracers");
		DungeonDoorTracker.Door localFallback = observedWither(8, 14, 98);
		check(BoxDoorsFairyRouteRules.chooseTracerTarget(ambiguous, List.of(localFallback), null,
			ignored -> BloodDoorTraceRules.DoorPresence.CLOSED).target()
			== localFallback,
			"an ambiguous map route uses tracker order instead of room-adjacency fallback");

		BoxDoorsFairyRouteRules.Selection openEntry = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1, fixture.observedDoors(), door ->
				door == fixture.entryDoor() ? BloodDoorTraceRules.DoorPresence.OPEN
					: BloodDoorTraceRules.DoorPresence.CLOSED);
		check(openEntry.status() == BoxDoorsFairyRouteRules.Status.SELECTED_EXIT
			&& openEntry.transition() == BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY
			&& openEntry.target() == fixture.exitDoor(),
			"opening the Fairy entrance advances the persistent tracer to the next closed route door");

		BoxDoorsFairyRouteRules.Selection unknownEntry = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1, fixture.observedDoors(), door ->
				door == fixture.entryDoor() ? BloodDoorTraceRules.DoorPresence.UNKNOWN
					: BloodDoorTraceRules.DoorPresence.CLOSED);
		check(unknownEntry.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_STATE_UNKNOWN
			&& unknownEntry.target() == fixture.entryDoor()
			&& BoxDoorsFairyRouteRules.chooseTracerTarget(unknownEntry, fixture.observedDoors(), Set.of()).source()
				== BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE,
			"unknown route-door state waits for an exact prompt scan instead of choosing a nearby edge");
	}

	private static FairyRouteFixture fairyRouteFixture() {
		List<OdinDungeonMapGrid.Room> rooms = new ArrayList<>(List.of(
			room(0, OdinDungeonMapGrid.MapRoomType.ENTRANCE),
			room(List.of(1, 7), OdinDungeonMapGrid.MapRoomType.NORMAL),
			room(8, OdinDungeonMapGrid.MapRoomType.FAIRY),
			room(14, OdinDungeonMapGrid.MapRoomType.NORMAL),
			room(20, OdinDungeonMapGrid.MapRoomType.NORMAL),
			room(21, OdinDungeonMapGrid.MapRoomType.BLOOD)));
		List<OdinDungeonMapGrid.Door> mapDoors = new ArrayList<>(List.of(
			mapDoor(0, 1, OdinDungeonMapGrid.DoorType.WITHER),
			mapDoor(1, 7, OdinDungeonMapGrid.DoorType.NORMAL),
			mapDoor(7, 8, OdinDungeonMapGrid.DoorType.FAIRY),
			mapDoor(8, 14, OdinDungeonMapGrid.DoorType.WITHER),
			mapDoor(14, 20, OdinDungeonMapGrid.DoorType.WITHER),
			mapDoor(20, 21, OdinDungeonMapGrid.DoorType.BLOOD)));
		DungeonDoorTracker.Door entry = observedWither(9, 8, 1);
		DungeonDoorTracker.Door exit = observedWither(15, 9, 2);
		DungeonDoorTracker.Door next = observedWither(21, 15, 3);
		DungeonDoorTracker.Door blood = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD,
			22, 21, 422, 70, -422, 3, 4, 3);
		return new FairyRouteFixture(map(rooms, mapDoors), List.copyOf(rooms), List.copyOf(mapDoors),
			1, 7, 8, 14, entry, exit, next, List.of(entry, exit, next, blood));
	}

	private static FairyRouteFixture withDecoyBranch(FairyRouteFixture fixture) {
		List<OdinDungeonMapGrid.Room> rooms = new ArrayList<>(fixture.rooms());
		rooms.add(room(2, OdinDungeonMapGrid.MapRoomType.NORMAL));
		rooms.add(room(3, OdinDungeonMapGrid.MapRoomType.PUZZLE));
		List<OdinDungeonMapGrid.Door> doors = new ArrayList<>(fixture.mapDoors());
		doors.add(mapDoor(1, 2, OdinDungeonMapGrid.DoorType.NORMAL));
		doors.add(mapDoor(2, 3, OdinDungeonMapGrid.DoorType.WITHER));
		return new FairyRouteFixture(map(rooms, doors), List.copyOf(rooms), List.copyOf(doors),
			fixture.predecessor(), fixture.entryFromCell(), fixture.fairyCell(), fixture.afterFairy(),
			fixture.entryDoor(), fixture.exitDoor(), fixture.nextDoor(), fixture.observedDoors());
	}

	private static void directFairyBloodExitSelection() {
		int entrance = 0, fairyCell = 1, bloodCell = 7;
		List<OdinDungeonMapGrid.Room> rooms = List.of(room(entrance, OdinDungeonMapGrid.MapRoomType.ENTRANCE),
			room(fairyCell, OdinDungeonMapGrid.MapRoomType.FAIRY), room(bloodCell, OdinDungeonMapGrid.MapRoomType.BLOOD));
		List<OdinDungeonMapGrid.Door> mapDoors = List.of(
			mapDoor(entrance, fairyCell, OdinDungeonMapGrid.DoorType.FAIRY),
			mapDoor(fairyCell, bloodCell, OdinDungeonMapGrid.DoorType.BLOOD));
		OdinDungeonMapGrid.ScanResult map = map(rooms, mapDoors);
		DungeonDoorTracker.Door entryDoor = observedWither(2, 1, 11);
		DungeonDoorTracker.Door bloodDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD,
			8, 2, 411, 70, -411, 3, 4, 3);
		List<DungeonDoorTracker.Door> observed = List.of(entryDoor, bloodDoor);

		BoxDoorsFairyRouteRules.Selection entry = BoxDoorsFairyRouteRules.select(map, entrance, entrance + 1, observed);
		check(entry.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ENTRY && entry.target() == entryDoor,
			"the Entrance route selects its exact translated Fairy-entry Wither edge");
		BoxDoorsFairyRouteRules.Selection bloodExit = BoxDoorsFairyRouteRules.select(
			map, fairyCell, fairyCell + 1, observed);
		check(bloodExit.status() == BoxDoorsFairyRouteRules.Status.SELECTED_BLOOD_EXIT
			&& bloodExit.transition() == BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD
			&& bloodExit.target() == bloodDoor && bloodExit.fromCell() == fairyCell + 1
			&& bloodExit.toCell() == bloodCell + 1
			&& bloodExit.diagnostic().contains("expected=blood")
			&& bloodExit.diagnostic().contains("observed=blood"),
			"a direct Fairy-to-Blood edge joins to its observed translated Blood door record");
		BoxDoorsFairyRouteRules.Selection missingBlood = BoxDoorsFairyRouteRules.select(
			map, fairyCell, fairyCell + 1, List.of(entryDoor));
		check(missingBlood.status() == BoxDoorsFairyRouteRules.Status.OBSERVED_EDGE_MISSING
			&& missingBlood.transition() == BoxDoorsFairyRouteRules.Transition.EXIT_FAIRY_TO_BLOOD
			&& missingBlood.mapEdgeType() == OdinDungeonMapGrid.DoorType.BLOOD
			&& missingBlood.expectedType() == DungeonDoorTracker.Type.BLOOD && missingBlood.target() == null,
			"a direct Fairy-to-Blood edge without an exact observed record fails closed");
	}

	private static void normalIntermediaryRoute() {
		List<OdinDungeonMapGrid.Room> rooms = List.of(
			room(0, OdinDungeonMapGrid.MapRoomType.ENTRANCE),
			room(1, OdinDungeonMapGrid.MapRoomType.NORMAL),
			room(2, OdinDungeonMapGrid.MapRoomType.NORMAL),
			room(3, OdinDungeonMapGrid.MapRoomType.BLOOD));
		List<OdinDungeonMapGrid.Door> mapDoors = List.of(
			mapDoor(0, 1, OdinDungeonMapGrid.DoorType.NORMAL),
			mapDoor(1, 2, OdinDungeonMapGrid.DoorType.NORMAL),
			mapDoor(2, 3, OdinDungeonMapGrid.DoorType.BLOOD));
		DungeonDoorTracker.Door reversedBlood = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD,
			5, 4, 300, 70, -300, 3, 4, 3);
		BoxDoorsFairyRouteRules.Selection route = BoxDoorsFairyRouteRules.select(
			map(rooms, mapDoors), 2, 4, List.of(reversedBlood));
		check(route.status() == BoxDoorsFairyRouteRules.Status.SELECTED_ROUTE_STEP
			&& route.expectedType() == DungeonDoorTracker.Type.BLOOD && route.target() == reversedBlood
			&& route.fromCell() == 4 && route.toCell() == 5
			&& BoxDoorsFairyRouteRules.chooseTracerTarget(route, List.of(), reversedBlood).source()
				== BoxDoorsFairyRouteRules.TargetSource.MAP_ROUTE,
			"the room before Blood selects its exact translated observed Blood edge after traversing NORMAL connectors");
	}

	private static void trackerFallbackSelection() {
		DungeonDoorTracker.Door firstWither = observedWither(8, 14, 71);
		DungeonDoorTracker.Door secondWither = observedWither(14, 20, 72);
		DungeonDoorTracker.Door blood = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.BLOOD,
			20, 26, 301, 70, -301, 3, 4, 3);
		var advancesPastOpen = BoxDoorsFairyRouteRules.chooseTracerTarget(null,
			List.of(firstWither, secondWither), blood, door -> door == firstWither
				? BloodDoorTraceRules.DoorPresence.OPEN : BloodDoorTraceRules.DoorPresence.CLOSED);
		check(advancesPastOpen.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE
			&& advancesPastOpen.target() == secondWither && advancesPastOpen.candidateCount() == 2,
			"tracker fallback advances past an authoritatively open Wither door in route order");

		var holdsUnknown = BoxDoorsFairyRouteRules.chooseTracerTarget(null,
			List.of(firstWither, secondWither), blood, door -> door == firstWither
				? BloodDoorTraceRules.DoorPresence.UNKNOWN : BloodDoorTraceRules.DoorPresence.CLOSED);
		check(holdsUnknown.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE
			&& holdsUnknown.target() == firstWither && holdsUnknown.rejectionReason().contains("unknown"),
			"an unknown first Wither state holds the tracker target instead of skipping to later Wither or Blood doors");

		var fallsBackToBlood = BoxDoorsFairyRouteRules.chooseTracerTarget(null,
			List.of(firstWither, secondWither), blood, ignored -> BloodDoorTraceRules.DoorPresence.OPEN);
		check(fallsBackToBlood.target() == null
			&& fallsBackToBlood.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_ROUTE_COMPLETE,
			"an entirely open tracker route does not select an open Blood entrance");
		var bloodAfterWithers = BoxDoorsFairyRouteRules.chooseTracerTarget(null,
			List.of(firstWither, secondWither), blood, door -> door == blood
				? BloodDoorTraceRules.DoorPresence.CLOSED : BloodDoorTraceRules.DoorPresence.OPEN);
		check(bloodAfterWithers.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_BLOOD_DOOR
			&& bloodAfterWithers.target() == blood,
			"tracker fallback selects Blood only after every ordered Wither door is open");
		var holdsUnknownBlood = BoxDoorsFairyRouteRules.chooseTracerTarget(null,
			List.of(firstWither, secondWither), blood, door -> door == blood
			? BloodDoorTraceRules.DoorPresence.UNKNOWN : BloodDoorTraceRules.DoorPresence.OPEN);
		check(holdsUnknownBlood.target() == blood
			&& holdsUnknownBlood.rejectionReason().contains("unknown"),
			"an unknown Blood state remains the selected endpoint without rendering or advancing");

		OdinDungeonMapGrid.ScanResult incomplete = map(
			List.of(room(0, OdinDungeonMapGrid.MapRoomType.ENTRANCE),
				room(1, OdinDungeonMapGrid.MapRoomType.NORMAL)),
			List.of(mapDoor(0, 1, OdinDungeonMapGrid.DoorType.NORMAL)));
		BoxDoorsFairyRouteRules.Selection incompleteRoute = BoxDoorsFairyRouteRules.select(
			incomplete, 0, 1, List.of(firstWither));
		var routeFallback = BoxDoorsFairyRouteRules.chooseTracerTarget(incompleteRoute,
			List.of(firstWither, secondWither), blood);
		check(incompleteRoute.status() == BoxDoorsFairyRouteRules.Status.ROUTE_INCOMPLETE
			&& routeFallback.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_WITHER_ROUTE
			&& routeFallback.target() == firstWither,
			"an incomplete map route falls back to tracker order from the current player cell");

		FairyRouteFixture fixture = fairyRouteFixture();
		BoxDoorsFairyRouteRules.Selection expectedMissing = BoxDoorsFairyRouteRules.select(
			fixture.map(), fixture.predecessor(), fixture.predecessor() + 1,
			List.of(fixture.exitDoor(), fixture.nextDoor()));
		BoxDoorsFairyRouteRules.TraceChoice waiting = BoxDoorsFairyRouteRules.chooseTracerTarget(
			expectedMissing, List.of(firstWither, secondWither), blood);
		check(waiting.source() == BoxDoorsFairyRouteRules.TargetSource.WAITING_FOR_ROUTE_EDGE
			&& waiting.target() == null,
			"a missing exact map-route edge requests its scan instead of falling back to tracker order");
		var noTrackerDoors = BoxDoorsFairyRouteRules.chooseTracerTarget(incompleteRoute, List.of(), null,
			ignored -> BloodDoorTraceRules.DoorPresence.UNKNOWN);
		check(noTrackerDoors.source() == BoxDoorsFairyRouteRules.TargetSource.TRACKER_ROUTE_COMPLETE
			&& noTrackerDoors.target() == null,
			"an unavailable route with no tracker records stays hidden with a diagnostic reason");
	}

	private static void legacyRoomBoundaryOverloads() {
		DungeonDoorTracker.Door boundaryWither = observedWither(8, 14, 73);
		BoxDoorsFairyRouteRules.LocalSelection local = BoxDoorsFairyRouteRules.selectLocal(
			List.of(boundaryWither), Set.of(8));
		check(local.status() == BoxDoorsFairyRouteRules.LocalStatus.SELECTED
			&& local.target() == boundaryWither && local.candidateCount() == 1,
			"the deprecated local room-boundary selector retains its Java compatibility behavior");

		BoxDoorsFairyRouteRules.TraceChoice choice = BoxDoorsFairyRouteRules.chooseTracerTarget(
			null, List.of(boundaryWither), Set.of(8));
		check(choice.source() == BoxDoorsFairyRouteRules.TargetSource.LOCAL_FALLBACK
			&& choice.target() == boundaryWither && choice.candidateCount() == 1,
			"the deprecated tracer overload continues delegating to local boundary selection");
	}

	private static void promptScanRules() {
		check(DungeonDoorTracker.promptEdgeIndex(8, 9) == DungeonDoorTracker.promptEdgeIndex(9, 8)
			&& DungeonDoorTracker.promptEdgeIndex(8, 14) == DungeonDoorTracker.promptEdgeIndex(14, 8),
			"targeted scan edge lookup accepts both endpoint orders");
		check(DungeonDoorTracker.promptEdgeIndex(8, 10) == -1
			&& DungeonDoorTracker.promptEdgeIndex(-1, 8) == -1,
			"targeted scan rejects non-adjacent and out-of-grid endpoints");
		check(DungeonDoorTracker.promptScanDecision(false, true, false, Long.MIN_VALUE, 100)
			== DungeonDoorTracker.PromptScanResult.TRACKER_NOT_READY,
			"targeted scan fails when the dungeon tracker is not ready");
		check(DungeonDoorTracker.promptScanDecision(true, false, false, Long.MIN_VALUE, 100)
			== DungeonDoorTracker.PromptScanResult.EDGE_UNLOADED,
			"targeted scan fails closed without loaded samples");
		check(DungeonDoorTracker.promptScanDecision(true, true, true, Long.MIN_VALUE, 100)
			== DungeonDoorTracker.PromptScanResult.ALREADY_QUEUED,
			"targeted scan requests deduplicate while queued");
		check(DungeonDoorTracker.promptScanDecision(true, true, false, 100, 119)
			== DungeonDoorTracker.PromptScanResult.RECENTLY_SCANNED
			&& DungeonDoorTracker.promptScanDecision(true, true, false, 100, 120)
			== DungeonDoorTracker.PromptScanResult.QUEUED,
			"targeted scans honor the retry cooldown and become eligible at its limit");
	}

	private static OdinDungeonMapGrid.Room room(int cell, OdinDungeonMapGrid.MapRoomType type) {
		return room(List.of(cell), type);
	}

	private static OdinDungeonMapGrid.Room room(List<Integer> cells, OdinDungeonMapGrid.MapRoomType type) {
		return new OdinDungeonMapGrid.Room(cells, type, OdinDungeonMapGrid.Checkmark.UNDISCOVERED,
			cells.size() == 1 ? "1x1" : "multi");
	}

	private static OdinDungeonMapGrid.ScanResult map(List<OdinDungeonMapGrid.Room> rooms,
		List<OdinDungeonMapGrid.Door> doors) {
		List<OdinDungeonMapGrid.Tile> tiles = new ArrayList<>(36);
		for (int cell = 0; cell < 36; cell++) {
			OdinDungeonMapGrid.MapRoomType type = OdinDungeonMapGrid.MapRoomType.UNDISCOVERED;
			for (OdinDungeonMapGrid.Room room : rooms) if (room.segments().contains(cell)) type = room.type();
			tiles.add(new OdinDungeonMapGrid.Tile(cell, cell % 6, cell / 6, type,
				type.mapColor(), type.mapColor(), OdinDungeonMapGrid.Checkmark.UNDISCOVERED));
		}
		return new OdinDungeonMapGrid.ScanResult(true,
			new OdinDungeonMapGrid.Layout(true, 18, 22, 22, 22, null), tiles, rooms, doors, "");
	}

	private static List<DungeonDoorTracker.Door> append(List<DungeonDoorTracker.Door> doors,
		DungeonDoorTracker.Door extra) {
		List<DungeonDoorTracker.Door> result = new ArrayList<>(doors);
		result.add(extra);
		return List.copyOf(result);
	}
	private static OdinDungeonMapGrid.Door mapDoor(int firstCell, int secondCell,
		OdinDungeonMapGrid.DoorType type) {
		OdinDungeonMapGrid.Direction direction = firstCell / 6 == secondCell / 6
			? OdinDungeonMapGrid.Direction.HORIZONTAL : OdinDungeonMapGrid.Direction.VERTICAL;
		return new OdinDungeonMapGrid.Door(firstCell, secondCell, direction, type, type.ordinal());
	}

	private static DungeonDoorTracker.Door observedWither(int firstCell, int secondCell, int marker) {
		return new DungeonDoorTracker.Door(DungeonDoorTracker.Type.WITHER, firstCell, secondCell,
			100 + marker, 70, -100 - marker, 3, 4, 3);
	}

	private record FairyRouteFixture(OdinDungeonMapGrid.ScanResult map, List<OdinDungeonMapGrid.Room> rooms,
		List<OdinDungeonMapGrid.Door> mapDoors, int predecessor, int entryFromCell, int fairyCell,
		int afterFairy, DungeonDoorTracker.Door entryDoor, DungeonDoorTracker.Door exitDoor,
		DungeonDoorTracker.Door nextDoor, List<DungeonDoorTracker.Door> observedDoors) { }

	private static void tracerRoomFallbackAndConflict() {
		DungeonGridRules.Cell mapCell = new DungeonGridRules.Cell(1, 1, -168, -168);
		List<DungeonMapGeometry.CellOffset> twoTileShape = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0));
		DungeonDoorTracker.Door connectedFarWither = new DungeonDoorTracker.Door(
			DungeonDoorTracker.Type.WITHER, 8, 14, 499, 70, 499, 3, 4, 3);

		BoxDoorsModule.TracerRoomContext mapFallback = BoxDoorsModule.resolveTracerRoomContext(
			null, mapCell, true, true, twoTileShape);
		check(mapFallback.confirmed() && "map-fallback".equals(mapFallback.source()),
			"a calibrated map cell supplies the tracer's current room when the strict physical lookup is null");
		check(mapFallback.footprint().equals(Set.of(7, 8))
			&& BoxDoorsModule.tracerRoomAdjacent(connectedFarWither, true, mapFallback),
			"map fallback retains the connected room footprint for door adjacency");

		BoxDoorsModule.TracerRoomContext agreement = BoxDoorsModule.resolveTracerRoomContext(
			mapCell, mapCell, true, true, twoTileShape);
		check(agreement.confirmed() && "physical+map-agree".equals(agreement.source())
			&& agreement.footprint().equals(Set.of(7, 8)),
			"the connected map footprint is used when its current cell agrees with the physical lookup");
		List<DungeonMapGeometry.CellOffset> nullOffsetShape = new ArrayList<>();
		nullOffsetShape.add(null);
		BoxDoorsModule.TracerRoomContext malformedMapFootprint = BoxDoorsModule.resolveTracerRoomContext(
			null, mapCell, true, true, nullOffsetShape);
		check(!malformedMapFootprint.confirmed()
			&& "map-footprint-unconfirmed".equals(malformedMapFootprint.rejectionReason()),
			"a null map-footprint offset fails closed instead of throwing or authorizing the map fallback");

		DungeonGridRules.Cell conflictingMapCell = new DungeonGridRules.Cell(4, 4, -72, -72);
		BoxDoorsModule.TracerRoomContext conflict = BoxDoorsModule.resolveTracerRoomContext(
			mapCell, conflictingMapCell, true, true, List.of(new DungeonMapGeometry.CellOffset(0, 0)));
		check(!conflict.confirmed() && "physical-map-cell-conflict".equals(conflict.rejectionReason()),
			"a disagreement between calibrated map and physical cells rejects the room identity");
		check(BoxDoorsModule.tracerCanRender(connectedFarWither,
			BloodDoorTraceRules.DoorPresence.CLOSED, conflict),
			"a room identity conflict does not suppress an already ordered closed tracer target");

		BoxDoorsModule.TracerRoomContext unknown = BoxDoorsModule.resolveTracerRoomContext(
			null, null, false, false, List.of());
		check(!unknown.confirmed() && "current-cell-unknown".equals(unknown.rejectionReason())
			&& BoxDoorsModule.tracerCanRender(connectedFarWither,
				BloodDoorTraceRules.DoorPresence.CLOSED, unknown),
			"an unknown current room does not suppress a closed ordered tracer target");

		BoxDoorsModule.TracerRoomContext uncalibratedMap = BoxDoorsModule.resolveTracerRoomContext(
			null, mapCell, false, false, twoTileShape);
		check(!uncalibratedMap.confirmed(), "uncalibrated map cells cannot authorize a tracer fallback");
	}

	private static void tracerRangeRules() {
		DungeonGridRules.Cell current = new DungeonGridRules.Cell(1, 1, -168, -168);
		List<DungeonMapGeometry.CellOffset> twoTileShape = List.of(
			new DungeonMapGeometry.CellOffset(0, 0), new DungeonMapGeometry.CellOffset(1, 0));
		BoxDoorsModule.TracerRoomContext room = BoxDoorsModule.resolveTracerRoomContext(
			current, current, true, true, twoTileShape);
		DungeonDoorTracker.Door connectedFarWither = new DungeonDoorTracker.Door(
			DungeonDoorTracker.Type.WITHER, 8, 14, 499, 70, 499, 3, 4, 3);
		DungeonDoorTracker.Door internalWither = new DungeonDoorTracker.Door(
			DungeonDoorTracker.Type.WITHER, 7, 8, 499, 70, 499, 3, 4, 3);
		check(BoxDoorsModule.shouldBypassTracerRange(connectedFarWither,
			BloodDoorTraceRules.DoorPresence.CLOSED, room)
			&& BoxDoorsModule.tracerWithinConfiguredRange(connectedFarWither,
				BloodDoorTraceRules.DoorPresence.CLOSED, room, 50_000.0, 16),
			"a confirmed connected-room Wither door beyond configured range keeps its tracer");
		check(BoxDoorsModule.shouldBypassTracerRange(internalWither,
			BloodDoorTraceRules.DoorPresence.CLOSED, room)
			&& BoxDoorsModule.tracerWithinConfiguredRange(internalWither,
				BloodDoorTraceRules.DoorPresence.CLOSED, room, 50_000.0, 16),
			"a selected non-adjacent Wither target ignores Door Render Range");
		check(!BoxDoorsModule.shouldBypassTracerRange(connectedFarWither,
			BloodDoorTraceRules.DoorPresence.OPEN, room)
			&& !BoxDoorsModule.tracerCanRender(connectedFarWither,
				BloodDoorTraceRules.DoorPresence.OPEN, room),
			"an open Wither door stays suppressed even when its room footprint is adjacent");

		DungeonDoorTracker.Door connectedFarBlood = new DungeonDoorTracker.Door(
			DungeonDoorTracker.Type.BLOOD, 8, 14, 499, 70, 499, 3, 4, 3);
		check(BoxDoorsModule.tracerCanRender(connectedFarBlood,
			BloodDoorTraceRules.DoorPresence.CLOSED, room)
			&& BoxDoorsModule.tracerWithinConfiguredRange(connectedFarBlood,
				BloodDoorTraceRules.DoorPresence.CLOSED, room, 50_000.0, 16),
			"Blood tracer keeps its existing no-range behavior for a confirmed closed boundary");
	}

	private static void highlightTypes() {
		for (DungeonDoorTracker.Type type : DungeonDoorTracker.Type.values()) {
			boolean expected = type == DungeonDoorTracker.Type.WITHER || type == DungeonDoorTracker.Type.BLOOD;
			check(BoxDoorsHighlightRules.rendersBox(type) == expected,
				"Box Doors only renders Blood and Wither boxes for " + type);
		}
		check(!BoxDoorsHighlightRules.rendersBox(null), "null door type is not rendered");
	}

	private static void closedDoorSourceStates() {
		var coal = Blocks.COAL_BLOCK.defaultBlockState();
		var redTerracotta = Blocks.DYED_TERRACOTTA.pick(DyeColor.RED).defaultBlockState();
		var glass = Blocks.GLASS.defaultBlockState();
		var barrier = Blocks.BARRIER.defaultBlockState();
		var air = Blocks.AIR.defaultBlockState();
		check(BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.WITHER, coal),
			"authoritative Coal Block keeps a Wither entrance closed while locally projected");
		check(BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.BLOOD, redTerracotta),
			"authoritative Red Terracotta keeps the Blood entrance closed while locally projected");
		check(!BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.WITHER, glass)
			&& !BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.BLOOD, glass),
			"the projected glass state is not mistaken for an open authoritative door");
		check(!BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.WITHER, barrier)
			&& !BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.BLOOD, barrier),
			"authoritative Barrier is treated as open rather than as a source block");
		check(!BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.WITHER, air)
			&& !BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.BLOOD, air),
			"authoritative air is treated as open");
		check(!BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.WITHER, redTerracotta)
			&& !BoxDoorsHighlightRules.isDoorSourceState(DungeonDoorTracker.Type.BLOOD, coal),
			"Blood and Wither source materials are not interchangeable");
	}

	private static void closedDoorGate() {
		var unloadedDoor = new DungeonDoorTracker.Door(DungeonDoorTracker.Type.WITHER, 0, 1,
			0, 69, 0, 3, 4, 3, List.of(new BlockPos(1, 70, 1)));
		var unloadedPresence = BloodDoorTraceRules.doorPresence(unloadedDoor, DungeonDoorTracker.Type.WITHER,
			position -> false, position -> true);
		check(unloadedPresence == BloodDoorTraceRules.DoorPresence.UNKNOWN,
			"a retained door with unloaded source samples reports UNKNOWN rather than CLOSED");
		check(!BoxDoorsHighlightRules.rendersBox(unloadedDoor.type(), unloadedPresence)
			&& !BoxDoorsHighlightRules.rendersTracer(unloadedPresence),
			"unloaded retained door samples suppress both the box and tracer");
		check(BoxDoorsHighlightRules.rendersTracer(BloodDoorTraceRules.DoorPresence.CLOSED),
			"closed authoritative door samples permit a tracer");
		check(!BoxDoorsHighlightRules.rendersTracer(BloodDoorTraceRules.DoorPresence.OPEN),
			"open authoritative door samples suppress a retained tracer");
		check(!BoxDoorsHighlightRules.rendersTracer(BloodDoorTraceRules.DoorPresence.UNKNOWN),
			"unknown or unloaded door samples suppress a retained tracer");
		for (DungeonDoorTracker.Type type : List.of(DungeonDoorTracker.Type.WITHER,
			DungeonDoorTracker.Type.BLOOD)) {
			check(BoxDoorsHighlightRules.rendersBox(type, BloodDoorTraceRules.DoorPresence.CLOSED),
				"a retained " + type + " record renders while its authoritative source is closed");
			check(!BoxDoorsHighlightRules.rendersBox(type, BloodDoorTraceRules.DoorPresence.OPEN),
				"an open " + type + " record remains tracked but its box is hidden");
			check(!BoxDoorsHighlightRules.rendersBox(type, BloodDoorTraceRules.DoorPresence.UNKNOWN),
				"an unloaded or unknown " + type + " record is not drawn");
		}
		check(!BoxDoorsHighlightRules.rendersBox(DungeonDoorTracker.Type.NORMAL,
			BloodDoorTraceRules.DoorPresence.CLOSED), "closed normal doors remain outside the key-door box ESP");
	}

	private static void visibleGroupsAndLegacySettings() {
		var module = BoxDoorsModule.INSTANCE;
		SettingGroup highlights = module.groups().stream()
			.filter(group -> "Door Highlights".equals(group.name())).findFirst().orElse(null);
		check(highlights != null, "Box Doors retains its highlight settings section");
		if (highlights != null) {
			check(highlights.children().stream().map(SettingGroup::name).toList()
				.equals(List.of("Wither doors", "Blood doors")),
				"only Wither and Blood highlight groups are visible");
		}
		for (String key : List.of("Show Normal Doors", "Normal Door Fill", "Normal Door Outline",
			"Normal Doors Through Walls", "Show Glass Doors", "Glass Door Fill", "Glass Door Outline",
			"Glass Doors Through Walls", "Show Start Door", "Start Door Fill", "Start Door Outline",
			"Start Door Through Walls", "Filter by Blood Rush Class", "Tank", "Healer", "Mage",
			"Berserk", "Archer")) {
			check(module.booleanSettings().stream().anyMatch(setting -> setting.name().equals(key)),
				"legacy boolean config key remains loadable and saveable: " + key);
		}
		var classFilter = module.booleanSettings().stream()
			.filter(setting -> setting.name().equals("Filter by Blood Rush Class")).findFirst().orElse(null);
		check(classFilter != null && !classFilter.value(),
			"the Blood Rush class filter defaults off to preserve existing visibility");
		for (String key : List.of("Tank", "Healer", "Mage", "Berserk", "Archer")) {
			var classToggle = module.booleanSettings().stream()
				.filter(setting -> setting.name().equals(key)).findFirst().orElse(null);
			check(classToggle != null && classToggle.value(),
				"the " + key + " class toggle defaults on");
		}
		check(BoxDoorsModule.bloodRushClassAllowsVisuals(false, null, false),
			"filter-off preserves visuals when the local tab class is missing");
		check(!BoxDoorsModule.bloodRushClassAllowsVisuals(true, null, true),
			"filter-on fails closed when the local tab class is unresolved");
		for (DungeonClass dungeonClass : DungeonClass.values()) {
			check(BoxDoorsModule.bloodRushClassAllowsVisuals(true, dungeonClass, true)
				&& !BoxDoorsModule.bloodRushClassAllowsVisuals(true, dungeonClass, false),
				"the class filter shows selected " + dungeonClass + " and hides unchecked classes");
		}
		for (String key : List.of("Normal Door Fill Color", "Normal Door Outline Color",
			"Glass Door Fill Color", "Glass Door Outline Color", "Start Door Fill Color",
			"Start Door Outline Color")) {
			check(module.colorSettings().stream().anyMatch(setting -> setting.name().equals(key)),
				"legacy color config key remains loadable and saveable: " + key);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
