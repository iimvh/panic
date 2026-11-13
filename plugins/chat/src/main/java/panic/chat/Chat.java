package panic.chat;

import context.Context;
import context.EventKey;
import context.HookKey;
import core.Signal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import panic.command.Hooks;
import panic.llm.ToolCall;
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
   * {@code onStep} hears each tool call when it starts and when it finishes.
   */
  public record Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta,
      Consumer<Step> onStep) {
    public Ask {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(signal, "signal");
      Objects.requireNonNull(onDelta, "onDelta");
      if (onStep == null) onStep = s -> { };
    }

    public Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta) {
      this(conversationId, text, signal, onDelta, null);
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

  /**
   * The answer. {@code text} is the bot's last message; {@code usage} adds up every model call of the turn;
   * {@code stop} says why it ended ({@link #DONE}, {@link #MAX_STEPS}, {@link #BUDGET}, {@link #LOOP}).
   */
  public record Reply(String text, String model, String finishReason, Usage usage, String stop, int steps,
      int toolCalls) {

    public Reply(String text, String model, String finishReason, Usage usage) {
      this(text, model, finishReason, usage, DONE, 1, 0);
    }
  }

  /** Emitted after every completed exchange. */
  public record Turn(String conversationId, String question, Reply reply) {
  }

  public static final HookKey<Ask, Reply> ASK = HookKey.of("chat.ask", Ask.class, Reply.class);
  public static final EventKey<Turn> TURN = EventKey.of("chat.turn", Turn.class);
  /** Emitted when a tool call of the bot starts and when it finishes (logging, auditing...). */
  public static final EventKey<Step> STEP = EventKey.of("chat.step", Step.class);

  private Chat() {
  }

  /**
   * Asks the bot. Empty when no chat plugin is running (no LLM connected). The handler's own exception
   * (LlmException, core.CancelledException, ...) is thrown as is, not wrapped in an EmitException.
   */
  public static Optional<Reply> ask(Context ctx, Ask ask) {
    return Hooks.bail(ctx, ASK, ask);
  }
}
