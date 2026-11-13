package panic.tools;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the tool commands, the permission policy and the built-in tools. */
public final class ToolPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of("tools", ToolsPlugin::new),
        PluginSpec.of("permissions", PermissionsConfig.class, PermissionsConfig.DEFAULT, PermissionsPlugin::new),
        PluginSpec.of("files", FilesConfig.class, FilesConfig.DEFAULT, FilesPlugin::new),
        PluginSpec.of("clock", ClockPlugin::new));
  }
}
