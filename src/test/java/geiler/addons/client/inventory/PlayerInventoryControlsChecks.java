package geiler.addons.client.inventory;

import geiler.addons.client.config.ConfigProfileCodec;
import geiler.addons.client.config.ConfigProfileInstaller;
import geiler.addons.client.gui.PlayerInventorySlotOverlay;
import geiler.addons.client.module.impl.PlayerInventoryControlsModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Isolated acceptance checks for inventory-lock boundaries and file-backed settings persistence. */
public final class PlayerInventoryControlsChecks {
	private PlayerInventoryControlsChecks() { }

	public static void main(String[] args) {
		run();
	}

	/** A lock toggle must not read and write the settings file on the client thread (it hitched the game). */
	private static void checkLockToggleSavesOffThread() {
		try {
			Path dir = Files.createTempDirectory("geiler-slotlock-async");
			PlayerInventoryControlsStoreSession session = new PlayerInventoryControlsStoreSession(dir);
			PlayerInventoryControlsData data = PlayerInventoryControlsData.defaults();
			for (int slot = 9; slot < 30; slot++) {
				data = data.withSlotLocked(slot, true);
				session.saveLater(data);
			}
			check(session.awaitIdle(5000), "the background writer drains its queue");
			PlayerInventoryControlsData reloaded = new PlayerInventoryControlsStoreSession(dir).load();
			check(reloaded.equals(data), "rapid lock toggles coalesce and the newest state is the one persisted");
		} catch (IOException exception) {
			throw new AssertionError("async save check could not use a temp directory", exception);
		}
	}

	public static void run() {
		checkRuntimeAdapterAbi();
		checkStoreAdapterAbi();
		checkHotbarLinkColors();
		checkSlotKeyGestureResolution();
		checkLockToggleSavesOffThread();
		try {
			java.lang.reflect.Method overlayRender = PlayerInventorySlotOverlay.class.getDeclaredMethod(
				"render", AbstractContainerScreen.class, GuiGraphicsExtractor.class);
			check(Modifier.isPublic(overlayRender.getModifiers()) && Modifier.isStatic(overlayRender.getModifiers())
				&& overlayRender.getReturnType() == void.class,
				"slot overlay keeps the public static Mixin render entrypoint signature");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Slot Locking Kotlin UI Java interop changed", exception);
		}

		PlayerInventoryControlsData defaults = PlayerInventoryControlsData.defaults();
		check(!defaults.isLocked(0) && defaults.hotbarBindings().size() == 9,
			"fresh settings have no locks and nine configurable hotbar slots");

		PlayerInventoryControlsData locked = defaults.withSlotLocked(0, true)
			.withSlotLocked(40, true);
		PlayerInventoryControlsData unlocked = locked.withSlotLocked(0, false);
		check(!unlocked.isLocked(0) && unlocked.isLocked(40),
			"tapping the gesture key can unlock one slot while preserving other locks");
		check(PlayerSlotLockRules.blocksSlotClick(true, 0, locked),
			"locked player slots block inventory and container-screen clicks");
		check(!PlayerSlotLockRules.blocksSlotClick(false, 0, locked),
			"an external container slot is not misidentified as a player slot");
		check(!PlayerSlotLockRules.blocksSlotClick(true, 1, locked),
			"unlocked player slots remain interactive");
		check(PlayerSlotLockRules.blocksWorldDrop(0, locked)
			&& !PlayerSlotLockRules.blocksWorldDrop(1, locked),
			"dropping from a locked selected hotbar slot is blocked");
		check(PlayerSlotLockRules.allowsHotbarSelect(0) && PlayerSlotLockRules.allowsHotbarSelect(8),
			"locked slots remain selectable and usable");
		check(PlayerSlotLockRules.swapTargetInventorySlot(0) == 0
			&& PlayerSlotLockRules.swapTargetInventorySlot(8) == 8
			&& PlayerSlotLockRules.swapTargetInventorySlot(40) == 40
			&& PlayerSlotLockRules.swapTargetInventorySlot(9) == -1,
			"container swap buttons identify hotbar and offhand targets only");

		check(blocks(PlayerSlotLockRules.ContainerAction.PICKUP, true, false, false, false),
			"pickup into or out of a locked slot is blocked");
		check(blocks(PlayerSlotLockRules.ContainerAction.QUICK_MOVE, false, false, true, false),
			"quick-move destination preflight blocks before a locked slot is mutated");
		check(!blocks(PlayerSlotLockRules.ContainerAction.QUICK_MOVE, false, false, false, false),
			"quick-move remains available when it cannot enter a locked slot");
		PlayerSlotLockRules.QuickMoveDestination unlockedAbsorber =
			new PlayerSlotLockRules.QuickMoveDestination(false, false, true, false, 60, 64);
		PlayerSlotLockRules.QuickMoveDestination lockedMatchingStack =
			new PlayerSlotLockRules.QuickMoveDestination(true, false, true, false, 60, 64);
		check(!PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(3, true,
			List.of(unlockedAbsorber, lockedMatchingStack)),
			"an earlier unlocked matching slot that absorbs the whole stack keeps the later locked slot untouched");
		PlayerSlotLockRules.QuickMoveDestination partialUnlockedStack =
			new PlayerSlotLockRules.QuickMoveDestination(false, false, true, false, 62, 64);
		PlayerSlotLockRules.QuickMoveDestination lockedRemainderStack =
			new PlayerSlotLockRules.QuickMoveDestination(true, false, true, false, 62, 64);
		check(PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(6, true,
			List.of(partialUnlockedStack, lockedRemainderStack)),
			"a locked matching slot that receives the remainder blocks the ordered transfer");
		PlayerSlotLockRules.QuickMoveDestination unlockedEmptySlot =
			new PlayerSlotLockRules.QuickMoveDestination(false, true, false, true, 0, 64);
		PlayerSlotLockRules.QuickMoveDestination lockedEmptySlot =
			new PlayerSlotLockRules.QuickMoveDestination(true, true, false, true, 0, 64);
		check(!PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(12, true,
			List.of(unlockedEmptySlot, lockedEmptySlot)),
			"the first eligible unlocked empty slot receives the transfer before later locked empties");
		check(PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(12, true,
			List.of(lockedEmptySlot)),
			"an external-to-player transfer blocks when its actual destination range starts at a locked slot");
		PlayerInventoryControlsData savedLocks = defaults.withSlotLocked(3, true);
		check(!savedLocks.lockedSlots().isEmpty()
			&& !PlayerSlotLockRules.shouldPreflightQuickMove(false, !savedLocks.lockedSlots().isEmpty())
			&& !PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(false, true, 12, true,
				List.of(lockedEmptySlot)),
			"persisted locks do not preflight or block quick-move while Slot Locking is disabled");
		PlayerSlotLockRules.QuickMoveDestination lockedOutsideRange =
			new PlayerSlotLockRules.QuickMoveDestination(true, true, false, true, 0, 64);
		check(lockedOutsideRange.locked() && !PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(12, true,
			List.of(unlockedEmptySlot)), "a locked slot outside the actual destination range is not considered");
		PlayerSlotLockRules.QuickMoveDestination normalizedBounds =
			new PlayerSlotLockRules.QuickMoveDestination(false, false, false, false, -1, -1);
		check(normalizedBounds.existingCount() == 0 && normalizedBounds.maxStackSize() == 0
			&& !PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(12, true, null),
			"move simulations keep constructor bounds and treat an unavailable destination list as a no-op");
		check(hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "locked", boolean.class)
			&& hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "empty", boolean.class)
			&& hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "sameItem", boolean.class)
			&& hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "mayPlace", boolean.class)
			&& hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "existingCount", int.class)
			&& hasPublicMethod(PlayerSlotLockRules.QuickMoveDestination.class, "maxStackSize", int.class),
			"quick-move destinations preserve their public constructor and Java accessor API");
		PlayerSlotLockRules.QuickMoveDestination zeroBounds =
			new PlayerSlotLockRules.QuickMoveDestination(false, false, false, false, 0, 0);
		check(normalizedBounds.equals(zeroBounds) && normalizedBounds.hashCode() == zeroBounds.hashCode()
			&& normalizedBounds.toString().equals("QuickMoveDestination[locked=false, empty=false, sameItem=false, "
				+ "mayPlace=false, existingCount=0, maxStackSize=0]"),
			"equality, hash code, and string form use the constructor's normalized components");
		check(blocks(PlayerSlotLockRules.ContainerAction.QUICK_MOVE, true, false, false, false),
			"quick-moving out of a locked source remains blocked independently of destination preflight");
		check(blocks(PlayerSlotLockRules.ContainerAction.SWAP, false, true, false, false),
			"number-key or offhand swap into a locked target is blocked");
		check(blocks(PlayerSlotLockRules.ContainerAction.CLONE, true, false, false, false),
			"creative clone from a locked slot is blocked");
		check(blocks(PlayerSlotLockRules.ContainerAction.THROW, true, false, false, false),
			"throwing a locked slot is blocked");
		check(blocks(PlayerSlotLockRules.ContainerAction.QUICK_CRAFT, true, false, false, false),
			"drag distribution to a locked slot is blocked");
		check(blocks(PlayerSlotLockRules.ContainerAction.PICKUP_ALL, false, false, false, true),
			"double-click collect is blocked when it would take a locked stack");
		check(!blocks(PlayerSlotLockRules.ContainerAction.PICKUP_ALL, false, false, false, false),
			"double-click collect remains available when no locked stack matches");

		PlayerInventoryControlsData.Binding a = new PlayerInventoryControlsData.Binding("key.keyboard.g", 0);
		PlayerInventoryControlsData.Binding b = new PlayerInventoryControlsData.Binding("key.keyboard.h", 2);
		PlayerInventoryControlsData configured = locked.withBinding(0, a).withBinding(1, b)
			.withBinding(2, a);
		check(configured.hotbarBindings().get(0).equals(PlayerInventoryControlsData.Binding.UNBOUND)
			&& configured.hotbarBindings().get(2).equals(a), "duplicate custom bindings resolve to the latest slot");
		PlayerInventoryControlsData restored = PlayerInventoryControlsCodec.decode(
			PlayerInventoryControlsCodec.encode(configured)).orElseThrow();
		check(restored.equals(configured), "settings round-trip through the standalone codec");

		PlayerInventoryControlsData linked = defaults.withLink(18, 0).withLink(19, 0);
		check(!linked.slotLinks().isEmpty()
			&& !PlayerSlotLockRules.shouldPreflightQuickMove(false, !linked.slotLinks().isEmpty())
			&& !PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(false, true, 12, true,
				List.of(lockedEmptySlot)),
			"persisted slot bindings do not preflight or block quick-move while Slot Locking is disabled");
		check(PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(true, true, 12, true,
			List.of(lockedEmptySlot)), "enabled protection still blocks a locked quick-move destination");
		check(linked.hotbarPartners(18).equals(List.of(0))
			&& linked.inventoryPartners(0).equals(List.of(18, 19)),
			"a held bind gesture can persist player-inventory-to-hotbar endpoints in insertion order");
		PlayerInventoryControlsData afterRemoval = linked.withoutLinksAt(18).withLink(20, 0);
		check(afterRemoval.inventoryPartners(0).equals(List.of(19, 20)),
			"adding a link after removal preserves endpoint priority without reusing a stale rank");
		PlayerInventoryControlsData multipleLinks = defaults.withLink(18, 0).withLink(18, 1);
		PlayerInventoryControlsData reordered = multipleLinks.prioritizeLink(
			new PlayerInventoryControlsData.SlotLink(18, 1), true);
		check(reordered.hotbarPartners(18).equals(List.of(1, 0))
			&& reordered.inventoryPartners(0).equals(List.of(18)),
			"using a link promotes it for the clicked endpoint without changing its pair");
		check(afterRemoval.withLink(8, 1).equals(afterRemoval)
			&& afterRemoval.withLink(20, 9).equals(afterRemoval),
			"links reject hotbar-to-hotbar and non-hotbar destinations");
		PlayerInventoryControlsData profiles = linked.createProfile("Dungeon");
		check(profiles.selectedProfile().equals("Dungeon") && profiles.slotLinks().isEmpty()
			&& profiles.selectProfile("Default").slotLinks().equals(linked.slotLinks()),
			"profiles keep separate link sets and can be switched");
		PlayerInventoryControlsData restoredProfiles = PlayerInventoryControlsCodec.decode(
			PlayerInventoryControlsCodec.encode(afterRemoval)).orElseThrow();
		check(restoredProfiles.equals(afterRemoval), "profile links and endpoint priorities round-trip");
		check(PlayerInventoryControlsCodec.decode("{\"version\":99}").isEmpty(),
			"unknown settings versions are rejected safely");
		check(new PlayerInventoryControlsData(Set.of(-1, 41), List.of()).lockedSlots().isEmpty(),
			"out-of-range slots are discarded on restore");
		checkFilePersistence();
	}

	private static void checkFilePersistence() {
		Path root = null;
		try {
			root = Files.createTempDirectory("ga-inventory-controls-check-");
			PlayerInventoryControlsData defaults = PlayerInventoryControlsData.defaults();
			Path sourceConfig = root.resolve("source-config");
			Path sourceFile = controlsFile(sourceConfig);
			PlayerInventoryControlsStoreSession source = new PlayerInventoryControlsStoreSession(sourceConfig);
			check(source.load().equals(defaults) && !Files.exists(sourceFile),
				"a missing controls.json loads defaults without creating a file");

			PlayerInventoryControlsData settings = defaults.withSlotLocked(3, true).withSlotLocked(40, true)
				.withBinding(0, new PlayerInventoryControlsData.Binding("key.keyboard.g", 0))
				.withBinding(1, new PlayerInventoryControlsData.Binding("key.keyboard.h", 2))
				.withLink(18, 0).withLink(18, 1).withLink(19, 0)
				.prioritizeLink(new PlayerInventoryControlsData.SlotLink(18, 1), true)
				.prioritizeLink(new PlayerInventoryControlsData.SlotLink(19, 0), false)
				.createProfile("Dungeon").withLink(20, 2);
			check(source.save(settings) && Files.isRegularFile(sourceFile),
				"the store writes a controls.json below config/geileraddons/inventory-controls");

			Path copiedConfig = root.resolve("copied-config");
			Path copiedFile = controlsFile(copiedConfig);
			Files.createDirectories(copiedFile.getParent());
			Files.copy(sourceFile, copiedFile);
			PlayerInventoryControlsStoreSession copied = new PlayerInventoryControlsStoreSession(copiedConfig);
			PlayerInventoryControlsData loaded = copied.load();
			check(loaded.equals(settings)
				&& loaded.isLocked(3) && loaded.isLocked(40)
				&& loaded.hotbarBindings().get(0).equals(new PlayerInventoryControlsData.Binding("key.keyboard.g", 0))
				&& loaded.selectedProfile().equals("Dungeon")
				&& loaded.selectProfile("Default").hotbarPartners(18).equals(List.of(1, 0))
				&& loaded.selectProfile("Default").inventoryPartners(0).equals(List.of(19, 18)),
				"a copied file restores locks, hotbar keys, named profiles, links, and endpoint priorities");

			PlayerInventoryControlsData edited = loaded.withSlotLocked(5, true)
				.withBinding(2, new PlayerInventoryControlsData.Binding("key.keyboard.j", 1));
			check(copied.save(edited), "the copied settings file accepts a valid save");
			PlayerInventoryControlsStoreSession reloadedStore = new PlayerInventoryControlsStoreSession(copiedConfig);
			PlayerInventoryControlsStore.LoadResult reloaded = reloadedStore.readForReload();
			check(reloaded.success() && reloaded.data().equals(edited),
				"saved copied settings reload with the updated lock and hotbar key");

			checkInvalidFileIsRetained(root.resolve("invalid-json"),
				"{not-json".getBytes(StandardCharsets.UTF_8), "malformed JSON");
			checkInvalidFileIsRetained(root.resolve("invalid-utf8"),
				new byte[] { (byte) 0xc3, 0x28 }, "invalid UTF-8");
			checkInvalidFileIsRetained(root.resolve("oversized"),
				new byte[PlayerInventoryControlsCodec.MAX_JSON_CHARS * 4 + 1], "oversized data");

			Path validImportConfig = root.resolve("valid-profile-import-config");
			PlayerInventoryControlsData preImportSettings = settings.withSlotLocked(7, true);
			PlayerInventoryControlsStoreSession validBeforeImport =
				new PlayerInventoryControlsStoreSession(validImportConfig);
			check(validBeforeImport.flushForProfileImport(preImportSettings)
				&& PlayerInventoryControlsCodec.decode(Files.readString(
					controlsFile(validImportConfig), StandardCharsets.UTF_8)).orElseThrow().equals(preImportSettings),
				"profile preparation flushes valid in-memory controls before installation");

			Path importConfig = root.resolve("profile-import-config");
			Path importFile = controlsFile(importConfig);
			Files.createDirectories(importFile.getParent());
			byte[] invalidBeforeImport = "{broken-profile-member".getBytes(StandardCharsets.UTF_8);
			Files.write(importFile, invalidBeforeImport);
			PlayerInventoryControlsStoreSession beforeImport = new PlayerInventoryControlsStoreSession(importConfig);
			check(beforeImport.load().equals(defaults)
				&& beforeImport.flushForProfileImport(defaults)
				&& Arrays.equals(invalidBeforeImport, Files.readAllBytes(importFile)),
				"profile preparation keeps an invalid member intact for its recovery snapshot");

			byte[] importedBytes = PlayerInventoryControlsCodec.encode(settings).getBytes(StandardCharsets.UTF_8);
			ConfigProfileInstaller.Result installed = ConfigProfileInstaller.install(
				importConfig.resolve("geileraddons"),
				Map.of("config.json", "{}".getBytes(StandardCharsets.UTF_8),
					"inventory-controls/controls.json", importedBytes),
				null, ConfigProfileCodec::safePath, files -> { }, null);
			PlayerInventoryControlsStore.LoadResult imported =
				new PlayerInventoryControlsStoreSession(importConfig).readForReload();
			check(installed.installed() && imported.success() && imported.data().equals(settings),
				"an installed portable controls.json reloads through the production store");
		} catch (IOException failure) {
			throw new AssertionError("Inventory Controls file checks could not complete", failure);
		} finally {
			if (root != null) {
				try {
					deleteTree(root);
				} catch (IOException failure) {
					throw new AssertionError("Inventory Controls file checks could not clean up", failure);
				}
			}
		}
	}

	private static void checkInvalidFileIsRetained(Path configDirectory, byte[] original, String description)
		throws IOException {
		Path file = controlsFile(configDirectory);
		Files.createDirectories(file.getParent());
		Files.write(file, original);
		PlayerInventoryControlsStoreSession store = new PlayerInventoryControlsStoreSession(configDirectory);
		check(store.load().equals(PlayerInventoryControlsData.defaults()),
			description + " falls back to defaults");
		check(!store.save(PlayerInventoryControlsData.defaults().withSlotLocked(1, true)),
			description + " blocks an ordinary save");
		check(Arrays.equals(original, Files.readAllBytes(file)),
			description + " remains byte-for-byte after a blocked save");
		check(store.flushForProfileImport(PlayerInventoryControlsData.defaults())
			&& Arrays.equals(original, Files.readAllBytes(file)),
			description + " remains byte-for-byte while profile import prepares recovery");
	}

	private static Path controlsFile(Path configDirectory) {
		return configDirectory.resolve("geileraddons").resolve("inventory-controls").resolve("controls.json");
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void checkRuntimeAdapterAbi() {
		try {
			var controls = PlayerInventoryControls.class;
			var instance = controls.getField("INSTANCE");
			check(Modifier.isPublic(instance.getModifiers()) && Modifier.isStatic(instance.getModifiers())
				&& Modifier.isFinal(instance.getModifiers()) && instance.getType() == controls,
				"Java inventory-control singleton remains a public static final field");

			checkAdapterMethod("settings", PlayerInventoryControlsData.class, false);
			checkAdapterMethod("lockHoveredSlotKey", KeyMapping.class, true);
			checkAdapterMethod("bindInventorySlotsKey", KeyMapping.class, true);
			checkAdapterMethod("reloadFromDisk", boolean.class, false);
			checkAdapterMethod("flush", boolean.class, false);
			checkAdapterMethod("flushForProfileImport", boolean.class, false);
			checkAdapterMethod("snapshotRuntimeState", PlayerInventoryControls.RuntimeSnapshot.class, false);
			checkAdapterMethod("restoreRuntimeState", void.class, false,
				PlayerInventoryControls.RuntimeSnapshot.class);
			checkAdapterMethod("beginProfileTransaction", void.class, false);
			checkAdapterMethod("endProfileTransaction", void.class, false);
			checkAdapterMethod("isSlotLocked", boolean.class, false, int.class);
			checkAdapterMethod("setSlotLocked", void.class, false, int.class, boolean.class);
			checkAdapterMethod("toggleSlotLock", void.class, false, int.class);
			checkAdapterMethod("applyData", void.class, false, PlayerInventoryControlsData.class);
			checkAdapterMethod("blocksSlotClick", boolean.class, false, Minecraft.class, Slot.class);
			checkAdapterMethod("blocksContainerInput", boolean.class, false, Minecraft.class,
				AbstractContainerScreen.class, Slot.class, int.class, int.class, ContainerInput.class);
			checkAdapterMethod("blocksMoveItemStackTo", boolean.class, false, Minecraft.class,
				AbstractContainerMenu.class, ItemStack.class, int.class, int.class, boolean.class);
			checkAdapterMethod("blocksQuickCraftTarget", boolean.class, false, Minecraft.class, Slot.class);
			checkAdapterMethod("blocksWorldDrop", boolean.class, false, Minecraft.class);
			checkAdapterMethod("blocksOffhandSwap", boolean.class, false, Minecraft.class);
			checkAdapterMethod("handleKeyEvent", boolean.class, false, Minecraft.class, int.class, KeyEvent.class);
			checkAdapterMethod("handleScreenKeyPressed", boolean.class, false,
				AbstractContainerScreen.class, Slot.class, KeyEvent.class);
			checkAdapterMethod("handleScreenKeyReleased", boolean.class, false,
				AbstractContainerScreen.class, Slot.class, KeyEvent.class);
			checkAdapterMethod("resetScreenGestures", void.class, false);
			checkAdapterMethod("runLinkedQuickMove", boolean.class, false,
				Minecraft.class, AbstractContainerMenu.class, Slot.class, int.class);
			checkAdapterMethod("slotLinkColor", int.class, false, int.class);

			var components = PlayerInventoryControls.RuntimeSnapshot.class.getRecordComponents();
			check(PlayerInventoryControls.RuntimeSnapshot.class.isRecord() && components.length == 1
				&& components[0].getName().equals("settings")
				&& components[0].getType() == PlayerInventoryControlsData.class,
				"profile transaction snapshots retain their Java record component");
			check(new PlayerInventoryControls.RuntimeSnapshot(null).settings() == null,
				"profile transaction snapshots retain nullable settings");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Slot Locking Java runtime adapter ABI changed", exception);
		}
	}

	private static void checkStoreAdapterAbi() {
		try {
			checkStoreMethod("load", PlayerInventoryControlsData.class);
			checkStoreMethod("save", boolean.class, PlayerInventoryControlsData.class);
			checkStoreMethod("readForReload", PlayerInventoryControlsStore.LoadResult.class);
			checkStoreMethod("flushForProfileImport", boolean.class, PlayerInventoryControlsData.class);

			var resultType = PlayerInventoryControlsStore.LoadResult.class;
			var components = resultType.getRecordComponents();
			check(resultType.isRecord() && components.length == 2
				&& components[0].getName().equals("success") && components[0].getType() == boolean.class
				&& components[1].getName().equals("data")
				&& components[1].getType() == PlayerInventoryControlsData.class,
				"store load results retain their Java record components");

			var emptyResult = new PlayerInventoryControlsStore.LoadResult(false, null);
			var equalEmptyResult = new PlayerInventoryControlsStore.LoadResult(false, null);
			check(emptyResult.data() == null && emptyResult.equals(equalEmptyResult)
				&& emptyResult.hashCode() == equalEmptyResult.hashCode(),
				"store load results retain nullable data and record equality semantics");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Slot Locking Java store adapter ABI changed", exception);
		}
	}

	private static void checkHotbarLinkColors() {
		PlayerInventoryControlsModule module = PlayerInventoryControlsModule.INSTANCE;
		int[] expected = {
			0xffff5555, 0xffffaa00, 0xffffff55,
			0xff55ff55, 0xff55ffff, 0xff55aaff,
			0xff5555ff, 0xffaa55ff, 0xffff55ff
		};
		for (int slot = 0; slot < expected.length; slot++) {
			check(module.linkColor(slot) == expected[slot],
				"hotbar slot " + (slot + 1) + " keeps its fixed binding color");
		}
		check(Arrays.stream(expected).distinct().count() == 9 && module.colorSettings().isEmpty()
			&& module.linkColor(-1) == 0xffffffff && module.linkColor(9) == 0xffffffff
			&& module.lockedSlotColor() == 0xffff5555,
			"binding colors stay unique and fixed, with a fixed lock color and no custom color settings");
		check(PlayerInventoryControls.lockHoveredSlotKey() == PlayerInventoryControls.bindInventorySlotsKey(),
			"legacy Java key accessors resolve to the same lock-and-bind mapping");
		check(module.actions().isEmpty()
			&& module.groups().getFirst().settings().stream().anyMatch(setting ->
				setting instanceof geiler.addons.client.module.TextSetting text
					&& text.name().equals("Slot Gesture Key") && text.keybindCapture())
			&& module.groups().stream().noneMatch(group -> group.name() != null
				&& group.name().toLowerCase(java.util.Locale.ROOT).contains("profile")),
			"Slot Locking exposes one gesture key and no Slot Profiles editor");
	}

	private static void checkSlotKeyGestureResolution() {
		var tap = PlayerInventorySlotGesture.resolve(18, 18, 50_000_000L);
		check(tap.action() == PlayerInventorySlotGesture.Action.TOGGLE_LOCK && tap.inventorySlot() == 18,
			"a short same-slot gesture toggles the lock");
		var untaggedTap = PlayerInventorySlotGesture.resolve(18, 18, 50_000_000L, false);
		check(untaggedTap.action() == PlayerInventorySlotGesture.Action.TOGGLE_LOCK,
			"tapping the key on an unbound slot still toggles its lock");
		var linkedTap = PlayerInventorySlotGesture.resolve(18, 18, 50_000_000L, true);
		check(linkedTap.action() == PlayerInventorySlotGesture.Action.CLEAR_LINKS && linkedTap.inventorySlot() == 18,
			"tapping the key on a bound slot removes its binding");
		var moveTap = PlayerInventorySlotGesture.resolve(18, 0, 50_000_000L);
		check(moveTap.action() == PlayerInventorySlotGesture.Action.NONE,
			"a quick move across slots is not mistaken for a binding");
		var holdBind = PlayerInventorySlotGesture.resolve(18, 0,
			PlayerInventorySlotGesture.TAP_LOCK_MAX_NANOS + 1);
		check(holdBind.action() == PlayerInventorySlotGesture.Action.BIND
			&& holdBind.inventorySlot() == 18 && holdBind.hotbarSlot() == 0,
			"holding from an inventory slot to a hotbar slot creates a binding");
		var reverseHoldBind = PlayerInventorySlotGesture.resolve(0, 18,
			PlayerInventorySlotGesture.TAP_LOCK_MAX_NANOS + 1);
		check(reverseHoldBind.action() == PlayerInventorySlotGesture.Action.BIND
			&& reverseHoldBind.inventorySlot() == 18 && reverseHoldBind.hotbarSlot() == 0,
			"holding from a hotbar slot to an inventory slot resolves the same pair");
		var clear = PlayerInventorySlotGesture.resolve(18, 18,
			PlayerInventorySlotGesture.TAP_LOCK_MAX_NANOS + 1);
		check(clear.action() == PlayerInventorySlotGesture.Action.CLEAR_LINKS && clear.inventorySlot() == 18,
			"holding on one linked slot clears that slot's links");
		check(PlayerInventorySlotGesture.resolve(41, 41,
			PlayerInventorySlotGesture.TAP_LOCK_MAX_NANOS + 1).action() == PlayerInventorySlotGesture.Action.NONE,
			"out-of-range slots cannot be locked or bound");
	}

	private static void checkStoreMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
		throws NoSuchMethodException {
		var method = PlayerInventoryControlsStore.class.getDeclaredMethod(name, parameterTypes);
		check(Modifier.isStatic(method.getModifiers()) && method.getReturnType() == returnType,
			"Java inventory-controls store retains its static " + name + " signature");
	}

	private static boolean hasPublicMethod(Class<?> type, String name, Class<?> returnType) {
		return Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().equals(name)
			&& Modifier.isPublic(method.getModifiers()) && method.getReturnType() == returnType);
	}

	private static void checkAdapterMethod(String name, Class<?> returnType, boolean isStatic,
		Class<?>... parameterTypes) throws NoSuchMethodException {
		var method = PlayerInventoryControls.class.getDeclaredMethod(name, parameterTypes);
		check(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()) == isStatic
			&& method.getReturnType() == returnType,
			"Java inventory-control adapter retains the public " + name + " signature");
	}

	private static boolean blocks(PlayerSlotLockRules.ContainerAction action, boolean clickedLocked,
		boolean swapTargetLocked, boolean quickMoveCouldEnterLocked, boolean pickupAllWouldTakeLocked) {
		return PlayerSlotLockRules.blocksContainerAction(action, clickedLocked, swapTargetLocked,
			quickMoveCouldEnterLocked, pickupAllWouldTakeLocked);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
