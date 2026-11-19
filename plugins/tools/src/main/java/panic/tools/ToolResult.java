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
 *
 * {@code diff} is for the user, never for a model: what the call changed in a file, as unified-diff hunks
 * ({@code @@ -a,b +c,d @@} then lines starting with space, {@code +} or {@code -}), so a front-end can show the change.
 * Null when the call changed no file.
 */
public record ToolResult(String content, boolean isError, Usage usage, Resource resource, String compact, String diff) {

  public ToolResult {
    Objects.requireNonNull(content, "content");
    if (usage == null) usage = Usage.NONE;
    if (compact != null && compact.isBlank()) compact = null;
    if (diff != null && diff.isEmpty()) diff = null;
  }

  public ToolResult(String content, boolean isError, Usage usage, Resource resource, String compact) {
    this(content, isError, usage, resource, compact, null);
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

  /** The same result with {@code content} instead (cut, prefixed...), its usage, resource, compact form and diff kept. */
  public ToolResult withContent(String content) {
    return new ToolResult(content, isError, usage, resource, compact, diff);
  }

  /** The same result, about {@code resource}, with {@code compact} as its short form (null for none). */
  public ToolResult about(Resource resource, String compact) {
    return new ToolResult(content, isError, usage, resource, compact, diff);
  }

  /** The same result, showing the user {@code diff} (null for none) as what it changed. */
  public ToolResult withDiff(String diff) {
    return new ToolResult(content, isError, usage, resource, compact, diff);
  }
}
