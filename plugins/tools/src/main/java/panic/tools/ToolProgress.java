package panic.tools;

import java.util.Objects;
import panic.llm.ToolCall;

/**
 * Something a running tool shows its caller before it returns: a piece of text ({@code text}), or one of its
 * own tool calls starting ({@code result == null}) or ending. A sub-agent reports this way; {@code agent}
 * names it. Exactly one of {@code text} and {@code call} is set. {@code reasoning} marks text that is what the
 * sub-agent thinks, not what it answers.
 */
public record ToolProgress(String agent, String text, ToolCall call, ToolResult result, boolean reasoning) {

  public ToolProgress {
    if ((text == null) == (call == null)) throw new IllegalArgumentException("set either text or call");
    if (result != null) Objects.requireNonNull(call, "call");
    if (reasoning && text == null) throw new IllegalArgumentException("only text can be reasoning");
  }

  public ToolProgress(String agent, String text, ToolCall call, ToolResult result) {
    this(agent, text, call, result, false);
  }

  public static ToolProgress text(String agent, String text) {
    return new ToolProgress(agent, text, null, null, false);
  }

  /** A piece of what sub-agent {@code agent} thinks. */
  public static ToolProgress reasoning(String agent, String text) {
    return new ToolProgress(agent, text, null, null, true);
  }

  public static ToolProgress step(String agent, ToolCall call, ToolResult result) {
    return new ToolProgress(agent, null, call, result, false);
  }

  public boolean isText() {
    return text != null;
  }
}
