package panic.chat;

/**
 * {@code systemPrompt}: null or blank for none. {@code maxHistory}: messages of history sent, 0 = all.
 *
 * The agent loop: {@code tools} offers the registered tools to the model (null = true; turn it off for models
 * without tool support). One question runs at most {@code maxSteps} model calls and stops once the tokens used
 * reach {@code maxTokens} (0 = no budget). A tool call is given {@code toolTimeoutSeconds}, its output is cut
 * at {@code maxToolOutput} characters, and the same call repeated more than {@code maxRepeats} times in a row
 * is refused, then ends the turn. Missing or non-positive numbers take the default.
 */
public record ChatConfig(String systemPrompt, int maxHistory, Boolean tools, int maxSteps, int maxTokens,
    int toolTimeoutSeconds, int maxToolOutput, int maxRepeats) {

  public static final ChatConfig DEFAULT = new ChatConfig("You are Panic, a helpful assistant.", 40);

  public ChatConfig {
    if (maxHistory < 0) maxHistory = 0;
    if (tools == null) tools = Boolean.TRUE;
    if (maxSteps <= 0) maxSteps = 8;
    if (maxTokens < 0) maxTokens = 0;
    if (toolTimeoutSeconds <= 0) toolTimeoutSeconds = 60;
    if (maxToolOutput <= 0) maxToolOutput = 16_000;
    if (maxRepeats <= 0) maxRepeats = 2;
  }

  public ChatConfig(String systemPrompt, int maxHistory) {
    this(systemPrompt, maxHistory, null, 0, 0, 0, 0, 0);
  }
}
