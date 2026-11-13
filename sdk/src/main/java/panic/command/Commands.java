package panic.command;

import context.Context;
import context.HookKey;
import core.Disposable;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The command system. A plugin declares commands with {@link #command}; they exist exactly as long as
 * that plugin (they are z hooks, so they are unregistered when it is disposed, after any run in progress).
 * A front-end (the CLI, a UI later) runs them with {@link #run} and lists them with {@link #list}.
 *
 * <pre>
 * Commands.command(ctx, "model [id]", "show or switch the model", call -&gt; {
 *   call.out().println("model: " + call.argOr("id", current));
 * });
 * </pre>
 *
 * No service is involved: declaring a command never ties a plugin's lifetime to the front-end's, and a
 * command whose plugin is not running is simply absent.
 */
public final class Commands {

  /** A request to run the command {@code name}. */
  public record Invocation(String name, String argLine, Session session) {
    public Invocation {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(session, "session");
    }
  }

  public enum Handled { YES }

  public enum Listing { ALL }

  public static final HookKey<Invocation, Handled> RUN = HookKey.of("command.run", Invocation.class, Handled.class);
  public static final HookKey<Listing, Command> LIST = HookKey.of("command.list", Listing.class, Command.class);

  private Commands() {
  }

  /** Declares a command in {@code ctx}. Disposing the result (or ctx) removes it. */
  public static Disposable command(Context ctx, String spec, String description, Action action) {
    Command command = Command.parse(spec, description, action);
    Disposable run = ctx.hook(RUN, inv -> {
      if (!inv.name().equals(command.name())) return null;
      command.run(inv.argLine(), inv.session());
      return Handled.YES;
    });
    Disposable list = ctx.hook(LIST, q -> command);
    return () -> {
      list.dispose();
      run.dispose();
    };
  }

  /**
   * Runs a command on the calling thread. Returns false when no running plugin declares it. Throws what
   * the command threw (UsageException for bad arguments).
   */
  public static boolean run(Context ctx, String name, String argLine, Session session) {
    return Hooks.bail(ctx, RUN, new Invocation(name, argLine == null ? "" : argLine, session)).isPresent();
  }

  /** Runs a command from already split arguments (argv). */
  public static boolean run(Context ctx, String name, List<String> args, Session session) {
    return run(ctx, name, String.join(" ", args.stream().map(Tokens::quote).toList()), session);
  }

  /** The commands available right now, by name. When two plugins declare the same name, the first one wins. */
  public static List<Command> list(Context ctx) {
    return ctx.collect(LIST, Listing.ALL).stream().sorted(Comparator.comparing(Command::name)).toList();
  }

  public static Optional<Command> find(Context ctx, String name) {
    return list(ctx).stream().filter(c -> c.name().equals(name)).findFirst();
  }
}
