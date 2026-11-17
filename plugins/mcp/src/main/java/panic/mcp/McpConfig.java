package panic.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import panic.tools.Risk;

/**
 * The MCP servers whose tools the agent may use. Each entry of {@code servers} has the shape of an entry of
 * Claude Code's {@code .mcp.json} ({@code mcpServers}), so one can be copied across:
 *
 * <ul>
 *   <li>{@code command}, {@code args}: the program to start (no shell); {@code env}: variables added to the
 *       app's environment for it; {@code cwd}: where it runs (relative to the app's working directory; default
 *       {@code .}).</li>
 *   <li>{@code type}: only {@code stdio} (or left out) is supported for now.</li>
 *   <li>{@code risk}: the {@link Risk} of every tool of the server, for the permission policy (default
 *       {@code network}: the policy asks the user unless told otherwise).</li>
 *   <li>{@code enabled}: false keeps the entry without starting it (default true).</li>
 * </ul>
 *
 * {@code dir}: where images a tool returns (screenshots) are saved (default {@code .panic/mcp}).
 * {@code timeoutSeconds}: a tool call's time limit (default 120). {@code startupSeconds}: how long a server may
 * take to answer {@code initialize} and {@code tools/list} (default 60; {@code npx} may download first).
 *
 * Server names make tool names ({@code mcp__<server>__<tool>}), so they are letters, digits, {@code _} and
 * {@code -}. A bad entry is a config error, not skipped: a typo must not go unnoticed.
 */
public record McpConfig(Map<String, Server> servers, String dir, int timeoutSeconds, int startupSeconds) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  public static final McpConfig DEFAULT = new McpConfig(null, null, 0, 0);

  public McpConfig {
    Map<String, Server> checked = new LinkedHashMap<>();
    if (servers != null) {
      servers.forEach((name, server) -> {
        if (name == null || !NAME.matcher(name).matches()) {
          throw new IllegalArgumentException("servers: bad server name '" + name + "' (letters, digits, _ and -)");
        }
        if (server == null) throw new IllegalArgumentException("servers." + name + ": empty entry");
        checked.put(name, server);
      });
    }
    servers = Map.copyOf(checked);
    dir = dir == null || dir.isBlank() ? ".panic/mcp" : dir.strip();
    if (timeoutSeconds <= 0) timeoutSeconds = 120;
    if (startupSeconds <= 0) startupSeconds = 60;
  }

  /** One server, as in {@code .mcp.json}, plus Panic's {@code risk} and {@code enabled}. */
  public record Server(String type, String command, List<String> args, Map<String, String> env, String cwd,
      String risk, Boolean enabled) {
    public Server {
      if (type != null && !type.isBlank() && !type.strip().equals("stdio")) {
        throw new IllegalArgumentException("type '" + type + "': only stdio servers are supported");
      }
      type = "stdio";
      if (command == null || command.isBlank()) throw new IllegalArgumentException("command is missing");
      command = command.strip();
      args = args == null ? List.of() : List.copyOf(args);
      env = env == null ? Map.of() : Map.copyOf(env);
      cwd = cwd == null || cwd.isBlank() ? "." : cwd.strip();
      risk = Risk.of(risk == null || risk.isBlank() ? "network" : risk).wire();
      if (enabled == null) enabled = true;
    }

    public Server(String command, List<String> args) {
      this(null, command, args, null, null, null, null);
    }

    /** The command line: {@code command} then {@code args}. */
    public List<String> commandLine() {
      List<String> line = new java.util.ArrayList<>();
      line.add(command);
      line.addAll(args);
      return line;
    }
  }
}
