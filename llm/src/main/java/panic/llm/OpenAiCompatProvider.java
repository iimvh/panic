package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import core.CancelledException;
import core.Signal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
          m.path("context_length").asLong(m.path("max_context_length").asLong(0)), free(modelId, m)));
    }
    List<ModelInfo> out = new ArrayList<>(byId.values());
    out.sort(Comparator.comparing(ModelInfo::id));
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
