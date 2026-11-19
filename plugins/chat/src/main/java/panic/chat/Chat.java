package panic.chat;

import context.Context;
import context.EventKey;
import context.HookKey;
import core.Disposable;
import core.Signal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
   * {@code onStep} hears each tool call when it starts and when it finishes (a sub-agent's calls too, with
   * {@link Step#parentCallId}); {@code onReview} hears every verdict on a final answer (passed or not), before a
   * rejected answer is attempted again; {@code onToolDelta} hears the pieces of a tool call while the model
   * writes it (before {@code onStep} reports it running; nothing when the model cannot stream calls);
   * {@code onAgentDelta} hears the text a sub-agent writes (and what it thinks) while one of this turn's tool
   * calls runs it. {@code onReasoning} hears what the model thinks before it writes, when the provider shows it;
   * it is shown, never saved.
   * {@code traceId} names the turn's trace (see {@link panic.trace.Trace}); null lets chat pick one, which
   * {@link Reply#traceId} returns. {@code delegate} is null for a user's question, set when a sub-agent asks
   * (see {@link Delegate}). The callbacks are called in the order things happen, one at a time.
   */
  public record Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
      Consumer<Step> onStep, Consumer<Reviewed> onReview, Consumer<ToolCallDelta> onToolDelta, String traceId,
      Consumer<AgentDelta> onAgentDelta, Delegate delegate, Consumer<String> onReasoning) {
    public Ask {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(signal, "signal");
      Objects.requireNonNull(onDelta, "onDelta");
      if (onStep == null) onStep = s -> { };
      if (onReview == null) onReview = r -> { };
      if (onToolDelta == null) onToolDelta = d -> { };
      if (traceId != null && traceId.isBlank()) traceId = null;
      if (onAgentDelta == null) onAgentDelta = d -> { };
      if (onReasoning == null) onReasoning = r -> { };
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
        Consumer<Step> onStep, Consumer<Reviewed> onReview, Consumer<ToolCallDelta> onToolDelta, String traceId,
        Consumer<AgentDelta> onAgentDelta, Delegate delegate) {
      this(conversationId, text, signal, onDelta, onStep, onReview, onToolDelta, traceId, onAgentDelta, delegate,
          null);
    }

    /** This ask, with {@code onReasoning} hearing what the model thinks. */
    public Ask withReasoning(Consumer<String> onReasoning) {
      return new Ask(conversationId, text, signal, onDelta, onStep, onReview, onToolDelta, traceId, onAgentDelta,
          delegate, onReasoning);
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
        Consumer<Step> onStep, Consumer<Reviewed> onReview, Consumer<ToolCallDelta> onToolDelta, String traceId) {
      this(conversationId, text, signal, onDelta, onStep, onReview, onToolDelta, traceId, null, null);
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

  /**
   * An ask made on behalf of another turn: a sub-agent doing a task for it. The turn runs in its own
   * conversation ({@link Ask#conversationId}) with:
   * <ul>
   * <li>{@code origin}: the conversation the user talks to. Tool approvals are asked there, naming
   * {@code agent}.</li>
   * <li>{@code systemPrompt}: replaces {@code chat.systemPrompt} (null keeps it). Contributed sections are still
   * added, for the sub-agent's conversation.</li>
   * <li>{@code tools}: the only tools offered and allowed (null = all). A call to another tool is refused.</li>
   * <li>{@code maxSteps} / {@code maxTokens}: its own limits, 0 = those of the chat config.</li>
   * <li>{@code parentSpanId}: the span its {@code turn} span nests under, in {@link Ask#traceId}'s trace.</li>
   * </ul>
   * A delegated turn is never reviewed (the asker's answer is) and never compacts its history.
   */
  public record Delegate(String origin, String agent, String systemPrompt, Set<String> tools, int maxSteps,
      int maxTokens, String parentSpanId) {
    public Delegate {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(agent, "agent");
      if (systemPrompt != null && systemPrompt.isBlank()) systemPrompt = null;
      if (tools != null) tools = Set.copyOf(tools);
      if (maxSteps < 0) maxSteps = 0;
      if (maxTokens < 0) maxTokens = 0;
    }

    /** True when the sub-agent may see and call {@code tool}. */
    public boolean allows(String tool) {
      return tools == null || tools.contains(tool);
    }
  }

  /**
   * A piece of text a sub-agent wrote while tool call {@code parentCallId} of this turn runs it; {@code reasoning}
   * when it is what the sub-agent thinks rather than its answer.
   */
  public record AgentDelta(String parentCallId, String agent, String text, boolean reasoning) {

    public AgentDelta(String parentCallId, String agent, String text) {
      this(parentCallId, agent, text, false);
    }
  }

  /**
   * A tool call of the bot: {@code result} is null when it starts, set when it is done. A call made by a
   * sub-agent that one of this turn's calls runs has that call's id as {@code parentCallId} and the sub-agent's
   * name as {@code agent} (both null for the bot's own calls).
   */
  public record Step(String conversationId, ToolCall call, ToolResult result, String parentCallId, String agent) {

    public Step(String conversationId, ToolCall call, ToolResult result) {
      this(conversationId, call, result, null, null);
    }

    public boolean done() {
      return result != null;
    }

    /** True for a sub-agent's call, reported to the asker of the turn that runs it. */
    public boolean nested() {
      return parentCallId != null;
    }
  }

  /**
   * Why a turn ended. Anything but {@link #DONE} means the bot was stopped before it finished. Only hard limits stop
   * a turn ({@link #MAX_STEPS}, {@link #BUDGET}, {@link #STALLED}), as in the Claude Agent SDK ({@code max_turns}),
   * Cursor (tool call limit, then "Continue") and the OpenAI Agents SDK ({@code max_turns}): a repeated call is
   * refused as feedback, it does not end the turn.
   */
  public static final String DONE = "done";
  public static final String MAX_STEPS = "max_steps";
  public static final String BUDGET = "budget";
  /** The reviewer still rejected the answer once {@code maxReviews} retries were used; see {@link Reply#review}. */
  public static final String REJECTED = "rejected";
  /**
   * The model's reply was cut at the output limit ({@code llm.maxTokens}) {@code TRUNCATIONS_IN_A_ROW} times in a
   * row: the limit is too low for what it tries to write, and asking again would only cut it again.
   */
  public static final String TRUNCATED = "truncated";
  /**
   * {@link #STALL_STOP_CALLS} tool calls in a row brought nothing the turn had not seen (the same file part, the same
   * output), {@link #STALL_FEEDBACK_CALLS} of them after the model was told so: it is going round in circles, and
   * every further call costs the whole context again.
   */
  public static final String STALLED = "stalled";

  /** Tool calls in a row without new information after which the model is told ({@link #STALL_FEEDBACK_PREFIX}). */
  public static final int STALL_FEEDBACK_CALLS = 15;

  /** Tool calls in a row without new information after which the turn stops with {@link #STALLED}. */
  public static final int STALL_STOP_CALLS = 30;

  /**
   * Starts the content of a {@code TOOL} message whose call failed (unknown tool, bad arguments, denied, not
   * run...), so a reader of the stored conversation can tell failures from results.
   */
  public static final String TOOL_ERROR_PREFIX = "error: ";

  /** Starts the user message that sends a reviewer's rejection back to the model (stored with the turn). */
  public static final String REVIEW_FEEDBACK_PREFIX = "A review of your answer rejected it: ";

  /**
   * Starts the user message that tells the model its last reply was cut at the output limit (stored with the
   * turn). It is the harness speaking, like {@link #REVIEW_FEEDBACK_PREFIX}: see {@link #fromHarness}.
   */
  public static final String TRUNCATED_FEEDBACK_PREFIX = "Your last reply was cut off at the output token limit";

  /**
   * Starts the user message that tells the model its last tool calls brought nothing new (stored with the turn). The
   * harness speaking, like {@link #REVIEW_FEEDBACK_PREFIX}.
   */
  public static final String STALL_FEEDBACK_PREFIX = "Your last tool calls brought no new information";

  /** Cut replies in a row after which a turn stops with {@link #TRUNCATED}. */
  public static final int TRUNCATIONS_IN_A_ROW = 3;

  /**
   * True for a user message the harness wrote (a review's rejection, a cut reply's notice), not the user: front-ends
   * do not show it as the user's words and summaries do not keep it as such.
   */
  public static boolean fromHarness(String userMessage) {
    return userMessage.startsWith(REVIEW_FEEDBACK_PREFIX) || userMessage.startsWith(TRUNCATED_FEEDBACK_PREFIX)
        || userMessage.startsWith(STALL_FEEDBACK_PREFIX);
  }

  /**
   * The answer. {@code text} is the bot's last message, or, when {@link #MAX_STEPS} or {@link #BUDGET} stopped the
   * turn before it answered or {@link #TRUNCATED} stopped it, a closing note of the harness (why it stopped, the
   * tools run, how to continue) that is not saved in the conversation; {@code usage} adds up every model call of the
   * turn (reviews included); {@code stop} says why it ended ({@link #DONE}, {@link #MAX_STEPS}, {@link #BUDGET},
   * {@link #REJECTED}, {@link #TRUNCATED}, {@link #STALLED}). {@code review} is the reason of the last verdict, null when nobody
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
   * A final answer to judge, put to {@link #STOP} and then to {@link #REVIEW}. {@code turn} is the whole turn so far
   * (the question, tool calls and results, the answer); {@code attempt} counts from 1. {@code evidence} is what the
   * stop checks that passed reported (empty for {@link #STOP} itself): ground truth the harness gathered, not a
   * claim of the model. A blocking reviewer must honour {@code signal}.
   */
  public record Review(String conversationId, String question, List<Message> turn, String answer, int attempt,
      Signal signal, List<String> evidence) {
    public Review {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(question, "question");
      Objects.requireNonNull(answer, "answer");
      Objects.requireNonNull(signal, "signal");
      turn = List.copyOf(turn);
      evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public Review(String conversationId, String question, List<Message> turn, String answer, int attempt,
        Signal signal) {
      this(conversationId, question, turn, answer, attempt, signal, null);
    }

    /** This review with {@code evidence}. */
    public Review withEvidence(List<String> evidence) {
      return new Review(conversationId, question, turn, answer, attempt, signal, evidence);
    }
  }

  /**
   * What one stop check said about a final answer (after Claude Code's Stop hooks). {@code name} says which check
   * (a command). {@code block}: the answer may not stand, and {@code reason} goes back to the model, which works on.
   * Otherwise {@code reason} is what the check found (e.g. "passed, exit 0"), passed to the reviewer as evidence.
   * {@code error} not null: the check could not run; it is reported and blocks nothing.
   */
  public record StopCheck(String name, boolean block, String reason, String error) {
    public StopCheck {
      Objects.requireNonNull(name, "name");
      if (reason == null) reason = "";
    }

    public static StopCheck passed(String name, String reason) {
      return new StopCheck(name, false, reason, null);
    }

    public static StopCheck blocked(String name, String reason) {
      return new StopCheck(name, true, reason, null);
    }

    public static StopCheck failed(String name, String error) {
      return new StopCheck(name, false, "", error);
    }
  }

  /** A reviewer's judgement: {@code reason} says what is missing (or why it passes); {@code usage} is its cost. */
  public record Verdict(boolean pass, String reason, Usage usage) {
    public Verdict {
      reason = reason == null || reason.isBlank() ? (pass ? "accepted" : "rejected without a reason") : reason.strip();
      if (usage == null) usage = Usage.NONE;
    }
  }

  /**
   * Emitted after each review of an answer (auditing...). {@code verdict} is null when the reviewer failed
   * ({@code error} says why): the answer stands, but the failure is reported instead of passing silently.
   */
  public record Reviewed(String conversationId, int attempt, Verdict verdict, String error) {
    public Reviewed {
      if ((verdict == null) == (error == null)) throw new IllegalArgumentException("a verdict or an error, not both");
    }

    public Reviewed(String conversationId, int attempt, Verdict verdict) {
      this(conversationId, attempt, verdict, null);
    }

    /** True when the reviewer could not judge the answer. */
    public boolean failed() {
      return error != null;
    }
  }

  /** Emitted after every completed exchange. {@code delegate} is set when a sub-agent asked. */
  public record Turn(String conversationId, String question, Reply reply, Delegate delegate) {

    public Turn(String conversationId, String question, Reply reply) {
      this(conversationId, question, reply, null);
    }
  }

  /**
   * The question put to {@link #CONTEXT} contributors, once per ask. {@code conversationId} is the conversation
   * the ask runs in. For a sub-agent ({@link Delegate}) that is its own conversation, so {@code origin} names the
   * conversation the user talks to (its project, its approvals) and {@code agent} the sub-agent; for the main agent
   * {@code origin} equals {@code conversationId} and {@code agent} is null.
   */
  public record ContextQuery(String conversationId, String origin, String agent) {
    public ContextQuery {
      Objects.requireNonNull(conversationId, "conversationId");
      if (origin == null || origin.isBlank()) origin = conversationId;
      if (agent != null && agent.isBlank()) agent = null;
    }

    /** The main agent's query for {@code conversationId}. */
    public ContextQuery(String conversationId) {
      this(conversationId, null, null);
    }

    /** True when the ask is a sub-agent's. */
    public boolean delegated() {
      return agent != null;
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
   * answer against) accepts it. A reviewer that throws accepts it too, but the failure is recorded (an ERROR
   * {@code review} span and a {@link Reviewed} with the error). A rejection sends the reason back to the model for another attempt, at most
   * {@code chat.maxReviews} times.
   */
  public static final HookKey<Review, Verdict> REVIEW = HookKey.of("chat.review", Review.class, Verdict.class);
  public static final EventKey<Reviewed> REVIEWED = EventKey.of("chat.reviewed", Reviewed.class);

  /**
   * Collected when the model gives a final answer, before {@link #REVIEW}: every answerer runs (as every Stop hook
   * of Claude Code does). One that blocks rejects the answer, which goes back to the model with the reasons, like a
   * review's rejection and within the same {@code chat.maxReviews}; the reviewer is then not asked. The findings of
   * checks that passed go to the reviewer as {@link Review#evidence}. Register with {@link #stopCheck}: a check
   * that throws then becomes a {@link StopCheck#error} instead of hiding the answers of the others.
   */
  public static final HookKey<Review, StopCheck> STOP = HookKey.of("chat.stop", Review.class, StopCheck.class);

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
   * Answers {@link #STOP} while {@code ctx} lives. {@code check} may return null to say nothing about an answer; if
   * it throws, that is reported as a {@link StopCheck#error} named {@code name} (cancellation is thrown as is).
   */
  public static Disposable stopCheck(Context ctx, String name, Function<Review, StopCheck> check) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(check, "check");
    return ctx.hook(STOP, r -> {
      try {
        return check.apply(r);
      } catch (core.CancelledException e) {
        throw e;
      } catch (RuntimeException e) {
        return StopCheck.failed(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      }
    });
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
