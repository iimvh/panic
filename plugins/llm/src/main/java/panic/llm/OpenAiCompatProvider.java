package panic.llm;

import panic.util.CancelToken;
import com.fasterxml.jackson.databind.JsonNode;
import core.CancelledException;
import core.Signal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared shape of OpenAI-compatible providers: chat through the client, models from {@code GET /models}. */
public abstract class OpenAiCompatProvider implements Provider {

  protected final OpenAiCompatClient client;
  private final String id;
  private final Signal lifetime;

  /** {@code lifetime}: the owning plugin's signal; every call is aborted when the plugin goes away. */
  protected OpenAiCompatProvider(String id, OpenAiCompatClient client, Signal lifetime) {
    this.id = id;
    this.client = client;
    this.lifetime = lifetime;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public ChatModel chat() {
    return client;
  }

  @Override
  public List<ModelInfo> models(Signal signal) {
    JsonNode data = client.get("/models", scoped(signal)).path("data");
    Map<String, ModelInfo> byId = new LinkedHashMap<>();
    for (JsonNode m : data) {
      String modelId = m.path("id").asText("");
      if (modelId.isBlank()) continue;
      byId.putIfAbsent(modelId, new ModelInfo(modelId, m.path("name").asText(modelId),
          m.path("context_length").asLong(m.path("max_context_length").asLong(0)), free(modelId, m),
          maxOutput(m), parameters(m)));
    }
    List<ModelInfo> out = new ArrayList<>(byId.values());
    out.sort(Comparator.comparing(ModelInfo::id));
    return out;
  }

  /**
   * The reply limit a model list states: OpenRouter's {@code top_provider.max_completion_tokens}, else a
   * {@code max_completion_tokens} or {@code max_output_tokens} of the model itself; 0 when none (or null).
   */
  static long maxOutput(JsonNode model) {
    for (JsonNode n : List.of(model.path("top_provider").path("max_completion_tokens"),
        model.path("max_completion_tokens"), model.path("max_output_tokens"))) {
      if (n.canConvertToLong() && n.asLong() > 0) return n.asLong();
    }
    return 0;
  }

  /** The parameters a model list says the model takes ({@code supported_parameters}); empty when it does not say. */
  static Set<String> parameters(JsonNode model) {
    Set<String> out = new HashSet<>();
    for (JsonNode p : model.path("supported_parameters")) {
      if (p.isTextual() && !p.asText().isBlank()) out.add(p.asText());
    }
    return out;
  }

  @Override
  public KeyCheck checkKey(Signal signal) {
    try {
      return verify(scoped(signal));
    } catch (CancelledException e) {
      throw e;
    } catch (LlmException e) {
      if (e.status() == 401 || e.status() == 403) return new KeyCheck(false, id + " rejected the key");
      return new KeyCheck(false, e.getMessage());
    } catch (RuntimeException e) {
      return new KeyCheck(false, id + " key check failed: " + e.getMessage());
    }
  }

  /** Calls the provider with the key; throws LlmException when it is refused. */
  protected abstract KeyCheck verify(Signal signal);

  /** Whether a model is free to use; null when the provider does not say. */
  protected abstract Boolean free(String modelId, JsonNode model);

  /** Cancelled by the caller or by the plugin's disposal, whichever comes first. */
  protected Signal scoped(Signal signal) {
    return CancelToken.any(signal, lifetime);
  }
}
