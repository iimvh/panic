package panic.chat;

/**
 * {@code systemPrompt}: null or blank for none. {@code maxHistory}: messages of history sent, 0 = all.
 *
 * The agent loop: {@code tools} offers the registered tools to the model (null = true; turn it off for models
 * without tool support). One question runs at most {@code maxSteps} model calls and stops once the tokens used
 * reach {@code maxTokens} (0 = no budget). A tool call is given {@code toolTimeoutSeconds}, its output is cut
 * at {@code maxToolOutput} characters, and the same call repeated more than {@code maxRepeats} times in a row
 * is refused (as feedback: the turn goes on). Missing or non-positive numbers take the default.
 *
 * The context window: every request is kept under the turn's {@link Budget} (see {@link #budget}): {@code
 * contextTokens} estimated tokens when positive, none when 0 or less (only {@code maxHistory} applies), and when null
 * the model's own window ({@code Llm.inputWindow()}, at most {@link #AUTO_MAX_CONTEXT_TOKENS}), or
 * {@link #DEFAULT_CONTEXT_TOKENS} while that is unknown. The oldest turns are left out first; with {@code compact}
 * (null = true) they are first summarised by the model into one summary kept with the conversation, and a turn that
 * alone outgrows the budget has its older steps summarised too ({@link TurnCompaction}).
 *
 * Review: when a reviewer answers {@link Chat#REVIEW} and rejects a final answer, the reason goes back to the
 * model at most {@code maxReviews} times (null = 1, 0 = answers are never reviewed); after that the turn stops
 * with {@link Chat#REJECTED}.
 *
 * Parallel tools: the calls of one model reply run up to {@code parallelTools} at once (missing or below 1 = 1,
 * one after the other). Their results still go back to the model in the order it made the calls.
 *
 * Tool result clearing (see {@link ContextEditor}): once a request, as it will be sent, is estimated over
 * {@code clearToolResultsAt} tokens (null = three quarters of the context budget; 0 or less, or no context limit =
 * never), tool results are cleared until it is under two thirds of that, never the newest {@code keepToolResults}
 * (missing or below 1 = 3); a result once cleared stays so for the turn. Only the request changes; the conversation
 * keeps the results. {@code editing} turns the mechanisms that choose what is cleared on or off ({@link Editing};
 * null = all on).
 *
 * {@code maxTokens}: the turn's token budget; null = {@link #DEFAULT_MAX_TOKENS}, 0 = none.
 */
public record ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
    int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact,
    Integer maxReviews, int parallelTools, Integer clearToolResultsAt, int keepToolResults, Editing editing) {

  public static final ChatConfig DEFAULT = new ChatConfig("You are Panic, a helpful assistant.", 40, null, 0, null,
      0, 0, 0, null, null, null, 0, null, 0, null);

  /**
   * What a cleared result leaves, beyond clearing by age with hysteresis: {@code resources} (a pointer instead of an
   * unchanged re-read, and a stale file view says it is stale), {@code compact} (a cleared file view leaves its
   * outline). Null = true. Both make the context smaller; which result gives way is only a matter of age, since the
   * order cannot save a working set that does not fit (eval/README.md). Each stays only while it earns its keep.
   */
  public record Editing(Boolean resources, Boolean compact) {

    public static final Editing ALL = new Editing(true, true);

    public Editing {
      if (resources == null) resources = Boolean.TRUE;
      if (compact == null) compact = Boolean.TRUE;
    }
  }

  /**
   * One turn's context limits: requests stay under {@code contextTokens} (0 = no limit); tool results are cleared
   * past {@code clearAt} down to {@code target} (0 = never).
   */
  public record Budget(int contextTokens, int clearAt, int target) {

    public boolean limited() {
      return contextTokens > 0;
    }
  }

  /** The budget when neither the config nor the model says one. */
  public static final int DEFAULT_CONTEXT_TOKENS = 32_000;

  /**
   * The most a budget taken from the model's window is: every step sends the whole request again, so its cost grows
   * with it, and models recall less from very long contexts, while a coding task's working set is far smaller.
   */
  public static final int AUTO_MAX_CONTEXT_TOKENS = 200_000;

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
    if (contextTokens != null && contextTokens < 0) contextTokens = 0;
    if (compact == null) compact = Boolean.TRUE;
    if (maxReviews == null) maxReviews = 1;
    if (maxReviews < 0) maxReviews = 0;
    if (parallelTools <= 0) parallelTools = 1;
    if (clearToolResultsAt != null && clearToolResultsAt < 0) clearToolResultsAt = 0;
    if (keepToolResults <= 0) keepToolResults = 3;
    if (editing == null) editing = Editing.ALL;
  }

  public ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, Integer maxTokens,
      int toolTimeoutSeconds, int maxToolOutput, int maxRepeats, Integer contextTokens, Boolean compact,
      Integer maxReviews, int parallelTools, Integer clearToolResultsAt, int keepToolResults) {
    this(systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats,
        contextTokens, compact, maxReviews, parallelTools, clearToolResultsAt, keepToolResults, null);
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

  /** The limits of a turn for a model whose window is {@code inputWindow} tokens (0 = unknown). */
  public Budget budget(int inputWindow) {
    int context = contextTokens != null ? contextTokens
        : inputWindow > 0 ? Math.min(inputWindow, AUTO_MAX_CONTEXT_TOKENS) : DEFAULT_CONTEXT_TOKENS;
    if (context <= 0) return new Budget(0, 0, 0);
    int clearAt = clearToolResultsAt != null ? clearToolResultsAt : context / 4 * 3;
    return new Budget(context, clearAt, clearAt / 3 * 2);
  }
}
