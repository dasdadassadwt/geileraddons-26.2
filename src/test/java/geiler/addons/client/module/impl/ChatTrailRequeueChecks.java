package geiler.addons.client.module.impl;

import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.Setting;
import geiler.addons.client.render.TrailPath;
import geiler.addons.client.utility.AliasStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks for Aliases, Instance Requeue and Trail. */
public final class ChatTrailRequeueChecks {
	private ChatTrailRequeueChecks() { }

	public static void run() throws Exception {
		checkAliasStore();
		checkAliasesModule();
		checkRequeueLogic();
		checkRequeueModule();
		checkTrailPath();
		checkTrailModule();
	}

	private static void checkAliasStore() throws Exception {
		Path dir = Files.createTempDirectory("geiler-aliases");
		Path file = dir.resolve("aliases.json");
		AliasStore store = AliasStore.forPathChecks(file);
		check(store.put("dh", "warp dh", true), "an alias with a command is saved");
		check("warp dh".equals(store.resolve("dh")) && "warp dh".equals(store.resolve("DH"))
			&& "warp dh".equals(store.resolve("  dh  ")) && "warp dh".equals(store.resolve("/dh")),
			"an alias matches ignoring case, surrounding spaces and a leading slash");
		check(store.resolve("dhx") == null && store.resolve("dh now") == null && store.resolve("d") == null
			&& store.resolve(null) == null && store.resolve("") == null, "only the whole message matches an alias");
		check(store.put("hub", "/lobby", true) && "lobby".equals(store.resolve("hub")),
			"a command typed with a leading slash is stored without it");
		check(store.put("off", "spawn", false) && store.resolve("off") == null, "a disabled alias does not resolve");
		check(!store.put("", "x", true) && !store.put("a b", "x", true) && !store.put("a", "", true)
			&& !store.put("a", "/", true) && !store.put("a", "b\nc", true) && !store.put("x".repeat(33), "y", true)
			&& !store.put("a", "y".repeat(257), true), "blank, spaced, control and oversized values are rejected");
		check(store.put("dh", "warp dungeon_hub", true) && "warp dungeon_hub".equals(store.resolve("dh"))
			&& store.entries().stream().filter(entry -> entry.alias().equals("dh")).count() == 1,
			"saving an existing alias replaces its command");
		ClientJsonFile.flush();
		AliasStore reloaded = AliasStore.forPathChecks(file);
		check("warp dungeon_hub".equals(reloaded.resolve("dh")) && "lobby".equals(reloaded.resolve("hub"))
			&& reloaded.resolve("off") == null && reloaded.entries().size() == 3, "aliases persist across a reload");
		check(store.remove("hub") && store.resolve("hub") == null && !store.remove("hub"), "an alias can be removed once");
		AliasStore many = AliasStore.forPathChecks(dir.resolve("many.json"));
		for (int index = 0; index < AliasStore.MAX_ENTRIES; index++) {
			check(many.put("a" + index, "cmd", true), "alias " + index + " fits");
		}
		check(!many.put("overflow", "cmd", true) && many.put("a0", "other", true), "the list is capped but existing aliases can be edited");
	}

	private static void checkAliasesModule() {
		AliasesModule module = AliasesModule.INSTANCE;
		check(module.name().equals("Aliases") && module.category() == Category.MISCELLANEOUS && !module.defaultEnabled(),
			"Aliases is a disabled-by-default Miscellaneous module");
		check(!module.shouldIntercept(false, "dh", false) && !module.shouldIntercept(true, "dh", true),
			"a disabled module never intercepts, and a replacement is never aliased again");
	}

	private static void checkRequeueLogic() {
		InstanceRequeueLogic logic = new InstanceRequeueLogic();
		check(logic.onChat("> EXTRA STATS <", false, true) == InstanceRequeueLogic.Action.SCHEDULE_REQUEUE && logic.canFire(),
			"the Extra Stats line schedules a requeue");
		check(logic.onChat("Some other line", false, true) == InstanceRequeueLogic.Action.NONE
			&& logic.onChat("> EXTRA STATS < now", false, true) == InstanceRequeueLogic.Action.NONE,
			"only the exact Extra Stats line counts");
		check(logic.onChat("You left the party.", false, true) == InstanceRequeueLogic.Action.NONE && !logic.canFire(),
			"leaving the party blocks a pending requeue");
		check(logic.onChat("> EXTRA STATS <", false, true) == InstanceRequeueLogic.Action.NONE
			&& logic.onChat("> EXTRA STATS <", false, true) == InstanceRequeueLogic.Action.SCHEDULE_REQUEUE,
			"the next run end consumes the block, then requeues again");
		logic.reset();
		check(logic.onChat("You have been kicked from the party by Steve", false, true) == InstanceRequeueLogic.Action.NONE
			&& !logic.canFire(), "being kicked from the party blocks the requeue too");
		logic.reset();
		check(logic.canFire(), "a world change clears the block");
		logic.onChat("The party was disbanded because all invites expired and the party was empty.", false, false);
		check(logic.canFire(), "with Disable On Leave/Kick off, leaving does not block");
		for (String left : List.of("[VIP] TurtleMan111 has left the party.", "[MVP+] Amitay0 has left the party.",
			"nick007lll08 has been removed from the party.", "Kicked [MVP+] Amitay0 because they were offline.",
			"[VIP] Trishu_ was removed from your party because they disconnected.")) {
			logic.reset();
			logic.onChat(left, false, true);
			check(!logic.canFire(), "a teammate leaving blocks the requeue: " + left);
		}
		logic.reset();
		for (String harmless : List.of("Party > [MVP+] Bob: Amitay0 has left the party.",
			"Party > Bob: The party was disbanded", "Guild > Bob: x has disbanded the party",
			"Party Finder > Your group has been removed from the party finder because the leader went offline!",
			"The party leader, [VIP] ich_liebe_Pizza1 has disconnected, they have 5 minutes to rejoin before the party is disbanded.")) {
			logic.onChat(harmless, false, true);
			check(logic.canFire(), "player chat and notices that are not a leave do not block: " + harmless);
		}
		logic.onChat("[VIP] TurtleMan111 has left the party.", false, false);
		check(logic.canFire(), "with Disable On Leave/Kick off, a teammate leaving does not block");
		logic.reset();
		check(logic.onChat("You are no longer allowed to access this instance!", true, true)
			== InstanceRequeueLogic.Action.ANNOUNCE_KICK
			&& logic.onChat("You were kicked while joining that server!", true, true)
				== InstanceRequeueLogic.Action.ANNOUNCE_KICK
			&& logic.onChat("Exception Connecting:ReadTimeoutException : null", true, true)
				== InstanceRequeueLogic.Action.ANNOUNCE_KICK
			&& logic.onChat("You were kicked while joining that server!", false, true) == InstanceRequeueLogic.Action.NONE,
			"kick lines announce only when Announce Kick is on");
		check(InstanceRequeueLogic.delayTicks(2) == 40 && InstanceRequeueLogic.delayTicks(0) == 0
			&& InstanceRequeueLogic.delayTicks(99) == 600 && InstanceRequeueLogic.delayTicks(-4) == 0,
			"the delay is seconds in 0..30 converted to ticks");
	}

	private static void checkRequeueModule() {
		InstanceRequeueModule module = InstanceRequeueModule.INSTANCE;
		check(module.name().equals("Instance Requeue") && module.category() == Category.F7 && !module.defaultEnabled(),
			"Instance Requeue is a disabled-by-default Dungeons module");
		check(names(module.numberSettings()).equals(List.of("Requeue Delay")), "Requeue Delay is the only number setting");
		NumberSetting delay = module.numberSettings().getFirst();
		check(Float.compare(delay.min(), 0f) == 0 && Float.compare(delay.max(), 30f) == 0
			&& Float.compare(delay.value(), 2f) == 0 && delay.isInteger(), "Requeue Delay is 0-30 s, default 2");
		check(names(module.booleanSettings()).equals(List.of("Disable On Leave/Kick", "Announce Kick"))
			&& module.booleanSettings().stream().map(BooleanSetting::rawValue).toList().equals(List.of(true, false)),
			"Disable On Leave/Kick defaults on, Announce Kick off");
	}

	private static void checkTrailPath() {
		check(Double.compare(TrailPath.spacing(4), 0.25) == 0 && TrailPath.spacing(0) > 0, "density becomes a point spacing");
		TrailPath path = new TrailPath();
		check(path.add(0, 0, 0, 0, 0.25) && !path.add(0.1, 0, 0, 1, 0.25) && path.add(0.3, 0, 0, 2, 0.25) && path.size() == 2,
			"a point is added only after moving the spacing");
		path.add(0.6, 0, 0, 3, 0.25);
		path.prune(41, 40, 100.0);
		check(path.size() == 2 && Math.abs(path.points().getFirst().x() - 0.3) < 1e-9, "points older than the decay are dropped");
		TrailPath line = new TrailPath();
		for (int step = 0; step <= 10; step++) line.add(step, 0, 0, step, 0.5);
		line.prune(10, 100, 5.0);
		check(line.size() == 6 && Math.abs(line.points().getFirst().x() - 5.0) < 1e-9
			&& Math.abs(line.points().getLast().x() - 10.0) < 1e-9, "only the last range blocks of the path are kept");
		check(Float.compare(TrailPath.alpha(10, 10, 40), 1.0f) == 0 && Math.abs(TrailPath.alpha(30, 10, 40) - 0.5f) < 1e-6
			&& TrailPath.alpha(60, 10, 40) == 0f, "alpha fades linearly over the decay");
		check(TrailPath.fadedArgb(0xDC55C8FF, 0.5f) == 0x6E55C8FF && TrailPath.fadedArgb(0xDC55C8FF, 0f) == 0x0055C8FF,
			"fading scales the alpha and keeps the color");
		TrailPath jump = new TrailPath();
		jump.add(0, 0, 0, 0, 0.25);
		jump.add(100, 0, 0, 1, 0.25);
		check(jump.size() == 1 && Math.abs(jump.points().getFirst().x() - 100) < 1e-9, "a teleport restarts the trail");
		TrailPath full = new TrailPath();
		for (int step = 0; step < TrailPath.MAX_POINTS + 50; step++) full.add(step * 0.3, 0, 0, step, 0.25);
		check(full.size() == TrailPath.MAX_POINTS, "the trail holds a bounded number of points");
	}

	private static void checkTrailModule() {
		TrailModule module = TrailModule.INSTANCE;
		check(module.name().equals("Trail") && module.category() == Category.VISUAL && !module.defaultEnabled(),
			"Trail is a disabled-by-default Visual module");
		check(names(module.numberSettings()).equals(List.of("Trail Range", "Trail Decay (ticks)", "Trail Density", "Trail Width"))
			&& module.numberSettings().stream().map(NumberSetting::value).toList().equals(List.of(8f, 40f, 4f, 3f)),
			"range, decay, density and width settings with their defaults");
		check(names(module.colorSettings()).equals(List.of("Trail Color"))
			&& module.colorSettings().getFirst().hex().equals("55C8FFDC"), "the trail color defaults to a light blue");
		check(names(module.booleanSettings()).equals(List.of("Show In First Person"))
			&& module.booleanSettings().getFirst().rawValue(), "the trail shows in first person by default");
	}

	private static List<String> names(List<? extends Setting> settings) {
		return settings.stream().map(Setting::name).toList();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
