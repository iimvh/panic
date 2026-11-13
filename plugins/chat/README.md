# plugins/chat

Conversation history and the chat bot.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `conversations` | none | none | service `Conversations.KEY` (in memory); command `reset` |
| `chat` | `ChatConfig { systemPrompt, maxHistory }` | `Llm.KEY`, `Conversations.KEY` | hook `Chat.ASK`; event `Chat.TURN`; command `ask` |

`conversations` does not depend on the LLM, so history survives a provider or model switch (which restarts
`chat`).

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Conversations.KEY` | service `Conversations` | `get(id)` returns the history of a session (created on first use) |
| `Chat.ASK` | hook `Ask(conversationId, text, signal, onDelta) → Reply` | Ask the bot. Use `Chat.ask(ctx, ask)`: it is empty when no LLM is connected and rethrows the handler's own exception |
| `Chat.TURN` | event `Turn(conversationId, question, reply)` | Emitted after every completed exchange (logging, memory, cost tracking...) |

## Commands

`ask <text...>` (only while an LLM is connected; front-ends send plain text here), `reset`.
