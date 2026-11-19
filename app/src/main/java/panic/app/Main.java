package panic.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ErrorHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import panic.command.Commands;
import panic.command.Out;
import panic.util.Json;

/**
 * <pre>
 * panic [-c FILE]                    interactive chat (REPL); also the web UI when web.enabled
 * panic [-c FILE] --web              REPL + web UI
 * panic [-c FILE] --serve            web UI only, until Ctrl+C
 * panic [-c FILE] COMMAND [ARGS]     run one command and exit, e.g. panic models free
 * </pre>
 *
 * Flags only override plugin configs (cli.repl, web.enabled); every plugin is found on the classpath.
 */
public final class Main {

  private static final String USAGE = "usage: panic [-c FILE] [--web | --serve | COMMAND [ARGS...]]";

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    args = WindowsArgs.recover(args);
    Path file = Path.of("panic.json");
    boolean web = false;
    boolean serve = false;
    List<String> command = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      if (!command.isEmpty()) {
        command.add(a);
      } else if (a.equals("-c") || a.equals("--config")) {
        if (i + 1 >= args.length) fail(USAGE);
        file = Path.of(args[++i]);
      } else if (a.equals("--web")) {
        web = true;
      } else if (a.equals("--serve")) {
        serve = true;
      } else if (a.equals("-h") || a.equals("--help")) {
        System.out.println(USAGE + "\n  commands: panic help");
        return;
      } else if (a.startsWith("-")) {
        fail("unknown option " + a + "\n" + USAGE);
      } else {
        command.add(a);
      }
    }
    if ((web || serve) && !command.isEmpty()) fail("--web / --serve do not combine with a command\n" + USAGE);
    boolean once = !command.isEmpty();

    ConfigLoader loader = ConfigLoader.system();
    boolean hasFile = file.toFile().exists();
    ObjectNode cfg = loader.load(file);
    if (!hasFile) {
      System.err.println("[config] " + file + " not found: using OPENROUTER_API_KEY / PANIC_MODEL");
    }

    ObjectNode overrides = Json.object();
    overrides.putObject("cli").put("repl", !once && !serve);
    if (once) overrides.putObject("web").put("enabled", false);
    else if (web || serve) overrides.putObject("web").put("enabled", true);

    ErrorHandler errors = (source, error) -> System.err.println("[panic] " + source + " failed: " + error);
    Out out = Out.of(System.out);
    CountDownLatch exit = new CountDownLatch(1);
    Panic panic;
    try {
      panic = Panic.start(Panic.discover(), cfg, overrides, new Panic.Process(System.in, out, exit::countDown),
          errors, System.err::println);
    } catch (RuntimeException e) {
      fail(e.getMessage());
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(panic::dispose, "shutdown"));

    if (once) {
      int code = Commands.once(panic.root(), command, out, Out.of(System.err), panic.root().signal());
      panic.dispose();
      System.exit(code);
    }

    if (hasFile) panic.root().plugin("config-watch", ConfigWatch.plugin(panic, loader, file, System.err));
    exit.await(); // the REPL's exit, or (--serve) never: Ctrl+C runs the shutdown hook
    panic.dispose();
  }

  private static void fail(String message) {
    System.err.println(message);
    System.exit(2);
  }
}
