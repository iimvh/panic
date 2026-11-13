package panic.cli;

import context.ConfigPlugin;
import context.Context;
import context.ServiceKey;
import core.CancelledException;
import core.Disposable;
import core.Signal;
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
 * and the prompt reads {@code [bypass] > } so that mode is never silent.
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

  /** {@code > }, or {@code [bypass] > } while the REPL's conversation runs without approvals (YOLO mode). */
  private static String prompt(Context ctx) {
    boolean bypass = Hooks.bail(ctx, Tools.BYPASS, new Tools.BypassQuery(SESSION)).map(Tools.Bypass::on).orElse(false);
    return bypass ? "\n[bypass] > " : "\n> ";
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
            + a.risk().wire() + ") " + a.call().arguments() + "\nallow? [y/N] ");
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
