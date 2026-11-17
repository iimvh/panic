package panic.interact;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: the agent's ways to interact with the user during a turn. */
public final class InteractPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("ask-user", AskUserConfig.class, AskUserConfig.DEFAULT, AskUserPlugin::new));
  }
}
