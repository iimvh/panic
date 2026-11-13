package panic.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import panic.command.Commands;
import panic.util.Json;
import panic.llm.ToolSpec;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;

/**
 * Tool {@code check {name}}: runs one of the commands configured in {@link ChecksConfig} (tests, build, lint)
 * and returns its exit status and the end of its output, so the agent works from real results instead of its
 * own claims. The model only picks a name, never the command. Risk {@code exec}, so the permission policy asks
 * by default. A failing check is an error result: the loop goes on and the model can fix and run it again.
 *
 * Requires nothing; with no checks configured it registers no tool. The process (and its children) is killed
 * when its timeout passes or the call is cancelled (the caller gave up, or the plugin is disposed). The tool
 * declares the longest configured timeout as its own limit, so {@code chat.toolTimeoutSeconds} does not cut it.
 * Command: {@code checks}.
 */
public final class ChecksPlugin implements ConfigPlugin<ChecksConfig> {

  @Override
  public Disposable apply(Context ctx, ChecksConfig cfg) {
    Path dir = Path.of(cfg.dir()).toAbsolutePath().normalize();
    if (!cfg.checks().isEmpty()) Tools.register(ctx, new CheckTool(cfg, dir));
    Commands.command(ctx, "checks", "list the checks the agent can run", call -> {
      if (cfg.checks().isEmpty()) {
        call.out().println("no checks configured (section \"checks\" of panic.json)");
        return;
      }
      for (Map.Entry<String, ChecksConfig.Check> e : cfg.checks().entrySet()) {
        call.out().println(e.getKey() + "  " + String.join(" ", e.getValue().command()) + "  (timeout "
            + e.getValue().timeoutSeconds() + "s, in " + dir + ")");
      }
    });
    return null;
  }

  private record CheckTool(ChecksConfig cfg, Path dir) implements Tool {
    @Override
    public ToolSpec spec() {
      ObjectNode schema = Json.object().put("type", "object");
      ObjectNode name = schema.putObject("properties").putObject("name").put("type", "string");
      ArrayNode names = name.putArray("enum");
      cfg.checks().keySet().forEach(names::add);
      schema.putArray("required").add("name");
      schema.put("additionalProperties", false);
      StringBuilder list = new StringBuilder();
      cfg.checks().forEach((n, c) -> list.append("\n- ").append(n).append(": ").append(String.join(" ", c.command())));
      return new ToolSpec("check", "Run a configured check (tests, build...) and get its exit status and the end of "
          + "its output. Use it to verify your work before you answer. Checks:" + list, schema);
    }

    @Override
    public Risk risk() {
      return Risk.EXEC;
    }

    /** The longest configured check, plus a little to report it: the chat's own tool limit must not cut it. */
    @Override
    public int timeoutSeconds() {
      return cfg.checks().values().stream().mapToInt(ChecksConfig.Check::timeoutSeconds).max().orElse(0) + 10;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      String name = args.get("name").asText();
      ChecksConfig.Check check = cfg.checks().get(name);
      if (!Files.isDirectory(dir)) return ToolResult.error("check " + name + ": directory not found: " + dir);
      Path log;
      try {
        log = Files.createTempFile("panic-check-", ".log");
      } catch (IOException e) {
        return ToolResult.error("check " + name + " could not start: " + e.getMessage());
      }
      try {
        return run(name, check, log, tc);
      } finally {
        try {
          Files.deleteIfExists(log);
        } catch (IOException ignored) {
          // a temporary file left behind is harmless
        }
      }
    }

    private ToolResult run(String name, ChecksConfig.Check check, Path log, ToolContext tc) {
      long start = System.nanoTime();
      Process p;
      try {
        p = new ProcessBuilder(check.command()).directory(dir.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(new File(nullDevice())))
            .start();
      } catch (IOException | RuntimeException e) {
        return ToolResult.error("check " + name + " could not start: " + e.getMessage());
      }
      Disposable onCancel = tc.signal().onCancel(() -> kill(p));
      boolean finished;
      try {
        finished = p.waitFor(check.timeoutSeconds(), TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        finished = false;
      } finally {
        onCancel.dispose();
      }
      if (!finished) kill(p);
      tc.signal().throwIfCancelled();
      String took = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - start) / 1e9);
      String output = tail(log);
      if (!finished) {
        return ToolResult.error("check " + name + " timed out after " + check.timeoutSeconds() + "s" + output);
      }
      int exit = p.exitValue();
      String head = "check " + name + (exit == 0 ? " passed" : " failed") + " (exit " + exit + ", " + took + ")";
      return exit == 0 ? ToolResult.ok(head + output) : ToolResult.error(head + output);
    }

    /** The last {@code maxOutput} characters of the log, after a newline; empty when there was no output. */
    private String tail(Path log) {
      String text;
      try {
        text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).strip();
      } catch (IOException e) {
        return "\n(output unreadable: " + e.getMessage() + ")";
      }
      if (text.isEmpty()) return "";
      int max = cfg.maxOutput();
      if (text.length() <= max) return "\n" + text;
      return "\n[" + (text.length() - max) + " earlier chars cut]\n" + text.substring(text.length() - max);
    }
  }

  /** Kills {@code p} and everything it started, and waits briefly for it to go. */
  private static void kill(Process p) {
    p.descendants().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
    try {
      p.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String nullDevice() {
    return File.separatorChar == '\\' ? "NUL" : "/dev/null";
  }
}
