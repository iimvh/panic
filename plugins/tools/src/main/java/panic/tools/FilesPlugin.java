package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import panic.util.Json;
import panic.llm.ToolSpec;

/**
 * File tools confined to a workspace root: {@code read_file}, {@code list_dir}, {@code glob} and {@code grep} (risk
 * read; the searches are pure Java, see {@link Search}), {@code write_file}
 * and {@code edit_file} (risk write, so the permission policy asks the user by default). {@code edit_file} replaces
 * an exact string, like the {@code str_replace} command of Anthropic's text editor tool and Claude Code's Edit tool,
 * so a small change does not cost the model a rewrite of the whole file. A path is resolved
 * against the root and must stay inside it once normalized and once symlinks are followed, so {@code ../},
 * absolute paths and links pointing out are refused. A write never follows a link at the target itself. The
 * root is resolved on every call, so it may be created after the plugin starts.
 *
 * The root is the project of the conversation the user talks to ({@link Workspace#ROOT}, asked with
 * {@link ToolContext#origin()} so a sub-agent works where its parent does), or {@link FilesConfig#root} when it
 * has none. Requires nothing; changing the config re-registers the tools with the new default root.
 */
public final class FilesPlugin implements ConfigPlugin<FilesConfig> {

  @Override
  public Disposable apply(Context ctx, FilesConfig cfg) {
    Path root = Path.of(cfg.root()).toAbsolutePath().normalize();
    Tools.register(ctx, new ReadFile(ctx, root, cfg));
    Tools.register(ctx, new ListDir(ctx, root, cfg));
    Tools.register(ctx, new Glob(ctx, root, cfg));
    Tools.register(ctx, new Grep(ctx, root, cfg));
    Tools.register(ctx, new WriteFile(ctx, root, cfg));
    Tools.register(ctx, new EditFile(ctx, root, cfg));
    return null;
  }

  /** A tool failure the model should read, raised from deep inside a helper. */
  private static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Refused(String message) {
      super(message, null, false, false);
    }
  }

  /** {@code relative} inside {@code root}, following symlinks; refuses anything that ends up outside. */
  static Path resolve(Path root, String relative) {
    Path realRoot;
    try {
      realRoot = root.toRealPath();
    } catch (IOException e) {
      throw new Refused("workspace root does not exist: " + root);
    }
    Path target;
    try {
      target = realRoot.resolve(relative).normalize();
    } catch (InvalidPathException e) {
      throw new Refused("invalid path: " + relative);
    }
    if (!target.startsWith(realRoot)) throw new Refused("path is outside the workspace: " + relative);
    try {
      Path real = target.toRealPath();
      if (!real.startsWith(realRoot)) throw new Refused("path is outside the workspace: " + relative);
      return real;
    } catch (NoSuchFileException e) {
      throw new Refused("not found: " + relative);
    } catch (IOException e) {
      throw new Refused("cannot open " + relative + ": " + e.getMessage());
    }
  }

  /**
   * Where to write {@code relative} inside {@code root}. The deepest existing directory on the way must be
   * inside the root once symlinks are followed; missing directories below it are created. The target itself
   * must not be a symlink or a directory.
   */
  static Path resolveForWrite(Path root, String relative) {
    Path realRoot;
    try {
      realRoot = root.toRealPath();
    } catch (IOException e) {
      throw new Refused("workspace root does not exist: " + root);
    }
    Path target;
    try {
      target = realRoot.resolve(relative).normalize();
    } catch (InvalidPathException e) {
      throw new Refused("invalid path: " + relative);
    }
    if (!target.startsWith(realRoot) || target.equals(realRoot)) {
      throw new Refused("path is outside the workspace: " + relative);
    }
    Path existing = target.getParent();
    while (!Files.exists(existing)) existing = existing.getParent();
    Path parent;
    try {
      Path realExisting = existing.toRealPath();
      if (!realExisting.startsWith(realRoot)) throw new Refused("path is outside the workspace: " + relative);
      if (!Files.isDirectory(realExisting)) throw new Refused("not a directory: " + realRoot.relativize(existing));
      parent = realExisting.resolve(existing.relativize(target.getParent()));
      Files.createDirectories(parent);
    } catch (IOException e) {
      throw new Refused("cannot create the directories of " + relative + ": " + e.getMessage());
    }
    Path file = parent.resolve(target.getFileName());
    if (Files.isSymbolicLink(file)) throw new Refused(relative + " is a symbolic link; refusing to write through it");
    if (Files.isDirectory(file)) throw new Refused(relative + " is a directory");
    return file;
  }

  private static ToolResult guarded(Supplier<ToolResult> body) {
    try {
      return body.get();
    } catch (Refused e) {
      return ToolResult.error(e.getMessage());
    }
  }

  private record ReadFile(Context host, Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("read_file",
          "Read a text file of the workspace. Each line comes as its line number, a tab, then the line as it is in "
              + "the file (like cat -n); the number and tab are not part of the file. At most " + cfg.maxLines()
              + " lines unless you give limit; continue with offset (1-based line number). To find which file or "
              + "lines to read, search first with grep or glob.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "path":{"type":"string","description":"path relative to the workspace root"},
                "offset":{"type":"integer","minimum":1,"description":"first line to read (1-based)"},
                "limit":{"type":"integer","minimum":1,"description":"most lines to read"}},
               "required":["path"],"additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> read(args, ctx));
    }

    private ToolResult read(JsonNode args, ToolContext ctx) {
      String path = args.path("path").asText();
      Path file = resolve(Workspace.root(host, ctx.origin(), root), path);
      if (Files.isDirectory(file)) return ToolResult.error(path + " is a directory; use list_dir");
      int offset = args.path("offset").asInt(1);
      int limit = args.path("limit").asInt(cfg.maxLines());
      try (InputStream in = Files.newInputStream(file)) {
        byte[] head = in.readNBytes(8192);
        for (byte b : head) {
          if (b == 0) return ToolResult.error(path + " is a binary file");
        }
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      Content whole;
      try {
        whole = Content.of(file);
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      StringBuilder out = new StringBuilder();
      List<String> shownLines = new ArrayList<>();
      int line = 0;
      String more = null;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
        String text;
        while ((text = reader.readLine()) != null) {
          line++;
          if (line % 1000 == 0) ctx.signal().throwIfCancelled();
          if (line < offset) continue;
          if (shownLines.size() == limit) {
            int remaining = whole.lines() - line + 1;
            more = "… [" + remaining + (remaining == 1 ? " more line" : " more lines") + "; continue with offset="
                + line + "]";
            break;
          }
          String shown = numbered(line, text);
          if (out.length() + shown.length() + 1 > cfg.maxBytes()) {
            more = "… [truncated at " + cfg.maxBytes() + " chars; continue with offset=" + line + "]";
            break;
          }
          out.append(shown).append('\n');
          shownLines.add(text);
        }
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      if (shownLines.isEmpty() && line > 0 && offset > line) {
        return ToolResult.error(path + " has only " + line + " lines");
      }
      ToolResult result = ToolResult.ok(more == null ? out.toString() : out + more);
      if (shownLines.isEmpty()) return result;
      int last = offset + shownLines.size() - 1;
      Path workspace = Workspace.root(host, ctx.origin(), root);
      return result.about(Resource.view(key(workspace, file), whole.version(), offset, last),
          Outline.of(path, shownLines, offset, whole.lines(), result.content().length()));
    }
  }

  /**
   * A file's version (the first 16 hex digits of the SHA-256 of its bytes) and its line count, read as a stream:
   * what {@link Resource} needs to tell that two reads saw the same content.
   */
  record Content(String version, int lines) {

    static Content of(Path file) throws IOException {
      java.security.MessageDigest sha = sha256();
      long newlines = 0;
      long size = 0;
      int lastByte = '\n';
      byte[] buf = new byte[64 * 1024];
      try (InputStream in = Files.newInputStream(file)) {
        int n;
        while ((n = in.read(buf)) > 0) {
          sha.update(buf, 0, n);
          for (int i = 0; i < n; i++) {
            if (buf[i] == '\n') newlines++;
          }
          lastByte = buf[n - 1];
          size += n;
        }
      }
      long lines = size == 0 ? 0 : newlines + (lastByte == '\n' ? 0 : 1);
      return new Content(hex(sha.digest()), (int) Math.min(Integer.MAX_VALUE, lines));
    }

    static String version(byte[] bytes) {
      return hex(sha256().digest(bytes));
    }

    private static java.security.MessageDigest sha256() {
      try {
        return java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException e) {
        throw new IllegalStateException("every JDK has SHA-256", e);
      }
    }

    private static String hex(byte[] digest) {
      return java.util.HexFormat.of().formatHex(digest, 0, 8);
    }
  }

  /** The {@link Resource} key of {@code file}: {@code file:} and its path inside the workspace, forward slashes. */
  static String key(Path root, Path file) {
    try {
      return "file:" + root.toRealPath().relativize(file).toString().replace('\\', '/');
    } catch (IOException | IllegalArgumentException e) {
      return "file:" + file.toString().replace('\\', '/');
    }
  }

  /** {@code text} as line {@code n} of {@code cat -n}: the number right-aligned in 6 columns, a tab, the line. */
  static String numbered(int n, String text) {
    return String.format(java.util.Locale.ROOT, "%6d\t%s", n, text);
  }

  /** A {@code cat -n} prefix (spaces, a number, a tab) at the start of a line. */
  private static final java.util.regex.Pattern LINE_PREFIX = java.util.regex.Pattern.compile("(?m)^ *\\d+\t");

  /**
   * {@code text} without the line-number prefixes {@code read_file} adds, when every line has one; null otherwise.
   * A model that copies them into {@code old_str} still means the file's text.
   */
  static String withoutLineNumbers(String text) {
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].isEmpty() && i == lines.length - 1) continue; // a trailing newline
      if (!LINE_PREFIX.matcher(lines[i]).lookingAt()) return null;
    }
    return LINE_PREFIX.matcher(text).replaceAll("");
  }

  private record ListDir(Context host, Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("list_dir", "List a directory of the workspace (directories end with /).",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "path":{"type":"string","description":"directory relative to the workspace root (default: the root)"}},
               "additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> list(args, ctx));
    }

    private ToolResult list(JsonNode args, ToolContext ctx) {
      String path = args.path("path").asText(".");
      Path dir = resolve(Workspace.root(host, ctx.origin(), root), path.isBlank() ? "." : path);
      if (!Files.isDirectory(dir)) return ToolResult.error(path + " is not a directory; use read_file");
      List<String> names = new ArrayList<>();
      try (Stream<Path> entries = Files.list(dir)) {
        entries.forEach(p -> names.add(p.getFileName() + (Files.isDirectory(p) ? "/" : "")));
      } catch (IOException e) {
        return ToolResult.error("cannot list " + path + ": " + e.getMessage());
      }
      ctx.signal().throwIfCancelled();
      names.sort(null);
      if (names.isEmpty()) return ToolResult.ok("(empty)");
      StringBuilder out = new StringBuilder();
      names.stream().limit(cfg.maxEntries()).forEach(n -> out.append(n).append('\n'));
      if (names.size() > cfg.maxEntries()) out.append("… ").append(names.size() - cfg.maxEntries()).append(" more\n");
      return ToolResult.ok(out.toString());
    }
  }

  /** Files whose path matches a glob, most recently changed first: where to look, before reading anything. */
  private record Glob(Context host, Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("glob",
          "Find files of the workspace by path pattern, most recently changed first (dependencies, build output "
              + "and .gitignore'd files are skipped). A pattern without / matches file names at any depth "
              + "(\"*.jsx\"); with /, the path from the search directory (\"src/**/*.ts\").",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "pattern":{"type":"string","description":"glob, e.g. *.java or src/**/Header*"},
                "path":{"type":"string","description":"directory to search, relative to the workspace root (default: the root)"}},
               "required":["pattern"],"additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> glob(args, ctx));
    }

    private ToolResult glob(JsonNode args, ToolContext ctx) {
      String pattern = args.path("pattern").asText();
      if (pattern.isBlank()) return ToolResult.error("pattern is empty");
      Path workspace = Workspace.root(host, ctx.origin(), root);
      String path = args.path("path").asText(".");
      Path base = resolve(workspace, path.isBlank() ? "." : path);
      java.nio.file.PathMatcher matcher;
      try {
        matcher = Search.glob(pattern);
      } catch (IllegalArgumentException e) {
        return ToolResult.error("bad pattern: " + e.getMessage());
      }
      List<Search.Found> found = new ArrayList<>();
      try {
        Path realBase = base;
        for (Search.Found f : Search.files(workspace, base, ctx.signal())) {
          Path fromBase = realBase.equals(f.file()) ? f.file().getFileName() : realBase.relativize(f.file());
          if (matcher.matches(fromBase)) found.add(f);
        }
      } catch (IOException e) {
        return ToolResult.error("cannot search " + path + ": " + e.getMessage());
      }
      if (found.isEmpty()) return ToolResult.ok("no files match " + pattern);
      found.sort((a, b) -> Long.compare(b.modified(), a.modified()));
      StringBuilder out = new StringBuilder();
      found.stream().limit(cfg.maxEntries()).forEach(f -> out.append(f.relative()).append('\n'));
      if (found.size() > cfg.maxEntries()) {
        out.append("… ").append(found.size() - cfg.maxEntries()).append(" more; narrow the pattern or the path\n");
      }
      return ToolResult.ok(out.toString());
    }
  }

  /**
   * Lines that match a regular expression, grouped by file with their line numbers: the model finds where
   * something is and reads only that part (read_file offset/limit), instead of reading whole files to look.
   */
  private record Grep(Context host, Path root, FilesConfig cfg) implements Tool {

    /** Characters of a matching line shown; minified files would otherwise flood the result. */
    static final int LINE_CHARS = 200;

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("grep",
          "Search the contents of the workspace's text files for a regular expression (Java syntax). Answers "
              + "each matching line as its line number and text, grouped by file (dependencies, build output, "
              + "binary and .gitignore'd files are skipped). Use it to find where something is, then read_file "
              + "with offset/limit to see that part.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "pattern":{"type":"string","description":"regular expression, e.g. formatPrice|toLocaleString"},
                "path":{"type":"string","description":"file or directory to search, relative to the workspace root (default: the root)"},
                "glob":{"type":"string","description":"only files matching this glob, e.g. *.jsx"},
                "ignore_case":{"type":"boolean","description":"case-insensitive (default false)"},
                "context":{"type":"integer","minimum":0,"maximum":10,"description":"lines shown before and after each match (default 0)"},
                "files_only":{"type":"boolean","description":"list the matching files only (default false)"}},
               "required":["pattern"],"additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> grep(args, ctx));
    }

    private ToolResult grep(JsonNode args, ToolContext ctx) {
      String pattern = args.path("pattern").asText();
      if (pattern.isEmpty()) return ToolResult.error("pattern is empty");
      java.util.regex.Pattern regex;
      try {
        regex = java.util.regex.Pattern.compile(pattern,
            args.path("ignore_case").asBoolean(false) ? java.util.regex.Pattern.CASE_INSENSITIVE
                | java.util.regex.Pattern.UNICODE_CASE : 0);
      } catch (java.util.regex.PatternSyntaxException e) {
        return ToolResult.error("bad regular expression: " + e.getDescription());
      }
      java.nio.file.PathMatcher only = null;
      if (!args.path("glob").asText("").isBlank()) {
        try {
          only = Search.glob(args.path("glob").asText());
        } catch (IllegalArgumentException e) {
          return ToolResult.error("bad glob: " + e.getMessage());
        }
      }
      int context = Math.max(0, Math.min(10, args.path("context").asInt(0)));
      boolean filesOnly = args.path("files_only").asBoolean(false);
      Path workspace = Workspace.root(host, ctx.origin(), root);
      String path = args.path("path").asText(".");
      Path base = resolve(workspace, path.isBlank() ? "." : path);
      List<Search.Found> files;
      try {
        files = Search.files(workspace, base, ctx.signal());
      } catch (IOException e) {
        return ToolResult.error("cannot search " + path + ": " + e.getMessage());
      }
      StringBuilder out = new StringBuilder();
      int matches = 0;
      int shown = 0;
      int matchedFiles = 0;
      int scanned = 0;
      for (Search.Found f : files) {
        if (only != null && !only.matches(Path.of(f.relative()))) continue;
        if (++scanned % 200 == 0) ctx.signal().throwIfCancelled();
        List<String> lines = Search.lines(f.file());
        if (lines == null) continue;
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
          if (regex.matcher(lines.get(i)).find()) hits.add(i);
        }
        if (hits.isEmpty()) continue;
        matchedFiles++;
        matches += hits.size();
        if (shown >= cfg.maxEntries()) continue;
        if (filesOnly) {
          out.append(f.relative()).append(" (").append(hits.size()).append(hits.size() == 1 ? " match" : " matches")
              .append(")\n");
          shown++;
          continue;
        }
        out.append(f.relative()).append('\n');
        int printedTo = -1;
        for (int hit : hits) {
          if (shown >= cfg.maxEntries()) break;
          int from = Math.max(printedTo + 1, hit - context);
          int to = Math.min(lines.size() - 1, hit + context);
          if (printedTo >= 0 && from > printedTo + 1) out.append("  --\n");
          for (int i = from; i <= to; i++) {
            String text = lines.get(i);
            if (text.length() > LINE_CHARS) text = text.substring(0, LINE_CHARS) + " …";
            out.append(String.format(java.util.Locale.ROOT, "%6d%s %s%n", i + 1, i == hit ? ":" : "-", text)
                .replace("\r\n", "\n"));
          }
          printedTo = to;
          shown++;
        }
      }
      if (matches == 0) return ToolResult.ok("no matches for " + pattern);
      if (shown < (filesOnly ? matchedFiles : matches)) {
        out.append("… ").append(matches).append(" matches in ").append(matchedFiles)
            .append(" files in all; narrow the pattern, path or glob\n");
      }
      return ToolResult.ok(out.toString());
    }
  }

  private record WriteFile(Context host, Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.WRITE;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("write_file",
          "Write a text file of the workspace, creating it (and its directories) or replacing all its content. "
          + "Use it for new files or a full rewrite; to change part of an existing file, use edit_file.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "path":{"type":"string","description":"path relative to the workspace root"},
                "content":{"type":"string","description":"the text to write"},
                "append":{"type":"boolean","description":"add to the end instead of replacing (default false)"}},
               "required":["path","content"],"additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> write(args, ctx));
    }

    private ToolResult write(JsonNode args, ToolContext ctx) {
      String path = args.path("path").asText();
      byte[] bytes = args.path("content").asText().getBytes(StandardCharsets.UTF_8);
      boolean append = args.path("append").asBoolean(false);
      if (bytes.length > cfg.maxWriteBytes()) {
        return ToolResult.error("content is " + bytes.length + " bytes; the most write_file takes is "
            + cfg.maxWriteBytes());
      }
      Path file = resolveForWrite(Workspace.root(host, ctx.origin(), root), path);
      ctx.signal().throwIfCancelled();
      try {
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
        return ToolResult.error("cannot write " + path + ": " + e.getMessage());
      }
      ToolResult done = ToolResult.ok((append ? "appended " : "wrote ") + bytes.length + " bytes to " + path);
      try {
        Path workspace = Workspace.root(host, ctx.origin(), root);
        String version = append ? Content.of(file).version() : Content.version(bytes);
        return done.about(Resource.write(key(workspace, file.toRealPath()), version), null);
      } catch (IOException e) {
        return done; // written; only the context bookkeeping is lost
      }
    }
  }

  private record EditFile(Context host, Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.WRITE;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("edit_file",
          "Change part of an existing text file: replace old_str, which must match the file exactly (whitespace and "
              + "indentation included) and occur exactly once, with new_str. Copy old_str from what follows the tab "
              + "of each read_file line: never include the line number and tab. Include enough surrounding lines to make "
              + "old_str unique, or set replace_all to change every occurrence. Read the file first so old_str is "
              + "copied, not guessed. Prefer this to write_file for any change to an existing file.",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "path":{"type":"string","description":"path relative to the workspace root"},
                "old_str":{"type":"string","description":"the exact text to replace"},
                "new_str":{"type":"string","description":"the text to put in its place (may be empty to delete)"},
                "replace_all":{"type":"boolean","description":"replace every occurrence (default false)"}},
               "required":["path","old_str","new_str"],"additionalProperties":false}"""));
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return guarded(() -> edit(args, ctx));
    }

    private ToolResult edit(JsonNode args, ToolContext ctx) {
      String path = args.path("path").asText();
      String oldStr = args.path("old_str").asText();
      String newStr = args.path("new_str").asText();
      boolean all = args.path("replace_all").asBoolean(false);
      if (oldStr.isEmpty()) return ToolResult.error("old_str is empty; to create a file use write_file");
      if (oldStr.equals(newStr)) return ToolResult.error("old_str and new_str are the same; nothing to change");
      Path workspace = Workspace.root(host, ctx.origin(), root);
      Path existing = resolve(workspace, path); // must exist, inside the root
      if (Files.isDirectory(existing)) return ToolResult.error(path + " is a directory");
      Path file = resolveForWrite(workspace, path); // and not be a link
      String text;
      try {
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes) {
          if (b == 0) return ToolResult.error(path + " is a binary file");
        }
        text = new String(bytes, StandardCharsets.UTF_8);
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      int count = count(text, oldStr);
      if (count == 0 && text.contains("\r\n") && !oldStr.contains("\r\n")) {
        // the model writes \n; the file uses \r\n
        String crlfOld = oldStr.replace("\n", "\r\n");
        if (count(text, crlfOld) > 0) {
          oldStr = crlfOld;
          newStr = newStr.replace("\n", "\r\n");
          count = count(text, oldStr);
        }
      }
      if (count == 0) {
        // poka-yoke: line numbers copied from read_file are not part of the file
        String bareOld = withoutLineNumbers(oldStr);
        String bareNew = bareOld == null ? null : withoutLineNumbers(newStr);
        if (bareOld != null && !bareOld.isEmpty() && count(text, bareOld) > 0) {
          oldStr = bareOld;
          if (bareNew != null) newStr = bareNew;
          count = count(text, oldStr);
        }
      }
      if (count == 0) {
        return ToolResult.error("old_str not found in " + path + ". It must match the file exactly, whitespace and "
            + "indentation included: read_file the part again and copy it.");
      }
      if (count > 1 && !all) {
        return ToolResult.error("old_str occurs " + count + " times in " + path + "; add surrounding lines to make it "
            + "unique, or set replace_all: true to change all of them.");
      }
      int line = 1 + (int) text.substring(0, text.indexOf(oldStr)).chars().filter(c -> c == '\n').count();
      String edited = all ? text.replace(oldStr, newStr) : replaceOnce(text, oldStr, newStr);
      byte[] out = edited.getBytes(StandardCharsets.UTF_8);
      if (out.length > cfg.maxWriteBytes()) {
        return ToolResult.error("the edited file would be " + out.length + " bytes; the most the file tools write is "
            + cfg.maxWriteBytes());
      }
      ctx.signal().throwIfCancelled();
      try {
        Files.write(file, out, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
        return ToolResult.error("cannot write " + path + ": " + e.getMessage());
      }
      int replaced = all ? count : 1;
      ToolResult done = ToolResult.ok("edited " + path + ": replaced " + replaced
          + (replaced == 1 ? " occurrence" : " occurrences") + " (first at line " + line + ")");
      return done.about(Resource.write(key(workspace, existing), Content.version(out)), null);
    }

    private static int count(String text, String part) {
      int n = 0;
      for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
      return n;
    }

    private static String replaceOnce(String text, String oldStr, String newStr) {
      int at = text.indexOf(oldStr);
      return text.substring(0, at) + newStr + text.substring(at + oldStr.length());
    }
  }
}
