package panic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One completion request. {@code model}, {@code temperature}, {@code maxTokens} and {@code reasoningEffort} may
 * be null: the model the request is sent to fills in its own defaults ({@code llm}'s config). {@code tools} empty
 * means no tools are offered; {@code toolChoice} null leaves the choice to the provider (normally {@code auto}).
 *
 * {@code reasoningEffort} is sent as {@code reasoning_effort} ({@code low}, {@code medium}, {@code high}, or any
 * level the provider knows): how much a reasoning model thinks before it answers, and whether a hybrid one thinks
 * at all. {@link #NO_REASONING_EFFORT} sends none even when the default has one, for a call that must not spend
 * its tokens thinking (a short summary, a verdict under a small limit).
 */
public record ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens,
    List<ToolSpec> tools, ToolChoice toolChoice, String reasoningEffort) {

  /** A {@code reasoningEffort} that sends no {@code reasoning_effort}, whatever the default. */
  public static final String NO_REASONING_EFFORT = "";

  public ChatRequest {
    Objects.requireNonNull(messages, "messages");
    messages = List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
    if (reasoningEffort != null) reasoningEffort = reasoningEffort.strip();
  }

  public ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens,
      List<ToolSpec> tools, ToolChoice toolChoice) {
    this(messages, model, temperature, maxTokens, tools, toolChoice, null);
  }

  public ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens) {
    this(messages, model, temperature, maxTokens, List.of(), null);
  }

  public static ChatRequest of(List<Message> messages) {
    return new ChatRequest(messages, null, null, null);
  }

  /** This request offering {@code tools} to the model. */
  public ChatRequest withTools(List<ToolSpec> tools, ToolChoice toolChoice) {
    return new ChatRequest(messages, model, temperature, maxTokens, tools, toolChoice, reasoningEffort);
  }

  /** This request with {@code effort} ({@link #NO_REASONING_EFFORT} for none, null for the default). */
  public ChatRequest withReasoningEffort(String effort) {
    return new ChatRequest(messages, model, temperature, maxTokens, tools, toolChoice, effort);
  }

  /** The {@code reasoning_effort} to send, or null to send none. */
  public String sentReasoningEffort() {
    return reasoningEffort == null || reasoningEffort.isEmpty() ? null : reasoningEffort;
  }

  /** This request with every null option taken from {@code defaults}. */
  public ChatRequest withDefaults(String model, Double temperature, Integer maxTokens) {
    return withDefaults(model, temperature, maxTokens, null);
  }

  /** This request with every null option taken from {@code defaults}. */
  public ChatRequest withDefaults(String model, Double temperature, Integer maxTokens, String reasoningEffort) {
    return new ChatRequest(messages,
        this.model != null ? this.model : model,
        this.temperature != null ? this.temperature : temperature,
        this.maxTokens != null ? this.maxTokens : maxTokens,
        tools, toolChoice,
        this.reasoningEffort != null ? this.reasoningEffort : reasoningEffort);
  }
}
