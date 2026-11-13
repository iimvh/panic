package panic.cli;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the terminal front-end. */
public final class CliPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("cli", CliConfig.class, CliConfig.DEFAULT, Cli::new));
  }
}
