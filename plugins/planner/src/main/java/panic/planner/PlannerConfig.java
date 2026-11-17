package panic.planner;

import java.util.List;

/**
 * Plan mode.
 *
 * <ul>
 *   <li>{@code byDefault}: every conversation starts in plan mode (default false); each one can switch with command
 *       {@code plan on|off}, which wins over this default.</li>
 *   <li>{@code allow}: tools allowed in plan mode besides the read-risk ones, by name (default none: the approved
 *       plan's steps become the task list by themselves, and a task list written while planning tempts a model to
 *       mark steps done that never ran).</li>
 *   <li>{@code approveTimeoutSeconds}: how long a proposed plan waits for the user (default 900).</li>
 *   <li>{@code dir}: where plan files go, relative to the conversation's project folder (default
 *       {@code .panic/plans}).</li>
 *   <li>{@code root}: the folder used instead when the conversation has no project (default {@code .}, the app's
 *       working directory).</li>
 * </ul>
 */
public record PlannerConfig(boolean byDefault, List<String> allow, int approveTimeoutSeconds, String dir, String root) {

  public static final int DEFAULT_APPROVE_TIMEOUT_SECONDS = 900;

  public static final PlannerConfig DEFAULT = new PlannerConfig(false, null, 0);

  public PlannerConfig(boolean byDefault, List<String> allow, int approveTimeoutSeconds) {
    this(byDefault, allow, approveTimeoutSeconds, null, null);
  }

  public PlannerConfig {
    if (allow == null) allow = List.of();
    for (String name : allow) {
      if (name == null || name.isBlank()) throw new IllegalArgumentException("allow: empty tool name");
    }
    allow = allow.stream().map(String::strip).distinct().toList();
    if (approveTimeoutSeconds <= 0) approveTimeoutSeconds = DEFAULT_APPROVE_TIMEOUT_SECONDS;
    dir = dir == null || dir.isBlank() ? ".panic/plans" : dir.strip();
    root = root == null || root.isBlank() ? "." : root.strip();
  }
}
