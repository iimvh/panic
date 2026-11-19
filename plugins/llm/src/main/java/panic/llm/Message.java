package panic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One chat message. An assistant message may carry {@code toolCalls} (its text may then be empty); a
 * {@link Role#TOOL} message is the result of one of them and names it with {@code toolCallId}. An assistant message
 * may also carry {@code reasoning}: what the model thought before it wrote (see {@link Completion#reasoning}), kept
 * for the user to read. It is never sent to a model ("" when there is none). Likewise a {@link Role#TOOL} message
 * may carry {@code diff}: what the call changed in a file, as unified-diff hunks, kept for the user to see and never
 * sent to a model ("" when there is none).
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String reasoning,
    String diff) {

  public Message {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    if (reasoning == null) reasoning = "";
    if (diff == null) diff = "";
    if (!reasoning.isEmpty() && role != Role.ASSISTANT) {
      throw new IllegalArgumentException("only assistant messages carry reasoning");
    }
    if (!toolCalls.isEmpty() && role != Role.ASSISTANT) {
      throw new IllegalArgumentException("only assistant messages carry tool calls");
    }
    if (role == Role.TOOL && (toolCallId == null || toolCallId.isBlank())) {
      throw new IllegalArgumentException("a tool message needs the id of the call it answers");
    }
    if (role != Role.TOOL && toolCallId != null) {
      throw new IllegalArgumentException("only tool messages carry a tool call id");
    }
    if (!diff.isEmpty() && role != Role.TOOL) {
      throw new IllegalArgumentException("only tool messages carry a diff");
    }
  }

  public Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String reasoning) {
    this(role, content, toolCalls, toolCallId, reasoning, "");
  }

  public Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
    this(role, content, toolCalls, toolCallId, "");
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

  /** An assistant turn with what the model thought before it (never sent back to a model). */
  public static Message assistant(String content, List<ToolCall> toolCalls, String reasoning) {
    return new Message(Role.ASSISTANT, content == null ? "" : content, toolCalls, null, reasoning);
  }

  /** The result of the tool call {@code toolCallId}, fed back to the model. */
  public static Message tool(String toolCallId, String content) {
    return new Message(Role.TOOL, content, List.of(), toolCallId);
  }

  /** The result of the tool call {@code toolCallId}, with what it changed in a file for the user (null for none). */
  public static Message tool(String toolCallId, String content, String diff) {
    return new Message(Role.TOOL, content, List.of(), toolCallId, "", diff);
  }

  /** The same message with {@code content} instead (a result cut or cleared from the context), the rest kept. */
  public Message withContent(String content) {
    return new Message(role, content, toolCalls, toolCallId, reasoning, diff);
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}
