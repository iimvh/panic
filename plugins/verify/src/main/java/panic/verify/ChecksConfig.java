package panic.verify;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The checks the agent may run, by name: {@code checks = {"test": {"command": ["./gradlew", "test"],
 * "timeoutSeconds": 600}}}. A command is a program and its arguments, run without a shell (use
 * {@code ["sh", "-c", "..."]} for one). {@code dir}: where commands run, relative to the app's working directory
 * (default {@code .}). {@code maxOutput}: the most characters of output returned, from the end (default 8000).
 */
public record ChecksConfig(String dir, Map<String, Check> checks, int maxOutput) {

  public static final ChecksConfig DEFAULT = new ChecksConfig(null, null, 0);

  /** One check: the command and how long it may run ({@code timeoutSeconds}, default 600). */
  public record Check(List<String> command, int timeoutSeconds) {
    public Check {
      if (command == null || command.isEmpty() || command.stream().anyMatch(a -> a == null || a.isEmpty())) {
        throw new IllegalArgumentException("a check needs a command: a non-empty list of non-empty strings");
      }
      command = List.copyOf(command);
      if (timeoutSeconds <= 0) timeoutSeconds = 600;
    }
  }

  public ChecksConfig {
    dir = dir == null || dir.isBlank() ? "." : dir.strip();
    checks = checks == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(checks));
    for (Map.Entry<String, Check> e : checks.entrySet()) {
      if (e.getKey().isBlank() || e.getValue() == null) throw new IllegalArgumentException("bad check: " + e.getKey());
    }
    if (maxOutput <= 0) maxOutput = 8_000;
  }
}
