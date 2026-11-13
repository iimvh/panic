package panic.trace;

import context.Context;
import core.CancelledException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A span still running. Created by {@link Trace#open}; emitted on {@link Trace#SPAN} by the first call to
 * {@link #end()} or one of the {@code fail} methods, later calls do nothing; {@link #discard()} ends it unseen. Not thread-safe: it belongs to the
 * thread doing the work, which is where the caller sets attributes and ends it.
 */
public final class OpenSpan {

  private final Context ctx;
  private final String traceId;
  private final String spanId;
  private final String parentId;
  private final String name;
  private final long startMicros;
  private final long startNanos;
  private final Map<String, Object> attrs = new LinkedHashMap<>();
  private boolean ended;

  OpenSpan(Context ctx, String traceId, String spanId, String parentId, String name) {
    this.ctx = ctx;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentId = parentId;
    this.name = name;
    this.startNanos = System.nanoTime();
    java.time.Instant now = java.time.Instant.now();
    this.startMicros = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
  }

  public String traceId() {
    return traceId;
  }

  public String id() {
    return spanId;
  }

  /** Microseconds since this span started. */
  public long elapsedMicros() {
    return (System.nanoTime() - startNanos) / 1_000;
  }

  /** Sets an attribute (string, number or boolean; null removes it). Returns this span. */
  public OpenSpan attr(String key, Object value) {
    if (value == null) attrs.remove(key);
    else attrs.put(key, value);
    return this;
  }

  /** A child span of this one, in the same trace. */
  public OpenSpan child(String childName) {
    return Trace.open(ctx, traceId, spanId, childName);
  }

  /** Ends the span as OK. */
  public void end() {
    finish(Span.Status.OK, null);
  }

  /** Ends the span without emitting it: the work turned out not to be worth a span (nobody answered...). */
  public void discard() {
    ended = true;
  }

  /** Ends the span as ERROR with this message (a failure reported as a result, not thrown). */
  public void fail(String message) {
    finish(Span.Status.ERROR, message == null || message.isBlank() ? "failed" : message);
  }

  /** Ends the span as CANCELLED when {@code error} is a cancellation, ERROR otherwise. */
  public void fail(Throwable error) {
    if (error instanceof CancelledException || error instanceof InterruptedException) {
      finish(Span.Status.CANCELLED, error.getMessage() == null ? "cancelled" : error.getMessage());
    } else {
      String msg = error.getMessage();
      finish(Span.Status.ERROR, msg == null || msg.isBlank() ? error.getClass().getSimpleName() : msg);
    }
  }

  private void finish(Span.Status status, String error) {
    if (ended) return;
    ended = true;
    Span span;
    try {
      span = new Span(traceId, spanId, parentId, name, startMicros, Math.max(0, elapsedMicros()), status, error,
          attrs);
    } catch (IllegalArgumentException e) {
      // A bad attribute must not break the measured work: drop the attributes, keep the timing.
      span = new Span(traceId, spanId, parentId, name, startMicros, Math.max(0, elapsedMicros()), status,
          error == null ? e.getMessage() : error, Map.of());
    }
    Trace.emit(ctx, span);
  }
}
