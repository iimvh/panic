package panic.mcp;

import context.ConfigPlugin;
import context.Context;
import context.PluginHandle;
import core.Disposable;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.UsageException;
import panic.mcp.McpClient.McpException;
import panic.tools.Tool;

/**
 * Gives the agent the tools of Model Context Protocol servers, the way Claude Code extends its tools: each tool of
 * server {@code s} becomes a {@link Tool} named {@code mcp__s__<tool>} with the server's {@code inputSchema}, and
 * the {@link McpConfig.Server#risk} of its server, so the permission policy asks for it like for any other tool.
 * Servers are local processes (stdio) or remote (Streamable HTTP, or the older HTTP+SSE), see {@link Transport}.
 * Command {@code mcp} lists the servers and restarts one.
 *
 * Lifecycle: each enabled server is a sub-plugin of this one whose task {@code session} connects
 * ({@code initialize}, {@code notifications/initialized}, {@code tools/list}, again on
 * {@code notifications/tools/list_changed}); the transport may add a reading task of its own. A local server that
 * exits takes its tools with it, and a call waiting on it fails. A remote one that drops is connected again with
 * backoff, keeping its tools meanwhile (a call says it is reconnecting). Disposing the plugin (a config change, the
 * app stopping) closes every connection: a local server is stopped as the MCP spec says for stdio, a remote session
 * is ended with a DELETE. A cancelled tool call sends {@code notifications/cancelled}; a call that passes
 * {@link McpConfig#timeoutSeconds} is cancelled the same way. Requires nothing.
 */
public final class McpPlugin implements ConfigPlugin<McpConfig> {

  /** The MCP revision this client speaks; servers answer with theirs, which is accepted (only basics are used). */
  static final String PROTOCOL = "2025-06-18";

  static final String PREFIX = "mcp__";

  private final Consumer<String> warn;

  public McpPlugin(Consumer<String> warn) {
    this.warn = warn;
  }

  /** The system prompt section with the servers' instructions; Claude Code adds the same. */
  static final String SECTION = "MCP Server Instructions";

  /** At most this much of one server's instructions goes into every system prompt. */
  static final int MAX_INSTRUCTIONS = 2000;

  private static final String USAGE =
      "mcp [list | restart <name> | prompts [server] | resources [server] | read <server> <uri>]";

  @Override
  public Disposable apply(Context ctx, McpConfig cfg) {
    Map<String, McpSession.State> states = new TreeMap<>();
    Map<String, PluginHandle> handles = new ConcurrentHashMap<>();
    Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    McpResources resources = new McpResources(ctx, sessions);
    cfg.servers().forEach((name, server) -> {
      McpSession.State state = new McpSession.State();
      states.put(name, state);
      if (!server.enabled()) {
        state.status = "disabled";
      } else {
        handles.put(name, start(ctx, cfg, name, server, state, sessions, resources::update));
      }
    });
    Chat.contribute(ctx, SECTION, 40, q -> instructions(states));
    Commands.command(ctx, "mcp [action] [args...]",
        "MCP servers, their tools, prompts and resources: list | restart <name> | prompts [server] | "
            + "resources [server] | read <server> <uri>",
        call -> {
          String action = call.argOr("action", "list");
          String args = call.argOr("args", "").strip();
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
              if (args.isEmpty()) throw new UsageException("which server?", "mcp restart <name>");
              McpConfig.Server server = cfg.servers().get(args);
              if (server == null || !server.enabled()) {
                call.out().println("no enabled server " + args);
                return;
              }
              synchronized (handles) {
                PluginHandle old = handles.remove(args);
                if (old != null) old.dispose();
                McpSession.State state = states.get(args);
                state.status = "starting";
                state.tools = List.of();
                state.detail = null;
                handles.put(args, start(ctx, cfg, args, server, state, sessions, resources::update));
              }
              call.out().println("server " + args + " restarted");
            }
            case "prompts" -> {
              StringBuilder out = new StringBuilder();
              states.forEach((name, st) -> {
                if (st.prompts.isEmpty() || !(args.isEmpty() || args.equals(name))) return;
                out.append(name).append(":\n");
                st.prompts.forEach(p -> out.append("  ").append(p).append('\n'));
              });
              call.out().println(out.isEmpty() ? "no server offers prompts now" : out.toString().stripTrailing());
            }
            case "resources" -> {
              try {
                call.out().println(resources.list(args.isEmpty() ? null : args, call.signal()));
              } catch (McpException e) {
                call.out().println(e.getMessage());
              }
            }
            case "read" -> {
              String[] parts = args.split("\\s+", 2);
              if (parts.length < 2) throw new UsageException("which server and URI?", "mcp read <server> <uri>");
              try {
                call.out().println(resources.read(parts[0], parts[1].strip(), call.signal()));
              } catch (McpException e) {
                call.out().println("MCP server " + parts[0] + ": " + e.getMessage());
              }
            }
            default -> throw new UsageException("unknown action '" + action + "'", USAGE);
          }
        });
    return null;
  }

  /** The instructions of the servers that are ready, one heading per server; null when none has any. */
  static String instructions(Map<String, McpSession.State> states) {
    StringBuilder text = new StringBuilder();
    states.forEach((name, s) -> {
      String i = s.instructions;
      if (i == null || i.isBlank() || !s.status.equals("ready")) return;
      i = i.strip();
      if (i.length() > MAX_INSTRUCTIONS) i = i.substring(0, MAX_INSTRUCTIONS) + "… [cut]";
      text.append("\n### ").append(name).append('\n').append(i).append('\n');
    });
    return text.isEmpty() ? null
        : "The following MCP servers have provided instructions for how to use their tools and resources:\n" + text;
  }

  private PluginHandle start(Context ctx, McpConfig cfg, String name, McpConfig.Server server,
      McpSession.State state, Map<String, McpSession> sessions, Runnable onChange) {
    return ctx.plugin("server-" + name, sctx -> {
      McpSession session = new McpSession(sctx, cfg, name, server, state, warn, onChange);
      sessions.put(name, session);
      session.start();
      return null;
    });
  }

  /**
   * {@code mcp__<server>__<tool>}, with characters a model API refuses replaced by {@code _} and cut to the
   * 64 characters tool names may have.
   */
  static String toolName(String server, String tool) {
    String full = PREFIX + server + "__" + tool.replaceAll("[^A-Za-z0-9_-]", "_");
    return full.length() <= 64 ? full : full.substring(0, 64);
  }
}
