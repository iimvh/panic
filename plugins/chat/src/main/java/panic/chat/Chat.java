package panic.chat;

import context.Context;
import context.EventKey;
import context.HookKey;
import core.Disposable;
import core.Signal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import panic.command.Hooks;
import panic.llm.Message;
import panic.llm.ToolCall;
import panic.llm.ToolCallDelta;
import panic.llm.Usage;
import panic.tools.ToolResult;

/**
 * How to talk to the chat bot. Callers do not depend on a service: they ask the {@link #ASK} hook, which
 * the chat plugin answers only while an LLM is connected. So a front-end (the REPL today, a UI later) keeps
 * running while the model behind it is switched, and simply gets no answer when there is none.
 *
 * One ask may take several model calls: the bot calls tools and reads their results before it answers.
 * {@link Step}s report that progress.
 */
public final class Chat {

  /**
   * One user message. {@code signal} cancels the reply; {@code onDelta} receives its text as it streams;
   * {@code onStep} hears each tool call when it starts and when it finishes; {@code onReview} hears every
   * verdict on a final answer (passed or not), before a rejected answer is attempted again;
   * {@code onToolDelta} hears the pieces of a tool call while the model writes it (before {@code onStep}
   * reports it running; nothing when the model cannot stream calls). {@code traceId} names the turn's trace
   * (see {@link panic.trace.Trace}); null lets chat pick one, which {@link Reply#traceId} returns. The
   * callbacks are called in the order
   * things happen, on the turn's thread.
   */
  public record Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
      Consumer<Step> onStep, Consumer<Reviewed> onReview, Consumer<ToolCallDelta> onToolDelta, String traceId) {
    public Ask {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(signal, "signal");
      Objects.requireNonNull(onDelta, "onDelta");
      if (onStep == null) onStep = s -> { };
      if (onReview == null) onReview = r -> { };
      if (onToolDelta == null) onToolDelta = d -> { };
      if (traceId != null && traceId.isBlank()) traceId = null;
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
        Consumer<Step> onStep, Consumer<Reviewed> onReview, Consumer<ToolCallDelta> onToolDelta) {
      this(conversationId, text, signal, onDelta, onStep, onReview, onToolDelta, null);
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
        Consumer<Step> onStep, Consumer<Reviewed> onReview) {
      this(conversationId, text, signal, onDelta, onStep, onReview, null, null);
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
        Consumer<Step> onStep) {
      this(conversationId, text, signal, onDelta, onStep, null, null, null);
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta) {
      this(conversationId, text, signal, onDelta, null, null, null, null);
    }
  }

  /** A tool call of the bot: {@code result} is null when it starts, set when it is done. */
  public record Step(String conversationId, ToolCall call, ToolResult result) {
    public boolean done() {
      return result != null;
    }
  }

  /** Why a turn ended. Anything but {@link #DONE} means the bot was stopped before it finished. */
  public static final String DONE = "done";
  public static final String MAX_STEPS = "max_steps";
  public static final String BUDGET = "budget";
  public static final String LOOP = "loop";
  /** The reviewer still rejected the answer once {@code maxReviews} retries were used; see {@link Reply#review}. */
  public static final String REJECTED = "rejected";

  /**
   * Starts the content of a {@code TOOL} message whose call failed (unknown tool, bad arguments, denied, not
   * run...), so a reader of the stored conversation can tell failures from results.
   */
  public static final String TOOL_ERROR_PREFIX = "error: ";

  /** Starts the user message that sends a reviewer's rejection back to the model (stored with the turn). */
  public static final String REVIEW_FEEDBACK_PREFIX = "A review of your answer rejected it: ";

  /**
   * The answer. {@code text} is the bot's last message; {@code usage} adds up every model call of the turn
   * (reviews included); {@code stop} says why it ended ({@link #DONE}, {@link #MAX_STEPS}, {@link #BUDGET},
   * {@link #LOOP}, {@link #REJECTED}). {@code review} is the reason of the last verdict, null when nobody
   * reviewed the answer. {@code traceId} is the turn's trace, whose spans are emitted on
   * {@link panic.trace.Trace#SPAN}.
   */
  public record Reply(String text, String model, String finishReason, Usage usage, String stop, int steps,
      int toolCalls, String review, String traceId) {

    public Reply(String text, String model, String finishReason, Usage usage, String stop, int steps,
        int toolCalls, String review) {
      this(text, model, finishReason, usage, stop, steps, toolCalls, review, null);
    }

    public Reply(String text, String model, String finishReason, Usage usage, String stop, int steps,
        int toolCalls) {
      this(text, model, finishReason, usage, stop, steps, toolCalls, null);
    }

    public Reply(String text, String model, String finishReason, Usage usage) {
      this(text, model, finishReason, usage, DONE, 1, 0);
    }
  }

  /**
   * A final answer to judge, put to {@link #REVIEW}. {@code turn} is the whole turn so far (the question, tool
   * calls and results, the answer); {@code attempt} counts from 1. A blocking reviewer must honour {@code signal}.
   */
  public record Review(String conversationId, String question, List<Message> turn, String answer, int attempt,
      Signal signal) {
    public Review {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(question, "question");
      Objects.requireNonNull(answer, "answer");
      Objects.requireNonNull(signal, "signal");
      turn = List.copyOf(turn);
    }
  }

  /** A reviewer's judgement: {@code reason} says what is missing (or why it passes); {@code usage} is its cost. */
  public record Verdict(boolean pass, String reason, Usage usage) {
    public Verdict {
      reason = reason == null || reason.isBlank() ? (pass ? "accepted" : "rejected without a reason") : reason.strip();
      if (usage == null) usage = Usage.NONE;
    }
  }

  /** Emitted after each review of an answer (auditing...). */
  public record Reviewed(String conversationId, int attempt, Verdict verdict) {
  }

  /** Emitted after every completed exchange. */
  public record Turn(String conversationId, String question, Reply reply) {
  }

  /**
   * A short, stable digest of a question, kept on the root span of each turn's trace ({@code questionHash}), so
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

  /** The question put to {@link #CONTEXT} contributors, once per ask. */
  public record ContextQuery(String conversationId) {
    public ContextQuery {
      Objects.requireNonNull(conversationId, "conversationId");
    }
  }

  /**
   * A part of the system prompt: sent as {@code ## name} then {@code text}. Sections are ordered by
   * {@code order}, then by name, so the prompt is stable from one request to the next.
   */
  public record Section(String name, int order, String text) {
    public Section {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(text, "text");
    }
  }

  public static final HookKey<Ask, Reply> ASK = HookKey.of("chat.ask", Ask.class, Reply.class);
  public static final EventKey<Turn> TURN = EventKey.of("chat.turn", Turn.class);
  /**
   * Asked when the model gives a final answer: may it stand? No answer (no reviewer, or nothing to check the
   * answer against) accepts it. A rejection sends the reason back to the model for another attempt, at most
   * {@code chat.maxReviews} times.
   */
  public static final HookKey<Review, Verdict> REVIEW = HookKey.of("chat.review", Review.class, Verdict.class);
  public static final EventKey<Reviewed> REVIEWED = EventKey.of("chat.reviewed", Reviewed.class);

  /** Emitted when a tool call of the bot starts and when it finishes (logging, auditing...). */
  public static final EventKey<Step> STEP = EventKey.of("chat.step", Step.class);

  /**
   * Collected once per ask: every answer becomes a section of the system prompt (environment, tool hints,
   * memory...). Register with {@link #contribute}, so the section goes away with the plugin that adds it.
   */
  public static final HookKey<ContextQuery, Section> CONTEXT = HookKey.of("chat.context", ContextQuery.class,
      Section.class);

  private Chat() {
  }

  /**
   * Asks the bot. Empty when no chat plugin is running (no LLM connected). The handler's own exception
   * (LlmException, core.CancelledException, ...) is thrown as is, not wrapped in an EmitException.
   */
  public static Optional<Reply> ask(Context ctx, Ask ask) {
    return Hooks.bail(ctx, ASK, ask);
  }

  /**
   * Adds a section to the system prompt of every ask while {@code ctx} lives. {@code text} may return null or
   * blank to add nothing for that conversation; if it throws, the section is left out of that ask and the ask
   * goes on. Disposing the result (or ctx) removes it.
   */
  public static Disposable contribute(Context ctx, String name, int order, Function<ContextQuery, String> text) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    return ctx.hook(CONTEXT, q -> {
      String t;
      try {
        t = text.apply(q);
      } catch (RuntimeException e) {
        return null; // one broken contributor must not break the ask
      }
      return t == null || t.isBlank() ? null : new Section(name, order, t);
    });
  }
}
