package panic.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One finished, timed piece of work inside a trace: a chat turn, an LLM call, a tool run. Spans are emitted
 * once, when they end, on {@link Trace#SPAN}; a span's children end (and are emitted) before it.
 *
 * <p>{@code parentId} is null for the root of a trace. Times are microseconds: {@code startMicros} since the
 * epoch, {@code durationMicros} measured with a monotonic clock. {@code error} is set for ERROR and CANCELLED.
 * Attribute values are restricted to strings, numbers and booleans, so any store can write them as JSON.
 */
public record Span(String traceId, String spanId, String parentId, String name, long startMicros,
    long durationMicros, Status status, String error, Map<String, Object> attrs) {

  /** How the work ended. */
  public enum Status { OK, ERROR, CANCELLED }

  public Span {
    requireText(traceId, "traceId");
    requireText(spanId, "spanId");
    requireText(name, "name");
    if (parentId != null && parentId.isBlank()) parentId = null;
    Objects.requireNonNull(status, "status");
    if (durationMicros < 0) throw new IllegalArgumentException("durationMicros must not be negative");
    Map<String, Object> copy = new LinkedHashMap<>();
    if (attrs != null) {
      attrs.forEach((k, v) -> {
        requireText(k, "attribute name");
        if (v == null) return;
        if (!(v instanceof String || v instanceof Long || v instanceof Integer || v instanceof Double
            || v instanceof Boolean)) {
          throw new IllegalArgumentException("attribute " + k + " must be a string, number or boolean, not "
              + v.getClass().getSimpleName());
        }
        copy.put(k, v instanceof Integer i ? Long.valueOf(i) : v);
      });
    }
    attrs = java.util.Collections.unmodifiableMap(copy);
  }

  /** True for the root span of its trace. */
  public boolean root() {
    return parentId == null;
  }

  /** The attribute as a long, or {@code fallback} when absent or not a number. */
  public long longAttr(String key, long fallback) {
    return attrs.get(key) instanceof Number n ? n.longValue() : fallback;
  }

  /** The attribute as a string, or null when absent. */
  public String stringAttr(String key) {
    Object v = attrs.get(key);
    return v == null ? null : v.toString();
  }

  private static void requireText(String s, String what) {
    Objects.requireNonNull(s, what);
    if (s.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
  }
}
