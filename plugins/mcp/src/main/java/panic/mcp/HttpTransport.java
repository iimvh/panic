package panic.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import core.Disposable;
import core.Signal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import panic.mcp.McpClient.McpException;
import panic.util.CancelToken;
import panic.util.Json;

/**
 * The MCP Streamable HTTP transport (spec 2025-06-18). Every message is a POST to the server's one endpoint,
 * accepting {@code application/json} and {@code text/event-stream}. A request's answer is either one JSON body or
 * an event stream that may carry the server's own requests and notifications before the response. Notifications
 * and responses are answered {@code 202 Accepted}; the POST waits for that, so they keep their order.
 *
 * The {@code Mcp-Session-Id} the server gives in its answer to {@code initialize} is sent with every later request,
 * with {@code MCP-Protocol-Version}. Once {@code notifications/initialized} is accepted, task {@code listen} opens
 * the GET stream on which the server sends what it has to say on its own ({@code list_changed}); a server without
 * one answers 405, which is fine. A stream that ends is opened again with {@code Last-Event-ID}.
 *
 * Failures: an HTTP error answers its request with an error response; a connection that fails reports
 * {@code disconnected}; a 404 while in a session means the server forgot it, so the session must initialize again
 * (the request fails with {@link HttpSupport#SESSION_EXPIRED}). {@link #close} ends the session with a DELETE and
 * stops everything under way. Bodies are read by the HTTP client's own threads, which closing the client stops.
 */
final class HttpTransport implements Transport {

  /** One POST or GET under way: its future and, once streaming, its line subscriber. */
  private static final class Exchange {
    volatile CompletableFuture<?> future;
    volatile SseParser.Lines lines;
    volatile boolean answered;
    volatile boolean accepted;
    volatile boolean cancelled;

    void cancel() {
      cancelled = true;
      SseParser.Lines l = lines;
      if (l != null) l.cancel();
      CompletableFuture<?> f = future;
      if (f != null) f.cancel(true);
    }
  }

  private final URI url;
  private final Map<String, String> headers;
  private final HttpClient http = HttpSupport.client();
  private final AtomicBoolean down = new AtomicBoolean();
  private final AtomicBoolean lost = new AtomicBoolean();
  private final Map<Long, Exchange> inflight = new ConcurrentHashMap<>();
  private final AtomicInteger listens = new AtomicInteger();
  private volatile Listener listener;
  private volatile Context ctx;
  private volatile String sessionId;
  private volatile String protocol;
  private volatile String lastEventId;
  private volatile long initId = -1;
  private volatile Exchange listening;

  HttpTransport(McpConfig.Server server) {
    this.url = URI.create(server.url());
    this.headers = server.headers();
  }

  @Override
  public void open(Context ctx, Listener listener, Signal signal, long timeoutMillis) {
    // nothing to open: the first POST (initialize) connects
    this.ctx = ctx;
    this.listener = listener;
  }

  @Override
  public void send(JsonNode msg) {
    if (down.get()) throw new McpException("the connection is closed");
    String body;
    try {
      body = Json.MAPPER.writeValueAsString(msg);
    } catch (JsonProcessingException e) {
      throw new McpException("cannot encode the message: " + e.getMessage());
    }
    String method = msg.path("method").asText(null);
    JsonNode idNode = msg.get("id");
    HttpRequest post = session(HttpSupport.request(url, headers))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
    if (method == null || idNode == null || !idNode.canConvertToLong()) {
      accept(post, method);
      return;
    }
    long id = idNode.asLong();
    if (method.equals("initialize")) initId = id;
    Exchange ex = new Exchange();
    inflight.put(id, ex);
    CompletableFuture<HttpResponse<Void>> f = http.sendAsync(post, info -> answer(info, id, ex));
    ex.future = f;
    f.whenComplete((r, e) -> {
      if (ex.cancelled || down.get()) return;
      if (e != null) {
        String why = HttpSupport.describe(e, url);
        deliver(HttpSupport.error(id, HttpSupport.HTTP_ERROR, why));
        lost("disconnected", why);
      } else if (!ex.answered && !ex.accepted) {
        deliver(HttpSupport.error(id, HttpSupport.HTTP_ERROR, "the server ended its answer without a result"));
      }
    });
  }

  /** Reads the answer to request {@code id}: one JSON body, an event stream, or an HTTP error. */
  private BodySubscriber<Void> answer(ResponseInfo info, long id, Exchange ex) {
    int status = info.statusCode();
    if (id == initId) info.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
    if (status == 202) {
      ex.accepted = true; // the answer will come on the GET stream
      return BodySubscribers.discarding();
    }
    if (status / 100 == 2) {
      if (contentType(info).startsWith("text/event-stream")) {
        SseParser.Lines lines = new SseParser(this::event).subscriber(() -> { });
        ex.lines = lines;
        if (ex.cancelled) lines.cancel();
        return BodySubscribers.fromLineSubscriber(lines);
      }
      return BodySubscribers.mapping(BodySubscribers.ofString(StandardCharsets.UTF_8), text -> {
        JsonNode msg = Json.parseOrNull(text);
        deliver(msg == null ? HttpSupport.error(id, HttpSupport.HTTP_ERROR, "the server's answer is not JSON") : msg);
        return null;
      });
    }
    boolean expired = status == 404 && sessionId != null;
    return BodySubscribers.mapping(BodySubscribers.ofString(StandardCharsets.UTF_8), text -> {
      ex.answered = true;
      if (expired) {
        deliver(HttpSupport.error(id, HttpSupport.SESSION_EXPIRED, "the server ended the session"));
        lost("disconnected", "the server ended the session (HTTP 404)");
      } else {
        deliver(HttpSupport.error(id, HttpSupport.HTTP_ERROR, HttpSupport.status(status, text)));
      }
      return null;
    });
  }

  /** Sends a notification or a response, and waits until the server accepts it. */
  private void accept(HttpRequest post, String method) {
    HttpResponse<String> r;
    try {
      r = HttpSupport.await(http.sendAsync(post, HttpResponse.BodyHandlers.ofString()), new CancelToken(),
          HttpSupport.ACCEPT_MILLIS, method == null ? "a response" : method, url);
    } catch (McpException e) {
      lost("disconnected", e.getMessage());
      throw e;
    }
    int status = r.statusCode();
    if (status == 404 && sessionId != null) {
      lost("disconnected", "the server ended the session (HTTP 404)");
      throw new McpException("the server ended the session");
    }
    if (status / 100 != 2) throw new McpException(HttpSupport.status(status, r.body()));
    if ("notifications/initialized".equals(method) && listens.getAndIncrement() == 0) {
      ctx.spawn("listen", signal -> {
        listen(signal);
        return null;
      });
    }
  }

  /** The GET stream: what the server sends on its own, until the connection closes. */
  private void listen(Signal signal) {
    Disposable onCancel = signal.onCancel(() -> {
      Exchange l = listening;
      if (l != null) l.cancel();
    });
    try {
      while (!down.get() && !signal.isCancelled()) {
        HttpRequest.Builder b = session(HttpSupport.request(url, headers)).header("Accept", "text/event-stream").GET();
        String last = lastEventId;
        if (last != null) b.header("Last-Event-ID", last);
        Exchange ex = new Exchange();
        listening = ex;
        AtomicInteger status = new AtomicInteger();
        CompletableFuture<HttpResponse<Void>> f = http.sendAsync(b.build(), info -> {
          status.set(info.statusCode());
          if (info.statusCode() / 100 != 2 || !contentType(info).startsWith("text/event-stream")) {
            return BodySubscribers.discarding();
          }
          SseParser.Lines lines = new SseParser(this::event).subscriber(() -> { });
          ex.lines = lines;
          if (ex.cancelled) lines.cancel();
          return BodySubscribers.fromLineSubscriber(lines);
        });
        ex.future = f;
        if (down.get()) ex.cancel();
        try {
          f.get();
        } catch (ExecutionException e) {
          if (!down.get() && !ex.cancelled) lost("disconnected", HttpSupport.describe(e, url));
          return;
        } catch (CancellationException e) {
          return;
        }
        int s = status.get();
        if (s == 404 && sessionId != null) {
          lost("disconnected", "the server ended the session (HTTP 404)");
          return;
        }
        if (s / 100 != 2) return; // 405: no stream of its own; another error: calls still work without it
        TimeUnit.SECONDS.sleep(1); // the server closed the stream: open it again, a little later
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      onCancel.dispose();
    }
  }

  private void event(SseParser.Event e) {
    if (e.id() != null && !e.id().isEmpty()) lastEventId = e.id();
    if (!e.type().equals("message")) return;
    JsonNode msg = Json.parseOrNull(e.data());
    if (msg != null) deliver(msg);
  }

  /** Hands {@code msg} on, noting the requests it answers and the protocol version {@code initialize} agreed. */
  private void deliver(JsonNode msg) {
    for (JsonNode m : msg.isArray() ? asList(msg) : List.of(msg)) {
      JsonNode id = m.get("id");
      if (m.isObject() && id != null && id.canConvertToLong() && (m.has("result") || m.has("error"))) {
        Exchange ex = inflight.get(id.asLong());
        if (ex != null) ex.answered = true;
        if (id.asLong() == initId && m.has("result")) {
          protocol = m.path("result").path("protocolVersion").asText(null);
        }
      }
    }
    Listener l = listener;
    if (l != null && !down.get()) l.message(msg);
  }

  private static List<JsonNode> asList(JsonNode array) {
    List<JsonNode> out = new java.util.ArrayList<>();
    array.forEach(out::add);
    return out;
  }

  private HttpRequest.Builder session(HttpRequest.Builder b) {
    String id = sessionId;
    if (id != null) b.header("Mcp-Session-Id", id);
    String version = protocol;
    if (version != null) b.header("MCP-Protocol-Version", version);
    return b;
  }

  private static String contentType(ResponseInfo info) {
    return info.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
  }

  private void lost(String status, String detail) {
    Listener l = listener;
    if (l != null && !down.get() && lost.compareAndSet(false, true)) l.lost(status, detail);
  }

  @Override
  public void done(long id) {
    Exchange ex = inflight.remove(id);
    if (ex != null && ex.future != null && !ex.future.isDone()) ex.cancel();
  }

  @Override
  public boolean reconnects() {
    return true;
  }

  @Override
  public void close() {
    if (!down.compareAndSet(false, true)) return;
    Exchange l = listening;
    if (l != null) l.cancel();
    inflight.values().forEach(Exchange::cancel);
    inflight.clear();
    String id = sessionId;
    if (id != null) {
      // the spec asks a client that leaves to end its session; a server that does not allow it answers 405
      boolean interrupted = Thread.interrupted();
      try {
        HttpRequest delete = session(HttpSupport.request(url, headers)).DELETE().build();
        http.sendAsync(delete, HttpResponse.BodyHandlers.discarding()).get(2, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        // gone already, or slow: the server forgets the session by itself
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
    http.shutdownNow();
  }
}
