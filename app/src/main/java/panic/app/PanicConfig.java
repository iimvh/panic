package panic.app;

import java.util.Map;
import java.util.TreeMap;
import panic.chat.ChatConfig;
import panic.llm.LlmConfig;
import panic.llm.ProviderConfig;

/** The whole config file: one section per configured plugin. Every section is a record, compared by equals(). */
public record PanicConfig(Map<String, ProviderConfig> providers, LlmConfig llm, ChatConfig chat) {

  public PanicConfig {
    providers = providers == null ? Map.of() : Map.copyOf(new TreeMap<>(providers));
    llm = llm == null ? new LlmConfig(null, null, null, null) : llm;
    chat = chat == null ? ChatConfig.DEFAULT : chat;
  }

  public ProviderConfig provider(String id) {
    return providers.getOrDefault(id, ProviderConfig.NONE);
  }
}
