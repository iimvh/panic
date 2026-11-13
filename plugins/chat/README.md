# plugins/chat

Conversation history and the chat bot, an agent loop that calls tools.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `conversations` | `ConversationsConfig { dir = ".panic/conversations", persist = true }` | none | service `Conversations.KEY` (on disk, or in memory with `persist: false`); command `reset` |
| `chat` | `ChatConfig { systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats, contextTokens, compact, maxReviews }` | `Llm.KEY`, `Conversations.KEY` | hook `Chat.ASK`; events `Chat.TURN`, `Chat.STEP`, `Chat.REVIEWED`; command `ask`. Collects hook `Chat.CONTEXT`, asks hook `Chat.REVIEW` |

`conversations` does not depend on the LLM, so history survives a provider or model switch (which restarts
`chat`).

## Storage

Each conversation is a JSON lines file in `dir` (resolved against the app's working directory), named after the
conversation id (`repl.jsonl`), or `~` + the id in base64url when the id is not a short `[A-Za-z0-9_-]` name. A
first `{"summary": ...}` line holds the compaction summary; every other line is a message
(`{"role", "content", "toolCalls"?, "toolCallId"?}`).

- **Loaded on first use.** A conversation is read the first time it is asked for after a start.
- **Written through.** An exchange is appended in one write, under the conversation's lock, so concurrent
  appends never interleave and a crash leaves at most a torn last line. A compaction rewrites the file through
  a temporary file and an atomic rename. `reset` deletes the file.
- **Damage is skipped, not fatal.** A line that is not a valid message is skipped with a warning, and so is a
  turn whose tool calls lack their results (a provider would reject it). The file is then rewritten clean.
- **Failures are warnings.** If the directory or a file cannot be written, the conversation goes on in memory
  and a warning is printed.
- **One process per directory.** Two apps sharing `dir` would interleave their writes.

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
come back as `error: ...` results. The timeout starts when the tool starts, so the time the user takes to
approve a call does not count (that wait has its own limit, `permissions.askTimeoutSeconds`). Tool output is
cut at `maxToolOutput` characters (default 16000). When a turn stops early, the calls that were not run get an
error result, so the saved history stays valid. The whole turn (user, assistant tool calls, tool results,
answer) is appended at once. A cancelled turn is not saved. `tools: false` turns tools off for models that do
not support them.

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

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Conversations.KEY` | service `Conversations` | `get(id)` returns the history of a session (created on first use) |
| `Chat.ASK` | hook `Ask(conversationId, text, signal, onDelta, onStep?, onReview?) → Reply(text, model, finishReason, usage, stop, steps, toolCalls, review)` | Ask the bot. Use `Chat.ask(ctx, ask)`: it is empty when no LLM is connected and rethrows the handler's own exception. `onStep` hears every tool call when it starts (`result == null`) and when it ends; `onReview` hears every `Reviewed` verdict. Callbacks run in the order things happen |
| `Chat.REVIEW` | hook `Review(conversationId, question, turn, answer, attempt, signal) → Verdict(pass, reason, usage)` | Judges a final answer. No answer accepts it; a rejection sends `reason` back to the model |
| `Chat.REVIEWED` | event `Reviewed(conversationId, attempt, verdict)` | Emitted after each verdict (auditing...) |
| `Chat.TURN` | event `Turn(conversationId, question, reply)` | Emitted after every completed exchange (logging, memory, cost tracking...) |
| `Chat.STEP` | event `Step(conversationId, call, result)` | Emitted when a tool call starts and when it ends (auditing...) |
| `Chat.CONTEXT` | hook `ContextQuery(conversationId) → Section(name, order, text)`, collected | Sections of the system prompt (environment, tool hints, memory...). Register with `Chat.contribute` |

## Commands

`ask <text...>` (only while an LLM is connected; front-ends send plain text here). It prints each tool call as
`[tool name args]` and the failed ones as `[tool name: error: ...]`. `reset`.
