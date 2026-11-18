package panic.command;

import context.Context;
import context.HookKey;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slash prompts: a line {@code /name args} that is not a command but text a plugin turns into a prompt (a skill),
 * after Claude Code's {@code /skill-name}. A front-end first runs {@code /name} as a
 * command; when no command has that name it asks {@link #EXPAND} and, if somebody answers, sends the line as a
 * plain ask. The chat asks {@link #EXPAND} again when the turn starts and puts the answer in the history. So a
 * slash prompt runs on the same path as any question (one answer per session, survives a page reload), and a
 * mistyped command is still reported as unknown instead of costing a model call.
 *
 * Like commands these are z hooks: the plugin that answers owns them, and without it the line is unknown.
 */
public final class Prompts {

  /** Which prompt: {@code name} without the slash, {@code args} the rest of the line ("" when none). */
  public record Query(String conversationId, String name, String args) {
    public Query {
      Objects.requireNonNull(conversationId, "conversationId");
      Objects.requireNonNull(name, "name");
      args = args == null ? "" : args.strip();
    }
  }

  /**
   * The expanded prompt. {@code text} is what the model reads. When {@code tool} is set, the chat records it as
   * a call of that tool with {@code toolArguments} (JSON) whose result is {@code text}, so the history looks as if
   * the model had loaded it itself; otherwise {@code text} follows the user's line as a user message.
   */
  public record Prompt(String name, String description, String tool, String toolArguments, String text) {
    public Prompt {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(text, "text");
      if (description == null) description = "";
      if (tool != null && tool.isBlank()) tool = null;
      if (toolArguments == null || toolArguments.isBlank()) toolArguments = "{}";
    }
  }

  /** A slash line split in name and arguments. */
  public record Line(String name, String args) {
  }

  public static final HookKey<Query, Prompt> EXPAND = HookKey.of("prompt.expand", Query.class, Prompt.class);

  private static final Pattern LINE = Pattern.compile("^/([a-z0-9][a-z0-9:_-]*)(?:\\s+([\\s\\S]*))?$");

  private Prompts() {
  }

  /** {@code /name args} split, or empty when {@code text} is not a slash line with a prompt-like name. */
  public static Optional<Line> parse(String text) {
    if (text == null) return Optional.empty();
    Matcher m = LINE.matcher(text.strip());
    if (!m.matches()) return Optional.empty();
    return Optional.of(new Line(m.group(1), m.group(2) == null ? "" : m.group(2).strip()));
  }

  /** The prompt {@code /name args} expands to in a conversation, if a running plugin answers it. */
  public static Optional<Prompt> expand(Context ctx, String conversationId, String name, String args) {
    return Hooks.bail(ctx, EXPAND, new Query(conversationId, name, args));
  }
}
