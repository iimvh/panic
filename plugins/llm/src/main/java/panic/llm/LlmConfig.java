package panic.llm;

/**
 * Which provider and model to chat with, plus default options (null = provider default). {@code reasoningEffort}
 * ({@code low}, {@code medium}, {@code high}, ...) is sent as {@code reasoning_effort} with every request that does
 * not choose its own (see {@link ChatRequest}): hybrid models (DeepSeek V4 on some hosts, for one) think only when
 * it is set. Null or blank sends none. Thinking tokens count against {@code maxTokens}.
 *
 * {@code contextWindow}: the model's context length in tokens, for providers whose model list does not say it
 * (null or 0 = take it from the list, see {@link Llm#inputWindow}).
 */
public record LlmConfig(String provider, String model, Double temperature, Integer maxTokens,
    String reasoningEffort, Integer contextWindow) {

  /** Nothing selected. */
  public static final LlmConfig NONE = new LlmConfig(null, null, null, null, null, null);

  public LlmConfig {
    reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
    if (contextWindow != null && contextWindow <= 0) contextWindow = null;
  }

  public LlmConfig(String provider, String model, Double temperature, Integer maxTokens, String reasoningEffort) {
    this(provider, model, temperature, maxTokens, reasoningEffort, null);
  }

  public LlmConfig(String provider, String model, Double temperature, Integer maxTokens) {
    this(provider, model, temperature, maxTokens, null);
  }

  /** Another provider: the context window given for the old model no longer applies. */
  public LlmConfig withProvider(String provider) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort, null);
  }

  /** Another model: the context window given for the old one no longer applies. */
  public LlmConfig withModel(String model) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort, null);
  }

  public LlmConfig withReasoningEffort(String reasoningEffort) {
    return new LlmConfig(provider, model, temperature, maxTokens, reasoningEffort, contextWindow);
  }
}
