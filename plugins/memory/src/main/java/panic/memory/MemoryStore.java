package panic.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The memory files of one {@code dir}, behind the virtual paths the model uses (after Anthropic's memory tool):
 * {@code /memories/user/...} is shared by every chat, {@code /memories/project/...} by the chats of one project.
 * A project's memory lives under {@code dir/projects/<name>-<hash>}, keyed by the git root of its folder (or the
 * folder), so it is never written into the user's repository.
 *
 * Every operation runs under one lock: they are small, and {@link #forget} touches a file and its index together.
 * Writes go through a temporary file and an atomic rename. Another process sharing {@code dir} is not locked out
 * (one Panic per directory, as for conversations); a stale {@code str_replace} then fails instead of losing text.
 */
final class MemoryStore {

  static final String ROOT = "/memories";
  static final String USER = "user";
  static final String PROJECT = "project";
  static final String INDEX = "MEMORY.md";

  /** A failure the model or the user should read, as is. */
  static final class MemoryException extends Exception {
    private static final long serialVersionUID = 1L;

    MemoryException(String message) {
      super(message);
    }
  }

  /** A resolved virtual path: its scope folder, the file or folder on disk, and the path as the model writes it. */
  record Target(Path scope, Path file, String virtual) {
    boolean isScope() {
      return file.equals(scope);
    }
  }

  private final Path dir;
  private final int maxFileChars;

  MemoryStore(Path dir, int maxFileChars) {
    this.dir = dir;
    this.maxFileChars = maxFileChars;
  }

  Path userDir() {
    return dir.resolve(USER);
  }

  /** The memory folder of the project in {@code root}: its git root's (or its own) name and a digest of its path. */
  Path projectDir(Path root) {
    Path key = gitRoot(root.toAbsolutePath().normalize());
    try {
      key = key.toRealPath();
    } catch (IOException e) {
      // a folder that does not exist (yet) keeps its normalized path
    }
    String name = key.getFileName() == null ? "root" : key.getFileName().toString();
    name = name.replaceAll("[^A-Za-z0-9._-]", "-");
    if (name.length() > 40) name = name.substring(0, 40);
    return dir.resolve("projects").resolve(name + "-" + digest(key.toString()));
  }

  private static Path gitRoot(Path start) {
    for (Path d = start; d != null; d = d.getParent()) {
      if (Files.exists(d.resolve(".git"))) return d;
    }
    return start;
  }

  private static String digest(String s) {
    try {
      byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * {@code virtual} on disk. Accepts {@code /memories}, {@code /memories/user[/...]} and
   * {@code /memories/project[/...]} (also without the leading {@code /memories/}); refuses {@code ..}, empty
   * segments and anything that leaves its scope. {@code project} is the project's memory folder.
   */
  Target resolve(String virtual, Path project) throws MemoryException {
    String v = virtual == null ? "" : virtual.strip().replace('\\', '/');
    if (v.isEmpty() || v.equals("/")) v = ROOT;
    if (!v.startsWith("/")) v = ROOT + "/" + v;
    if (v.indexOf('\0') >= 0) throw new MemoryException("bad path: " + virtual);
    while (v.length() > 1 && v.endsWith("/")) v = v.substring(0, v.length() - 1);
    if (!v.equals(ROOT) && !v.startsWith(ROOT + "/")) {
      throw new MemoryException("paths start with " + ROOT + "/user or " + ROOT + "/project, not " + virtual);
    }
    if (v.equals(ROOT)) return new Target(dir, dir, ROOT);
    String[] parts = v.substring(ROOT.length() + 1).split("/");
    for (String p : parts) {
      if (p.isEmpty() || p.equals(".") || p.equals("..")) throw new MemoryException("bad path: " + virtual);
    }
    Path scope = switch (parts[0]) {
      case USER -> userDir();
      case PROJECT -> project;
      default -> throw new MemoryException("unknown scope '" + parts[0] + "': use " + ROOT + "/user or " + ROOT
          + "/project");
    };
    Path file = scope;
    for (int i = 1; i < parts.length; i++) {
      file = file.resolve(parts[i]);
      if (Files.isSymbolicLink(file)) throw new MemoryException("not a memory file: " + virtual);
    }
    if (!file.normalize().startsWith(scope)) throw new MemoryException("bad path: " + virtual);
    return new Target(scope, file, v);
  }

  /** A folder as a list of its memory files (with their descriptions), or a file with line numbers. */
  synchronized String view(Target t, int from, int to, Path project) throws MemoryException {
    if (t.file().equals(dir)) {
      return listing(resolve(ROOT + "/" + USER, project)) + "\n" + listing(resolve(ROOT + "/" + PROJECT, project));
    }
    if (Files.isDirectory(t.file(), LinkOption.NOFOLLOW_LINKS) || t.isScope()) return listing(t);
    String text = read(t);
    String[] lines = text.split("\n", -1);
    int n = text.endsWith("\n") ? lines.length - 1 : lines.length;
    int start = Math.max(1, from);
    int end = to <= 0 ? n : Math.min(n, to);
    if (n == 0) return "(empty file)";
    if (start > n) throw new MemoryException(t.virtual() + " has " + n + " lines");
    StringBuilder out = new StringBuilder();
    for (int i = start; i <= end; i++) {
      out.append(String.format(Locale.ROOT, "%6d\t%s", i, lines[i - 1]));
      if (i < end) out.append('\n');
    }
    return out.toString();
  }

  private String listing(Target t) {
    List<String> lines = new ArrayList<>();
    lines.add(t.virtual() + "/");
    if (Files.isDirectory(t.file())) {
      try (Stream<Path> files = Files.walk(t.file())) {
        files.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.toString().endsWith(".md")).sorted()
            .forEach(p -> {
              String rel = t.file().relativize(p).toString().replace('\\', '/');
              String desc = description(p);
              lines.add("- " + t.virtual() + "/" + rel + " (" + size(p) + " chars)" + (desc == null ? "" : ": " + desc));
            });
      } catch (IOException | java.io.UncheckedIOException e) {
        lines.add("(cannot list: " + e.getMessage() + ")");
      }
    }
    if (lines.size() == 1) lines.add("(no memories)");
    return String.join("\n", lines);
  }

  private static long size(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8).length();
    } catch (IOException e) {
      return 0;
    }
  }

  /** The {@code description:} of a file's front matter; null when it has none. */
  private static String description(Path p) {
    try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
      List<String> head = lines.limit(12).toList();
      if (head.isEmpty() || !head.get(0).strip().equals("---")) return null;
      for (int i = 1; i < head.size(); i++) {
        String l = head.get(i).strip();
        if (l.equals("---")) return null;
        if (l.startsWith("description:")) {
          String d = l.substring("description:".length()).strip();
          if (d.length() > 1 && (d.startsWith("\"") && d.endsWith("\"") || d.startsWith("'") && d.endsWith("'"))) {
            d = d.substring(1, d.length() - 1);
          }
          return d.isEmpty() ? null : d;
        }
      }
      return null;
    } catch (IOException | java.io.UncheckedIOException e) {
      return null;
    }
  }

  /** Writes {@code text} to {@code t}, creating or replacing it. Returns whether it was created. */
  synchronized boolean create(Target t, String text) throws MemoryException {
    checkFile(t);
    if (Files.isDirectory(t.file())) throw new MemoryException(t.virtual() + " is a folder");
    checkSize(t, text);
    boolean created = !Files.exists(t.file());
    write(t, text);
    return created;
  }

  /** Replaces the only occurrence of {@code old} in {@code t} with {@code now}. */
  synchronized void replace(Target t, String old, String now) throws MemoryException {
    checkFile(t);
    if (old == null || old.isEmpty()) throw new MemoryException("old_str is empty");
    String text = read(t);
    int at = text.indexOf(old);
    if (at < 0) throw new MemoryException("old_str is not in " + t.virtual() + "; view the file and copy the text exactly");
    int count = 0;
    for (int i = at; i >= 0; i = text.indexOf(old, i + 1)) count++;
    if (count > 1) {
      throw new MemoryException("old_str is in " + t.virtual() + " " + count + " times; include more of the text "
          + "around it so it is unique");
    }
    String next = text.substring(0, at) + (now == null ? "" : now) + text.substring(at + old.length());
    checkSize(t, next);
    write(t, next);
  }

  /** Deletes the file {@code t}, and the folders it leaves empty up to its scope. */
  synchronized void delete(Target t) throws MemoryException {
    if (t.isScope()) throw new MemoryException("cannot delete a whole scope; delete its files");
    if (Files.isDirectory(t.file(), LinkOption.NOFOLLOW_LINKS)) {
      throw new MemoryException(t.virtual() + " is a folder; delete its files");
    }
    try {
      if (!Files.deleteIfExists(t.file())) throw new MemoryException("no such memory: " + t.virtual());
      for (Path d = t.file().getParent(); d != null && !d.equals(t.scope()) && d.startsWith(t.scope());
          d = d.getParent()) {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(d)) {
          if (s.iterator().hasNext()) break;
        }
        Files.delete(d);
      }
    } catch (IOException e) {
      throw new MemoryException("cannot delete " + t.virtual() + ": " + e.getMessage());
    }
  }

  /**
   * Deletes {@code t} and the lines of its scope's {@code MEMORY.md} that link to it ({@code (name.md)}). Returns
   * how many index lines were removed.
   */
  synchronized int forget(Target t) throws MemoryException {
    delete(t);
    Path index = t.scope().resolve(INDEX);
    if (t.file().equals(index) || !Files.isRegularFile(index)) return 0;
    String rel = t.scope().relativize(t.file()).toString().replace('\\', '/');
    try {
      String text = Files.readString(index, StandardCharsets.UTF_8);
      List<String> kept = new ArrayList<>();
      int removed = 0;
      for (String line : text.split("\n", -1)) {
        if (line.contains("(" + rel + ")")) {
          removed++;
        } else {
          kept.add(line);
        }
      }
      if (removed > 0) write(new Target(t.scope(), index, ROOT), String.join("\n", kept));
      return removed;
    } catch (IOException e) {
      throw new MemoryException("deleted " + t.virtual() + " but cannot update " + INDEX + ": " + e.getMessage());
    }
  }

  /** The index of a scope folder, at most {@code max} characters cut at a line; null when there is none. */
  synchronized String index(Path scope, int max) {
    Path index = scope.resolve(INDEX);
    String text;
    try {
      if (Files.isSymbolicLink(index)) return null;
      text = Files.readString(index, StandardCharsets.UTF_8).strip();
    } catch (NoSuchFileException e) {
      return null;
    } catch (IOException e) {
      return "(cannot read " + INDEX + ": " + e.getMessage() + ")";
    }
    if (text.isEmpty()) return null;
    if (text.length() <= max) return text;
    int cut = text.lastIndexOf('\n', max);
    if (cut <= 0) cut = max;
    return text.substring(0, cut) + "\n[index cut: " + (text.length() - cut) + " more chars; view " + INDEX
        + " for the rest]";
  }

  private String read(Target t) throws MemoryException {
    try {
      return Files.readString(t.file(), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      throw new MemoryException("no such memory: " + t.virtual());
    } catch (IOException e) {
      throw new MemoryException("cannot read " + t.virtual() + ": " + e.getMessage());
    }
  }

  private static void checkFile(Target t) throws MemoryException {
    if (t.isScope() || t.virtual().equals(ROOT)) throw new MemoryException("give a file path, such as "
        + ROOT + "/user/" + INDEX);
    if (!t.file().getFileName().toString().endsWith(".md")) {
      throw new MemoryException("memories are Markdown files: name it *.md, not " + t.virtual());
    }
  }

  private void checkSize(Target t, String text) throws MemoryException {
    if (text.length() > maxFileChars) {
      throw new MemoryException(t.virtual() + " would hold " + text.length() + " chars, more than " + maxFileChars
          + ": keep one fact per file and split it");
    }
  }

  private static void write(Target t, String text) throws MemoryException {
    Path file = t.file();
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, text, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new MemoryException("cannot save " + t.virtual() + ": " + e.getMessage());
    }
  }
}
