package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.helpers.DungeonLividTracker;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.Module;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Optional;

/** Direct Java ABI checks for the Kotlin dungeon event and HUD adapter. */
public final class DungeonAssistModuleKotlinChecks {
	private DungeonAssistModuleKotlinChecks() { }

	public static void main(String[] args) {
		run();
		System.out.println("Dungeon Odin Assist Kotlin checks passed.");
	}

	public static void run() {
		checkSingletonAndModuleIdentity();
		checkJavaEntryPoints();
		checkBlazeObservationRecord();
		checkLeapDeathRule();
	}

	private static void checkSingletonAndModuleIdentity() {
		try {
			Field instanceField = DungeonAssistModule.class.getField("INSTANCE");
			check(instanceField.getType() == DungeonAssistModule.class
					&& Modifier.isStatic(instanceField.getModifiers())
					&& Modifier.isFinal(instanceField.getModifiers()),
				"Java callers retain the public static final singleton field");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Dungeon Odin Assist singleton ABI changed", exception);
		}
		DungeonAssistModule module = DungeonAssistModule.INSTANCE;
		check(module instanceof Module, "the Kotlin adapter remains a Java Module");
		check("Dungeon Feature Runtime".equals(module.name())
			&& "Dungeon Feature Runtime".equals(module.configName()),
			"the internal adapter display and config identity remain unchanged");
		check("Internal event adapter for independent dungeon modules.".equals(module.description()),
			"the internal adapter description remains unchanged");
		check(module.category() == Category.F7 && !module.defaultEnabled(),
			"the adapter remains disabled and categorized in F7 without adding a registry module");
	}

	private static void checkJavaEntryPoints() {
		assertPublicMethod("tick", void.class, long.class, long.class);
		assertPublicMethod("render", void.class,
			net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext.class);
		assertPublicMethod("renderHud", void.class, net.minecraft.client.gui.GuiGraphicsExtractor.class);
		assertPublicMethod("onChat", void.class, String.class, long.class, long.class);
		assertPublicMethod("onChat", void.class, String.class, long.class, long.class, Integer.class);
		assertPublicMethod("onTerminalTitle", String.class, String.class, String.class);
		assertPublicMethod("replaceCreeperBeamHitSound", SoundInstance.class, SoundInstance.class);
		assertPublicMethod("handleSpiritLeapKey", boolean.class, String.class,
			ExperimentSolverModule.SlotClickDispatcher.class);
		assertPublicMethod("handleSpiritLeapMouseRelease", boolean.class, AbstractContainerScreen.class,
			double.class, double.class, int.class, ExperimentSolverModule.SlotClickDispatcher.class);
		assertPublicMethod("renderSpiritLeapScreen", boolean.class,
			net.minecraft.client.gui.GuiGraphicsExtractor.class,
			net.minecraft.client.gui.Font.class, int.class, int.class, AbstractContainerScreen.class,
			int.class, int.class);
		assertPublicMethod("onPuzzleBlockEvent", void.class, net.minecraft.network.protocol.game.ClientboundBlockEventPacket.class);
		assertPublicMethod("onPuzzleBlockChange", void.class, net.minecraft.core.BlockPos.class,
			net.minecraft.world.level.block.state.BlockState.class, net.minecraft.core.BlockPos.class,
			net.minecraft.world.level.block.state.BlockState.class);
		assertPublicMethod("resetAfterDisconnect", void.class,
			net.minecraft.client.multiplayer.ClientPacketListener.class, net.minecraft.client.Minecraft.class);
		assertPublicMethod("publishedLividTarget", DungeonLividTracker.TargetSnapshot.class, boolean.class);
		assertPublicStaticMethod("isLoadedLeapMemberDead", boolean.class, boolean.class, float.class);
		assertPublicStaticMethod("dispatchSpiritLeapTarget", boolean.class, AbstractContainerMenu.class,
			Optional.class, ExperimentSolverModule.SlotClickDispatcher.class);
	}

	private static void checkBlazeObservationRecord() {
		Class<?> type = DungeonAssistModule.BlazeObservation.class;
		check(type.isRecord(), "BlazeObservation retains Java record metadata");
		RecordComponent[] components = type.getRecordComponents();
		check(components.length == 2
				&& "entityId".equals(components[0].getName())
				&& components[0].getType() == String.class
				&& "currentHealth".equals(components[1].getName())
				&& components[1].getType() == int.class,
			"BlazeObservation retains its component names, order, and types");
		DungeonAssistModule.BlazeObservation observation =
			new DungeonAssistModule.BlazeObservation(null, 20);
		check(observation.entityId() == null && observation.currentHealth() == 20,
			"BlazeObservation preserves nullable Java record values and accessors");
	}

	private static void checkLeapDeathRule() {
		check(!DungeonAssistModule.isLoadedLeapMemberDead(true, 20.0f),
			"a living loaded leap member with health is not marked dead");
		check(DungeonAssistModule.isLoadedLeapMemberDead(false, 20.0f)
				&& DungeonAssistModule.isLoadedLeapMemberDead(true, 0.0f),
			"the leap death rule still honors entity death and zero health");
	}

	private static void assertPublicMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = DungeonAssistModule.class.getMethod(name, parameterTypes);
			check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
					&& !Modifier.isStatic(method.getModifiers()),
				"Java callers retain the instance method signature " + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Dungeon Odin Assist instance method is missing: " + name, exception);
		}
	}

	private static void assertPublicStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes) {
		try {
			Method method = DungeonAssistModule.class.getMethod(name, parameterTypes);
			check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
					&& Modifier.isStatic(method.getModifiers()),
				"Java callers retain the static method signature " + name);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Dungeon Odin Assist static method is missing: " + name, exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
