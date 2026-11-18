package panic.memory;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: what the agent knows across conversations. */
public final class MemoryPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of("instructions", InstructionsConfig.class, InstructionsConfig.DEFAULT,
            () -> new InstructionsPlugin(System.err::println)),
        PluginSpec.of("memory", MemoryConfig.class, MemoryConfig.DEFAULT, MemoryPlugin::new));
  }
}
