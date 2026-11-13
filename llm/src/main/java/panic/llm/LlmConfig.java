package panic.llm;

/** Which provider and model to chat with, plus default options (null = provider default). */
public record LlmConfig(String provider, String model, Double temperature, Integer maxTokens) {

  public LlmConfig withProvider(String provider) {
    return new LlmConfig(provider, model, temperature, maxTokens);
  }

  public LlmConfig withModel(String model) {
    return new LlmConfig(provider, model, temperature, maxTokens);
  }
}
