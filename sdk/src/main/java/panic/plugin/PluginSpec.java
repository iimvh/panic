package panic.plugin;

import context.ConfigPlugin;
import context.Plugin;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One installable plugin, Koishi style: a name, a config type with its default, and the plugin itself.
 *
 * The app installs it under its name and reads its config from the panic.json section of the same name
 * ({@code "web": {"port": 8080}}), converted to {@code configType} (records are ideal). A missing section
 * means the default; {@code false} means "not installed". Saving the file reconfigures only the plugins
 * whose section changed.
 *
 * Plugins are found through {@link PluginSet}; nothing in the app names them.
 */
public record PluginSpec<C>(String name, Class<C> configType, C defaultConfig, Supplier<ConfigPlugin<C>> factory) {

  public PluginSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(configType, "configType");
    Objects.requireNonNull(defaultConfig, "defaultConfig");
    Objects.requireNonNull(factory, "factory");
    if (!name.matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("bad plugin name '" + name + "'");
  }

  /** A plugin with a config. */
  public static <C> PluginSpec<C> of(String name, Class<C> configType, C defaultConfig,
      Supplier<ConfigPlugin<C>> factory) {
    return new PluginSpec<>(name, configType, defaultConfig, factory);
  }

  /** A plugin without config: its section can only be left out (installed) or set to {@code false}. */
  public static PluginSpec<NoConfig> of(String name, Supplier<Plugin> factory) {
    Objects.requireNonNull(factory, "factory");
    return new PluginSpec<>(name, NoConfig.class, NoConfig.INSTANCE, () -> {
      Plugin plugin = factory.get();
      return new ConfigPlugin<>() {
        @Override
        public java.util.List<context.ServiceKey<?>> requires() {
          return plugin.requires();
        }

        @Override
        public core.Disposable apply(context.Context ctx, NoConfig config) {
          return plugin.apply(ctx);
        }
      };
    });
  }
}
