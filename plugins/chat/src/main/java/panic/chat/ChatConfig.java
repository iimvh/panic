package panic.chat;

/** {@code systemPrompt}: null or blank for none. {@code maxHistory}: messages of history sent, 0 = all. */
public record ChatConfig(String systemPrompt, int maxHistory) {

  public static final ChatConfig DEFAULT = new ChatConfig("You are Panic, a helpful assistant.", 40);
}
