package panic.llm;

/** Config of a provider plugin. A blank {@code apiKey} means "not connected"; a null {@code baseUrl}, the default. */
public record ProviderConfig(String apiKey, String baseUrl) {

  public static final ProviderConfig NONE = new ProviderConfig(null, null);

  public boolean hasKey() {
    return apiKey != null && !apiKey.isBlank();
  }

  public String baseUrlOr(String fallback) {
    return baseUrl == null || baseUrl.isBlank() ? fallback : baseUrl;
  }

  @Override
  public String toString() {
    // never print the key
    return "ProviderConfig[apiKey=" + (hasKey() ? "***" : "<none>") + ", baseUrl=" + baseUrl + "]";
  }
}
