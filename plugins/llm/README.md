# plugins/llm

LLM providers and the model the app chats with.

## Plugins

| Name | Config (`panic.json` section) | Requires | Provides / registers |
| --- | --- | --- | --- |
| `openrouter` | `ProviderConfig { apiKey, baseUrl? }` | none | service `Providers.key("openrouter")` while it has a key; hook `Providers.STATUS` |
| `mistral` | `ProviderConfig { apiKey, baseUrl? }` | none | service `Providers.key("mistral")` while it has a key; hook `Providers.STATUS` |
| `llm` | `LlmConfig { provider, model, temperature?, maxTokens? }` | none (its child `active:<provider>` requires that provider) | service `Llm.KEY`; hooks `Llm.CURRENT`, `Llm.CONFIG`; commands `status`, `models`, `check` |
| `settings` | none | `Configs.KEY` | commands `provider`, `model` (they update the `llm` config) |

## Public keys

| Key | Type | Meaning |
| --- | --- | --- |
| `Providers.key(id)` | service `Provider` | A connected provider: `chat()`, `models(signal)`, `checkKey(signal)` |
| `Providers.STATUS` | hook `Query → Status(id, connected)` | Every installed provider, connected or not (`collect`) |
| `Llm.KEY` | service `Llm` | The selected provider plus model and default options. Require it to depend on the LLM |
| `Llm.CURRENT` | hook `Query → Llm` | Look at the current Llm without tying your lifetime to it (front-ends) |
| `Llm.CONFIG` | hook `Query → LlmConfig` | The selected provider and model, even when not connected |

## Commands

`status`, `models [filter]`, `check` (the last two only while connected); `provider [id]`, `model [id]`.

## Adding a provider

Write a `ConfigPlugin<ProviderConfig>` that answers `Providers.STATUS` and, when it has a key, provides
`Providers.key("<id>")`. Extend `OpenAiCompatProvider` for OpenAI-compatible APIs (`Mistral.java` is about
50 lines), then list it in `LlmPlugins`. The `settings` command picks it up through `Providers.STATUS`.
