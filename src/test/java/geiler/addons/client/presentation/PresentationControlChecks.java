package geiler.addons.client.presentation;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ChoiceSetting;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.TextSetting;
import geiler.addons.client.module.impl.HideAllParticlesModule;
import geiler.addons.client.module.impl.ItemAnimationsModule;
import geiler.addons.client.module.impl.PlayerInventoryControlsModule;
import geiler.addons.client.module.impl.PlayerNamesModule;
import geiler.addons.client.module.impl.PlayerSizeModule;
import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import geiler.addons.client.inventory.PlayerInventoryControlsData;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/** Isolated default-value checks for the client presentation controls. */
public final class PresentationControlChecks {
	private PresentationControlChecks() { }

	public static void main(String[] args) {
		PlayerPresentationPolicyChecks.run();
		ItemAnimationsModule animations = ItemAnimationsModule.INSTANCE;
		check(!animations.defaultEnabled(), "Animations defaults off on a fresh configuration");
		Map<String, Float> numbers = numberValues(animations.numberSettings());
		check(numbers.get("xOffset") == 0.0f && numbers.get("yOffset") == 0.14f
			&& numbers.get("zOffset") == 0.0f, "Item Animations position defaults match the approved values");
		check(numbers.get("scale") == -1.53f && numbers.get("swingSpeed") == 0.08f,
			"Item Animations scale and swing speed defaults match the approved values");
		check(Float.valueOf(-2.068693338301614f).equals(numbers.get("yawOffset"))
			&& Float.valueOf(0.0f).equals(numbers.get("pitchOffset"))
			&& Float.valueOf(-2.068693338301614f).equals(numbers.get("rollOffset")),
			"Item Animations rotation defaults match their NumberSetting values");
		Map<String, Boolean> booleans = booleanValues(animations.booleanSettings());
		check(Boolean.TRUE.equals(booleans.get("changeHand")) && Boolean.TRUE.equals(booleans.get("changeMap"))
			&& Boolean.TRUE.equals(booleans.get("ignoreEffects"))
			&& Boolean.TRUE.equals(booleans.get("noReequipReset"))
			&& Boolean.TRUE.equals(booleans.get("inplaceSwing"))
			&& Boolean.TRUE.equals(booleans.get("noHandSway"))
			&& Boolean.FALSE.equals(booleans.get("noSwing"))
			&& Boolean.FALSE.equals(booleans.get("noSwingTerm")),
			"Item Animations toggle defaults match the approved values");

		check(!PlayerNamesModule.INSTANCE.defaultEnabled() && !PlayerSizeModule.INSTANCE.defaultEnabled(),
			"newly separated player features default off on a fresh configuration");
		PlayerSizeModule playerSize = PlayerSizeModule.INSTANCE;
		List<NumberSetting> playerSizeAxes = playerSize.numberSettings();
		check("Player Size".equals(playerSize.name()) && "Player Size".equals(playerSize.configName())
			&& playerSize.category() == Category.VISUAL
			&& playerSizeAxes.stream().map(NumberSetting::name).toList()
				.equals(List.of("Own Size X", "Own Size Y", "Own Size Z"))
			&& playerSizeAxes.stream().allMatch(setting -> setting.min() == 0.1f && setting.max() == 4.0f
				&& setting.value() == 1.0f && !setting.isInteger()),
			"Player Size retains its identity and persisted three-axis setting IDs, bounds, and defaults");
		check(booleanValues(playerSize.booleanSettings()).equals(Map.of(
			"Resize Own Player", false,
			"Use Hosted Sizes", true))
			&& playerSize.groups().stream().map(group -> group.name()).toList()
				.equals(List.of("Local Size", "Hosted Profiles"))
			&& playerSize.actions().size() == 1
			&& "Manage Local Profiles".equals(playerSize.actions().get(0).name()),
			"Player Size retains local/hosted controls, groups, and the profile editor action");
		BooleanSetting resizeOwnPlayer = playerSize.resizeOwnPlayerSetting();
		boolean originalResizeOwnPlayer = resizeOwnPlayer.rawValue();
		try {
			resizeOwnPlayer.setValue(false);
			check(!playerSize.isSettingVisible(playerSize.ownSizeXSetting())
				&& !playerSize.isSettingVisible(playerSize.ownSizeYSetting())
				&& !playerSize.isSettingVisible(playerSize.ownSizeZSetting()),
				"own-size axes remain hidden when own-player resizing is disabled");
			resizeOwnPlayer.setValue(true);
			check(playerSize.isSettingVisible(playerSize.ownSizeXSetting())
				&& playerSize.isSettingVisible(playerSize.ownSizeYSetting())
				&& playerSize.isSettingVisible(playerSize.ownSizeZSetting()),
				"own-size axes appear when own-player resizing is enabled");
		} finally {
			resizeOwnPlayer.setValue(originalResizeOwnPlayer);
		}
		check(playerSize.sizeScale("any-player").isUnit() && playerSize.sizeMultiplier("any-player") == 1.0f,
			"disabled Player Size keeps uniform-scale callers at vanilla scale");
		PlayerSizeModule.SizeScale sampleSize = new PlayerSizeModule.SizeScale(0.5f, 1.25f, 2.0f);
		check(sampleSize.getClass().isRecord() && sampleSize.x() == 0.5f && sampleSize.y() == 1.25f
			&& sampleSize.z() == 2.0f,
			"Player Size keeps the Java record and three-axis accessors used by the rendering hook");
		playerSizeScalingUsesEnabledSources(playerSize);

		PlayerInventoryControlsModule inventoryControls = PlayerInventoryControlsModule.INSTANCE;
		check("Slot Locking".equals(inventoryControls.name())
			&& "Player Inventory Controls".equals(inventoryControls.configName())
			&& inventoryControls.category() == Category.MISCELLANEOUS
			&& !inventoryControls.defaultEnabled() && inventoryControls.isWorkInProgress(),
			"Slot Locking retains its public/config identity, category, default-off state, and WIP marker");
		check(booleanValues(inventoryControls.booleanSettings()).equals(Map.of(
			"Protect Bound Slots", true,
			"Bound Outline", true))
			&& choiceValues(inventoryControls.choiceSettings()).equals(Map.of(
				"Pointing Line Mode", "None",
				"Locked Slot Style", "Lock Icon"))
			&& numberValues(inventoryControls.numberSettings()).equals(Map.of("Line Width", 2.0f))
			&& inventoryControls.groups().stream().map(group -> group.name()).toList()
				.equals(List.of("Keys", "Slot Protection", "Slot Bindings"))
			&& inventoryControls.actions().isEmpty()
			&& inventoryControls.colorSettings().isEmpty()
			&& inventoryControls.groups().stream().noneMatch(group -> group.name() != null
				&& group.name().toLowerCase(java.util.Locale.ROOT).contains("profile")),
			"Slot Locking keeps one gesture key, its settings and groups, fixed colors, and no profile editor");
		check(inventoryControls.protectBoundSlots() && inventoryControls.drawBoundOutline()
			&& !inventoryControls.drawPointingLines() && inventoryControls.lineWidth() == 2
			&& inventoryControls.lockedSlotUsesIcon()
			&& inventoryControls.linkColor(0) == 0xFFFF5555
			&& inventoryControls.linkColor(1) == 0xFFFFAA00
			&& inventoryControls.linkColor(8) == 0xFFFF55FF
			&& inventoryControls.linkColor(-1) == 0xFFFFFFFF,
			"Slot Locking keeps its initial protection and fixed, distinct hotbar colors");
		check(geiler.addons.client.inventory.PlayerInventoryControls.lockHoveredSlotKey()
			== geiler.addons.client.inventory.PlayerInventoryControls.bindInventorySlotsKey(),
			"legacy lock and bind key accessors resolve to the same tap-and-hold mapping");
		PlayerInventoryControlsData defaults = PlayerInventoryControlsData.defaults();
		PlayerInventoryControlsData locked = defaults.withSlotLocked(9, true);
		PlayerInventoryControlsData linked = defaults.withLink(9, 0);
		check(!defaults.isLocked(9) && locked.isLocked(9) && !locked.isLocked(8)
			&& linked.hasLinkAt(9) && linked.hasLinkAt(0)
			&& linked.inventoryPartners(0).equals(List.of(9))
			&& linked.hotbarPartners(9).equals(List.of(0)),
			"slot lock and inventory-to-hotbar link state applies to the selected player slots");

		ThirdPersonCameraModule camera = ThirdPersonCameraModule.INSTANCE;
		check("Third Person Camera".equals(camera.name()) && "Third Person Camera".equals(camera.configName())
			&& camera.category() == Category.VISUAL && !camera.defaultEnabled(),
			"Third Person Camera keeps its config identity, category, and default-off state");
		Map<String, Boolean> cameraToggles = booleanValues(camera.booleanSettings());
		check(cameraToggles.equals(Map.of(
			"Front Facing", false,
			"No Camera Clip", false,
			"Show Your Name", false,
			"Free Look Enabled", false)),
			"camera toggle config IDs and defaults remain stable");
		NumberSetting cameraDistance = camera.cameraDistanceSetting();
		check(camera.numberSettings().size() == 1 && "Camera Distance".equals(cameraDistance.name())
			&& cameraDistance.min() == 0.1f && cameraDistance.max() == 16.0f && cameraDistance.value() == 4.0f,
			"camera distance config ID, bounds, and default remain stable");
		check(camera.textSettings().size() == 1, "camera keeps one persisted free-look keybind setting");
		TextSetting freeLookKeybind = camera.textSettings().get(0);
		check("Free Look Keybind".equals(freeLookKeybind.name())
			&& "key.keyboard.unknown".equals(freeLookKeybind.value()) && freeLookKeybind.maxLength() == 48
			&& freeLookKeybind.keybindCapture(),
			"free-look keybind config ID and default remain stable");
		check("key.geileraddons.free_look".equals(ThirdPersonCameraModule.freeLookKey().getName())
			&& "key.keyboard.unknown".equals(ThirdPersonCameraModule.freeLookKey().getDefaultKey().getName()),
			"registered free-look control is unbound by default and keeps its translation key");
		check(camera.isSettingVisible(camera.frontFacingSetting())
			&& "Allow Front Camera".equals(camera.frontFacingSetting().displayName()),
			"front-facing setting is visible with a clear label and keeps its persisted ID");
		boolean originalCameraEnabled = camera.isEnabled();
		boolean originalFrontFacing = camera.frontFacingSetting().rawValue();
		boolean originalNoCameraClip = camera.noCameraClipSetting().rawValue();
		float originalCameraDistance = cameraDistance.value();
		try {
			camera.setEnabled(false);
			check(camera.cameraType(CameraType.THIRD_PERSON_FRONT) == CameraType.THIRD_PERSON_FRONT
				&& camera.constrainCameraTypeRequest(CameraType.THIRD_PERSON_FRONT) == CameraType.THIRD_PERSON_FRONT
				&& camera.distance(8.0f) == 8.0f && !camera.bypassCollisionClip(),
				"disabled camera controls leave vanilla perspective and clipping unchanged");

			camera.frontFacingSetting().setValue(false);
			camera.noCameraClipSetting().setValue(true);
			cameraDistance.setValue(12.0f);
			camera.setEnabled(true);
			check(camera.cameraType(CameraType.THIRD_PERSON_FRONT) == CameraType.FIRST_PERSON
				&& camera.cameraType(CameraType.THIRD_PERSON_BACK) == CameraType.THIRD_PERSON_BACK
				&& camera.constrainCameraTypeRequest(CameraType.THIRD_PERSON_FRONT) == CameraType.FIRST_PERSON
				&& camera.constrainCameraTypeRequest(CameraType.THIRD_PERSON_BACK) == CameraType.THIRD_PERSON_BACK,
				"enabled camera preserves rear view and routes front-facing view requests to first person unless allowed");
			camera.frontFacingSetting().setValue(true);
			check(camera.cameraType(CameraType.THIRD_PERSON_FRONT) == CameraType.THIRD_PERSON_FRONT
				&& camera.constrainCameraTypeRequest(CameraType.THIRD_PERSON_FRONT) == CameraType.THIRD_PERSON_FRONT,
				"the front-camera toggle lets the vanilla front view through");
			camera.frontFacingSetting().setValue(false);
			check(camera.distance(8.0f) == 12.0f && camera.bypassCollisionClip(),
				"enabled camera applies configured distance and disables collision clipping when requested");
		} finally {
			camera.frontFacingSetting().setValue(originalFrontFacing);
			camera.noCameraClipSetting().setValue(originalNoCameraClip);
			cameraDistance.setValue(originalCameraDistance);
			camera.setEnabled(originalCameraEnabled);
		}
		HideAllParticlesModule particles = HideAllParticlesModule.INSTANCE;
		check(!particles.defaultEnabled() && particles.showsToggleControl()
			&& "Hide Particles".equals(particles.name())
			&& "Hide All Particles".equals(particles.configName())
			&& "Hides particle effects on this client.".equals(particles.description())
			&& particles.category() == Category.VISUAL
			&& !particles.hasSettings() && particles.booleanSettings().isEmpty(),
			"Hide Particles keeps its off-by-default single-toggle identity");
	}

	private static void playerSizeScalingUsesEnabledSources(PlayerSizeModule module) {
		try {
			Unsafe unsafe = unsafe();
			Field minecraftInstance = Minecraft.class.getDeclaredField("instance");
			minecraftInstance.setAccessible(true);
			Object originalClient = minecraftInstance.get(null);
			Field clientUser = Minecraft.class.getDeclaredField("user");
			Minecraft fakeClient = (Minecraft) unsafe.allocateInstance(Minecraft.class);
			unsafe.putObject(fakeClient, unsafe.objectFieldOffset(clientUser),
				new User("Self", UUID.fromString("00000000-0000-0000-0000-000000000001"), "",
					Optional.empty(), Optional.empty()));

			PlayerSizeOverrideStore overrides = PlayerSizeOverrideStore.INSTANCE;
			Field entries = PlayerSizeOverrideStore.class.getDeclaredField("entries");
			entries.setAccessible(true);
			Object originalEntries = entries.get(overrides);
			Field loaded = PlayerSizeOverrideStore.class.getDeclaredField("loaded");
			loaded.setAccessible(true);
			boolean originalLoaded = loaded.getBoolean(overrides);

			PlayerPresentationProfileStore hosted = PlayerPresentationProfileStore.INSTANCE;
			Field currentProfiles = PlayerPresentationProfileStore.class.getDeclaredField("currentProfiles");
			currentProfiles.setAccessible(true);
			Object originalProfiles = currentProfiles.get(hosted);

			Field enabled = Module.class.getDeclaredField("enabled");
			enabled.setAccessible(true);
			boolean originalEnabled = enabled.getBoolean(module);
			boolean originalResizeOwn = module.resizeOwnPlayerSetting().rawValue();
			boolean originalUseHosted = module.useRemoteSizesSetting().rawValue();
			float originalOwnX = module.ownSizeXSetting().value();
			float originalOwnY = module.ownSizeYSetting().value();
			float originalOwnZ = module.ownSizeZSetting().value();

			try {
				minecraftInstance.set(null, fakeClient);
				entries.set(overrides, Map.of(
					"self", new PlayerSizeOverrideStore.Entry("Self", 0.25f, 0.3f, 0.35f, true),
					"local", new PlayerSizeOverrideStore.Entry("Local", 0.6f, 0.7f, 0.8f, true),
					"localoff", new PlayerSizeOverrideStore.Entry("LocalOff", 0.4f, 0.5f, 0.6f, false)));
				loaded.setBoolean(overrides, true);
				try {
					currentProfiles.set(hosted, PlayerPresentationProfiles.parse("""
						{"schemaVersion":1,"players":[
						  {"name":"Self","sizeX":0.45,"sizeY":0.55,"sizeZ":0.65},
						  {"name":"Local","sizeX":1.1,"sizeY":1.2,"sizeZ":1.3},
						  {"name":"LocalOff","sizeX":1.4,"sizeY":1.5,"sizeZ":1.6},
						  {"name":"Hosted","sizeX":2.0,"sizeY":0.75,"sizeZ":1.25}
						]}
						"""));
				} catch (java.io.IOException error) {
					throw new AssertionError("Could not create hosted Player Size fixture", error);
				}
				// Skip lifecycle hooks so the isolated lookup does not start diagnostics or profile refresh work.
				enabled.setBoolean(module, true);
				module.resizeOwnPlayerSetting().setValue(true);
				module.ownSizeXSetting().setValue(0.75f);
				module.ownSizeYSetting().setValue(1.5f);
				module.ownSizeZSetting().setValue(2.5f);
				module.useRemoteSizesSetting().setValue(true);

				check(matches(module.sizeScale("sELf"), 0.75f, 1.5f, 2.5f),
					"enabled own-player axes take precedence over local and hosted entries case-insensitively");
				check(matches(module.sizeScale("LOCAL"), 0.6f, 0.7f, 0.8f),
					"an enabled local per-player override takes precedence over hosted axes");
				check(matches(module.sizeScale("LocalOff"), 1.4f, 1.5f, 1.6f),
					"a disabled local override falls through to an enabled hosted profile");
				check(matches(module.sizeScale("Hosted"), 2.0f, 0.75f, 1.25f),
					"enabled hosted profiles return independent three-axis scales");

				module.useRemoteSizesSetting().setValue(false);
				check(matches(module.sizeScale("LOCAL"), 0.6f, 0.7f, 0.8f)
					&& module.sizeScale("Hosted").isUnit(),
					"local overrides remain active while the hosted-size toggle gates hosted scales");
			} finally {
				enabled.setBoolean(module, originalEnabled);
				module.resizeOwnPlayerSetting().setValue(originalResizeOwn);
				module.ownSizeXSetting().setValue(originalOwnX);
				module.ownSizeYSetting().setValue(originalOwnY);
				module.ownSizeZSetting().setValue(originalOwnZ);
				module.useRemoteSizesSetting().setValue(originalUseHosted);
				entries.set(overrides, originalEntries);
				loaded.setBoolean(overrides, originalLoaded);
				currentProfiles.set(hosted, originalProfiles);
				minecraftInstance.set(null, originalClient);
			}
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Could not isolate Player Size scale-source checks", error);
		}
	}

	private static Unsafe unsafe() throws ReflectiveOperationException {
		Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
		singleton.setAccessible(true);
		return (Unsafe) singleton.get(null);
	}

	private static boolean matches(PlayerSizeModule.SizeScale scale, float x, float y, float z) {
		return scale.x() == x && scale.y() == y && scale.z() == z;
	}

	private static Map<String, Float> numberValues(Iterable<NumberSetting> settings) {
		Map<String, Float> values = new HashMap<>();
		for (NumberSetting setting : settings) values.put(setting.name(), setting.value());
		return values;
	}

	private static Map<String, Boolean> booleanValues(Iterable<BooleanSetting> settings) {
		Map<String, Boolean> values = new HashMap<>();
		for (BooleanSetting setting : settings) values.put(setting.name(), setting.value());
		return values;
	}

	private static Map<String, String> choiceValues(Iterable<ChoiceSetting> settings) {
		Map<String, String> values = new HashMap<>();
		for (ChoiceSetting setting : settings) values.put(setting.name(), setting.value());
		return values;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
