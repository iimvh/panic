package panic.trace;

import context.Context;
import context.EventKey;
import context.HookKey;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tracing contract. Plugins that do measurable work (chat turns, LLM calls, tool runs) emit finished
 * {@link Span}s on {@link #SPAN}; a tracing plugin, when installed, stores them and answers {@link #GET} and
 * {@link #LIST}. Nobody requires the tracer: with no listener, emitting costs a lookup and nothing is kept.
 */
public final class Trace {

  /** Every finished span. Listeners run on the worker's thread, so they must be quick and never block. */
  public static final EventKey<Span> SPAN = EventKey.of("trace.span", Span.class);

  /** The spans of one trace, in the order they started. Empty answer = unknown trace or no tracer. */
  public static final HookKey<Query, Spans> GET = HookKey.of("trace.get", Query.class, Spans.class);

  /** The root spans of a conversation's latest traces, newest first. */
  public static final HookKey<ListQuery, Roots> LIST = HookKey.of("trace.list", ListQuery.class, Roots.class);

  /** Root attribute: the conversation a turn belongs to. */
  public static final String CONVERSATION = "conversationId";

  /** Root attribute: {@link #questionHash} of the question a turn answered. */
  public static final String QUESTION_HASH = "questionHash";

  /**
   * Root attribute: whether the turn's question was saved in its conversation ({@code true}), so the trace can be
   * linked to it in the history. A turn that fails or is cancelled keeps its question too.
   */
  public static final String SAVED = "saved";

  /**
   * A short, stable digest of a question, kept on the root span of each turn's trace ({@link #QUESTION_HASH}), so
   * a front-end can find the trace of a stored question without the trace keeping (or matching on) its text.
   */
  public static String questionHash(String question) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(question.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest, 0, 8);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /** One trace of a conversation; {@code traceId} may be a unique prefix. */
  public record Query(String conversationId, String traceId) {
    public Query {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(traceId, "traceId");
    }
  }

  /** The {@code limit} latest traces of a conversation ({@code <= 0} = all). */
  public record ListQuery(String conversationId, int limit) {
    public ListQuery {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  public record Spans(List<Span> spans) {
    public Spans {
      spans = List.copyOf(spans);
    }
  }

  public record Roots(List<Span> roots) {
    public Roots {
      roots = List.copyOf(roots);
    }
  }

  private Trace() {
  }

  /** A new random id (16 hex characters). */
  public static String newId() {
    byte[] b = new byte[8];
    ThreadLocalRandom.current().nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  /** Starts a span. {@code parentId} null starts the root of trace {@code traceId}. */
  public static OpenSpan open(Context ctx, String traceId, String parentId, String name) {
    Objects.requireNonNull(ctx, "ctx");
    Objects.requireNonNull(name, "name");
    return new OpenSpan(ctx, traceId == null || traceId.isBlank() ? newId() : traceId, newId(), parentId, name);
  }

  /**
   * Emits a finished span. Telemetry must never break the work it measures, so listener failures (and a
   * context already disposed) are swallowed here.
   */
  public static void emit(Context ctx, Span span) {
    try {
      ctx.emit(SPAN, span);
    } catch (RuntimeException ignored) {
      // The tracer is responsible for its own errors; the traced work goes on.
    }
  }
}
