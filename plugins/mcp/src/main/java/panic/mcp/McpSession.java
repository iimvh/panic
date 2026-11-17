package panic.mcp;

import static panic.mcp.McpPlugin.PROTOCOL;
import static panic.mcp.McpPlugin.toolName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.Context;
import core.CancelledException;
import core.Disposable;
import core.Signal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import panic.llm.ToolSpec;
import panic.mcp.McpClient.McpException;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.Json;

/**
 * One MCP server: its connection (over a {@link Transport}), the tools registered for it, and the requests other
 * parts of the plugin make to it (resources, prompts). Lives in the server's sub-plugin {@code server-<name>}: task
 * {@code session} connects, serves and, for a remote server, connects again (see {@link #run}).
 */
final class McpSession {

  /** What the session loop waits for: the tool list changed, or the connection was lost. */
  private record Event(String kind, String status, String detail) {
    static final Event RELIST = new Event("relist", null, null);
  }

  private final Context ctx;
  private final McpConfig cfg;
  private final String name;
  private final McpConfig.Server server;
  private final State state;
  private final Consumer<String> warn;
  private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
  private final Map<String, Registered> registered = new LinkedHashMap<>();
  private final Runnable onChange;
  private volatile McpClient client;
  /** What the server declared in {@code initialize} ({@code tools}, {@code resources}, {@code prompts}). */
  private volatile JsonNode capabilities = Json.object();

  /** {@code onChange} runs when the session becomes ready or stops being so (other parts offer more or less). */
  McpSession(Context ctx, McpConfig cfg, String name, McpConfig.Server server, State state, Consumer<String> warn,
      Runnable onChange) {
    this.ctx = ctx;
    this.cfg = cfg;
    this.name = name;
    this.server = server;
    this.state = state;
    this.warn = warn;
    this.onChange = onChange;
  }

  String name() {
    return name;
  }

  McpConfig config() {
    return cfg;
  }

  /** Whether the server is connected and initialized. */
  boolean ready() {
    return state.status.equals("ready");
  }

  /** Whether the server declared {@code capability} ({@code resources}, {@code prompts}) when it initialized. */
  boolean offers(String capability) {
    return capabilities.path(capability).isObject();
  }

  private void changed() {
    try {
      onChange.run();
    } catch (RuntimeException e) {
      warn.accept("mcp: server " + name + ": " + e.getMessage());
    }
  }

  void start() {
    ctx.spawn("session", signal -> {
      run(signal);
      return null;
    });
  }

  /** How many connections in a row may fail before a remote server is given up (Claude Code tries 5 times). */
  static final int MAX_RECONNECTS = 5;

  private Transport transport() {
    return switch (server.type()) {
      case "http" -> new HttpTransport(server);
      case "sse" -> new SseTransport(server);
      default -> new StdioTransport(server);
    };
  }

  /**
   * Connects and serves until the connection ends, then, for a remote server, connects again: at once after a
   * connection that worked, then after 1, 2, 4, 8 s; {@link #MAX_RECONNECTS} failures in a row give it up. A
   * local server that exits stays down (as in Claude Code) until {@code mcp restart}.
   */
  private void run(Signal signal) {
    int failures = 0;
    // the tools stay while a remote server reconnects (a call then says so), and go when the session ends
    try {
      while (true) {
        Transport transport = transport();
        Outcome outcome = connect(transport, signal);
        if (signal.isCancelled()) return;
        if (!transport.reconnects()) {
          if (outcome.lost() != null) {
            Event lost = outcome.lost();
            state.status = lost.status();
            state.detail = lost.detail();
            warn.accept("mcp: server " + name + " " + lost.status() + (lost.detail() == null ? "" : ": " + lost.detail()));
          } else {
            failed(outcome.why());
          }
          return;
        }
        failures = outcome.wasReady() ? 0 : failures + 1;
        if (failures >= MAX_RECONNECTS) {
          failed(outcome.why() + " (gave up after " + MAX_RECONNECTS + " attempts; mcp restart " + name + ")");
          return;
        }
        state.status = "reconnecting";
        state.detail = outcome.why();
        if (outcome.wasReady()) warn.accept("mcp: server " + name + " " + outcome.why() + "; reconnecting");
        if (failures > 0) TimeUnit.SECONDS.sleep(1L << (failures - 1));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // cancelled: the plugin is going away
    } finally {
      state.tools = List.of();
      state.instructions = null;
      unregister();
      changed();
    }
  }

  /** How a connection ended: {@code lost} when it was lost, else {@code why} it failed. */
  private record Outcome(boolean wasReady, Event lost, String why) {
  }

  /** One connection, from opening it to its end. Throws InterruptedException when the plugin goes away. */
  private Outcome connect(Transport transport, Signal signal) throws InterruptedException {
    long startup = TimeUnit.SECONDS.toMillis(cfg.startupSeconds());
    McpClient c = new McpClient(transport, n -> {
      if (n.method().equals("notifications/tools/list_changed")) events.offer(Event.RELIST);
    });
    events.clear();
    boolean ready = false;
    try {
      transport.open(ctx, new Transport.Listener() {
        @Override
        public void message(JsonNode msg) {
          c.receive(msg);
        }

        @Override
        public void lost(String status, String detail) {
          c.close(detail == null ? "the server stopped" : detail);
          events.offer(new Event("lost", status, detail));
        }
      }, signal, startup);
      JsonNode init = c.request("initialize", initialize(), signal, startup);
      c.notify("notifications/initialized", null);
      JsonNode info = init.path("serverInfo");
      String about = info.isObject()
          ? info.path("name").asText("?") + " " + info.path("version").asText("") + ", protocol "
              + init.path("protocolVersion").asText("?")
          : null;
      synchronized (this) {
        client = c;
        notifyAll(); // calls waiting for a new session
      }
      list(signal, startup);
      state.detail = about;
      state.instructions = init.path("instructions").isTextual() ? init.path("instructions").asText() : null;
      capabilities = init.path("capabilities").isObject() ? init.path("capabilities") : Json.object();
      state.status = "ready";
      changed();
      ready = true;
      while (true) {
        Event event = events.take();
        if (event != Event.RELIST) {
          return new Outcome(true, event, event.status() + (event.detail() == null ? "" : ": " + event.detail()));
        }
        list(signal, startup);
      }
    } catch (McpException e) {
      String tail = transport.log();
      Event lost = events.stream().filter(ev -> ev != Event.RELIST).findFirst().orElse(null);
      if (lost != null && !transport.reconnects()) return new Outcome(ready, lost, null);
      return new Outcome(ready, null, e.getMessage() + (tail.isEmpty() ? "" : " | " + tail));
    } catch (CancelledException e) {
      throw new InterruptedException("cancelled");
    } finally {
      // the connection first: calls still waiting on it fail, so removing the tools does not wait for them
      c.close("the server stopped");
      transport.close();
    }
  }

  /**
   * The client of a session newer than {@code old} once it has initialized, or null when none comes within
   * {@link McpConfig#startupSeconds} or {@code signal} is cancelled.
   */
  private McpClient next(McpClient old, Signal signal) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.startupSeconds());
    Disposable wake = signal.onCancel(() -> {
      synchronized (this) {
        notifyAll();
      }
    });
    try {
      synchronized (this) {
        while (client == old) {
          long left = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
          if (left <= 0 || signal.isCancelled()) return null;
          wait(left);
        }
        return client;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      wake.dispose();
    }
  }

  private void failed(String why) {
    state.status = "failed";
    state.detail = why;
    state.tools = List.of();
    warn.accept("mcp: server " + name + " failed: " + why);
  }

  private static ObjectNode initialize() {
    ObjectNode params = Json.object().put("protocolVersion", PROTOCOL);
    params.set("capabilities", Json.object());
    params.set("clientInfo", Json.object().put("name", "panic").put("version", "1"));
    return params;
  }

  /** Lists the server's tools (every page) and registers them in place of the previous list. */
  private void list(Signal signal, long timeoutMillis) {
    List<JsonNode> tools = new ArrayList<>();
    String cursor = null;
    do {
      ObjectNode params = Json.object();
      if (cursor != null) params.put("cursor", cursor);
      JsonNode page = client.request("tools/list", params, signal, timeoutMillis);
      page.path("tools").forEach(tools::add);
      cursor = page.path("nextCursor").asText(null);
    } while (cursor != null && !cursor.isEmpty());
    Map<String, Registered> next = new LinkedHashMap<>();
    List<String> shown = new ArrayList<>();
    for (JsonNode t : tools) {
      String original = t.path("name").asText("");
      if (original.isEmpty()) continue;
      String toolName = toolName(name, original);
      if (!server.offers(original)) {
        shown.add(toolName + " (filtered)");
        continue;
      }
      if (next.containsKey(toolName)) {
        warn.accept("mcp: server " + name + ": tool " + original + " skipped, its name clashes as " + toolName);
        continue;
      }
      JsonNode schema = t.path("inputSchema");
      ToolSpec spec = new ToolSpec(toolName, t.path("description").asText(""), schema.isObject() ? schema : null);
      next.put(toolName, new Registered(spec, original, null));
      shown.add(toolName);
    }
    sync(next);
    state.tools = List.copyOf(shown);
  }

  /** A tool registered for the server: what the model sees, the server's name for it, and its registration. */
  private record Registered(ToolSpec spec, String original, Disposable registration) {
  }

  /**
   * Makes the registered tools those of {@code next}. A tool that did not change keeps its registration: removing
   * one waits for its calls and drops their results, so a list that changes while a call runs (the server says so
   * before it answers) must not touch the tool being called.
   */
  private void sync(Map<String, Registered> next) {
    List<Disposable> gone = new ArrayList<>();
    synchronized (registered) {
      for (var it = registered.entrySet().iterator(); it.hasNext(); ) {
        var e = it.next();
        Registered now = next.get(e.getKey());
        if (now == null || !now.spec().equals(e.getValue().spec()) || !now.original().equals(e.getValue().original())) {
          gone.add(e.getValue().registration());
          it.remove();
        }
      }
    }
    gone.forEach(Disposable::dispose);
    synchronized (registered) {
      next.forEach((toolName, r) -> {
        if (registered.containsKey(toolName)) return;
        Disposable d = Tools.register(ctx, new McpTool(r.spec(), r.original(), Risk.of(server.risk()), this));
        registered.put(toolName, new Registered(r.spec(), r.original(), d));
      });
    }
  }

  private void unregister() {
    List<Disposable> old;
    synchronized (registered) {
      old = registered.values().stream().map(Registered::registration).toList();
      registered.clear();
    }
    old.forEach(Disposable::dispose);
  }

  /**
   * Sends a request on the current session. When the server forgot the session (an HTTP 404), the spec asks for a
   * new one: the request is sent once more as soon as the session has initialized again.
   */
  JsonNode request(String method, JsonNode params, Signal signal, long timeoutMillis) {
    McpClient c = client;
    if (c == null) throw new McpException("not connected");
    if (state.status.equals("reconnecting")) {
      throw new McpException("reconnecting (" + state.detail + "); try again in a moment");
    }
    try {
      return c.request(method, params, signal, timeoutMillis);
    } catch (McpException e) {
      if (e.code() != HttpSupport.SESSION_EXPIRED) throw e;
      McpClient fresh = next(c, signal);
      if (fresh == null) throw new McpException("the server ended the session and no new one started in time");
      return fresh.request(method, params, signal, timeoutMillis);
    }
  }

  /** {@code tools/call}: the result as text for the model; images are saved to files. */
  ToolResult call(String tool, JsonNode args, ToolContext tc) {
    ObjectNode params = Json.object().put("name", tool);
    params.set("arguments", args == null || !args.isObject() ? Json.object() : args);
    JsonNode result;
    try {
      result = request("tools/call", params, tc.signal(), TimeUnit.SECONDS.toMillis(cfg.timeoutSeconds()));
    } catch (McpException e) {
      return ToolResult.error("MCP server " + name + ": " + e.getMessage());
    }
    String text = content(result);
    return result.path("isError").asBoolean(false) ? ToolResult.error(text) : ToolResult.ok(text);
  }

  private final AtomicInteger saved = new AtomicInteger();

  /** The content blocks as text: text as is, images and audio saved to files and named by their path. */
  private String content(JsonNode result) {
    List<String> parts = new ArrayList<>();
    for (JsonNode block : result.path("content")) {
      String type = block.path("type").asText("");
      switch (type) {
        case "text" -> parts.add(block.path("text").asText(""));
        case "image", "audio" -> parts.add(save(type, block.path("data").asText(""), block.path("mimeType").asText("")));
        case "resource_link" -> parts.add("[resource " + block.path("uri").asText("?")
            + (block.hasNonNull("name") ? " (" + block.path("name").asText() + ")" : "") + "]");
        case "resource" -> {
          JsonNode r = block.path("resource");
          parts.add(r.hasNonNull("text") ? r.path("text").asText()
              : "[binary resource " + r.path("uri").asText("?") + ", " + r.path("mimeType").asText("?") + "]");
        }
        default -> parts.add("[" + (type.isEmpty() ? "unknown" : type) + " content left out]");
      }
    }
    if (parts.isEmpty() && result.has("structuredContent")) parts.add(result.get("structuredContent").toString());
    return parts.isEmpty() ? "(no output)" : String.join("\n", parts);
  }

  /** Saves {@code base64} data under {@code dir/<server>/} and says where, for the model. */
  String save(String type, String base64, String mime) {
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return "[" + type + " left out: bad base64 data]";
    }
    String ext = mime.contains("/") ? mime.substring(mime.indexOf('/') + 1).replaceAll("[^A-Za-z0-9]", "") : "bin";
    if (ext.isEmpty() || ext.equals("jpeg")) ext = ext.isEmpty() ? "bin" : "jpg";
    Path dir = Path.of(cfg.dir()).toAbsolutePath().normalize().resolve(name);
    Path file = dir.resolve(String.format(Locale.ROOT, "%s-%d-%d.%s", type, System.currentTimeMillis(),
        saved.incrementAndGet(), ext));
    try {
      Files.createDirectories(dir);
      Files.write(file, bytes);
    } catch (IOException e) {
      return "[" + type + " left out: cannot save it: " + e.getMessage() + "]";
    }
    return "[" + type + " saved to " + file + " (" + mime + ", " + kb(bytes.length) + "); you cannot see it, "
        + "but the user can open the file]";
  }

  private static String kb(int bytes) {
    return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
  }

  /** What the {@code mcp} command shows about a server. */
  static final class State {
    volatile String status = "starting";
    volatile List<String> tools = List.of();
    volatile String detail;
    /** What the server says about using it ({@code instructions} of {@code initialize}), or null. */
    volatile String instructions;
  }

  /** A tool of a server: calls go to its session. */
  private record McpTool(ToolSpec spec, String original, Risk risk, McpSession session) implements Tool {

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return session.call(original, args, ctx);
    }

    @Override
    public int timeoutSeconds() {
      // a little more than the call's own limit, so the server is sent notifications/cancelled first
      return session.config().timeoutSeconds() + 5;
    }
  }
}
