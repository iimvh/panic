package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import panic.util.Json;

/**
 * An MCP server over HTTP for tests, in the test's JVM. Streamable HTTP at {@code /mcp}: answers in JSON or, with
 * {@link #sse}, as event streams that first carry a {@code ping} request from the server; a GET stream (405 without
 * {@link #stream}) that carries what {@link #push} queues; sessions that {@link #expire} forgets. The legacy HTTP+SSE
 * transport at {@code /sse} (GET, {@code endpoint} event) and {@code /messages} (POST).
 *
 * Tools: {@code echo {text}}, {@code grow} (adds {@code extra} and says so on the stream), {@code wait} (answers
 * once cancelled). Everything it hears goes to {@link #log}.
 */
final class FakeHttpMcpServer implements AutoCloseable {

  final List<String> log = new CopyOnWriteArrayList<>();
  volatile boolean sse;
  volatile boolean stream = true;
  volatile String requireAuth;

  private final AtomicInteger sessions = new AtomicInteger();
  private final AtomicInteger events = new AtomicInteger();
  private final BlockingQueue<String> out = new LinkedBlockingQueue<>();
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private volatile String session;
  private volatile boolean grown;
  private volatile CountDownLatch cancelled = new CountDownLatch(1);
  private volatile boolean closed;
  private HttpServer server;
  private int port;

  FakeHttpMcpServer start() throws IOException {
    return start(0);
  }

  private FakeHttpMcpServer start(int at) throws IOException {
    closed = false;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", at), 0);
    server.setExecutor(pool);
    server.createContext("/mcp", this::streamable);
    server.createContext("/sse", this::legacyStream);
    server.createContext("/messages", this::legacyPost);
    server.start();
    port = server.getAddress().getPort();
    return this;
  }

  String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  /** Stops answering (connections are refused) until {@link #restart}. */
  void stop() {
    closed = true;
    server.stop(0);
  }

  void restart() throws IOException {
    start(port);
  }

  /** Forgets the session: requests that name it get 404 until the client initializes again. */
  void expire() {
    session = "forgotten";
  }

  void push(JsonNode msg) {
    out.add(msg.toString());
  }

  long count(String prefix) {
    return log.stream().filter(l -> l.startsWith(prefix)).count();
  }

  @Override
  public void close() {
    stop();
    pool.shutdownNow();
  }

  private void streamable(HttpExchange ex) throws IOException {
    try (ex) {
      switch (ex.getRequestMethod()) {
        case "GET" -> {
          log.add("GET last=" + ex.getRequestHeaders().getFirst("Last-Event-ID"));
          if (!stream) {
            ex.sendResponseHeaders(405, -1);
            return;
          }
          if (!sessionOk(ex)) return;
          pump(ex, false);
        }
        case "DELETE" -> {
          log.add("DELETE " + ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
          ex.sendResponseHeaders(200, -1);
        }
        case "POST" -> post(ex);
        default -> ex.sendResponseHeaders(405, -1);
      }
    }
  }

  private boolean sessionOk(HttpExchange ex) throws IOException {
    String sent = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
    if (session != null && !session.equals(sent)) {
      log.add("404 " + sent);
      ex.sendResponseHeaders(404, -1);
      return false;
    }
    return true;
  }

  private void post(HttpExchange ex) throws IOException {
    JsonNode msg = Json.parseOrNull(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String method = msg.path("method").asText(null);
    String auth = ex.getRequestHeaders().getFirst("Authorization");
    if (requireAuth != null && !requireAuth.equals(auth)) {
      log.add("401");
      send(ex, 401, "text/plain", "missing token");
      return;
    }
    log.add("POST " + (method == null ? "response " + msg.path("id").asText() : method)
        + " session=" + ex.getRequestHeaders().getFirst("Mcp-Session-Id")
        + " version=" + ex.getRequestHeaders().getFirst("MCP-Protocol-Version") + " auth=" + auth);
    if (!"initialize".equals(method) && !sessionOk(ex)) return;
    if (method == null || !msg.has("id")) {
      if ("notifications/cancelled".equals(method)) {
        log.add("cancelled " + msg.path("params"));
        cancelled.countDown();
      }
      ex.sendResponseHeaders(202, -1);
      return;
    }
    if ("initialize".equals(method)) {
      session = "s" + sessions.incrementAndGet();
      ex.getResponseHeaders().add("Mcp-Session-Id", session);
    }
    if (isCall(msg, "wait")) {
      CountDownLatch c = cancelled;
      try {
        c.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      cancelled = new CountDownLatch(1);
      ex.sendResponseHeaders(500, -1);
      return;
    }
    String reply = reply(msg).toString();
    if (!sse) {
      send(ex, 200, "application/json", reply);
      return;
    }
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    OutputStream body = ex.getResponseBody();
    if (isCall(msg, "echo")) {
      event(body, Json.object().put("jsonrpc", "2.0").put("id", "srv-1").put("method", "ping").toString());
    }
    event(body, reply);
  }

  private static boolean isCall(JsonNode msg, String tool) {
    return "tools/call".equals(msg.path("method").asText()) && tool.equals(msg.path("params").path("name").asText());
  }

  /** The GET stream: what is queued, as events; a comment now and then finds out when the client left. */
  private void pump(HttpExchange ex, boolean legacy) throws IOException {
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    OutputStream body = ex.getResponseBody();
    if (legacy) {
      body.write("event: endpoint\ndata: /messages?session=1\n\n".getBytes(StandardCharsets.UTF_8));
      body.flush();
    }
    try {
      while (!closed) {
        String next = out.poll(100, TimeUnit.MILLISECONDS);
        if (next == null) {
          body.write(": idle\n\n".getBytes(StandardCharsets.UTF_8));
          body.flush();
        } else {
          event(body, next);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void event(OutputStream body, String data) throws IOException {
    body.write(("id: " + events.incrementAndGet() + "\nevent: message\ndata: " + data + "\n\n")
        .getBytes(StandardCharsets.UTF_8));
    body.flush();
  }

  private void legacyStream(HttpExchange ex) throws IOException {
    try (ex) {
      log.add("legacy GET auth=" + ex.getRequestHeaders().getFirst("Authorization"));
      pump(ex, true);
    }
  }

  private void legacyPost(HttpExchange ex) throws IOException {
    try (ex) {
      JsonNode msg = Json.parseOrNull(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String method = msg.path("method").asText(null);
      log.add("legacy POST " + method + " " + ex.getRequestURI().getQuery());
      if (method != null && msg.has("id")) out.add(reply(msg).toString());
      ex.sendResponseHeaders(202, -1);
    }
  }

  private ObjectNode reply(JsonNode msg) {
    JsonNode params = msg.path("params");
    ObjectNode result = Json.object();
    switch (msg.path("method").asText()) {
      case "initialize" -> {
        result.put("protocolVersion", params.path("protocolVersion").asText());
        result.set("capabilities", Json.object().set("tools", Json.object().put("listChanged", true)));
        result.set("serverInfo", Json.object().put("name", "fake-http").put("version", "0.2"));
      }
      case "tools/list" -> {
        ArrayNode tools = result.putArray("tools");
        tools.addObject().put("name", "echo").put("description", "Says the text back.")
            .set("inputSchema", Json.parseOrNull("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"));
        tools.addObject().put("name", "grow").set("inputSchema", Json.object().put("type", "object"));
        tools.addObject().put("name", "wait").set("inputSchema", Json.object().put("type", "object"));
        if (grown) tools.addObject().put("name", "extra").set("inputSchema", Json.object().put("type", "object"));
      }
      case "tools/call" -> {
        String name = params.path("name").asText();
        ArrayNode content = result.putArray("content");
        if (name.equals("grow")) {
          grown = true;
          push(Json.object().put("jsonrpc", "2.0").put("method", "notifications/tools/list_changed"));
          content.addObject().put("type", "text").put("text", "grown");
        } else {
          content.addObject().put("type", "text").put("text", "echo: " + params.path("arguments").path("text").asText());
        }
      }
      default -> {
        ObjectNode err = Json.object().put("jsonrpc", "2.0");
        err.set("id", msg.get("id"));
        err.set("error", Json.object().put("code", -32601).put("message", "no " + msg.path("method").asText()));
        return err;
      }
    }
    ObjectNode reply = Json.object().put("jsonrpc", "2.0");
    reply.set("id", msg.get("id"));
    reply.set("result", result);
    return reply;
  }

  private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", type);
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
  }
}
