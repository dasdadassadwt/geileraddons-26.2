package geiler.addons.client.rename;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Offline checks for Any Rename: markup parsing, gradients, store precedence, persistence and share strings. */
public final class RenameChecks {
	private RenameChecks() { }

	public static void main(String[] args) throws Exception {
		run();
	}

	public static void run() throws Exception {
		checkText();
		checkStore();
		checkPersistence();
		checkShare();
	}

	private static void checkText() {
		List<RenameText.Seg> plain = RenameText.segments("&6Hello &lWorld", "", false, false, false, false, false);
		check(plain.size() == 2, "colour change splits runs: " + plain.size());
		check(plain.get(0).getText().equals("Hello ") && plain.get(0).getRgb() == 0xFFAA00, "gold run");
		check(plain.get(1).getText().equals("World") && plain.get(1).getBold() && plain.get(1).getRgb() == 0xFFAA00,
			"bold keeps colour");

		List<RenameText.Seg> hex = RenameText.segments("&#12abEFHi", "", false, false, false, false, false);
		check(hex.size() == 1 && hex.get(0).getRgb() == 0x12ABEF, "hex colour");

		List<RenameText.Seg> reset = RenameText.segments("&c&lA&rB", "", false, false, false, false, false);
		check(reset.size() == 2 && !reset.get(1).getBold() && reset.get(1).getRgb() == -1, "reset clears style");

		List<RenameText.Seg> grad = RenameText.segments("abc", "FF0000,0000FF", false, false, false, false, false);
		check(grad.size() == 3, "gradient is one run per character");
		check(grad.get(0).getRgb() == 0xFF0000 && grad.get(2).getRgb() == 0x0000FF, "gradient ends hit the stops");
		check(grad.get(1).getRgb() == 0x7F007F || grad.get(1).getRgb() == 0x800080, "gradient midpoint: "
			+ Integer.toHexString(grad.get(1).getRgb()));

		List<RenameText.Seg> forced = RenameText.segments("x", "", true, true, true, true, true);
		check(forced.get(0).getBold() && forced.get(0).getItalic() && forced.get(0).getUnderline()
			&& forced.get(0).getStrike() && forced.get(0).getObfuscated(), "override flags apply to the whole name");

		List<RenameText.Seg> rainbow = RenameText.segments("abcdef", "rainbow", false, false, false, false, false);
		check(rainbow.size() == 6 && rainbow.get(0).getRgb() != rainbow.get(3).getRgb(), "rainbow keyword");

		check(RenameText.segments("", "FF0000", false, false, false, false, false).isEmpty(), "empty name has no runs");
		check(RenameText.plain("&6A&lB&#112233C").equals("ABC"), "plain strips markup");
		check(RenameText.parseHex("zz") == -1 && RenameText.parseHex("#ff0000") == 0xFF0000, "hex parser");
	}

	private static void checkStore() {
		RenameStore store = new RenameStore();
		check(!store.getAny(), "empty store");
		RenameOverride unique = new RenameOverride();
		unique.setName("&aMine");
		RenameOverride type = new RenameOverride();
		type.setName("&cAll");
		type.setAllOfType(true);
		store.put("uuid-1", "HYPERION", unique);
		store.put("uuid-2", "HYPERION", type);
		check(store.getAny(), "store has entries");
		check(store.lookup("uuid-1", "HYPERION") == unique, "uuid wins over the type rule");
		check(store.lookup("uuid-9", "HYPERION") == type, "other items of the type use the type rule");
		check(store.lookup("", "HYPERION") == type, "items without a uuid use the type rule");
		check(store.lookup("uuid-9", "OTHER") == null, "unrelated item untouched");
		check(store.lookup("", "") == null, "no id and no uuid is never overridden");
		store.remove("uuid-1", "HYPERION", false);
		check(store.lookup("uuid-1", "HYPERION") == type, "removal falls back to the type rule");
		store.remove("uuid-2", "HYPERION", true);
		check(!store.getAny() && store.size() == 0, "removing the last entry clears the flag");

		RenameStore capped = new RenameStore();
		for (int i = 0; i < RenameStore.MAX_ENTRIES + 50; i++) {
			RenameOverride o = new RenameOverride();
			o.setName("n" + i);
			capped.put("u" + i, "ID", o);
		}
		check(capped.size() <= RenameStore.MAX_ENTRIES, "store is bounded: " + capped.size());
		check(capped.lookup("u" + (RenameStore.MAX_ENTRIES + 49), "ID") != null, "newest survives the cap");
		check(capped.lookup("u0", "ID") == null, "oldest is evicted at the cap");

		RenameOverride empty = new RenameOverride();
		check(empty.isEmpty(), "default override is empty");
		RenameStore blank = new RenameStore();
		blank.put("u", "ID", empty);
		check(!blank.getAny(), "an empty override is not stored");
	}

	private static void checkPersistence() throws Exception {
		Path file = Files.createTempFile("any-rename", ".json");
		try {
			RenameStore store = new RenameStore();
			RenameOverride o = new RenameOverride();
			o.setName("&6Gold");
			o.setGradient("FF0000,0000FF");
			o.setBold(true);
			o.setIcon("minecraft:diamond_sword");
			o.setLore("line one|line two");
			o.setLoreMode("Append");
			o.setRarityLine("&dMYTHIC");
			o.setHide("Reforge");
			o.setGlint("On");
			o.setDye(0x112233);
			store.put("u1", "ID", o);
			RenameOverride t = new RenameOverride();
			t.setName("Type");
			t.setAllOfType(true);
			store.put("u2", "TYPE_ID", t);
			store.save(file);

			RenameStore loaded = new RenameStore();
			loaded.load(file);
			RenameOverride back = loaded.lookup("u1", "ID");
			check(back != null && back.getName().equals("&6Gold") && back.getGradient().equals("FF0000,0000FF")
				&& back.getBold() && back.getIcon().equals("minecraft:diamond_sword")
				&& back.getLore().equals("line one|line two") && back.getLoreMode().equals("Append")
				&& back.getRarityLine().equals("&dMYTHIC") && back.getHide().equals("Reforge")
				&& back.getGlint().equals("On") && back.getDye() == 0x112233, "override round trips");
			check(loaded.lookup("other", "TYPE_ID") != null, "type rule round trips");

			Files.writeString(file, "{ not json");
			RenameStore broken = new RenameStore();
			broken.load(file);
			check(!broken.getAny(), "corrupt file loads as empty instead of throwing");
			Files.delete(file);
			broken.load(file);
			check(!broken.getAny(), "missing file loads as empty");
		} finally {
			Files.deleteIfExists(file);
		}
	}

	private static void checkShare() {
		RenameOverride o = new RenameOverride();
		o.setName("&bShared");
		o.setIcon("head:Notch");
		String text = RenameStore.export(o);
		check(text.startsWith("GAR1:"), "share prefix");
		RenameOverride back = RenameStore.importOverride(text);
		check(back != null && back.getName().equals("&bShared") && back.getIcon().equals("head:Notch"), "share round trip");
		check(RenameStore.importOverride("garbage") == null, "bad prefix rejected");
		check(RenameStore.importOverride("GAR1:%%%") == null, "bad base64 rejected");
		check(RenameStore.importOverride("GAR1:" + java.util.Base64.getEncoder().encodeToString("[1,2]".getBytes()))
			== null, "wrong json shape rejected");
		check(RenameStore.importOverride("GAR1:" + "A".repeat(RenameStore.MAX_SHARE_CHARS + 1)) == null,
			"oversized share string rejected");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError("RenameChecks: " + message);
	}
}
