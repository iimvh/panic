package panic.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import panic.util.Json;

/** Small helpers over HttpExchange. */
final class Http {

  private Http() {
  }

  static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
    ex.getResponseHeaders().set("Content-Type", type);
    boolean head = "HEAD".equals(ex.getRequestMethod());
    ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
    if (!head && body.length > 0) ex.getResponseBody().write(body);
  }

  static void json(HttpExchange ex, int status, Object value) throws IOException {
    send(ex, status, "application/json", Json.MAPPER.writeValueAsBytes(value));
  }

  static void text(HttpExchange ex, int status, String text) throws IOException {
    send(ex, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
  }

  static void error(HttpExchange ex, int status, String message) throws IOException {
    json(ex, status, Map.of("error", message));
  }

  /** The request body as a JSON object; an empty object when there is none. */
  static JsonNode body(HttpExchange ex) throws IOException {
    byte[] bytes = ex.getRequestBody().readAllBytes();
    if (bytes.length == 0) return Json.object();
    JsonNode node = Json.MAPPER.readTree(bytes);
    if (node == null || !node.isObject()) throw new BadRequest("body must be a JSON object");
    return node;
  }

  static Map<String, String> query(HttpExchange ex) {
    Map<String, String> out = new HashMap<>();
    String raw = ex.getRequestURI().getRawQuery();
    if (raw == null || raw.isEmpty()) return out;
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.put(k, v);
    }
    return out;
  }

  /** A client error: answered with 400 and its message. */
  static final class BadRequest extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BadRequest(String message) {
      super(message);
    }
  }
}
