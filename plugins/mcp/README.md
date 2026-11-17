# plugins/mcp

Tools from [Model Context Protocol](https://modelcontextprotocol.io) servers. Claude Code extends its tools the same
way: a browser server such as `chrome-devtools-mcp` lets the agent open the page it built, take a snapshot or a
screenshot, and see what a passing `npm run build` cannot show (a style that did not load, text over a heading). A
remote server (GitHub, Sentry, documentation search) is reached over HTTP.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `mcp` | `McpConfig { servers = {}, dir = ".panic/mcp", timeoutSeconds = 120, startupSeconds = 60 }` | none | one tool per tool of each running server, `mcp__<server>__<tool>`; `list_mcp_resources` and `read_mcp_resource` while a server offers resources; commands `mcp` and one `mcp__<server>__<prompt>` per prompt |

## Config

```json
"mcp": {
  "servers": {
    "chrome-devtools": {
      "command": "npx",
      "args": ["-y", "chrome-devtools-mcp@latest", "--browserUrl", "http://127.0.0.1:9222"],
      "env": {},
      "risk": "network"
    },
    "context7": {
      "type": "http",
      "url": "https://mcp.context7.com/mcp",
      "headers": {"CONTEXT7_API_KEY": "${CONTEXT7_API_KEY}"}
    }
  }
}
```

Each entry of `servers` has the shape of an entry of Claude Code's `.mcp.json` (`mcpServers`), so it can be copied
across:

- `type`: `stdio` (a local process), `http` (MCP Streamable HTTP) or `sse` (the older HTTP+SSE transport, which
  Claude Code still supports and calls deprecated). Left out, it is `http` when there is a `url`, else `stdio`.
- stdio: `command`, `args` (no shell), `env` (added to the app's environment), `cwd` (default `.`).
- http and sse: `url` (http or https) and `headers`, sent with every request. `${ENV}` works in them as everywhere
  in `panic.json`, which is how a token gets there (`"Authorization": "Bearer ${GITHUB_TOKEN}"`). OAuth is not
  supported: a server that needs it answers 401, and the status says to set `headers`.

Panic adds:

- `risk`: the risk of every tool of the server for the permission policy (`read`, `write`, `exec`, `network`;
  default `network`, which the default policy asks about). Allow single tools with
  `"permissions": {"tools": {"mcp__chrome-devtools__take_snapshot": "allow"}}`.
- `enabled`: `false` keeps the entry without starting the server.
- `includeTools` (only these) and `excludeTools` (not these): the server's tools the agent gets, by their name on the
  server; default all. A server with dozens of tools costs tokens on every request; this is OpenAI Agents SDK's
  `tool_filter` and the per-tool switches of Cursor and VS Code. `mcp list` still shows a filtered tool, marked
  `(filtered)`.

`dir` is where images returned by tools are saved, `timeoutSeconds` a tool call's time limit, `startupSeconds` how
long `initialize` and `tools/list` may take (`npx` may download the server first). A bad entry is a config error: an
unknown `type` or `risk`, no `command` for stdio, no http(s) `url` for http or sse, a `url` with stdio or a
`command` with http, a server name other than letters, digits, `_` and `-`.

## How it works

- **Protocol:** JSON-RPC 2.0 with Jackson only (`McpClient`), over a `Transport`. `initialize` (protocol
  `2025-06-18`, no client capabilities), then `notifications/initialized`, then `tools/list` (every page).
  `notifications/tools/list_changed` lists the tools again; a tool that did not change keeps its registration, so a
  call in progress is not disturbed. A `ping` from the server is answered; other requests from it (sampling, roots,
  elicitation) get "method not found", since the client declares none.
- **stdio** (`StdioTransport`): Panic starts the server as a child process, one message per line. The server's
  stderr goes to a temporary log, whose end is shown when it fails.
- **Streamable HTTP** (`HttpTransport`, spec 2025-06-18): every message is a POST accepting `application/json` and
  `text/event-stream`. An answer that is an event stream is read as it comes and may carry the server's own requests
  and notifications before the response. The `Mcp-Session-Id` given with the answer to `initialize` goes with every
  later request, with `MCP-Protocol-Version`. A GET stream carries what the server sends on its own
  (`list_changed`); a server without one answers 405, which is fine, and a stream that ends is opened again with
  `Last-Event-ID`. A 404 in a session means the server forgot it: the session initializes again and the request is
  sent once more, as the spec asks. Bodies are read by the HTTP client's own threads, so no plugin thread waits on
  a stream.
- **HTTP+SSE** (`SseTransport`, spec 2024-11-05): a GET stream whose `endpoint` event names where to POST; every
  answer comes on the stream. The endpoint must be on the same origin as `url`, since the headers go to it.
- **Tools:** each tool becomes `mcp__<server>__<tool>` (Claude Code's naming; characters a model API refuses become
  `_`, cut at 64), with the server's description and `inputSchema`. Calls go through the permission policy like any
  other tool.
- **Instructions:** what a server says in `instructions` when it initializes goes into the system prompt, in the
  section `MCP Server Instructions` (`### <server>` each, at most 2000 characters per server), while the server is
  ready. Claude Code does the same.
- **Resources** (`McpResources`): data a server exposes by URI (files, records, documents). As in Claude Code
  (`ListMcpResourcesTool`, `ReadMcpResourceTool`), two tools serve every server, present while at least one ready
  server declares the `resources` capability: `list_mcp_resources {server?}` lists resources and URI templates
  (every page), `read_mcp_resource {server, uri}` reads one (text as is, binary saved under `dir/<server>/`). Both
  are risk `read`, so the default policy runs them without asking, like Claude Code; ask for them with
  `"permissions": {"tools": {"read_mcp_resource": "ask"}}`. Nothing is cached, so a changed list needs no
  notification; subscriptions are not used.
- **Prompts** (`McpPrompts`): templates a server offers for the user to start from. As Claude Code's slash commands
  `/mcp__<server>__<prompt> arg1 arg2`, each prompt becomes the command `mcp__<server>__<prompt>` (lowercase, other
  characters `_`). Values go to the prompt's arguments in the order it declares them; quotes group words and the
  last argument takes the rest of the line as typed; a required one that is missing shows the usage. The command gets the
  prompt (`prompts/get`) and runs the chat's `ask` command with its text in the same session, so the chat gets it
  as if the user had typed it. A prompt of several messages is sent as one text, each message headed by its role.
  Without a chat the text is printed. `notifications/prompts/list_changed` lists them again, keeping the commands
  that did not change. In the web UI a command's output is shown as such, not as a chat turn: there, typing
  `/mcp__server__prompt` gives the answer but not a turn in the conversation view.
- **Results:** text content is passed on as is. Images and audio are saved under `dir/<server>/` and the result
  names the file (models without vision cannot read them; use a text tool such as chrome-devtools-mcp's
  `take_snapshot` to inspect a page). `isError: true` makes an error result.
- **Cancellation and time limits:** a cancelled call (the turn is stopped) or one that passes `timeoutSeconds` sends
  `notifications/cancelled` to the server. The tool declares `timeoutSeconds + 5` as its own limit, so
  `chat.toolTimeoutSeconds` does not cut it first.
- **When a server goes away:** a local server that exits takes its tools with it and stays down until `mcp restart`
  (as in Claude Code). A remote one that drops is connected again, at once and then after 1, 2, 4 and 8 s; after 5
  failed attempts in a row it is `failed`. Meanwhile its tools stay, and a call answers that the server is
  reconnecting (Claude Code also reconnects remote servers with backoff).
- **Ownership:** each server is a sub-plugin of `mcp` (`server-<name>`) whose task `session` runs the connection;
  stdio adds a task reading stdout, Streamable HTTP one reading the GET stream. Disposing `mcp` (a config change,
  the app stopping) closes every connection: a local server is stopped the way the spec says for stdio (close its
  input, wait, SIGTERM, wait, SIGKILL, then whatever it started), a remote session is ended with a DELETE.

## Keys used

| Key | From | Use |
| --- | --- | --- |
| `Tools.register` | `plugins/tools` | Registers each server's tools |
| `Chat.contribute` | `plugins/chat` | Section `MCP Server Instructions` of the system prompt |
| command `ask` | `plugins/chat` | A prompt command sends the prompt's text to the chat |

## Commands

| Command | Does |
| --- | --- |
| `mcp [list]` | Each server with its state (`starting`, `ready`, `reconnecting`, `failed`, `exited (exit N)`, `disabled`), the server's name and protocol or why it stopped, and its tools |
| `mcp prompts [server]` | The prompts of every server that offers them, as commands with their arguments |
| `mcp__<server>__<prompt> [args...]` | Sends the prompt to the chat (see Prompts) |
| `mcp resources [server]` | The resources and URI templates of every server that offers them, or of one |
| `mcp read <server> <uri>` | Prints a resource (binary content is saved to a file) |
| `mcp restart <name>` | Stops the server and starts it again (after it crashed or gave up, or to pick up a new version) |
