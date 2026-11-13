package panic.web;

/**
 * {@code enabled=false}: the plugin does nothing. Binds {@code host:port} (defaults 127.0.0.1:8080 when left
 * out); port 0 picks a free one.
 */
public record WebConfig(boolean enabled, String host, Integer port) {

  public static final WebConfig DEFAULT = new WebConfig(false, "127.0.0.1", 8080);

  public WebConfig {
    if (host == null || host.isBlank()) host = "127.0.0.1";
    if (port == null) port = 8080;
    if (port < 0 || port > 65535) throw new IllegalArgumentException("web.port out of range: " + port);
  }

  public WebConfig withEnabled(boolean on) {
    return new WebConfig(on, host, port);
  }
}
