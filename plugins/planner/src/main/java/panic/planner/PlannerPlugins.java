package panic.planner;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: plan mode (research read-only, propose a plan, execute it once approved). */
public final class PlannerPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("planner", PlannerConfig.class, PlannerConfig.DEFAULT, PlannerPlugin::new));
  }
}
