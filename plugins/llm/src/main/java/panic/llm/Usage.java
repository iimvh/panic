package panic.llm;

/**
 * Token counts reported by the provider; zero when it reported none. {@code reasoningTokens} is the part of
 * {@code completionTokens} the model spent thinking ({@code completion_tokens_details.reasoning_tokens}), reported
 * also by providers that do not show the thinking itself.
 */
public record Usage(int promptTokens, int completionTokens, int reasoningTokens) {

  public static final Usage NONE = new Usage(0, 0, 0);

  public Usage(int promptTokens, int completionTokens) {
    this(promptTokens, completionTokens, 0);
  }

  public int totalTokens() {
    return promptTokens + completionTokens;
  }

  /** Both counts added up, for the totals of a turn. */
  public Usage plus(Usage other) {
    return new Usage(promptTokens + other.promptTokens, completionTokens + other.completionTokens,
        reasoningTokens + other.reasoningTokens);
  }
}
