# plugins/web

The web front-end: an HTTP + SSE API and the SvelteKit UI from `ui/` (built by Gradle into
`panic/web/ui/` in this jar; `-PskipUi` skips it). Like `cli`, it owns no feature.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `web` | `WebConfig { enabled, host, port }` (default off, `127.0.0.1:8080`) | `Conversations.KEY` | an HTTP server while enabled; hook `Tools.APPROVE` |

Every request runs as a task of the plugin (`ctx.spawn`). Disposing it (config change or shutdown)
cancels the answers in flight, waits for them, then stops the server. An SSE heartbeat notices a browser
that left and cancels its answer.

## API

| Endpoint | Built on |
| --- | --- |
| `GET /api/state` | hooks `Llm.CONFIG`, `Llm.CURRENT`, `Providers.STATUS`; `Commands.list` |
| `GET /api/models?filter=` | hook `Llm.CURRENT` |
| `GET /api/history?session=` | service `Conversations.KEY` (questions and answers only; tool messages are left out) |
| `POST /api/chat {session, text}` → SSE `delta`, `tool`, `done`, ... | `Chat.ask` (`tool`: `{id, name, arguments, status: running/ok/error, content?}` from `onStep`; `done` adds `stop`, `toolCalls`) |
| `POST /api/command {session, line}` → SSE | `Commands.run` |
| `POST /api/approve {session, id, allow}` | answers an SSE `approve` (`404` when it is unknown, already answered or expired) |
| `PUT /api/settings {provider?, model?}` | the `provider` / `model` commands |

### Approvals

`web` answers the `Tools.APPROVE` hook for every session that has a `/api/chat` or `/api/command` stream open.
It sends `approve {id, callId, name, arguments, risk}` on that stream and waits for `POST /api/approve` with
the same session and id. The UI shows an Allow/Deny card until the call's finished `tool` event arrives. A
browser that leaves denies the call, and a cancelled call or an `askTimeoutSeconds` timeout ends the wait.
Sessions without an open stream are not answered (another front-end may answer them, otherwise the call is
denied).

New UI features call new endpoints here, and those endpoints use hooks and commands of the plugin that
owns the feature. Never add feature logic to this module.
