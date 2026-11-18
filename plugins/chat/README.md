# plugins/chat

Conversation history and the chat bot, an agent loop that calls tools.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `conversations` | `ConversationsConfig { dir = ".panic/conversations", persist = true }` | none | service `Conversations.KEY` (on disk, or in memory with `persist: false`); event `Conversations.DELETED`; hooks `Conversations.LIST`, `HISTORY`, `REMOVE`; commands `reset`, `chats`, `forget` |
| `chat` | `ChatConfig { systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats, contextTokens, compact, maxReviews, parallelTools, clearToolResultsAt, keepToolResults }` | `Llm.KEY`, `Conversations.KEY` | hook `Chat.ASK`; events `Chat.TURN`, `Chat.STEP`, `Chat.REVIEWED`, `Trace.SPAN`; command `ask`. Collects hooks `Chat.CONTEXT` and `Chat.STOP`, asks hook `Chat.REVIEW` |

`conversations` does not depend on the LLM, so history survives a provider or model switch (which restarts
`chat`).

## Storage

Each conversation is a JSON lines file in `dir` (resolved against the app's working directory), named after the
conversation id (`repl.jsonl`), or `~` + the id in base64url when the id is not a short `[A-Za-z0-9_-]` name.
Header lines come first: `{"parent": ...}` for a conversation that works for another (a sub-agent's, set by
`chat` from `Chat.Delegate.origin`), `{"title": ...}` (the first question, written with it, so it stays the title
after a compaction removes that question), and `{"summary": ...}` for the compaction summary. Every other line is a
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
  its title header. It leaves out empty conversations and those with a `parent`, newest first. `Info.title` is
  the first line of the title (`Conversation.title()`, the first question ever asked; a file written before titles
  were kept takes its first remaining question), at most 80 characters. Clearing a conversation clears its title.
- **Deleting** (`delete(id)`) removes the conversation and, recursively, every conversation whose `parent` it
  is (a sub-agent's), from memory and disk. It emits `Conversations.DELETED` once for each, so `todo` and
  `tracing` drop their data for it. Deleting a conversation while a turn is running in it is the caller's
  business: `web` refuses it.

## The agent loop

On each ask, `chat` sends the history and the tools registered right now (`Tools.specs`, from
`plugins/tools`), less those a `Tools.GATE` keeps out of the conversation the user talks to (plan mode of
`plugins/planner`). The list is read again before every model call, so a mode that ends mid-turn offers the tools
on the next call. It does not require them: with no tools it is a plain chat bot. When the model asks for tools,
`chat` runs each call (`Tools.call`), appends the results as `tool` messages and calls the model again. It repeats
until the model answers or a limit stops it:

| Stop (`Reply.stop`) | When |
| --- | --- |
| `done` | The model answered without calling a tool |
| `max_steps` | `maxSteps` model calls were made (default 25) |
| `budget` | The tokens of the turn reached `maxTokens` (default 400000; `0` = no budget) |
| `rejected` | The reviewer still rejected the answer after `maxReviews` retries (or no step was left for one). `Reply.review` holds its reason |
| `truncated` | The model's reply was cut at the output limit (`llm.maxTokens`) `Chat.TRUNCATIONS_IN_A_ROW` (3) times in a row |

**Only hard limits stop a turn.** This follows proven harnesses:

- Anthropic's "Building effective agents" recommends stopping conditions such as a maximum number of iterations.
- The Claude Agent SDK stops at `max_turns` with `error_max_turns`, and the session can then be resumed.
- Cursor stops after its tool call limit and offers "Continue".
- The OpenAI Agents SDK raises `MaxTurnsExceeded` at `max_turns`, with a handler for a final output.

A repeated call is feedback, not a stop. The same call (name and arguments, key order ignored) made more than
`maxRepeats` times in a row (default 2) is refused with `error: the same call was made N times in a row; change the
arguments or answer with what you have`, and the turn goes on, so a model that changes course recovers.

**A turn stopped by a limit closes with a note for the user.** When `max_steps` or `budget` stops a turn before
the model answered, or `truncated` stops it, `Reply.text` is a note written by the harness, not the model:
`Stopped after 25 steps (max_steps) before finishing. Tools run this turn: read_file ×7, write_file ×2. Say
"continue" to go on from here.` It is also streamed through `onDelta`. It is never saved in the conversation, so
the model never reads the harness's words as its own. The work done is saved, so asking again (the web UI's
**Continue** button, or typing "continue") goes on from there. A sub-agent gets no note: the `agent` tool reports
its stop to its caller.

**A reply cut at the output limit is not an answer.** When the provider ends a reply with `finish_reason: length`
(`Completion.truncated()`), the model was stopped mid-way by `llm.maxTokens`, typically while writing a large tool
call (a whole file). Its text stops mid-sentence and its last call is incomplete, so treating it as either an answer
or a call would be wrong: it is not put to `Chat.STOP` or `Chat.REVIEW` (that would spend a review on work the model
never finished), and none of its calls run. Its text is kept, without the calls (a call with no result would be
refused by providers), followed by a user message from the harness (`Chat.TRUNCATED_FEEDBACK_PREFIX`) naming the
calls that were lost and asking for the work in smaller pieces; the loop goes on. It counts as a step. After
`Chat.TRUNCATIONS_IN_A_ROW` cuts in a row (any reply that finishes resets the count) the turn stops with
`truncated` and a closing note telling the user to raise `llm.maxTokens`, and the web UI offers **Continue**.
`Chat.fromHarness` tells such messages (and review feedback) from the user's words: summaries do not keep them
verbatim and the web history does not show them.

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
calls. Repeats are counted in that order before any call runs, so refusing repeats works the same. `onStep` hears
the steps one at a time, in the order they start and end. Calls that may ask the user (writes, exec) are asked
at the same time too; front-ends answer them one by one.

A tool's own token spending (`ToolResult.usage`, a sub-agent's) is added to the turn's `usage` and counts
toward `maxTokens`. While a tool runs, what it reports (`ToolContext.progress`) reaches the asker: a sub-agent's
text and reasoning through `Ask.onAgentDelta` (`AgentDelta.reasoning`), its tool calls through `Ask.onStep` as nested steps (`Step.parentCallId`,
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

It is never put to `Chat.STOP` or `Chat.REVIEW` (the answer of the turn that asked is) and never compacts its
history.

## Review

When the model answers without calling a tool, `chat` puts the answer to the `Chat.REVIEW` hook (the whole turn,
the question and the attempt number). Nobody answering accepts it, so with no reviewer, or nothing to judge the
answer against, the loop is unchanged. Every verdict goes to the asker's `Ask.onReview` (the `ask` command prints rejections as `[review: reason]`; it is never mixed into the answer's text), and a rejection is sent back
to the model as a user message, and the loop goes on. That happens at most `maxReviews` times (default 1; `0`
turns reviews off); after that the turn stops with `rejected`. A reviewer that throws counts as no verdict, so a
broken reviewer never blocks an answer, but the failure shows: its `review` span ends as ERROR with the message,
and `onReview` / `Chat.REVIEWED` get a `Reviewed` with `verdict` null and `error` set (`ask` prints
`[review failed: ...]`); cancelling the ask during a review cancels it as usual. The reviewer's
tokens count in the turn's `usage`, and `Reply.review` holds the reason of the last verdict. Each verdict is also
emitted as `Chat.REVIEWED`. `plugins/verify` provides a reviewer that judges against the acceptance criteria of
the conversation's plan.

**Stop checks** (after Claude Code's Stop hooks) come first: the answer is put to the `Chat.STOP` hook, which is
*collected*, so every check runs. Register one with `Chat.stopCheck(ctx, name, review -> StopCheck)`: it returns
`StopCheck.passed(name, finding)`, `blocked(name, reason)`, `failed(name, error)` or null, and a check that throws
becomes `failed` instead of hiding the others' answers. If any blocks, the answer is rejected with `stop hook
<name>: <reason>` (all blocks joined): it is reported like a rejection and sent back to the model within the same
`maxReviews`, and `Chat.REVIEW` is not asked about that answer. Otherwise the findings of the checks that passed
reach the reviewer as `Review.evidence`. A check that failed is reported as a failed review (`stop hook <name>:
<error>`) and blocks nothing. `plugins/verify`'s `stop-hooks` runs configured commands this way.

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
  nothing. `maxHistory` alone never triggers a summary. The summary call never asks the model to think
  (`ChatRequest.NO_REASONING_EFFORT`), whatever `llm.reasoningEffort` says: its limit is small (at most 2048 tokens).
  - **What the summary keeps.** The prompt (`SUMMARY_PROMPT`) asks for the sections of Claude Code's compaction:
    the primary request, every user message word for word, key facts, work done and current state, pending tasks
    and the next step. Then `chat` checks it: each user message of the summarised turns that the summary does not
    quote is added under `User messages (word for word):` (each cut at 2000 characters; a reviewer's feedback is
    not a user message). So a long task never loses what the user asked.
  - **Never from nothing.** When not even the last earlier turn fits half the budget (one turn with many tool
    calls), its question and final answer are kept as a short turn after the summary (`kept` 0 is no longer
    possible while that turn has an answer); its tool calls are covered by the summary.
- **Tool result clearing** (after Anthropic's context editing, `clear_tool_uses`). Once a request is estimated
  over `clearToolResultsAt` tokens (default half of `contextTokens`; `0` = never), every tool result but the
  newest `keepToolResults` (default 3) is sent as `[tool result cleared to save context (N chars); call the tool
  again if you need it]`. This covers the current turn too, where a long task piles up file contents and command
  output that every model call would otherwise send again. Only the request changes: the conversation keeps the
  results, and a result shorter than the placeholder is left as is. The `llm` span records `clearedToolResults`.

## Slash prompts

A user's question `/name args` (not a sub-agent's task) that some plugin answers on `Prompts.EXPAND` (`sdk`; the
`skills` plugin does for its skills) is expanded when the turn starts. The line is kept as the user wrote it, and
the prompt follows it:

- with `Prompt.tool` (a skill): an assistant message calling that tool (id `pre_<8 hex>`, arguments
  `Prompt.toolArguments`) and its result, `Prompt.text` cut at `maxToolOutput`. `onStep` and `Chat.STEP` report the
  call as running and then done, as for a call of the model. The history then looks as if the model had loaded
  the skill itself, and compaction summarises it as a tool result instead of keeping it as the user's words.
- without: a second user message with `Prompt.text`.

The `turn` span gets `prompt` (the name). Nobody answers (the plugin is off, the skill was removed): the line goes
as is. The call stays in the history afterwards, even when its tool is gone later, as with a removed MCP server.

## Context sections

Other plugins add sections to the system prompt with `Chat.contribute(ctx, name, order, query -> text)`. `chat`
collects them once per ask, sorts them by `order` then `name`, and sends them after `systemPrompt` as
`## name` + text. A sub-agent's ask collects them too: `ContextQuery.origin` tells a contributor which
conversation (and so which project) the sub-agent works for. A section returning null or blank is left out for that ask, one that throws is skipped, and
all of a plugin's sections disappear when it is disposed. Chat does not require the contributors.

## Tracing

Every turn is traced on `Trace.SPAN` (`sdk`, `panic.trace`), whether it is done, fails or is cancelled. Nobody has
to listen (the `tracing` plugin of `plugins/trace` stores them), and a listener that fails never affects the turn.
The trace id is `Ask.traceId` when the asker gives one (web does, to tell the UI at once), otherwise a new one;
`Reply.traceId` returns it. Each span ends (and is emitted) before its parent:

| Span | Parent | Attributes |
| --- | --- | --- |
| `turn` | none (root); a sub-agent's under the `tool` span that runs it | `conversationId`, `question` (first 200 chars), `prompt` (a [slash prompt](#slash-prompts)'s name), `questionHash` (`Trace.QUESTION_HASH`, from `Trace.questionHash`), `model`, `stop`, `steps`, `toolCalls`, `promptTokens`, `completionTokens` (whole turn), `saved`; `agent`, `origin` for a sub-agent |
| `llm` | `turn` | `step`, `messages`, `clearedToolResults` (when any), `model`, `finishReason`, `promptTokens`, `completionTokens`, `toolCalls`, `firstTokenMicros` (first text, reasoning or tool-call piece), and when the model thought: `firstReasoningMicros`, `reasoningChars`, `reasoningTokens` (sizes only, never the text) |
| `tool` | `turn` | `tool`, `callId`, `approvalMicros` (until the tool started), `outputChars`, `truncated`, `refused` (a repeated call, or a tool a sub-agent may not use), `timedOut`, `promptTokens` / `completionTokens` (a tool's own spending). An error result is an `ERROR` span |
| `stop` | `turn` | `attempt`, `checks`, `blocked`, `names`, `reason` (the first block's). `ERROR` when a check failed. Only when a check answered |
| `review` | `turn` | `attempt`, `verdict` (`pass` / `fail`), `pass`, `reason`, `promptTokens`, `completionTokens`. Only when a reviewer judged (or failed) |
| `compaction` | `turn` | `messages` (summarised), `kept`, `condensed` (the last turn kept as question and answer), `applied`, `model`, tokens. `ERROR` when the model gave no summary |

A span ends `CANCELLED` when the turn was cancelled, `ERROR` when its work threw.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Conversations.KEY` | service `Conversations` | `get(id)` returns the history of a session (created on first use); `list()` → `Info(id, title, updatedAt)`, newest first; `delete(id)` → whether anything was deleted |
| `Conversations.LIST`, `Conversations.HISTORY`, `Conversations.REMOVE` | hooks | What front-ends ask instead of requiring the service: `ListQuery` → `Listing(conversations)`, `HistoryQuery(id)` → `History(messages)`, `RemoveQuery(id)` → `Removal(deleted)`. Answered by the store while it runs; no answer means no store |
| `Conversations.DELETED` | event `Deleted(id)` | A conversation was deleted (one event per conversation, sub-agents' included) |
| `Chat.ASK` | hook `Ask(conversationId, text, signal, onDelta, onStep?, onReview?, onToolDelta?, traceId?, onAgentDelta?, delegate?, onReasoning?) → Reply(text, model, finishReason, usage, stop, steps, toolCalls, review, traceId)` | Ask the bot. Use `Chat.ask(ctx, ask)`: it is empty when no LLM is connected and rethrows the handler's own exception. `onStep` hears every tool call when it starts (`result == null`) and when it ends, a sub-agent's too (`Step.nested()`); `onReview` hears every `Reviewed` verdict (or reviewer failure, `Reviewed.failed()`); `onToolDelta` hears the pieces of a tool call while the model writes it (`ToolCallDelta`, before its step starts); `onAgentDelta` hears a sub-agent's text (`AgentDelta(parentCallId, agent, text, reasoning)`); `onReasoning` (set with `Ask.withReasoning`) hears what the model thinks before it writes, when the provider shows it (see the llm README). Reasoning is shown, never saved: the history keeps the answer only, and `Reply.usage().reasoningTokens()` counts it. `delegate` makes it a [delegated ask](#delegated-asks-sub-agents). Callbacks run in the order things happen, one at a time |
| `Chat.REVIEW` | hook `Review(conversationId, question, turn, answer, attempt, signal) → Verdict(pass, reason, usage)` | Judges a final answer. No answer accepts it; a rejection sends `reason` back to the model |
| `Chat.REVIEWED` | event `Reviewed(conversationId, attempt, verdict)` | Emitted after each verdict (auditing...) |
| `Chat.TURN` | event `Turn(conversationId, question, reply, delegate?)` | Emitted after every completed exchange (logging, memory, cost tracking...) |
| `Chat.STEP` | event `Step(conversationId, call, result, parentCallId?, agent?)` | Emitted when a tool call starts and when it ends (auditing...) |
| `Trace.SPAN` (emitted) | event `Span` | The turn's spans, see [Tracing](#tracing). `Trace.questionHash(question)` is the digest on the root |
| `Prompts.EXPAND` (asked, `sdk`) | hook `Query(conversationId, name, args) → Prompt(name, description, tool, toolArguments, text)` | Expands a question `/name args`, see [Slash prompts](#slash-prompts) |
| `Chat.CONTEXT` | hook `ContextQuery(conversationId, origin, agent) → Section(name, order, text)`, collected | Sections of the system prompt (environment, tool hints, memory...). Register with `Chat.contribute`. For a sub-agent, `conversationId` is its own conversation, `origin` the one the user talks to (its project) and `agent` its name (`delegated()`); for the main agent `origin == conversationId` and `agent` is null |

## Commands

`ask <text...>` (only while an LLM is connected; front-ends send plain text here). It prints each tool call as
`[tool name args]` and the failed ones as `[tool name: error: ...]`; a sub-agent's calls are indented and named
(`  [explore: tool name args]`). What the model thinks is printed as lines starting with `│ `, closed by
`[thought Ns]`; the closing line adds `(N thinking)` to the token count when the provider reports reasoning tokens.
`reset` clears the session's conversation. `chats [count]` lists the
conversations (default 20; `*` marks the session's own). `forget <id>` deletes one with its sub-agents'
conversations.
