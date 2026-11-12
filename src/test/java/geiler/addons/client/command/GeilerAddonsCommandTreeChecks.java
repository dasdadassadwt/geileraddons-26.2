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
		System.out.println("GeilerAddons command tree checks passed.");
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
