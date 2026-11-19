package panic.llm;

import java.util.Set;

/**
 * One model a provider can serve. {@code free} is TRUE / FALSE when the provider publishes pricing,
 * null when it does not; {@code contextLength} is 0 when unknown. {@code maxOutput}: the most tokens one reply may
 * have, 0 when unknown. {@code parameters}: the request parameters the model takes ({@code reasoning},
 * {@code tools}, ..., OpenRouter's {@code supported_parameters}), empty when the provider does not say.
 */
public record ModelInfo(String id, String name, long contextLength, Boolean free, long maxOutput,
    Set<String> parameters) {

  public ModelInfo {
    if (contextLength < 0) contextLength = 0;
    if (maxOutput < 0) maxOutput = 0;
    parameters = parameters == null ? Set.of() : Set.copyOf(parameters);
  }

  public ModelInfo(String id, String name, long contextLength, Boolean free) {
    this(id, name, contextLength, free, 0, Set.of());
  }

  /**
   * Whether the model takes a reasoning effort: TRUE / FALSE when the provider lists its parameters, null when it
   * does not (then nobody knows, and the effort is sent as configured).
   */
  public Boolean reasoning() {
    if (parameters.isEmpty()) return null;
    return parameters.contains("reasoning") || parameters.contains("reasoning_effort");
  }
}
