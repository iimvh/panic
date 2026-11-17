package panic.mcp;

import java.util.concurrent.Flow;
import java.util.function.Consumer;

/**
 * Server-sent events, line by line, as the HTML spec reads them: {@code data} lines joined by newlines,
 * {@code event} (default {@code message}) and {@code id}; comments and {@code retry} are skipped; a blank line ends
 * an event. It is fed by the HTTP client's line subscriber ({@link #subscriber}), so reading a stream needs no thread
 * of the plugin's.
 *
 * {@code panic.llm.Sse} is not used: it reads a blocking stream and drops {@code event} and {@code id}, which MCP
 * needs (the {@code endpoint} event, {@code Last-Event-ID}).
 */
final class SseParser {

  /** One event: its type, its data and the id the server gave it (null when none). */
  record Event(String type, String data, String id) {
  }

  private final Consumer<Event> onEvent;
  private final StringBuilder data = new StringBuilder();
  private boolean hasData;
  private String type;
  private String id;

  SseParser(Consumer<Event> onEvent) {
    this.onEvent = onEvent;
  }

  void line(String line) {
    if (line.isEmpty()) {
      dispatch();
      return;
    }
    if (line.startsWith(":")) return;
    int colon = line.indexOf(':');
    String field = colon < 0 ? line : line.substring(0, colon);
    String value = colon < 0 ? "" : line.substring(colon + 1);
    if (value.startsWith(" ")) value = value.substring(1);
    switch (field) {
      case "data" -> {
        if (hasData) data.append('\n');
        data.append(value);
        hasData = true;
      }
      case "event" -> type = value;
      case "id" -> id = value;
      default -> {
        // retry and unknown fields are not used
      }
    }
  }

  /** The stream ended: an event not yet closed by a blank line still counts. */
  void end() {
    dispatch();
  }

  private void dispatch() {
    if (hasData) onEvent.accept(new Event(type == null || type.isEmpty() ? "message" : type, data.toString(), id));
    data.setLength(0);
    hasData = false;
    type = null;
    id = null;
  }

  /**
   * A subscriber for {@code BodySubscribers.fromLineSubscriber} that feeds this parser. {@link #cancel} stops the
   * stream (the body is abandoned and the connection freed); {@code onEnd} runs once when the stream ends, however
   * it ends.
   */
  final class Lines implements Flow.Subscriber<String> {
    private final Runnable onEnd;
    private volatile Flow.Subscription subscription;
    private volatile boolean cancelled;

    Lines(Runnable onEnd) {
      this.onEnd = onEnd;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      if (cancelled) {
        s.cancel();
      } else {
        s.request(Long.MAX_VALUE);
      }
    }

    @Override
    public void onNext(String line) {
      if (!cancelled) line(line);
    }

    @Override
    public void onError(Throwable error) {
      onEnd.run();
    }

    @Override
    public void onComplete() {
      if (!cancelled) end();
      onEnd.run();
    }

    void cancel() {
      cancelled = true;
      Flow.Subscription s = subscription;
      if (s != null) s.cancel();
    }
  }

  Lines subscriber(Runnable onEnd) {
    return new Lines(onEnd);
  }
}
