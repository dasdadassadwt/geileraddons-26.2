package geiler.addons.client.module.impl;

import geiler.addons.client.entity.RareMob;
import geiler.addons.client.location.Island;

public final class RareMobHighlighterChecks {
	private RareMobHighlighterChecks() { }

	public static void run() {
		tag("[Lv55] Zealot 13,000/13,000❤", Island.THE_END, RareMob.ZEALOT);
		tag("§8[§7Lv55§8] §cRunic Zealot §a52k§f/§a52k§c❤", Island.THE_END, RareMob.ZEALOT);
		tag("[Lv100] Zealot Bruiser 65k/65k❤", Island.THE_END, null);
		tag("﴾ [Lv100] Endstone Protector 5M/5M❤ ﴿", Island.THE_END, RareMob.ENDSTONE_PROTECTOR);
		tag("[Lv300] Arachne 20,000/20,000❤", Island.SPIDERS_DEN, RareMob.ARACHNE);
		tag("[Lv100] Arachne's Brood 4,000/4,000❤", Island.SPIDERS_DEN, RareMob.ARACHNE_BROOD);
		tag("[Lv200] Arachne's Keeper 3,000/3,000❤", Island.SPIDERS_DEN, null);
		tag("﴾ [Lv200] Ashfang 50M/50M❤ ﴿", Island.CRIMSON_ISLE, RareMob.ASHFANG);
		tag("[Lv200] Barbarian Duke X 70M/70M❤", Island.CRIMSON_ISLE, RareMob.BARBARIAN_DUKE);
		tag("[Lv200] Mage Outlaw 70M/70M❤", Island.CRIMSON_ISLE, RareMob.MAGE_OUTLAW);
		tag("[Lv500] Magma Boss 200M/200M❤", Island.CRIMSON_ISLE, RareMob.MAGMA_BOSS);
		tag("[Lv200] Bladesoul 50M/50M❤", Island.CRIMSON_ISLE, RareMob.BLADESOUL);
		tag("☠ [Lv100] Vanquisher 800k/800k❤", Island.CRIMSON_ISLE, RareMob.VANQUISHER);
		tag("[Lv750] Minos Inquisitor 40M/40M❤", Island.HUB, RareMob.MINOS_INQUISITOR);
		tag("[Lv5] Scatha 10/10❤", Island.CRYSTAL_HOLLOWS, RareMob.SCATHA);
		tag("[Lv55] Zealot 13,000/13,000❤", Island.HUB, null);
		tag("Zealot", Island.THE_END, null);
		tag("Ashfang's Follower 5/5❤", Island.CRIMSON_ISLE, null);

		RareMobHighlighterModule module = RareMobHighlighterModule.INSTANCE;
		check(!module.isEnabled(), "off by default");
		for (RareMob mob : RareMob.values()) check(module.mobToggle(mob).value(), mob + " defaults on");
		check(RareMob.ZEALOT.common() && RareMob.ARACHNE_BROOD.common() && !RareMob.ASHFANG.common(),
			"common mobs never alert");
		check(!module.partyMessageToggle().value(), "party message is off by default");
		check("Ashfang at x: -483, y: 137, z: -1015".equals(
			RareMobHighlighterModule.partyText(RareMob.ASHFANG, -482.5, 137.2, -1014.1)), "party text uses block coordinates");
	}

	private static void tag(String name, Island island, RareMob expected) {
		RareMob actual = RareMob.fromNameTag(name, island);
		check(actual == expected, name + " on " + island + " -> " + actual + ", expected " + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
