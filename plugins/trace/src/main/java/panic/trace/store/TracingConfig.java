package panic.trace.store;

/**
 * {@code dir}: where traces are stored, one JSON lines file per conversation, relative to the app's working
 * directory (null or blank = {@code .panic/traces}). {@code keepDays}: traces older than this are deleted when
 * the plugin starts (null or negative = 30, 0 = keep them all). {@code maxTraces}: the most traces kept per
 * conversation, oldest dropped first (null or non-positive = 500).
 */
public record TracingConfig(String dir, Integer keepDays, Integer maxTraces) {

  public static final TracingConfig DEFAULT = new TracingConfig(null, null, null);

  public TracingConfig {
    dir = dir == null || dir.isBlank() ? ".panic/traces" : dir.strip();
    if (keepDays == null || keepDays < 0) keepDays = 30;
    if (maxTraces == null || maxTraces <= 0) maxTraces = 500;
  }
}
