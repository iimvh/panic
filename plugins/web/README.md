# plugins/web

The web front-end: an HTTP + SSE API and the SvelteKit UI from `ui/` (built by Gradle into
`panic/web/ui/` in this jar; `-PskipUi` skips it). Like `cli`, it owns no feature.

## Plugins

| Name | Config | Requires | Provides / registers |
| --- | --- | --- | --- |
| `web` | `WebConfig { enabled, host, port, detachSeconds }` (default off, `127.0.0.1:8080`, 120) | none | an HTTP server while enabled; hooks `Tools.APPROVE`, `Planner.APPROVE`, `AskUser.ASK` |

Every request runs as a task of the plugin (`ctx.spawn`). Disposing it (config change or shutdown)
cancels the answers in flight, waits for them, then stops the server. An SSE heartbeat notices a browser
that left: a command is cancelled, an answer goes on without it (see [Resuming](#resuming)).

## API

| Endpoint | Built on |
| --- | --- |
| `GET /api/state` | hooks `Llm.CONFIG`, `Llm.CURRENT`, `Providers.STATUS`; `Commands.list` |
| `GET /api/models?filter=` | hook `Llm.CURRENT` |
| `GET /api/history?session=` | hook `Conversations.HISTORY`: `user {content}`, `assistant {content, toolCalls[]}`, `tool {toolCallId, status: ok/error, content}` (content cut like `tool` events), `review {reason}` for a rejection sent back to the model (`Chat.REVIEW_FEEDBACK_PREFIX`; errors are told by `Chat.TOOL_ERROR_PREFIX`). Turns already compacted into the summary are left out. A sub-agent's steps are not in it: its `agent` call and result (the sub-agent's answer) are, and the trace of the turn holds the sub-agent's work. With a tracer (hook `Trace.LIST`), `user` entries whose turn was traced add `traceId` (see [Traces](#traces)) |
| `GET /api/conversations` | hook `Conversations.LIST`: `[{id, title, updatedAt}]`, newest first. Only conversations whose id is a valid session id; sub-agents' conversations are not listed |
| `GET /api/permissions?session=` | hook `Tools.BYPASS`: `{bypass, default}`, whether the session runs in bypass mode (tool calls that would ask the user just run) and what the config says for every chat. `503` when nobody answers (no `permissions` plugin). Switching goes through `POST /api/command` (`bypass on \| off`) |
| `GET /api/mode?session=` | hook `Planner.MODE`: `{plan, default}`, whether the session is in plan mode (read-only research, then a plan to approve) and what the config says for every chat. `503` when nobody answers (no `planner` plugin). Switching goes through `POST /api/command` (`plan on \| off`) |
| `GET /api/workspaces` | hook `Workspace.LIST`: `{projects: [{path, name, conversations: [id]}], hidden: [id]}`; `hidden` are the chats of removed projects, which the page does not list. `503` when nobody answers (no `workspace` plugin). Projects are changed through `POST /api/command` (`workspace add \| use \| remove \| pick ...`) |
| `DELETE /api/conversations?session=` | hook `Conversations.REMOVE`: the conversation, its sub-agents' conversations, and (through `Conversations.DELETED`) its plan and traces. `409` while an answer runs in it, `404` when there is nothing to delete |
| `POST /api/chat {session, text}` → SSE `start`, `reasoning`, `delta`, `tool_delta`, `tool`, `review`, `done`, ... | `Chat.ask`, events in the order they happen (`reasoning`: `{text}` from `onReasoning`, a piece of what the model thinks, never kept in the history; `tool_delta`: `{index, id?, name?, arguments}` from `onToolDelta`, a piece of a call the model is still writing; `tool`: `{id, name, arguments, status: running/ok/error, content?, parent?, agent?}` from `onStep`, with `parent` (the id of the call that runs it) and `agent` for a sub-agent's call; `agent_delta`: `{parent, agent, text, reasoning?}` from `onAgentDelta`, a sub-agent's text (`reasoning: true` for what it thinks); `review`: `{attempt, pass, reason}` from `onReview`, or `{attempt, error}` when the reviewer failed; `done` adds `stop`, `toolCalls`, `traceId`, `usage.reasoningTokens` and `review` when the answer was reviewed) |
| `GET /api/trace?session=&id=` | hook `Trace.GET`: `{traceId, spans: [{id, parentId, name, startMicros, durationMicros, status: ok/error/cancelled, error?, attrs}]}`, in the order they started. `404` `no such trace`, or `tracing is off` when nobody answers `Trace.LIST` |
| `GET /api/stream?session=&after=` → SSE | attaches to the session's answer in flight: its events after SSE id `after`, then the rest; `204` when none runs |
| `POST /api/stop {session}` | cancels the session's answer (`404` when none runs) |
| `POST /api/command {session, line}` → SSE | `Commands.run`. When no command has the name but `Prompts.EXPAND` answers it (a skill), SSE `prompt {line}` and nothing runs: the page sends `line` to `/api/chat`, so a slash prompt is an answer like any other (one per session, resumable) |
| `POST /api/approve {session, id, allow}` | answers an SSE `approve` (`404` when it is unknown, already answered or expired) |
| `POST /api/plan {session, id, approve, feedback?}` | answers an SSE `plan`: approve it, or send it back with `feedback` (`404` when it is unknown, already answered or expired) |
| `POST /api/answer {session, id, answers: [{selected: [label], other?}]}` | answers an SSE `question`, one answer per question in order (`400` for a wrong count, an unknown label, or several picks on a single-select question; all empty skips; `404` when it is unknown, already answered or expired) |
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

History links questions to traces by `Trace.questionHash` (attrs `Trace.QUESTION_HASH`, `Trace.SAVED`). Both are walked newest first, and each question takes
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

It answers `Planner.APPROVE` (plan mode, `plugins/planner`) the same way: `plan {id, plan, items, criteria}` on the
stream, then `POST /api/plan` with `approve` and an optional `feedback`. The UI shows the plan (markdown, steps,
criteria) with Approve and "Keep planning" until the `exit_plan_mode` call's finished `tool` event arrives. "Keep
planning" sends the plan back only with feedback. It is replayed on resume like `approve`. A browser that leaves a
command stream answers nothing.

It also answers `AskUser.ASK` (the agent's questions, `plugins/interact`): `question {id, questions: [{question, header,
multiSelect, options: [{label, description}]}]}` on the stream, then `POST /api/answer`. The UI shows a card with
the options (radio or checkbox) and an "Other" text field, and Submit / Skip buttons, until the `ask_user` call's
finished `tool` event arrives. It is replayed on resume. A browser that leaves a command stream answers nothing.

New UI features call new endpoints here, and those endpoints use hooks and commands of the plugin that
owns the feature. Never add feature logic to this module.
