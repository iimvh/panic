# plugins/tools

The tool registry, the permission boundary and the built-in tools the model can call. This module runs single
tool calls. The loop that asks the model and feeds results back belongs to the agent, not here.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `tools` | none | none | commands `tools`, `tool` |
| `permissions` | `PermissionsConfig { risks = {read: allow, write: ask, exec: ask, network: ask}, tools = {}, askTimeoutSeconds = 120 }` | none | hook `Tools.POLICY` |
| `files` | `FilesConfig { root = ".", maxBytes = 65536, maxEntries = 200, maxWriteBytes = 1048576 }` | none | tools `read_file`, `list_dir` (read), `write_file` (write) |
| `clock` | none | none | tool `time` |

`files` sees only `root`, which is resolved against the app's working directory. A path must stay inside the
root after normalization and after following symlinks, so `../`, absolute paths and links that point out are
refused. `read_file` returns at most `maxBytes` characters and tells the model which `offset` to continue
from. Files with a NUL byte in their first 8 KiB are treated as binary and refused.

`write_file {path, content, append?}` creates or replaces a file, or appends to it, and creates missing
directories. The deepest existing directory on the way must be inside the root once symlinks are followed. The
target itself must not be a symlink (even one pointing inside) or a directory, and it is opened with
`NOFOLLOW_LINKS`. Content over `maxWriteBytes` bytes is refused.

## Permissions

Every tool declares a `Risk`: `read`, `write`, `exec` or `network`. Each call passes a gate inside the
registered `tool.run` hook, after its arguments are checked and before the tool runs, so no caller can skip it:

1. The `Tools.POLICY` hook gives the rule for the call: the tool's own entry in `permissions.tools`, otherwise
   the entry for its risk in `permissions.risks`. When `permissions` is not running (disabled, or restarting
   on a config change), `Verdict.fallback` applies: `read` is allowed, everything else is asked.
2. `allow` runs the call. `deny` returns `<tool>: denied by policy`.
3. `ask` calls the `Tools.APPROVE` hook and waits at most `askTimeoutSeconds`. Front-ends (`cli`, `web`) answer
   it for the conversations they talk to. No answer, a timeout or `DENY` returns `<tool>: denied by the user`.
   A cancelled caller is thrown (`CancelledException`).

Denials are ordinary error results, so the agent loop passes them to the model. Unknown risks or rules in
`permissions` are a config error, so a typo cannot open the gate. `permissions` requires nothing, so a change
to its section restarts only it and applies from the next call.

## Public keys

| Key / helper | Type | Meaning |
| --- | --- | --- |
| `Tools.register(ctx, tool)` | registers hooks in `ctx` | Declares a `Tool`. It exists while the plugin does, and disposal waits for a call in progress |
| `Tools.LIST` | hook `Listing → Info(ToolSpec, Risk)` | Every tool available right now (`collect`). Use `Tools.specs(ctx)` (what the model sees) or `Tools.infos(ctx)` (with risks). Both sort and drop duplicate names (first wins) |
| `Tools.RUN` | hook `Invocation(ToolCall, ToolContext) → ToolResult` | Runs a call through the permission gate. Use `Tools.call(ctx, call, toolCtx)` |
| `Tools.POLICY` | hook `Check(ToolCall, Risk) → Verdict(Permission, askTimeoutSeconds)` | The rule for a call. Answered by `permissions`. `Tools.verdict(ctx, call, risk)` applies the fallback |
| `Tools.APPROVE` | hook `Approval(conversationId, ToolCall, Risk, Signal) → Decision` | Asks the user. Front-ends answer only for their own conversations and return null otherwise. A blocking answer must honour `signal` |

`ToolContext(conversationId, signal, started?)`: `started` runs right before the tool itself, never for a
refused call. The agent loop starts its tool timeout there, so approval time does not count.

`Tools.call` never throws for a bad call. An unknown tool, invalid JSON, arguments that fail the schema, denied
calls and exceptions thrown by the tool all come back as `ToolResult.error(...)`, so the model can read them. Only
cancellation is thrown (`core.CancelledException`).

## Writing a tool

```java
Tools.register(ctx, new Tool() {
  public ToolSpec spec() {
    return new ToolSpec("weather", "current weather of a city", Json.parseOrNull(
        "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"));
  }
  public Risk risk() {
    return Risk.NETWORK; // asked by default
  }
  public ToolResult call(JsonNode args, ToolContext tc) {
    return ToolResult.ok(lookUp(args.get("city").asText(), tc.signal()));
  }
});
```

`args` has already been checked against the schema. The checked subset is `type`, `properties`, `required`,
`additionalProperties: false`, `enum`, `items`, `minimum` and `maximum`. Other keywords are sent to the
model but not checked. `tc.signal()` is cancelled when the caller gives up or when the tool's plugin stops.
Long work must honour it.

## Commands

`tools` lists the tools as `name [risk: permission]  description`. `tool <name> [args...]` runs one by hand,
through the same permission gate (in the session's conversation, so the REPL asks you). `args` is a JSON object (default `{}`),
for example `tool read_file {"path":"README.md","limit":5}`.
