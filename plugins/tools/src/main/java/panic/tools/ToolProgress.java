package panic.tools;

import java.util.Objects;
import panic.llm.ToolCall;

/**
 * Something a running tool shows its caller before it returns: a piece of text ({@code text}), or one of its
 * own tool calls starting ({@code result == null}) or ending. A sub-agent reports this way; {@code agent}
 * names it. Exactly one of {@code text} and {@code call} is set.
 */
public record ToolProgress(String agent, String text, ToolCall call, ToolResult result) {

  public ToolProgress {
    if ((text == null) == (call == null)) throw new IllegalArgumentException("set either text or call");
    if (result != null) Objects.requireNonNull(call, "call");
  }

  public static ToolProgress text(String agent, String text) {
    return new ToolProgress(agent, text, null, null);
  }

  public static ToolProgress step(String agent, ToolCall call, ToolResult result) {
    return new ToolProgress(agent, null, call, result);
  }

  public boolean isText() {
    return text != null;
  }
}
