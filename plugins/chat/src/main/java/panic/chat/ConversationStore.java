package panic.chat;

import context.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import panic.command.Commands;

/**
 * The store behind {@link Conversations#KEY}: conversations loaded on first use and kept in memory, written
 * through to {@code dir} (one file each, see {@link ConversationFile}), or in memory only when {@code dir} is
 * null. Listing and deleting see both the loaded conversations and the files not loaded yet.
 */
final class ConversationStore implements Conversations {

  /** Characters of the title (the first question) shown in listings. */
  static final int TITLE_CHARS = 80;

  private final Context ctx;
  private final Path dir;
  private final Consumer<String> warn;
  private final ConcurrentMap<String, Conversation> all = new ConcurrentHashMap<>();

  ConversationStore(Context ctx, Path dir, Consumer<String> warn) {
    this.ctx = ctx;
    this.dir = dir;
    this.warn = warn;
  }

  @Override
  public Conversation get(String id) {
    return all.computeIfAbsent(id, k -> dir == null ? new Conversation(k) : ConversationFile.load(dir, k, warn));
  }

  @Override
  public List<Info> list() {
    List<Info> out = new ArrayList<>();
    for (Entry e : entries().values()) {
      if (e.parent() == null && !e.empty()) out.add(new Info(e.id(), title(e.title()), e.updatedAt()));
    }
    out.sort(Comparator.comparingLong(Info::updatedAt).reversed().thenComparing(Info::id));
    return out;
  }

  @Override
  public boolean delete(String id) {
    Map<String, Entry> entries = entries();
    // the conversation, then whatever works for it, and for those, ...
    Map<String, List<String>> children = new HashMap<>();
    for (Entry e : entries.values()) {
      if (e.parent() != null) children.computeIfAbsent(e.parent(), k -> new ArrayList<>()).add(e.id());
    }
    Set<String> doomed = new LinkedHashSet<>();
    Deque<String> next = new ArrayDeque<>(List.of(id));
    while (!next.isEmpty()) {
      String x = next.poll();
      if (doomed.add(x)) next.addAll(children.getOrDefault(x, List.of()));
    }
    boolean any = false;
    for (String x : doomed) {
      boolean existed = entries.containsKey(x) && !entries.get(x).empty();
      Conversation c = all.remove(x);
      if (c != null) c.clear(); // its journal deletes the file
      if (dir != null) ConversationFile.delete(dir, x, warn);
      if (existed) {
        any = true;
        ctx.emit(DELETED, new Deleted(x));
      }
    }
    return any;
  }

  /** Answers {@link Conversations#LIST}, {@link Conversations#HISTORY} and {@link Conversations#REMOVE} for front-ends. */
  void hooks(Context ctx) {
    ctx.hook(LIST, q -> new Listing(list()));
    ctx.hook(HISTORY, q -> new History(get(q.id()).recent(0)));
    ctx.hook(REMOVE, q -> new Removal(delete(q.id())));
  }

  /** {@code list} and {@code forget}, and {@code reset} for the session's own conversation. */
  void commands(Context ctx) {
    Commands.command(ctx, "reset", "forget this session's conversation", call -> {
      Conversation c = get(call.session().id());
      boolean empty = c.isEmpty();
      c.clear();
      call.out().println(empty ? "nothing to forget" : "conversation cleared");
    });
    Commands.command(ctx, "chats [count]", "list the conversations, newest first", call -> {
      int count = count(call.arg("count"));
      List<Info> list = list();
      if (list.isEmpty()) {
        call.out().println("no conversations");
        return;
      }
      DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
      for (Info i : list.subList(0, Math.min(count, list.size()))) {
        String mark = i.id().equals(call.session().id()) ? "* " : "  ";
        call.out().println(mark + i.id() + "  " + time.format(Instant.ofEpochMilli(i.updatedAt())) + "  " + i.title());
      }
    });
    Commands.command(ctx, "forget <id>", "delete a conversation (and its sub-agents' conversations)", call -> {
      String id = call.arg("id");
      call.out().println(delete(id) ? "deleted " + id : "no conversation " + id);
    });
  }

  /** What listing and deleting know of a conversation, loaded or not. */
  private record Entry(String id, String parent, String title, long updatedAt, boolean empty) {
  }

  /** Every conversation: the loaded ones as they are now, the others from their files. */
  private Map<String, Entry> entries() {
    Map<String, Entry> out = new HashMap<>();
    for (Conversation c : all.values()) {
      out.put(c.id(), new Entry(c.id(), c.parent(), c.title(), c.updatedAt(), c.isEmpty()));
    }
    if (dir == null) return out;
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(f -> {
        String id = ConversationFile.idOf(f.getFileName().toString());
        if (id == null || out.containsKey(id)) return;
        ConversationFile.Head head = ConversationFile.head(f);
        if (head != null) out.put(id, new Entry(id, head.parent(), head.title(), head.updatedAt(), false));
      });
    } catch (IOException e) {
      warn.accept("[conversations] cannot list " + dir + ": " + e);
    }
    return out;
  }

  /** The {@code chats} count: 20 when not given or not a positive number. */
  private static int count(String arg) {
    if (arg == null) return 20;
    try {
      int n = Integer.parseInt(arg.strip());
      return n > 0 ? n : 20;
    } catch (NumberFormatException e) {
      return 20;
    }
  }

  /** The first line of {@code question}, cut at {@link #TITLE_CHARS}. */
  static String title(String question) {
    if (question == null) return "";
    String line = question.strip().lines().findFirst().orElse("").strip();
    return line.length() <= TITLE_CHARS ? line : line.substring(0, TITLE_CHARS - 1).strip() + "…";
  }
}
