# plugins/mcp

Tools from [Model Context Protocol](https://modelcontextprotocol.io) servers. Claude Code extends its tools the same
way: a browser server such as `chrome-devtools-mcp` lets the agent open the page it built, take a snapshot or a
screenshot, and see what a passing `npm run build` cannot show (a style that did not load, text over a heading).

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `mcp` | `McpConfig { servers = {}, dir = ".panic/mcp", timeoutSeconds = 120, startupSeconds = 60 }` | none | one tool per tool of each running server, `mcp__<server>__<tool>`; command `mcp` |

## Config

```json
"mcp": {
  "servers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--browserUrl", "http://127.0.0.1:9222"],
      "env": {},
      "risk": "network"
    }
  }
}
```

Each entry of `servers` has the shape of an entry of Claude Code's `.mcp.json` (`mcpServers`), so it can be copied
across: `type` (only `stdio`, the default), `command`, `args` (no shell), `env` (added to the app's environment;
`${ENV}` works as everywhere in `panic.json`), `cwd` (default `.`). Panic adds:

- `risk`: the risk of every tool of the server for the permission policy (`read`, `write`, `exec`, `network`;
  default `network`, which the default policy asks about). Allow single tools with
  `"permissions": {"tools": {"mcp__chrome-devtools__take_snapshot": "allow"}}`.
- `enabled`: `false` keeps the entry without starting the server.

`dir` is where images returned by tools are saved, `timeoutSeconds` a tool call's time limit, `startupSeconds` how
long `initialize` and `tools/list` may take (`npx` may download the server first). A bad entry (unknown `type` or
`risk`, no `command`, a server name other than letters, digits, `_` and `-`) is a config error.

## How it works

- **Transport:** stdio only. Panic starts the server as a child process and speaks JSON-RPC 2.0, one message per
  line (`McpClient`, Jackson only). The server's stderr goes to a temporary log, whose end is shown when it fails.
- **Lifecycle** (MCP spec): `initialize` (protocol `2025-06-18`, no client capabilities), then
  `notifications/initialized`, then `tools/list` (every page). `notifications/tools/list_changed` lists the tools
  again. A `ping` from the server is answered; other requests from it (sampling, roots, elicitation) get "method not
  found", since the client declares none.
- **Tools:** each tool becomes `mcp__<server>__<tool>` (Claude Code's naming; characters a model API refuses become
  `_`, cut at 64), with the server's description and `inputSchema`. Calls go through the permission policy like any
  other tool.
- **Results:** text content is passed on as is. Images and audio are saved under `dir/<server>/` and the result
  names the file (models without vision cannot read them; use a text tool such as chrome-devtools-mcp's
  `take_snapshot` to inspect a page). `isError: true` makes an error result.
- **Cancellation and time limits:** a cancelled call (the turn is stopped) or one that passes `timeoutSeconds` sends
  `notifications/cancelled` to the server. The tool declares `timeoutSeconds + 5` as its own limit, so
  `chat.toolTimeoutSeconds` does not cut it first.
- **Ownership:** each server is a sub-plugin of `mcp` (`server-<name>`) with two tasks: one reads the server's
  stdout, one runs the session. When the server exits, its tools go away and calls waiting on it fail. Disposing
  `mcp` (a config change, the app stopping) stops every server the way the spec says for stdio: close its input,
  wait, SIGTERM, wait, SIGKILL, then whatever it started.

## Keys used

| Key | From | Use |
| --- | --- | --- |
| `Tools.register` | `plugins/tools` | Registers each server's tools |

## Commands

| Command | Does |
| --- | --- |
| `mcp [list]` | Each server with its state (`starting`, `ready`, `failed`, `exited (exit N)`, `disabled`), the server's name and protocol or why it stopped, and its tools |
| `mcp restart <name>` | Stops the server and starts it again (after it crashed, or to pick up a new version) |
