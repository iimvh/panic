package panic.memory;

import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import panic.chat.Chat;
import panic.command.Commands;
import panic.tools.Workspace;

/**
 * The instruction files people write for agents ({@code AGENTS.md}, {@code CLAUDE.md}), sent in the system prompt
 * as the section {@code Instructions}, after Pi and opencode: the user's global file, then one file per folder
 * from the farthest parent down to the chat's project (the nearest, most specific, last). The walk up stops at
 * the folder holding {@code .git}, below the user's home, or at the file system root. Command {@code instructions}
 * shows what a chat gets.
 *
 * Requires nothing and provides no service, only a hook answer and a command, so a config change restarts this
 * plugin alone. Files are read again only when their time or size changes.
 */
public final class InstructionsPlugin implements ConfigPlugin<InstructionsConfig> {

  /** Section name; its order puts it after {@code Workspace} (1) and before the sections that change often. */
  static final String SECTION = "Instructions";
  static final int ORDER = 2;

  private final Consumer<String> warn;

  public InstructionsPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  /** One instruction file as sent: its text, maybe cut, and how many characters were left out. */
  record Source(Path path, String text, int cut) {
  }

  @Override
  public Disposable apply(Context ctx, InstructionsConfig cfg) {
    if (!cfg.enabled()) return null;
    Reader files = new Reader(warn);
    Path fallback = Path.of(cfg.root());
    Chat.contribute(ctx, SECTION, ORDER, q -> render(load(cfg, files, Workspace.root(ctx, q.origin(), fallback))));
    Commands.command(ctx, "instructions", "show the instruction files (AGENTS.md...) this chat's agent follows",
        call -> {
          Path root = Workspace.root(ctx, call.session().id(), fallback);
          List<Source> sources = load(cfg, files, root);
          if (sources.isEmpty()) {
            call.out().println("no instruction files: looked for " + String.join(", ", cfg.names()) + " from "
                + root.toAbsolutePath().normalize() + " up, and " + Path.of(cfg.global()).toAbsolutePath().normalize());
            return;
          }
          for (Source s : sources) {
            call.out().println(s.path() + "  " + s.text().length() + " chars" + (s.cut() > 0 ? ", " + s.cut()
                + " left out (instructions.maxChars " + cfg.maxChars() + ")" : ""));
          }
        });
    return null;
  }

  /** The files a chat working in {@code root} follows, in prompt order, cut to {@code maxChars} together. */
  static List<Source> load(InstructionsConfig cfg, Reader files, Path root) {
    Path global = Path.of(cfg.global()).toAbsolutePath().normalize();
    List<Path> folders = folders(root.toAbsolutePath().normalize());
    // farthest parent first, the project last
    Set<Path> found = new LinkedHashSet<>();
    for (int i = folders.size() - 1; i >= 0; i--) {
      for (String name : cfg.names()) {
        Path p = folders.get(i).resolve(name);
        if (Files.isRegularFile(p)) {
          found.add(p);
          break;
        }
      }
    }
    found.remove(global);
    List<Path> paths = new ArrayList<>();
    if (Files.isRegularFile(global)) paths.add(global);
    paths.addAll(found);

    List<String> texts = new ArrayList<>();
    List<Path> kept = new ArrayList<>();
    boolean globalKept = false;
    for (Path p : paths) {
      String t = files.read(p);
      if (t != null && !t.isBlank()) {
        kept.add(p);
        texts.add(t.strip());
        if (p.equals(global)) globalKept = true;
      }
    }
    return cut(kept, texts, globalKept, cfg.maxChars());
  }

  /**
   * Fits the texts in {@code max} characters. The project's own file is cut last, the global one next to last,
   * and the parents in between, farthest first: the nearer a file, the more it says about this work.
   */
  private static List<Source> cut(List<Path> paths, List<String> texts, boolean global, int max) {
    int n = paths.size();
    int[] keep = new int[n];
    int total = 0;
    for (int i = 0; i < n; i++) {
      keep[i] = texts.get(i).length();
      total += keep[i];
    }
    if (total > max) {
      List<Integer> order = new ArrayList<>();
      int first = global ? 1 : 0;
      for (int i = first; i < n - 1; i++) order.add(i); // parents, farthest first
      if (global && n > 1) order.add(0);
      order.add(n - 1); // the project's own file (or the global one when it is alone)
      int over = total - max;
      for (int i : order) {
        if (over <= 0) break;
        int take = Math.min(over, keep[i]);
        keep[i] -= take;
        over -= take;
      }
    }
    List<Source> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String t = texts.get(i);
      out.add(new Source(paths.get(i), t.substring(0, keep[i]), t.length() - keep[i]));
    }
    return out;
  }

  /** {@code start} and its parents, nearest first, up to the folder holding {@code .git} or below the home. */
  static List<Path> folders(Path start) {
    Path home = Path.of(System.getProperty("user.home", "/")).toAbsolutePath().normalize();
    List<Path> out = new ArrayList<>();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      out.add(dir);
      if (Files.exists(dir.resolve(".git"))) break;
      Path parent = dir.getParent();
      if (parent != null && parent.equals(home) && !dir.equals(home)) break;
    }
    return out;
  }

  /** The section text; null when there is nothing to follow. */
  static String render(List<Source> sources) {
    if (sources.isEmpty()) return null;
    StringBuilder out = new StringBuilder("Instructions written for agents by the user and the project. Follow them; "
        + "a later (nearer) file is more specific than an earlier one.");
    for (Source s : sources) {
      if (s.text().isEmpty()) {
        out.append("\n\n### ").append(s.path()).append("\n[left out: ").append(s.cut())
            .append(" chars; read the file if you need it]");
        continue;
      }
      out.append("\n\n### ").append(s.path()).append('\n').append(s.text());
      if (s.cut() > 0) out.append("\n[cut: ").append(s.cut()).append(" more chars; read the file for the rest]");
    }
    return out.toString();
  }

  /** The texts read so far, read again when a file's time or size changes. One per plugin instance. */
  static final class Reader {
    private record Entry(long modified, long size, String text) {
    }

    private final Consumer<String> warn;
    private final ConcurrentMap<Path, Entry> cache = new ConcurrentHashMap<>();

    Reader(Consumer<String> warn) {
      this.warn = warn;
    }

    /** The text of {@code p}; null when it cannot be read (with a warning). */
    String read(Path p) {
      try {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        Entry e = cache.get(p);
        long modified = a.lastModifiedTime().toMillis();
        if (e != null && e.modified() == modified && e.size() == a.size()) return e.text();
        String text = Files.readString(p, StandardCharsets.UTF_8);
        cache.put(p, new Entry(modified, a.size(), text));
        return text;
      } catch (IOException | RuntimeException e) {
        cache.remove(p);
        warn.accept("[instructions] cannot read " + p + ": " + e);
        return null;
      }
    }
  }
}
