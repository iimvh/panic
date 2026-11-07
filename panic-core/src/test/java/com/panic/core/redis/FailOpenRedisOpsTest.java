package com.panic.core.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The fail-open guard promises one warning per outage, silent skipping while it is down, and a
 * probe after the cooldown — never a broken chat. Observable through the port's answers.
 */
class FailOpenRedisOpsTest {

	/** Delegate that can be switched on/off and counts the calls that reached it. */
	static final class SwitchableRedisOps implements StringRedisOps {

		private final AtomicInteger calls = new AtomicInteger();
		private volatile boolean down;

		SwitchableRedisOps down(boolean down) {
			this.down = down;
			return this;
		}

		int attempts() {
			return calls.get();
		}

		@Override
		public Optional<String> get(String key) {
			calls.incrementAndGet();
			if (down) {
				throw new IllegalStateException("connection refused");
			}
			return Optional.of("value:" + key);
		}

		@Override
		public void set(String key, String value, Duration ttl) {
			calls.incrementAndGet();
			if (down) {
				throw new IllegalStateException("connection refused");
			}
		}

		@Override
		public boolean setIfAbsent(String key, String value, Duration ttl) {
			calls.incrementAndGet();
			if (down) {
				throw new IllegalStateException("connection refused");
			}
			return true;
		}

		@Override
		public long incrementWindow(String key, Duration window) {
			calls.incrementAndGet();
			if (down) {
				throw new IllegalStateException("connection refused");
			}
			return 1;
		}

		@Override
		public void delete(String key) {
			calls.incrementAndGet();
			if (down) {
				throw new IllegalStateException("connection refused");
			}
		}
	}

	@Test
	void outageAnswersFailOpenAndSkipsFurtherCalls() {
		SwitchableRedisOps delegate = new SwitchableRedisOps().down(true);
		AtomicLong nanos = new AtomicLong();
		FailOpenRedisOps ops = new FailOpenRedisOps(delegate, nanos::get, Duration.ofSeconds(30));

		assertThat(ops.get("k")).isEmpty();
		assertThat(ops.setIfAbsent("k", "v", Duration.ofSeconds(5))).isTrue();
		assertThat(ops.incrementWindow("k", Duration.ofMinutes(1))).isZero();
		ops.set("k", "v", Duration.ofSeconds(5));
		ops.delete("k");

		int afterOneFailure = delegate.attempts();
		assertThat(afterOneFailure).isOne();

		// While degraded everything still answers fail-open, without touching the delegate.
		assertThat(ops.get("k")).isEmpty();
		assertThat(ops.setIfAbsent("k", "v", Duration.ofSeconds(5))).isTrue();
		assertThat(delegate.attempts()).isEqualTo(afterOneFailure);
	}

	@Test
	void firstCallAfterCooldownProbesAndRecovers() {
		SwitchableRedisOps delegate = new SwitchableRedisOps().down(true);
		AtomicLong nanos = new AtomicLong();
		FailOpenRedisOps ops = new FailOpenRedisOps(delegate, nanos::get, Duration.ofSeconds(30));

		assertThat(ops.get("k")).isEmpty();
		nanos.addAndGet(Duration.ofSeconds(15).toNanos());
		assertThat(ops.get("k")).isEmpty();
		assertThat(delegate.attempts()).isOne();

		// 30s after the failure the next call is the probe — it fails, and a new cooldown arms.
		nanos.addAndGet(Duration.ofSeconds(16).toNanos());
		assertThat(ops.get("k")).isEmpty();
		assertThat(delegate.attempts()).isEqualTo(2);

		// Back up before the second cooldown ends: skipped.
		assertThat(ops.get("k")).isEmpty();
		assertThat(delegate.attempts()).isEqualTo(2);

		// Redis heals; the probe succeeds and the accelerator silently returns.
		nanos.addAndGet(Duration.ofSeconds(31).toNanos());
		delegate.down(false);
		assertThat(ops.get("k")).contains("value:k");
		assertThat(ops.incrementWindow("k", Duration.ofMinutes(1))).isEqualTo(1);
		assertThat(delegate.attempts()).isEqualTo(4);

		// And a later outage fails open again the same way.
		delegate.down(true);
		assertThat(ops.setIfAbsent("k", "v", Duration.ofSeconds(5))).isTrue();
		assertThat(delegate.attempts()).isEqualTo(5);
	}
}
