# plugins/web

The web front-end: an HTTP + SSE API and the SvelteKit UI from `ui/` (built by Gradle into
`panic/web/ui/` in this jar; `-PskipUi` skips it). Like `cli`, it owns no feature.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `web` | `WebConfig { enabled, host, port }` (default off, `127.0.0.1:8080`) | `Conversations.KEY` | an HTTP server while enabled |

Every request runs as a task of the plugin (`ctx.spawn`). Disposing it (config change or shutdown)
cancels the answers in flight, waits for them, then stops the server. An SSE heartbeat notices a browser
that left and cancels its answer.

## API

| Endpoint | Built on |
| --- | --- |
| `GET /api/state` | hooks `Llm.CONFIG`, `Llm.CURRENT`, `Providers.STATUS`; `Commands.list` |
| `GET /api/models?filter=` | hook `Llm.CURRENT` |
| `GET /api/history?session=` | service `Conversations.KEY` |
| `POST /api/chat {session, text}` → SSE | `Chat.ask` |
| `POST /api/command {session, line}` → SSE | `Commands.run` |
| `PUT /api/settings {provider?, model?}` | the `provider` / `model` commands |

New UI features call new endpoints here, and those endpoints use hooks and commands of the plugin that
owns the feature. Never add feature logic to this module.
