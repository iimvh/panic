# plugins/verify

Verification: ground truth from real commands, and an independent judge of the final answer. Together with the
acceptance criteria of `plugins/todo`, they keep the agent from calling work done because it says so.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `checks` | `ChecksConfig { dir = ".", checks = {}, maxOutput = 8000 }`, each check `{ command: [...], timeoutSeconds = 600 }` | none | tool `check` (exec), when at least one check is configured; command `checks` |
| `review` | `ReviewConfig { prompt = (built in), maxTokens = 512, maxTranscript = 12000, temperature = null }` | `Llm.KEY` | answers hook `Chat.REVIEW` |

## `check`: external ground truth

```json
"checks": {
  "dir": ".",
  "checks": {
    "test": { "command": ["./gradlew", "check", "-PskipUi"], "timeoutSeconds": 600 },
    "lint": { "command": ["sh", "-c", "npm run lint"] }
  }
}
```

`check {name}` runs the configured command for `name` in the project folder of the conversation (hook
`Workspace.ROOT`), or in `dir` (relative to the app's working directory) when it has no project, and
returns `check <name> passed|failed (exit N, 3.2s)` and the end of its output (stdout and stderr together, last
`maxOutput` characters: failures are usually at the end). The model only picks a name from the configured ones
(an `enum` in the schema), never the command, which runs without a shell unless you write `sh -c`.

- A non-zero exit or a timeout is an error result, so the agent loop goes on: the model reads the failure,
  fixes things and runs the check again.
- Risk `exec`: the default policy asks the user each time. Allow it with
  `"permissions": {"tools": {"check": "allow"}}` once you trust the configured commands.
- The process and its children are killed when `timeoutSeconds` passes or the call is cancelled (with
  `Processes` of `plugins/tools`, shared with the `exec` tool). The tool asks
  for the longest configured `timeoutSeconds` (plus 10s) as its own limit (`Tool.timeoutSeconds`), so the chat's
  `chat.toolTimeoutSeconds` (default 60) does not cut long checks.
- No checks configured: no tool is registered.

## `review`: an independent evaluator

`review` answers `Chat.REVIEW`, which `chat` asks whenever the model gives a final answer. It reads the
conversation's plan through `Todo.PLAN`; with no acceptance criteria it gives no verdict, so the answer stands
and nothing is spent. Otherwise it makes one model call with its own prompt (`prompt`) and a brief of the turn:
the question, the numbered criteria, what the assistant did (tool calls and results, each cut at 2000
characters, the newest `maxTranscript` characters kept) and the final answer. It expects
`{"pass": bool, "reason": "..."}` and takes the first JSON object in the reply.

A rejection goes back to the model (see `chat.maxReviews` in `plugins/chat`); the turn stops as `rejected` when
the retries are used up. A failed model call, or a reply that is not a verdict, throws (`ReviewException`):
`chat` lets the answer stand, but ends the `review` span as ERROR with the message and reports the failure to the
asker (the web UI shows "Review failed: ..."), so a broken check is visible instead of passing silently.

The review uses the same LLM as the chat; its tokens count in the turn's usage. It sends only `maxTokens` and,
when set, `temperature`: null (the default) lets the model use its own default, because some models reject
`temperature: 0` (for example "top_p must be 1 when using greedy sampling").

## Keys used

| Key | From | Use |
| --- | --- | --- |
| `Tools.register` | `plugins/tools` | Registers `check` |
| `Chat.REVIEW` | `plugins/chat` | Answered by `review` |
| `Todo.PLAN` | `plugins/todo` | The acceptance criteria to judge against |
| `Llm.KEY` | `plugins/llm` | The model `review` calls |

## Commands

`checks` lists the configured checks with their command, timeout and directory.
