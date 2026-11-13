package panic.llm;

import core.Signal;
import java.util.function.Consumer;

/**
 * Something that answers chat requests. Both calls block until the answer is complete; cancelling
 * {@code signal} aborts the request and makes the call throw {@code core.CancelledException}.
 */
public interface ChatModel {

  Completion complete(ChatRequest request, Signal signal);

  /** Like complete(), but hands each piece of text to {@code onDelta} as soon as it arrives. */
  Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta);
}
