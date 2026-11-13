package panic.tools;

import core.Signal;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Who calls a tool: the conversation it runs for, and the signal that cancels it. {@code started} runs on the
 * calling thread once the call is allowed, right before the tool itself starts, so a caller can time the tool
 * without counting the time the user took to approve it. It never runs for a refused or invalid call.
 *
 * A call made by a sub-agent runs in the sub-agent's own conversation, but the user talks to {@code origin}:
 * approvals are asked there, and {@code agent} names the sub-agent in them (null for the main agent).
 * {@code traceId} and {@code spanId} locate the call in the caller's trace (null when it is not traced), so a
 * tool that does traced work of its own can nest it under the call. {@code progress} hears what a long tool
 * wants to show while it runs (a sub-agent's text and tool calls); nobody has to listen.
 */
public record ToolContext(String conversationId, Signal signal, Runnable started, String origin, String agent,
    String traceId, String spanId, Consumer<ToolProgress> progress) {

  public ToolContext {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(signal, "signal");
    if (started == null) started = () -> { };
    if (origin == null || origin.isBlank()) origin = conversationId;
    if (agent != null && agent.isBlank()) agent = null;
    if (progress == null) progress = p -> { };
  }

  public ToolContext(String conversationId, Signal signal, Runnable started) {
    this(conversationId, signal, started, null, null, null, null, null);
  }

  public ToolContext(String conversationId, Signal signal) {
    this(conversationId, signal, null);
  }

  /** The same caller with another signal, and nothing left to run on start (what the tool itself receives). */
  public ToolContext forTool(Signal toolSignal) {
    return new ToolContext(conversationId, toolSignal, null, origin, agent, traceId, spanId, progress);
  }
}
