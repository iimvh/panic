package panic.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** A local HTTP server for client tests. */
final class FakeServer implements AutoCloseable {

  interface Handler {
    void handle(HttpExchange exchange) throws Exception;
  }

  private final HttpServer server;
  volatile String lastBody;
  volatile String lastAuth;

  FakeServer(Handler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", exchange -> {
      try (exchange) {
        lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
        handler.handle(exchange);
      } catch (Exception e) {
        // a test that aborts the request makes writes fail: expected
      }
    });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  static void json(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
  }

  static void sse(HttpExchange ex, String... events) throws IOException, InterruptedException {
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.sendResponseHeaders(200, 0);
    OutputStream out = ex.getResponseBody();
    for (String e : events) {
      if (e.startsWith("sleep:")) {
        Thread.sleep(Long.parseLong(e.substring(6)));
        continue;
      }
      out.write(("data: " + e + "\n\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
