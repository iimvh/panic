package panic.planner;

import context.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import panic.tools.Workspace;

/**
 * The plan files: one markdown file per conversation, in the folder of its project ({@link Workspace#ROOT}, else
 * {@link PlannerConfig#root}) under {@link PlannerConfig#dir}, so the plan lives next to the code it is about. The
 * name is a slug of the plan's title and a short id of the conversation ({@code landing-page-c327b77b.md}); a
 * conversation keeps its file when the title changes. The first line records the conversation and the status:
 * {@code <!-- panic plan: conversation=<id> status=proposed|approved|rejected updated=<instant> -->}.
 *
 * Writes replace the file through a temporary file and an atomic rename, under one lock per plugin (plans are
 * written rarely, by a user's answer).
 */
final class PlanFiles {

  /** What a plan file says. */
  record Saved(Path path, String status, String body) {
    boolean approved() {
      return APPROVED.equals(status);
    }
  }

  static final String PROPOSED = "proposed";
  static final String APPROVED = "approved";
  static final String REJECTED = "rejected";

  private static final Pattern HEADER = Pattern.compile(
      "^<!-- panic plan: conversation=(\\S+) status=(\\w+) updated=(\\S+) -->\\R?");
  private static final int SLUG_CHARS = 40;

  private final Context ctx;
  private final PlannerConfig cfg;
  private final Path fallback;
  private final Object lock = new Object();

  PlanFiles(Context ctx, PlannerConfig cfg) {
    this.ctx = ctx;
    this.cfg = cfg;
    this.fallback = Path.of(cfg.root()).toAbsolutePath().normalize();
  }

  /** The folder of {@code conversationId}'s plans: its project's, else the configured root. */
  Path dir(String conversationId) {
    Path root = fallback;
    try {
      root = Workspace.root(ctx, conversationId, fallback);
    } catch (RuntimeException e) {
      // a failing workspace lookup must not lose the plan: keep the configured root
    }
    return root.resolve(cfg.dir()).normalize();
  }

  /** The file of {@code conversationId}, if it has one. */
  Optional<Path> find(String conversationId) {
    Path dir = dir(conversationId);
    String suffix = shortId(conversationId) + ".md";
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + suffix)) {
      for (Path f : files) {
        String name = f.getFileName().toString();
        if (name.equals(suffix) || name.endsWith("-" + suffix)) return Optional.of(f);
      }
    } catch (NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  /** The plan of {@code conversationId}, if a file for it exists and can be read. */
  Optional<Saved> read(String conversationId) {
    Optional<Path> file = find(conversationId);
    if (file.isEmpty()) return Optional.empty();
    String text;
    try {
      text = Files.readString(file.get(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return Optional.empty();
    }
    Matcher m = HEADER.matcher(text);
    if (!m.find()) return Optional.of(new Saved(file.get(), PROPOSED, text.strip()));
    return Optional.of(new Saved(file.get(), m.group(2), text.substring(m.end()).strip()));
  }

  /**
   * Writes the plan of {@code conversationId} with {@code status}, replacing its file (the name is kept once
   * chosen), and returns where. {@code feedback} is added for a rejected plan.
   */
  Path write(String conversationId, String plan, List<String> items, List<String> criteria, String status,
      String feedback) throws IOException {
    StringBuilder out = new StringBuilder("<!-- panic plan: conversation=").append(conversationId.replaceAll("\\s", "_"))
        .append(" status=").append(status).append(" updated=")
        .append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" -->\n\n");
    out.append(plan.strip()).append('\n');
    if (!items.isEmpty()) {
      out.append("\n## Steps\n\n");
      for (int i = 0; i < items.size(); i++) out.append(i + 1).append(". ").append(items.get(i)).append('\n');
    }
    if (criteria != null && !criteria.isEmpty()) {
      out.append("\n## Acceptance criteria\n\n");
      for (String c : criteria) out.append("- ").append(c).append('\n');
    }
    if (feedback != null && !feedback.isBlank()) {
      out.append("\n## Feedback\n\n> ").append(feedback.strip().replace("\n", "\n> ")).append('\n');
    }
    synchronized (lock) {
      Path file = find(conversationId).orElseGet(() -> dir(conversationId).resolve(fileName(plan, conversationId)));
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, out, StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return file;
    }
  }

  /** Changes the status line of the plan of {@code conversationId}, keeping its text. */
  Optional<Path> mark(String conversationId, String status) throws IOException {
    synchronized (lock) {
      Optional<Saved> saved = read(conversationId);
      if (saved.isEmpty()) return Optional.empty();
      String header = "<!-- panic plan: conversation=" + conversationId.replaceAll("\\s", "_") + " status=" + status
          + " updated=" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + " -->\n\n";
      Path file = saved.get().path();
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, header + saved.get().body() + "\n", StandardCharsets.UTF_8);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      return Optional.of(file);
    }
  }

  /** {@code <slug of the title>-<short id>.md}, or {@code <short id>.md} when the title gives no slug. */
  static String fileName(String plan, String conversationId) {
    String slug = slug(title(plan));
    return (slug.isEmpty() ? "" : slug + "-") + shortId(conversationId) + ".md";
  }

  /** The first heading of the plan, else its first non-blank line. */
  static String title(String plan) {
    String first = null;
    for (String line : plan.split("\\R")) {
      String l = line.strip();
      if (l.isEmpty()) continue;
      if (l.startsWith("#")) return l.replaceFirst("^#+\\s*", "");
      if (first == null) first = l;
    }
    return first == null ? "" : first;
  }

  /** Lowercase ASCII words joined by dashes, accents removed ({@code Trang chủ đẹp} → {@code trang-chu-dep}). */
  static String slug(String text) {
    String ascii = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "");
    String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    if (slug.length() > SLUG_CHARS) slug = slug.substring(0, SLUG_CHARS).replaceAll("-+$", "");
    return slug;
  }

  /** The first 8 safe characters of the id, or a hash of it when it has none to spare. */
  static String shortId(String conversationId) {
    String safe = conversationId.replaceAll("[^A-Za-z0-9_-]", "");
    if (safe.length() >= 4 && safe.equals(conversationId)) return safe.substring(0, Math.min(8, safe.length()));
    return String.format("%08x", conversationId.hashCode());
  }
}
