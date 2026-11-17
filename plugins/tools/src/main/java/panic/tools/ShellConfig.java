package panic.tools;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shell commands the agent runs ({@code exec}).
 *
 * <ul>
 *   <li>{@code dir}: where commands run when the conversation has no project (relative to the app's working
 *       directory; default {@code .}).</li>
 *   <li>{@code timeoutSeconds}: a command's time limit when the model gives none (default 120);
 *       {@code maxTimeoutSeconds} the most it may ask for (default 600).</li>
 *   <li>{@code maxOutput}: characters of output returned, from the end (default 8000).</li>
 *   <li>{@code maxBackground}: background jobs running at once per conversation (default 4).</li>
 *   <li>{@code deny}: regular expressions (case-insensitive, found anywhere in the command); a command that matches
 *       one is refused before anyone is asked, even in bypass mode. Leaving it out keeps {@link #DEFAULT_DENY};
 *       a list replaces it.</li>
 * </ul>
 *
 * A pattern that does not compile is a config error: a typo must not open the gate.
 */
public record ShellConfig(String dir, int timeoutSeconds, int maxTimeoutSeconds, int maxOutput, int maxBackground,
    List<String> deny) {

  /** Commands that are never worth the risk: privilege, wiping, disks, power, fork bombs, piping downloads to a shell. */
  public static final List<String> DEFAULT_DENY = List.of(
      "(^|[;&|(\\s])(sudo|su|doas)(\\s|$)",
      "(^|[;&|(\\s])rm\\s+(-\\S+\\s+)*(/|/\\*|~|~/|\\$HOME|/home|/usr|/etc|/var|/bin|/boot)(\\s|$)",
      "(^|[;&|(\\s])mkfs",
      "(^|[;&|(\\s])dd\\s.*\\bof=/dev/",
      "(^|[;&|(\\s])(shutdown|reboot|halt|poweroff)(\\s|$)",
      ":\\(\\)\\s*\\{",
      "(curl|wget)\\b[^|]*\\|\\s*(sudo\\s+)?(ba|z|da)?sh\\b",
      ">\\s*/dev/sd[a-z]");

  public static final ShellConfig DEFAULT = new ShellConfig(null, 0, 0, 0, 0, null);

  public ShellConfig {
    dir = dir == null || dir.isBlank() ? "." : dir.strip();
    if (timeoutSeconds <= 0) timeoutSeconds = 120;
    if (maxTimeoutSeconds <= 0) maxTimeoutSeconds = 600;
    if (timeoutSeconds > maxTimeoutSeconds) timeoutSeconds = maxTimeoutSeconds;
    if (maxOutput <= 0) maxOutput = 8000;
    if (maxBackground <= 0) maxBackground = 4;
    deny = deny == null ? DEFAULT_DENY : List.copyOf(deny);
    for (String d : deny) {
      try {
        Pattern.compile(d);
      } catch (PatternSyntaxException | NullPointerException e) {
        throw new IllegalArgumentException("deny: bad pattern '" + d + "'");
      }
    }
  }

  /** The first deny pattern {@code command} matches, or null. */
  public String denied(String command) {
    for (String d : deny) {
      if (Pattern.compile(d, Pattern.CASE_INSENSITIVE).matcher(command).find()) return d;
    }
    return null;
  }
}
