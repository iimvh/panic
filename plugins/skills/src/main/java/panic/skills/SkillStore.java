package panic.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds and reads skills: folders {@code <dir>/<name>/SKILL.md} (the Agent Skills format), in a project's skills
 * folders and then the user's global one. A {@code SKILL.md} starts with a front matter between two {@code ---}
 * lines; only the keys below are read, as simple {@code key: value} lines (no YAML library, see the README):
 * {@code name}, {@code description}, {@code disable-model-invocation}, {@code user-invocable},
 * {@code argument-hint}. Other keys are kept by name so that {@code skills} can show they are ignored.
 *
 * Files are read again only when their time or size changes. One store per plugin instance, so a config change
 * starts from an empty cache.
 */
final class SkillStore {

  static final String FILE = "SKILL.md";
  static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  static final int MAX_NAME = 64;
  /** How many supporting files a skill lists; more are counted, not named. */
  static final int MAX_FILES = 100;
  /** Keys of the Agent Skills format that change nothing here, so they are not reported as ignored. */
  private static final Set<String> QUIET = Set.of("license", "compatibility", "metadata");

  /**
   * One skill. {@code source} is the folder it was found in, as configured ({@code .claude/skills}, or
   * {@code global}); {@code ignored} the front matter keys this harness does not apply ({@code allowed-tools}...).
   */
  record Skill(String name, String description, String argumentHint, Path dir, String body, boolean model,
      boolean user, String source, List<String> ignored) {
  }

  private record Entry(long modified, long size, Skill skill) {
  }

  private final Consumer<String> warn;
  private final ConcurrentMap<Path, Entry> cache = new ConcurrentHashMap<>();
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  SkillStore(Consumer<String> warn) {
    this.warn = warn;
  }

  /**
   * The skills a chat working in {@code root} can use, by name: the project's folders in the order of
   * {@code dirs}, then {@code global}. The first skill of a name wins, so a project overrides the user's own.
   */
  Map<String, Skill> list(SkillsConfig cfg, Path root) {
    Map<Path, String> folders = new LinkedHashMap<>();
    Path project = root.toAbsolutePath().normalize();
    for (String d : cfg.dirs()) folders.putIfAbsent(project.resolve(d).normalize(), d);
    folders.putIfAbsent(Path.of(cfg.global()).toAbsolutePath().normalize(), "global");
    Map<String, Skill> out = new LinkedHashMap<>();
    for (Map.Entry<Path, String> f : folders.entrySet()) {
      for (Skill s : scan(f.getKey(), f.getValue())) {
        Skill first = out.putIfAbsent(s.name(), s);
        if (first != null) once("skill " + s.name() + " in " + s.dir() + " is hidden by " + first.dir());
      }
    }
    return out;
  }

  private List<Skill> scan(Path folder, String source) {
    if (!Files.isDirectory(folder)) return List.of();
    List<Path> dirs;
    try (Stream<Path> s = Files.list(folder)) {
      dirs = s.filter(p -> Files.isRegularFile(p.resolve(FILE))).sorted().toList();
    } catch (IOException | RuntimeException e) {
      once("cannot list " + folder + ": " + e);
      return List.of();
    }
    List<Skill> out = new ArrayList<>();
    for (Path dir : dirs) {
      Skill s = read(dir, source);
      if (s != null) out.add(s);
    }
    return out;
  }

  /** The skill in {@code dir}; null (with a warning, once) when it cannot be read or is not valid. */
  private Skill read(Path dir, String source) {
    Path file = dir.resolve(FILE);
    try {
      BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
      long modified = a.lastModifiedTime().toMillis();
      Entry e = cache.get(file);
      if (e != null && e.modified() == modified && e.size() == a.size()) return e.skill();
      Skill skill = parse(dir, source, Files.readString(file, StandardCharsets.UTF_8));
      cache.put(file, new Entry(modified, a.size(), skill));
      return skill;
    } catch (IOException | RuntimeException e) {
      cache.remove(file);
      once("cannot read " + file + ": " + e);
      return null;
    }
  }

  /** Parses a {@code SKILL.md}; null (with a warning, once) when its name or description is missing or wrong. */
  Skill parse(Path dir, String source, String text) {
    String folder = dir.getFileName().toString();
    Map<String, String> meta = new LinkedHashMap<>();
    String body = frontMatter(text.replace("\r\n", "\n"), meta);
    String name = meta.getOrDefault("name", folder);
    if (!name.equals(folder) || name.length() > MAX_NAME || !NAME.matcher(name).matches()) {
      once("skipped skill " + dir + ": name '" + name + "' must be lowercase letters, digits and dashes (at most "
          + MAX_NAME + ") and match its folder '" + folder + "'");
      return null;
    }
    String description = meta.getOrDefault("description", "").strip();
    if (description.isEmpty()) {
      once("skipped skill " + dir + ": its front matter has no description");
      return null;
    }
    List<String> ignored = meta.keySet().stream().filter(k -> !k.equals("name") && !k.equals("description")
        && !k.equals("disable-model-invocation") && !k.equals("user-invocable") && !k.equals("argument-hint")
        && !QUIET.contains(k)).toList();
    return new Skill(name, description, meta.get("argument-hint"), dir.toAbsolutePath().normalize(), body.strip(),
        !"true".equalsIgnoreCase(meta.get("disable-model-invocation")),
        !"false".equalsIgnoreCase(meta.get("user-invocable")), source, ignored);
  }

  /**
   * Reads the front matter of {@code text} into {@code meta} and returns the rest. Top-level {@code key: value}
   * lines; a value may be quoted, or {@code >} / {@code |} followed by indented lines (folded with spaces /
   * kept as lines). Nested or list values are skipped but their key is kept (as "").
   */
  static String frontMatter(String text, Map<String, String> meta) {
    if (!text.startsWith("---\n")) return text;
    int end = text.indexOf("\n---", 3);
    if (end < 0) return text;
    int after = text.indexOf('\n', end + 4);
    String body = after < 0 ? "" : text.substring(after + 1);
    String[] lines = text.substring(4, end).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.isBlank() || line.startsWith("#") || Character.isWhitespace(line.charAt(0)) || line.startsWith("-")) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) continue;
      String key = line.substring(0, colon).strip();
      String value = line.substring(colon + 1).strip();
      if (value.startsWith(">") || value.startsWith("|")) {
        boolean folded = value.startsWith(">");
        StringBuilder block = new StringBuilder();
        while (i + 1 < lines.length && (lines[i + 1].isBlank() || Character.isWhitespace(lines[i + 1].charAt(0)))) {
          String part = lines[++i].strip();
          if (block.length() > 0) block.append(folded ? " " : "\n");
          block.append(part);
        }
        value = block.toString().strip();
      } else if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
          || value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
        if (line.substring(colon + 1).strip().startsWith("\"")) value = value.replace("\\\"", "\"");
      }
      meta.put(key, value);
    }
    return body;
  }

  /** The skill's text for the model: where it is, its files, and its instructions with the arguments put in. */
  static String render(Skill s, String args, int max) {
    String body = s.body();
    String a = args == null ? "" : args.strip();
    boolean placeholder = body.contains("$ARGUMENTS");
    body = body.replace("$ARGUMENTS", a);
    if (!placeholder && !a.isEmpty()) body = body + "\n\nARGUMENTS: " + a;
    if (body.length() > max) {
      body = body.substring(0, max) + "\n[cut: " + (body.length() - max) + " more chars; read " + FILE
          + " with skill {name, file: \"" + FILE + "\"} if you need the rest]";
    }
    StringBuilder out = new StringBuilder("Skill: ").append(s.name()).append('\n');
    out.append("Base directory: ").append(s.dir()).append('\n');
    List<String> files = files(s.dir());
    if (!files.isEmpty()) {
      out.append("Files (read one with skill {name, file}; run scripts with the shell from the base directory):\n");
      for (String f : files) out.append("- ").append(f).append('\n');
    }
    return out.append('\n').append(body).toString();
  }

  /** The supporting files of a skill: relative paths with their size, symbolic links left out. */
  static List<String> files(Path dir) {
    List<String> out = new ArrayList<>();
    int[] more = {0};
    try (Stream<Path> s = Files.walk(dir, 4)) {
      s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).sorted().forEach(p -> {
        String rel = dir.relativize(p).toString().replace('\\', '/');
        if (rel.equals(FILE) || rel.startsWith(".")) return;
        if (out.size() >= MAX_FILES) {
          more[0]++;
          return;
        }
        long size;
        try {
          size = Files.size(p);
        } catch (IOException e) {
          size = -1;
        }
        out.add(rel + (size < 0 ? "" : " (" + size + " bytes)"));
      });
    } catch (IOException | RuntimeException e) {
      return out;
    }
    if (more[0] > 0) out.add("... and " + more[0] + " more");
    return out;
  }

  /**
   * The text of {@code file} inside the skill's folder. Refused: a path that leaves the folder (also through a
   * symbolic link), a missing or binary file, more than {@code max} characters (cut with a note).
   */
  static String readFile(Skill s, String file, int max) throws SkillException {
    if (file == null || file.isBlank()) throw new SkillException("give a file of the skill");
    Path dir = s.dir();
    Path p;
    try {
      p = dir.resolve(file.strip()).normalize();
    } catch (RuntimeException e) {
      throw new SkillException("bad path: " + file);
    }
    if (!p.startsWith(dir) || p.equals(dir)) throw new SkillException(file + " is not inside the skill " + s.name());
    try {
      if (!Files.isRegularFile(p)) throw new SkillException("no file " + file + " in the skill " + s.name());
      if (!p.toRealPath().startsWith(dir.toRealPath())) {
        throw new SkillException(file + " links outside the skill " + s.name());
      }
      if (Files.size(p) > (long) max * 4) throw new SkillException(file + " is too big (" + Files.size(p) + " bytes)");
      byte[] bytes = Files.readAllBytes(p);
      for (byte b : bytes) {
        if (b == 0) throw new SkillException(file + " is a binary file; use it from the shell");
      }
      String text = new String(bytes, StandardCharsets.UTF_8);
      if (text.length() > max) text = text.substring(0, max) + "\n[cut: " + (text.length() - max) + " more chars]";
      return text;
    } catch (IOException e) {
      throw new SkillException("cannot read " + file + ": " + e.getMessage());
    }
  }

  private void once(String message) {
    if (warned.add(message)) warn.accept("[skills] " + message);
  }

  /** A refusal the model or the user can act on. */
  static final class SkillException extends Exception {
    private static final long serialVersionUID = 1L;

    SkillException(String message) {
      super(message);
    }
  }
}
