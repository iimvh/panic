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

  /**
   * Like {@link #stream(ChatRequest, Signal, Consumer)}, and also hands each piece of a tool call to
   * {@code onToolDelta} as it arrives. A model that cannot tell (the default) reports no pieces: callers then
   * see the calls only in the {@link Completion}.
   */
  default Completion stream(ChatRequest request, Signal signal, Consumer<String> onDelta,
      Consumer<ToolCallDelta> onToolDelta) {
    return stream(request, signal, onDelta);
  }
}
