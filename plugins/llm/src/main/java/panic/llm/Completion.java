package panic.llm;

/** The full answer of one request. {@code finishReason} may be null when the provider sent none. */
public record Completion(String text, String model, String finishReason, Usage usage) {
}
