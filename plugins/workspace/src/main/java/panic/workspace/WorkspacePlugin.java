package panic.workspace;

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
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import panic.chat.Chat;
import panic.chat.Conversations;
import panic.command.Call;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.UsageException;
import panic.tools.Workspace;
import panic.util.Json;

/**
 * Projects: folders the user works in, and which conversation works in which. Answers {@link Workspace#ROOT}
 * (the file tools and checks ask it for the folder of the conversation they run for) and {@link Workspace#LIST}
 * (front-ends group chats by project), adds a {@code Workspace} section to the system prompt so the model knows
 * where it works, and offers command {@code workspace}.
 *
 * Projects and the map live in one JSON file under {@link WorkspaceConfig#dir}, replaced atomically on every
 * change; conversations themselves stay where {@code conversations} keeps them. Removing a project only forgets
 * the project: its chats keep their entry (listed as hidden) and come back when the same folder is added again.
 * Deleting a conversation drops its entry.
 *
 * Requires nothing: the hooks, the section and the command exist while the plugin does, and nothing requires
 * them. A config change re-registers everything with the new directory.
 */
public final class WorkspacePlugin implements ConfigPlugin<WorkspaceConfig> {

  private final Consumer<String> warn;

  public WorkspacePlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  @Override
  public Disposable apply(Context ctx, WorkspaceConfig cfg) {
    Store store = new Store(Path.of(cfg.dir()).toAbsolutePath().normalize().resolve("projects.json"), warn);
    ctx.hook(Workspace.ROOT, q -> store.root(q.conversationId()));
    ctx.hook(Workspace.LIST, q -> store.list());
    Chat.contribute(ctx, "Workspace", 1, q -> {
      // a sub-agent works in the project of the conversation that asked it
      Workspace.Project p = store.projectOf(q.origin());
      if (p == null) {
        return "No project is selected, so the file tools use the default workspace. When the user wants to work "
            + "in another folder of their computer, tell them to add it as a project (the \"Add project\" button "
            + "of the sidebar, or the command /workspace add <path>) and to start a chat in it.";
      }
      return "You work in the project \"" + p.name() + "\", the folder " + p.path() + ". The paths of read_file, "
          + "list_dir, write_file and edit_file are relative to it and you cannot reach anything outside it; checks run there "
          + "too. Another folder needs another project: the user adds one with the \"Add project\" button of the "
          + "sidebar or /workspace add <path>.";
    });
    ctx.on(Conversations.DELETED, d -> store.forget(d.id()));
    Commands.command(ctx, "workspace [action] [target...]",
        "projects: list | add <path> | use <path> | reset | remove [path] | pick | open [path]",
        call -> run(ctx, store, call));
    return null;
  }

  private static void run(Context ctx, Store store, Call call) {
    String action = call.argOr("action", "list");
    String target = call.arg("target");
    String id = call.session().id();
    try {
      switch (action) {
        case "list" -> list(store, id, call);
        case "add" -> {
          Path path = store.add(require(target, "add <path>"));
          call.out().println("added " + path);
        }
        case "use" -> {
          Path path = store.use(id, require(target, "use <path>"));
          call.out().println("this chat works in " + path);
        }
        case "reset" -> {
          store.reset(id);
          call.out().println("this chat uses the default workspace");
        }
        case "remove" -> {
          Path path = target == null ? current(store, id) : store.resolveDir(target);
          if (path == null) throw new UsageException("this chat has no project; give a path", "workspace remove [path]");
          call.out().println(store.remove(path) ? "removed " + path + " (its chats are hidden until it is added again)"
              : path + " is not a project");
        }
        case "pick" -> {
          var picked = Hooks.bail(ctx, Workspace.PICK, new Workspace.PickQuery(call.signal()));
          if (picked.isEmpty()) {
            call.out().println("no folder chosen (cancelled, or no folder dialog on this machine); "
                + "type the path: workspace add <path>");
            return;
          }
          Path path = store.add(picked.get().path().toString());
          call.out().println("added " + path);
        }
        case "open" -> {
          Path path = target == null ? current(store, id) : store.resolveDir(target);
          if (path == null) throw new UsageException("this chat has no project; give a path", "workspace open [path]");
          // only a project: the page may name any path, and opening is the user's choice of folders, not the page's
          if (!store.isProject(path)) {
            call.out().println(path + " is not a project");
            return;
          }
          boolean opened = Hooks.bail(ctx, Workspace.REVEAL, new Workspace.RevealQuery(path)).orElse(false);
          call.out().println(opened ? "opened " + path
              : "cannot open folders on this machine (folder-picker is off or has no file manager); the folder is "
                  + path);
        }
        default -> throw new UsageException("unknown action '" + action + "'", "workspace [action] [target...]");
      }
    } catch (IllegalArgumentException e) {
      call.out().println(e.getMessage());
    } catch (IOException e) {
      call.out().println("cannot save the projects: " + e.getMessage());
    }
  }

  private static String require(String target, String usage) {
    if (target == null || target.isBlank()) throw new UsageException("missing <path>", "workspace " + usage);
    return target;
  }

  private static Path current(Store store, String id) {
    Workspace.Project p = store.projectOf(id);
    return p == null ? null : p.path();
  }

  private static void list(Store store, String id, Call call) {
    Workspace.Project here = store.projectOf(id);
    call.out().println("this chat: " + (here == null ? "default workspace" : here.name() + "  " + here.path()));
    Workspace.Projects all = store.list();
    if (all.projects().isEmpty()) {
      call.out().println("no projects: workspace add <path>");
      return;
    }
    for (Workspace.Project p : all.projects()) {
      call.out().println(p.name() + "  " + p.path() + "  (" + p.conversations().size() + " chats)");
    }
  }

  /** The projects and the chat map in memory, saved to {@code file} on every change. */
  private static final class Store {
    private final Path file;
    private final Consumer<String> warn;
    /** Absolute path (as text) to display name, in the order added. */
    private final Map<String, String> projects = new LinkedHashMap<>();
    /** Conversation id to the path of its project (which may have been removed since). */
    private final Map<String, String> chats = new LinkedHashMap<>();

    Store(Path file, Consumer<String> warn) {
      this.file = file;
      this.warn = warn;
      load();
    }

    private void load() {
      String text;
      try {
        text = Files.readString(file, StandardCharsets.UTF_8);
      } catch (NoSuchFileException e) {
        return;
      } catch (IOException e) {
        warn.accept("[workspace] cannot read " + file + ": " + e);
        return;
      }
      try {
        JsonNode root = Json.MAPPER.readTree(text);
        for (JsonNode p : root.path("projects")) {
          projects.put(p.path("path").asText(), p.path("name").asText());
        }
        root.path("chats").fields().forEachRemaining(e -> chats.put(e.getKey(), e.getValue().asText()));
      } catch (IOException | RuntimeException e) {
        projects.clear();
        chats.clear();
        warn.accept("[workspace] ignoring damaged " + file + ": " + e.getMessage());
      }
    }

    /** Writes the state; the caller holds the lock. */
    private void save() throws IOException {
      ObjectNode root = Json.object();
      ArrayNode list = root.putArray("projects");
      projects.forEach((path, name) -> list.addObject().put("path", path).put("name", name));
      ObjectNode map = root.putObject("chats");
      chats.forEach(map::put);
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
          StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    /** An existing directory as an absolute real path; throws a message for the user otherwise. */
    Path resolveDir(String text) {
      Path path;
      try {
        path = Path.of(text.strip()).toAbsolutePath().normalize().toRealPath();
      } catch (InvalidPathException e) {
        throw new IllegalArgumentException("invalid path: " + text);
      } catch (IOException e) {
        throw new IllegalArgumentException("folder not found: " + text);
      }
      if (!Files.isDirectory(path)) throw new IllegalArgumentException("not a folder: " + path);
      return path;
    }

    private static String nameOf(Path path) {
      Path last = path.getFileName();
      return last == null ? path.toString() : last.toString();
    }

    synchronized Path add(String text) throws IOException {
      Path path = resolveDir(text);
      if (projects.putIfAbsent(path.toString(), nameOf(path)) == null) save();
      return path;
    }

    synchronized Path use(String id, String text) throws IOException {
      Path path = resolveDir(text);
      projects.putIfAbsent(path.toString(), nameOf(path));
      chats.put(id, path.toString());
      save();
      return path;
    }

    synchronized void reset(String id) throws IOException {
      if (chats.remove(id) != null) save();
    }

    synchronized boolean isProject(Path path) {
      return projects.containsKey(path.toString());
    }

    synchronized boolean remove(Path path) throws IOException {
      if (projects.remove(path.toString()) == null) return false;
      save();
      return true;
    }

    /** Drops the entry of a deleted conversation (a failure to save is a warning). */
    synchronized void forget(String id) {
      if (chats.remove(id) == null) return;
      try {
        save();
      } catch (IOException e) {
        warn.accept("[workspace] cannot save " + file + ": " + e);
      }
    }

    /** The folder of {@code id}'s project, or null when it has none or the project was removed. */
    synchronized Path root(String id) {
      String path = chats.get(id);
      return path != null && projects.containsKey(path) ? Path.of(path) : null;
    }

    synchronized Workspace.Project projectOf(String id) {
      String path = chats.get(id);
      return path == null || !projects.containsKey(path) ? null : project(path);
    }

    private Workspace.Project project(String path) {
      List<String> ids = new ArrayList<>();
      chats.forEach((id, p) -> {
        if (p.equals(path)) ids.add(id);
      });
      return new Workspace.Project(Path.of(path), projects.get(path), ids);
    }

    synchronized Workspace.Projects list() {
      List<Workspace.Project> active = new ArrayList<>();
      for (String path : projects.keySet()) active.add(project(path));
      List<String> hidden = new ArrayList<>();
      chats.forEach((id, p) -> {
        if (!projects.containsKey(p)) hidden.add(id);
      });
      return new Workspace.Projects(active, hidden);
    }
  }
}
