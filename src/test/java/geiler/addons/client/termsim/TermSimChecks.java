package geiler.addons.client.termsim;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import kotlin.random.Random;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Offline checks for the Terminal Simulator: every simulator can be solved by its rules, and the simulator can never
 * reach the server (no packet code in the package, a firewall for container packets, local-only commands).
 */
public final class TermSimChecks {
	private TermSimChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkPanes();
		checkRubix();
		checkNumbers();
		checkStartsWith();
		checkSelectAll();
		checkMelody();
		checkStartMenu();
		checkNeverTouchesTheServer();
		checkFirewall();
		checkScreenShape();
		checkCommand();
		checkCommandFailuresStayLocal();
		checkOpenPathHasOneHop();
		checkNoOtherModuleReactsToTheSimulator();
	}

	private static int count(TermSim sim, String path) {
		int n = 0;
		for (SimItem item : sim.getSlots()) if (item.getPath().equals(path)) n++;
		return n;
	}

	private static void checkPanes() {
		PanesSim sim = new PanesSim(new kotlin.random.XorWowRandom(21, 22));
		sim.create();
		require(count(sim, "red_stained_glass_pane") + count(sim, "lime_stained_glass_pane") == 15, "panes: fifteen panes in the grid");
		require(!sim.click(0, 0).getSound(), "panes: the black border does nothing");
		SimOutcome last = null;
		for (int i = 0; i < 45; i++) {
			if (sim.getSlots()[i].getPath().equals("red_stained_glass_pane")) last = sim.click(i, 0);
		}
		require(last != null && last.getCompleted() && count(sim, "red_stained_glass_pane") == 0, "panes: clicking every red pane completes");
		SimOutcome toggle = sim.click(11, 0);
		require(toggle.getSound() && !toggle.getCompleted() && count(sim, "red_stained_glass_pane") == 1, "panes: a green pane turns red again");
	}

	private static void checkRubix() {
		RubixSim sim = new RubixSim(new kotlin.random.XorWowRandom(7, 11));
		sim.create();
		int guard = 0;
		SimOutcome out = null;
		while (guard++ < 200 && (out == null || !out.getCompleted())) {
			String target = sim.getSlots()[12].getPath();
			for (int index : new int[] {12, 13, 14, 21, 22, 23, 30, 31, 32}) {
				if (!sim.getSlots()[index].getPath().equals(target)) {
					out = sim.click(index, 0);
					break;
				}
			}
			if (out == null) break;
		}
		require(out != null && out.getCompleted(), "rubix: clicking every odd pane forward ends with one color");
		require(!sim.click(0, 0).getSound(), "rubix: the border does nothing");
		RubixSim right = new RubixSim(new kotlin.random.XorWowRandom(3, 5));
		right.create();
		String before = right.getSlots()[12].getPath();
		right.click(12, 1);
		require(!right.getSlots()[12].getPath().equals(before), "rubix: a right click moves the pane back");
	}

	private static void checkNumbers() {
		NumbersSim sim = new NumbersSim(new kotlin.random.XorWowRandom(1, 2));
		sim.create();
		int redPanes = count(sim, "red_stained_glass_pane");
		require(redPanes == 10, "numbers: ten numbered panes");
		int wrong = -1;
		int right = -1;
		int min = 1000;
		for (int i = 0; i < 36; i++) {
			SimItem item = sim.getSlots()[i];
			if (item.getPath().equals("red_stained_glass_pane") && item.getCount() < min) {
				min = item.getCount();
				right = i;
			}
		}
		for (int i = 0; i < 36; i++) {
			if (i != right && sim.getSlots()[i].getPath().equals("red_stained_glass_pane")) {
				wrong = i;
				break;
			}
		}
		require(min == 1 && sim.click(wrong, 0).getSound() == false, "numbers: only the lowest number can be clicked");
		SimOutcome out = null;
		for (int n = 1; n <= 10; n++) {
			for (int i = 0; i < 36; i++) {
				if (sim.getSlots()[i].getPath().equals("red_stained_glass_pane") && sim.getSlots()[i].getCount() == n) {
					out = sim.click(i, 0);
					break;
				}
			}
		}
		require(out != null && out.getCompleted(), "numbers: clicking 1 to 10 completes");
	}

	private static ItemPool pool() {
		List<String> paths = List.of("apple", "bone", "bread", "book", "cake", "stone", "golden_apple", "blue_stained_glass_pane");
		return new ItemPool(paths, Set.of("golden_apple"), p -> p.replace('_', ' '));
	}

	private static void checkStartsWith() {
		StartsWithSim sim = new StartsWithSim(new kotlin.random.XorWowRandom(5, 9), pool(), "B");
		require(sim.getTitle().equals("What starts with: 'B'?"), "starts with: title");
		sim.create();
		int wrongIndex = -1;
		for (int i = 0; i < 45; i++) {
			SimItem item = sim.getSlots()[i];
			if (!item.isBlackPane() && !item.getPath().toLowerCase().startsWith("b")) {
				wrongIndex = i;
				break;
			}
		}
		if (wrongIndex >= 0) {
			SimOutcome wrong = sim.click(wrongIndex, 0);
			require(!wrong.getMessages().isEmpty() && wrong.getMessages().get(0).contains("does not start with"),
				"starts with: wrong item is explained");
		}
		SimOutcome out = null;
		for (int i = 0; i < 45; i++) {
			SimItem item = sim.getSlots()[i];
			if (!item.isBlackPane() && item.getPath().toLowerCase().startsWith("b")) {
				out = sim.click(i, 0);
				SimOutcome again = sim.click(i, 0);
				require(!again.getMessages().isEmpty() && again.getMessages().get(0).contains("Already selected"),
					"starts with: a selected item says so");
			}
		}
		require(out != null && out.getSound(), "starts with: B items can be clicked");
		boolean allDone = true;
		for (int i = 0; i < 45; i++) {
			SimItem item = sim.getSlots()[i];
			if (!item.isBlackPane() && item.getPath().toLowerCase().startsWith("b") && !item.getGlint()) allDone = false;
		}
		require(allDone, "starts with: every B item ends selected");
	}

	private static void checkSelectAll() {
		SelectAllSim sim = new SelectAllSim(new kotlin.random.XorWowRandom(8, 13), "light_gray");
		require(sim.getTitle().equals("Select all the SILVER items!"), "select: light gray is shown as SILVER: " + sim.getTitle());
		require(SelectAllSim.possible("blue").contains("lapis_lazuli") && SelectAllSim.possible("white").contains("bone_meal")
			&& SelectAllSim.possible("red").contains("red_dye"), "select: item families per color");
		SelectAllSim blue = new SelectAllSim(new kotlin.random.XorWowRandom(2, 4), "blue");
		blue.create();
		List<String> mine = SelectAllSim.possible("blue");
		SimOutcome out = null;
		for (int i = 0; i < 54; i++) {
			if (mine.contains(blue.getSlots()[i].getPath())) out = blue.click(i, 0);
		}
		require(out != null && out.getCompleted(), "select: clicking every item of the color completes");
		int other = -1;
		SelectAllSim red = new SelectAllSim(new kotlin.random.XorWowRandom(6, 6), "red");
		red.create();
		for (int i = 0; i < 54; i++) {
			if (!red.getSlots()[i].isBlackPane() && !SelectAllSim.possible("red").contains(red.getSlots()[i].getPath())) {
				other = i;
				break;
			}
		}
		if (other >= 0) require(red.click(other, 0).getMessages().get(0).contains("is not: RED"), "select: other colors are refused");
	}

	private static void checkMelody() {
		MelodySim sim = new MelodySim(new kotlin.random.XorWowRandom(4, 4));
		sim.create();
		SimOutcome out = null;
		for (int round = 0; round < 3; round++) {
			int guard = 0;
			while (!sim.getAligned() && guard++ < 500) sim.tick();
			require(sim.getAligned(), "melody: the pointer reaches the column");
			int row = sim.getRow();
			out = sim.click(row * 9 + 7, 0);
			require(out.getSound(), "melody: aligned click counts, round " + round);
		}
		require(out.getCompleted(), "melody: three rows complete it");
		MelodySim early = new MelodySim(new kotlin.random.XorWowRandom(9, 9));
		early.create();
		require(!early.click(16, 0).getSound() || early.getAligned(), "melody: a click off the column does nothing");
	}

	private static void checkStartMenu() {
		boolean[] reset = {false};
		StartMenu menu = new StartMenu(kind -> kind == SimKind.PANES ? 0.002f : null, () -> {
			reset[0] = true;
			return kotlin.Unit.INSTANCE;
		}, Random.Default);
		menu.create();
		require(menu.getSlots()[4].getPath().equals("black_dye") && menu.getSlots()[13].getPath().equals("white_dye")
			&& menu.getSlots()[10].getPath().equals("lime_dye") && menu.getSlots()[16].getPath().equals("purple_dye"),
			"menu: layout of the buttons");
		require(menu.getSlots()[10].getLore().get(0).equals("§7Personal Best: §d0.00s")
			&& menu.getSlots()[11].getLore().get(0).equals("§7No PB set!"), "menu: personal best lore");
		require(menu.click(10, 0).getOpen().getKind() == SimKind.PANES && menu.click(16, 0).getOpen().getKind() == SimKind.MELODY
			&& menu.click(13, 0).getOpen().getRandom(), "menu: buttons open their terminal");
		SimOutcome ask = menu.click(4, 0);
		require(!reset[0] && ask.getMessages().get(0).contains("Are you sure"), "menu: first reset click asks");
		for (int i = 0; i < 61; i++) menu.tick();
		require(menu.takeMessages().contains("§aPBs reset cancelled."), "menu: the question times out after 60 ticks");
		menu.click(4, 0);
		SimOutcome confirmed = menu.click(4, 0);
		require(reset[0] && confirmed.getMessages().get(0).equals("§aPBs reset!"), "menu: second click resets");
		require(!StartMenu.Companion.getRANDOM_KINDS().contains(SimKind.MELODY), "menu: random never picks melody");
	}

	/** The simulator package must contain no code that can build or send a packet or a chat line. */
	private static void checkNeverTouchesTheServer() {
		List<String> forbidden = List.of(
			"handleContainerInput", "gameMode", ".connection", "sendCommand", "sendChat", ".send(", "ServerboundChat",
			"closeContainer", "super.slotClicked", "super.onClose", "ClientPacketListener", "Connection", "sendUnsignedCommand");
		List<Path> roots = List.of(
			Path.of("src/client/kotlin/geiler/addons/client/termsim"), Path.of("src/client/java/geiler/addons/client/termsim"));
		int files = 0;
		for (Path root : roots) {
			require(Files.isDirectory(root), "simulator source folder exists: " + root.toAbsolutePath());
			try (Stream<Path> stream = Files.walk(root)) {
				for (Path file : stream.filter(Files::isRegularFile).toList()) {
					files++;
					String text = stripComments(Files.readString(file));
					for (String token : forbidden) {
						require(!text.contains(token), file.getFileName() + " must not contain '" + token + "'");
					}
				}
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		}
		require(files >= 6, "the scan found the simulator sources: " + files);
		try {
			String solver = Files.readString(Path.of("src/client/kotlin/geiler/addons/client/module/impl/TerminalSolverModule.kt"));
			int sim = solver.indexOf("simulator.clickIndex");
			int real = solver.indexOf("gameMode.handleContainerInput");
			require(sim > 0 && real > sim, "the solver routes simulator clicks to the screen before the real packet path");
			require(solver.contains("} else if (player != null && gameMode != null) {"), "the real click is the else branch of the simulator check");
			String mixin = Files.readString(Path.of("src/client/java/geiler/addons/client/mixin/ClientCommonPacketListenerMixin.java"));
			require(mixin.contains("TermSimGuard.shouldDrop(packet)") && mixin.contains("send(Lnet/minecraft/network/protocol/Packet;)V"),
				"the firewall mixin hooks every outgoing packet");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
	}

	private static void checkFirewall() {
		TermSimGuard.closed(TermSimGuard.class);
		require(!TermSimGuard.active() && !TermSimGuard.blocks("ServerboundContainerClickPacket"),
			"firewall: nothing is blocked when no simulator is open");
		Object screen = new Object();
		TermSimGuard.opened(screen);
		try {
			for (String name : List.of("ServerboundContainerClickPacket", "ServerboundContainerClosePacket",
				"ServerboundContainerButtonClickPacket", "ServerboundContainerSlotStateChangedPacket", "ServerboundPlaceRecipePacket",
				"ServerboundSetCreativeModeSlotPacket", "ServerboundSelectBundleItemPacket", "ServerboundRenameItemPacket",
				"ServerboundSelectTradePacket", "ServerboundSetBeaconPacket")) {
				require(TermSimGuard.blocks(name), "firewall blocks " + name);
			}
			for (String name : List.of("ServerboundChatPacket", "ServerboundChatCommandPacket", "ServerboundMovePlayerPacket",
				"ServerboundPlayerActionPacket", "ServerboundUseItemPacket")) {
				require(!TermSimGuard.blocks(name), "firewall leaves " + name + " alone");
			}
			for (String name : TermSimGuard.blockedNames()) {
				try {
					Class.forName(TermSimGuard.PACKAGE + name);
				} catch (ClassNotFoundException e) {
					throw new AssertionError("blocked packet class no longer exists in this Minecraft version: " + name);
				}
			}
			long before = TermSimGuard.INSTANCE.getBlockedCount();
			require(TermSimGuard.shouldDrop(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(0))
				&& TermSimGuard.INSTANCE.getBlockedCount() == before + 1, "firewall drops a real container close packet and counts it");
			require(!TermSimGuard.shouldDrop(new net.minecraft.network.protocol.game.ServerboundChatAckPacket(0)),
				"firewall passes a real chat ack packet");
		} finally {
			TermSimGuard.closed(screen);
		}
		require(!TermSimGuard.active(), "firewall opens again when the simulator closes");
	}

	/** The screen overrides every path that would send a container packet. */
	private static void checkScreenShape() {
		try {
			Method slotClicked = null;
			for (Method m : TermSimBase.class.getDeclaredMethods()) if (m.getName().equals("slotClicked")) slotClicked = m;
			require(slotClicked != null && Modifier.isFinal(slotClicked.getModifiers()), "slotClicked is overridden and final in the base");
			Method onClose = TermSimScreen.class.getDeclaredMethod("onClose");
			require(onClose.getDeclaringClass() == TermSimScreen.class, "onClose is overridden (no close container packet)");
			for (Method m : TermSimScreen.class.getDeclaredMethods()) {
				require(!m.getName().equals("slotClicked"), "the screen cannot re-override slotClicked back to vanilla");
			}
			int id = TermSimScreen.LOCAL_CONTAINER_ID;
			require(id > 100 && id < 127, "the local menu id is one no server container or the player inventory (0) uses: " + id);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private static void checkCommand() {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("ga");
		TermSimCommand.appendTo(root);
		CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
		dispatcher.register(root);
		for (String line : List.of("ga termsim", "ga termsim 50", "ga termsim panes", "ga termsim melody 100")) {
			ParseResults<FabricClientCommandSource> parsed = dispatcher.parse(line, null);
			require(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty(), "command parses: " + line);
			require(parsed.getContext().getCommand() != null, "command is runnable: " + line);
		}
		require(TermSimCommand.terminalNames().equals(List.of("panes", "rubix", "numbers", "starts_with", "select", "melody")),
			"terminal names: " + TermSimCommand.terminalNames());
		require(SimKind.Companion.parse("STARTS_WITH") == SimKind.STARTS_WITH && SimKind.Companion.parse("nope") == null, "terminal parsing");
		List<String> names = new ArrayList<>();
		root.getArguments().forEach(node -> names.add(node.getName()));
		require(names.contains("termsim"), "the command hangs under /ga");
	}

	/**
	 * Fabric sends a client command line to the server only when the dispatcher reports an unknown command or a parse
	 * error. A bad terminal-simulator line must fail with any other error, which Fabric keeps local.
	 */
	private static void checkCommandFailuresStayLocal() {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("ga");
		TermSimCommand.appendTo(root);
		CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
		dispatcher.register(root);
		for (String line : List.of("ga termsim panes 99999", "ga termsim 50 extra", "ga termsim panes 5 x", "ga termsim panes -1")) {
			try {
				dispatcher.execute(line, null);
				throw new AssertionError("a bad line must not run: " + line);
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
				Object type = e.getType();
				var builtIn = com.mojang.brigadier.exceptions.CommandSyntaxException.BUILT_IN_EXCEPTIONS;
				require(type != builtIn.dispatcherUnknownCommand() && type != builtIn.dispatcherParseException(),
					"a bad simulator line would be forwarded to the server: " + line);
			}
		}
	}

	/**
	 * A module that reads a container title and then sends chat, a command or a container click would act on the
	 * simulator's screen too (the Melody simulator shares the real terminal's title). Every such file must either
	 * exclude [TermSimScreen] or be a known title-keyed module the simulator's titles never match.
	 */
	private static void checkNoOtherModuleReactsToTheSimulator() {
		Set<String> reviewed = Set.of("DungeonOdinAssistModule.kt", "MacroRunner.kt", "TerminalSolverModule.kt", "PartyFinderStatsModule.kt",
			"ContainerKeybindModules.kt");
		List<String> senders = List.of("sendChat(", "sendCommand(", "sendUnattendedCommand(", "handleContainerInput(", "connection.send(");
		Path root = Path.of("src/client/kotlin/geiler/addons/client");
		try (Stream<Path> stream = Files.walk(root)) {
			for (Path file : stream.filter(p -> p.toString().endsWith(".kt")).toList()) {
				String name = file.getFileName().toString();
				if (file.toString().replace('\\', '/').contains("/termsim/")) continue;
				String text = stripComments(Files.readString(file));
				boolean readsScreen = text.contains("AbstractContainerScreen") && (text.contains(".title") || text.contains("isMelodyTitle"));
				boolean sends = senders.stream().anyMatch(text::contains);
				if (readsScreen && sends) {
					require(text.contains("TermSimScreen") || reviewed.contains(name),
						name + " reads a container screen title and sends to the server: exclude TermSimScreen or review it");
				}
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * The command runs before the chat screen closes, so the simulator must be set in one deferred task. A nested
	 * `execute` inside the opener adds a second task and a window where the world is visible.
	 */
	private static void checkOpenPathHasOneHop() {
		try {
			String command = stripComments(Files.readString(Path.of("src/client/kotlin/geiler/addons/client/termsim/TermSimCommand.kt")));
			String service = stripComments(Files.readString(Path.of("src/client/kotlin/geiler/addons/client/termsim/TermSimService.kt")));
			require(command.contains("TermSimService.openFromCommand(kind, ping)"), "the command calls the single-hop opener");
			require(!command.contains("TermSimService.open(kind, ping)"), "the command no longer uses the nested-deferral opener");
			require(service.contains("fun openFromCommand("), "the service has the single-hop opener");
			int start = service.indexOf("private fun showNow(");
			int end = service.indexOf("fun completed(");
			require(start > 0 && end > start, "the service has showNow before completed()");
			require(!service.substring(start, end).contains("execute"), "showNow sets the screen without another deferred task");
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
