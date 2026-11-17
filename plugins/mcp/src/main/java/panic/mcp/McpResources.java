package panic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import context.Context;
import core.Disposable;
import core.Signal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import panic.llm.ToolSpec;
import panic.mcp.McpClient.McpException;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.Json;

/**
 * The resources of the MCP servers (files, records, documents a server exposes by URI), offered the way Claude Code
 * offers them: two tools for all servers, {@code list_mcp_resources} and {@code read_mcp_resource}, present while at
 * least one ready server declares the {@code resources} capability. Both are {@link Risk#READ}: reading what a server
 * the user configured exposes changes nothing, and Claude Code does not ask for them either.
 *
 * Nothing is cached: every listing asks the servers, so {@code notifications/resources/list_changed} needs no
 * handling. Subscriptions are not used.
 */
final class McpResources {

  static final String LIST = "list_mcp_resources";
  static final String READ = "read_mcp_resource";

  /** The JSON-RPC code of a method the server does not have (a server without resource templates). */
  private static final int METHOD_NOT_FOUND = -32601;

  private final Context ctx;
  private final Map<String, McpSession> sessions;
  private final List<Disposable> tools = new ArrayList<>();

  McpResources(Context ctx, Map<String, McpSession> sessions) {
    this.ctx = ctx;
    this.sessions = sessions;
  }

  /** Registers the two tools when a ready server offers resources, and removes them when none does. */
  synchronized void update() {
    if (ctx.signal().isCancelled()) return; // the plugin is going away and takes the tools with it
    boolean any = sessions.values().stream().anyMatch(s -> s.ready() && s.offers("resources"));
    if (any && tools.isEmpty()) {
      tools.add(Tools.register(ctx, new ListTool()));
      tools.add(Tools.register(ctx, new ReadTool()));
    } else if (!any && !tools.isEmpty()) {
      tools.forEach(Disposable::dispose);
      tools.clear();
    }
  }

  private List<McpSession> withResources() {
    return sessions.values().stream().filter(s -> s.ready() && s.offers("resources"))
        .sorted((a, b) -> a.name().compareTo(b.name())).toList();
  }

  private String available() {
    List<String> names = withResources().stream().map(McpSession::name).toList();
    return names.isEmpty() ? "no server offers resources now" : "servers with resources: " + String.join(", ", names);
  }

  /**
   * The resources and URI templates of every server with resources, or of {@code only}: one line each,
   * {@code uri  name  (mimeType) - description}. Throws {@link McpException} for an unknown server.
   */
  String list(String only, Signal signal) {
    List<McpSession> targets = withResources().stream().filter(s -> only == null || s.name().equals(only)).toList();
    if (targets.isEmpty()) {
      throw new McpException(only == null ? available() : "no ready server " + only + " offers resources; " + available());
    }
    StringBuilder out = new StringBuilder();
    for (McpSession s : targets) {
      out.append(s.name()).append(":\n");
      int before = out.length();
      try {
        for (JsonNode r : pages(s, "resources/list", "resources", signal)) {
          out.append("  ").append(r.path("uri").asText("?")).append(describe(r)).append('\n');
        }
      } catch (McpException e) {
        out.append("  (cannot list resources: ").append(e.getMessage()).append(")\n");
      }
      try {
        for (JsonNode t : pages(s, "resources/templates/list", "resourceTemplates", signal)) {
          out.append("  template ").append(t.path("uriTemplate").asText("?")).append(describe(t)).append('\n');
        }
      } catch (McpException e) {
        if (e.code() != METHOD_NOT_FOUND) {
          out.append("  (cannot list templates: ").append(e.getMessage()).append(")\n");
        }
      }
      if (out.length() == before) out.append("  (none)\n");
    }
    return out.toString().stripTrailing();
  }

  private static String describe(JsonNode r) {
    StringBuilder d = new StringBuilder();
    String name = r.path("title").asText(r.path("name").asText(""));
    if (!name.isEmpty()) d.append("  ").append(name);
    if (r.hasNonNull("mimeType")) d.append("  (").append(r.path("mimeType").asText()).append(')');
    String description = r.path("description").asText("").strip().replaceAll("\\s+", " ");
    if (description.length() > 200) description = description.substring(0, 200) + "…";
    if (!description.isEmpty()) d.append(" - ").append(description);
    return d.toString();
  }

  private static List<JsonNode> pages(McpSession s, String method, String field, Signal signal) {
    List<JsonNode> all = new ArrayList<>();
    String cursor = null;
    do {
      ObjectNode params = Json.object();
      if (cursor != null) params.put("cursor", cursor);
      JsonNode page = s.request(method, params, signal, timeout(s));
      page.path(field).forEach(all::add);
      cursor = page.path("nextCursor").asText(null);
    } while (cursor != null && !cursor.isEmpty());
    return all;
  }

  private static long timeout(McpSession s) {
    return TimeUnit.SECONDS.toMillis(s.config().timeoutSeconds());
  }

  /**
   * The resource {@code uri} of {@code server} as text: text contents as they are (several are headed by their URI),
   * binary ones saved to a file and named. Throws {@link McpException} when it cannot be read.
   */
  String read(String server, String uri, Signal signal) {
    McpSession s = sessions.get(server);
    if (s == null || !s.ready() || !s.offers("resources")) {
      throw new McpException("no ready server " + server + " offers resources; " + available());
    }
    JsonNode result = s.request("resources/read", Json.object().put("uri", uri), signal, timeout(s));
    JsonNode contents = result.path("contents");
    List<String> parts = new ArrayList<>();
    for (JsonNode c : contents) {
      String head = contents.size() > 1 ? "--- " + c.path("uri").asText(uri) + "\n" : "";
      if (c.has("text")) {
        parts.add(head + c.path("text").asText(""));
      } else if (c.has("blob")) {
        parts.add(head + s.save("resource", c.path("blob").asText(""), c.path("mimeType").asText("")));
      }
    }
    return parts.isEmpty() ? "(empty resource)" : String.join("\n", parts);
  }

  private static ObjectNode schema(boolean read) {
    ObjectNode props = Json.object();
    props.set("server", Json.object().put("type", "string").put("description",
        read ? "The MCP server that has the resource." : "Only this server's resources (default: every server)."));
    if (read) {
      props.set("uri", Json.object().put("type", "string")
          .put("description", "The resource's URI, as listed, or a listed template with its parameters filled in."));
    }
    ObjectNode schema = Json.object().put("type", "object");
    schema.set("properties", props);
    if (read) schema.set("required", Json.MAPPER.createArrayNode().add("server").add("uri"));
    return schema;
  }

  private final class ListTool implements Tool {
    private final ToolSpec spec = new ToolSpec(LIST, "Lists the resources (files, records, documents) the connected "
        + "MCP servers offer, and the URI templates they accept. Read one with " + READ + ".", schema(false));

    @Override
    public ToolSpec spec() {
      return spec;
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      String server = args.path("server").asText("").strip();
      try {
        return ToolResult.ok(list(server.isEmpty() ? null : server, tc.signal()));
      } catch (McpException e) {
        return ToolResult.error(e.getMessage());
      }
    }
  }

  private final class ReadTool implements Tool {
    private final ToolSpec spec = new ToolSpec(READ, "Reads a resource of an MCP server by its URI (from "
        + LIST + ").", schema(true));

    @Override
    public ToolSpec spec() {
      return spec;
    }

    @Override
    public Risk risk() {
      return Risk.READ;
    }

    @Override
    public ToolResult call(JsonNode args, ToolContext tc) {
      String server = args.path("server").asText("");
      try {
        return ToolResult.ok(read(server, args.path("uri").asText(""), tc.signal()));
      } catch (McpException e) {
        return ToolResult.error("MCP server " + server + ": " + e.getMessage());
      }
    }
  }
}
