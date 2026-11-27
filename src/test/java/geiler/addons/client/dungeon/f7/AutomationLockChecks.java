package geiler.addons.client.dungeon.f7;

/** Offline checks for the shared dungeon automation lock. */
public final class AutomationLockChecks {
	private AutomationLockChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		AutomationLock.clear();
		require(AutomationLock.holder(0) == null, "a fresh lock is free");
		require(!AutomationLock.blocks("ss", 0), "a free lock blocks nobody");
		require(AutomationLock.acquire("mask", 100), "a free lock can be taken");
		require(AutomationLock.blocks("ss", 200), "the holder blocks other features");
		require(!AutomationLock.blocks("mask", 200), "the holder is not blocked by itself");
		require(!AutomationLock.acquire("ss", 300), "a held lock cannot be taken by another feature");
		require(AutomationLock.acquire("mask", 400), "the holder can renew the lock");
		AutomationLock.release("ss");
		require("mask".equals(AutomationLock.holder(500)), "only the holder can release the lock");
		AutomationLock.release("mask");
		require(AutomationLock.holder(500) == null, "release frees the lock");

		require(AutomationLock.acquire("mask", 1_000), "taken again");
		require(AutomationLock.blocks("ss", 1_000 + AutomationLock.TIMEOUT_MS - 1), "still held just before the timeout");
		require(!AutomationLock.blocks("ss", 1_000 + AutomationLock.TIMEOUT_MS), "a stuck holder times out");
		require(AutomationLock.acquire("ss", 1_000 + AutomationLock.TIMEOUT_MS), "another feature can take a timed-out lock");
		AutomationLock.clear();
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
