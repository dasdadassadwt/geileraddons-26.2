package geiler.addons.client.party;

import java.util.LinkedHashMap;
import java.util.Map;

/** Focused offline checks for party-state lifetime across world and connection transitions. */
public final class PartySessionLifecycleChecks {
	private PartySessionLifecycleChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkSameConnectionWorldReplacementPreservesSession();
		checkDisconnectResetsSession();
		checkServerConnectionReplacementResetsSession();
		checkRosterDeltasDoNotEndSession();
		checkConfirmedNewPartyResetsSession();
	}

	private static void checkSameConnectionWorldReplacementPreservesSession() {
		SessionFixture session = new SessionFixture();
		Object connection = new Object();
		session.tick(connection, new Object());
		session.roster.put("leader", "Leader");
		session.roster.put("member", "Member");
		session.stats.put("leader", "leader stats");
		session.stats.put("member", "member stats");
		long generation = session.generation;

		// A fresh ClientLevel represents respawn/dimension replacement, while the packet
		// listener remains the same. PartyListBackend only observes the latter for resets.
		session.tick(connection, new Object());
		check(session.roster.size() == 2 && session.stats.size() == 2
			&& session.generation == generation,
			"same-party level replacement preserves roster, stats, and generation");
	}

	private static void checkDisconnectResetsSession() {
		SessionFixture session = populatedSession();
		long generation = session.generation;
		session.tick(null, null);
		check(session.roster.isEmpty() && session.stats.isEmpty()
			&& session.generation == generation + 1,
			"disconnect clears party data and advances the session generation");
		session.tick(new Object(), new Object());
		check(session.generation == generation + 1,
			"connecting after a disconnect does not cause a second stale-session reset");
	}

	private static void checkServerConnectionReplacementResetsSession() {
		SessionFixture session = populatedSession();
		long generation = session.generation;
		session.tick(new Object(), new Object());
		check(session.roster.isEmpty() && session.stats.isEmpty()
			&& session.generation == generation + 1,
			"replacing an established server connection clears prior party data");
	}

	private static void checkRosterDeltasDoNotEndSession() {
		SessionFixture session = populatedSession();
		long generation = session.generation;
		session.roster.put("joiner", "Joiner");
		session.stats.put("joiner", "joiner stats");
		session.roster.remove("member");
		session.stats.remove("member");
		check(session.roster.containsKey("leader") && session.roster.containsKey("joiner")
			&& session.generation == generation,
			"incremental joins/leaves update membership without invalidating the party session");
	}

	private static void checkConfirmedNewPartyResetsSession() {
		SessionFixture session = populatedSession();
		long generation = session.generation;
		if (PartySessionLifecycle.shouldResetForConfirmedSelfJoin(true)) session.clearParty();
		check(session.roster.isEmpty() && session.stats.isEmpty()
			&& session.generation == generation + 1,
			"a confirmed self-join while tracking a party starts a fresh session");
		check(!PartySessionLifecycle.shouldResetForConfirmedSelfJoin(false),
			"a first party join does not add an unnecessary reset before session data exists");
	}

	private static SessionFixture populatedSession() {
		SessionFixture session = new SessionFixture();
		session.tick(new Object(), new Object());
		session.roster.put("leader", "Leader");
		session.roster.put("member", "Member");
		session.stats.put("leader", "leader stats");
		session.stats.put("member", "member stats");
		return session;
	}

	private static final class SessionFixture {
		private final PartySessionLifecycle lifecycle = new PartySessionLifecycle();
		private final Map<String, String> roster = new LinkedHashMap<>();
		private final Map<String, String> stats = new LinkedHashMap<>();
		private long generation;

		private void tick(Object connection, Object ignoredLevel) {
			if (!lifecycle.updateConnection(connection)) return;
			clearParty();
		}

		private void clearParty() {
			roster.clear();
			stats.clear();
			generation++;
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
