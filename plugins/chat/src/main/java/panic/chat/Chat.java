package panic.chat;

import context.Context;
import context.EventKey;
import context.HookKey;
import core.Signal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import panic.command.Hooks;
import panic.llm.Usage;

/**
 * How to talk to the chat bot. Callers do not depend on a service: they ask the {@link #ASK} hook, which
 * the chat plugin answers only while an LLM is connected. So a front-end (the REPL today, a UI later) keeps
 * running while the model behind it is switched, and simply gets no answer when there is none.
 */
public final class Chat {

  /** One user message. {@code signal} cancels the reply; {@code onDelta} receives it as it streams. */
  public record Ask(String conversationId, String text, Signal signal, Consumer<String> onDelta) {
    public Ask {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(signal, "signal");
      Objects.requireNonNull(onDelta, "onDelta");
    }
  }

  public record Reply(String text, String model, String finishReason, Usage usage) {
  }

  /** Emitted after every completed exchange. */
  public record Turn(String conversationId, String question, Reply reply) {
  }

  public static final HookKey<Ask, Reply> ASK = HookKey.of("chat.ask", Ask.class, Reply.class);
  public static final EventKey<Turn> TURN = EventKey.of("chat.turn", Turn.class);

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
