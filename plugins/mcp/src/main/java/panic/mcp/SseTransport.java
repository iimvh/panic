package panic.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import core.Signal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import panic.mcp.McpClient.McpException;
import panic.util.CancelToken;
import panic.util.Json;

/**
 * The HTTP+SSE transport of MCP 2024-11-05, which Streamable HTTP replaced; Claude Code still connects to such
 * servers ({@code "type": "sse"}), and so does this. A GET opens an event stream whose first event,
 * {@code endpoint}, names where to POST messages; every message from the server, responses included, comes on
 * that stream as a {@code message} event. The stream ending is a lost connection.
 *
 * The endpoint must be on the same origin as {@code url}: the configured headers (a token) are sent to it.
 */
final class SseTransport implements Transport {

  private final URI url;
  private final Map<String, String> headers;
  private final HttpClient http = HttpSupport.client();
  private final AtomicBoolean down = new AtomicBoolean();
  private final AtomicBoolean lost = new AtomicBoolean();
  private volatile Listener listener;
  private volatile URI endpoint;
  private volatile SseParser.Lines lines;
  private volatile CompletableFuture<?> stream;

  SseTransport(McpConfig.Server server) {
    this.url = URI.create(server.url());
    this.headers = server.headers();
  }

  @Override
  public void open(Context ctx, Listener listener, Signal signal, long timeoutMillis) {
    this.listener = listener;
    CompletableFuture<URI> found = new CompletableFuture<>();
    SseParser.Lines l = new SseParser(e -> {
      switch (e.type()) {
        case "endpoint" -> {
          URI uri = url.resolve(e.data().strip());
          if (sameOrigin(uri)) {
            found.complete(uri);
          } else {
            found.completeExceptionally(new McpException("the server's endpoint " + uri + " is not on " + url));
          }
        }
        case "message" -> {
          JsonNode msg = Json.parseOrNull(e.data());
          if (msg != null && !down.get()) listener.message(msg);
        }
        default -> {
          // other events are not part of the protocol
        }
      }
    }).subscriber(() -> { });
    lines = l;
    HttpRequest get = HttpSupport.request(url, headers).header("Accept", "text/event-stream").GET().build();
    CompletableFuture<HttpResponse<String>> f = http.sendAsync(get, info -> {
      String type = info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
      if (info.statusCode() / 100 == 2 && type.startsWith("text/event-stream")) {
        return BodySubscribers.mapping(BodySubscribers.fromLineSubscriber(l), v -> "");
      }
      return BodySubscribers.ofString(StandardCharsets.UTF_8);
    });
    stream = f;
    f.whenComplete((r, e) -> {
      if (down.get()) return;
      String why = e != null ? HttpSupport.describe(e, url)
          : r.statusCode() / 100 != 2 ? HttpSupport.status(r.statusCode(), r.body())
          : "the server closed the event stream";
      if (!found.isDone()) {
        found.completeExceptionally(new McpException(why));
      } else {
        lost("disconnected", why);
      }
    });
    endpoint = HttpSupport.await(found, signal, timeoutMillis, "the endpoint event", url);
  }

  private boolean sameOrigin(URI uri) {
    return Objects.equals(uri.getScheme(), url.getScheme()) && Objects.equals(uri.getHost(), url.getHost())
        && uri.getPort() == url.getPort();
  }

  @Override
  public void send(JsonNode msg) {
    URI to = endpoint;
    if (down.get() || to == null) throw new McpException("the connection is closed");
    String body;
    try {
      body = Json.MAPPER.writeValueAsString(msg);
    } catch (JsonProcessingException e) {
      throw new McpException("cannot encode the message: " + e.getMessage());
    }
    HttpRequest post = HttpSupport.request(to, headers)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    CompletableFuture<HttpResponse<String>> f = http.sendAsync(post, HttpResponse.BodyHandlers.ofString());
    JsonNode idNode = msg.get("id");
    if (msg.has("method") && idNode != null && idNode.canConvertToLong()) {
      long id = idNode.asLong();
      f.whenComplete((r, e) -> {
        if (down.get()) return;
        if (e != null) {
          String why = HttpSupport.describe(e, to);
          listener.message(HttpSupport.error(id, HttpSupport.HTTP_ERROR, why));
          lost("disconnected", why);
        } else if (r.statusCode() / 100 != 2) {
          listener.message(HttpSupport.error(id, HttpSupport.HTTP_ERROR, HttpSupport.status(r.statusCode(), r.body())));
        }
      });
      return;
    }
    HttpResponse<String> r;
    try {
      r = HttpSupport.await(f, new CancelToken(), HttpSupport.ACCEPT_MILLIS,
          msg.path("method").asText("a response"), to);
    } catch (McpException e) {
      lost("disconnected", e.getMessage());
      throw e;
    }
    if (r.statusCode() / 100 != 2) throw new McpException(HttpSupport.status(r.statusCode(), r.body()));
  }

  private void lost(String status, String detail) {
    Listener l = listener;
    if (l != null && !down.get() && lost.compareAndSet(false, true)) l.lost(status, detail);
  }

  @Override
  public boolean reconnects() {
    return true;
  }

  @Override
  public void close() {
    if (!down.compareAndSet(false, true)) return;
    SseParser.Lines l = lines;
    if (l != null) l.cancel();
    CompletableFuture<?> f = stream;
    if (f != null) f.cancel(true);
    http.shutdownNow();
  }
}
