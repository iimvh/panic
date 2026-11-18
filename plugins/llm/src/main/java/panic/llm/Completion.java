package panic.llm;

import java.util.List;

/**
 * The full answer of one request. {@code finishReason} may be null when the provider sent none; it is
 * {@code tool_calls} when the model stopped to call {@code toolCalls}. {@code reasoning} is what the model
 * thought before it answered, when the provider shows it (empty otherwise). It is for the user to read: it is not
 * part of {@link #message()}, so it is never sent back to the model nor saved with the conversation.
 */
public record Completion(String text, String model, String finishReason, Usage usage, List<ToolCall> toolCalls,
    String reasoning) {

  public Completion {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    if (reasoning == null) reasoning = "";
  }

  public Completion(String text, String model, String finishReason, Usage usage, List<ToolCall> toolCalls) {
    this(text, model, finishReason, usage, toolCalls, "");
  }

  public Completion(String text, String model, String finishReason, Usage usage) {
    this(text, model, finishReason, usage, List.of(), "");
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }

  /** This answer as the assistant message to put back into the history (without the reasoning). */
  public Message message() {
    return Message.assistant(text, toolCalls);
  }
}
