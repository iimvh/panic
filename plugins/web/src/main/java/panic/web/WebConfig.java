package panic.web;

/**
 * {@code enabled=false}: the plugin does nothing. Binds {@code host:port} (defaults 127.0.0.1:8080 when left
 * out); port 0 picks a free one. {@code detachSeconds}: how long an answer keeps running with no browser
 * watching it (a reload, a lost connection) before it is stopped; 0 stops it as soon as the page goes.
 */
public record WebConfig(boolean enabled, String host, Integer port, Integer detachSeconds) {

  public static final WebConfig DEFAULT = new WebConfig(false, "127.0.0.1", 8080, 120);

  public WebConfig {
    if (host == null || host.isBlank()) host = "127.0.0.1";
    if (port == null) port = 8080;
    if (port < 0 || port > 65535) throw new IllegalArgumentException("web.port out of range: " + port);
    if (detachSeconds == null) detachSeconds = 120;
    if (detachSeconds < 0) throw new IllegalArgumentException("web.detachSeconds < 0: " + detachSeconds);
  }

  public WebConfig(boolean enabled, String host, Integer port) {
    this(enabled, host, port, null);
  }

  public WebConfig withEnabled(boolean on) {
    return new WebConfig(on, host, port, detachSeconds);
  }
}
