package panic.cli;

import context.ConfigPlugin;
import context.Context;
import context.ServiceKey;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import panic.command.Command;
import panic.command.Commands;
import panic.command.Hooks;
import panic.command.Out;
import panic.command.Session;
import panic.command.UsageException;
import panic.interact.AskUser;
import panic.planner.Planner;
import panic.plugin.App;
import panic.tools.Tools;

/**
 * The terminal front-end. It owns no feature: it only runs commands that other plugins declare (see
 * {@link Commands}), so it shows exactly what is running right now. Plain text runs {@code ask}.
 *
 * Two ways to use it:
 *  - as a plugin with {@code repl=true}: reads lines from the app's terminal until {@code exit} or end of
 *    input, then asks the app to exit;
 *  - {@link #once}: runs one command from argv, e.g. {@code panic models free} (with {@code repl=false}).
 *
 * Commands it declares itself: {@code help [command]}, and {@code exit} in the REPL.
 *
 * In the REPL it also answers {@link Tools#APPROVE} for its own session: a tool call the policy wants
 * approved is shown and runs only if the user types {@code y}. One-shot mode answers nothing, so such calls
 * are denied (set {@code permissions.bypassPermissions} for scripts). While the session runs in bypass mode
 * ({@link Tools#BYPASS}, switched with command {@code bypass}) the policy allows those calls, so nothing is asked,
 * and the prompt reads {@code [bypass] > } so that mode is never silent. It answers {@link Planner#APPROVE} the
 * same way: the proposed plan is printed and {@code y} approves it, any other text goes back to the agent as
 * feedback. While the session is in plan mode the prompt reads {@code [plan] > }. Questions the agent asks
 * ({@link AskUser#ASK}) are shown with numbered options, in the same queue.
 */
public final class Cli implements ConfigPlugin<CliConfig> {

  public static final String PREFIX = "/";
  static final String ASK = "ask";
  static final String SESSION = "repl";

  @Override
  public List<ServiceKey<?>> requires() {
    return List.of(App.KEY);
  }

  @Override
  public Disposable apply(Context ctx, CliConfig cfg) {
    if (!cfg.repl()) {
      help(ctx, "");
      return null;
    }
    App app = ctx.get(App.KEY);
    Stdin in = Stdin.of(app.in());
    Out out = app.out();
    help(ctx, PREFIX);
    AtomicBoolean stop = new AtomicBoolean();
    Commands.command(ctx, "exit", "quit", call -> stop.set(true));
    ReentrantLock asking = new ReentrantLock(true);
    ctx.hook(Tools.APPROVE, a -> a.conversationId().equals(SESSION) ? approve(a, in, out, asking) : null);
    ctx.hook(Planner.APPROVE, p -> p.conversationId().equals(SESSION) ? approve(p, in, out, asking) : null);
    ctx.hook(AskUser.ASK, r -> r.conversationId().equals(SESSION) ? answer(r, in, out, asking) : null);
    ctx.spawn("repl", signal -> {
      Session session = new Session(SESSION, out, signal);
      out.println("panic — type a message, or " + PREFIX + "help");
      Commands.run(ctx, Commands.STATUS, "", session);
      try {
        while (!signal.isCancelled() && !stop.get()) {
          out.print(prompt(ctx));
          String line = in.readLine();
          if (line == null) break;
          line = line.strip();
          if (!line.isEmpty()) dispatch(ctx, line, session, signal);
        }
      } catch (InterruptedException stopped) {
        return null;
      }
      if (!signal.isCancelled()) app.exit();
      return null;
    });
    return null;
  }

  /**
   * {@code > }, with {@code [plan]} while the REPL's conversation is in plan mode and {@code [bypass]} while it runs
   * without approvals (YOLO mode), so neither mode is silent.
   */
  static String prompt(Context ctx) {
    boolean plan = Hooks.bail(ctx, Planner.MODE, new Planner.ModeQuery(SESSION)).map(Planner.Mode::on).orElse(false);
    boolean bypass = Hooks.bail(ctx, Tools.BYPASS, new Tools.BypassQuery(SESSION)).map(Tools.Bypass::on).orElse(false);
    return "\n" + (plan ? "[plan] " : "") + (bypass ? "[bypass] " : "") + "> ";
  }

  /**
   * Asks the user at the terminal whether a tool call may run. The REPL thread is blocked in the command that
   * made the call, so the approvals are the only readers of the terminal meanwhile; calls running in parallel
   * (or sub-agents) may ask at the same time, so {@code asking} puts their questions one after the other. A
   * sub-agent's call is shown with its name. Anything but {@code y}/{@code yes} denies, and so does end of input.
   */
  private static Tools.Decision approve(Tools.Approval a, Stdin in, Out out, ReentrantLock asking) {
    String answer;
    try {
      while (!asking.tryLock(100, TimeUnit.MILLISECONDS)) a.signal().throwIfCancelled();
      try {
        a.signal().throwIfCancelled();
        out.print("\n[approve] " + (a.agent() == null ? "" : a.agent() + ": ") + a.call().name() + " ("
            + a.risk().wire() + ") " + shown(a.call().arguments()) + "\nallow? [y/N] ");
        answer = in.readLine(a.signal());
      } finally {
        asking.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException("approval interrupted");
    }
    String yes = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
    boolean allowed = yes.equals("y") || yes.equals("yes");
    if (!allowed) out.println("[denied]");
    return allowed ? Tools.Decision.ALLOW : Tools.Decision.DENY;
  }

  /**
   * The arguments as the user reads them: a shell command ({@code {"command": ...}}, the {@code exec} tool) as
   * {@code $ command} (with {@code (in cwd)}), anything else as its JSON.
   */
  static String shown(String arguments) {
    com.fasterxml.jackson.databind.JsonNode args = panic.util.Json.parseOrNull(arguments);
    if (args == null || !args.path("command").isTextual()) return arguments;
    String cwd = args.path("cwd").asText("");
    return "\n  $ " + args.get("command").asText() + (cwd.isBlank() ? "" : "   (in " + cwd + ")")
        + (args.path("background").asBoolean(false) ? "   [background]" : "");
  }

  /**
   * Shows a proposed plan and reads one line, queued with the tool approvals: {@code y}/{@code yes} approves it,
   * anything else is sent back as feedback (an empty line or {@code n} without any); end of input answers nothing.
   */
  private static Planner.Answer approve(Planner.Proposal p, Stdin in, Out out, ReentrantLock asking) {
    String answer;
    try {
      while (!asking.tryLock(100, TimeUnit.MILLISECONDS)) p.signal().throwIfCancelled();
      try {
        p.signal().throwIfCancelled();
        StringBuilder text = new StringBuilder("\n[plan]\n").append(p.plan().strip()).append('\n');
        if (!p.items().isEmpty()) {
          text.append("\nSteps:\n");
          for (int i = 0; i < p.items().size(); i++) text.append(i + 1).append(". ").append(p.items().get(i)).append('\n');
        }
        if (!p.criteria().isEmpty()) {
          text.append("\nAcceptance criteria:\n");
          for (String c : p.criteria()) text.append("- ").append(c).append('\n');
        }
        out.print(text + "approve the plan? [y/N, or what to change] ");
        answer = in.readLine(p.signal());
      } finally {
        asking.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException("plan approval interrupted");
    }
    if (answer == null) return null; // end of input: no answer
    String reply = answer.strip();
    String lower = reply.toLowerCase(Locale.ROOT);
    if (lower.equals("y") || lower.equals("yes")) return Planner.Answer.APPROVED;
    out.println("[plan not approved]");
    return Planner.Answer.rejected(lower.equals("n") || lower.equals("no") ? "" : reply);
  }

  /**
   * Shows the agent's questions one by one, with numbered options and {@code 0} for an answer of the user's own,
   * and reads one line each, queued with the approvals: {@code 2} picks an option, {@code 1,3} several (when the
   * question allows it), {@code 0} asks for the text, anything else is the user's own answer, an empty line leaves
   * the question unanswered. End of input answers nothing (null).
   */
  private static AskUser.Answers answer(AskUser.Request r, Stdin in, Out out, ReentrantLock asking) {
    List<AskUser.Answer> answers = new ArrayList<>();
    try {
      while (!asking.tryLock(100, TimeUnit.MILLISECONDS)) r.signal().throwIfCancelled();
      try {
        for (AskUser.Question q : r.questions()) {
          r.signal().throwIfCancelled();
          StringBuilder text = new StringBuilder("\n[question] ");
          if (!q.header().isEmpty()) text.append('[').append(q.header()).append("] ");
          text.append(q.question()).append('\n');
          for (int i = 0; i < q.options().size(); i++) {
            AskUser.Option o = q.options().get(i);
            text.append("  ").append(i + 1).append(". ").append(o.label());
            if (!o.description().isEmpty()) text.append(" - ").append(o.description());
            text.append('\n');
          }
          text.append("  0. Other (type your own)\n");
          out.print(text + (q.multiSelect() ? "choose (e.g. 1,3) or type: " : "choose or type: "));
          String line = in.readLine(r.signal());
          if (line == null) return null;
          line = line.strip();
          if (line.equals("0")) {
            out.print("your answer: ");
            line = in.readLine(r.signal());
            if (line == null) return null;
            answers.add(new AskUser.Answer(null, line));
          } else {
            answers.add(parse(q, line));
          }
        }
      } finally {
        asking.unlock();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancelledException("question interrupted");
    }
    return new AskUser.Answers(answers);
  }

  /** Option numbers ({@code 2}, or {@code 1,3} for a multi-select) pick labels; any other text is the user's own. */
  static AskUser.Answer parse(AskUser.Question q, String line) {
    if (line.isEmpty()) return new AskUser.Answer(null, null);
    String[] parts = line.split("\\s*,\\s*|\\s+");
    if (parts.length > 1 && !q.multiSelect()) return new AskUser.Answer(null, line);
    List<String> picked = new ArrayList<>();
    for (String p : parts) {
      int n;
      try {
        n = Integer.parseInt(p);
      } catch (NumberFormatException e) {
        return new AskUser.Answer(null, line);
      }
      if (n < 1 || n > q.options().size()) return new AskUser.Answer(null, line);
      String label = q.options().get(n - 1).label();
      if (!picked.contains(label)) picked.add(label);
    }
    return new AskUser.Answer(picked, null);
  }

  private static void dispatch(Context ctx, String line, Session session, Signal signal) {
    String name;
    String argLine;
    if (line.startsWith(PREFIX)) {
      String rest = line.substring(PREFIX.length());
      int space = indexOfWhitespace(rest);
      name = space < 0 ? rest : rest.substring(0, space);
      argLine = space < 0 ? "" : rest.substring(space + 1);
    } else {
      name = ASK;
      argLine = line;
    }
    Out out = session.out();
    try {
      if (!Commands.run(ctx, name, argLine, session)) out.println(unknown(name, PREFIX));
    } catch (UsageException e) {
      out.println("usage: " + PREFIX + e.usage() + "  (" + e.getMessage() + ")");
    } catch (CancelledException e) {
      if (!signal.isCancelled()) out.println("\n[cancelled]");
    } catch (RuntimeException e) {
      out.println("\n[error] " + message(e));
    }
  }

  private static void help(Context ctx, String prefix) {
    Commands.command(ctx, "help [command]", "list the commands available now", call -> {
      String which = call.arg("command");
      Out out = call.out();
      if (which != null) {
        Optional<Command> c = Commands.find(ctx, which.startsWith(PREFIX) ? which.substring(PREFIX.length()) : which);
        out.println(c.map(cmd -> prefix + cmd.usage() + "\n  " + cmd.description()).orElse(unknown(which, prefix)));
        return;
      }
      List<Command> all = Commands.list(ctx);
      int width = all.stream().mapToInt(c -> c.usage().length()).max().orElse(0) + prefix.length();
      for (Command c : all) {
        out.println(String.format("  %-" + width + "s  %s", prefix + c.usage(), c.description()));
      }
      if (!prefix.isEmpty()) out.println("  (plain text: " + ASK + ")");
    });
  }

  private static String unknown(String name, String prefix) {
    if (name.equals(ASK)) {
      return "[no LLM connected] nothing can answer yet: check " + prefix + "status";
    }
    return "unknown command " + prefix + name + ": " + prefix + "help lists the commands available now";
  }

  private static String message(RuntimeException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private static int indexOfWhitespace(String s) {
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) return i;
    }
    return -1;
  }
}
