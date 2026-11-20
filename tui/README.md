# tui

Panic's terminal front-end, written in Rust: a full-screen chat in the terminal, so you don't need a browser on a weak machine.
It is a single small binary. It talks only to the `web` plugin's JSON + SSE API (see `plugins/web/README.md`), the same
API ui-v2 uses, and owns no feature.

## Layers

```
src/core    pure logic over the API's data, ported from ui-v2's src/lib/core with its test cases
            (sse, reply timeline, diff, markdown, summary, trace, conversations, formats, session ids)
src/api     the web API behind the `Backend` trait (HttpBackend: blocking HTTP on worker threads)
src/state   chats and runs, the only layer that calls the backend
src/ui      drawing and keys (ratatui), never calls the backend
```

Each layer uses only the layers above it in this list.

The state layer keeps every chat that has an answer in flight in memory, with its stream open. This has two effects:
- Leaving a chat never loses its answer.
- A chat in the background that waits for an approval is known at once.

## Dependencies

AGENTS.md rule 10 asks for a reason for each one. They are kept few so the crate builds quickly on a weak machine.

| Crate | Why |
| --- | --- |
| `ratatui` (with its `crossterm` backend) | Drawing, keys, mouse and resize in every common terminal. Writing this by hand would be a terminal library of our own. |
| `ureq` (no default features: plain HTTP, no TLS) | A blocking HTTP client small enough to read SSE line by line. It needs no async runtime: worker threads and one channel are enough for a front-end. |
| `serde`, `serde_json` | The API's JSON. |

The crate deliberately has no async runtime, no CLI-argument crate, no Markdown crate and no clipboard crate:
- Markdown is a port of ui-v2's own parser, so both front-ends render answers the same way.
- Copying uses the OSC 52 terminal sequence, which also works over SSH.

## Tests

```bash
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

- **Unit tests** in `src/core` are the cases of ui-v2's `*.test.ts`.
- **`tests/state.rs`** drives the state layer against `tests/common`, a scripted fake of the API on a local port.
  It covers resuming, background chats, the queue, cards, mode switches and deletes.

When the API changes (an endpoint, an event or a field), change that fake in the same commit as `ui-v2/e2e/fake-api.ts`.
