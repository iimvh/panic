package panic.skills;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: skills the agent loads when a task needs them. */
public final class SkillPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(PluginSpec.of("skills", SkillsConfig.class, SkillsConfig.DEFAULT,
        () -> new SkillsPlugin(System.err::println)));
  }
}
