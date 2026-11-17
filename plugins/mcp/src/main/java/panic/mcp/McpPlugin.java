package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.ConfigPlugin;
import context.Context;
import context.PluginHandle;
import core.Disposable;
import core.Signal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import panic.command.Commands;
import panic.command.UsageException;
import panic.llm.ToolSpec;
import panic.mcp.McpClient.McpException;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.Json;

/**
 * Gives the agent the tools of Model Context Protocol servers (stdio transport), the way Claude Code extends its
 * tools: each tool of server {@code s} becomes a {@link Tool} named {@code mcp__s__<tool>} with the server's
 * {@code inputSchema}, and the {@link McpConfig.Server#risk} of its server, so the permission policy asks for it
 * like for any other tool. Command {@code mcp} lists the servers and restarts one.
 *
 * Lifecycle: each enabled server is a sub-plugin of this one with two tasks, one reading the server's stdout and
 * one running the session ({@code initialize}, {@code notifications/initialized}, {@code tools/list}, again on
 * {@code notifications/tools/list_changed}). Its tools exist while the server runs: they are removed when it
 * exits, and a call waiting on it then fails. Disposing the plugin (a config change, the app stopping) stops every
 * server as the MCP spec says for stdio (close its input, then SIGTERM, then SIGKILL, children included). A
 * cancelled tool call sends {@code notifications/cancelled}; a call that passes {@link McpConfig#timeoutSeconds}
 * is cancelled the same way. Requires nothing.
 */
public final class McpPlugin implements ConfigPlugin<McpConfig> {

  /** The MCP revision this client speaks; servers answer with theirs, which is accepted (only basics are used). */
  static final String PROTOCOL = "2025-06-18";

  static final String PREFIX = "mcp__";

  private final Consumer<String> warn;

  public McpPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  /** What the {@code mcp} command shows about a server. */
  private static final class State {
    volatile String status = "starting";
    volatile List<String> tools = List.of();
    volatile String detail;
  }

  @Override
  public Disposable apply(Context ctx, McpConfig cfg) {
    Map<String, State> states = new TreeMap<>();
    Map<String, PluginHandle> handles = new ConcurrentHashMap<>();
    cfg.servers().forEach((name, server) -> {
      State state = new State();
      states.put(name, state);
      if (!server.enabled()) {
        state.status = "disabled";
      } else {
        handles.put(name, start(ctx, cfg, name, server, state));
      }
    });
    Commands.command(ctx, "mcp [action] [name]", "MCP servers and their tools: list | restart <name>", call -> {
      String action = call.argOr("action", "list");
      switch (action) {
        case "list" -> {
          if (states.isEmpty()) {
            call.out().println("no MCP servers configured (section \"mcp\": {\"servers\": {...}})");
            return;
          }
          states.forEach((name, s) -> {
            call.out().println(name + "  " + s.status + (s.detail == null ? "" : "  " + s.detail));
            for (String t : s.tools) call.out().println("  " + t);
          });
        }
        case "restart" -> {
          String name = call.arg("name");
          if (name == null) throw new UsageException("which server?", "mcp restart <name>");
          McpConfig.Server server = cfg.servers().get(name);
          if (server == null || !server.enabled()) {
            call.out().println("no enabled server " + name);
            return;
          }
          synchronized (handles) {
            PluginHandle old = handles.remove(name);
            if (old != null) old.dispose();
            State state = states.get(name);
            state.status = "starting";
            state.tools = List.of();
            state.detail = null;
            handles.put(name, start(ctx, cfg, name, server, state));
          }
          call.out().println("server " + name + " restarted");
        }
        default -> throw new UsageException("unknown action '" + action + "'", "mcp [list|restart <name>]");
      }
    });
    return null;
  }

  private PluginHandle start(Context ctx, McpConfig cfg, String name, McpConfig.Server server, State state) {
    return ctx.plugin("server-" + name, sctx -> {
      new Session(sctx, cfg, name, server, state, warn).start();
      return null;
    });
  }

  /** One server: its connection, and the tools registered for it while it is ready. */
  private static final class Session {

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
    private final List<Disposable> registered = new ArrayList<>();
    private volatile McpClient client;

    Session(Context ctx, McpConfig cfg, String name, McpConfig.Server server, State state, Consumer<String> warn) {
      this.ctx = ctx;
      this.cfg = cfg;
      this.name = name;
      this.server = server;
      this.state = state;
      this.warn = warn;
    }

    void start() {
      ctx.spawn("session", signal -> {
        run(signal);
        return null;
      });
    }

    private void run(Signal signal) {
      long startup = TimeUnit.SECONDS.toMillis(cfg.startupSeconds());
      Transport transport = new StdioTransport(server);
      McpClient c = new McpClient(transport, n -> {
        if (n.method().equals("notifications/tools/list_changed")) events.offer(Event.RELIST);
      });
      client = c;
      Event lost = null;
      try {
        transport.open(ctx, new Transport.Listener() {
          @Override
          public void message(JsonNode msg) {
            c.receive(msg);
          }

          @Override
          public void lost(String status, String detail) {
            c.close("the server stopped");
            events.offer(new Event("lost", status, detail));
          }
        }, signal, startup);
        JsonNode init = c.request("initialize", initialize(), signal, startup);
        c.notify("notifications/initialized", null);
        JsonNode info = init.path("serverInfo");
        state.detail = info.isObject()
            ? info.path("name").asText("?") + " " + info.path("version").asText("") + ", protocol "
                + init.path("protocolVersion").asText("?")
            : null;
        list(signal, startup);
        state.status = "ready";
        while (true) {
          Event event = events.take();
          if (event != Event.RELIST) {
            lost = event;
            break;
          }
          list(signal, startup);
        }
      } catch (McpException e) {
        String tail = transport.log();
        failed(e.getMessage() + (tail.isEmpty() ? "" : " | " + tail));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // cancelled: the plugin is going away
      } finally {
        // the connection first: calls still waiting on it fail, so removing the tools does not wait for them
        c.close("the server stopped");
        transport.close();
        if (lost != null && !signal.isCancelled()) {
          state.status = lost.status();
          state.detail = lost.detail();
          state.tools = List.of();
          warn.accept("mcp: server " + name + " " + lost.status() + (lost.detail() == null ? "" : ": " + lost.detail()));
        }
        unregister();
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
      unregister();
      Set<String> names = new HashSet<>();
      List<String> shown = new ArrayList<>();
      for (JsonNode t : tools) {
        String original = t.path("name").asText("");
        if (original.isEmpty()) continue;
        String toolName = toolName(name, original);
        if (!names.add(toolName)) {
          warn.accept("mcp: server " + name + ": tool " + original + " skipped, its name clashes as " + toolName);
          continue;
        }
        JsonNode schema = t.path("inputSchema");
        String description = t.path("description").asText("");
        ToolSpec spec = new ToolSpec(toolName, description, schema.isObject() ? schema : null);
        synchronized (registered) {
          registered.add(Tools.register(ctx, new McpTool(spec, original, Risk.of(server.risk()), this)));
        }
        shown.add(toolName);
      }
      state.tools = List.copyOf(shown);
    }

    private void unregister() {
      List<Disposable> old;
      synchronized (registered) {
        old = List.copyOf(registered);
        registered.clear();
      }
      old.forEach(Disposable::dispose);
    }

    /** {@code tools/call}: the result as text for the model; images are saved to files. */
    ToolResult call(String tool, JsonNode args, ToolContext tc) {
      ObjectNode params = Json.object().put("name", tool);
      params.set("arguments", args == null || !args.isObject() ? Json.object() : args);
      JsonNode result;
      try {
        result = client.request("tools/call", params, tc.signal(), TimeUnit.SECONDS.toMillis(cfg.timeoutSeconds()));
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

    private String save(String type, String base64, String mime) {
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

  }

  /**
   * {@code mcp__<server>__<tool>}, with characters a model API refuses replaced by {@code _} and cut to the
   * 64 characters tool names may have.
   */
  static String toolName(String server, String tool) {
    String full = PREFIX + server + "__" + tool.replaceAll("[^A-Za-z0-9_-]", "_");
    return full.length() <= 64 ? full : full.substring(0, 64);
  }

  /** A tool of a server: calls go to its session. */
  private record McpTool(ToolSpec spec, String original, Risk risk, Session session) implements Tool {

    @Override
    public ToolResult call(JsonNode args, ToolContext ctx) {
      return session.call(original, args, ctx);
    }

    @Override
    public int timeoutSeconds() {
      // a little more than the call's own limit, so the server is sent notifications/cancelled first
      return session.cfg.timeoutSeconds() + 5;
    }
  }
}
