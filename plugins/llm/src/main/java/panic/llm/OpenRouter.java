package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import core.Signal;

/**
 * OpenRouter: one OpenAI-compatible endpoint in front of many models. Free models carry a {@code :free}
 * suffix or zero prompt and completion pricing.
 */
public final class OpenRouter extends OpenAiCompatProvider {

  public static final String ID = "openrouter";
  public static final String BASE_URL = "https://openrouter.ai/api/v1";

  OpenRouter(OpenAiCompatClient client, Signal lifetime) {
    super(ID, client, lifetime);
  }

  /** The plugin: provides {@code Providers.key("openrouter")} while it has a key. */
  public static ConfigPlugin<ProviderConfig> plugin() {
    return new ConfigPlugin<>() {
      @Override
      public Disposable apply(Context ctx, ProviderConfig cfg) {
        ctx.hook(Providers.STATUS, q -> new Providers.Status(ID, cfg.hasKey()));
        if (!cfg.hasKey()) return null;
        OpenAiCompatClient client = new OpenAiCompatClient(ID, cfg.baseUrlOr(BASE_URL), cfg.apiKey(),
            cfg.retryPolicy());
        ctx.provide(Providers.key(ID), new OpenRouter(client, ctx.signal()));
        return null;
      }
    };
  }

  @Override
  protected KeyCheck verify(Signal signal) {
    JsonNode key = client.get("/key", signal).path("data");
    String label = key.path("label").asText("");
    StringBuilder detail = new StringBuilder("key accepted");
    if (!label.isBlank()) detail.append(" ('").append(label).append("')");
    if (key.path("is_free_tier").asBoolean(false)) detail.append(", free tier");
    return new KeyCheck(true, detail.toString());
  }

  @Override
  protected Boolean free(String modelId, JsonNode model) {
    if (modelId.toLowerCase().endsWith(":free")) return true;
    JsonNode pricing = model.path("pricing");
    if (!pricing.isObject()) return null;
    return zero(pricing.path("prompt")) && zero(pricing.path("completion"));
  }

  private static boolean zero(JsonNode price) {
    String text = price.asText("").strip();
    if (text.isEmpty()) return true;
    try {
      return Double.parseDouble(text) == 0.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
