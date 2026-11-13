package panic.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import context.Context;
import context.ErrorHandler;
import context.Root;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import panic.command.Out;
import panic.plugin.App;
import panic.plugin.Configs;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/**
 * The plugin tree. It names no plugin: every {@link PluginSet} on the classpath is found with ServiceLoader,
 * and each of its plugins is installed under its name with the panic.json section of that name.
 *
 * The root provides two services to the plugins: {@link Configs}, and {@link App} (terminal, exit). App
 * is provided after every plugin is installed, so a plugin that requires it (a front-end such as the REPL)
 * starts only when the rest of the tree is there, whatever the install order.
 */
public final class Panic {

  private final Root root;
  private final PluginHost host;

  private Panic(Root root, PluginHost host) {
    this.root = root;
    this.host = host;
  }

  /** Every plugin of every PluginSet on the classpath, in classpath order. */
  public static List<PluginSpec<?>> discover() {
    List<PluginSpec<?>> specs = new ArrayList<>();
    for (PluginSet set : discoverSets()) specs.addAll(set.plugins());
    return specs;
  }

  /** Every PluginSet on the classpath, in classpath order. */
  public static List<PluginSet> discoverSets() {
    List<PluginSet> sets = new ArrayList<>();
    for (PluginSet set : ServiceLoader.load(PluginSet.class)) sets.add(set);
    return sets;
  }

  /** The process side of the tree: where the CLI reads and writes, and what "exit" does. */
  public record Process(InputStream in, Out out, Runnable onExit) implements App {
    @Override
    public void exit() {
      onExit.run();
    }
  }

  /**
   * Builds the tree from {@code file} (a loaded panic.json) with {@code overrides} merged over it.
   * Throws (and leaves nothing running) when a plugin fails to start.
   */
  public static Panic start(List<PluginSpec<?>> specs, ObjectNode file, ObjectNode overrides, App app,
      ErrorHandler errors, Consumer<String> warn) {
    Root root = Context.root("panic", errors);
    try {
      PluginHost host = new PluginHost(root, specs, overrides, warn);
      root.provide(Configs.KEY, host);
      host.start(file);
      // Last: front-ends require App, so they start once every other plugin is installed.
      root.provide(App.KEY, app);
      return new Panic(root, host);
    } catch (RuntimeException | Error e) {
      root.dispose();
      throw e;
    }
  }

  public Root root() {
    return root;
  }

  /** The installed plugin names, in install order. */
  public List<String> plugins() {
    return host.names();
  }

  /** Applies a reloaded panic.json: only plugins whose section changed are reconfigured. */
  public void apply(ObjectNode file) {
    host.apply(file);
  }

  public void dispose() {
    root.dispose();
  }
}
