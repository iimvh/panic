package panic.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import context.Context;
import context.Task;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import panic.util.CancelToken;
import panic.util.Json;

/**
 * A server-sent-events response. A failed write means the browser went away (closed, or pressed Stop):
 * {@link #gone()} is then cancelled, which aborts whatever is producing the events.
 */
final class SseResponse {

  private final OutputStream out;
  private final CancelToken gone = new CancelToken();

  private SseResponse(OutputStream out) {
    this.out = out;
  }

  static SseResponse open(HttpExchange ex) throws IOException {
    ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
    ex.getResponseHeaders().set("Cache-Control", "no-cache");
    ex.getResponseHeaders().set("X-Accel-Buffering", "no");
    ex.sendResponseHeaders(200, 0);
    return new SseResponse(ex.getResponseBody());
  }

  CancelToken gone() {
    return gone;
  }

  void send(String event) {
    send(event, Json.object());
  }

  /** Never throws: a broken connection only cancels {@link #gone()}. */
  void send(String event, JsonNode data) {
    write("event: " + event + "\ndata: " + data + "\n\n");
  }

  /** With an SSE {@code id}, which a client sends back to resume after it (see {@link Run}). */
  void send(long id, String event, JsonNode data) {
    write("id: " + id + "\nevent: " + event + "\ndata: " + data + "\n\n");
  }

  /**
   * Writes an SSE comment every {@code every} until the returned task is disposed, so a browser that left
   * is noticed even while nothing else is written (a model that has not produced its first token yet).
   */
  Task<Void> heartbeat(Context ctx, Duration every) {
    return ctx.spawn("sse-heartbeat", signal -> {
      try {
        while (!signal.isCancelled() && !gone.isCancelled()) {
          Thread.sleep(every.toMillis());
          write(": ping\n\n");
        }
      } catch (InterruptedException stopped) {
        // disposed
      }
      return null;
    });
  }

  private void write(String text) {
    if (!writeOrFail(text)) gone.cancel(); // outside the lock: listeners may take other locks
  }

  private synchronized boolean writeOrFail(String text) {
    if (gone.isCancelled()) return true;
    try {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      out.flush();
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
