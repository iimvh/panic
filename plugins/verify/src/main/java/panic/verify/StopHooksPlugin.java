package panic.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import panic.chat.Chat;
import panic.command.Hooks;
import panic.todo.Todo;
import panic.tools.Processes;
import panic.tools.Workspace;
import panic.util.Json;

/**
 * Stop hooks, after Claude Code's: commands the user configured run when the agent gives its final answer, and
 * can send it back to work. Answers {@link Chat#STOP} with one {@link Chat.StopCheck} per hook. The commands are
 * the user's, never the model's, so they run without asking (as Claude Code's hooks do).
 *
 * <ul>
 *   <li>Input: a JSON object on stdin, like Claude Code's: {@code hook_event_name} ("Stop"), {@code session_id}
 *       (the conversation), {@code cwd}, {@code stop_hook_active} (true when the agent is already working on a
 *       previous block, so a hook can avoid blocking forever), and Panic's {@code question} and
 *       {@code last_assistant_message}.</li>
 *   <li>Exit 0: passes; its stdout goes to the reviewer as evidence. Unless stdout is
 *       {@code {"decision": "block", "reason": "..."}}: then it blocks with that reason.</li>
 *   <li>Exit 2: blocks; stderr (or stdout when stderr is empty) is the reason the model reads.</li>
 *   <li>Any other exit, a timeout, or a command that cannot start: a non-blocking error, reported to the user.</li>
 * </ul>
 *
 * Lifecycle: requires nothing; a hook runs on the asking thread and is killed with what it started when its time
 * limit passes or the ask is cancelled (then cancellation is thrown).
 */
public final class StopHooksPlugin implements ConfigPlugin<StopHooksConfig> {

  @Override
  public Disposable apply(Context ctx, StopHooksConfig cfg) {
    if (cfg.hooks().isEmpty()) return null;
    Path fallback = Path.of(cfg.dir()).toAbsolutePath().normalize();
    for (StopHooksConfig.Hook hook : cfg.hooks()) {
      Chat.stopCheck(ctx, hook.command(), review -> {
        if (cfg.onlyWithCriteria() && !hasCriteria(ctx, review.conversationId())) return null;
        return run(hook, review, Workspace.root(ctx, review.conversationId(), fallback), cfg.maxOutput());
      });
    }
    return null;
  }

  private static boolean hasCriteria(Context ctx, String conversationId) {
    try {
      Optional<Todo.Plan> plan = Hooks.bail(ctx, Todo.PLAN, new Todo.Query(conversationId));
      return plan.map(p -> !p.criteria().isEmpty()).orElse(false);
    } catch (RuntimeException e) {
      return false;
    }
  }

  static Chat.StopCheck run(StopHooksConfig.Hook hook, Chat.Review review, Path dir, int maxOutput) {
    String name = hook.command();
    Path out = null;
    Path err = null;
    Path in = null;
    try {
      out = Files.createTempFile("panic-stop-", ".out");
      err = Files.createTempFile("panic-stop-", ".err");
      in = Files.createTempFile("panic-stop-", ".json");
      ObjectNode input = Json.object().put("hook_event_name", "Stop").put("session_id", review.conversationId())
          .put("cwd", dir.toString()).put("stop_hook_active", review.attempt() > 1)
          .put("question", review.question()).put("last_assistant_message", review.answer());
      Files.writeString(in, input.toString(), StandardCharsets.UTF_8);
      Process p;
      try {
        p = new ProcessBuilder(Processes.shell(hook.command())).directory(dir.toFile())
            .redirectInput(in.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
      } catch (IOException | RuntimeException e) {
        return Chat.StopCheck.failed(name, "could not start: " + e.getMessage());
      }
      long start = System.nanoTime();
      Disposable onCancel = review.signal().onCancel(() -> Processes.kill(p));
      boolean finished;
      try {
        finished = p.waitFor(hook.timeoutSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        finished = false;
      } finally {
        onCancel.dispose();
      }
      if (!finished) Processes.kill(p);
      review.signal().throwIfCancelled();
      if (!finished) return Chat.StopCheck.failed(name, "timed out after " + hook.timeoutSeconds() + "s");
      String took = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - start) / 1e9);
      String stdout = Processes.tail(out, maxOutput).strip();
      String stderr = Processes.tail(err, maxOutput).strip();
      int exit = p.exitValue();
      if (exit == 2) {
        String why = !stderr.isEmpty() ? stderr : !stdout.isEmpty() ? stdout
            : "the check failed (exit 2) without saying why";
        return Chat.StopCheck.blocked(name, why);
      }
      if (exit != 0) {
        return Chat.StopCheck.failed(name, "exit " + exit + (stderr.isEmpty() ? "" : ": " + stderr));
      }
      JsonNode decision = stdout.startsWith("{") ? Json.parseOrNull(stdout) : null;
      if (decision != null && "block".equals(decision.path("decision").asText())) {
        return Chat.StopCheck.blocked(name, decision.path("reason").asText("blocked without a reason"));
      }
      return Chat.StopCheck.passed(name, "passed (exit 0, " + took + ")" + (stdout.isEmpty() ? "" : ": " + stdout));
    } catch (IOException e) {
      return Chat.StopCheck.failed(name, "cannot run: " + e.getMessage());
    } finally {
      for (Path f : new Path[] {out, err, in}) {
        if (f == null) continue;
        try {
          Files.deleteIfExists(f);
        } catch (IOException ignored) {
          // a temporary file left behind is harmless
        }
      }
    }
  }
}
