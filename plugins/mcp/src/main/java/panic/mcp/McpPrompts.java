package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.Context;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import panic.command.Call;
import panic.command.Commands;
import panic.command.UsageException;
import panic.mcp.McpClient.McpException;
import panic.util.Json;

/**
 * The prompts of one MCP server, as commands: Claude Code turns each into the slash command
 * {@code /mcp__<server>__<prompt> arg1 arg2}, and so does this. Running one asks the server for the prompt
 * ({@code prompts/get} with the arguments by position, in the order the prompt declares them; quotes group words,
 * and the last argument takes the rest of the line), then sends its text to the chat as if the user had typed it,
 * through the {@code ask} command of the same session. Without a chat, the text is printed.
 *
 * The commands live in the server's sub-plugin, so they go with it. A list that changes
 * ({@code notifications/prompts/list_changed}) keeps the commands that did not change: removing one waits for a
 * run in progress, which may be a whole chat turn.
 */
final class McpPrompts {

  /** The chat's command (plugins/chat); also what the CLI runs for plain text. */
  static final String ASK = "ask";

  private record Registered(JsonNode prompt, Disposable registration) {
  }

  private final McpSession session;
  private final Context ctx;
  private final Map<String, Registered> registered = new LinkedHashMap<>();

  McpPrompts(McpSession session, Context ctx) {
    this.session = session;
    this.ctx = ctx;
  }

  /**
   * {@code mcp__<server>__<prompt>} in lowercase, other characters than letters, digits, {@code _} and {@code -}
   * replaced by {@code _}, cut to 64.
   */
  static String commandName(String server, String prompt) {
    String full = (McpPlugin.PREFIX + server + "__" + prompt).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    return full.length() <= 64 ? full : full.substring(0, 64);
  }

  /** Lists the server's prompts (every page) and makes the commands match; returns how each is used. */
  List<String> list(Signal signal, long timeoutMillis) {
    List<JsonNode> prompts = new ArrayList<>();
    String cursor = null;
    do {
      ObjectNode params = Json.object();
      if (cursor != null) params.put("cursor", cursor);
      JsonNode page = session.request("prompts/list", params, signal, timeoutMillis);
      page.path("prompts").forEach(prompts::add);
      cursor = page.path("nextCursor").asText(null);
    } while (cursor != null && !cursor.isEmpty());
    Map<String, JsonNode> next = new LinkedHashMap<>();
    for (JsonNode p : prompts) {
      String name = p.path("name").asText("");
      if (!name.isEmpty()) next.putIfAbsent(commandName(session.name(), name), p);
    }
    List<Disposable> gone = new ArrayList<>();
    synchronized (registered) {
      for (var it = registered.entrySet().iterator(); it.hasNext(); ) {
        var e = it.next();
        if (!e.getValue().prompt().equals(next.get(e.getKey()))) {
          gone.add(e.getValue().registration());
          it.remove();
        }
      }
    }
    gone.forEach(Disposable::dispose);
    List<String> usages = new ArrayList<>();
    synchronized (registered) {
      next.forEach((command, p) -> {
        usages.add(usage(command, p) + describe(p));
        if (registered.containsKey(command)) return;
        Disposable d = Commands.command(ctx, command + " [args...]", description(p), call -> run(command, p, call));
        registered.put(command, new Registered(p, d));
      });
    }
    return usages;
  }

  /** Removes every command (the session ended). */
  void clear() {
    List<Disposable> old;
    synchronized (registered) {
      old = registered.values().stream().map(Registered::registration).toList();
      registered.clear();
    }
    old.forEach(Disposable::dispose);
  }

  private static String usage(String command, JsonNode prompt) {
    StringBuilder u = new StringBuilder(command);
    for (JsonNode a : prompt.path("arguments")) {
      String name = a.path("name").asText("?");
      u.append(' ').append(a.path("required").asBoolean(false) ? "<" + name + ">" : "[" + name + "]");
    }
    return u.toString();
  }

  private static String describe(JsonNode prompt) {
    String d = prompt.path("description").asText("").strip().replaceAll("\\s+", " ");
    return d.isEmpty() ? "" : " - " + d;
  }

  private static String description(JsonNode prompt) {
    String title = prompt.path("title").asText(prompt.path("description").asText("")).strip();
    List<String> args = new ArrayList<>();
    for (JsonNode a : prompt.path("arguments")) {
      args.add(a.path("name").asText("?") + (a.path("required").asBoolean(false) ? "" : "?"));
    }
    return (title.isEmpty() ? "MCP prompt" : title) + (args.isEmpty() ? "" : " (args: " + String.join(", ", args) + ")");
  }

  private void run(String command, JsonNode prompt, Call call) {
    String usage = usage(command, prompt);
    ObjectNode arguments = arguments(prompt, call.argOr("args", ""), usage);
    ObjectNode params = Json.object().put("name", prompt.path("name").asText());
    params.set("arguments", arguments);
    JsonNode result;
    try {
      result = session.request("prompts/get", params, call.signal(),
          TimeUnit.SECONDS.toMillis(session.config().timeoutSeconds()));
    } catch (McpException e) {
      call.out().println("MCP server " + session.name() + ": " + e.getMessage());
      return;
    }
    String text = text(result);
    if (text.isBlank()) {
      call.out().println("the prompt is empty");
      return;
    }
    if (!Commands.run(ctx, ASK, text, call.session())) call.out().println(text);
  }

  /**
   * The values typed after the command, given to the prompt's arguments in order: quotes group words, and the last
   * argument takes what is left. A required argument without a value is a {@link UsageException}.
   */
  static ObjectNode arguments(JsonNode prompt, String line, String usage) {
    List<JsonNode> declared = new ArrayList<>();
    prompt.path("arguments").forEach(declared::add);
    List<String> words = words(line);
    if (declared.isEmpty() && !words.isEmpty()) throw new UsageException("this prompt takes no arguments", usage);
    ObjectNode args = Json.object();
    for (int i = 0; i < declared.size(); i++) {
      String name = declared.get(i).path("name").asText();
      String value = i >= words.size() ? null
          : i == declared.size() - 1 ? String.join(" ", words.subList(i, words.size())) : words.get(i);
      if (value == null) {
        if (declared.get(i).path("required").asBoolean(false)) throw new UsageException("missing " + name, usage);
        continue;
      }
      args.put(name, value);
    }
    return args;
  }

  /** Splits on whitespace; {@code "..."} and {@code '...'} keep words together. */
  static List<String> words(String line) {
    List<String> words = new ArrayList<>();
    StringBuilder word = new StringBuilder();
    char quote = 0;
    boolean inWord = false;
    for (char c : line.strip().toCharArray()) {
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          word.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inWord = true;
      } else if (Character.isWhitespace(c)) {
        if (inWord) words.add(word.toString());
        word.setLength(0);
        inWord = false;
      } else {
        word.append(c);
        inWord = true;
      }
    }
    if (inWord) words.add(word.toString());
    return words;
  }

  /**
   * The prompt's messages as one text for the chat: a single user message as it is, several headed by their role.
   * Embedded resources give their text; images and audio are saved to files and named.
   */
  private String text(JsonNode result) {
    JsonNode messages = result.path("messages");
    List<String> parts = new ArrayList<>();
    for (JsonNode m : messages) {
      String content = session.block(m.path("content"));
      parts.add(messages.size() == 1 && m.path("role").asText("user").equals("user") ? content
          : "[" + m.path("role").asText("user") + "]\n" + content);
    }
    return String.join("\n\n", parts);
  }
}
