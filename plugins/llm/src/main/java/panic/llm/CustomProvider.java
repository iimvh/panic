package panic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.ConfigPlugin;
import context.Context;
import core.Disposable;
import core.Signal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An OpenAI-compatible endpoint declared in the {@code custom-providers} config, with no behaviour of its own:
 * the key check lists the models, and no model is known to be free. A provider whose API differs gets a class
 * of its own instead (like {@link OpenRouter}).
 */
public final class CustomProvider extends OpenAiCompatProvider {

  public static final String PLUGIN = "custom-providers";

  CustomProvider(String id, OpenAiCompatClient client, Signal lifetime) {
    super(id, client, lifetime);
  }

  /**
   * The plugin: one child plugin per entry, named after its id, which answers {@code Providers.STATUS} and provides
   * {@code Providers.key(id)} while connected. A config change only touches the entries that changed
   * ({@link ConfigPlugin#reconfigure}): editing one endpoint never retracts the others, so a chat running on another
   * custom provider (and everything that requires its {@code Llm.KEY}) is left alone.
   */
  public static ConfigPlugin<CustomProvidersConfig> plugin() {
    return new ConfigPlugin<>() {
      /** The child of each entry; owned by this instance's context, which disposes them with it. */
      private final Map<String, ConfigHandle<CustomProvidersConfig.Endpoint>> entries = new LinkedHashMap<>();

      @Override
      public Disposable apply(Context ctx, CustomProvidersConfig cfg) {
        entries.clear();
        cfg.providers().forEach((id, endpoint) -> entries.put(id, ctx.plugin(id, endpoint(id), endpoint)));
        return entries::clear;
      }

      @Override
      public boolean reconfigure(Context ctx, CustomProvidersConfig previous, CustomProvidersConfig next) {
        entries.keySet().removeIf(id -> {
          if (next.providers().containsKey(id)) return false;
          entries.get(id).dispose();
          return true;
        });
        next.providers().forEach((id, endpoint) -> {
          ConfigHandle<CustomProvidersConfig.Endpoint> h = entries.get(id);
          if (h == null) {
            entries.put(id, ctx.plugin(id, endpoint(id), endpoint));
          } else {
            h.update(endpoint); // equal configs are a no-op
          }
        });
        return true;
      }
    };
  }

  /** One endpoint: listed by {@code Providers.STATUS}, provided while connected. */
  private static ConfigPlugin<CustomProvidersConfig.Endpoint> endpoint(String id) {
    return (ctx, endpoint) -> {
      ctx.hook(Providers.STATUS, q -> new Providers.Status(id, endpoint.connected()));
      if (!endpoint.connected()) return null;
      OpenAiCompatClient client = new OpenAiCompatClient(id, endpoint.baseUrl(), endpoint.key(),
          endpoint.retryPolicy());
      ctx.provide(Providers.key(id), new CustomProvider(id, client, ctx.signal()));
      return null;
    };
  }

  @Override
  protected KeyCheck verify(Signal signal) {
    int count = client.get("/models", signal).path("data").size();
    return new KeyCheck(true, "reachable (" + count + " models)");
  }

  @Override
  protected Boolean free(String modelId, JsonNode model) {
    return null;
  }
}
