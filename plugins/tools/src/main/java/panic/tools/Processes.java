package panic.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Helpers for tools that run processes ({@code exec} here, {@code check} of {@code plugins/verify}): how to run a
 * command line through the system shell, how to kill a process with everything it started, and how to read the end
 * of its output. Stateless; the processes belong to the tool that starts them.
 */
public final class Processes {

  private Processes() {
  }

  /**
   * {@code command} run by the system shell: {@code sh -c} (or {@code cmd /c} on Windows). For command lines a person
   * wrote for their own system (stop hooks); the agent's commands go through a {@link Shell}.
   */
  public static List<String> shell(String command) {
    return windows() ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
  }

  /**
   * The shell that runs the agent's command lines, and what the model is told about it. Models write POSIX shell, and
   * the {@code deny} rules and the permission rules read it, so {@link #resolve auto} picks a POSIX shell everywhere
   * it can: {@code sh} on Unix, Git Bash on Windows; PowerShell only when Windows has no Git Bash.
   *
   * @param name what the tool's description calls it
   * @param note how to write commands for it, for the description; empty when nothing needs saying
   * @param windows whether it is started on Windows, where Java builds one command line from the arguments
   */
  public record Shell(String name, Kind kind, String executable, String note, boolean windows) {

    public enum Kind { POSIX, CMD, POWERSHELL }

    /** The process command line that runs {@code line}. */
    public List<String> command(String line) {
      return switch (kind) {
        // Java does not escape quotes inside an argument on Windows: quote it ourselves, by the C runtime's rules
        case POSIX -> List.of(executable, "-c", windows ? quoteForWindows(line) : line);
        case CMD -> List.of(executable, "/c", line);
        case POWERSHELL -> List.of(executable, "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString((POWERSHELL_PRELUDE + line + POWERSHELL_EXIT)
                .getBytes(StandardCharsets.UTF_16LE)));
      };
    }

    /**
     * The shell {@code configured} names: {@code auto}, {@code sh}, {@code bash}, {@code cmd}, {@code powershell},
     * {@code pwsh}, or the path of a shell's executable.
     *
     * @throws IllegalArgumentException when it is not found
     */
    public static Shell resolve(String configured) {
      return resolve(configured, Processes.windows(), Processes::findGitBash);
    }

    static Shell resolve(String configured, boolean windows, Supplier<Path> gitBash) {
      String c = configured == null || configured.isBlank() ? "auto" : configured.strip();
      switch (c.toLowerCase(Locale.ROOT)) {
        case "auto" -> {
          if (!windows) return posix("sh", "sh");
          Path bash = gitBash.get();
          return bash != null ? gitBash(bash) : powershell("powershell");
        }
        case "sh", "bash" -> {
          if (!windows) return posix(c, c);
          Path bash = gitBash.get();
          if (bash == null) throw new IllegalArgumentException("shell: Git Bash not found; give the path of bash.exe");
          return gitBash(bash);
        }
        case "cmd" -> {
          return cmd("cmd");
        }
        case "powershell", "pwsh" -> {
          return powershell(c);
        }
        default -> {
          Path path = Path.of(c);
          if (!Files.isRegularFile(path)) throw new IllegalArgumentException("shell: not found: " + c);
          String file = path.getFileName().toString().toLowerCase(Locale.ROOT);
          if (file.equals("cmd.exe")) return cmd(c);
          if (file.equals("powershell.exe") || file.startsWith("pwsh")) return powershell(c);
          return windows ? gitBash(path) : posix(file, c);
        }
      }
    }

    private static Shell posix(String name, String executable) {
      return new Shell(name, Kind.POSIX, executable, "", false);
    }

    private static Shell gitBash(Path bash) {
      return new Shell("bash (Git Bash on Windows)", Kind.POSIX, bash.toString(),
          "Write POSIX shell; Windows paths work as C:/x or /c/x.", true);
    }

    private static Shell cmd(String executable) {
      return new Shell("cmd.exe (Windows)", Kind.CMD, executable,
          "Write cmd syntax, not POSIX shell: dir, type, copy, del, && between commands.", true);
    }

    private static Shell powershell(String executable) {
      return new Shell("PowerShell (Windows)", Kind.POWERSHELL, executable,
          "Write PowerShell, not POSIX shell: separate commands with ; and use cmdlets (Get-ChildItem, "
              + "Get-Content, Remove-Item -Recurse).", true);
    }
  }

  /** Output in UTF-8 (the log is read as UTF-8), and errors do not stop the script. */
  private static final String POWERSHELL_PRELUDE =
      "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $OutputEncoding=[Text.Encoding]::UTF8\n";
  /** The exit status of the last command: a native program's code, else 1 when a cmdlet failed. */
  private static final String POWERSHELL_EXIT =
      "\nif (-not $?) { if ($LASTEXITCODE) { exit $LASTEXITCODE } else { exit 1 } }";

  /**
   * {@code s} as one argument of a Windows command line, by the rules of the C runtime that MSYS programs share:
   * in quotes, a quote as {@code \"}, and backslashes doubled where a quote follows. Java leaves an argument that is
   * already quoted alone, unless it ends in an escaped quote, so a trailing backslash gets a newline after it.
   */
  static String quoteForWindows(String s) {
    if (s.endsWith("\\")) s = s + "\n";
    StringBuilder b = new StringBuilder("\"");
    int backslashes = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\') {
        backslashes++;
        continue;
      }
      if (c == '"') {
        b.append("\\".repeat(backslashes * 2 + 1)).append('"');
      } else {
        b.append("\\".repeat(backslashes)).append(c);
      }
      backslashes = 0;
    }
    return b.append("\\".repeat(backslashes * 2)).append('"').toString();
  }

  /**
   * Git for Windows' {@code bin\bash.exe} (the wrapper that sets up its PATH, not {@code usr\bin\bash.exe}): first
   * where installers put it, then next to the {@code git} that {@code where} finds. Never {@code System32\bash.exe},
   * which is WSL and sees a Linux file system. Null when there is none.
   */
  static Path findGitBash() {
    String home = System.getProperty("user.home", "");
    List<Path> roots = new ArrayList<>();
    roots.add(Path.of("C:\\Program Files\\Git"));
    roots.add(Path.of("C:\\Program Files (x86)\\Git"));
    if (!home.isEmpty()) {
      roots.add(Path.of(home, "AppData", "Local", "Programs", "Git"));
      roots.add(Path.of(home, "scoop", "apps", "git", "current"));
    }
    for (Path root : roots) {
      Path bash = root.resolve("bin").resolve("bash.exe");
      if (Files.isRegularFile(bash)) return bash;
    }
    for (Path git : where("git")) {
      // ...\Git\cmd\git.exe or ...\Git\mingw64\bin\git.exe
      for (Path up = git.getParent(); up != null && up.getNameCount() > 0; up = up.getParent()) {
        Path bash = up.resolve("bin").resolve("bash.exe");
        if (Files.isRegularFile(bash) && Files.isDirectory(up.resolve("usr").resolve("bin"))) return bash;
      }
    }
    return null;
  }

  /** The files {@code where.exe} finds for {@code program} on the PATH; empty when it fails. */
  private static List<Path> where(String program) {
    Path out = null;
    Process p = null;
    try {
      out = Files.createTempFile("panic-where-", ".txt");
      p = new ProcessBuilder("where", program).redirectOutput(out.toFile())
          .redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
          .start();
      if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) return List.of();
      List<Path> found = new ArrayList<>();
      for (String line : Files.readAllLines(out, Charset.defaultCharset())) {
        if (!line.isBlank()) found.add(Path.of(line.strip()));
      }
      return found;
    } catch (IOException | RuntimeException e) {
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } finally {
      if (p != null) p.destroyForcibly();
      if (out != null) {
        try {
          Files.deleteIfExists(out);
        } catch (IOException ignored) {
          // a temporary file left behind is harmless
        }
      }
    }
  }

  /** Kills {@code p} and everything it started, and waits briefly for it to go. */
  public static void kill(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
    try {
      p.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Where a process with no input reads from. */
  public static File nullDevice() {
    return new File(windows() ? "NUL" : "/dev/null");
  }

  /**
   * The last {@code max} characters of {@code log}, after a newline: failures are usually at the end. Empty when
   * there was no output.
   */
  public static String tail(Path log, int max) {
    String text;
    try {
      text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      return "\n(output unreadable: " + e.getMessage() + ")";
    }
    if (text.isEmpty()) return "";
    if (text.length() <= max) return "\n" + text;
    return "\n[" + (text.length() - max) + " earlier chars cut]\n" + text.substring(text.length() - max);
  }

  private static boolean windows() {
    return File.separatorChar == '\\';
  }
}
