package panic.verify;

import java.util.List;

/**
 * Commands run when the agent is about to give its final answer, after Claude Code's Stop hooks. {@code hooks}:
 * each {@code {command, timeoutSeconds = 120}}, run by the system shell in the conversation's project (else in
 * {@code dir}, default {@code .}). {@code onlyWithCriteria} (default true): run them only when the conversation's
 * plan has acceptance criteria, so a chat that verifies nothing does not pay for a build on every answer (Claude
 * Code runs its Stop hooks on every stop). {@code maxOutput}: characters of a hook's output kept (default 4000).
 */
public record StopHooksConfig(List<Hook> hooks, String dir, Boolean onlyWithCriteria, int maxOutput) {

  public static final StopHooksConfig DEFAULT = new StopHooksConfig(null, null, null, 0);

  /** One hook: a shell command line and its time limit. */
  public record Hook(String command, int timeoutSeconds) {
    public Hook {
      if (command == null || command.isBlank()) throw new IllegalArgumentException("hooks: a hook needs a command");
      command = command.strip();
      if (timeoutSeconds <= 0) timeoutSeconds = 120;
    }
  }

  public StopHooksConfig {
    hooks = hooks == null ? List.of() : List.copyOf(hooks);
    dir = dir == null || dir.isBlank() ? "." : dir.strip();
    if (onlyWithCriteria == null) onlyWithCriteria = Boolean.TRUE;
    if (maxOutput <= 0) maxOutput = 4000;
  }
}
