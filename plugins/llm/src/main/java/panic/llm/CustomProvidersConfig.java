package panic.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Config of the {@code custom-providers} plugin: OpenAI-compatible endpoints by provider id (Mistral, Ollama,
 * Groq, vLLM, LM Studio, ...). Each becomes the provider {@code Providers.key(id)}, so a new provider is a
 * config entry, not code. Ids are letters, digits, {@code _} and {@code -}. A bad entry is a config error, not
 * skipped: a typo must not go unnoticed.
 */
public record CustomProvidersConfig(Map<String, Endpoint> providers) {

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  public static final CustomProvidersConfig DEFAULT = new CustomProvidersConfig(null);

  public CustomProvidersConfig {
    Map<String, Endpoint> checked = new LinkedHashMap<>();
    if (providers != null) {
      providers.forEach((id, endpoint) -> {
        if (id == null || !ID.matcher(id).matches()) {
          throw new IllegalArgumentException("providers: bad provider id '" + id + "' (letters, digits, _ and -)");
        }
        if (id.equals(OpenRouter.ID)) {
          // both would provide Providers.key(id): the conflict would stop every custom provider, so refuse it here
          throw new IllegalArgumentException("providers: '" + id + "' is a built-in provider; pick another id");
        }
        if (endpoint == null) throw new IllegalArgumentException("providers." + id + ": empty entry");
        checked.put(id, endpoint);
      });
    }
    providers = Map.copyOf(checked);
  }

  /**
   * One endpoint. {@code baseUrl} is required (the API root, e.g. {@code http://127.0.0.1:11434/v1}).
   * {@code apiKey} left out = the server needs no key (no {@code Authorization} header); present but blank
   * (e.g. an unset {@code ${VAR}}) = a key is needed and missing, so not connected. {@code maxRetries}: as in
   * {@link ProviderConfig}. {@code enabled} (default true): false keeps it listed but not connected.
   */
  public record Endpoint(String baseUrl, String apiKey, Integer maxRetries, Boolean enabled) {

    public Endpoint {
      if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl is missing");
      baseUrl = baseUrl.strip();
      if (maxRetries != null && maxRetries < 0) maxRetries = 0;
      if (enabled == null) enabled = true;
    }

    public Endpoint(String baseUrl, String apiKey) {
      this(baseUrl, apiKey, null, null);
    }

    public boolean connected() {
      return enabled && (apiKey == null || !apiKey.isBlank());
    }

    /** The key to send; empty when none is needed. */
    public String key() {
      return apiKey == null ? "" : apiKey.strip();
    }

    public RetryPolicy retryPolicy() {
      return RetryPolicy.of(maxRetries);
    }

    @Override
    public String toString() {
      // never print the key
      String shown = apiKey == null ? "<none>" : apiKey.isBlank() ? "<missing>" : "***";
      return "Endpoint[baseUrl=" + baseUrl + ", apiKey=" + shown + ", maxRetries=" + maxRetries
          + ", enabled=" + enabled + "]";
    }
  }
}
