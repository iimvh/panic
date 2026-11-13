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
import panic.chat.Conversations;
import panic.command.Commands;
import panic.llm.Json;
import panic.llm.ToolSpec;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

/**
 * A plan per conversation that the agent keeps across turns and restarts: a task list and the acceptance
 * criteria of the work. Tools {@code todo_read} (risk read) and {@code todo_write} (risk write: it replaces the
 * list), the plan as a {@code Tasks} section of the system prompt ({@link Chat#contribute}), hook
 * {@link Todo#PLAN} for other plugins (the reviewer reads the criteria there), and command {@code todo}. Plans
 * are JSON files under {@link TodoConfig#dir}, replaced atomically on every write.
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
      Todo.Plan plan = store.read(q.conversationId());
      return plan.isEmpty() ? null : "Your plan (keep it current with todo_write):\n" + render(plan);
    });
    ctx.hook(Todo.PLAN, q -> {
      Todo.Plan plan = store.read(q.conversationId());
      return plan.isEmpty() ? null : plan;
    });
    // a deleted conversation takes its plan with it
    ctx.on(Conversations.DELETED, d -> store.delete(d.id()));
    Commands.command(ctx, "todo", "show this session's task list and acceptance criteria", call -> {
      Todo.Plan plan = store.read(call.session().id());
      call.out().println(plan.isEmpty() ? "no tasks" : render(plan));
    });
    return null;
  }

  /** Numbered lines ({@code 1. [~] text}), then the criteria under {@code Acceptance criteria:}. */
  static String render(Todo.Plan plan) {
    if (plan.isEmpty()) return "no tasks";
    StringBuilder out = new StringBuilder();
    List<TodoItem> tasks = plan.items();
    for (int i = 0; i < tasks.size(); i++) {
      if (i > 0) out.append('\n');
      out.append(i + 1).append(". ").append(tasks.get(i).line());
    }
    if (!plan.criteria().isEmpty()) {
      if (!tasks.isEmpty()) out.append('\n');
      out.append("Acceptance criteria:");
      for (String c : plan.criteria()) out.append("\n- ").append(c);
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

    /** The plan of {@code id}; empty when there is none or it cannot be read (with a warning). */
    Todo.Plan read(String id) {
      Path file = dir.resolve(fileName(id));
      synchronized (lock(id)) {
        String text;
        try {
          text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
          return Todo.Plan.EMPTY;
        } catch (IOException e) {
          warn.accept("[todo] cannot read " + file + ": " + e);
          return Todo.Plan.EMPTY;
        }
        try {
          JsonNode root = Json.MAPPER.readTree(text);
          JsonNode items = root.path("items");
          if (!items.isArray()) throw new IllegalArgumentException("no items list");
          List<TodoItem> tasks = new ArrayList<>();
          for (JsonNode n : items) tasks.add(new TodoItem(n.path("text").asText(""), TodoItem.Status.of(n.path("status").asText(""))));
          List<String> criteria = new ArrayList<>();
          for (JsonNode c : root.path("criteria")) criteria.add(c.asText());
          return new Todo.Plan(tasks, criteria);
        } catch (IOException | RuntimeException e) {
          warn.accept("[todo] ignoring damaged " + file + ": " + e.getMessage());
          return Todo.Plan.EMPTY;
        }
      }
    }

    /** Deletes the plan of {@code id}, if any (a failure is a warning). */
    void delete(String id) {
      Path file = dir.resolve(fileName(id));
      synchronized (lock(id)) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          warn.accept("[todo] cannot delete " + file + ": " + e);
        }
      }
      locks.remove(id);
    }

    /**
     * Replaces the tasks of {@code id}, and its criteria unless {@code criteria} is null (kept as they are), so
     * updating a status never loses the definition of done. Returns the new plan; an empty plan deletes the file.
     */
    Todo.Plan write(String id, List<TodoItem> tasks, List<String> criteria) throws IOException {
      Path file = dir.resolve(fileName(id));
      synchronized (lock(id)) {
        Todo.Plan plan = new Todo.Plan(tasks, criteria != null ? criteria : read(id).criteria());
        if (plan.isEmpty()) {
          Files.deleteIfExists(file);
          return plan;
        }
        ObjectNode root = Json.object();
        ArrayNode items = root.putArray("items");
        for (TodoItem t : plan.items()) items.addObject().put("text", t.text()).put("status", t.status().wire());
        ArrayNode crit = root.putArray("criteria");
        for (String c : plan.criteria()) crit.add(c);
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
            StandardCharsets.UTF_8);
        try {
          Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return plan;
      }
    }
  }

  private record Read(Store store) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("todo_read", "Your plan for this conversation: one numbered line per task with its "
          + "status ([ ] pending, [~] in progress, [x] done), then the acceptance criteria.",
          Json.parseOrNull("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      return ToolResult.ok(render(store.read(tc.conversationId())));
    }
  }

  private record Write(Store store, int maxItems) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec("todo_write", "Replace your task list for this conversation with `items` (send the whole "
          + "list, in order; [] clears it). Plan multi-step work here and mark each task in_progress, then done, as "
          + "you go. Before you start, write `criteria`: the checkable conditions your final answer must meet; it "
          + "will be reviewed against them. Leave `criteria` out to keep the current ones, [] clears them.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "items":{"type":"array","items":{"type":"object","properties":{
                  "text":{"type":"string","description":"what to do"},
                  "status":{"type":"string","enum":["pending","in_progress","done"]}},
                 "required":["text","status"],"additionalProperties":false}},
                "criteria":{"type":"array","items":{"type":"string"},
                  "description":"acceptance criteria of the whole task (definition of done)"}},
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
      List<String> criteria = null;
      if (args.has("criteria")) {
        criteria = new ArrayList<>();
        for (JsonNode c : args.get("criteria")) {
          if (!c.asText().isBlank()) criteria.add(c.asText().strip());
        }
        if (criteria.size() > maxItems) {
          return ToolResult.error("too many criteria: " + criteria.size() + " (at most " + maxItems + ")");
        }
      }
      Todo.Plan plan;
      try {
        plan = store.write(tc.conversationId(), tasks, criteria);
      } catch (IOException e) {
        return ToolResult.error("cannot save the task list: " + e.getMessage());
      }
      return ToolResult.ok(plan.isEmpty() ? "task list cleared" : render(plan));
    }
  }
}
