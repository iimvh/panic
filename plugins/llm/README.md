# plugins/llm

LLM providers and the model the app chats with.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `openrouter` | `ProviderConfig { apiKey, baseUrl?, maxRetries? }` | none | service `Providers.key("openrouter")` while it has a key; hook `Providers.STATUS` |
| `custom-providers` | `CustomProvidersConfig { providers: { <id>: { baseUrl, apiKey?, maxRetries?, enabled?, streamUsage? } } }` | none | per entry: service `Providers.key(<id>)` while connected; hook `Providers.STATUS` |
| `llm` | `LlmConfig { provider, model, temperature?, maxTokens?, reasoningEffort?, contextWindow? }` | none (its child `active:<provider>` requires that provider) | service `Llm.KEY`; hooks `Llm.CURRENT`, `Llm.CONFIG`; commands `status`, `models`, `check` |
| `settings` | none | `Configs.KEY` | commands `provider`, `model` (they update the `llm` config) |

## Context window

`Llm.inputWindow()` is how many tokens one request may hold: the model's context length less room for the reply,
or 0 when unknown. `chat` sizes its context budget from it (see the chat README). The reply's room is `maxTokens`
when set; otherwise the model's own reply limit as its provider lists it, up to 16384 (`Llm.REPLY_ROOM`); otherwise
8192 (`Llm.DEFAULT_REPLY_ROOM`). Leaving `maxTokens` out sends no `max_tokens`: the provider's default applies.

The context length is `contextWindow` when given; otherwise the provider's model list says it. The
`active:<provider>` branch looks the model up once, in the background, in that list, and keeps what it says
(`Llm.info()`, a `ModelInfo`): `context_length`, the reply limit (`top_provider.max_completion_tokens` on
OpenRouter, or `max_completion_tokens` / `max_output_tokens`) and the parameters it takes (`supported_parameters`).
So the length is 0 until that answers, and stays 0 when the list does not say it (many OpenAI-compatible hosts list
ids only: give `contextWindow` there). `/model` and `/provider` drop `contextWindow`, since it describes the old
model. `status` shows the limits in use and where they come from.

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
- `streamUsage` (default true) asks for the token usage of a streamed reply (`stream_options.include_usage`).
  Without it most servers send no usage in a stream, so the turn's token counts, its `chat.maxTokens` budget and
  the context estimate's calibration all see zero. Set it to `false` only for a server that refuses the option.
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
| `Llm.KEY` | service `Llm` | The selected provider plus model and default options; `info()` is what the provider lists for the model (null while unknown). Require it to depend on the LLM |
| `Llm.CURRENT` | hook `Query → Llm` | Look at the current Llm without tying your lifetime to it (front-ends) |
| `Llm.CONFIG` | hook `Query → LlmConfig` | The selected provider and model, even when not connected |

## Tool calling

The API carries tools; it does not run them (that is the caller's job).

| Type | Meaning |
| --- | --- |
| `ToolSpec(name, description, parameters)` | A tool offered to the model; `parameters` is a JSON Schema object |
| `ChatRequest.withTools(tools, toolChoice)` | Offer tools; `ToolChoice.AUTO` / `NONE` / `REQUIRED` / `named(name)`, null = provider default |
| `Completion.toolCalls()` | `ToolCall(id, name, arguments)` the model asked for (`finishReason` is `tool_calls`); `arguments` is raw JSON text, not validated |
| `Completion.truncated()` | True when `finishReason` is `length` (or `max_tokens`): the reply was cut at the output limit, so its text stops mid-way and its last tool call is incomplete |
| `Completion.message()` | The answer as the assistant `Message` to append to the history |
| `Message.tool(callId, content)` | The result of one call, sent back with role `tool` |
| `ChatModel.stream(request, signal, onDelta, onToolDelta)` | Also hands each piece of a tool call to `onToolDelta` as it streams: `ToolCallDelta(index, id?, name?, arguments)`, `arguments` being only the new text. Progress only (the calls still come in the `Completion`); the default reports nothing |

Streams hand only text to `onDelta`; tool calls are rebuilt from their fragments and returned in the
`Completion`.

## Reasoning

What a reasoning model thinks before it answers is kept apart from its answer, so users can see it.

| Type | Meaning |
| --- | --- |
| `ChatModel.stream(request, signal, onDelta, onToolDelta, onReasoning)` | Also hands each piece of the reasoning to `onReasoning` as it streams; the default reports nothing. A wrapper must forward it (`Llm`'s does) |
| `Completion.reasoning()` | The whole reasoning, empty when the provider shows none. Goes with `message()` (`Message.reasoning()`, assistant messages only) so the conversation keeps it for the user; no provider sends it back to the model |
| `Usage.reasoningTokens()` | The part of `completionTokens` spent thinking (`completion_tokens_details.reasoning_tokens`), also when the text is hidden |
| `Usage.cachedTokens()` | The part of `promptTokens` served from the provider's prompt cache (`prompt_tokens_details.cached_tokens`, or DeepSeek's `prompt_cache_hit_tokens`); 0 when the provider reports none |
| `Content.reasoning(message)` | Reads `reasoning` (OpenRouter, Ollama), else `reasoning_content` (DeepSeek, vLLM, LM Studio), plus `thinking` content parts (Mistral) |

**Turning it on.** `llm.reasoningEffort` (`low`, `medium`, `high`, or any level the provider knows; left out = none)
is sent as `reasoning_effort` with every request that does not choose its own. Some models always think; hybrid ones
(DeepSeek V4 on River, for one) think only when it is set, and stream it as `reasoning_content`. A request chooses its
own with `ChatRequest.withReasoningEffort(level)`, or `ChatRequest.NO_REASONING_EFFORT` to send none whatever the
default: `chat`'s compaction does, and so does the `review` reviewer unless `review.reasoningEffort` is set, because
thinking tokens count against `max_tokens` and those calls have small limits. Raise `llm.maxTokens` when you turn it
on: a reply cut at the limit while thinking has nothing to show (see `Completion.truncated()`). The configured level is
not sent to a model whose provider lists its parameters without `reasoning` (or `reasoning_effort`); a request's own
level still is, and so is the configured one while nobody knows (the list says nothing, or the lookup has not
answered). So a host that lists no parameters and a model that refuses `reasoning_effort` still need `effort off`.

A model that writes `<think>` tags into its text (old Ollama versions) is not split; its thinking stays in the
answer.

## Retries

Provider calls (chat, models, key check) are retried on transient failures: unreachable, 408, 429, 500,
502, 503, 504. The wait grows exponentially with jitter (500 ms base, 8 s cap); a longer `Retry-After` in
seconds is honoured up to the cap. `maxRetries` sets the number of retries (default 2, 0 = never).
Cancelling the call ends a wait at once. A stream is retried only until the provider accepts it, never
after text has been handed out.

## Commands

`status`, `models [filter]`, `check` (the last two only while connected); `provider [id]`, `model [id]`,
`effort [level]` (`low`, `medium`, `high`, ... or `off`; with no level it shows the current one), all three in
`settings`, which updates the `llm` section.

## Adding a provider

An OpenAI-compatible API is a `custom-providers` entry, with nothing to code. Write a class only when the API
behaves differently (its own key check, pricing, request shape): a `ConfigPlugin<ProviderConfig>` that answers
`Providers.STATUS` and, when it has a key, provides `Providers.key("<id>")`. Extend `OpenAiCompatProvider` (see
`OpenRouter.java`), then list it in `LlmPlugins`. The `settings` command picks it up through `Providers.STATUS`.
