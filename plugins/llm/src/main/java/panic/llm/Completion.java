package panic.llm;

import java.util.List;

/**
 * The full answer of one request. {@code finishReason} may be null when the provider sent none; it is
 * {@code tool_calls} when the model stopped to call {@code toolCalls}.
 */
public record Completion(String text, String model, String finishReason, Usage usage, List<ToolCall> toolCalls) {

  public Completion {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public Completion(String text, String model, String finishReason, Usage usage) {
    this(text, model, finishReason, usage, List.of());
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }

  /** This answer as the assistant message to put back into the history. */
  public Message message() {
    return Message.assistant(text, toolCalls);
  }
}
