package panic.plugin;

import context.ServiceKey;

/**
 * Reads and changes the config of installed plugins at runtime, by name (provided by the app). A change
 * made here lasts until the plugin's section of panic.json is edited.
 *
 * Do not update a plugin's config from inside that same plugin (its restart would wait for the caller):
 * declare such commands in a separate plugin, as the {@code settings} plugin does for {@code llm}.
 */
public interface Configs {

  ServiceKey<Configs> KEY = ServiceKey.of("configs", Configs.class);

  /** The plugin's current config; throws IllegalArgumentException when no such plugin is installed. */
  <C> C get(String plugin, Class<C> type);

  /** Reconfigures (or restarts) the plugin with {@code config}. */
  <C> void update(String plugin, C config);
}
