package com.panic.core.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link StringRedisOps} for tests: real TTL semantics (including expiry on read
 * and NX-acquire) against a settable clock, so quota/lock/window behaviour can be exercised
 * without any Redis — and without sleeping.
 */
public class InMemoryStringRedisOps implements StringRedisOps {

	/** Stored value with its expiry instant (0 = no expiry); visible for precise assertions. */
	public record Entry(String value, long expiresAtMillis) {
	}

	private final Map<String, Entry> values = new HashMap<>();
	private long nowMillis = System.currentTimeMillis();

	/** Test clock: moves time forward for TTL/window tests. */
	public void advance(Duration by) {
		nowMillis += by.toMillis();
	}

	public Entry peek(String key) {
		return values.get(key);
	}

	@Override
	public Optional<String> get(String key) {
		Entry entry = live(key);
		return entry == null ? Optional.empty() : Optional.of(entry.value());
	}

	@Override
	public void set(String key, String value, Duration ttl) {
		values.put(key, new Entry(value, nowMillis + ttl.toMillis()));
	}

	@Override
	public boolean setIfAbsent(String key, String value, Duration ttl) {
		if (live(key) != null) {
			return false;
		}
		set(key, value, ttl);
		return true;
	}

	@Override
	public long incrementWindow(String key, Duration window) {
		Entry entry = live(key);
		long hits = entry == null ? 0 : Long.parseLong(entry.value());
		hits++;
		long expiresAt = hits == 1 || entry == null || entry.expiresAtMillis() == 0
				? nowMillis + window.toMillis()
				: entry.expiresAtMillis();
		values.put(key, new Entry(String.valueOf(hits), expiresAt));
		return hits;
	}

	@Override
	public void delete(String key) {
		values.remove(key);
	}

	private Entry live(String key) {
		Entry entry = values.get(key);
		if (entry == null) {
			return null;
		}
		if (entry.expiresAtMillis() != 0 && entry.expiresAtMillis() <= nowMillis) {
			values.remove(key);
			return null;
		}
		return entry;
	}
}
