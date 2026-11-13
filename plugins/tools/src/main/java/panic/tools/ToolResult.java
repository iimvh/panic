package panic.tools;

import java.util.Objects;

/**
 * What a tool call produced, as text for the model. {@code isError} marks a failed call: the model still
 * sees the content, so it can correct its arguments or try something else.
 */
public record ToolResult(String content, boolean isError) {

  public ToolResult {
    Objects.requireNonNull(content, "content");
  }

  public static ToolResult ok(String content) {
    return new ToolResult(content, false);
  }

  public static ToolResult error(String content) {
    return new ToolResult(content, true);
  }
}
