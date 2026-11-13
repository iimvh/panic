package panic.app;

import context.ErrorHandler;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** {@code panic [config.json]}: starts the plugin tree and the terminal chat bot. */
public final class Main {

  private Main() {
  }

  public static void main(String[] args) throws Exception {
    Path file = Path.of(args.length > 0 ? args[0] : "panic.json");
    ConfigLoader loader = ConfigLoader.system();
    PanicConfig cfg = loader.load(file);
    if (!file.toFile().exists()) {
      System.err.println("[config] " + file + " not found: using OPENROUTER_API_KEY / MISTRAL_API_KEY / PANIC_MODEL");
    }

    ErrorHandler errors = (source, error) -> System.err.println("[panic] " + source + " failed: " + error);
    Panic panic = Panic.start(cfg, errors);
    CountDownLatch exit = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(panic::dispose, "shutdown"));

    if (file.toFile().exists()) {
      panic.root().plugin("config-watch", ConfigWatch.plugin(panic, loader, file, System.err));
    }
    panic.root().plugin("repl", new Repl(panic, new Stdin(System.in), System.out, exit::countDown).plugin());

    exit.await();
    panic.dispose();
  }
}
