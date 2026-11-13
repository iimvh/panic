# plugins/trace

Keeps what the agent did and how long it took. Every chat turn is a **trace**: a tree of timed **spans** (the
turn, each model call, tool run, review and compaction) with tokens and status. This module stores them and
reads them back. Spans come from the contract in `sdk` (`panic.trace`), not from chat: any plugin that emits
`Trace.SPAN` is traced, and nothing here depends on chat. Java package `panic.trace.store` (`panic.trace` is the
sdk contract).

## Plugins

| Plugin | Config | Requires | Provides |
| --- | --- | --- | --- |
| `tracing` | `TracingConfig { dir, keepDays, maxTraces }` | nothing | hooks `Trace.GET`, `Trace.LIST`; commands `traces`, `trace`, `stats`. Listens to `Trace.SPAN` |

## Config

```json
"tracing": { "dir": ".panic/traces", "keepDays": 30, "maxTraces": 500 }
```

- `dir`: where traces are written, one JSON lines file per conversation (one span per line), named like the
  conversation files. Relative to the working directory. Default `.panic/traces`.
- `keepDays`: traces older than this are deleted when the plugin starts. Default 30, `0` keeps them all.
- `maxTraces`: the most traces kept per conversation, in memory and on disk. Oldest go first. Default 500.

Leave the plugin out (`"plugins": { "tracing": false }`) to keep no traces. Nothing else changes.

## How it works

- The `Trace.SPAN` listener only holds the span until its root ends. It never touches a file, so a turn never
  waits for the disk. Up to 256 unfinished traces are held; beyond that the oldest is dropped.
- When the root ends, the whole trace is recorded in memory and queued. A writer task (`ctx.spawn`) appends it
  to the conversation's file in one write.
- When the plugin is disposed, it first stops listening, then the writer writes what is still queued and
  returns.
- A conversation's file is read the first time its traces are asked for. It is merged with what was recorded
  meanwhile.
- At start the writer goes through the directory:
  - it drops traces past `keepDays` or beyond `maxTraces`
  - it skips damaged lines and spans whose trace has no root, with a warning
  - it rewrites a file that changed, and deletes one left empty
- One process per directory, as for conversations.

## Public keys

Defined in `sdk` (`panic.trace.Trace`):

| Key | Type | Meaning |
| --- | --- | --- |
| `Trace.SPAN` | event `Span(traceId, spanId, parentId, name, startMicros, durationMicros, status, error, attrs)` | A finished span. `parentId` null marks the root, which ends last. `status` is `OK` / `ERROR` / `CANCELLED`. `attrs` hold strings, numbers and booleans; the root's `conversationId` decides the file |
| `Trace.GET` | hook `Query(conversationId, traceId) → Spans(spans)` | The spans of one trace, in the order they started. `traceId` may be a unique prefix. No answer = unknown trace or no tracer |
| `Trace.LIST` | hook `ListQuery(conversationId, limit) → Roots(roots)` | The root spans of the latest traces, newest first (`limit <= 0` = all) |

Emit spans with `Trace.open(ctx, traceId, parentId, name)` → `OpenSpan` (`attr`, `child`, `end`, `fail`).
Listener failures are swallowed there, so telemetry never breaks the traced work. Chat's spans and their
attributes are listed in the [chat README](../chat/README.md#tracing). The web API serves traces at
`GET /api/trace` (see the [web README](../web/README.md)).

## Commands

All of them work on the session's conversation.

- `traces [count]`: the latest traces (default 10), newest first. Each line shows the id prefix, time,
  duration, outcome, steps, tools, tokens and the question.
- `trace <id>`: one trace as an indented tree, with the offset from the start of the turn, duration, tokens,
  time to the first token, approval wait and errors. An id prefix is enough.
- `stats`: turns (failed, cancelled), tokens in and out, turn and model-call latency (p50, p95, max, first
  token), and calls, average time and failures per tool.

```text
> trace 3f9c
trace 3f9c0a1e77b24d10 · 2026-11-13 14:03:12 · 3.21s · done
turn                         +0µs     3.21s  gpt-4o-mini · done · 2 steps · 1830+95 tok
  llm · step 1               +4ms     1.20s  900+40 tok · first token 310ms · 1 tool call
  tool · read_file           +1.20s   6ms    1520 chars
  llm · step 2               +1.21s   2.00s  930+55 tok · first token 280ms
```
