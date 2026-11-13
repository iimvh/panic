package panic.llm;

/** Token counts reported by the provider; zero when it reported none. */
public record Usage(int promptTokens, int completionTokens) {

  public static final Usage NONE = new Usage(0, 0);

  public int totalTokens() {
    return promptTokens + completionTokens;
  }
}
