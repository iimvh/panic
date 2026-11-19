package panic.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import core.Signal;

/**
 * The file walk behind {@code glob} and {@code grep}: every regular file under a directory of the workspace, in a
 * stable order, without what is never source (version control, dependencies, build output) nor what the root's
 * {@code .gitignore} names. Symlinks are not followed, so the walk stays inside the workspace. Pure Java, so it
 * needs no ripgrep on the machine (Windows included).
 */
final class Search {

  /** Skipped wherever they are, with or without a .gitignore: never what the model is looking for. */
  static final Set<String> ALWAYS_SKIPPED = Set.of(".git", ".hg", ".svn", "node_modules", ".gradle", ".panic",
      ".svelte-kit", ".next", ".venv", "__pycache__", ".idea", ".vscode");

  /** Files larger than this are not searched: logs, dumps and bundles, not code. */
  static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

  private Search() {
  }

  /** A file found: its path relative to the workspace root (forward slashes) and when it last changed. */
  record Found(Path file, String relative, long modified) {
  }

  /** The .gitignore rules of a root, the simple forms only (no negation): names, globs, and anchored paths. */
  static final class Ignore {

    private final List<PathMatcher> anywhere = new ArrayList<>();
    private final List<PathMatcher> anchored = new ArrayList<>();
    private final List<Boolean> anchoredDirOnly = new ArrayList<>();
    private final List<Boolean> anywhereDirOnly = new ArrayList<>();

    static Ignore of(Path root) {
      Ignore ignore = new Ignore();
      Path file = root.resolve(".gitignore");
      if (!Files.isRegularFile(file)) return ignore;
      try {
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          String line = raw.strip();
          if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
          boolean dirOnly = line.endsWith("/");
          if (dirOnly) line = line.substring(0, line.length() - 1);
          if (line.isEmpty()) continue;
          try {
            if (line.contains("/")) {
              String p = line.startsWith("/") ? line.substring(1) : line;
              ignore.anchored.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
              ignore.anchoredDirOnly.add(dirOnly);
            } else {
              ignore.anywhere.add(FileSystems.getDefault().getPathMatcher("glob:" + line));
              ignore.anywhereDirOnly.add(dirOnly);
            }
          } catch (IllegalArgumentException badPattern) {
            // a pattern Java's glob does not take: ignoring the rule only means searching more
          }
        }
      } catch (IOException unreadable) {
        return ignore;
      }
      return ignore;
    }

    boolean ignores(String relative, boolean dir) {
      Path rel = Path.of(relative);
      Path name = rel.getFileName();
      for (int i = 0; i < anywhere.size(); i++) {
        if ((dir || !anywhereDirOnly.get(i)) && name != null && anywhere.get(i).matches(name)) return true;
      }
      for (int i = 0; i < anchored.size(); i++) {
        if ((dir || !anchoredDirOnly.get(i)) && anchored.get(i).matches(rel)) return true;
      }
      return false;
    }
  }

  /** Every file under {@code base} (inside {@code root}), in path order. */
  static List<Found> files(Path root, Path base, Signal signal) throws IOException {
    Path realRoot = root.toRealPath();
    Ignore ignore = Ignore.of(realRoot);
    List<Found> out = new ArrayList<>();
    if (Files.isRegularFile(base)) {
      out.add(new Found(base, relative(realRoot, base), Files.getLastModifiedTime(base).toMillis()));
      return out;
    }
    Files.walkFileTree(base, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
        signal.throwIfCancelled();
        if (dir.equals(base)) return FileVisitResult.CONTINUE;
        String name = dir.getFileName().toString();
        if (ALWAYS_SKIPPED.contains(name) || ignore.ignores(relative(realRoot, dir), true)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
        String rel = relative(realRoot, file);
        if (!ignore.ignores(rel, false)) out.add(new Found(file, rel, attrs.lastModifiedTime().toMillis()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(Path file, IOException e) {
        return FileVisitResult.CONTINUE; // unreadable: not found, rather than failing the whole search
      }
    });
    out.sort((a, b) -> a.relative().compareTo(b.relative()));
    return out;
  }

  /** A glob that matches a relative path; a pattern without a slash matches the file name at any depth. */
  static PathMatcher glob(String pattern) {
    String p = pattern.startsWith("./") ? pattern.substring(2) : pattern;
    PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + p);
    if (p.contains("/")) return full;
    return path -> path.getFileName() != null && full.matches(path.getFileName());
  }

  /** The lines of {@code file}, or null when it is binary, too large or not UTF-8 text. */
  static List<String> lines(Path file) {
    try {
      if (Files.size(file) > MAX_FILE_BYTES) return null;
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = in.readAllBytes();
      }
      for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
        if (bytes[i] == 0) return null;
      }
      String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      return text.lines().toList();
    } catch (CharacterCodingException notText) {
      return null;
    } catch (IOException unreadable) {
      return null;
    }
  }

  static String relative(Path realRoot, Path file) {
    return realRoot.relativize(file).toString().replace('\\', '/');
  }
}
