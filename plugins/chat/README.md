# plugins/chat

Conversation history and the chat bot, an agent loop that calls tools.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `conversations` | `ConversationsConfig { dir = ".panic/conversations", persist = true }` | none | service `Conversations.KEY` (on disk, or in memory with `persist: false`); event `Conversations.DELETED`; hooks `Conversations.LIST`, `HISTORY`, `REMOVE`; commands `reset`, `chats`, `forget` |
| `chat` | `ChatConfig { systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats, contextTokens, compact, maxReviews, parallelTools }` | `Llm.KEY`, `Conversations.KEY` | hook `Chat.ASK`; events `Chat.TURN`, `Chat.STEP`, `Chat.REVIEWED`, `Trace.SPAN`; command `ask`. Collects hook `Chat.CONTEXT`, asks hook `Chat.REVIEW` |

`conversations` does not depend on the LLM, so history survives a provider or model switch (which restarts
`chat`).

## Storage

Each conversation is a JSON lines file in `dir` (resolved against the app's working directory), named after the
conversation id (`repl.jsonl`), or `~` + the id in base64url when the id is not a short `[A-Za-z0-9_-]` name.
Header lines come first: `{"parent": ...}` for a conversation that works for another (a sub-agent's, set by
`chat` from `Chat.Delegate.origin`), and `{"summary": ...}` for the compaction summary. Every other line is a
message (`{"role", "content", "toolCalls"?, "toolCallId"?}`).

- **Loaded on first use.** A conversation is read the first time it is asked for after a start.
- **Written through.** An exchange is appended in one write, under the conversation's lock, so concurrent
  appends never interleave and a crash leaves at most a torn last line. A compaction rewrites the file through
  a temporary file and an atomic rename. `reset` deletes the file.
- **Damage is skipped, not fatal.** A line that is not a valid message is skipped with a warning, and so is a
  turn whose tool calls lack their results (a provider would reject it). The file is then rewritten clean.
- **Failures are warnings.** If the directory or a file cannot be written, the conversation goes on in memory
  and a warning is printed.
- **One process per directory.** Two apps sharing `dir` would interleave their writes.
- **Listing** (`list()`) covers the loaded conversations and the files not loaded yet, reading a file only up to
  its first question. It leaves out empty conversations and those with a `parent`, newest first. `Info.title`
  is the first line of the first question, at most 80 characters.
- **Deleting** (`delete(id)`) removes the conversation and, recursively, every conversation whose `parent` it
  is (a sub-agent's), from memory and disk. It emits `Conversations.DELETED` once for each, so `todo` and
  `tracing` drop their data for it. Deleting a conversation while a turn is running in it is the caller's
  business: `web` refuses it.

## The agent loop

On each ask, `chat` sends the history and the tools registered right now (`Tools.specs`, from
`plugins/tools`). It does not require them: with no tools it is a plain chat bot. When the model asks for tools,
`chat` runs each call (`Tools.call`), appends the results as `tool` messages and calls the model again. It repeats
until the model answers or a limit stops it:

| Stop (`Reply.stop`) | When |
| --- | --- |
| `done` | The model answered without calling a tool |
| `max_steps` | `maxSteps` model calls were made (default 8) |
| `budget` | The tokens of the turn reached `maxTokens` (default 0 = no budget) |
| `loop` | The same call (name and arguments, key order ignored) came more than `maxRepeats + 1` times in a row. From the `maxRepeats + 1`th time (default 2) it is refused with an error result instead of run |
| `rejected` | The reviewer still rejected the answer after `maxReviews` retries (or no step was left for one). `Reply.review` holds its reason |

Every failure is an observation for the model, not a crash of the turn. Unknown tools, bad arguments, tool
errors, calls denied by the permission policy or the user, and timeouts (`toolTimeoutSeconds`, default 60) all
come back as `error: ...` results. A tool may set its own limit (`Tool.timeoutSeconds`), which then replaces
`toolTimeoutSeconds`. The timeout starts when the tool starts, so the time the user takes to
approve a call does not count (that wait has its own limit, `permissions.askTimeoutSeconds`). Tool output is
cut at `maxToolOutput` characters (default 16000). When a turn stops early, the calls that were not run get an
error result, so the saved history stays valid. The whole turn (user, assistant tool calls, tool results,
answer) is appended at once. A cancelled turn is not saved. `tools: false` turns tools off for models that do
not support them.

**Parallel calls.** When the model asks for several tools in one reply, they run one after the other by default.
With `parallelTools` above 1, up to that many run at once (on tasks of the chat plugin, so cancelling the ask or
disposing chat cancels and awaits all of them). Their results go back to the model in the order it made the
calls. Repeats are counted in that order before any call runs, so loop detection is unchanged. `onStep` hears
the steps one at a time, in the order they start and end. Calls that may ask the user (writes, exec) are asked
at the same time too; front-ends answer them one by one.

A tool's own token spending (`ToolResult.usage`, a sub-agent's) is added to the turn's `usage` and counts
toward `maxTokens`. While a tool runs, what it reports (`ToolContext.progress`) reaches the asker: a sub-agent's
text through `Ask.onAgentDelta`, its tool calls through `Ask.onStep` as nested steps (`Step.parentCallId`,
`Step.agent`). Nested steps are not emitted on `Chat.STEP`: the sub-agent's own turn emits them.

## Delegated asks (sub-agents)

An `Ask` with a `Chat.Delegate` is a sub-agent doing a task for another turn (the `agents` plugin of
`plugins/agents` makes them). It runs the same loop in its own conversation, which is saved like any other,
with these differences:

| Field | Effect |
| --- | --- |
| `origin` | The conversation the user talks to: its tool calls are approved there (`Tools.Approval.agent` names the sub-agent) |
| `agent` | Its name, on its `turn` span, its approvals and `Chat.Turn.delegate` |
| `systemPrompt` | Replaces `chat.systemPrompt` (null keeps it); contributed sections are still added |
| `tools` | The only tools offered; a call to any other is refused with an error result (null = all) |
| `maxSteps`, `maxTokens` | Its own limits (0 = the config's) |
| `parentSpanId` | Its `turn` span nests under this span, in `Ask.traceId`'s trace |

It is never put to `Chat.REVIEW` (the answer of the turn that asked is reviewed) and never compacts its history.

## Review

When the model answers without calling a tool, `chat` puts the answer to the `Chat.REVIEW` hook (the whole turn,
the question and the attempt number). Nobody answering accepts it, so with no reviewer, or nothing to judge the
answer against, the loop is unchanged. Every verdict goes to the asker's `Ask.onReview` (the `ask` command prints rejections as `[review: reason]`; it is never mixed into the answer's text), and a rejection is sent back
to the model as a user message, and the loop goes on. That happens at most `maxReviews` times (default 1; `0`
turns reviews off); after that the turn stops with `rejected`. A reviewer that throws counts as no verdict, so a
broken reviewer never blocks an answer; cancelling the ask during a review cancels it as usual. The reviewer's
tokens count in the turn's `usage`, and `Reply.review` holds the reason of the last verdict. Each verdict is also
emitted as `Chat.REVIEWED`. `plugins/verify` provides a reviewer that judges against the acceptance criteria of
the conversation's plan.

## The context window

Every request stays under `contextTokens` estimated tokens (default 32000; `0` turns the limit off and only
`maxHistory` applies). Set it below the model's context length, leaving room for the answer (`llm.maxTokens`).

- **Estimate.** About four characters per token plus 4 per message, tool definitions included. The estimate is
  scaled by a ratio learned from the prompt tokens the provider reports (kept between 0.5 and 3, learned again
  when chat restarts, for example after a model switch).
- **What is always sent.** The system message (prompt, contributed sections, summary) and the whole current
  turn, even when they alone exceed the budget. The earlier turns that still fit come in between, newest
  first. History is cut by whole turns (a user message and everything up to the next one), so a tool call is
  never sent without its results and history always starts with a user message. `maxHistory` still caps the
  number of messages sent.
- **Compaction** (`compact`, default true). When earlier turns no longer fit, the model summarises them, with
  the previous summary, in one extra call before the turn. The summary is kept with the conversation
  (`Conversation.summary()`) and sent in the system message under `## Summary of the earlier conversation`;
  the summarised messages are removed from the history. It compacts down to half the budget, so the next asks
  do not each pay for a summary. Its tokens count in the turn's `usage` (and `maxTokens`). If the summary call
  fails, the old turns are only left out of the request; a cancelled ask is cancelled as usual and changes
  nothing. `maxHistory` alone never triggers a summary.

## Context sections

Other plugins add sections to the system prompt with `Chat.contribute(ctx, name, order, query -> text)`. `chat`
collects them once per ask, sorts them by `order` then `name`, and sends them after `systemPrompt` as
`## name` + text. A section returning null or blank is left out for that ask, one that throws is skipped, and
all of a plugin's sections disappear when it is disposed. Chat does not require the contributors.

## Tracing

Every turn is traced on `Trace.SPAN` (`sdk`, `panic.trace`), whether it is done, fails or is cancelled. Nobody has
to listen (the `tracing` plugin of `plugins/trace` stores them), and a listener that fails never affects the turn.
The trace id is `Ask.traceId` when the asker gives one (web does, to tell the UI at once), otherwise a new one;
`Reply.traceId` returns it. Each span ends (and is emitted) before its parent:

| Span | Parent | Attributes |
| --- | --- | --- |
| `turn` | none (root); a sub-agent's under the `tool` span that runs it | `conversationId`, `question` (first 200 chars), `questionHash` (`Trace.QUESTION_HASH`, from `Trace.questionHash`), `model`, `stop`, `steps`, `toolCalls`, `promptTokens`, `completionTokens` (whole turn), `saved`; `agent`, `origin` for a sub-agent |
| `llm` | `turn` | `step`, `messages`, `model`, `finishReason`, `promptTokens`, `completionTokens`, `toolCalls`, `firstTokenMicros` (first text or tool-call piece) |
| `tool` | `turn` | `tool`, `callId`, `approvalMicros` (until the tool started), `outputChars`, `truncated`, `refused` (loop detection, or a tool a sub-agent may not use), `timedOut`, `promptTokens` / `completionTokens` (a tool's own spending). An error result is an `ERROR` span |
| `review` | `turn` | `attempt`, `verdict` (`pass` / `fail`), `pass`, `reason`, `promptTokens`, `completionTokens`. Only when a reviewer judged (or failed) |
| `compaction` | `turn` | `messages` (summarised), `kept`, `applied`, `model`, tokens. `ERROR` when the model gave no summary |

A span ends `CANCELLED` when the turn was cancelled, `ERROR` when its work threw.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Conversations.KEY` | service `Conversations` | `get(id)` returns the history of a session (created on first use); `list()` → `Info(id, title, updatedAt)`, newest first; `delete(id)` → whether anything was deleted |
| `Conversations.LIST`, `Conversations.HISTORY`, `Conversations.REMOVE` | hooks | What front-ends ask instead of requiring the service: `ListQuery` → `Listing(conversations)`, `HistoryQuery(id)` → `History(messages)`, `RemoveQuery(id)` → `Removal(deleted)`. Answered by the store while it runs; no answer means no store |
| `Conversations.DELETED` | event `Deleted(id)` | A conversation was deleted (one event per conversation, sub-agents' included) |
| `Chat.ASK` | hook `Ask(conversationId, text, signal, onDelta, onStep?, onReview?, onToolDelta?, traceId?, onAgentDelta?, delegate?) → Reply(text, model, finishReason, usage, stop, steps, toolCalls, review, traceId)` | Ask the bot. Use `Chat.ask(ctx, ask)`: it is empty when no LLM is connected and rethrows the handler's own exception. `onStep` hears every tool call when it starts (`result == null`) and when it ends, a sub-agent's too (`Step.nested()`); `onReview` hears every `Reviewed` verdict; `onToolDelta` hears the pieces of a tool call while the model writes it (`ToolCallDelta`, before its step starts); `onAgentDelta` hears a sub-agent's text (`AgentDelta(parentCallId, agent, text)`). `delegate` makes it a [delegated ask](#delegated-asks-sub-agents). Callbacks run in the order things happen, one at a time |
| `Chat.REVIEW` | hook `Review(conversationId, question, turn, answer, attempt, signal) → Verdict(pass, reason, usage)` | Judges a final answer. No answer accepts it; a rejection sends `reason` back to the model |
| `Chat.REVIEWED` | event `Reviewed(conversationId, attempt, verdict)` | Emitted after each verdict (auditing...) |
| `Chat.TURN` | event `Turn(conversationId, question, reply, delegate?)` | Emitted after every completed exchange (logging, memory, cost tracking...) |
| `Chat.STEP` | event `Step(conversationId, call, result, parentCallId?, agent?)` | Emitted when a tool call starts and when it ends (auditing...) |
| `Trace.SPAN` (emitted) | event `Span` | The turn's spans, see [Tracing](#tracing). `Trace.questionHash(question)` is the digest on the root |
| `Chat.CONTEXT` | hook `ContextQuery(conversationId) → Section(name, order, text)`, collected | Sections of the system prompt (environment, tool hints, memory...). Register with `Chat.contribute` |

## Commands

`ask <text...>` (only while an LLM is connected; front-ends send plain text here). It prints each tool call as
`[tool name args]` and the failed ones as `[tool name: error: ...]`; a sub-agent's calls are indented and named
(`  [explore: tool name args]`). `reset` clears the session's conversation. `chats [count]` lists the
conversations (default 20; `*` marks the session's own). `forget <id>` deletes one with its sub-agents'
conversations.
