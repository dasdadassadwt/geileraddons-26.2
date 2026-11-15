package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.utility.AliasStore;
import geiler.addons.client.utility.BuiltInAliases;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks for the built-in f1-f7, m1-m7 and t1-t5 queue aliases. */
public final class AliasBuiltInChecks {
	private AliasBuiltInChecks() { }

	public static void run() throws Exception {
		checkCommands();
		checkStoreRejectsBuiltIns();
		checkModuleSwitch();
	}

	private static void checkCommands() {
		check(BuiltInAliases.names().size() == 19, "there are 19 built-in queue aliases");
		check("joininstance CATACOMBS_FLOOR_ONE".equals(BuiltInAliases.command("f1"))
			&& "joininstance CATACOMBS_FLOOR_SEVEN".equals(BuiltInAliases.command("F7"))
			&& "joininstance MASTER_CATACOMBS_FLOOR_ONE".equals(BuiltInAliases.command("m1"))
			&& "joininstance MASTER_CATACOMBS_FLOOR_SEVEN".equals(BuiltInAliases.command("/m7"))
			&& "joininstance KUUDRA_NORMAL".equals(BuiltInAliases.command(" t1 "))
			&& "joininstance KUUDRA_INFERNAL".equals(BuiltInAliases.command("t5")),
			"each built-in alias maps to its joininstance command, ignoring case, spaces and a leading slash");
		check(BuiltInAliases.command("f8") == null && BuiltInAliases.command("t6") == null
			&& BuiltInAliases.command("m0") == null && BuiltInAliases.command("f1 now") == null
			&& BuiltInAliases.command("") == null && BuiltInAliases.command(null) == null,
			"only a whole built-in name matches");
		check(BuiltInAliases.isBuiltInName("M3") && !BuiltInAliases.isBuiltInName("dh"), "built-in names are recognised");
	}

	private static void checkStoreRejectsBuiltIns() throws Exception {
		Path dir = Files.createTempDirectory("geiler-builtin-aliases");
		AliasStore store = AliasStore.forPathChecks(dir.resolve("aliases.json"));
		check(!store.put("f1", "warp", true) && !store.put("T5", "warp", true) && store.put("dh", "warp dh", true),
			"a saved alias cannot take a built-in name, but other names still save");
		check(store.resolve("f1") == null && store.entries().size() == 1,
			"built-in names never come from the saved list");
	}

	private static void checkModuleSwitch() {
		List<BooleanSetting> switches = AliasesModule.INSTANCE.booleanSettings();
		check(switches.size() == 1 && switches.getFirst().name().equals("Built-in Aliases")
			&& switches.getFirst().rawValue(), "Built-in Aliases is one switch that defaults on");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
