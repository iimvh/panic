package panic.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helpers for tools that run processes ({@code exec} here, {@code check} of {@code plugins/verify}): how to run a
 * command line through the system shell, how to kill a process with everything it started, and how to read the end
 * of its output. Stateless; the processes belong to the tool that starts them.
 */
public final class Processes {

  private Processes() {
  }

  /** {@code command} run by the system shell: {@code sh -c} (or {@code cmd /c} on Windows). */
  public static List<String> shell(String command) {
    return windows() ? List.of("cmd", "/c", command) : List.of("sh", "-c", command);
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
