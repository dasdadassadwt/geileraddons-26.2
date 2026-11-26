package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;

import java.util.List;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Offline checks for Armor Transparency's rules and its module declaration. */
public final class ArmorTransparencyChecks {
	private ArmorTransparencyChecks() { }

	public static void run() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();

		// Opacity: the slot's percent, but only for the wearers the switches allow.
		check(ArmorTransparencyRules.opacity(100, true, true, true) == 1.0f, "100 percent leaves armor untouched");
		check(ArmorTransparencyRules.opacity(0, true, true, true) == 0.0f, "0 percent hides armor");
		check(ArmorTransparencyRules.opacity(40, false, true, true) == 0.4f, "40 percent fades another player's armor");
		check(ArmorTransparencyRules.opacity(40, true, false, true) == 1.0f, "Own Armor off leaves your armor untouched");
		check(ArmorTransparencyRules.opacity(40, false, true, false) == 1.0f, "Other Players off leaves theirs untouched");
		check(ArmorTransparencyRules.opacity(250, true, true, true) == 1.0f && ArmorTransparencyRules.opacity(-5, true, true, true) == 0.0f,
			"percent is clamped to 0-100");

		// Alpha: the opacity scales the colour's own alpha and keeps the rgb.
		check(ArmorTransparencyRules.fade(0xFFFFFFFF, 0.5f) == 0x80FFFFFF, "half opacity halves a white layer's alpha");
		check(ArmorTransparencyRules.fade(0xFF336699, 1.0f) == 0xFF336699, "full opacity keeps the colour exactly");
		check(ArmorTransparencyRules.fade(0x80336699, 0.5f) == 0x40336699, "alpha scales from the colour's own alpha");

		// Slot: matched by identity against the wearer's four equipment stacks.
		ItemStack head = new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET);
		ItemStack chest = new ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
		ItemStack legs = new ItemStack(net.minecraft.world.item.Items.DIAMOND_LEGGINGS);
		ItemStack feet = new ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS);
		check(ArmorTransparencyRules.slotOf(head, head, chest, legs, feet) == EquipmentSlot.HEAD
			&& ArmorTransparencyRules.slotOf(chest, head, chest, legs, feet) == EquipmentSlot.CHEST
			&& ArmorTransparencyRules.slotOf(legs, head, chest, legs, feet) == EquipmentSlot.LEGS
			&& ArmorTransparencyRules.slotOf(feet, head, chest, legs, feet) == EquipmentSlot.FEET
			&& ArmorTransparencyRules.slotOf(new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET), head, chest, legs, feet) == null,
			"a stack maps to the slot it is worn in, and an unrelated stack maps to none");

		// Module declaration: off by default, four 0-100 sliders defaulting to 100, two wearer switches defaulting on.
		ArmorTransparencyModule module = ArmorTransparencyModule.INSTANCE;
		check("Armor Transparency".equals(module.name()) && module.category() == Category.VISUAL && !module.defaultEnabled(),
			"Armor Transparency is a Visual module that is off by default");
		List<String> sliders = module.numberSettings().stream().map(Setting::name).toList();
		check(List.of("Helmet", "Chestplate", "Leggings", "Boots").equals(sliders), "one opacity slider per armor slot");
		for (NumberSetting slider : module.numberSettings()) {
			check(slider.min() == 0f && slider.max() == 100f && slider.value() == 100f && slider.isInteger(),
				slider.name() + " is an integer 0-100 slider defaulting to 100");
		}
		check(List.of("Own Armor", "Other Players").equals(module.booleanSettings().stream().map(Setting::name).toList())
			&& module.booleanSettings().stream().allMatch(s -> s.value()), "Own Armor and Other Players default on");
		check(module.opacityFor(EquipmentSlot.HEAD, 1) == 1.0f, "the module changes nothing while it is off");

		// Worn skulls are not armor: the head layer mixin must exist and be registered so the Helmet slider reaches them.
		try {
			java.nio.file.Path mixins = java.nio.file.Path.of("src", "client", "java", "geiler", "addons", "client", "mixin");
			String headMixin = java.nio.file.Files.readString(mixins.resolve("CustomHeadLayerArmorTransparencyMixin.java"));
			String registry = java.nio.file.Files.readString(java.nio.file.Path.of("src", "client", "resources", "geileraddons.client.mixins.json"));
			check(headMixin.contains("EquipmentSlot.HEAD") && headMixin.contains("submitSkull") && registry.contains("CustomHeadLayerArmorTransparencyMixin")
				&& registry.contains("SkullBlockRendererAccessor"), "worn skulls follow the Helmet slider through a registered head layer mixin");
		} catch (java.io.IOException error) {
			throw new IllegalStateException("ArmorTransparencyChecks: could not read the head layer mixin", error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("ArmorTransparencyChecks: " + message);
	}
}
