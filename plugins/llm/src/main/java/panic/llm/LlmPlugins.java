package panic.llm;

import java.util.List;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/** The plugins of this module: two providers, the model selector, and its settings commands. */
public final class LlmPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of(OpenRouter.ID, ProviderConfig.class, ProviderConfig.NONE, OpenRouter::plugin),
        PluginSpec.of(Mistral.ID, ProviderConfig.class, ProviderConfig.NONE, Mistral::plugin),
        PluginSpec.of("llm", LlmConfig.class, LlmConfig.NONE, LlmPlugin::new),
        PluginSpec.of("settings", SettingsPlugin::new));
  }
}
