package com.panic.core.redis;

import java.time.Duration;
import java.util.Optional;

/**
 * The only Redis surface the workspace knows: string keys with a TTL. Consumers (conversation
 * window cache, chat rate limit, per-conversation lock) stay testable through an in-memory fake
 * and swappable between the Upstash REST adapter and a classic TCP client.
 * <p>
 * Implementations may be absent (returns "no Redis configured") and may fail (network down):
 * consumers must treat both as "Redis is not there right now" and keep working without it.
 */
public interface StringRedisOps {

	/** Value of {@code key}, empty when the key does not exist. */
	Optional<String> get(String key);

	/** Writes {@code key} with the given time-to-live (refreshes an existing entry's TTL). */
	void set(String key, String value, Duration ttl);

	/**
	 * Writes {@code key} only when absent, with the given time-to-live. Returns {@code true} when
	 * this call created the entry (lock acquired), {@code false} when somebody already holds it.
	 */
	boolean setIfAbsent(String key, String value, Duration ttl);

	/**
	 * Counts hits inside a fixed window: increments {@code key} and arms the window's TTL the
	 * first time the window opens (or when a previous arm attempt was lost). Returns the current
	 * counter value; callers decide what "over the limit" means.
	 */
	long incrementWindow(String key, Duration window);

	/** Removes {@code key}; missing keys are not an error. */
	void delete(String key);
}
