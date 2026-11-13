package panic.agents;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: sub-agents the model can hand a task to. */
public final class AgentPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("agents", AgentsConfig.class, AgentsConfig.DEFAULT, AgentsPlugin::new));
  }
}
