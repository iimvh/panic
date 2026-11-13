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
import panic.llm.Json;
import panic.llm.ToolSpec;

/**
 * File tools confined to {@link FilesConfig#root}: {@code read_file} and {@code list_dir} (risk read) and
 * {@code write_file} (risk write, so the permission policy asks the user by default). A path is resolved
 * against the root and must stay inside it once normalized and once symlinks are followed, so {@code ../},
 * absolute paths and links pointing out are refused. A write never follows a link at the target itself. The
 * root is resolved on every call, so it may be created after the plugin starts.
 *
 * Requires nothing; changing the config re-registers the tools with the new root.
 */
public final class FilesPlugin implements ConfigPlugin<FilesConfig> {

  @Override
  public Disposable apply(Context ctx, FilesConfig cfg) {
    Path root = Path.of(cfg.root()).toAbsolutePath().normalize();
    Tools.register(ctx, new ReadFile(root, cfg));
    Tools.register(ctx, new ListDir(root, cfg));
    Tools.register(ctx, new WriteFile(root, cfg));
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

  private record ReadFile(Path root, FilesConfig cfg) implements Tool {

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
      Path file = resolve(root, path);
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

  private record ListDir(Path root, FilesConfig cfg) implements Tool {

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
      Path dir = resolve(root, path.isBlank() ? "." : path);
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

  private record WriteFile(Path root, FilesConfig cfg) implements Tool {

    @Override
    public Risk risk() {
      return Risk.WRITE;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec("write_file",
          "Write a text file of the workspace, creating it (and its directories) or replacing its content.",
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
      Path file = resolveForWrite(root, path);
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
}
