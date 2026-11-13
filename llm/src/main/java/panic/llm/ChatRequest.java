package panic.llm;

import java.util.List;
import java.util.Objects;

/**
 * One completion request. {@code model}, {@code temperature} and {@code maxTokens} may be null: the
 * model the request is sent to fills in its own defaults.
 */
public record ChatRequest(List<Message> messages, String model, Double temperature, Integer maxTokens) {

  public ChatRequest {
    Objects.requireNonNull(messages, "messages");
    messages = List.copyOf(messages);
  }

  public static ChatRequest of(List<Message> messages) {
    return new ChatRequest(messages, null, null, null);
  }

  /** This request with every null option taken from {@code defaults}. */
  public ChatRequest withDefaults(String model, Double temperature, Integer maxTokens) {
    return new ChatRequest(messages,
        this.model != null ? this.model : model,
        this.temperature != null ? this.temperature : temperature,
        this.maxTokens != null ? this.maxTokens : maxTokens);
  }
}
