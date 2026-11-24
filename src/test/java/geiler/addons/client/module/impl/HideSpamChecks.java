package geiler.addons.client.module.impl;

import geiler.addons.client.module.impl.HideSpamModule.Line;

/** Focused behavior checks for the Hide Spam chat rules. */
public final class HideSpamChecks {
	private HideSpamChecks() { }

	public static void main(String[] args) {
		check(HideSpamModule.match("This ability is on cooldown for 5s.") == Line.COOLDOWN
			&& HideSpamModule.match("This ability is on cooldown for 1.5s.") == Line.COOLDOWN
			&& HideSpamModule.match("No more charges, next one in 3s!") == Line.COOLDOWN,
			"ability cooldown lines match");
		check(HideSpamModule.match("There are blocks in the way!") == Line.BLOCKS, "blocks in the way matches");
		check(HideSpamModule.match("Your Implosion hit 1 enemy for 1,234,567.8 damage.") == Line.DAMAGE
			&& HideSpamModule.match("Your Molten Wave hit 12 enemies for 25,000 damage.") == Line.DAMAGE
			&& HideSpamModule.match("Your Spirit Sceptre hit 3 enemies for 900 damage.") == Line.DAMAGE,
			"ability damage lines match singular and plural with comma numbers");
		check(HideSpamModule.match("You healed yourself for 120 health!") == Line.HEAL_BREAKER
			&& HideSpamModule.match("Steve_1 healed you for 1,200 health!") == Line.HEAL_BREAKER,
			"heal lines match");
		check(HideSpamModule.match("A mystical force prevents you from digging that block!") == Line.HEAL_BREAKER
			&& HideSpamModule.match("A mystical force prevents you digging in this room!") == Line.HEAL_BREAKER
			&& HideSpamModule.match("A mystical force prevents you digging there!") == Line.HEAL_BREAKER
			&& HideSpamModule.match("You don't have enough charges to break this block right now!") == Line.HEAL_BREAKER,
			"dungeon breaker lines match");
		check(HideSpamModule.match("+5 Kill Combo") == Line.COMBO
			&& HideSpamModule.match("+5 Kill Combo +3% ✯ Magic Find") == Line.COMBO
			&& HideSpamModule.match("+5 Kill Combo +10 coins per kill") == Line.COMBO
			&& HideSpamModule.match("+5 Kill Combo +2☯ Combat Wisdom") == Line.COMBO
			&& HideSpamModule.match("Your Kill Combo has expired! You reached a 50 Kill Combo!") == Line.COMBO,
			"kill combo lines match");
		check(HideSpamModule.match("Warped from the Spawn Teleport Pad to the Hub Teleport Pad!") == Line.TELEPORT_PAD
			&& HideSpamModule.match("This Teleport Pad does not have a destination set!") == Line.TELEPORT_PAD,
			"teleport pad lines match");
		check(HideSpamModule.match("Mimic Killed!") == Line.MIMIC
			&& HideSpamModule.match("Party > Steve: Mimic dead!") == Line.MIMIC
			&& HideSpamModule.match("$SKYTILS-DUNGEON-SCORE-MIMIC$") == Line.MIMIC
			&& HideSpamModule.match("The Mimic Killed! soon") == null,
			"mimic lines match");
		check(HideSpamModule.match("Autopet equipped your [Lvl 100] Tiger! VIEW RULE") == Line.AUTOPET
			&& HideSpamModule.match("Autopet equipped your [Lvl 100] Tiger!") == null,
			"autopet line needs the VIEW RULE tail");
		check(HideSpamModule.match("New day! Your Lottery buff changed!") == Line.LOTTERY
			&& HideSpamModule.match("New buff: Gain +5 Fig Fortune.") == Line.LOTTERY
			&& HideSpamModule.match("You can disable this messaging by toggling Lottery in your /hotf!") == Line.LOTTERY,
			"lottery lines match");
		check(HideSpamModule.match("New day! Your Sky Mall buff changed!") == Line.SKY_MALL
			&& HideSpamModule.match("New buff: Gain +20 Mining Speed.") == Line.SKY_MALL
			&& HideSpamModule.match("New buff: -10% Pickaxe Ability cooldowns.") == Line.SKY_MALL
			&& HideSpamModule.match("You can disable this messaging by toggling Sky Mall in your /hotm!") == Line.SKY_MALL,
			"sky mall lines match");
		check(HideSpamModule.match("[MVP+] Steve: This ability is on cooldown for 5s.") == null
			&& HideSpamModule.match("This ability is on cooldown for soon.") == null
			&& HideSpamModule.match("Your Implosion hit enemies for damage.") == null
			&& HideSpamModule.match("There are blocks in the way") == null
			&& HideSpamModule.match("You healed yourself for 5 health! Nice") == null
			&& HideSpamModule.match("") == null,
			"player chat and near-miss lines pass");

		HideSpamModule module = HideSpamModule.INSTANCE;
		check(module.booleanSettings().size() == 10 && module.booleanSettings().stream().allMatch(s -> s.value()),
			"all ten line toggles default on");
		check(!module.isEnabled() && !module.shouldHide("There are blocks in the way!"),
			"Hide Spam is off by default and hides nothing while off");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
