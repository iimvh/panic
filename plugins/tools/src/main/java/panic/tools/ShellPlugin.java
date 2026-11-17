package panic.tools;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import context.Task;
import core.Disposable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import panic.command.Commands;
import panic.command.UsageException;
import panic.llm.ToolSpec;
import panic.util.Json;

/**
 * Lets the agent run shell commands in the project of the conversation the user talks to ({@link Workspace#ROOT},
 * else {@link ShellConfig#dir}): tool {@code exec} (risk exec, so the policy asks the user for every command by
 * default), and for commands that do not end by themselves (dev servers, watchers) background jobs read with
 * {@code exec_output} (read) and stopped with {@code exec_stop} (exec). Command {@code jobs} lists and stops them.
 *
 * Commands matching {@link ShellConfig#deny} are refused by a {@link Tools#GATE} that sees the call's arguments,
 * before anyone is asked and even in bypass mode. A command runs only inside the project folder ({@code cwd} may
 * not leave it), with no input, its output (stdout and stderr) going to a temporary log.
 *
 * Lifetime: a foreground command is killed with everything it started when its time limit passes or the call is
 * cancelled. Each background job is a task of this plugin, so disposing the plugin (a config change, the app
 * stopping) kills them all and waits for them.
 */
public final class ShellPlugin implements ConfigPlugin<ShellConfig> {

  static final String EXEC = "exec";
  static final String OUTPUT = "exec_output";
  static final String STOP = "exec_stop";

  /** How long a background job is watched before {@code exec} returns, for its first lines (a server's URL). */
  static final Duration STARTUP = Duration.ofSeconds(5);

  /** A background job: its command, where it runs, its process and the log of its output. */
  private record Job(String id, String origin, String command, Path dir, Process process, Path log, Instant started,
      Task<Void> watcher) {
    String state() {
      if (process.isAlive()) return "running";
      return "exit " + process.exitValue();
    }
  }

  @Override
  public Disposable apply(Context ctx, ShellConfig cfg) {
    Path fallback = Path.of(cfg.dir()).toAbsolutePath().normalize();
    Map<String, Job> jobs = new ConcurrentHashMap<>();
    AtomicInteger ids = new AtomicInteger();
    ctx.hook(Tools.GATE, g -> {
      if (!g.name().equals(EXEC) || g.arguments() == null) return null;
      JsonNode args = Json.parseOrNull(g.arguments());
      String command = args == null ? null : args.path("command").asText(null);
      String pattern = command == null ? null : cfg.denied(command);
      return pattern == null ? null
          : new Tools.Refusal("this command is not allowed here (matches deny rule " + pattern + "); do not run it "
              + "another way, tell the user to run it themselves if it is really needed", false);
    });
    Tools.register(ctx, new Exec(ctx, cfg, fallback, jobs, ids));
    Tools.register(ctx, new Output(cfg, jobs));
    Tools.register(ctx, new Stop(jobs));
    Commands.command(ctx, "jobs [action] [id]", "background commands the agent started: list | stop <id>", call -> {
      String action = call.argOr("action", "list");
      switch (action) {
        case "list" -> {
          if (jobs.isEmpty()) {
            call.out().println("no background jobs");
            return;
          }
          for (Job j : jobs.values()) {
            call.out().println(j.id() + "  " + j.state() + "  " + since(j.started()) + "  " + j.command() + "  (in "
                + j.dir() + ", chat " + j.origin() + ")");
          }
        }
        case "stop" -> {
          String id = call.arg("id");
          if (id == null) throw new UsageException("which job?", "jobs stop <id>");
          Job j = jobs.get(id);
          if (j == null) {
            call.out().println("no job " + id);
            return;
          }
          stop(jobs, j);
          call.out().println("job " + id + " stopped");
        }
        default -> throw new UsageException("unknown action '" + action + "'", "jobs [list|stop <id>]");
      }
    });
    // the watchers are tasks of ctx: disposing it kills the processes; the logs go after them
    return () -> jobs.values().forEach(j -> deleteQuietly(j.log()));
  }

  /** Kills the job (its watcher kills the process) and forgets it. */
  private static void stop(Map<String, Job> jobs, Job j) {
    j.watcher().dispose();
    Processes.kill(j.process());
    jobs.remove(j.id());
    deleteQuietly(j.log());
  }

  private static String since(Instant start) {
    long s = Duration.between(start, Instant.now()).toSeconds();
    return s < 60 ? s + "s" : (s / 60) + "m" + String.format(Locale.ROOT, "%02d", s % 60) + "s";
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      // a temporary file left behind is harmless
    }
  }

  /** {@code exec {command, cwd?, timeoutSeconds?, background?}}. */
  private record Exec(Context ctx, ShellConfig cfg, Path fallback, Map<String, Job> jobs, AtomicInteger ids)
      implements Tool {

    @Override
    public ToolSpec spec() {
      return new ToolSpec(EXEC, "Run a shell command (sh -c) in the project folder of this chat and get its exit "
          + "status and the end of its output (stdout and stderr). The user approves commands their rules do not "
          + "allow; to stop a background job use exec_stop, not kill. Use it to install "
          + "dependencies, build, test or run the project, not to read or write files (use the file tools). A "
          + "command that does not end by itself (a dev server such as `npm run dev`, a watcher) must run with "
          + "background: true; then read its output with " + OUTPUT + " and stop it with " + STOP + ". Commands get "
          + "no input, so pass flags that avoid prompts (e.g. `npm init -y`, `npx --yes`).",
          Json.parseOrNull("""
              {"type":"object","properties":{
                "command":{"type":"string","description":"the command line, run by sh -c"},
                "cwd":{"type":"string","description":"a folder inside the project to run in (default: its root)"},
                "timeoutSeconds":{"type":"integer","minimum":1,"description":"time limit of a foreground command"},
                "background":{"type":"boolean","description":"start it as a background job and return at once"}},
               "required":["command"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.EXEC;
    }

    /** The longest foreground command allowed, plus a little: the chat's own tool limit must not cut it. */
    @Override
    public int timeoutSeconds() {
      return cfg.maxTimeoutSeconds() + 10;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      String command = args.get("command").asText().strip();
      if (command.isEmpty()) return ToolResult.error("command is empty");
      Path root = Workspace.root(ctx, tc.origin(), fallback);
      Path dir;
      try {
        dir = folder(root, args.path("cwd").asText(""));
      } catch (IllegalArgumentException e) {
        return ToolResult.error(e.getMessage());
      }
      if (args.path("background").asBoolean(false)) return background(command, dir, tc);
      int limit = Math.min(args.path("timeoutSeconds").asInt(cfg.timeoutSeconds()), cfg.maxTimeoutSeconds());
      return foreground(command, dir, limit, tc);
    }

    /** {@code cwd} inside {@code root} (real paths, so a link cannot lead out), as an existing folder. */
    private static Path folder(Path root, String cwd) {
      Path real;
      try {
        real = root.toRealPath();
      } catch (IOException e) {
        throw new IllegalArgumentException("project folder not found: " + root);
      }
      if (cwd.isBlank()) return real;
      Path target;
      try {
        target = real.resolve(cwd).normalize().toRealPath();
      } catch (IOException | RuntimeException e) {
        throw new IllegalArgumentException("cwd not found: " + cwd);
      }
      if (!target.startsWith(real)) throw new IllegalArgumentException("cwd is outside the project: " + cwd);
      if (!Files.isDirectory(target)) throw new IllegalArgumentException("cwd is not a folder: " + cwd);
      return target;
    }

    private Process start(String command, Path dir, Path log) throws IOException {
      return new ProcessBuilder(Processes.shell(command)).directory(dir.toFile()).redirectErrorStream(true)
          .redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(Processes.nullDevice())).start();
    }

    private ToolResult foreground(String command, Path dir, int limit, ToolContext tc) {
      Path log;
      try {
        log = Files.createTempFile("panic-exec-", ".log");
      } catch (IOException e) {
        return ToolResult.error("could not start: " + e.getMessage());
      }
      try {
        long start = System.nanoTime();
        Process p;
        try {
          p = start(command, dir, log);
        } catch (IOException | RuntimeException e) {
          return ToolResult.error("could not start: " + e.getMessage());
        }
        Disposable onCancel = tc.signal().onCancel(() -> Processes.kill(p));
        boolean finished;
        try {
          finished = p.waitFor(limit, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          finished = false;
        } finally {
          onCancel.dispose();
        }
        if (!finished) Processes.kill(p);
        tc.signal().throwIfCancelled();
        String output = Processes.tail(log, cfg.maxOutput());
        if (!finished) {
          return ToolResult.error("timed out after " + limit + "s and was killed (for a server or watcher, use "
              + "background: true)" + output);
        }
        int exit = p.exitValue();
        String took = String.format(Locale.ROOT, "%.1fs", (System.nanoTime() - start) / 1e9);
        String head = "exit " + exit + " (" + took + ")";
        return exit == 0 ? ToolResult.ok(head + output) : ToolResult.error(head + output);
      } finally {
        deleteQuietly(log);
      }
    }

    private ToolResult background(String command, Path dir, ToolContext tc) {
      long running = jobs.values().stream().filter(j -> j.origin().equals(tc.origin()) && j.process().isAlive()).count();
      if (running >= cfg.maxBackground()) {
        return ToolResult.error("already " + running + " background jobs running in this chat (at most "
            + cfg.maxBackground() + "): stop one with " + STOP + " first");
      }
      Path log;
      Process p;
      try {
        log = Files.createTempFile("panic-job-", ".log");
      } catch (IOException e) {
        return ToolResult.error("could not start: " + e.getMessage());
      }
      try {
        p = start(command, dir, log);
      } catch (IOException | RuntimeException e) {
        deleteQuietly(log);
        return ToolResult.error("could not start: " + e.getMessage());
      }
      String id = Integer.toString(ids.incrementAndGet());
      // the job lives as long as this plugin: cancelling the watcher (stop, disposal) kills the process
      Task<Void> watcher = ctx.spawn("job-" + id, s -> {
        Disposable kill = s.onCancel(() -> Processes.kill(p));
        try {
          p.waitFor();
        } catch (InterruptedException e) {
          Processes.kill(p);
        } finally {
          kill.dispose();
        }
        return null;
      });
      Job job = new Job(id, tc.origin(), command, dir, p, log, Instant.now(), watcher);
      jobs.put(id, job);
      // watch its first seconds: a quick failure or a server's address is worth reporting now
      long until = System.nanoTime() + STARTUP.toNanos();
      try {
        while (p.isAlive() && System.nanoTime() < until) {
          tc.signal().throwIfCancelled();
          p.waitFor(100, TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        stop(jobs, job); // the caller gave up before learning the job's id
        throw e;
      }
      if (tc.signal().isCancelled()) {
        stop(jobs, job);
        tc.signal().throwIfCancelled();
      }
      String output = Processes.tail(log, cfg.maxOutput());
      if (!p.isAlive()) {
        int exit = p.exitValue();
        String head = "job " + id + " ended at once with exit " + exit;
        return exit == 0 ? ToolResult.ok(head + output) : ToolResult.error(head + output);
      }
      return ToolResult.ok("started job " + id + " (pid " + p.pid() + ") in " + dir + "; read it with " + OUTPUT
          + " {id: \"" + id + "\"}, stop it with " + STOP + output);
    }
  }

  /** {@code exec_output {id}}: a job's state and the end of its output. */
  private record Output(ShellConfig cfg, Map<String, Job> jobs) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec(OUTPUT, "The state (running, or its exit status) and the end of the output of a background "
          + "job started with " + EXEC + " in this chat.", Json.parseOrNull("""
              {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      Job j = jobs.get(args.get("id").asText());
      if (j == null || !j.origin().equals(tc.origin())) return ToolResult.error("no job " + args.get("id").asText()
          + " in this chat; jobs: " + ids(jobs, tc.origin()));
      return ToolResult.ok("job " + j.id() + " " + j.state() + " (" + since(j.started()) + "): " + j.command()
          + Processes.tail(j.log(), cfg.maxOutput()));
    }
  }

  /** {@code exec_stop {id}}: kills a job and everything it started. */
  private record Stop(Map<String, Job> jobs) implements Tool {
    @Override
    public ToolSpec spec() {
      return new ToolSpec(STOP, "Stop a background job started with " + EXEC + " in this chat (it and everything it "
          + "started are killed).", Json.parseOrNull("""
              {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""));
    }

    @Override
    public Risk risk() {
      return Risk.EXEC;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      Job j = jobs.get(args.get("id").asText());
      if (j == null || !j.origin().equals(tc.origin())) return ToolResult.error("no job " + args.get("id").asText()
          + " in this chat; jobs: " + ids(jobs, tc.origin()));
      String state = j.state();
      stop(jobs, j);
      return ToolResult.ok("job " + j.id() + " stopped (was " + state + ")");
    }
  }

  private static String ids(Map<String, Job> jobs, String origin) {
    List<String> out = new ArrayList<>();
    for (Job j : jobs.values()) {
      if (j.origin().equals(origin)) out.add(j.id() + " (" + j.state() + ")");
    }
    return out.isEmpty() ? "none" : String.join(", ", out);
  }
}
