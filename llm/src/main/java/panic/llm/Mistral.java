package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import core.Signal;

/**
 * Mistral's own API (OpenAI-compatible chat). Its {@code content} may come back as an array of parts,
 * which {@link Content} handles. The models list publishes no pricing.
 */
public final class Mistral extends OpenAiCompatProvider {

  public static final String ID = "mistral";
  public static final String BASE_URL = "https://api.mistral.ai/v1";

  Mistral(OpenAiCompatClient client, Signal lifetime) {
    super(ID, client, lifetime);
  }

  /** The plugin: provides {@code Providers.key("mistral")} while it has a key. */
  public static ConfigPlugin<ProviderConfig> plugin() {
    return new ConfigPlugin<>() {
      @Override
      public Disposable apply(Context ctx, ProviderConfig cfg) {
        if (!cfg.hasKey()) return null;
        OpenAiCompatClient client = new OpenAiCompatClient(ID, cfg.baseUrlOr(BASE_URL), cfg.apiKey());
        ctx.provide(Providers.key(ID), new Mistral(client, ctx.signal()));
        return null;
      }
    };
  }

  @Override
  protected KeyCheck verify(Signal signal) {
    int count = client.get("/models", signal).path("data").size();
    return new KeyCheck(true, "key accepted (" + count + " models)");
  }

  @Override
  protected Boolean free(String modelId, JsonNode model) {
    return null;
  }
}
