package geiler.addons.client.dungeon.helpers;

import java.util.List;

/** Java record-compatible API backed by the Kotlin roster implementation. */
public final class SpiritLeapRoster {
	private final SpiritLeapRosterLogic logic = new SpiritLeapRosterLogic();

	/** Caches immutable quadrant cards while roster contents and ordering settings remain unchanged. */
	public static final class CardsCache {
		private final SpiritLeapRosterCardsCacheLogic logic = new SpiritLeapRosterCardsCacheLogic();

		public List<Card> cards(SpiritLeapRoster roster, Sort sort, String customOrder) {
			return logic.cards(roster, sort, customOrder);
		}

		public void clear() {
			logic.clear();
		}
	}

	public void update(List<Member> members, String localPlayerName) {
		logic.update(members, localPlayerName);
	}

	public List<Card> cards(Sort sort, List<String> customOrder) {
		return logic.cards(sort, customOrder);
	}

	public List<Member> observed() { return logic.observed(); }
	public boolean hasObservedTeammates() { return logic.hasObservedTeammates(); }
	public void clear() { logic.clear(); }

	/** Resolves only validated leap-menu heads, enriching them with matching live roster data. */
	public static List<Member> resolveMenuHeads(List<SpiritLeapSelection.MenuHead> menuHeads,
		List<Member> knownMembers, String localPlayerName) {
		return SpiritLeapRosterLogic.resolveMenuHeads(menuHeads, knownMembers, localPlayerName);
	}

	/** Odin 0.3.6 first reserves each class's default corner by priority, then fills holes in order. */
	public static List<Card> odinCards(List<Member> members) {
		return SpiritLeapRosterLogic.odinCards(members);
	}

	/** Minecraft legacy color code used by Odin's player-class card style. */
	public static char classColorCode(String dungeonClass) {
		return SpiritLeapRosterLogic.classColorCode(dungeonClass);
	}

	/** Packed ARGB palette used by Odin's leap menu and shared party highlights. */
	public static int classColorArgb(String dungeonClass) {
		return SpiritLeapRosterLogic.classColorArgb(dungeonClass);
	}

	public enum Sort { ODIN, CLASS, NAME, HEALTH, CUSTOM }

	public record Member(String name, String dungeonClass, int healthPercent, boolean online, boolean dead) {
		private static final Member EMPTY = new Member("", "", 0, false, false);

		public Member(String name, String dungeonClass, int healthPercent, boolean online) {
			this(name, dungeonClass, healthPercent, online, false);
		}

		public Member {
			name = name == null ? "" : name.trim();
			dungeonClass = dungeonClass == null ? "" : dungeonClass.trim();
			healthPercent = Math.max(0, Math.min(100, healthPercent));
		}

		public boolean empty() { return name.isBlank(); }
	}

	public record Card(int quadrant, Member member) { }
}
