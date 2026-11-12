package geiler.addons.client.dungeon;

import com.google.gson.JsonObject;
import geiler.addons.client.gui.DungeonGuideEditorScreen;
import geiler.addons.client.gui.DungeonGuideStagesScreen;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Focused offline checks for Dungeon Guide matching and route progression. */
public final class DungeonGuideChecks {
	private DungeonGuideChecks() { }

	public static void run() {
		checkJavaAbi();
		checkGuideEnumOrder();
		checkPackageCodecRecordSemantics();
		checkRouteStepConstructorSemantics();
		checkRouteSnapshotListSemantics();
		checkSnapshotIdentityAndStaleness();
		checkLegacyDefaults();
		checkLegacyBlankSemantics();
		checkLegacyRouteFilePaths();
		checkChatMatching();
		checkConfiguredChatEvents();
		checkManualConditionCombination();
		checkForwardPhaseProgression();
		checkSkipAheadProgression();
		checkSnapshotRevisionReconciliation();
		checkUnknownPhaseFallback();
		checkFirstMatchingCustomChatEventStopsAfterRejectedMatch();
		try {
			DungeonGuidePersistenceChecks.run();
		} catch (Exception failure) {
			throw new AssertionError("Dungeon Guide legacy/profile persistence checks failed", failure);
		}
	}

	private static void checkJavaAbi() {
		assertEquals(DungeonPackageCodec.class, DungeonPackageCodec.DecodeResult.class.getEnclosingClass(),
			"DecodeResult retains its nested Java owner");
		assertRecordComponents(DungeonPackageCodec.DecodeResult.class, List.of("success", "entries", "error"));
		assertRecordComponentTypes(DungeonPackageCodec.DecodeResult.class, boolean.class, List.class, String.class);
		assertPublicConstructor(DungeonPackageCodec.DecodeResult.class, boolean.class, List.class, String.class);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "success", boolean.class, false);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "entries", List.class, false);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "error", String.class, false);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "equals", boolean.class, false, Object.class);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "hashCode", int.class, false);
		assertMethod(DungeonPackageCodec.DecodeResult.class, "toString", String.class, false);
		assertMethod(DungeonPackageCodec.class, "encodeGuides", String.class, true, List.class);
		assertMethod(DungeonPackageCodec.class, "decodeGuides", DungeonPackageCodec.DecodeResult.class,
			true, String.class);
		assertEquals(DungeonGuideProgress.class, DungeonGuideProgress.RouteStep.class.getEnclosingClass(),
			"RouteStep retains its nested Java owner");
		assertEquals(DungeonGuideProgress.class, DungeonGuideProgress.RouteSnapshot.class.getEnclosingClass(),
			"RouteSnapshot retains its nested Java owner");
		assertRecordComponents(DungeonGuidePhaseOutputState.Transition.class, List.of("floor", "phaseId"));
		assertRecordComponents(DungeonGuidePhaseEntryBuffer.Pending.class,
			List.of("floor", "level", "connection", "capturedAtNanos"));
		assertFalse(DungeonGuideProgress.RouteStep.class.isRecord(),
			"RouteStep uses a Kotlin class to normalize copied inputs, so Java record reflection metadata is unavailable");
		assertEquals(null, DungeonGuideProgress.RouteStep.class.getRecordComponents(),
			"RouteStep explicitly documents the loss of Java record components");
		assertPublicConstructor(DungeonGuideProgress.RouteStep.class,
			String.class, String.class, String.class, int.class, int.class, int.class, int.class,
			float.class, float.class, int.class, int.class, int.class, float.class,
			DungeonGuideNode.Shape.class, int.class, float.class, float.class, float.class, float.class,
			boolean.class, java.util.Set.class, DungeonGuideNode.ConditionRule.class, float.class, float.class,
			String.class, int.class, boolean.class, DungeonGuideNode.ChatMatch.class);
		assertPublicConstructor(DungeonGuideProgress.class);
		assertPublicConstructor(DungeonGuideEditorScreen.class, Screen.class);
		assertPublicConstructor(DungeonGuideStagesScreen.class, Screen.class);
		assertPublicConstructor(DungeonGuidePhaseOutputState.Transition.class, DungeonFloor.class, String.class);
		assertPublicConstructor(DungeonGuidePhaseEntryBuffer.Pending.class,
			DungeonFloor.class, Object.class, Object.class, long.class);
		assertMethod(DungeonGuideProgress.RouteStep.class, "id", String.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "phase", String.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "label", String.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "x", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "y", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "z", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "visibilityDistance", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "size", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "rotation", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "color", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "fillColor", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "labelColor", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "labelScale", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "shape", DungeonGuideNode.Shape.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringCount", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringHeight", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringRadius", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringSpeed", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringWidth", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "ringFill", boolean.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "conditions", java.util.Set.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "conditionRule", DungeonGuideNode.ConditionRule.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "triggerRadius", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "triggerSeconds", float.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "eventText", String.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "macroId", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "skipAhead", boolean.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "chatMatch", DungeonGuideNode.ChatMatch.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "equals", boolean.class, false, Object.class);
		assertMethod(DungeonGuideProgress.RouteStep.class, "hashCode", int.class, false);
		assertMethod(DungeonGuideProgress.RouteStep.class, "toString", String.class, false);

		assertMethod(DungeonGuideProgress.class, "setFloor", void.class, false,
			DungeonFloor.class, long.class);
		assertMethod(DungeonGuideProgress.class, "setPhase", boolean.class, false,
			String.class, long.class);
		assertMethod(DungeonGuideProgress.class, "setPhase", boolean.class, false,
			String.class, long.class, DungeonGuideProgress.RouteSnapshot.class);
		assertMethod(DungeonGuideProgress.class, "snapshot", DungeonGuideProgress.RouteSnapshot.class,
			false, List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "snapshot", DungeonGuideProgress.RouteSnapshot.class,
			false, List.class, long.class, long.class);
		assertMethod(DungeonGuideProgress.class, "setRoute", boolean.class, false,
			String.class, long.class);
		assertMethod(DungeonGuideProgress.class, "phase", String.class, false);
		assertMethod(DungeonGuideProgress.class, "floor", DungeonFloor.class, false);
		assertMethod(DungeonGuideProgress.class, "index", int.class, false);
		assertMethod(DungeonGuideProgress.class, "elapsedNanos", long.class, false, long.class);
		assertMethod(DungeonGuideProgress.class, "group", List.class, false, List.class);
		assertMethod(DungeonGuideProgress.class, "cachedGroup", List.class, false);
		assertMethod(DungeonGuideProgress.class, "remaining", int.class, false, List.class);
		assertMethod(DungeonGuideProgress.class, "next", void.class, false, List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "advance", DungeonGuideNode.class, false,
			List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "advance", DungeonGuideProgress.RouteStep.class, false,
			DungeonGuideProgress.RouteSnapshot.class, long.class);
		assertMethod(DungeonGuideProgress.class, "advanceTo", boolean.class, false,
			DungeonGuideProgress.RouteSnapshot.class, int.class, long.class);
		assertMethod(DungeonGuideProgress.class, "advanceManually", DungeonGuideNode.class, false,
			List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "advanceManually", DungeonGuideProgress.RouteStep.class, false,
			DungeonGuideProgress.RouteSnapshot.class, long.class);
		assertMethod(DungeonGuideProgress.class, "previous", void.class, false, long.class);
		assertMethod(DungeonGuideProgress.class, "observeConfiguredEvent", boolean.class, false,
			String.class, List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "observeConfiguredTrigger", DungeonGuideNode.class, false,
			String.class, List.class, long.class);
		assertMethod(DungeonGuideProgress.class, "triggerReady", boolean.class, true,
			DungeonGuideNode.class, long.class, double.class);
		assertMethod(DungeonGuideProgress.class, "triggerReady", boolean.class, true,
			DungeonGuideNode.class, long.class, double.class, String.class, String.class);
		assertMethod(DungeonGuideProgress.class, "triggerReady", boolean.class, true,
			DungeonGuideProgress.RouteStep.class, long.class, double.class, String.class, String.class);
		assertMethod(DungeonGuideProgress.class, "furthestSkipAheadIndexInRange", int.class, true,
			DungeonGuideProgress.RouteSnapshot.class, double.class, double.class, double.class);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "current", DungeonGuideProgress.RouteStep.class,
			false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "floor", DungeonFloor.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "phase", String.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "routeName", String.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "stepIndex", int.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "guideRevision", long.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "rebuildReason", String.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "buildNanos", long.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "steps", List.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "skipAheadIndices", List.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "nodeCount", int.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "remaining", int.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "complete", boolean.class, false);
		assertMethod(DungeonGuideProgress.RouteSnapshot.class, "create", DungeonGuideProgress.RouteSnapshot.class,
			true, DungeonFloor.class, String.class, String.class, int.class, long.class, String.class, long.class,
			List.class, List.class);
		assertMethod(DungeonGuidePhaseTracker.class, "preferredWaypointPhase", String.class, true,
			List.class, String.class, String.class);
		assertMethod(DungeonGuidePhaseOutputState.class, "supportedPhaseIds", List.class, true,
			DungeonFloor.class);
		assertMethod(DungeonGuidePhaseEntryBuffer.class, "capture", boolean.class, false,
			DungeonFloor.class, Object.class, Object.class, long.class);
		assertMethod(DungeonGuidePhaseEntryBuffer.class, "consumeIfReady",
			DungeonGuidePhaseEntryBuffer.Pending.class, false, boolean.class, DungeonFloor.class,
			Object.class, Object.class, long.class);
		assertPublicStaticFinalField(DungeonGuidePhaseEntryBuffer.class, "MAX_PENDING_NANOS", long.class);
		assertMethod(DungeonGuideFlush.class, "drain", void.class, true, DungeonGuideFlush.Writer.class);
		assertMethod(DungeonGuideChatDispatch.class, "dispatch", void.class, true,
			DungeonFloor.class, DungeonFloor.class, String.class,
			java.util.function.BiConsumer.class, java.util.function.Consumer.class);
	}

	private static void checkGuideEnumOrder() {
		assertEquals(List.of(DungeonGuideNode.Shape.BOX, DungeonGuideNode.Shape.BEACON,
			DungeonGuideNode.Shape.RING, DungeonGuideNode.Shape.TEXT_ONLY),
			Arrays.asList(DungeonGuideNode.Shape.values()), "guide shape enum order remains stable");
		assertEquals(List.of(DungeonGuideNode.Trigger.MANUAL, DungeonGuideNode.Trigger.ENTER_RADIUS,
			DungeonGuideNode.Trigger.AFTER_SECONDS, DungeonGuideNode.Trigger.CHAT_EVENT),
			Arrays.asList(DungeonGuideNode.Trigger.values()), "legacy guide trigger enum order remains stable");
		assertEquals(List.of(DungeonGuideNode.Condition.MANUAL, DungeonGuideNode.Condition.ENTER_RADIUS,
			DungeonGuideNode.Condition.AFTER_SECONDS, DungeonGuideNode.Condition.CHAT_EVENT,
			DungeonGuideNode.Condition.DETECTED_PHASE),
			Arrays.asList(DungeonGuideNode.Condition.values()), "guide condition enum order remains stable");
		assertEquals(List.of(DungeonGuideNode.ConditionRule.ALL, DungeonGuideNode.ConditionRule.ANY),
			Arrays.asList(DungeonGuideNode.ConditionRule.values()), "guide condition-rule enum order remains stable");
		assertEquals(List.of(DungeonGuideNode.ChatMatch.EXACT, DungeonGuideNode.ChatMatch.CONTAINS),
			Arrays.asList(DungeonGuideNode.ChatMatch.values()), "guide chat-match enum order remains stable");
	}

	private static void checkPackageCodecRecordSemantics() {
		DungeonPackageCodec.DecodeResult<DungeonGuideNode> nullable =
			new DungeonPackageCodec.DecodeResult<>(false, null, null);
		assertEquals(null, nullable.entries(), "DecodeResult preserves direct-constructor null entries");
		assertEquals("DecodeResult[success=false, entries=null, error=null]", nullable.toString(),
			"DecodeResult retains Java record string formatting");

		List<DungeonGuideNode> entries = new ArrayList<>();
		DungeonPackageCodec.DecodeResult<DungeonGuideNode> value =
			new DungeonPackageCodec.DecodeResult<>(true, entries, "detail");
		assertTrue(value.entries() == entries, "DecodeResult preserves the directly supplied list reference");
		DungeonPackageCodec.DecodeResult<DungeonGuideNode> same =
			new DungeonPackageCodec.DecodeResult<>(true, entries, "detail");
		assertEquals(value, same, "DecodeResult retains record-style value equality");
		assertEquals(value.hashCode(), same.hashCode(), "equal DecodeResult records retain equal hash codes");
		assertEquals("DecodeResult[success=true, entries=[], error=detail]", value.toString(),
			"DecodeResult retains Java record component formatting");
	}

	private static void assertRecordComponents(Class<?> type, List<String> expectedNames) {
		assertTrue(type.isRecord(), type.getName() + " remains a Java record for Java/Kotlin callers");
		List<String> actualNames = Arrays.stream(type.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName).toList();
		assertEquals(expectedNames, actualNames, type.getName() + " retains its record component order");
	}

	private static void assertRecordComponentTypes(Class<?> type, Class<?>... expectedTypes) {
		List<Class<?>> actualTypes = Arrays.stream(type.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getType).toList();
		assertEquals(Arrays.asList(expectedTypes), actualTypes,
			type.getName() + " retains its record component types and order");
	}

	private static void assertMethod(Class<?> owner, String name, Class<?> returnType, boolean isStatic,
		Class<?>... parameterTypes) {
		try {
			Method method = owner.getDeclaredMethod(name, parameterTypes);
			assertEquals(returnType, method.getReturnType(), owner.getSimpleName() + "." + name + " return type");
			assertTrue(Modifier.isPublic(method.getModifiers()),
				owner.getSimpleName() + "." + name + " remains public");
			assertEquals(isStatic, Modifier.isStatic(method.getModifiers()),
				owner.getSimpleName() + "." + name + " static/instance ABI");
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("Missing Java ABI method " + owner.getName() + "." + name, error);
		}
	}

	private static void assertPublicStaticFinalField(Class<?> owner, String name, Class<?> fieldType) {
		try {
			var field = owner.getField(name);
			assertEquals(fieldType, field.getType(), owner.getSimpleName() + "." + name + " field type");
			int modifiers = field.getModifiers();
			assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
				owner.getSimpleName() + "." + name + " remains public static final");
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("Missing Java ABI field " + owner.getName() + "." + name, error);
		}
	}

	private static void assertPublicConstructor(Class<?> owner, Class<?>... parameterTypes) {
		try {
			var constructor = owner.getDeclaredConstructor(parameterTypes);
			assertTrue(Modifier.isPublic(constructor.getModifiers()),
				owner.getSimpleName() + " retains its public Java constructor");
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("Missing Java constructor on " + owner.getName(), error);
		}
	}

	private static void checkLegacyDefaults() {
		DungeonGuideNode legacy = ClientJsonFile.GSON.fromJson(
			"{\"id\":\"legacy\",\"floor\":\"F1\",\"phase\":\"ENTRY\"}", DungeonGuideNode.class);
		legacy.sanitize();
		assertFalse(legacy.skipAhead, "a legacy guide without skipAhead keeps skip-ahead disabled");
		assertEquals(DungeonGuideNode.ChatMatch.EXACT, legacy.chatMatch,
			"a legacy guide without chatMatch keeps exact matching");
	}

	private static void checkLegacyBlankSemantics() {
		String nonBreakingSpace = "\u00a0";
		assertFalse(nonBreakingSpace.isBlank(), "Java String.isBlank does not treat a non-breaking space as blank");
		DungeonGuideNode node = new DungeonGuideNode();
		node.id = nonBreakingSpace;
		node.phase = nonBreakingSpace;
		node.routeName = nonBreakingSpace;
		node.label = nonBreakingSpace;
		node.sanitize();
		assertEquals(nonBreakingSpace, node.id, "node sanitization retains the legacy Java blank check for ids");
		assertEquals(nonBreakingSpace, node.phase, "node sanitization retains the legacy Java blank check for phases");
		assertEquals(nonBreakingSpace, node.routeName, "node sanitization retains the legacy Java blank check for route names");
		assertEquals(nonBreakingSpace, node.label, "node sanitization retains the legacy Java blank check for labels");

		DungeonGuideCustomSegment segment = new DungeonGuideCustomSegment();
		segment.label = nonBreakingSpace;
		segment.sanitize();
		assertEquals(nonBreakingSpace, segment.label, "custom stage sanitization retains legacy Java blank behavior");

		String[] dispatched = { null };
		DungeonGuideChatDispatch.dispatch(null, null, nonBreakingSpace, null, message -> dispatched[0] = message);
		assertEquals(nonBreakingSpace, dispatched[0], "chat dispatch retains non-breaking-space messages under Java blank semantics");
	}

	private static void checkLegacyRouteFilePaths() {
		assertTrue(isValidRouteEntry("legacy-route.json"), "a safe root-relative route path accepted by the legacy index remains accepted");
		assertTrue(isValidRouteEntry("legacy/archive/route.json"),
			"a safe nested relative route path accepted by the legacy index remains accepted");
		assertTrue(isValidRouteEntry("F6/ENTRY/route..a-abc.json"),
			"repeated periods are allowed inside a basename while path-segment validation remains enforced");
		assertFalse(isValidRouteEntry("../outside.json"), "parent traversal remains rejected");
		assertFalse(isValidRouteEntry("legacy/../outside.json"), "nested parent traversal remains rejected");
		assertFalse(isValidRouteEntry("a".repeat(252) + ".json"), "route paths remain limited to 256 characters");
	}

	private static boolean isValidRouteEntry(String file) {
		try {
			Class<?> routeEntryType = Class.forName(DungeonGuideStore.class.getName() + "$RouteEntry");
			JsonObject json = new JsonObject();
			json.addProperty("floor", "F1");
			json.addProperty("phase", "ENTRY");
			json.addProperty("name", "Default");
			json.addProperty("file", file);
			Object entry = ClientJsonFile.GSON.fromJson(json, routeEntryType);
			Field companionField = DungeonGuideStore.class.getField("Companion");
			Object companion = companionField.get(null);
			Method validator = companion.getClass().getDeclaredMethod("validEntry", routeEntryType);
			validator.setAccessible(true);
			return (boolean) validator.invoke(companion, entry);
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("Could not inspect Dungeon Guide route entry validation", error);
		}
	}

	private static void checkRouteStepConstructorSemantics() {
		DungeonGuideProgress.RouteStep defaults = routeStep(null, null, null);
		assertEquals(java.util.Set.of(), defaults.conditions(),
			"the public RouteStep constructor defaults null conditions to an empty set");
		assertEquals("", defaults.eventText(), "the public RouteStep constructor defaults null eventText to empty text");
		assertEquals(DungeonGuideNode.ChatMatch.EXACT, defaults.chatMatch(),
			"the public RouteStep constructor defaults null chatMatch to Exact");

		EnumSet<DungeonGuideNode.Condition> mutable = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		DungeonGuideProgress.RouteStep copied = routeStep(mutable, "door opened", DungeonGuideNode.ChatMatch.CONTAINS);
		mutable.add(DungeonGuideNode.Condition.MANUAL);
		assertEquals(EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT), copied.conditions(),
			"the public RouteStep constructor defensively copies its conditions input");
		boolean immutable = false;
		try {
			copied.conditions().add(DungeonGuideNode.Condition.MANUAL);
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		assertTrue(immutable, "RouteStep conditions remain immutable to direct Java callers");
		Set<DungeonGuideNode.Condition> nullCondition = new HashSet<>();
		nullCondition.add(null);
		boolean rejectedNullCondition = false;
		try {
			routeStep(nullCondition, "door opened", DungeonGuideNode.ChatMatch.CONTAINS);
		} catch (NullPointerException expected) {
			rejectedNullCondition = true;
		}
		assertTrue(rejectedNullCondition, "RouteStep preserves Set.copyOf null-element rejection");

		DungeonGuideProgress.RouteStep nullableReferences = new DungeonGuideProgress.RouteStep(
			null, null, null, 0, 0, 0, 0, 0.0f, 0.0f, 0, 0, 0, 0.0f,
			null, 0, 0.0f, 0.0f, 0.0f, 0.0f, false, null, null,
			0.0f, 0.0f, null, 0, false, null);
		assertEquals(null, nullableReferences.id(), "un-normalized record components still accept null values");
		assertEquals(null, nullableReferences.shape(), "nullable shape values remain accepted by the constructor");
		assertEquals(null, nullableReferences.conditionRule(), "nullable condition rules remain accepted by the constructor");

		DungeonGuideProgress.RouteStep sameValue = routeStep(
			java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT), "door opened", DungeonGuideNode.ChatMatch.CONTAINS);
		assertEquals(copied, sameValue, "RouteStep retains record-style equality after the Kotlin port");
		assertEquals(routeStepValue(java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT), "door opened",
			DungeonGuideNode.ChatMatch.CONTAINS).hashCode(), copied.hashCode(),
			"RouteStep keeps the Java record component-order hash code");
		assertFalse(copied.equals(defaults), "different RouteStep values remain unequal");
		assertEquals(routeStepValue(java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT), "door opened",
			DungeonGuideNode.ChatMatch.CONTAINS).toString().replaceFirst("^RouteStepValue", "RouteStep"),
			copied.toString(), "RouteStep preserves the Java record component order and field-labeled string");
		assertFalse(routeStep(java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT), "door opened",
			DungeonGuideNode.ChatMatch.CONTAINS, 0.0f).equals(routeStep(
			java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT), "door opened",
			DungeonGuideNode.ChatMatch.CONTAINS, -0.0f)),
			"RouteStep float equality retains Java record signed-zero semantics");
		DungeonGuideProgress.RouteStep nan = routeStep(java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT),
			"door opened", DungeonGuideNode.ChatMatch.CONTAINS, Float.NaN);
		DungeonGuideProgress.RouteStep otherNan = routeStep(java.util.Set.of(DungeonGuideNode.Condition.CHAT_EVENT),
			"door opened", DungeonGuideNode.ChatMatch.CONTAINS, Float.intBitsToFloat(0x7fc00001));
		assertEquals(nan, otherNan, "RouteStep float equality treats NaN like Java records");
		assertEquals(nan.hashCode(), otherNan.hashCode(), "equal NaN RouteSteps retain matching hashes");
	}

	private static void checkRouteSnapshotListSemantics() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.F1, "ENTRY", 0, 0, 0, 0);
		node.skipAhead = true;
		List<DungeonGuideNode> nodes = List.of(node);
		DungeonGuideProgress.RouteSnapshot route = progress.snapshot(nodes, 1, 100);

		assertImmutableList(route.steps(), "published route steps remain immutable");
		assertImmutableList(route.skipAheadIndices(), "published skip-ahead indices remain immutable");
		assertImmutableList(progress.group(nodes), "grouped guide nodes remain immutable");
		assertImmutableList(progress.cachedGroup(), "cached grouped guide nodes remain immutable");

		List<DungeonGuideProgress.RouteStep> mutableSteps = new ArrayList<>(route.steps());
		List<Integer> mutableIndices = new ArrayList<>(route.skipAheadIndices());
		DungeonGuideProgress.RouteSnapshot copied = DungeonGuideProgress.RouteSnapshot.create(
			route.floor(), route.phase(), route.routeName(), route.stepIndex(), route.guideRevision(),
			route.rebuildReason(), route.buildNanos(), mutableSteps, mutableIndices);
		mutableSteps.clear();
		mutableIndices.clear();
		assertEquals(1, copied.steps().size(), "a published snapshot copies its source step list");
		assertEquals(List.of(0), copied.skipAheadIndices(), "a published snapshot copies its skip-ahead list");
		assertImmutableList(copied.steps(), "copied route steps remain immutable");
		assertImmutableList(copied.skipAheadIndices(), "copied skip-ahead indices remain immutable");
	}

	private static void assertImmutableList(List<?> values, String message) {
		boolean immutable = false;
		try {
			values.clear();
		} catch (UnsupportedOperationException expected) {
			immutable = true;
		}
		assertTrue(immutable, message);
	}

	private static void checkSnapshotIdentityAndStaleness() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		List<DungeonGuideNode> nodes = List.of(
			step("first", 0, "ENTRY", 11),
			step("second", 1, "ENTRY", 22),
			step("third", 2, "ENTRY", 33));

		DungeonGuideProgress.RouteSnapshot first = progress.snapshot(nodes, 1, 100);
		assertSame(first, progress.snapshot(nodes, 1, 101),
			"an unchanged route snapshot retains object identity across refreshes");
		assertEquals("first", progress.advance(first, 150).id(), "the published snapshot advances its active node");
		assertEquals(null, progress.advance(first, 160), "a consumed route snapshot is stale after the index changes");
		DungeonGuideProgress.RouteSnapshot afterStep = progress.snapshot(nodes, 1, 160);
		assertNotSame(first, afterStep, "a progress change publishes a new snapshot object");
		assertFalse(progress.advanceTo(first, 2, 170), "advanceTo rejects an older snapshot object");
		assertEquals(1, progress.index(), "rejecting a stale snapshot leaves the current index unchanged");
		assertSame(afterStep, progress.snapshot(nodes, 1, 170),
			"the refreshed snapshot retains identity while route and progress stay unchanged");

		DungeonGuideProgress.RouteSnapshot afterRevision = progress.snapshot(nodes, 2, 180);
		assertNotSame(afterStep, afterRevision, "a guide revision publishes a new snapshot object");
		assertEquals(null, progress.advance(afterStep, 190), "a superseded route revision cannot advance progress");
		assertFalse(progress.advanceTo(afterStep, 2, 200), "advanceTo rejects a superseded route revision");
		assertEquals(1, progress.index(), "rejecting a superseded revision leaves the active index unchanged");
		assertTrue(progress.advanceTo(afterRevision, 2, 210), "the current published snapshot remains actionable");
	}

	private static DungeonGuideProgress.RouteStep routeStep(
		java.util.Set<DungeonGuideNode.Condition> conditions,
		String eventText,
		DungeonGuideNode.ChatMatch chatMatch) {
		return routeStep(conditions, eventText, chatMatch, 1.0f);
	}

	private static DungeonGuideProgress.RouteStep routeStep(
		java.util.Set<DungeonGuideNode.Condition> conditions,
		String eventText,
		DungeonGuideNode.ChatMatch chatMatch,
		float size) {
		return new DungeonGuideProgress.RouteStep("abi", "ENTRY", "Label",
			1, 2, 3, 64, size, 0.0f, 0xff00ff00, 0x6600ff00, 0xffffffff, 1.0f,
			DungeonGuideNode.Shape.BOX, 3, 0.25f, 1.5f, 1.0f, 2.0f, true,
			conditions, DungeonGuideNode.ConditionRule.ANY, 2.5f, 3.5f, eventText, 17, true, chatMatch);
	}

	private static RouteStepValue routeStepValue(
		java.util.Set<DungeonGuideNode.Condition> conditions,
		String eventText,
		DungeonGuideNode.ChatMatch chatMatch) {
		return new RouteStepValue("abi", "ENTRY", "Label",
			1, 2, 3, 64, 1.0f, 0.0f, 0xff00ff00, 0x6600ff00, 0xffffffff, 1.0f,
			DungeonGuideNode.Shape.BOX, 3, 0.25f, 1.5f, 1.0f, 2.0f, true,
			conditions, DungeonGuideNode.ConditionRule.ANY, 2.5f, 3.5f, eventText, 17, true, chatMatch);
	}

	private record RouteStepValue(
		String id,
		String phase,
		String label,
		int x,
		int y,
		int z,
		int visibilityDistance,
		float size,
		float rotation,
		int color,
		int fillColor,
		int labelColor,
		float labelScale,
		DungeonGuideNode.Shape shape,
		int ringCount,
		float ringHeight,
		float ringRadius,
		float ringSpeed,
		float ringWidth,
		boolean ringFill,
		Set<DungeonGuideNode.Condition> conditions,
		DungeonGuideNode.ConditionRule conditionRule,
		float triggerRadius,
		float triggerSeconds,
		String eventText,
		int macroId,
		boolean skipAhead,
		DungeonGuideNode.ChatMatch chatMatch) { }

	private static void checkChatMatching() {
		DungeonGuideNode chat = new DungeonGuideNode(DungeonFloor.F1, "ENTRY", 0, 0, 0, 0);
		chat.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		chat.eventText = "The door opened!";
		chat.chatMatch = DungeonGuideNode.ChatMatch.EXACT;
		String normalizedExact = "\u00a7aTHE\u00a0DOOR OPENED!";
		assertTrue(DungeonGuideProgress.triggerReady(chat, 0, Double.POSITIVE_INFINITY,
			normalizedExact, "ENTRY"), "exact chat matching ignores formatting, case, and non-breaking spaces");
		assertFalse(DungeonGuideProgress.triggerReady(chat, 0, Double.POSITIVE_INFINITY,
			"Boss: " + normalizedExact, "ENTRY"), "exact chat matching rejects surrounding text");

		chat.chatMatch = DungeonGuideNode.ChatMatch.CONTAINS;
		assertTrue(DungeonGuideProgress.triggerReady(chat, 0, Double.POSITIVE_INFINITY,
			"Boss: " + normalizedExact, "ENTRY"), "contains chat matching finds a normalized phrase in a longer line");
	}

	private static void checkConfiguredChatEvents() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		DungeonGuideNode active = step("chat", 0, "ENTRY", 505);
		active.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		active.eventText = "The door opened!";
		active.chatMatch = DungeonGuideNode.ChatMatch.EXACT;
		List<DungeonGuideNode> nodes = new java.util.ArrayList<>(List.of(active));
		assertFalse(progress.observeConfiguredEvent("Boss: The door opened!", nodes, 150),
			"configured route events require an exact normalized message unless Contains is selected");
		assertEquals(0, progress.index(), "an unmatched configured event leaves the active step unchanged");
		assertTrue(progress.observeConfiguredEvent("\u00a7aTHE\u00a0DOOR OPENED!", nodes, 200),
			"a unique exact configured event advances the active route step");
		assertEquals(1, progress.index(), "the matching configured event advances only once");

		DungeonGuideNode duplicate = step("duplicate", 1, "ENTRY", 606);
		duplicate.conditions = EnumSet.of(DungeonGuideNode.Condition.CHAT_EVENT);
		duplicate.eventText = active.eventText;
		duplicate.chatMatch = DungeonGuideNode.ChatMatch.EXACT;
		nodes.add(duplicate);
		assertFalse(progress.observeConfiguredEvent("The door opened!", nodes, 300),
			"two different waypoint IDs with the same configured phrase are ambiguous");
		assertEquals(1, progress.index(), "an ambiguous phrase does not advance the route");
	}

	private static void checkManualConditionCombination() {
		DungeonGuideNode combined = new DungeonGuideNode(DungeonFloor.F1, "ENTRY", 0, 0, 0, 0);
		combined.conditions = EnumSet.of(DungeonGuideNode.Condition.MANUAL,
			DungeonGuideNode.Condition.ENTER_RADIUS);
		combined.triggerRadius = 2.0f;
		combined.sanitize();
		assertTrue(DungeonGuideProgress.triggerReady(combined, 0, 3.9, "", "ENTRY"),
			"Manual is neutral when an automatic condition is also configured");
		assertFalse(DungeonGuideProgress.triggerReady(combined, 0, 4.1, "", "ENTRY"),
			"the automatic radius condition still gates a Manual-plus-automatic route");

		DungeonGuideNode manualOnly = new DungeonGuideNode(DungeonFloor.F1, "ENTRY", 1, 0, 0, 0);
		manualOnly.conditions = EnumSet.of(DungeonGuideNode.Condition.MANUAL);
		manualOnly.sanitize();
		assertFalse(DungeonGuideProgress.triggerReady(manualOnly, Long.MAX_VALUE,
			Double.NEGATIVE_INFINITY, "", "ENTRY"), "a manual-only waypoint is not automatically ready");
	}

	private static void checkForwardPhaseProgression() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		DungeonGuideNode entry = step("entry", 0, "ENTRY", 101);
		DungeonGuideNode clear = step("clear", 1, "CLEAR", 202);
		DungeonGuideNode blood = step("blood", 2, "BLOOD_OPEN", 303);
		DungeonGuideNode boss = step("boss", 3, "BOSS_ENTRY", 404);
		List<DungeonGuideNode> nodes = List.of(entry, clear, blood, boss);

		DungeonGuideProgress.RouteSnapshot route = progress.snapshot(nodes, 1, 100);
		assertEquals("entry", route.current().id(), "a new route starts at its first phase step");
		assertTrue(progress.setPhase("BLOOD_OPEN", 200, route), "a valid forward phase is accepted");
		route = progress.snapshot(nodes, 1, 200);
		assertEquals("blood", route.current().id(), "forward phase changes skip earlier-phase waypoints");
		assertEquals(0L, progress.elapsedNanos(200), "skipping earlier phases resets the active waypoint timer");

		assertTrue(progress.setPhase("CLEAR", 300, route), "a valid phase regression can update detection state");
		route = progress.snapshot(nodes, 1, 300);
		assertEquals("blood", route.current().id(), "a phase regression does not rewind route progress");
		DungeonGuideProgress.RouteStep fired = progress.advance(route, 400);
		assertEquals("blood", fired.id(), "the next fired waypoint is the active phase target");
		assertEquals(303, fired.macroId(), "earlier-phase macros are not returned as fired during a phase skip");
	}

	private static void checkSkipAheadProgression() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		DungeonGuideNode first = step("first", 0, "ENTRY", 11, 0, 64, 0);
		DungeonGuideNode bypassed = step("bypassed", 1, "ENTRY", 22, 0, 64, 0);
		DungeonGuideNode nearerTarget = step("nearer-target", 2, "ENTRY", 32, 1, 64, 0);
		DungeonGuideNode target = step("target", 3, "ENTRY", 33, 2, 64, 0);
		DungeonGuideNode outside = step("outside", 4, "ENTRY", 44, 10, 64, 0);
		first.skipAhead = true;
		bypassed.skipAhead = true;
		nearerTarget.skipAhead = true;
		target.skipAhead = true;
		outside.skipAhead = true;
		outside.triggerRadius = 1.0f;
		List<DungeonGuideNode> nodes = List.of(first, bypassed, nearerTarget, target, outside);
		DungeonGuideProgress.RouteSnapshot route = progress.snapshot(nodes, 1, 100);
		assertEquals(List.of(0, 1, 2, 3, 4), route.skipAheadIndices(),
			"the route snapshot exposes enabled skip-ahead waypoints in route order");

		assertEquals("first", progress.advance(route, 150).id(), "the initial waypoint fires through normal progression");
		route = progress.snapshot(nodes, 1, 150);
		int targetIndex = DungeonGuideProgress.furthestSkipAheadIndexInRange(route, 0.5, 64.5, 0.5);
		assertEquals(3, targetIndex,
			"skip-ahead chooses the furthest later flagged waypoint in range and ignores a farther out-of-range flag");

		assertTrue(progress.advanceTo(route, targetIndex, 200), "skip-ahead selects the requested later route step");
		assertEquals(0L, progress.elapsedNanos(200), "skip-ahead resets the selected waypoint timer");
		route = progress.snapshot(nodes, 1, 200);
		assertEquals("target", route.current().id(), "the selected later waypoint remains active after the jump");
		assertEquals(-1, DungeonGuideProgress.furthestSkipAheadIndexInRange(route, 0.5, 64.5, 0.5),
			"skip-ahead ignores earlier and current flags, and returns no target for out-of-range later flags");
		DungeonGuideProgress.RouteStep fired = progress.advance(route, 300);
		assertEquals("target", fired.id(), "only the active target is returned as fired after skip-ahead");
		assertEquals(33, fired.macroId(), "a bypassed waypoint macro is not returned for execution");
	}

	private static void checkSnapshotRevisionReconciliation() {
		DungeonGuideProgress progress = new DungeonGuideProgress();
		progress.setFloor(DungeonFloor.F1, 100);
		DungeonGuideNode first = step("first", 0, "ENTRY", 11);
		DungeonGuideNode active = step("active", 1, "ENTRY", 22);
		DungeonGuideNode last = step("last", 2, "ENTRY", 33);
		List<DungeonGuideNode> original = List.of(first, active, last);
		DungeonGuideProgress.RouteSnapshot route = progress.snapshot(original, 1, 100);
		assertEquals("first", progress.advance(route, 150).id(), "initial route advance records its fired step");

		DungeonGuideNode inserted = step("inserted", 0, "ENTRY", 44);
		DungeonGuideNode reorderedFirst = step("first", 1, "ENTRY", 11);
		DungeonGuideNode reorderedActive = step("active", 2, "ENTRY", 22);
		DungeonGuideNode reorderedLast = step("last", 3, "ENTRY", 33);
		route = progress.snapshot(List.of(inserted, reorderedFirst, reorderedActive, reorderedLast), 2, 200);
		assertEquals("active", route.current().id(), "a guide revision keeps the same uniquely identified waypoint active");
		assertEquals(50L, progress.elapsedNanos(200), "inserting before the active waypoint preserves its timer");

		route = progress.snapshot(List.of(inserted, reorderedFirst, reorderedLast), 3, 300);
		assertEquals("inserted", route.current().id(), "removing the active waypoint restarts the route at its first step");
		assertEquals(0L, progress.elapsedNanos(300), "removing the active waypoint resets the new active timer");
	}

	private static void checkUnknownPhaseFallback() {
		DungeonGuidePhaseTracker phases = new DungeonGuidePhaseTracker();
		phases.setFloor(DungeonFloor.F1, 100);
		List<String> validPhases = List.of("ENTRY", "CLEAR", "BLOOD_OPEN", "BOSS_ENTRY");
		assertFalse(phases.hasPhaseEvidence(), "detecting a floor alone does not establish a dungeon phase");
		assertEquals(null, phases.detectedPhaseFor(DungeonFloor.F1),
			"an unknown phase is not exposed as detected Entry");
		assertEquals("CLEAR", DungeonGuidePhaseTracker.preferredWaypointPhase(validPhases,
			phases.detectedPhaseFor(DungeonFloor.F1), "CLEAR"),
			"an unknown active-floor phase falls back to the selected step's valid phase");
		assertEquals("CLEAR", DungeonGuidePhaseTracker.preferredWaypointPhase(validPhases,
			phases.detectedPhaseFor(DungeonFloor.F2), "CLEAR"),
			"a different floor's phase evidence is ignored in favor of the selected step phase");
		assertEquals("ENTRY", DungeonGuidePhaseTracker.preferredWaypointPhase(validPhases,
			phases.detectedPhaseFor(DungeonFloor.F1), "NOT_A_PHASE"),
			"an unknown phase and invalid selection fall back to Entry");

		assertTrue(phases.choose("BLOOD_OPEN", 200), "a valid manual phase choice is accepted");
		assertEquals("BLOOD_OPEN", phases.detectedPhaseFor(DungeonFloor.F1),
			"a manual phase choice is valid phase evidence for waypoint creation");
		assertEquals("BLOOD_OPEN", DungeonGuidePhaseTracker.preferredWaypointPhase(validPhases,
			phases.detectedPhaseFor(DungeonFloor.F1), "CLEAR"),
			"an established active-floor phase takes priority over the selected step phase");
		phases.resumeAuto();
		assertFalse(phases.manual(), "resuming automatic detection clears manual mode");
		assertEquals("BLOOD_OPEN", phases.phase(), "resuming automatic detection preserves the manual phase");

		assertTrue(phases.observe(new DungeonGuideSegments.Event("BOSS_ENTRY", "Test event"),
			DungeonGuideStore.TransitionTiming.EVENT_SEGMENT, 300), "a valid automatic event advances the known phase");
		phases.resumeAuto();
		assertEquals("BOSS_ENTRY", phases.detectedPhaseFor(DungeonFloor.F1),
			"resuming automatic detection preserves the last automatically detected phase");
		phases.setFloor(DungeonFloor.F2, 400);
		assertFalse(phases.hasPhaseEvidence(), "changing floors clears phase evidence");
		assertEquals(null, phases.detectedPhaseFor(DungeonFloor.F2),
			"a newly detected floor starts with an unknown phase");
	}

	private static void checkFirstMatchingCustomChatEventStopsAfterRejectedMatch() {
		DungeonGuideStore.load();
		List<DungeonGuideCustomSegment> customSegments = liveCustomSegmentsForCheck();
		List<DungeonGuideCustomSegment> original = new ArrayList<>(customSegments);
		try {
			// Exercise the live tracker against temporary in-memory data without invoking Store save hooks.
			customSegments.clear();
			customSegments.add(customChatSegment("CUSTOM_LATER", 1));
			customSegments.add(customChatSegment("CUSTOM_NEXT", 0));

			DungeonGuidePhaseTracker phases = new DungeonGuidePhaseTracker();
			phases.setFloor(DungeonFloor.F1, 100);
			assertFalse(phases.observeCustomChat("same event", 200),
				"the first exact chat match stops processing even when its custom stage is not next");
			assertEquals("ENTRY", phases.phase(),
				"a later duplicate chat match cannot advance past an earlier rejected match");
		} finally {
			customSegments.clear();
			customSegments.addAll(original);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<DungeonGuideCustomSegment> liveCustomSegmentsForCheck() {
		try {
			Field field = DungeonGuideStore.class.getDeclaredField("CUSTOM_SEGMENTS");
			field.setAccessible(true);
			return (List<DungeonGuideCustomSegment>) field.get(null);
		} catch (ReflectiveOperationException error) {
			throw new IllegalStateException("Unable to access the in-memory custom stages for the parity check", error);
		}
	}

	private static DungeonGuideCustomSegment customChatSegment(String id, int afterBuiltInIndex) {
		DungeonGuideCustomSegment segment = new DungeonGuideCustomSegment();
		segment.id = id;
		segment.floor = "F1";
		segment.trigger = DungeonGuideCustomSegment.Trigger.CHAT_EVENT;
		segment.eventText = "same event";
		segment.afterBuiltInIndex = afterBuiltInIndex;
		return segment;
	}

	private static DungeonGuideNode step(String id, int order, String phase, int macroId) {
		return step(id, order, phase, macroId, 0, 0, 0);
	}

	private static DungeonGuideNode step(String id, int order, String phase, int macroId, int x, int y, int z) {
		DungeonGuideNode node = new DungeonGuideNode(DungeonFloor.F1, phase, order, x, y, z);
		node.id = id;
		node.macroId = macroId;
		return node;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}

	private static void assertFalse(boolean condition, String message) {
		assertTrue(!condition, message);
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			throw new IllegalStateException(message + " (expected " + expected + ", got " + actual + ")");
		}
	}

	private static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) throw new IllegalStateException(message);
	}

	private static void assertNotSame(Object expected, Object actual, String message) {
		if (expected == actual) throw new IllegalStateException(message);
	}
}
