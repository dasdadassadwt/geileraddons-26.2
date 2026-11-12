package geiler.addons.client.dungeon;

import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Java ABI and parsing regression checks for the Kotlin-backed dungeon class enum. */
public final class DungeonClassChecks {
	private DungeonClassChecks() { }

	public static void run() {
		check(Arrays.equals(DungeonClass.values(), new DungeonClass[] {
			DungeonClass.TANK, DungeonClass.HEALER, DungeonClass.MAGE, DungeonClass.BERSERK, DungeonClass.ARCHER,
		}), "dungeon class names and iteration order remain stable");
		check("Tank".equals(DungeonClass.TANK.displayName())
			&& "Berserk".equals(DungeonClass.BERSERK.displayName()), "display labels remain stable");
		check(DungeonClass.parse("tank") == DungeonClass.TANK
			&& DungeonClass.parse(" Mage ") == DungeonClass.MAGE
			&& DungeonClass.parse("ARCHER") == DungeonClass.ARCHER
			&& DungeonClass.parse("\tHealer\u0000") == DungeonClass.HEALER,
			"parsing remains case-insensitive and trims the same ASCII control range");
		check(DungeonClass.parse(null) == null && DungeonClass.parse("berserker") == null,
			"null and unrecognized class values remain unknown");
		try {
			var displayName = DungeonClass.class.getMethod("displayName");
			var parse = DungeonClass.class.getMethod("parse", String.class);
			check(displayName.getReturnType() == String.class && Modifier.isPublic(displayName.getModifiers())
				&& !Modifier.isStatic(displayName.getModifiers()), "displayName remains a public Java instance method");
			check(parse.getReturnType() == DungeonClass.class && Modifier.isPublic(parse.getModifiers())
				&& Modifier.isStatic(parse.getModifiers()), "parse remains a public static Java method");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("DungeonClass Java ABI changed", exception);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
