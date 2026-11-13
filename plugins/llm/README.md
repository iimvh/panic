# plugins/llm

LLM providers and the model the app chats with.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `openrouter` | `ProviderConfig { apiKey, baseUrl?, maxRetries? }` | none | service `Providers.key("openrouter")` while it has a key; hook `Providers.STATUS` |
| `mistral` | `ProviderConfig { apiKey, baseUrl?, maxRetries? }` | none | service `Providers.key("mistral")` while it has a key; hook `Providers.STATUS` |
| `llm` | `LlmConfig { provider, model, temperature?, maxTokens? }` | none (its child `active:<provider>` requires that provider) | service `Llm.KEY`; hooks `Llm.CURRENT`, `Llm.CONFIG`; commands `status`, `models`, `check` |
| `settings` | none | `Configs.KEY` | commands `provider`, `model` (they update the `llm` config) |

## Environment without `panic.json`

`LlmPlugins.environment` (`PluginSet.environment`) tells the app which variables fill which sections when there is
no file: `OPENROUTER_API_KEY` / `MISTRAL_API_KEY` set `<provider>.apiKey`, the first provider with a key becomes
`llm.provider`, and `PANIC_MODEL` is `llm.model`. The app only supplies the lookup, so it knows no provider name.

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Providers.key(id)` | service `Provider` | A connected provider: `chat()`, `models(signal)`, `checkKey(signal)` |
| `Providers.STATUS` | hook `Query → Status(id, connected)` | Every installed provider, connected or not (`collect`) |
| `Llm.KEY` | service `Llm` | The selected provider plus model and default options. Require it to depend on the LLM |
| `Llm.CURRENT` | hook `Query → Llm` | Look at the current Llm without tying your lifetime to it (front-ends) |
| `Llm.CONFIG` | hook `Query → LlmConfig` | The selected provider and model, even when not connected |

## Tool calling

The API carries tools; it does not run them (that is the caller's job).

| Type | Meaning |
| --- | --- |
| `ToolSpec(name, description, parameters)` | A tool offered to the model; `parameters` is a JSON Schema object |
| `ChatRequest.withTools(tools, toolChoice)` | Offer tools; `ToolChoice.AUTO` / `NONE` / `REQUIRED` / `named(name)`, null = provider default |
| `Completion.toolCalls()` | `ToolCall(id, name, arguments)` the model asked for (`finishReason` is `tool_calls`); `arguments` is raw JSON text, not validated |
| `Completion.message()` | The answer as the assistant `Message` to append to the history |
| `Message.tool(callId, content)` | The result of one call, sent back with role `tool` |
| `ChatModel.stream(request, signal, onDelta, onToolDelta)` | Also hands each piece of a tool call to `onToolDelta` as it streams: `ToolCallDelta(index, id?, name?, arguments)`, `arguments` being only the new text. Progress only (the calls still come in the `Completion`); the default reports nothing |

Streams hand only text to `onDelta`; tool calls are rebuilt from their fragments and returned in the
`Completion`.

## Retries

Provider calls (chat, models, key check) are retried on transient failures: unreachable, 408, 429, 500,
502, 503, 504. The wait grows exponentially with jitter (500 ms base, 8 s cap); a longer `Retry-After` in
seconds is honoured up to the cap. `maxRetries` sets the number of retries (default 2, 0 = never).
Cancelling the call ends a wait at once. A stream is retried only until the provider accepts it, never
after text has been handed out.

## Commands

`status`, `models [filter]`, `check` (the last two only while connected); `provider [id]`, `model [id]`.

## Adding a provider

Write a `ConfigPlugin<ProviderConfig>` that answers `Providers.STATUS` and, when it has a key, provides
`Providers.key("<id>")`. Extend `OpenAiCompatProvider` for OpenAI-compatible APIs (`Mistral.java` is about
50 lines), then list it in `LlmPlugins`. The `settings` command picks it up through `Providers.STATUS`.
