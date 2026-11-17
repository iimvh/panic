package panic.verify;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the {@code check} tool and the reviewer of final answers. */
public final class VerifyPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of("checks", ChecksConfig.class, ChecksConfig.DEFAULT, ChecksPlugin::new),
        PluginSpec.of("review", ReviewConfig.class, ReviewConfig.DEFAULT, ReviewPlugin::new),
        PluginSpec.of("stop-hooks", StopHooksConfig.class, StopHooksConfig.DEFAULT, StopHooksPlugin::new));
  }
}
