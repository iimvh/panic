package panic.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import panic.plugin.PluginSet;
import panic.plugin.PluginSpec;

/**
 * The plugins of this module: OpenRouter, the custom (OpenAI-compatible) providers, the model selector, and its
 * settings commands.
 */
public final class LlmPlugins implements PluginSet {

  @Override
  public List<PluginSpec<?>> plugins() {
    return List.of(
        PluginSpec.of(OpenRouter.ID, ProviderConfig.class, ProviderConfig.NONE, OpenRouter::plugin),
        PluginSpec.of(CustomProvider.PLUGIN, CustomProvidersConfig.class, CustomProvidersConfig.DEFAULT,
            CustomProvider::plugin),
        PluginSpec.of("llm", LlmConfig.class, LlmConfig.NONE, LlmPlugin::new),
        PluginSpec.of("settings", SettingsPlugin::new));
  }

  /**
   * {@code OPENROUTER_API_KEY} connects OpenRouter and makes it {@code llm.provider}; {@code PANIC_MODEL} is its
   * model. Custom providers are declared in panic.json only.
   */
  @Override
  public Map<String, Map<String, String>> environment(UnaryOperator<String> env) {
    Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    Map<String, String> llm = new LinkedHashMap<>();
    String key = env.apply("OPENROUTER_API_KEY");
    if (key != null && !key.isBlank()) {
      sections.put(OpenRouter.ID, Map.of("apiKey", key));
      llm.put("provider", OpenRouter.ID);
    }
    String model = env.apply("PANIC_MODEL");
    if (model != null && !model.isBlank()) llm.put("model", model);
    sections.put("llm", llm);
    return sections;
  }
}
