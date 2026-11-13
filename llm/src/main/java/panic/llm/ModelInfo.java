package panic.llm;

/**
 * One model a provider can serve. {@code free} is TRUE / FALSE when the provider publishes pricing,
 * null when it does not; {@code contextLength} is 0 when unknown.
 */
public record ModelInfo(String id, String name, long contextLength, Boolean free) {
}
