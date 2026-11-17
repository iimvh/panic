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
 * File tools confined to a workspace root: {@code read_file} and {@code list_dir} (risk read), {@code write_file}
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
          "Read a text file of the workspace. Long files are cut; continue with offset (1-based line number).",
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
      int limit = args.path("limit").asInt(Integer.MAX_VALUE);
      try (InputStream in = Files.newInputStream(file)) {
        byte[] head = in.readNBytes(8192);
        for (byte b : head) {
          if (b == 0) return ToolResult.error(path + " is a binary file");
        }
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      StringBuilder out = new StringBuilder();
      int line = 0;
      int taken = 0;
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
        String text;
        while ((text = reader.readLine()) != null) {
          line++;
          if (line % 1000 == 0) ctx.signal().throwIfCancelled();
          if (line < offset) continue;
          if (taken == limit) {
            return ToolResult.ok(out + "… [more lines; continue with offset=" + line + "]");
          }
          if (out.length() + text.length() + 1 > cfg.maxBytes()) {
            return ToolResult.ok(out + "… [truncated at " + cfg.maxBytes() + " chars; continue with offset="
                + line + "]");
          }
          out.append(text).append('\n');
          taken++;
        }
      } catch (IOException e) {
        return ToolResult.error("cannot read " + path + ": " + e.getMessage());
      }
      if (taken == 0 && line > 0 && offset > line) {
        return ToolResult.error(path + " has only " + line + " lines");
      }
      return ToolResult.ok(out.toString());
    }
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
      return ToolResult.ok((append ? "appended " : "wrote ") + bytes.length + " bytes to " + path);
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
              + "indentation included) and occur exactly once, with new_str. Include enough surrounding lines to make "
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
      return ToolResult.ok("edited " + path + ": replaced " + replaced + (replaced == 1 ? " occurrence" : " occurrences")
          + " (first at line " + line + ")");
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
