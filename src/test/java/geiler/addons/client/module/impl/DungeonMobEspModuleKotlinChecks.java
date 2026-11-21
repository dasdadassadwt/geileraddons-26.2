package geiler.addons.client.module.impl;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.dungeon.DungeonClass;
import geiler.addons.client.dungeon.DungeonRoomTracker;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.ModuleKeybind;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import geiler.addons.client.module.TextSetting;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Direct Java ABI, setting, and legacy-config checks for the Kotlin-backed registered module. */
public final class DungeonMobEspModuleKotlinChecks {
	private DungeonMobEspModuleKotlinChecks() { }

	public static void run() {
		checkJavaAbi();
		checkSpecialMobRules();
		DungeonMobEspModule module = DungeonMobEspModule.INSTANCE;
		checkIdentityAndGroups(module);
		checkSettings(module);
		checkLegacySettings(module);
		checkKeybindMigration();
	}

	private static void checkJavaAbi() {
		check(Modifier.isPublic(DungeonMobEspModule.class.getModifiers())
			&& Modifier.isFinal(DungeonMobEspModule.class.getModifiers()),
			"the registered Kotlin module keeps a public final Java class");
		try {
			Field instance = DungeonMobEspModule.class.getField("INSTANCE");
			check(Modifier.isPublic(instance.getModifiers()) && Modifier.isStatic(instance.getModifiers())
				&& Modifier.isFinal(instance.getModifiers()) && instance.getType() == DungeonMobEspModule.class
				&& instance.get(null) == DungeonMobEspModule.INSTANCE,
				"Java callers retain the public static final DungeonMobEspModule.INSTANCE field");
			checkMethod("onPlayerInfoUpdate", void.class, ClientboundPlayerInfoUpdatePacket.class);
			checkMethod("onEntitySpawn", void.class, ClientboundAddEntityPacket.class);
			checkMethod("onEntityMetadataUpdated", void.class, int.class);
			checkMethod("onEntitiesRemoved", void.class, ClientboundRemoveEntitiesPacket.class);
			checkMethod("clearDungeonMinibossProfiles", void.class);
			checkMethod("tick", void.class);
			checkMethod("render", void.class, LevelRenderContext.class);
			checkMethod("renderHud", void.class, GuiGraphicsExtractor.class);
			checkMethod("restoreLegacySettings", void.class, Map.class, Map.class, Map.class, Map.class);
			checkSupportAbi();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Kotlin module Java ABI is incomplete", exception);
		}
	}

	private static void checkSupportAbi() throws ReflectiveOperationException {
		checkChestLocationAbi();
		check(Modifier.isPublic(DungeonSpecialMobRules.class.getModifiers())
			&& Modifier.isFinal(DungeonSpecialMobRules.class.getModifiers()),
			"the Kotlin rules helper keeps its public final Java class");
		check(Modifier.isPrivate(DungeonSpecialMobRules.class.getDeclaredConstructor().getModifiers()),
			"the Kotlin rules helper keeps a private utility constructor");
		checkStaticMethod(DungeonSpecialMobRules.class, "isF7WitherBossName", boolean.class, String.class);
		checkStaticMethod(DungeonSpecialMobRules.class, "shouldScanF7WitherBoss", boolean.class,
			boolean.class, boolean.class, boolean.class, boolean.class, boolean.class);
		checkStaticMethod(DungeonSpecialMobRules.class, "isEarlyF7WitherBoss", boolean.class,
			boolean.class, boolean.class, boolean.class, boolean.class);
		checkStaticMethod(DungeonSpecialMobRules.class, "isF7WitherBoss", boolean.class,
			boolean.class, boolean.class, boolean.class, String.class);
		checkStaticMethod(DungeonSpecialMobRules.class, "shouldHighlightMimic", boolean.class,
			DungeonSpecialMobRules.ChestLocation.class, DungeonSpecialMobRules.ChestLocation.class,
			boolean.class, boolean.class);
		checkStaticMethod(DungeonSpecialMobRules.class, "defaultClassColor", int.class, DungeonClass.class);

		checkRecord(DungeonMobEspSupport.WorldPosition.class, "x", "y", "z");
		checkRecord(DungeonMobEspSupport.FelCandidate.class, "entity", "starred");
		checkRecord(DungeonMobEspSupport.MinibossNameplateMatch.class, "body", "label");
		checkRecord(DungeonMobEspSupport.ScanResult.class, "matches", "nearbyEntities", "namedCandidates",
			"resolvedBodies", "unresolvedBodies", "rangeRejected", "roomRejected", "nameSamples",
			"shadowAssassins", "confirmedMinibossNameplates", "reason", "shadowAssassinDiagnostics");
		checkRecord(DungeonMobEspSupport.ShadowAssassinScanDiagnostics.class, "profilePathEnabled", "labels",
			"labelBodyAssociations", "labelRejections", "exactProfiles", "exactProfileAccepted",
			"exactProfileRangeRejections", "exactProfileRoomRejections", "exactProfileDeadRejections",
			"lastLabelRejection", "lastProfileRejection", "earlyExitReason");
		check(!DungeonMobTargetMemory.Bounds.class.isRecord()
			&& Modifier.isFinal(DungeonMobTargetMemory.Bounds.class.getModifiers()),
			"bounds stay a final Kotlin value type with Java-facing constructor/accessors");
		DungeonMobTargetMemory.Bounds.class.getConstructor(double.class, double.class, double.class,
			double.class, double.class, double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "minX", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "minY", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "minZ", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "maxX", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "maxY", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "maxZ", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "centerX", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "centerY", double.class);
		checkInstanceMethod(DungeonMobTargetMemory.Bounds.class, "centerZ", double.class);
		check(!DungeonMobTargetMemory.Target.class.isRecord()
			&& Modifier.isFinal(DungeonMobTargetMemory.Target.class.getModifiers()),
			"target snapshots stay a final Kotlin value type with Java-facing constructors/accessors");
		checkRecord(DungeonStarredMobTracker.Association.class, "nameplateId", "bodyId", "mobClass", "starred", "source");
		checkRecord(DungeonStarredMobTracker.LabelPosition.class, "x", "y", "z");
		checkRecord(DungeonStarredMobTracker.BodyCandidate.class, "entityId", "x", "y", "z");
		checkRecord(DungeonStarredMobTracker.SpatialResolution.class, "bodyId", "ambiguous");
		checkRecord(DungeonMobEspEntityCensus.Result.class, "sampleTick", "visited", "capped", "typeCounts",
			"typeCount", "omittedTypes", "candidateRows", "shadowAssassinCandidates", "omittedCandidates", "samples");
		checkRecord(DungeonMobEspKeybindMigration.Resolution.class, "keybind", "migrated", "conflict");
		checkRecord(MinibossAppearanceCapture.AppearanceIdentity.class, "textureSignature", "model");
		checkRecord(MinibossAppearanceCatalog.Identity.class, "signature", "skinModel");
		checkRecord(MinibossAppearanceCatalog.ParseResult.class, "valid", "error", "catalog", "entryCount", "unreviewedCount");
		checkRecord(MinibossAppearanceCatalogStore.LoadResult.class, "catalog", "status");

		checkStaticMethod(DungeonMobEspSupport.class, "scan", DungeonMobEspSupport.ScanResult.class,
			Minecraft.class, int.class, boolean.class, Predicate.class, boolean.class, boolean.class);
		checkStaticMethod(DungeonMobEspSupport.class, "scan", DungeonMobEspSupport.ScanResult.class,
			Minecraft.class, int.class, boolean.class, Predicate.class, boolean.class, boolean.class,
			DungeonRoomTracker.MapRoomFootprint.class);
		checkStaticMethod(DungeonMobEspSupport.class, "scan", DungeonMobEspSupport.ScanResult.class,
			Minecraft.class, int.class, boolean.class, Predicate.class, boolean.class, boolean.class,
			DungeonRoomTracker.MapRoomFootprint.class, Predicate.class);
		checkStaticMethod(DungeonMobEspSupport.class, "scan", DungeonMobEspSupport.ScanResult.class,
			Minecraft.class, int.class, boolean.class, Predicate.class, boolean.class, boolean.class,
			DungeonRoomTracker.MapRoomFootprint.class, Predicate.class, boolean.class);
		checkStaticMethod(DungeonMobEspSupport.class, "findFelSkullMarkers", List.class,
			Minecraft.class, int.class, boolean.class, DungeonRoomTracker.MapRoomFootprint.class);
		checkStaticMethod(DungeonMobEspSupport.class, "findFels", List.class,
			Minecraft.class, int.class, boolean.class);
		checkStaticMethod(DungeonMobEspSupport.class, "findFels", List.class,
			Minecraft.class, int.class, boolean.class, DungeonRoomTracker.MapRoomFootprint.class);
		checkStaticMethod(DungeonMobEspSupport.class, "isFelSkullMarker", boolean.class,
			boolean.class, boolean.class, String.class);
		checkStaticMethod(DungeonMobEspSupport.ScanResult.class, "empty",
			DungeonMobEspSupport.ScanResult.class, String.class);
		checkStaticMethod(DungeonMobEspSupport.ShadowAssassinScanDiagnostics.class, "empty",
			DungeonMobEspSupport.ShadowAssassinScanDiagnostics.class, String.class);
		checkStaticMethod(DungeonMobTargetMemory.class, "isPersistent", boolean.class,
			DungeonMobTargetMemory.Group.class);
		check(Modifier.isFinal(DungeonMobTargetMemory.class.getModifiers())
			&& DungeonMobTargetMemory.MAX_TARGETS == 512,
			"target memory retains its final Kotlin type and 512-entry cap");
		checkInstanceMethod(DungeonMobTargetMemory.class, "update", List.class,
			java.util.Collection.class, long.class, java.util.function.Function.class,
			java.util.function.Function.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "update", List.class,
			java.util.Collection.class, String.class, int.class, java.util.function.Function.class,
			java.util.function.Function.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "clearGroup", void.class,
			DungeonMobTargetMemory.Group.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "removeEntityId", void.class, int.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "clearTransientGroups", void.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "clear", void.class);
		checkInstanceMethod(DungeonMobTargetMemory.class, "size", int.class);
		checkStaticMethod(DungeonStarredMobTracker.class, "resolveSpatialBodyId",
			DungeonStarredMobTracker.SpatialResolution.class, DungeonStarredMobTracker.LabelPosition.class, List.class);
		checkStaticMethod(DungeonMobEspEntityCensus.class, "capture", DungeonMobEspEntityCensus.Result.class,
			net.minecraft.client.multiplayer.ClientLevel.class, long.class, List.class, List.class, java.util.Set.class,
			DungeonMinibossProfileTracker.class, MinibossProfileMatchPolicy.IdentitySnapshot.class,
			boolean.class, boolean.class, boolean.class, DungeonRoomTracker.MapRoomFootprint.class);
		checkStaticMethod(DungeonMobEspKeybindMigration.class, "resolve",
			DungeonMobEspKeybindMigration.Resolution.class, boolean.class,
			geiler.addons.client.module.ModuleKeybind.class, geiler.addons.client.module.ModuleKeybind.class,
			geiler.addons.client.module.ModuleKeybind.class);
		checkStaticMethod(MinibossAppearanceCapture.class, "appearanceIdentity",
			MinibossAppearanceCapture.AppearanceIdentity.class, com.mojang.authlib.GameProfile.class);
		checkStaticMethod(MinibossAppearanceCatalog.class, "parse", MinibossAppearanceCatalog.ParseResult.class,
			String.class);
		checkStaticMethod(MinibossAppearanceCatalogStore.class, "load",
			MinibossAppearanceCatalogStore.LoadResult.class);
		checkStaticMethod(FelSkullMarkerGeometry.class, "bounds", DungeonMobTargetMemory.Bounds.class,
			net.minecraft.world.entity.LivingEntity.class);
		checkStaticMethod(FelSkullMarkerGeometry.class, "bounds", DungeonMobTargetMemory.Bounds.class,
			double.class, double.class, double.class);

		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "uuid", java.util.UUID.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "entityId", int.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "group", DungeonMobTargetMemory.Group.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "type", String.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "label", String.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "bounds", DungeonMobTargetMemory.Bounds.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "roomKey", String.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "seenAtTick", long.class);
		checkInstanceMethod(DungeonMobTargetMemory.Target.class, "fullShadowAssassin", boolean.class);
		DungeonMobTargetMemory.Target.class.getDeclaredConstructor(java.util.UUID.class, int.class,
			DungeonMobTargetMemory.Group.class, String.class, String.class, DungeonMobTargetMemory.Bounds.class,
			String.class, long.class);
		DungeonMobTargetMemory.Target.class.getDeclaredConstructor(java.util.UUID.class, int.class,
			DungeonMobTargetMemory.Group.class, String.class, String.class, DungeonMobTargetMemory.Bounds.class,
			String.class, long.class, boolean.class);
		check(MinibossProfileMatchPolicy.IdentitySnapshot.class.getMethod("partyPlayerIds").getReturnType() == java.util.Set.class
			&& MinibossProfileMatchPolicy.IdentitySnapshot.class.getMethod("identifies", java.util.UUID.class, String.class)
				.getReturnType() == boolean.class,
			"identity snapshots retain normalized Java accessors and matching method");
		checkStaticMethod(MinibossProfileMatchPolicy.IdentitySnapshot.class, "empty",
			MinibossProfileMatchPolicy.IdentitySnapshot.class);

		checkInstanceMethod(DungeonMobEncounterHistory.class, "observe", void.class,
			String.class, double.class, double.class, double.class);
		checkInstanceMethod(DungeonMobEncounterHistory.class, "positions", List.class, String.class);
		checkInstanceMethod(DungeonMobEncounterHistory.class, "clearRoom", void.class, String.class);
		checkInstanceMethod(DungeonMobEncounterHistory.class, "clear", void.class);
		checkInstanceMethod(DungeonMobEncounterHistory.class, "roomCount", int.class);
	}

	private static void checkRecord(Class<?> type, String... componentNames) throws ReflectiveOperationException {
		check(type.isRecord(), type.getSimpleName() + " retains its Java record ABI");
		RecordComponent[] components = type.getRecordComponents();
		check(Arrays.stream(components).map(RecordComponent::getName).toList().equals(List.of(componentNames)),
			type.getSimpleName() + " retains its Java record component order");
		Class<?>[] constructorTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
		type.getDeclaredConstructor(constructorTypes);
		for (RecordComponent component : components) {
			check(type.getMethod(component.getName()).getReturnType() == component.getType(),
				type.getSimpleName() + " retains accessor " + component.getName() + "()");
		}
	}

	private static void checkChestLocationAbi() throws ReflectiveOperationException {
		Class<?> type = DungeonSpecialMobRules.ChestLocation.class;
		check(Modifier.isPublic(type.getModifiers()) && Modifier.isStatic(type.getModifiers())
			&& Modifier.isFinal(type.getModifiers()) && !type.isRecord(),
			"ChestLocation remains a public final nested Kotlin value type; Java record metadata is not required");
		check(Modifier.isPublic(type.getConstructor(String.class, int.class, int.class, int.class).getModifiers()),
			"Java callers retain the public ChestLocation(String, int, int, int) constructor");
		checkInstanceMethod(type, "roomId", String.class);
		checkInstanceMethod(type, "x", int.class);
		checkInstanceMethod(type, "y", int.class);
		checkInstanceMethod(type, "z", int.class);
		Method copy = type.getMethod("copy", String.class, int.class, int.class, int.class);
		check(copy.getReturnType() == type && !Modifier.isStatic(copy.getModifiers()),
			"Kotlin callers retain immutable value-copy behavior on ChestLocation");

		boolean immutableFields = Arrays.stream(type.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
			.allMatch(field -> Modifier.isPrivate(field.getModifiers()) && Modifier.isFinal(field.getModifiers()));
		check(immutableFields, "ChestLocation stores its room and coordinates in private final fields");
	}

	private static void checkSpecialMobRules() {
		DungeonSpecialMobRules.ChestLocation known = new DungeonSpecialMobRules.ChestLocation(" \troom-a\r\n", 10, 70, 20);
		DungeonSpecialMobRules.ChestLocation observed = new DungeonSpecialMobRules.ChestLocation("room-a", 10, 70, 20);
		check(known.roomId().equals("room-a") && known.equals(observed) && known.hashCode() == observed.hashCode()
			&& known.hashCode() == -1114228791,
			"ChestLocation keeps Java trim normalization, record equality, and hash behavior");
		check(known.toString().equals("ChestLocation[roomId=room-a, x=10, y=70, z=20]"),
			"ChestLocation keeps the Java record string form");
		check(new DungeonSpecialMobRules.ChestLocation(null, 10, 70, 20).roomId().isEmpty()
			&& new DungeonSpecialMobRules.ChestLocation("\u00A0room-a\u00A0", 10, 70, 20).roomId()
				.equals("\u00A0room-a\u00A0"),
			"ChestLocation normalizes null but preserves characters outside Java String.trim's range");
		DungeonSpecialMobRules.ChestLocation changedCopy = known.copy("\troom-a\r\n", 11, 70, 20);
		check(changedCopy != known && changedCopy.roomId().equals("room-a") && changedCopy.x() == 11
			&& known.x() == 10 && !changedCopy.equals(known),
			"ChestLocation copy normalizes its new value without mutating the original");

		check(DungeonSpecialMobRules.shouldHighlightMimic(known, observed, true, true),
			"Mimic highlighting requires the exact normalized room and chest coordinate, trapped state, and Clear phase");
		check(!DungeonSpecialMobRules.shouldHighlightMimic(null, observed, true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known, null, true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known,
				new DungeonSpecialMobRules.ChestLocation("room-a", 11, 70, 20), true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known,
				new DungeonSpecialMobRules.ChestLocation("room-b", 10, 70, 20), true, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known, observed, false, true)
			&& !DungeonSpecialMobRules.shouldHighlightMimic(known, observed, true, false),
			"Mimic highlighting rejects unknown, mismatched, untrapped, and non-Clear candidates");
		DungeonSpecialMobRules.ChestLocation whitespaceRoom =
			new DungeonSpecialMobRules.ChestLocation(" \t\r\n", 10, 70, 20);
		check(!DungeonSpecialMobRules.shouldHighlightMimic(whitespaceRoom, whitespaceRoom, true, true),
			"Mimic highlighting rejects an empty Java-trimmed room identifier");
		DungeonSpecialMobRules.ChestLocation nonBreakingSpaceRoom =
			new DungeonSpecialMobRules.ChestLocation("\u00A0", 10, 70, 20);
		check(DungeonSpecialMobRules.shouldHighlightMimic(nonBreakingSpaceRoom, nonBreakingSpaceRoom, true, true),
			"Mimic room validation retains Java String.isBlank behavior for non-breaking spaces");

		check(DungeonSpecialMobRules.isF7WitherBossName("\u00A7cW\uE001ITHER")
			&& !DungeonSpecialMobRules.isF7WitherBossName("Wither King")
			&& !DungeonSpecialMobRules.isF7WitherBossName(null),
			"Wither name normalization strips formatting and private-use characters while retaining exact identity");
		check(DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, false, true)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, false, false)
			&& DungeonSpecialMobRules.shouldScanF7WitherBoss(true, true, true, true, true)
			&& !DungeonSpecialMobRules.shouldScanF7WitherBoss(true, false, true, false, true),
			"Wither scanning is limited to enabled F7, regular or Master, after the boss-phase signal");
		for (boolean enabled : new boolean[] {false, true}) {
			for (boolean inDungeon : new boolean[] {false, true}) {
				for (boolean floorSeven : new boolean[] {false, true}) {
					for (boolean masterMode : new boolean[] {false, true}) {
						for (boolean phaseObserved : new boolean[] {false, true}) {
							boolean expected = enabled && inDungeon && floorSeven && phaseObserved;
							check(DungeonSpecialMobRules.shouldScanF7WitherBoss(enabled, inDungeon, floorSeven,
								masterMode, phaseObserved) == expected,
								"Wither scanning truth table preserves each independent gate");
						}
					}
				}
			}
		}
		check(DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, true, true)
			&& !DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, false, true)
			&& DungeonSpecialMobRules.isEarlyF7WitherBoss(true, true, true, true)
			&& !DungeonSpecialMobRules.isEarlyF7WitherBoss(true, false, true, false),
			"early Wither entity detection remains phase-gated and includes Master Mode");
		check(DungeonSpecialMobRules.isF7WitherBoss(true, false, true, "§cWither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(false, false, true, "Wither")
			&& DungeonSpecialMobRules.isF7WitherBoss(true, true, true, "Wither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(true, false, false, "Wither")
			&& !DungeonSpecialMobRules.isF7WitherBoss(true, false, true, null),
			"named Wither bosses retain the F7 (regular or Master), entity-type, and exact-name gates");
		check(DungeonSpecialMobRules.defaultClassColor(DungeonClass.ARCHER) == 0xFFFFAA00
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.BERSERK) == 0xFFAA0000
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.HEALER) == 0xFFFF55FF
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.MAGE) == 0xFF55FFFF
			&& DungeonSpecialMobRules.defaultClassColor(DungeonClass.TANK) == 0xFF00AA00,
			"party-class defaults retain the frozen palette");
		check(DungeonSpecialMobRules.defaultClassColor(null) == 0xFFFFFFFF,
			"unknown party class retains the opaque white fallback color");
	}

	private static void checkStaticMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) throws ReflectiveOperationException {
		Method method = owner.getMethod(name, parameterTypes);
		check(method.getReturnType() == returnType && Modifier.isStatic(method.getModifiers()),
			"Java callers retain static method " + owner.getSimpleName() + "." + name + " with its expected signature");
	}

	private static void checkPackageStaticMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) throws ReflectiveOperationException {
		Method method = owner.getDeclaredMethod(name, parameterTypes);
		int access = method.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
		check(method.getReturnType() == returnType && Modifier.isStatic(method.getModifiers()) && access == 0,
			"Java callers retain package static method " + owner.getSimpleName() + "." + name + " with its expected signature");
	}

	private static void checkPackageInstanceMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) throws ReflectiveOperationException {
		Method method = owner.getDeclaredMethod(name, parameterTypes);
		int access = method.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
		check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()) && access == 0,
			"Java callers retain package instance method " + owner.getSimpleName() + "." + name + " with its expected signature");
	}

	private static void checkInstanceMethod(Class<?> owner, String name, Class<?> returnType,
		Class<?>... parameterTypes) throws ReflectiveOperationException {
		Method method = owner.getMethod(name, parameterTypes);
		check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()),
			"Java callers retain instance method " + owner.getSimpleName() + "." + name + " with its expected signature");
	}

	private static void checkMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
		throws ReflectiveOperationException {
		Method method = DungeonMobEspModule.class.getMethod(name, parameterTypes);
		check(method.getReturnType() == returnType && !Modifier.isStatic(method.getModifiers()),
			"Java callers retain instance method " + name + " with its expected signature");
	}

	private static void checkIdentityAndGroups(DungeonMobEspModule module) {
		check(module.name().equals("Dungeon Mob ESP") && module.configName().equals("Dungeon Mob ESP")
			&& module.description().equals("Highlights starred dungeon mobs, minibosses, and Fels.")
			&& module.category() == Category.F7 && !module.defaultEnabled() && module.hasSettings(),
			"module identity, F7 category, disabled default, and stable config key are preserved");

		List<SettingGroup> groups = module.groups();
		check(groups.size() == 5 && groups.get(0).name() == null,
			"the module keeps its shared settings row and four named setting groups");
		checkGroup(groups.get(0), null, null, List.of("Depth Check", "Debug Target Matching"));
		checkGroup(groups.get(1), "Fels", "Fels Enabled", List.of(
			"Fels Only Current Room", "Highlight Starred Fels", "Highlight Hidden Fel Skulls", "Fel Style",
			"Fel Outline Color", "Fel Fill Color", "Fel Outline Width", "Fel Ring Count", "Fel Ring Height",
			"Fel Ring Radius", "Fel Ring Speed", "Fel Ring Line Width", "Fel Skull Tracer", "Fel Tracer Color",
			"Fel Tracer Width"));
		checkGroup(groups.get(2), "Starred Mobs", "Starred Group Enabled", List.of(
			"Starred Only Current Room", "Starred Range", "Starred Style", "Starred Outline Color",
			"Starred Fill Color", "Starred Outline Width", "Starred Ring Count", "Starred Ring Height",
			"Starred Ring Radius", "Starred Ring Speed", "Starred Ring Line Width"));
		checkGroup(groups.get(3), "Minibosses", "Minibosses Enabled", List.of(
			"Miniboss Range", "Miniboss Only Current Room", "Only Starred Minibosses", "Miniboss Style",
			"Miniboss Outline Color", "Miniboss Fill Color", "Miniboss Outline Width", "Miniboss Tracer",
			"Miniboss Tracer Color", "Miniboss Tracer Width", "Miniboss Ring Count", "Miniboss Ring Height",
			"Miniboss Ring Radius", "Miniboss Ring Speed", "Miniboss Ring Line Width"));
		checkGroup(groups.get(4), "Automatic miniboss appearance capture", "Capture Miniboss Appearances",
			List.of("Optional Manual Appearance Label", "Capture Crosshair Player Profile"));
		check(module.actions().size() == 1
			&& module.actions().getFirst().label().equals("Capture Crosshair Player Profile"),
			"the manual appearance capture action remains available in the module settings list");
	}

	private static void checkGroup(SettingGroup group, String expectedName, String expectedToggle,
		List<String> expectedRows) {
		check(expectedName == null ? group.name() == null : expectedName.equals(group.name()),
			"setting group name remains " + expectedName);
		check(expectedToggle == null ? group.toggle() == null
			: group.toggle() != null && expectedToggle.equals(group.toggle().name()),
			"setting group toggle remains " + expectedToggle);
		check(settingNames(group.settings()).equals(expectedRows),
			"setting group rows and order remain unchanged for " + expectedName);
	}

	private static void checkSettings(DungeonMobEspModule module) {
		check(settingNames(module.booleanSettings()).equals(List.of(
			"Starred Group Enabled", "Starred Only Current Room", "Depth Check", "Minibosses Enabled",
			"Miniboss Only Current Room", "Only Starred Minibosses", "Miniboss Tracer", "Capture Miniboss Appearances",
			"Fels Enabled", "Fels Only Current Room", "Highlight Starred Fels", "Highlight Hidden Fel Skulls",
			"Fel Skull Tracer", "Debug Target Matching")),
			"all persisted boolean setting IDs remain present in declaration order");
		check(module.booleanSettings().stream().map(BooleanSetting::rawValue).toList().equals(List.of(
			true, false, false, true, false, false, true, false, true, false, true, true, true, false)),
			"all boolean setting defaults remain unchanged");

		check(settingNames(module.numberSettings()).equals(List.of(
			"Starred Range", "Starred Outline Width", "Starred Ring Count", "Starred Ring Height",
			"Starred Ring Radius", "Starred Ring Speed", "Starred Ring Line Width", "Miniboss Range",
			"Miniboss Outline Width", "Miniboss Tracer Width", "Miniboss Ring Count", "Miniboss Ring Height",
			"Miniboss Ring Radius", "Miniboss Ring Speed", "Miniboss Ring Line Width", "Fel Outline Width",
			"Fel Ring Count", "Fel Ring Height", "Fel Ring Radius", "Fel Ring Speed", "Fel Ring Line Width",
			"Fel Tracer Width")),
			"all persisted number setting IDs remain present in declaration order");
		checkNumber(module, "Starred Range", 8, 128, 128, true);
		checkNumber(module, "Starred Outline Width", 0.5f, 25, 5, false);
		checkNumber(module, "Starred Ring Count", 1, 8, 3, true);
		checkNumber(module, "Starred Ring Height", 0.4f, 6, 2, false);
		checkNumber(module, "Starred Ring Radius", 0.1f, 3.5f, 0.65f, false);
		checkNumber(module, "Starred Ring Speed", 0.1f, 5, 0.8f, false);
		checkNumber(module, "Starred Ring Line Width", 0.5f, 5, 1.5f, false);
		checkNumber(module, "Miniboss Range", 8, 128, 64, true);
		checkNumber(module, "Miniboss Outline Width", 0.5f, 25, 5, false);
		checkNumber(module, "Miniboss Tracer Width", 0.5f, 5, 1.5f, false);
		checkNumber(module, "Miniboss Ring Count", 1, 8, 3, true);
		checkNumber(module, "Miniboss Ring Height", 0.4f, 6, 2, false);
		checkNumber(module, "Miniboss Ring Radius", 0.1f, 3.5f, 0.8f, false);
		checkNumber(module, "Miniboss Ring Speed", 0.1f, 5, 0.8f, false);
		checkNumber(module, "Miniboss Ring Line Width", 0.5f, 5, 1.5f, false);
		checkNumber(module, "Fel Outline Width", 0.5f, 25, 5, false);
		checkNumber(module, "Fel Ring Count", 1, 8, 3, true);
		checkNumber(module, "Fel Ring Height", 0.4f, 6, 2, false);
		checkNumber(module, "Fel Ring Radius", 0.1f, 3.5f, 0.65f, false);
		checkNumber(module, "Fel Ring Speed", 0.1f, 5, 0.8f, false);
		checkNumber(module, "Fel Ring Line Width", 0.5f, 5, 1.5f, false);
		checkNumber(module, "Fel Tracer Width", 0.5f, 5, 1.5f, false);

		check(settingNames(module.choiceSettings()).equals(List.of("Starred Style", "Miniboss Style", "Fel Style")),
			"all three rendering-style setting IDs remain stable");
		List<String> styles = List.of("Chams", "3D Box", "2D Box", "Ring");
		for (String name : List.of("Starred Style", "Miniboss Style", "Fel Style")) {
			ChoiceSetting setting = choice(module, name);
			check(setting.value().equals("Chams") && setting.choices().equals(styles),
				"default style and available render choices remain unchanged for " + name);
		}

		check(settingNames(module.colorSettings()).equals(List.of(
			"Starred Outline Color", "Starred Fill Color", "Miniboss Outline Color", "Miniboss Fill Color",
			"Miniboss Tracer Color", "Fel Outline Color", "Fel Fill Color", "Fel Tracer Color")),
			"all color setting IDs remain present in declaration order");
		check(colorHex(module).equals(List.of(
			"Starred Outline Color=FFFFFFFF", "Starred Fill Color=000000FF", "Miniboss Outline Color=000000FF",
			"Miniboss Fill Color=FFFFFFFF", "Miniboss Tracer Color=FFFFFFDC", "Fel Outline Color=FFFFFFFF",
			"Fel Fill Color=FFFFFFFF", "Fel Tracer Color=FFFFFFDC")),
			"all color defaults and alpha values remain unchanged");

		check(module.textSettings().size() == 1
			&& module.textSettings().getFirst().name().equals("Optional Manual Appearance Label")
			&& module.textSettings().getFirst().displayName().equals("Optional Manual Appearance Label")
			&& module.textSettings().getFirst().value().isEmpty()
			&& module.textSettings().getFirst().maxLength() == 80,
			"manual appearance label key, label, empty default, and 80-character limit are preserved");

		try {
			ChoiceSetting starredStyle = choice(module, "Starred Style");
			NumberSetting starredRings = number(module, "Starred Ring Count");
			NumberSetting starredWidth = number(module, "Starred Outline Width");
			check(!module.isSettingVisible(starredRings) && module.isSettingVisible(starredWidth),
				"ring controls stay hidden until Ring style is selected");
			starredStyle.setValue("Ring");
			check(module.isSettingVisible(starredRings) && !module.isSettingVisible(starredWidth),
				"Ring style shows ring controls and hides the ordinary outline width");
			BooleanSetting minibossTracer = bool(module, "Miniboss Tracer");
			minibossTracer.setValue(false);
			check(!module.isSettingVisible(color(module, "Miniboss Tracer Color"))
				&& !module.isSettingVisible(number(module, "Miniboss Tracer Width")),
				"miniboss tracer settings follow the tracer switch");
			BooleanSetting felSkulls = bool(module, "Highlight Hidden Fel Skulls");
			felSkulls.setValue(false);
			check(!module.isSettingVisible(bool(module, "Fel Skull Tracer"))
				&& !module.isSettingVisible(color(module, "Fel Tracer Color"))
				&& !module.isSettingVisible(number(module, "Fel Tracer Width")),
				"Fel tracer controls follow the hidden-skull switch");
			BooleanSetting capture = bool(module, "Capture Miniboss Appearances");
			check(!module.isSettingVisible(module.textSettings().getFirst())
				&& !module.isSettingVisible(module.actions().getFirst()),
				"manual capture controls start hidden while automatic appearance capture is off");
			capture.setValue(true);
			check(module.isSettingVisible(module.textSettings().getFirst())
				&& module.isSettingVisible(module.actions().getFirst()),
				"enabling appearance capture reveals its label and manual action");
		} finally {
			resetSettings(module);
		}
	}

	private static void checkLegacySettings(DungeonMobEspModule module) {
		resetSettings(module);
		try {
			// ModConfig loads current keys before the module applies any retired-key fallbacks.
			number(module, "Miniboss Range").setValue(80.0f);
			number(module, "Starred Outline Width").setValue(7.0f);
			number(module, "Miniboss Outline Width").setValue(9.0f);
			number(module, "Miniboss Tracer Width").setValue(3.0f);
			bool(module, "Depth Check").setValue(false);
			bool(module, "Miniboss Only Current Room").setValue(false);
			bool(module, "Miniboss Tracer").setValue(false);
			choice(module, "Starred Style").setValue("Ring");
			color(module, "Starred Outline Color").set(1, 2, 3, 4);
			module.restoreLegacySettings(
				Map.of(
					"Starred Mob ESP.Range", 96.0f,
					"Miniboss ESP.Range", 40.0f,
					"Dungeon Mob ESP.Miniboss Range", 80.0f,
					"Dungeon Mob ESP.Starred Outline Width", 7.0f,
					"Dungeon Mob ESP.Starred Line Width", 3.0f,
					"Dungeon Mob ESP.Miniboss Outline Width", 9.0f,
					"Dungeon Mob ESP.Miniboss Line Width", 4.0f,
					"Miniboss ESP.Tracer Width", 1.0f,
					"Dungeon Mob ESP.Miniboss Tracer Width", 3.0f),
				Map.of(
					"Starred Mob ESP.Depth Check", true,
					"Miniboss ESP.Depth Check", false,
					"Dungeon Mob ESP.Depth Check", false,
					"Miniboss ESP.Only Current Room", true,
					"Dungeon Mob ESP.Miniboss Only Current Room", false,
					"Miniboss ESP.Only Starred Minibosses", true,
					"Miniboss ESP.Tracer", true,
					"Dungeon Mob ESP.Miniboss Tracer", false),
				Map.of(
					"Starred Mob ESP.Style", "2D Box",
					"Dungeon Mob ESP.Starred Style", "Ring",
					"Miniboss ESP.Style", "2D Box"),
				Map.of(
					"Starred Mob ESP.Outline Color", new int[] {10, 20, 30, 40},
					"Dungeon Mob ESP.Starred Outline Color", new int[] {1, 2, 3, 4},
					"Miniboss ESP.Tracer Color", new int[] {40, 50, 60, 70}));

			check(Float.compare(number(module, "Starred Range").value(), 96.0f) == 0
				&& Float.compare(number(module, "Miniboss Range").value(), 80.0f) == 0,
				"legacy ranges migrate and current module config values take precedence");
		check(Float.compare(number(module, "Starred Outline Width").value(), 7.0f) == 0
			&& Float.compare(number(module, "Miniboss Outline Width").value(), 9.0f) == 0
			&& Float.compare(number(module, "Miniboss Tracer Width").value(), 3.0f) == 0,
			"line-width compatibility keys do not overwrite saved current settings");
		check(!bool(module, "Depth Check").rawValue()
			&& !bool(module, "Miniboss Only Current Room").rawValue()
			&& bool(module, "Only Starred Minibosses").rawValue()
			&& !bool(module, "Miniboss Tracer").rawValue(),
			"legacy booleans restore without overriding current keys");
		check(choice(module, "Starred Style").value().equals("Ring")
			&& choice(module, "Miniboss Style").value().equals("2D Box"),
			"legacy choices restore while current module choices retain precedence");
		check(color(module, "Starred Outline Color").red() == 1
			&& color(module, "Starred Outline Color").green() == 2
			&& color(module, "Miniboss Tracer Color").red() == 40,
			"legacy color records restore while a current color key wins");

		resetSettings(module);
		module.restoreLegacySettings(Map.of(), Map.of("Miniboss ESP.Depth Check", true), Map.of(), Map.of());
		check(bool(module, "Depth Check").rawValue(),
			"depth-check migration retains the older miniboss-group fallback when the starred key is absent");
		} finally {
			resetSettings(module);
		}
	}

	private static void checkKeybindMigration() {
		ModuleKeybind starred = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_G, 0, 0));
		ModuleKeybind miniboss = ModuleKeybind.from(new KeyEvent(InputConstants.KEY_H, 0, 0));
		DungeonMobEspKeybindMigration.Resolution currentWins = DungeonMobEspKeybindMigration.resolve(
			true, starred, miniboss, ModuleKeybind.NONE);
		check(currentWins.keybind().equals(starred) && !currentWins.migrated() && !currentWins.conflict(),
			"an existing combined Dungeon Mob ESP bind wins over legacy values");

		DungeonMobEspKeybindMigration.Resolution explicitNoneWins = DungeonMobEspKeybindMigration.resolve(
			true, ModuleKeybind.NONE, starred, miniboss);
		check(explicitNoneWins.keybind().equals(ModuleKeybind.NONE) && !explicitNoneWins.migrated(),
			"an explicitly saved unbound combined module is not repopulated from legacy binds");

		DungeonMobEspKeybindMigration.Resolution oneLegacyBind = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, ModuleKeybind.NONE);
		check(oneLegacyBind.keybind().equals(starred) && oneLegacyBind.migrated() && !oneLegacyBind.conflict(),
			"one non-none legacy bind migrates to the combined module");

		DungeonMobEspKeybindMigration.Resolution equalLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, starred);
		check(equalLegacyBinds.keybind().equals(starred) && equalLegacyBinds.migrated()
			&& !equalLegacyBinds.conflict(), "equal legacy binds migrate once without ambiguity");

		DungeonMobEspKeybindMigration.Resolution conflictingLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, starred, miniboss);
		check(conflictingLegacyBinds.keybind().equals(ModuleKeybind.NONE)
			&& conflictingLegacyBinds.migrated() && conflictingLegacyBinds.conflict(),
			"conflicting legacy binds resolve to no bind and mark the migration as ambiguous");

		DungeonMobEspKeybindMigration.Resolution noLegacyBinds = DungeonMobEspKeybindMigration.resolve(
			false, null, ModuleKeybind.NONE, ModuleKeybind.NONE);
		check(noLegacyBinds.keybind().equals(ModuleKeybind.NONE) && !noLegacyBinds.migrated(),
			"no legacy bind leaves the combined module unbound");
	}

	private static void checkNumber(DungeonMobEspModule module, String name,
		float minimum, float maximum, float defaultValue, boolean integer) {
		NumberSetting setting = number(module, name);
		check(Float.compare(setting.min(), minimum) == 0 && Float.compare(setting.max(), maximum) == 0
			&& Float.compare(setting.value(), defaultValue) == 0 && setting.isInteger() == integer,
			"number bounds, default, and integral mode remain unchanged for " + name);
	}

	private static List<String> colorHex(DungeonMobEspModule module) {
		return module.colorSettings().stream().map(setting -> setting.name() + "=" + setting.hex()).toList();
	}

	private static List<String> settingNames(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static BooleanSetting bool(DungeonMobEspModule module, String name) {
		return module.booleanSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static NumberSetting number(DungeonMobEspModule module, String name) {
		return module.numberSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static ChoiceSetting choice(DungeonMobEspModule module, String name) {
		return module.choiceSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static ColorSetting color(DungeonMobEspModule module, String name) {
		return module.colorSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static void resetSettings(DungeonMobEspModule module) {
		module.booleanSettings().forEach(BooleanSetting::reset);
		module.numberSettings().forEach(NumberSetting::reset);
		module.choiceSettings().forEach(ChoiceSetting::reset);
		module.colorSettings().forEach(ColorSetting::reset);
		module.textSettings().forEach(TextSetting::reset);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
