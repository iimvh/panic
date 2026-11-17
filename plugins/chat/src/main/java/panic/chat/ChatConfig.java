package panic.chat;

/**
 * {@code systemPrompt}: null or blank for none. {@code maxHistory}: messages of history sent, 0 = all.
 *
 * The agent loop: {@code tools} offers the registered tools to the model (null = true; turn it off for models
 * without tool support). One question runs at most {@code maxSteps} model calls and stops once the tokens used
 * reach {@code maxTokens} (0 = no budget). A tool call is given {@code toolTimeoutSeconds}, its output is cut
 * at {@code maxToolOutput} characters, and the same call repeated more than {@code maxRepeats} times in a row
 * is refused, then ends the turn. Missing or non-positive numbers take the default.
 *
 * The context window: every request is kept under {@code contextTokens} estimated tokens (null = 32000, 0 or
 * less = no limit, only {@code maxHistory} applies) by leaving out the oldest turns. With {@code compact}
 * (null = true) the turns left out are first summarised by the model into one summary kept with the
 * conversation.
 *
 * Review: when a reviewer answers {@link Chat#REVIEW} and rejects a final answer, the reason goes back to the
 * model at most {@code maxReviews} times (null = 1, 0 = answers are never reviewed); after that the turn stops
 * with {@link Chat#REJECTED}.
 *
 * Parallel tools: the calls of one model reply run up to {@code parallelTools} at once (missing or below 1 = 1,
 * one after the other). Their results still go back to the model in the order it made the calls.
 *
 * Tool result clearing (after Anthropic's context editing, {@code clear_tool_uses}): once a request is estimated
 * over {@code clearToolResultsAt} tokens (null = half of {@code contextTokens}; 0 or less, or no context limit =
 * never), every tool result but the newest {@code keepToolResults} (missing or below 1 = 3) is sent as a one-line
 * placeholder. Only the request changes; the conversation keeps the results.
 *
 * {@code maxTokens}: the turn's token budget; null = {@link #DEFAULT_MAX_TOKENS}, 0 = none.
 */
public record ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
    int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact,
    Integer maxReviews, int parallelTools, Integer clearToolResultsAt, int keepToolResults) {

  public static final ChatConfig DEFAULT = new ChatConfig("You are Panic, a helpful assistant.", 40, null, 0, null,
      0, 0, 0, null, null, null, 0, null, 0);

  public static final int DEFAULT_CONTEXT_TOKENS = 32_000;

  /** A turn that spends this many tokens stops ({@link Chat#BUDGET}) unless the config says otherwise. */
  public static final int DEFAULT_MAX_TOKENS = 400_000;

  public ChatConfig {
    if (maxHistory < 0) maxHistory = 0;
    if (tools == null) tools = Boolean.TRUE;
    if (maxSteps <= 0) maxSteps = 25;
    if (maxTokens == null) maxTokens = DEFAULT_MAX_TOKENS;
    if (maxTokens < 0) maxTokens = 0;
    if (toolTimeoutSeconds <= 0) toolTimeoutSeconds = 60;
    if (maxToolOutput <= 0) maxToolOutput = 16_000;
    if (maxRepeats <= 0) maxRepeats = 2;
    if (contextTokens == null) contextTokens = DEFAULT_CONTEXT_TOKENS;
    if (contextTokens < 0) contextTokens = 0;
    if (compact == null) compact = Boolean.TRUE;
    if (maxReviews == null) maxReviews = 1;
    if (maxReviews < 0) maxReviews = 0;
    if (parallelTools <= 0) parallelTools = 1;
    if (clearToolResultsAt == null) clearToolResultsAt = contextTokens / 2;
    if (clearToolResultsAt < 0 || contextTokens == 0) clearToolResultsAt = 0;
    if (keepToolResults <= 0) keepToolResults = 3;
  }

  public ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
      int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact,
      Integer maxReviews, int parallelTools) {
    this(systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats,
        contextTokens, compact, maxReviews, parallelTools, null, 0);
  }

  public ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
      int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact,
      Integer maxReviews) {
    this(systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats,
        contextTokens, compact, maxReviews, 1);
  }

  public ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
      int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact) {
    this(systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats,
        contextTokens, compact, null);
  }

  public ChatConfig(String systemPrompt, int maxHistory) {
    this(systemPrompt, maxHistory, null, 0, 0, 0, 0, 0, null, null);
  }

  /** True when requests are kept under {@link #contextTokens}. */
  public boolean limitsContext() {
    return contextTokens > 0;
  }
}
