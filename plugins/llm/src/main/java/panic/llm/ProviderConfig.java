package panic.llm;

/**
 * Config of a provider plugin. A blank {@code apiKey} means "not connected"; a null {@code baseUrl}, the
 * default. {@code maxRetries}: retries after a transient failure (null = {@link RetryPolicy#DEFAULT_RETRIES},
 * 0 = never).
 */
public record ProviderConfig(String apiKey, String baseUrl, Integer maxRetries) {

  public static final ProviderConfig NONE = new ProviderConfig(null, null, null);

  public ProviderConfig {
    if (maxRetries != null && maxRetries < 0) maxRetries = 0;
  }

  public ProviderConfig(String apiKey, String baseUrl) {
    this(apiKey, baseUrl, null);
  }

  public boolean hasKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String baseUrlOr(String fallback) {
    return baseUrl == null || baseUrl.isBlank() ? fallback : baseUrl;
  }

  public RetryPolicy retryPolicy() {
    return RetryPolicy.of(maxRetries);
  }

  @Override
  public String toString() {
    // never print the key
    return "ProviderConfig[apiKey=" + (hasKey() ? "***" : "<none>") + ", baseUrl=" + baseUrl
        + ", maxRetries=" + maxRetries + "]";
  }
}
