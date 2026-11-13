# plugins/chat

Conversation history and the chat bot, an agent loop that calls tools.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `conversations` | none | none | service `Conversations.KEY` (in memory); command `reset` |
| `chat` | `ChatConfig { systemPrompt, maxHistory, tools, maxSteps, maxTokens, toolTimeoutSeconds, maxToolOutput, maxRepeats }` | `Llm.KEY`, `Conversations.KEY` | hook `Chat.ASK`; events `Chat.TURN`, `Chat.STEP`; command `ask` |

`conversations` does not depend on the LLM, so history survives a provider or model switch (which restarts
`chat`).

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

Every failure is an observation for the model, not a crash of the turn. Unknown tools, bad arguments, tool
errors, calls denied by the permission policy or the user, and timeouts (`toolTimeoutSeconds`, default 60) all
come back as `error: ...` results. The timeout starts when the tool starts, so the time the user takes to
approve a call does not count (that wait has its own limit, `permissions.askTimeoutSeconds`). Tool output is
cut at `maxToolOutput` characters (default 16000). When a turn stops early, the calls that were not run get an
error result, so the saved history stays valid. The whole turn (user, assistant tool calls, tool results,
answer) is appended at once. A cancelled turn is not saved. `tools: false` turns tools off for models that do
not support them.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Conversations.KEY` | service `Conversations` | `get(id)` returns the history of a session (created on first use) |
| `Chat.ASK` | hook `Ask(conversationId, text, signal, onDelta, onStep?) → Reply(text, model, finishReason, usage, stop, steps, toolCalls)` | Ask the bot. Use `Chat.ask(ctx, ask)`: it is empty when no LLM is connected and rethrows the handler's own exception. `onStep` hears every tool call when it starts (`result == null`) and when it ends |
| `Chat.TURN` | event `Turn(conversationId, question, reply)` | Emitted after every completed exchange (logging, memory, cost tracking...) |
| `Chat.STEP` | event `Step(conversationId, call, result)` | Emitted when a tool call starts and when it ends (auditing...) |

## Commands

`ask <text...>` (only while an LLM is connected; front-ends send plain text here). It prints each tool call as
`[tool name args]` and the failed ones as `[tool name: error: ...]`. `reset`.
