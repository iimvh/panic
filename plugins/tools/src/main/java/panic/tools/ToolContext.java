package panic.tools;

import core.Signal;
import java.util.Objects;

/**
 * Who calls a tool: the conversation it runs for, and the signal that cancels it. {@code started} runs on the
 * calling thread once the call is allowed, right before the tool itself starts, so a caller can time the tool
 * without counting the time the user took to approve it. It never runs for a refused or invalid call.
 */
public record ToolContext(String conversationId, Signal signal, Runnable started) {

  public ToolContext {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(signal, "signal");
    if (started == null) started = () -> { };
  }

  public ToolContext(String conversationId, Signal signal) {
    this(conversationId, signal, null);
  }
}
