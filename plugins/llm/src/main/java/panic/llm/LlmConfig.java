package panic.llm;

/**
 * Which provider and model to chat with, plus default options (null = provider default). {@code reasoningEffort}
 * ({@code low}, {@code medium}, {@code high}, ...) is sent as {@code reasoning_effort} with every request that does
 * not choose its own (see {@link ChatRequest}): hybrid models (DeepSeek V4 on some hosts, for one) think only when
 * it is set. Null or blank sends none. Thinking tokens count against {@code maxTokens}.
 */
public record LlmConfig(String provider, String model, Double temperature, Integer maxTokens,
    String reasoningEffort) {

  /** Nothing selected. */
  public static final LlmConfig NONE = new LlmConfig(null, null, null, null, null);

  public LlmConfig {
    reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
  }

  public LlmConfig(String provider, String model, Double temperature, Integer maxTokens) {
    this(provider, model, temperature, maxTokens, null);
  }

  public LlmConfig withProvider(String provider) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort);
  }

  public LlmConfig withModel(String model) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort);
  }

  public LlmConfig withReasoningEffort(String reasoningEffort) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort);
  }
}
