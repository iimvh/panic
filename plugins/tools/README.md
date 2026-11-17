# plugins/tools

The tool registry, the permission boundary and the built-in tools the model can call. This module runs single
tool calls. The loop that asks the model and feeds results back belongs to the agent, not here.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `tools` | none | none | commands `tools`, `tool` |
| `permissions` | `PermissionsConfig { risks = {read: allow, write: ask, exec: ask, network: ask}, tools = {}, askTimeoutSeconds = 120, bypassPermissions = false }` | none | hooks `Tools.POLICY`, `Tools.BYPASS`; command `bypass` |
| `files` | `FilesConfig { root = ".", maxBytes = 65536, maxEntries = 200, maxWriteBytes = 1048576 }` | none | tools `read_file`, `list_dir` (read), `write_file`, `edit_file` (write) |
| `clock` | none | none | tool `time` |
| `shell` | `ShellConfig { dir = ".", timeoutSeconds = 120, maxTimeoutSeconds = 600, maxOutput = 8000, maxBackground = 4, deny = (defaults) }` | none | tools `exec` (exec), `exec_output` (read), `exec_stop` (exec); answers `Tools.GATE` for `exec`; command `jobs` |

`files` sees only one root: the folder of the conversation's project (key `Workspace.ROOT`, answered by plugin
`workspace`, asked with the conversation the user talks to, so a sub-agent works where its parent does), or
`root` when the conversation has no project. `root` is resolved against the app's working directory. A path must stay inside the
root after normalization and after following symlinks, so `../`, absolute paths and links that point out are
refused. `read_file` returns at most `maxBytes` characters and tells the model which `offset` to continue
from. Files with a NUL byte in their first 8 KiB are treated as binary and refused.

`write_file {path, content, append?}` creates or replaces a file, or appends to it, and creates missing
directories. The deepest existing directory on the way must be inside the root once symlinks are followed. The
target itself must not be a symlink (even one pointing inside) or a directory, and it is opened with
`NOFOLLOW_LINKS`. Content over `maxWriteBytes` bytes is refused. Its description sends the model to `edit_file`
for changes to an existing file.

`edit_file {path, old_str, new_str, replace_all?}` changes part of an existing file, like the `str_replace` command
of Anthropic's text editor tool and Claude Code's Edit tool: a one-line change costs the model that line, not a
rewrite of the file (and a long `write_file` is where broken JSON arguments come from).

- `old_str` must match the file exactly and occur exactly once. Not found: an error that tells the model to read
  the part again and copy it. Found N times: an error with N, telling it to add context or set `replace_all`.
- `replace_all: true` replaces every occurrence. An empty `new_str` deletes. An empty `old_str`, or one equal to
  `new_str`, is refused.
- A file with `\r\n` line endings is matched when the model writes `\n`, and keeps its `\r\n`.
- The same guards as `write_file`: the file must exist inside the root (it is never created), must not be a
  symlink or a directory or binary, and the edited file may not exceed `maxWriteBytes`. It is written in place
  with `NOFOLLOW_LINKS`.
- The result is short: `edited <path>: replaced N occurrence(s) (first at line L)`.

## Shell commands (`shell`)

The agent runs shell commands in the folder of the conversation's project (`Workspace.ROOT`), or in `dir` when it
has none:

- **`exec {command, cwd?, timeoutSeconds?, background?}`** runs `command` through `sh -c` (`cmd /c` on Windows).
  - It runs with no input, and stdout and stderr go together to a temporary log.
  - `cwd` is a folder inside the project. One that leads out, even through a link, is refused.
  - The result is `exit N (1.2s)` and the last `maxOutput` characters of the output.
  - A non-zero exit, or `timeoutSeconds` passing (default `timeoutSeconds`, at most `maxTimeoutSeconds`), is an
    error result, so the agent reads the failure and goes on. On a timeout the process and everything it started
    are killed, and so they are when the call is cancelled.
  - The tool asks for `maxTimeoutSeconds + 10` as its own limit, so `chat.toolTimeoutSeconds` does not cut it.
- **`background: true`** starts a job for a command that does not end by itself, such as a dev server or a
  watcher.
  - `exec` watches it for 5 seconds and returns `started job <id> (pid N)` with its first output (a server's
    address). A job that ended in that time is reported with its exit status.
  - At most `maxBackground` jobs run at once per conversation.
  - Each job is a task of the plugin, so a config change or the app stopping kills them all.
- **`exec_output {id}`** (read) gives a job's state (`running`, or `exit N`) and the end of its output.
- **`exec_stop {id}`** (exec) kills it and everything it started. Both see only the jobs of the conversation that
  asks.
- **Approval.** `exec` and `exec_stop` have risk `exec`: the default policy asks the user for every command (the
  REPL shows `$ command`, the web UI the command line). Bypass mode or `permissions.tools.exec: "allow"` lets them
  run unasked, and plan mode refuses them until a plan is approved.
- **Deny rules.** A command that matches a `deny` pattern (a regex, case-insensitive, found anywhere in the command
  line) is refused by a `Tools.GATE` before anyone is asked, even in bypass mode. The default list
  (`ShellConfig.DEFAULT_DENY`) covers `sudo`/`su`/`doas`, `rm` of `/`, `~` or system folders, `mkfs`, `dd` to a
  device, `shutdown`/`reboot`, fork bombs and `curl`/`wget` piped into a shell. A `deny` list in the config
  replaces it, and a pattern that does not compile is a config error.
- **These are guardrails, not a sandbox.** A command runs with the app's user and environment, so keep approvals on
  where a mistake would hurt.
- **Sub-agents** may use it too, approved in the user's conversation.

`jobs` lists every job (`id  state  age  command  (in dir, chat id)`), and `jobs stop <id>` stops one. Jobs are not
stopped when their conversation is deleted: stop them with `jobs stop` (this module cannot hear about conversations,
which live in `plugins/chat`).

`Processes` (public) holds what tools that run processes share: `shell(command)`, `kill(process)` (with its
descendants), `nullDevice()` and `tail(log, max)`. `check` of `plugins/verify` uses it too.

## Public keys: workspaces

`Workspace` holds the keys for projects (folders a conversation works in). Plugins ask them instead of requiring
the plugin that answers (`workspace`, `folder-picker` in `plugins/workspace`); with no answer there is no project.

| Key | Type | Meaning |
| --- | --- | --- |
| `Workspace.ROOT` | hook `Query(conversationId) → Path` | The project folder of a conversation; no answer when it has none. Used by `files` and `checks` (`Workspace.root(ctx, id, fallback)`) |
| `Workspace.LIST` | hook `Listing.ALL → Projects(projects[Project(path, name, conversations)], hidden[id])` | The active projects and the chats of removed ones (not to be listed) |
| `Workspace.PICK` | hook `PickQuery(signal) → Picked(path)` | A folder chosen in a native dialog; no answer when cancelled or unavailable |

## Permissions

Every tool declares a `Risk`: `read`, `write`, `exec` or `network`. Each call passes a gate inside the
registered `tool.run` hook, before the tool runs, so no caller can skip it:

0. The `Tools.GATE` hook (collected) may keep the tool out of the conversation the user talks to (`origin`), for
   example while it is in plan mode (`plugins/planner`). Any `Refusal` returns `<tool>: refused: <reason>`, before
   the arguments are checked or anyone is asked; bypass mode does not lift it. A gate that throws refuses too.
   `Tools.specs(ctx, conversationId)` leaves out the tools refused with `hide` (and those of a gate that throws).
   A refusal without `hide` keeps the tool offered, so the model learns the reason when it calls it instead of
   guessing why it has no way to act.
1. The `Tools.POLICY` hook gives the rule for the call: the tool's own entry in `permissions.tools`, otherwise
   the entry for its risk in `permissions.risks`. When `permissions` is not running (disabled, or restarting
   on a config change), `Verdict.fallback` applies: `read` is allowed, everything else is asked.
2. `allow` runs the call. `deny` returns `<tool>: denied by policy`.
3. `ask` calls the `Tools.APPROVE` hook and waits at most `askTimeoutSeconds`. Front-ends (`cli`, `web`) answer
   it for the conversations they talk to. No answer, a timeout or `DENY` returns `<tool>: denied by the user`.
   A cancelled caller is thrown (`CancelledException`).

### Bypass mode (YOLO)

`permissions.bypassPermissions: true` (default `false`) turns every `ask` into `allow`, so nothing waits for the
user. A rule that says `deny` (in `permissions.tools` or `permissions.risks`) still denies: bypass only removes the
questions, it never overrides an explicit refusal. A conversation can also switch it for itself with command
`bypass on | off | status` (the web UI has a header switch, the REPL prompt reads `[bypass] > `). That choice wins
over the config default in both directions, applies to the sub-agents of that conversation, and is kept in memory
only: it is not config (a plugin never updates its own), so it is forgotten when `permissions` restarts, for
example when its section changes. One-shot mode has no approver and denies what would be asked, so set
`bypassPermissions` in the config for scripts. Use it only where the agent can do no harm you cannot undo.

Denials are ordinary error results, so the agent loop passes them to the model. Unknown risks or rules in
`permissions` are a config error, so a typo cannot open the gate. `permissions` requires nothing, so a change
to its section restarts only it and applies from the next call.

## Public keys

| Key / helper | Type | Meaning |
| --- | --- | --- |
| `Tools.register(ctx, tool)` | registers hooks in `ctx` | Declares a `Tool`. It exists while the plugin does, and disposal waits for a call in progress |
| `Tools.LIST` | hook `Listing → Info(ToolSpec, Risk, timeoutSeconds)` | Every tool available right now (`collect`). Use `Tools.specs(ctx)` (what the model sees), `Tools.infos(ctx)` (with risks and time limits) or `Tools.info(ctx, name)`. They sort and drop duplicate names (first wins) |
| `Tools.RUN` | hook `Invocation(ToolCall, ToolContext) → ToolResult` | Runs a call through the permission gate. Use `Tools.call(ctx, call, toolCtx)` |
| `Tools.POLICY` | hook `Check(ToolCall, Risk, conversationId) → Verdict(Permission, askTimeoutSeconds)` | The rule for a call (`conversationId` is the conversation the user talks to, null when none). Answered by `permissions`, which applies bypass mode. `Tools.verdict(ctx, call, risk[, conversationId])` applies the fallback |
| `Tools.GATE` | hook `Gate(conversationId, name, Risk, arguments?) → Refusal(reason, hide)`, collected; `arguments` is the call's raw JSON when a call is checked, null in a listing | Refuses a tool's calls in a conversation (a mode like plan mode); `hide` (the default of `Refusal(reason)`) also leaves it out of `Tools.specs(ctx, conversationId)`. `Tools.refusal(ctx, conversationId, info)` gives the first reason |
| `Tools.BYPASS` | hook `BypassQuery(conversationId) → Bypass(on, byDefault)` | Whether a conversation bypasses approvals. Answered by `permissions`; front-ends show it |
| `Tools.APPROVE` | hook `Approval(conversationId, ToolCall, Risk, Signal, agent?) → Decision` | Asks the user. `conversationId` is the call's `origin`, the conversation the user talks to, also for a sub-agent's call (`agent` names it). Front-ends answer only for their own conversations and return null otherwise. A blocking answer must honour `signal` |

`ToolContext(conversationId, signal, started?, origin?, agent?, traceId?, spanId?, progress?)`:

- `started` runs right before the tool itself, never for a refused call. The agent loop starts its tool timeout
  there, so approval time does not count.
- `origin` is the conversation the user talks to (default `conversationId`). A sub-agent's calls run in its own
  conversation, but are approved in `origin`; `agent` names the sub-agent (null for the main agent).
- `traceId` / `spanId` are the caller's trace and the call's span (null when untraced), so a tool doing traced
  work of its own can nest it under the call.
- `progress` hears `ToolProgress` (a piece of `text`, or one of the tool's own calls starting or ending) while a
  long tool runs. The agent loop passes it on to the asker; nobody has to listen.

`ToolResult(content, isError, usage?)`: `usage` is what the tool spent on a model of its own (a sub-agent). The
agent loop adds it to the turn's usage and budget. `Tool.timeoutSeconds()` (default 0 = the caller's limit,
`chat.toolTimeoutSeconds`) lets a slow tool ask for more time.

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
