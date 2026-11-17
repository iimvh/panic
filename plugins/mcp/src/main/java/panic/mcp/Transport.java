package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import core.Signal;

/**
 * How JSON-RPC messages travel between {@link McpClient} and one MCP server: a child process's stdin and stdout
 * ({@link StdioTransport}), HTTP POSTs answered with JSON or server-sent events ({@link HttpTransport}), or the older
 * HTTP+SSE pair ({@link SseTransport}). One instance is one connection: once closed or lost it is not reopened, the
 * session makes a new one.
 *
 * A transport owns no thread of its own: what must read in the background is a task of the {@code ctx} given to
 * {@link #open}, so it ends with the server's sub-plugin at the latest.
 */
interface Transport {

  /** Where a transport hands what it receives. Called on whatever thread received it. */
  interface Listener {

    /** A message from the server (a response, a request or a notification), or a JSON array of them. */
    void message(JsonNode msg);

    /**
     * The connection ended without {@link #close} being called: {@code status} says how for the {@code mcp} command
     * ({@code exited (exit 3)}, {@code disconnected}), {@code detail} why (the end of the server's log), or null.
     */
    void lost(String status, String detail);
  }

  /**
   * Connects (starts the process, opens the stream). Throws {@link McpClient.McpException} when it cannot; waits
   * at most {@code timeoutMillis} and gives up when {@code signal} is cancelled.
   */
  void open(Context ctx, Listener listener, Signal signal, long timeoutMillis);

  /**
   * Sends one message without waiting for its answer, which comes to the listener. A transport that learns that a
   * request failed (an HTTP error) hands the listener an error response for its id. Throws
   * {@link McpClient.McpException} when the message cannot be sent at all.
   */
  void send(JsonNode msg);

  /** The client stopped waiting for request {@code id} (answered, cancelled or timed out): free what it holds. */
  default void done(long id) {
  }

  /** The end of what the server logged, to explain a failure; empty when there is no log. */
  default String log() {
    return "";
  }

  /** Whether the session should connect again after a {@link Listener#lost} (a remote server), or give up. */
  boolean reconnects();

  /** Closes the connection; safe to call more than once and from any thread. No {@link Listener#lost} follows. */
  void close();
}
