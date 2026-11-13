package panic.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import panic.chat.Chat;
import panic.command.Commands;
import panic.llm.Json;
import panic.llm.ToolSpec;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

/**
 * A task list per conversation that the agent keeps across turns and restarts: tools {@code todo_read} (risk
 * read) and {@code todo_write} (risk write: it replaces the whole list), the list as a {@code Tasks} section of
 * the system prompt ({@link Chat#contribute}), and command {@code todo}. Lists are JSON files under
 * {@link TodoConfig#dir}, replaced atomically on every write.
 *
 * Requires nothing: the tools and the section exist while the plugin does, and chat does not require them. A
 * config change re-registers everything with the new directory.
 */
public final class TodoPlugin implements ConfigPlugin<TodoConfig> {

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,100}");

  private final Consumer<String> warn;

  public TodoPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  @Override
  public Disposable apply(Context ctx, TodoConfig cfg) {
    Store store = new Store(Path.of(cfg.dir()).toAbsolutePath().normalize(), warn);
    Tools.register(ctx, new Read(store));
    Tools.register(ctx, new Write(store, cfg.maxItems()));
    Chat.contribute(ctx, "Tasks", 50, q -> {
      List<TodoItem> tasks = store.read(q.conversationId());
      return tasks.isEmpty() ? null : "Your task list (keep it current with todo_write):\n" + render(tasks);
    });
    Commands.command(ctx, "todo", "show this session's task list", call -> {
      List<TodoItem> tasks = store.read(call.session().id());
      call.out().println(tasks.isEmpty() ? "no tasks" : render(tasks));
    });
    return null;
  }

  /** Numbered lines: {@code 1. [~] text}. */
  static String render(List<TodoItem> tasks) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tasks.size(); i++) {
      if (i > 0) out.append('\n');
      out.append(i + 1).append(". ").append(tasks.get(i).line());
    }
    return out.toString();
  }

  /** The file name of conversation {@code id}: the id when short and safe, otherwise {@code ~} + base64url. */
  static String fileName(String id) {
    if (SAFE.matcher(id).matches()) return id + ".json";
    return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8)) + ".json";
  }

  /** The lists on disk, one lock per conversation so a read never sees half a write. */
  private static final class Store {
    private final Path dir;
    private final Consumer<String> warn;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    Store(Path dir, Consumer<String> warn) {
      this.dir = dir;
      this.warn = warn;
    }

    private Object lock(String id) {
      return locks.computeIfAbsent(id, k -> new Object());
    }

    /** The list of {@code id}; empty when there is none or it cannot be read (with a warning). */
    List<TodoItem> read(String id) {
      Path file = dir.resolve(fileName(id));
      synchronized (lock(id)) {
        String text;
        try {
          text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
          return List.of();
        } catch (IOException e) {
          warn.accept("[todo] cannot read " + file + ": " + e);
          return List.of();
        }
        try {
          List<TodoItem> tasks = new ArrayList<>();
          JsonNode items = Json.MAPPER.readTree(text).path("items");
          if (!items.isArray()) throw new IllegalArgumentException("no items list");
          for (JsonNode n : items) tasks.add(new TodoItem(n.path("text").asText(""), TodoItem.Status.of(n.path("status").asText(""))));
          return List.copyOf(tasks);
        } catch (IOException | RuntimeException e) {
          warn.accept("[todo] ignoring damaged " + file + ": " + e.getMessage());
          return List.of();
        }
      }
    }

    /** Replaces the list of {@code id}; an empty list deletes its file. */
    void write(String id, List<TodoItem> tasks) throws IOException {
      Path file = dir.resolve(fileName(id));
      synchronized (lock(id)) {
        if (tasks.isEmpty()) {
          Files.deleteIfExists(file);
          return;
        }
        ObjectNode root = Json.object();
        ArrayNode items = root.putArray("items");
        for (TodoItem t : tasks) items.addObject().put("text", t.text()).put("status", t.status().wire());
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
        try {
          Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private record Read(Store store) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("todo_read", "Your task list for this conversation, one numbered line per task with its "
          + "status: [ ] pending, [~] in progress, [x] done.",
          Json.parseOrNull("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      List<TodoItem> tasks = store.read(tc.conversationId());
      return ToolResult.ok(tasks.isEmpty() ? "no tasks" : render(tasks));
    }
  }

  private record Write(Store store, int maxItems) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("todo_write", "Replace your task list for this conversation with `items` (send the whole "
          + "list, in order; [] clears it). Plan multi-step work here and mark each task in_progress, then done, as "
          + "you go.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "items":{"type":"array","items":{"type":"object","properties":{
                  "text":{"type":"string","description":"what to do"},
                  "status":{"type":"string","enum":["pending","in_progress","done"]}},
                 "required":["text","status"],"additionalProperties":false}}},
               "required":["items"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.WRITE;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      JsonNode items = args.get("items");
      if (items.size() > maxItems) {
        return ToolResult.error("too many tasks: " + items.size() + " (at most " + maxItems + ")");
      }
      List<TodoItem> tasks = new ArrayList<>();
      for (int i = 0; i < items.size(); i++) {
        JsonNode n = items.get(i);
        if (n.get("text").asText().isBlank()) return ToolResult.error("items[" + i + "].text is empty");
        tasks.add(new TodoItem(n.get("text").asText(), TodoItem.Status.of(n.get("status").asText())));
      }
      try {
        store.write(tc.conversationId(), tasks);
      } catch (IOException e) {
        return ToolResult.error("cannot save the task list: " + e.getMessage());
      }
      return ToolResult.ok(tasks.isEmpty() ? "task list cleared" : render(tasks));
    }
  }
}
