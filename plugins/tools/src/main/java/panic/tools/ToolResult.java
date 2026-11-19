package panic.tools;

import java.util.Objects;
import panic.llm.Usage;

/**
 * What a tool call produced, as text for the model. {@code isError} marks a failed call: the model still
 * sees the content, so it can correct its arguments or try something else. {@code usage} is what the tool
 * spent on a model of its own (a sub-agent): the caller adds it to its turn, so budgets still hold.
 *
 * Optional, for the chat's context management: {@code resource} says what the result shows or changes (see
 * {@link Resource}), and {@code compact} is a short form of the content (a file's outline) that can stand in for
 * it once the full text no longer fits the context. Both are null when the tool gives none.
 */
public record ToolResult(String content, boolean isError, Usage usage, Resource resource, String compact) {

  public ToolResult {
    Objects.requireNonNull(content, "content");
    if (usage == null) usage = Usage.NONE;
    if (compact != null && compact.isBlank()) compact = null;
  }

  public ToolResult(String content, boolean isError, Usage usage) {
    this(content, isError, usage, null, null);
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

  /** The same result with {@code content} instead (cut, prefixed...), its usage, resource and compact form kept. */
  public ToolResult withContent(String content) {
    return new ToolResult(content, isError, usage, resource, compact);
  }

  /** The same result, about {@code resource}, with {@code compact} as its short form (null for none). */
  public ToolResult about(Resource resource, String compact) {
    return new ToolResult(content, isError, usage, resource, compact);
  }
}
