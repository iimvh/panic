package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import panic.mcp.McpClient.McpException;
import panic.util.Json;

/** What the two HTTP transports share: the client, requests with the configured headers, errors and waits. */
final class HttpSupport {

  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** How long a message without an answer (a notification, a response) may take to be accepted. */
  static final long ACCEPT_MILLIS = 30_000;

  /** The JSON-RPC code of an error made up by a transport for a request that failed at the HTTP level. */
  static final int HTTP_ERROR = -32000;

  /** The code when the server forgot the session (404): the session reconnects and the call may be sent again. */
  static final int SESSION_EXPIRED = -32001;

  private HttpSupport() {
  }

  /** A client of its own per connection, so closing the connection stops everything it has under way. */
  static HttpClient client() {
    return HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  /** A request to {@code uri} with the server's configured headers. A header Java refuses is a failure. */
  static HttpRequest.Builder request(URI uri, Map<String, String> headers) {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri);
    try {
      headers.forEach(b::header);
    } catch (IllegalArgumentException e) {
      throw new McpException("bad header in the config: " + e.getMessage());
    }
    return b;
  }

  /** An error response for request {@code id}, as if the server had sent it. */
  static JsonNode error(long id, int code, String message) {
    ObjectNode msg = Json.object().put("jsonrpc", "2.0").put("id", id);
    msg.set("error", Json.object().put("code", code).put("message", message));
    return msg;
  }

  /** What an HTTP error answer says, with a hint for the usual one. */
  static String status(int status, String body) {
    String text = body == null ? "" : body.strip().replaceAll("\\s+", " ");
    if (text.length() > 300) text = text.substring(0, 300) + "…";
    String hint = status == 401 || status == 403
        ? " (set the server's \"headers\", e.g. \"Authorization\": \"Bearer ${TOKEN}\"; OAuth is not supported)"
        : "";
    return "HTTP " + status + (text.isEmpty() ? "" : ": " + text) + hint;
  }

  /** A failure to reach the server, in a few words. */
  static String describe(Throwable e, URI uri) {
    Throwable t = e;
    while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof HttpConnectTimeoutException) return "cannot connect to " + uri + ": timed out";
    if (t instanceof ConnectException) return "cannot connect to " + uri;
    if (t instanceof HttpTimeoutException) return "no answer from " + uri;
    if (t instanceof ClosedChannelException) return "the connection to " + uri + " closed";
    String m = t.getMessage();
    return (m == null || m.isBlank() ? t.getClass().getSimpleName() : m) + " (" + uri + ")";
  }

  /**
   * Waits for {@code f} at most {@code timeoutMillis}; a cancelled {@code signal} cancels it. Throws
   * {@link McpException} for a timeout or a failure, {@link CancelledException} when cancelled.
   */
  static <T> T await(CompletableFuture<T> f, Signal signal, long timeoutMillis, String what, URI uri) {
    Disposable onCancel = signal.onCancel(() -> f.cancel(true));
    try {
      return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      f.cancel(true);
      throw new McpException(what + " timed out after " + timeoutMillis / 1000 + "s (" + uri + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      f.cancel(true);
      throw new CancelledException("cancelled");
    } catch (CancellationException e) {
      throw new CancelledException("cancelled");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof McpException m) throw m;
      throw new McpException(describe(e, uri));
    } finally {
      onCancel.dispose();
    }
  }
}
