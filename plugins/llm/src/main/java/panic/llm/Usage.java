package panic.llm;

/**
 * Token counts reported by the provider; zero when it reported none. {@code reasoningTokens} is the part of
 * {@code completionTokens} the model spent thinking ({@code completion_tokens_details.reasoning_tokens}), reported
 * also by providers that do not show the thinking itself. {@code cachedTokens} is the part of {@code promptTokens}
 * the provider served from its prompt cache ({@code prompt_tokens_details.cached_tokens}, or DeepSeek's
 * {@code prompt_cache_hit_tokens}): what tells whether the requests keep a stable prefix.
 */
public record Usage(int promptTokens, int completionTokens, int reasoningTokens, int cachedTokens) {

  public static final Usage NONE = new Usage(0, 0, 0, 0);

  public Usage(int promptTokens, int completionTokens, int reasoningTokens) {
    this(promptTokens, completionTokens, reasoningTokens, 0);
  }

  public Usage(int promptTokens, int completionTokens) {
    this(promptTokens, completionTokens, 0, 0);
  }

  public int totalTokens() {
    return promptTokens + completionTokens;
  }

  /** Both counts added up, for the totals of a turn. */
  public Usage plus(Usage other) {
    return new Usage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
        reasoningTokens + other.reasoningTokens, cachedTokens + other.cachedTokens);
  }
}
