package geiler.addons.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlags;

import java.util.Set;
import java.util.stream.Collectors;

/** Checks the real Fabric registration callback's Brigadier tree without executing client effects. */
public final class GeilerAddonsCommandTreeChecks {
	private GeilerAddonsCommandTreeChecks() { }

	public static void run() {
		GeilerAddonsCommand.register();
		CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
		CommandBuildContext registryAccess = CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS);
		ClientCommandRegistrationCallback.EVENT.invoker().register(dispatcher, registryAccess);

		CommandNode<FabricClientCommandSource> root = dispatcher.getRoot().getChild("ga");
		require(root != null && root.getCommand() != null, "the registered /ga root executes without children");
		for (String alias : new String[] {"Ga", "gA", "GA"}) {
			CommandNode<FabricClientCommandSource> aliasNode = dispatcher.getRoot().getChild(alias);
			require(aliasNode != null && aliasNode.getRedirect() == root && aliasNode.getCommand() != null,
				"/" + alias + " redirects to /ga and also executes without children");
		}

		require(children(root).containsAll(Set.of(
			"reset", "config", "dstats", "startdoor", "pfretry", "pfstats", "ep", "sb", "timer")),
			"all direct /ga commands are registered");
		for (String child : new String[] {"reset", "config", "dstats", "startdoor", "pfretry", "pfstats", "ep", "sb", "timer"}) {
			require(child(root, child) != null, "/ga has child " + child);
		}
		require(command(child(root, "reset")), "/ga reset executes");
		require(command(child(root, "dstats")), "/ga dstats works without a name");
		require(command(child(root, "startdoor")), "/ga startdoor retains its usage response without a child");

		CommandNode<FabricClientCommandSource> config = child(root, "config");
		for (String child : new String[] {"export", "import", "save", "folder"}) {
			require(child(config, child) != null, "/ga config has child " + child);
		}
		require(command(child(config, "export")), "/ga config export works without a name");
		assertStringArgument(child(child(config, "export"), "name"), StringArgumentType.StringType.GREEDY_PHRASE,
			"/ga config export accepts a greedy optional name");
		for (String child : new String[] {"import", "save", "folder"}) {
			require(command(child(config, child)), "/ga config " + child + " executes");
		}

		CommandNode<FabricClientCommandSource> dstatsName = child(child(root, "dstats"), "name");
		assertStringArgument(dstatsName, StringArgumentType.StringType.QUOTABLE_PHRASE,
			"/ga dstats name remains one quoted or unquoted string argument");
		require(command(dstatsName), "/ga dstats name executes a player lookup");
		require(dstatsName instanceof ArgumentCommandNode<?, ?> node && node.getCustomSuggestions() != null,
			"/ga dstats name keeps its player suggestion provider");

		CommandNode<FabricClientCommandSource> startdoor = child(root, "startdoor");
		for (String child : new String[] {"mark", "cancel", "reset"}) {
			require(command(child(startdoor, child)), "/ga startdoor " + child + " executes");
		}

		CommandNode<FabricClientCommandSource> pfretry = child(root, "pfretry");
		require(command(pfretry), "/ga pfretry retains its usage response without a name");
		CommandNode<FabricClientCommandSource> pfretryName = child(pfretry, "name");
		assertStringArgument(pfretryName, StringArgumentType.StringType.SINGLE_WORD,
			"/ga pfretry name accepts one word");
		require(command(pfretryName), "/ga pfretry name dispatches a player retry");
		require(command(child(root, "pfstats")), "/ga pfstats re-shows cached party stats");

		for (String supply : new String[] {"ep", "sb"}) {
			CommandNode<FabricClientCommandSource> supplyNode = child(root, supply);
			require(command(supplyNode), "/ga " + supply + " retains its default target");
			CommandNode<FabricClientCommandSource> amount = child(supplyNode, "amount");
			require(amount instanceof ArgumentCommandNode<?, ?>, "/ga " + supply + " amount is an argument");
			IntegerArgumentType type = (IntegerArgumentType) ((ArgumentCommandNode<?, ?>) amount).getType();
			require(type.getMinimum() == 1 && type.getMaximum() == Integer.MAX_VALUE,
				"/ga " + supply + " amount accepts positive integers");
			require(command(amount), "/ga " + supply + " amount dispatches its request");
		}

		CommandNode<FabricClientCommandSource> timer = child(root, "timer");
		require(command(timer), "/ga timer shows state without a child");
		for (String child : new String[] {"get", "set", "pause", "resume", "reset"}) {
			require(command(child(timer, child)), "/ga timer " + child + " executes");
		}
		CommandNode<FabricClientCommandSource> duration = child(child(timer, "set"), "duration");
		assertStringArgument(duration, StringArgumentType.StringType.GREEDY_PHRASE,
			"/ga timer set accepts a greedy duration expression");
		require(command(duration), "/ga timer set duration dispatches the update");
		checkMacroCommands(dispatcher);
		System.out.println("GeilerAddons command tree checks passed.");
	}

	/** The macro commands beneath /ga: areakiller, addmobkill, pathfind and spawnmob, with the source's argument shapes. */
	private static void checkMacroCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		CommandNode<FabricClientCommandSource> ga = dispatcher.getRoot().getChild("ga");
		for (String old : new String[] {"areakiller", "addmobkill", "pathfind", "spawnmob", "etw"}) {
			require(dispatcher.getRoot().getChild(old) == null, "/" + old + " is no longer a top-level command");
		}
		CommandNode<FabricClientCommandSource> killer = ga.getChild("areakiller");
		require(killer != null && killer.getCommand() == null, "/ga areakiller needs a subcommand");
		require(children(killer).equals(Set.of(
			"start", "stop", "status", "gui", "add", "remove", "list", "gate", "margin", "slot", "radius", "rest", "overlay")),
			"/ga areakiller has the ported subcommands and neither map nor mine");
		CommandNode<FabricClientCommandSource> area = ga.getChild("area");
		require(area != null && area.getCommand() == null && children(area).equals(Set.of("paint", "use", "show", "list", "info", "clear", "delete")),
			"/ga area has paint, use, show, list, info, clear and delete");
		CommandNode<FabricClientCommandSource> paint = child(area, "paint");
		require(children(paint).contains("off") && children(paint).contains("name"), "/ga area paint takes off or an area name");
		CommandNode<FabricClientCommandSource> brushRadius = child(child(paint, "name"), "radius");
		require(children(brushRadius).equals(Set.of("allowed", "preferred", "forbidden", "wall", "rest", "erase")),
			"/ga area paint <name> <radius> ends in one of the six paints");
		CommandNode<FabricClientCommandSource> brush = ga.getChild("brush");
		require(brush != null && children(brush).equals(Set.of("off", "name")), "/ga brush takes off or an area name");
		require(children(child(brush, "name")).equals(Set.of("allowed", "preferred", "forbidden", "wall", "rest", "erase")),
			"/ga brush <name> is followed by the paint");
		require(children(child(child(brush, "name"), "rest")).equals(Set.of("radius")) && command(child(child(child(brush, "name"), "rest"), "radius")),
			"/ga brush <name> <type> <radius> executes");
		CommandNode<FabricClientCommandSource> rest = child(killer, "rest");
		require(command(rest) && children(rest).equals(Set.of("set", "clear", "on", "radius", "delay")),
			"/ga areakiller rest shows the point and sets, clears, switches, sizes and delays it");
		for (String child : new String[] {"start", "stop", "status", "gui", "add", "list"}) {
			require(command(child(killer, child)), "/ga areakiller " + child + " executes");
		}
		assertStringArgument(child(child(killer, "add"), "mob"), StringArgumentType.StringType.GREEDY_PHRASE,
			"/ga areakiller add accepts a greedy mob");
		require(!command(child(killer, "remove")), "/ga areakiller remove needs a mob");
		assertStringArgument(child(child(killer, "remove"), "mob"), StringArgumentType.StringType.GREEDY_PHRASE,
			"/ga areakiller remove accepts a greedy mob");

		CommandNode<FabricClientCommandSource> gate = child(killer, "gate");
		for (String argument : new String[] {"x1", "y1", "z1", "x2", "y2", "z2"}) {
			require(!command(gate), "/ga areakiller gate needs all six coordinates");
			gate = child(gate, argument);
			require(gate instanceof ArgumentCommandNode<?, ?> node && node.getType() instanceof IntegerArgumentType,
				"/ga areakiller gate " + argument + " is an integer");
		}
		require(command(gate), "/ga areakiller gate executes after six coordinates");
		assertIntRange(child(child(killer, "margin"), "blocks"), 0, 3, "/ga areakiller margin is 0 to 3");
		assertIntRange(child(child(killer, "slot"), "slot"), 1, 9, "/ga areakiller slot is 1 to 9");
		assertIntRange(child(child(killer, "radius"), "blocks"), 0, 1024, "/ga areakiller radius is 0 to 1024");
		require(command(child(child(killer, "overlay"), "on")), "/ga areakiller overlay takes a flag");

		CommandNode<FabricClientCommandSource> addMobKill = ga.getChild("addmobkill");
		require(command(addMobKill), "/ga addmobkill adds the mob looked at");
		assertStringArgument(child(addMobKill, "mob"), StringArgumentType.StringType.GREEDY_PHRASE,
			"/ga addmobkill accepts a greedy mob");

		CommandNode<FabricClientCommandSource> pathfind = ga.getChild("pathfind");
		require(command(pathfind), "/ga pathfind alone shows its usage");
		require(command(child(pathfind, "stop")), "/ga pathfind stop executes");
		CommandNode<FabricClientCommandSource> axis = pathfind;
		for (String argument : new String[] {"x", "y", "z"}) {
			axis = child(axis, argument);
			require(axis instanceof ArgumentCommandNode<?, ?> node
				&& node.getType() instanceof com.mojang.brigadier.arguments.DoubleArgumentType,
				"/ga pathfind " + argument + " is a double");
		}
		require(command(axis) && !command(child(pathfind, "x")), "/ga pathfind executes only after x y z");
		CommandNode<FabricClientCommandSource> snapshot = child(pathfind, "snapshot");
		require(command(snapshot), "/ga pathfind snapshot executes with the default radius");
		assertIntRange(child(snapshot, "radius"), 4, 64, "/ga pathfind snapshot radius is 4 to 64");

		CommandNode<FabricClientCommandSource> spawn = ga.getChild("spawnmob");
		require(spawn != null && !command(spawn), "/ga spawnmob needs its arguments");
		CommandNode<FabricClientCommandSource> radius = child(child(spawn, "count"), "radius");
		require(((ArgumentCommandNode<?, ?>) child(spawn, "count")).getType() instanceof IntegerArgumentType count
			&& count.getMinimum() == 1 && count.getMaximum() == 200 && !command(child(spawn, "count")),
			"/ga spawnmob count is 1 to 200 and does not execute alone");
		require(((ArgumentCommandNode<?, ?>) radius).getType() instanceof IntegerArgumentType range
			&& range.getMinimum() == 1 && range.getMaximum() == 64 && !command(radius),
			"/ga spawnmob radius is 1 to 64 and does not execute alone");
		CommandNode<FabricClientCommandSource> mob = child(radius, "mob");
		require(command(mob) && ((ArgumentCommandNode<?, ?>) mob).getType() instanceof com.mojang.brigadier.arguments.StringArgumentType,
			"/ga spawnmob <count> <radius> <mob> executes");
	}

	private static void assertIntRange(CommandNode<FabricClientCommandSource> node, int min, int max, String message) {
		require(node instanceof ArgumentCommandNode<?, ?>, message);
		Object type = ((ArgumentCommandNode<?, ?>) node).getType();
		require(type instanceof IntegerArgumentType range && range.getMinimum() == min && range.getMaximum() == max, message);
		require(command(node), message);
	}

	private static Set<String> children(CommandNode<FabricClientCommandSource> node) {
		return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
	}

	@SuppressWarnings("unchecked")
	private static CommandNode<FabricClientCommandSource> child(CommandNode<FabricClientCommandSource> node,
		String name) {
		if (node == null) return null;
		return (CommandNode<FabricClientCommandSource>) node.getChild(name);
	}

	private static void assertStringArgument(CommandNode<FabricClientCommandSource> node,
		StringArgumentType.StringType expected, String message) {
		require(node instanceof ArgumentCommandNode<?, ?>, message);
		Object type = ((ArgumentCommandNode<?, ?>) node).getType();
		require(type instanceof StringArgumentType && ((StringArgumentType) type).getType() == expected, message);
	}

	private static boolean command(CommandNode<FabricClientCommandSource> node) {
		return node != null && node.getCommand() != null;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
