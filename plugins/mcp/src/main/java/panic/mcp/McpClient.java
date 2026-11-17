package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import panic.util.Json;

/**
 * The client side of a JSON-RPC 2.0 connection to an MCP server, over any {@link Transport}. It owns no thread:
 * the transport hands it what the server sends ({@link #receive}); {@link #request} blocks the calling thread until
 * the answer arrives, the time limit passes or the signal is cancelled (then the server is sent
 * {@code notifications/cancelled}, as the MCP cancellation spec asks).
 *
 * Requests from the server are answered here: {@code ping} with an empty result, anything else (sampling, roots,
 * elicitation: capabilities this client does not declare) with "method not found". Notifications go to
 * {@code onNotification}, on the transport's receiving thread: it must not make requests itself (their answers may
 * be read by that same thread).
 */
final class McpClient {

  /** A notification from the server: {@code method} and its {@code params} (an empty object when none). */
  record Notification(String method, JsonNode params) {
  }

  private final Transport transport;
  private final Consumer<Notification> onNotification;
  private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final AtomicLong ids = new AtomicLong();
  private volatile String closed;

  McpClient(Transport transport, Consumer<Notification> onNotification) {
    this.transport = transport;
    this.onNotification = onNotification;
  }

  /** A message (or a JSON array of messages) from the server. Anything that is not a message is ignored. */
  void receive(JsonNode msg) {
    if (msg.isArray()) {
      msg.forEach(m -> {
        if (m.isObject()) dispatch(m);
      });
    } else if (msg.isObject()) {
      dispatch(msg);
    }
  }

  private void dispatch(JsonNode msg) {
    JsonNode id = msg.get("id");
    String method = msg.path("method").asText(null);
    if (method == null) {
      if (id == null || !id.canConvertToLong()) return; // a response we cannot match
      CompletableFuture<JsonNode> f = pending.get(id.asLong());
      if (f == null) return; // answered after we gave up on it
      JsonNode error = msg.get("error");
      if (error != null && !error.isNull()) {
        f.completeExceptionally(new McpException(error.path("code").asInt(), error.path("message").asText("error")));
      } else {
        f.complete(msg.path("result"));
      }
    } else if (id != null && !id.isNull()) {
      ObjectNode reply = Json.object().put("jsonrpc", "2.0").set("id", id);
      if (method.equals("ping")) {
        reply.set("result", Json.object());
      } else {
        reply.set("error", Json.object().put("code", -32601).put("message", "method not found: " + method));
      }
      try {
        send(reply);
      } catch (McpException ignored) {
        // the server is going away; its own reader notices
      }
    } else {
      JsonNode params = msg.path("params");
      try {
        onNotification.accept(new Notification(method, params.isObject() ? params : Json.object()));
      } catch (RuntimeException ignored) {
        // a broken listener must not stop the session
      }
    }
  }

  /**
   * Sends {@code method} with {@code params} and waits for its result. Throws {@link McpException} for an error
   * answer, a closed connection or a timeout, and {@link CancelledException} when {@code signal} is cancelled.
   */
  JsonNode request(String method, JsonNode params, Signal signal, long timeoutMillis) {
    long id = ids.incrementAndGet();
    CompletableFuture<JsonNode> f = new CompletableFuture<>();
    pending.put(id, f);
    Disposable onCancel = signal.onCancel(() -> f.completeExceptionally(new CancelledException("cancelled")));
    try {
      String why = closed;
      if (why != null) throw new McpException(why);
      ObjectNode msg = Json.object().put("jsonrpc", "2.0").put("id", id).put("method", method);
      if (params != null) msg.set("params", params);
      send(msg);
      try {
        return f.get(timeoutMillis, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        cancel(id, "timed out");
        throw new McpException(method + " timed out after " + timeoutMillis / 1000 + "s");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cancel(id, "cancelled");
        throw new CancelledException("cancelled");
      } catch (ExecutionException e) {
        if (e.getCause() instanceof CancelledException c) {
          cancel(id, "cancelled");
          throw c;
        }
        if (e.getCause() instanceof McpException m) throw m;
        throw new McpException(String.valueOf(e.getCause()));
      }
    } finally {
      onCancel.dispose();
      pending.remove(id);
      transport.done(id);
    }
  }

  /** Sends a notification (no answer expected). */
  void notify(String method, JsonNode params) {
    ObjectNode msg = Json.object().put("jsonrpc", "2.0").put("method", method);
    if (params != null) msg.set("params", params);
    send(msg);
  }

  /** Tells the server to stop working on request {@code id}; ignored when it is gone already. */
  private void cancel(long id, String reason) {
    if (closed != null) return;
    try {
      notify("notifications/cancelled", Json.object().put("requestId", id).put("reason", reason));
    } catch (McpException ignored) {
      // gone: nothing to cancel
    }
  }

  private void send(JsonNode msg) {
    transport.send(msg);
  }

  /** Fails every waiting request with {@code reason}; later requests fail at once. */
  void close(String reason) {
    if (closed == null) closed = reason;
    pending.values().forEach(f -> f.completeExceptionally(new McpException(reason)));
  }

  /** An error answer from the server (with its JSON-RPC {@code code}), or a connection that broke. */
  static final class McpException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;

    McpException(String message) {
      this(0, message);
    }

    McpException(int code, String message) {
      super(message);
      this.code = code;
    }

    int code() {
      return code;
    }
  }
}
