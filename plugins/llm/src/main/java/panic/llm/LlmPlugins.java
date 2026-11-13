package panic.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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

  /**
   * {@code OPENROUTER_API_KEY} / {@code MISTRAL_API_KEY} connect their provider; the first one set becomes
   * {@code llm.provider}, and {@code PANIC_MODEL} its model.
   */
  @Override
  public Map<String, Map<String, String>> environment(UnaryOperator<String> env) {
    Map<String, Map<String, String>> sections = new LinkedHashMap<>();
    Map<String, String> llm = new LinkedHashMap<>();
    for (String id : List.of(OpenRouter.ID, Mistral.ID)) {
      String key = env.apply(id.toUpperCase() + "_API_KEY");
      if (key == null || key.isBlank()) continue;
      sections.put(id, Map.of("apiKey", key));
      llm.putIfAbsent("provider", id);
    }
    String model = env.apply("PANIC_MODEL");
    if (model != null && !model.isBlank()) llm.put("model", model);
    sections.put("llm", llm);
    return sections;
  }
}
