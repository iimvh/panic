# plugins/agents

Sub-agents: the main agent hands a self-contained task to a new agent loop with its own prompt, tools and
budget, and gets back only its final answer. Big or separate work (exploring a codebase, researching one part
of a change) then stays out of the main context, and several sub-agents can work at the same time.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `agents` | `AgentsConfig { profiles = {}, general = true, maxDepth = 1, maxParallel = 4, timeoutSeconds = 600 }`, each profile `{ description, systemPrompt?, tools?, maxSteps?, maxTokens? }` | none | tool `agent` (read), when there is any sub-agent; command `agents`. Asks hook `Chat.ASK` |

```json
"agents": {
  "profiles": {
    "explore": {
      "description": "Searches the codebase broadly (many files or folders) and reports each finding with its path, line numbers and the relevant code quoted, so you need not read those files again. Read-only. Not for reading one file you know.",
      "tools": ["read_file", "list_dir"],
      "maxSteps": 12
    }
  },
  "general": true,
  "maxDepth": 1,
  "maxParallel": 4,
  "timeoutSeconds": 600
}
```

- **Profiles** are named sub-agents (`[a-z][a-z0-9_-]*`). `description` (required) tells the model when to use
  it, and is the sub-agent's role in the built-in prompt; `systemPrompt` replaces that prompt. `tools` lists the
  only tools it may see and call (left out = all). `maxSteps` and `maxTokens` are its own limits (0 = the chat's).
- **`general`** (on by default) is a built-in sub-agent with every tool, whose instructions the model writes in
  the call (`instructions`). `"general": false` removes it, and then `general` may name a profile.
- **`maxDepth`**: how deep sub-agents nest. At the default 1 a sub-agent cannot start another one: `agent` is
  left out of its tools.
- **`maxParallel`**: how many sub-agents run at once in the whole app; more calls wait for a slot (cancelling
  the ask stops the wait). Several `agent` calls in one reply only run together when `chat.parallelTools` is
  above 1.
- **`timeoutSeconds`**: how long one sub-agent may run. It replaces `chat.toolTimeoutSeconds` for this tool.

No profile and `general: false`: no tool is registered.

## When a sub-agent pays off

A sub-agent costs a model loop of its own, and its work is wasted when the caller reads the same files again.
The tool description and the built-in prompt follow Anthropic's multi-agent guidance (the lead agent gives each
sub-agent an objective, a scope and an output format; the sub-agent returns a condensed result) and Claude Code's
Task/Explore tools (when not to delegate; return paths and code excerpts):

- **Delegate broad work only.** The description tells the model to use `agent` for searches across many files,
  research, or a separate part of a change, and not to read a known file or a few files through it.
- **Say what to return.** The task should state the goal, the scope and the output wanted.
- **Return evidence, not a story.** The built-in prompt asks for each finding's path (and lines) with the relevant
  code quoted as is, values as they came, and what was not found.
- **Trust the answer.** The description tells the caller not to read again the files the sub-agent quoted unless
  it is going to change them.

Profiles written with `description` only get the same prompt, so describe the profile's scope and output in it
(see the `explore` example above).

## The `agent` tool

`agent {agent, task, instructions?}` starts a delegated ask (`Chat.Delegate`, see `plugins/chat`):

- **Its own conversation**, `<conversation>~<agent>-<8 hex>`, saved by `conversations` like any other. It holds
  the task, the sub-agent's tool calls and results, and its answer. The sub-agent sees nothing of the calling
  conversation, so the task must say everything it needs.
- **Its result** is the sub-agent's final answer. Its tokens come back as `ToolResult.usage`, so they count in
  the calling turn's usage and `chat.maxTokens`. A sub-agent stopped by a limit gives an error result with its
  last answer (`explore stopped before it finished (max_steps, 12 steps)…`). With no LLM connected, the result
  is an error.
- **Permissions.** `agent` is a `read` tool; each of the sub-agent's own calls passes the permission gate. Calls
  that ask are put to the user's conversation (`ToolContext.origin`), with `Tools.Approval.agent` naming the
  sub-agent, so cli and web ask the user as usual.
- **Progress.** The sub-agent's text, reasoning (`ToolProgress.reasoning`) and tool calls reach the asker of the calling turn as `Ask.onAgentDelta` and
  nested steps (`Chat.Step.parentCallId`, `agent`). A deeper sub-agent's steps are reported under the same call.
- **Tracing.** The sub-agent's `turn` span (with `agent` and `origin`) nests under the `tool` span of the call,
  in the same trace.
- **Lifecycle.** The sub-agent runs on the calling thread and is cancelled with the call: by the asker, the
  tool timeout, or disposal of `agents` or `chat` (which waits for it). It is not reviewed (the answer of the
  turn that called it is) and never compacts its history.

## Keys used

| Key | From | Use |
| --- | --- | --- |
| `Chat.ASK`, `Chat.Delegate` | `plugins/chat` | Runs a sub-agent: a delegated ask |
| `Tools.register`, `Tools.specs`, `ToolContext`, `ToolProgress` | `plugins/tools` | Registers `agent`, lists the tools of a sub-agent, reports its progress |

## Commands

`agents` lists the sub-agents: name, tools, limits and description.
