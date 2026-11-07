package com.panic.core.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a {@link StringRedisOps} delegate so an unreachable Redis never takes a chat task down:
 * the first failure logs one warning, every later call short-circuits with a fail-open answer
 * (cache miss, quota allowed, lock granted), and the first call after the cooldown acts as a
 * probe — when the probe succeeds the accelerator quietly comes back.
 * <p>
 * The workspace logs one warning <em>per outage</em>: after a successful probe a new failure
 * warns again, so operators see recoveries in the log without per-call noise.
 */
public class FailOpenRedisOps implements StringRedisOps, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(FailOpenRedisOps.class);

	private final StringRedisOps delegate;
	private final Clock clock;
	/** Nanotime stamp until which calls are short-circuited; 0 = healthy. */
	private final AtomicLong skipUntil = new AtomicLong();
	private volatile boolean warned;

	public FailOpenRedisOps(StringRedisOps delegate) {
		this(delegate, System::nanoTime, Duration.ofSeconds(30));
	}

	/** Full constructor: injectable clock + cooldown for tests. */
	public FailOpenRedisOps(StringRedisOps delegate, java.util.function.LongSupplier clock, Duration cooldown) {
		this.delegate = delegate;
		this.clock = new Clock(clock, cooldown.toNanos());
	}

	@Override
	public Optional<String> get(String key) {
		return call(() -> delegate.get(key), Optional.empty());
	}

	@Override
	public void set(String key, String value, Duration ttl) {
		call(() -> {
			delegate.set(key, value, ttl);
			return null;
		}, null);
	}

	@Override
	public boolean setIfAbsent(String key, String value, Duration ttl) {
		return call(() -> delegate.setIfAbsent(key, value, ttl), true);
	}

	@Override
	public long incrementWindow(String key, Duration window) {
		return call(() -> delegate.incrementWindow(key, window), 0L);
	}

	@Override
	public void delete(String key) {
		call(() -> {
			delegate.delete(key);
			return null;
		}, null);
	}

	@Override
	public void close() {
		if (delegate instanceof AutoCloseable closeable) {
			try {
				closeable.close();
			} catch (Exception ex) {
				log.debug("Redis adapter shutdown failed: {}", ex.getMessage());
			}
		}
	}

	private <T> T call(java.util.function.Supplier<T> operation, T failOpenAnswer) {
		if (clock.withinCooldown()) {
			return failOpenAnswer;
		}
		try {
			T result = operation.get();
			if (clock.markHealthy()) {
				warned = false;
				log.info("Redis is reachable again — caching, rate limit and conversation lock re-enabled");
			}
			return result;
		} catch (RuntimeException ex) {
			clock.markFailed();
			if (!warned) {
				warned = true;
				log.warn("Redis is unreachable ({}); continuing without it — the recent-turn cache, chat "
						+ "rate limit and per-conversation lock are disabled until it comes back",
						ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
			}
			return failOpenAnswer;
		}
	}

	/** Nano-clock cooldown: decides whether a call runs, short-circuits, or probes after the pause. */
	private static final class Clock {

		private final java.util.function.LongSupplier nano;
		private final long cooldownNanos;
		private volatile long skipUntil;

		Clock(java.util.function.LongSupplier nano, long cooldownNanos) {
			this.nano = nano;
			this.cooldownNanos = cooldownNanos;
		}

		synchronized boolean withinCooldown() {
			return skipUntil != 0 && nano.getAsLong() < skipUntil;
		}

		/** Records a healthy call; returns true when this call ended an outage. */
		synchronized boolean markHealthy() {
			if (skipUntil != 0) {
				skipUntil = 0;
				return true;
			}
			return false;
		}

		synchronized void markFailed() {
			skipUntil = nano.getAsLong() + cooldownNanos;
		}
	}
}
