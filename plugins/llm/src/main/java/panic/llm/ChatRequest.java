package panic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One completion request. {@code model}, {@code temperature} and {@code maxTokens} may be null: the
 * model the request is sent to fills in its own defaults. {@code tools} empty means no tools are offered;
 * {@code toolChoice} null leaves the choice to the provider (normally {@code auto}).
 */
public record ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens,
    List<ToolSpec> tools, ToolChoice toolChoice) {

  public ChatRequest {
    Objects.requireNonNull(messages, "messages");
    messages = List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
  }

  public ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens) {
    this(messages, model, temperature, maxTokens, List.of(), null);
  }

  public static ChatRequest of(List<Message> messages) {
    return new ChatRequest(messages, null, null, null);
  }

  /** This request offering {@code tools} to the model. */
  public ChatRequest withTools(List<ToolSpec> tools, ToolChoice toolChoice) {
    return new ChatRequest(messages, model, temperature, maxTokens, tools, toolChoice);
  }

  /** This request with every null option taken from {@code defaults}. */
  public ChatRequest withDefaults(String model, Double temperature, Integer maxTokens) {
    return new ChatRequest(messages,
        this.model != null ? this.model : model,
        this.temperature != null ? this.temperature : temperature,
        this.maxTokens != null ? this.maxTokens : maxTokens,
        tools, toolChoice);
  }
}
