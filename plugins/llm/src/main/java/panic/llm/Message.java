package panic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One chat message. An assistant message may carry {@code toolCalls} (its text may then be empty); a
 * {@link Role#TOOL} message is the result of one of them and names it with {@code toolCallId}.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {

  public Message {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
      throw new IllegalArgumentException("only assistant messages carry tool calls");
    }
    if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) {
      throw new IllegalArgumentException("a tool message needs the id of the call it answers");
    }
    if (role != Role.TOOL && toolCallId != null) {
      throw new IllegalArgumentException("only tool messages carry a tool call id");
    }
  }

  public Message(Role role, String content) {
    this(role, content, List.of(), null);
  }

  public static Message system(String content) {
    return new Message(Role.SYSTEM, content);
  }

  public static Message user(String content) {
    return new Message(Role.USER, content);
  }

  public static Message assistant(String content) {
    return new Message(Role.ASSISTANT, content);
  }

  /** An assistant turn that asks for tools, with whatever text came along (may be empty). */
  public static Message assistant(String content, List<ToolCall> toolCalls) {
    return new Message(Role.ASSISTANT, content == null ? "" : content, toolCalls, null);
  }

  /** The result of the tool call {@code toolCallId}, fed back to the model. */
  public static Message tool(String toolCallId, String content) {
    return new Message(Role.TOOL, content, List.of(), toolCallId);
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
