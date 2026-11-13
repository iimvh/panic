package panic.tools;

import java.util.Objects;
import panic.llm.Usage;

/**
 * What a tool call produced, as text for the model. {@code isError} marks a failed call: the model still
 * sees the content, so it can correct its arguments or try something else. {@code usage} is what the tool
 * spent on a model of its own (a sub-agent): the caller adds it to its turn, so budgets still hold.
 */
public record ToolResult(String content, boolean isError, Usage usage) {

  public ToolResult {
    Objects.requireNonNull(content, "content");
    if (usage == null) usage = Usage.NONE;
  }

  public ToolResult(String content, boolean isError) {
    this(content, isError, Usage.NONE);
  }

  public static ToolResult ok(String content) {
    return new ToolResult(content, false);
  }

  public static ToolResult error(String content) {
    return new ToolResult(content, true);
  }

  /** The same result with {@code content} instead (cut, prefixed...), its usage kept. */
  public ToolResult withContent(String content) {
    return new ToolResult(content, isError, usage);
  }
}
