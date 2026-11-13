package panic.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How a provider call is retried after a transient failure (unreachable, 408, 429, 5xx): up to
 * {@code maxRetries} more attempts, waiting an exponential delay with jitter that never exceeds
 * {@code maxDelay}. A {@code Retry-After} from the provider wins when it is longer, still capped.
 */
public record RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay) {

  public static final int DEFAULT_RETRIES = 2;
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(DEFAULT_RETRIES, Duration.ofMillis(500), Duration.ofSeconds(8));
  public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO, Duration.ZERO);

  public RetryPolicy {
    if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
    Objects.requireNonNull(baseDelay, "baseDelay");
    Objects.requireNonNull(maxDelay, "maxDelay");
    if (baseDelay.isNegative() || maxDelay.isNegative()) throw new IllegalArgumentException("negative delay");
  }

  /** The default delays with {@code maxRetries} attempts; null means the default count. */
  public static RetryPolicy of(Integer maxRetries) {
    return maxRetries == null ? DEFAULT : new RetryPolicy(maxRetries, DEFAULT.baseDelay, DEFAULT.maxDelay);
  }

  /**
   * The wait before retry number {@code attempt} (0-based): between half and all of base * 2^attempt, so
   * clients failing together do not retry together.
   */
  public Duration delay(int attempt) {
    long exp = baseDelay.toMillis() << Math.min(attempt, 20);
    long jitter = exp <= 0 ? 0 : ThreadLocalRandom.current().nextLong(exp / 2 + 1);
    return cap(Duration.ofMillis(exp - exp / 2 + jitter));
  }

  /** {@code wanted}, but never longer than {@code maxDelay}. */
  public Duration cap(Duration wanted) {
    return wanted.compareTo(maxDelay) > 0 ? maxDelay : wanted;
  }
}
