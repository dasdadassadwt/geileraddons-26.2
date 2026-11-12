package geiler.addons.client.module.impl;

import geiler.addons.client.collections.FolderTree;
import geiler.addons.client.location.Island;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ModuleAction;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.module.SettingGroup;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Focused offline checks for Mob Highlight defaults and its Java-facing boundary. */
public final class MobHighlightChecks {
	private MobHighlightChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkModuleFacade();
		checkDynamicHighlights();
		checkCompleteAabbBounds();

		MobHighlight highlight = new MobHighlight(73);
		check(highlight.id() == 73 && highlight.folderId() == null,
			"new highlights preserve their supplied ID and start outside folders");
		check(highlight.enabled().value() && highlight.matchName().value() && highlight.depthCheck().value(),
			"new highlights retain enabled, name matching, and depth-check defaults");
		check(highlight.matchText().value().isEmpty() && highlight.matchText().maxLength() == 48,
			"the search text starts empty and keeps its 48-character limit");
		check(highlight.displayName().value().equals("New Highlight") && highlight.displayName().maxLength() == 48,
			"the display label keeps its default and 48-character limit");
		check(highlight.scanInterval().intValue() == 20 && highlight.scanInterval().min() == 1
			&& highlight.scanInterval().max() == 200,
			"the default scan interval remains 20 ticks within the 1-200 range");
		check(highlight.outlineColor().argb() == 0xFFFF0000 && highlight.fillColor().argb() == 0x3CFF0000,
			"the outline and translucent fill preserve their red defaults");
		check(highlight.islands().values().stream().noneMatch(setting -> setting.value()),
			"every selectable island starts disabled");
		check(highlight.islands().keySet().stream().allMatch(MobHighlight::isKnownSkyBlockIsland),
			"only known SkyBlock islands receive selectable settings");
		check(!MobHighlight.isKnownSkyBlockIsland(Island.NONE)
			&& !MobHighlight.isKnownSkyBlockIsland(Island.OTHER),
			"unknown and non-SkyBlock locations fail the island boundary closed");
		check("zombie".equals(MobHighlightModule.normalizeNeedle("  \u00a7aZoMbIe  ")),
			"configured search text strips legacy colors, trims, and folds case");
		check(MobHighlightModule.matchesNameText("\u00a7cZombie", "zombie")
			&& !MobHighlightModule.matchesNameText("Skeleton", "zombie"),
			"name matching uses plain case-insensitive substring containment");
		check(MobHighlightModule.matchesTypeText("entity.minecraft.zombie", "Undead", "zom")
			&& MobHighlightModule.matchesTypeText("entity.minecraft.other", "Zombie", "zom")
			&& !MobHighlightModule.matchesTypeText("entity.minecraft.skeleton", "Skeleton", "zom"),
			"type matching accepts either the English ID suffix or the visible localized description");
		check(MobHighlight.DEFAULT_DISPLAY_NAME.equals("New Highlight")
			&& MobHighlightModule.MAX_HIGHLIGHTS == 32 && MobHighlightModule.INSTANCE != null,
			"Java callers retain the default-name, list-limit, and singleton entry points");

		highlight.setFolderId("  ");
		check(highlight.folderId() == null, "blank folder IDs normalize to null");
		highlight.setFolderId(" folder id ");
		check(" folder id ".equals(highlight.folderId()), "nonblank folder IDs are preserved without rewriting");

		SettingGroupCheck.check(highlight);
		for (int tick = 1; tick < 20; tick++) {
			check(!highlight.dueForScan(), "the scan does not run before its configured interval");
		}
		check(highlight.dueForScan(), "the default interval triggers on its twentieth tick");
		check(!highlight.dueForScan(), "the interval resets after a scan");

		try {
			highlight.islands().clear();
			throw new AssertionError("the island map must not permit structural mutation");
		} catch (UnsupportedOperationException expected) {
			// Settings remain mutable while enum membership stays fixed.
		}
	}

	private static void checkDynamicHighlights() {
		MobHighlightModule module = MobHighlightModule.INSTANCE;
		List<MobHighlight> originalHighlights = module.highlights();
		List<FolderTree.Folder> originalFolders = module.folders().folders();
		FolderTree.Folder folder = module.folders().create("Mob Highlight Check", null);
		try {
			check(folder != null, "the highlight folder fixture is created");
			MobHighlight filed = module.blank(8);
			MobHighlight unfiled = module.blank(21);
			filed.setFolderId(folder.id());
			module.restore(List.of(filed, unfiled));

			check(module.highlights().size() == 2
				&& module.highlights().get(0).id() == 8 && module.highlights().get(1).id() == 21,
				"restoring highlights preserves their persisted IDs and order");

			List<MobHighlight> snapshot = module.highlights();
			try {
				snapshot.clear();
				throw new AssertionError("the module's highlight snapshot must be immutable");
			} catch (UnsupportedOperationException expected) {
				// Config and transfer callers receive a view, never the mutable backing list.
			}

			List<SettingGroup> groups = module.groups();
			check(groups.size() == 3 && "Mob Highlight Check".equals(groups.get(1).name())
				&& ("mob-highlight-folder:" + folder.id()).equals(groups.get(1).stateKey())
				&& groups.get(1).children().size() == 1
				&& "#8 New Highlight".equals(groups.get(1).children().get(0).name())
				&& "#21 New Highlight".equals(groups.get(2).name()),
				"folder membership groups the right highlight and keeps stable section identities");

			List<MobHighlight> oversized = new ArrayList<>();
			for (int index = 0; index < MobHighlightModule.MAX_HIGHLIGHTS + 3; index++) {
				oversized.add(module.blank(100 + index));
			}
			module.restore(oversized);
			check(module.highlights().size() == MobHighlightModule.MAX_HIGHLIGHTS,
				"restoring hand-edited data remains bounded by the highlight limit");
		} finally {
			module.restore(originalHighlights);
			module.folders().restore(originalFolders);
		}
	}

	private static void checkModuleFacade() {
		MobHighlightModule module = MobHighlightModule.INSTANCE;
		check(module.name().equals("Mob Highlight")
			&& module.description().equals("Boxes mobs whose name or type matches your text.")
			&& module.category() == Category.VISUAL,
			"the registered module keeps its visible identity, description, and visual category");
		check(!module.defaultEnabled() && !module.isEnabled(),
			"Mob Highlight remains disabled on a fresh install and before user activation");

		check(module.actions().size() == 2
			&& "Create Mob Highlight".equals(module.actions().get(0).label())
			&& "Manage Folders".equals(module.actions().get(1).label())
			&& module.actions().get(0).description() == null
			&& "Organize highlights into nested folders.".equals(module.actions().get(1).description()),
			"the module exposes its create and folder-management actions with their established copy");

		check(module.numberSettings().size() == 1,
			"the module exposes exactly one standalone number setting");
		NumberSetting labelSize = module.numberSettings().get(0);
		check("Label Size".equals(labelSize.name()) && "Label Size".equals(labelSize.displayName())
			&& labelSize.min() == 0.5f && labelSize.max() == 4f && labelSize.value() == 1f,
			"the Label Size setting retains its label and 0.5-4 range with default 1");

		check(module.groups().size() == 1, "an empty Mob Highlight module has just its actions/settings group");
		SettingGroup group = module.groups().get(0);
		check(group.name() == null && group.children().isEmpty() && group.settings().size() == 3,
			"the default module group stays unnamed and contains only the two actions and label size");
		Setting create = group.settings().get(0);
		Setting folders = group.settings().get(1);
		Setting size = group.settings().get(2);
		check(create instanceof ModuleAction && folders instanceof ModuleAction && size instanceof NumberSetting
			&& "Create Mob Highlight".equals(create.name())
			&& "Manage Folders".equals(folders.name())
			&& "Label Size".equals(size.name()),
			"the actions and Label Size stay in their established module group and order");
	}

	private static void checkCompleteAabbBounds() {
		AABB entityBounds = new AABB(-4.25, 63.75, 12.5, -2.75, 66.125, 15.0);
		Vec3 camera = new Vec3(10.125, 64.5, 20.75);
		MobHighlightGeometry.RelativeBounds bounds = MobHighlightGeometry.relativeBounds(entityBounds, camera);
		check(close(bounds.minX(), -14.375) && close(bounds.minY(), -0.75) && close(bounds.minZ(), -8.25)
			&& close(bounds.maxX(), -12.875) && close(bounds.maxY(), 1.625) && close(bounds.maxZ(), -5.75),
			"Vulkan custom geometry keeps the entity AABB's full camera-relative minimum and maximum on all axes");
		check(close(bounds.sizeX(), 1.5) && close(bounds.sizeY(), 2.375) && close(bounds.sizeZ(), 2.5),
			"box submission dimensions preserve the full entity width, height, and depth");
	}

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) < 1.0e-9;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	/** Keeps the nested settings-group assertions local without adding runner dependencies. */
	private static final class SettingGroupCheck {
		private static void check(MobHighlight highlight) {
			var group = highlight.group();
			if (!"#73 New Highlight".equals(group.name()) || group.children().size() != 1
				|| !"#73 Islands".equals(group.children().getFirst().name())
				|| !group.children().getFirst().collapsedByDefault()) {
				throw new AssertionError("the per-highlight and folded island settings groups keep their titles");
			}
		}
	}
}
