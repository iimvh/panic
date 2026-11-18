# plugins/llm

LLM providers and the model the app chats with.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `openrouter` | `ProviderConfig { apiKey, baseUrl?, maxRetries? }` | none | service `Providers.key("openrouter")` while it has a key; hook `Providers.STATUS` |
| `custom-providers` | `CustomProvidersConfig { providers: { <id>: { baseUrl, apiKey?, maxRetries?, enabled? } } }` | none | per entry: service `Providers.key(<id>)` while connected; hook `Providers.STATUS` |
| `llm` | `LlmConfig { provider, model, temperature?, maxTokens? }` | none (its child `active:<provider>` requires that provider) | service `Llm.KEY`; hooks `Llm.CURRENT`, `Llm.CONFIG`; commands `status`, `models`, `check` |
| `settings` | none | `Configs.KEY` | commands `provider`, `model` (they update the `llm` config) |

## Environment without `panic.json`

`LlmPlugins.environment` (`PluginSet.environment`) tells the app which variables fill which sections when there is
no file: `OPENROUTER_API_KEY` sets `openrouter.apiKey` and makes OpenRouter `llm.provider`, and `PANIC_MODEL` is
`llm.model`. The app only supplies the lookup, so it knows no provider name. Custom providers are declared in
`panic.json` only.

## Custom providers

Any OpenAI-compatible API (Mistral, Ollama, Groq, vLLM, LM Studio, ...) is an entry in `custom-providers`, with no
code. The entry's id becomes the provider id, so `/provider <id>` and `llm.provider` name it.

```json
"custom-providers": {
  "providers": {
    "mistral": { "baseUrl": "https://api.mistral.ai/v1", "apiKey": "${MISTRAL_API_KEY}" },
    "ollama": { "baseUrl": "http://127.0.0.1:11434/v1" }
  }
}
```

- `baseUrl` is required: the API root, ending in `/v1` for OpenAI-style servers.
- `apiKey` left out means the server needs no key (Ollama, LM Studio): no `Authorization` header is sent.
- `apiKey` present but blank, for example an unset `${VAR}`, means a key is needed and missing: the entry is listed
  by `Providers.STATUS` as not connected and provides no service.
- `enabled: false` keeps an entry listed but not connected.
- `maxRetries` is as for `openrouter`.
- Ids are letters, digits, `_` and `-`. A bad entry fails the config instead of being skipped.
- Ids must not equal a built-in provider's (`openrouter`): both would claim `Providers.key(id)`, so such an
  entry is a config error.
- Each entry is a child plugin named after its id. A config change only restarts the entries that changed,
  appeared or went away, so editing one endpoint does not interrupt a chat running on another custom provider.
- An old `"mistral": {"apiKey": ...}` section no longer has a plugin (the app warns about the unknown section):
  move it into `custom-providers` as above.

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

An OpenAI-compatible API is a `custom-providers` entry, with nothing to code. Write a class only when the API
behaves differently (its own key check, pricing, request shape): a `ConfigPlugin<ProviderConfig>` that answers
`Providers.STATUS` and, when it has a key, provides `Providers.key("<id>")`. Extend `OpenAiCompatProvider` (see
`OpenRouter.java`), then list it in `LlmPlugins`. The `settings` command picks it up through `Providers.STATUS`.
