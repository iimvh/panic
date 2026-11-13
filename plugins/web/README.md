# plugins/web

The web front-end: an HTTP + SSE API and the SvelteKit UI from `ui/` (built by Gradle into
`panic/web/ui/` in this jar; `-PskipUi` skips it). Like `cli`, it owns no feature.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `web` | `WebConfig { enabled, host, port, detachSeconds }` (default off, `127.0.0.1:8080`, 120) | `Conversations.KEY` | an HTTP server while enabled; hook `Tools.APPROVE` |

Every request runs as a task of the plugin (`ctx.spawn`). Disposing it (config change or shutdown)
cancels the answers in flight, waits for them, then stops the server. An SSE heartbeat notices a browser
that left: a command is cancelled, an answer goes on without it (see [Resuming](#resuming)).

## API

| Endpoint | Built on |
| --- | --- |
| `GET /api/state` | hooks `Llm.CONFIG`, `Llm.CURRENT`, `Providers.STATUS`; `Commands.list` |
| `GET /api/models?filter=` | hook `Llm.CURRENT` |
| `GET /api/history?session=` | service `Conversations.KEY`: `user {content}`, `assistant {content, toolCalls[]}`, `tool {toolCallId, status: ok/error, content}` (content cut like `tool` events), `review {reason}` for a rejection sent back to the model (`Chat.REVIEW_FEEDBACK_PREFIX`; errors are told by `Chat.TOOL_ERROR_PREFIX`). Turns already compacted into the summary are left out. A sub-agent's steps are not in it: its `agent` call and result (the sub-agent's answer) are, and the trace of the turn holds the sub-agent's work. With a tracer (hook `Trace.LIST`), `user` entries whose turn was traced add `traceId` (see [Traces](#traces)) |
| `GET /api/conversations` | service `Conversations.KEY` (`list()`): `[{id, title, updatedAt}]`, newest first. Only conversations whose id is a valid session id; sub-agents' conversations are not listed |
| `DELETE /api/conversations?session=` | `Conversations.KEY` (`delete(id)`): the conversation, its sub-agents' conversations, and (through `Conversations.DELETED`) its plan and traces. `409` while an answer runs in it, `404` when there is nothing to delete |
| `POST /api/chat {session, text}` → SSE `start`, `delta`, `tool_delta`, `tool`, `review`, `done`, ... | `Chat.ask`, events in the order they happen (`tool_delta`: `{index, id?, name?, arguments}` from `onToolDelta`, a piece of a call the model is still writing; `tool`: `{id, name, arguments, status: running/ok/error, content?, parent?, agent?}` from `onStep`, with `parent` (the id of the call that runs it) and `agent` for a sub-agent's call; `agent_delta`: `{parent, agent, text}` from `onAgentDelta`, a sub-agent's text; `review`: `{attempt, pass, reason}` from `onReview`; `done` adds `stop`, `toolCalls`, `traceId` and `review` when the answer was reviewed) |
| `GET /api/trace?session=&id=` | hook `Trace.GET`: `{traceId, spans: [{id, parentId, name, startMicros, durationMicros, status: ok/error/cancelled, error?, attrs}]}`, in the order they started. `404` `no such trace`, or `tracing is off` when nobody answers `Trace.LIST` |
| `GET /api/stream?session=&after=` → SSE | attaches to the session's answer in flight: its events after SSE id `after`, then the rest; `204` when none runs |
| `POST /api/stop {session}` | cancels the session's answer (`404` when none runs) |
| `POST /api/command {session, line}` → SSE | `Commands.run` |
| `POST /api/approve {session, id, allow}` | answers an SSE `approve` (`404` when it is unknown, already answered or expired) |
| `PUT /api/settings {provider?, model?}` | the `provider` / `model` commands |

### Resuming

An answer belongs to its session, not to the connection that asked for it. Every event of `POST /api/chat`
has an SSE `id` (1, 2, ...), and the first one is `start {question, startedAt, traceId}`. When the browser goes (a
reload, a dropped network) the answer keeps running and its events are kept. `GET /api/stream` with the
last id seen replays the missed events, then streams the rest. The answer stops on `POST /api/stop`, when
nobody has watched it for `detachSeconds` (`0`: as soon as the last page leaves, the old behaviour), or when
the plugin stops. A session runs one answer at a time (`409` for another `POST /api/chat`). Once it ends,
`GET /api/history` holds it.

### Traces

`POST /api/chat` picks the answer's trace id and passes it as `Ask.traceId`, so `start` already names the trace.
A resumed page gets it from the replayed `start`. Web never requires the tracer: it only asks `Trace.GET` and
`Trace.LIST` (from `sdk`). With no `tracing` plugin, those ask nobody: `/api/trace` answers `404` and history has
no `traceId`.

History links questions to traces by `Chat.questionHash`. Both are walked newest first, and each question takes
the next saved trace (`saved` on the root) of the same question. A question asked while tracing was off gets
none. Traces of turns that left the history (compacted, `reset`) are passed over.

### Approvals

`web` answers the `Tools.APPROVE` hook for every session with an answer in flight or a `/api/command` stream open.
It sends `approve {id, callId, name, arguments, risk, agent?}` on that stream and waits for `POST /api/approve` with
the same session and id. The UI shows an Allow/Deny card until the call's finished `tool` event arrives. For
an answer the question is part of its events, so a page that resumes gets it again and can still answer it;
for a command a browser that leaves denies the call. A cancelled call or an `askTimeoutSeconds` timeout ends
the wait.
Sessions without an open stream are not answered (another front-end may answer them, otherwise the call is
denied).

New UI features call new endpoints here, and those endpoints use hooks and commands of the plugin that
owns the feature. Never add feature logic to this module.
