# tui

Panic's terminal front-end, in Rust: a full-screen chat in the terminal, so a weak machine needs no browser.

- **Small.** It is one binary of about 2 MB that uses about 8 MB of memory. It starts at once and uses no CPU while idle.
- **Same API as ui-v2.** It talks only to the `web` plugin's JSON + SSE API (see `plugins/web/README.md`) and owns no feature.

## Running

Run everything from the repository root. Panic reads `panic.json` from the folder it starts in, and the TUI puts a
new chat in the project of the folder it starts in.

1. **Install Rust** once (no root needed):
   `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal`.
   Then open a new shell, or run `export PATH="$HOME/.cargo/bin:$PATH"`.
2. **Build the Panic launcher**, once and again after Java changes. `-PskipUi` skips the web UI and needs no Node:
   `./gradlew :app:installDist -PskipUi`
3. **Configure Panic**: copy `panic.example.json` to `panic.json` and set a provider's key. You can skip this when
   `OPENROUTER_API_KEY` is set.
4. **Build the TUI**, once and again after changes to `tui/`: `cargo build --release --manifest-path tui/Cargo.toml`.
   The first build takes about a minute.
5. **Run**, either way:

   ```bash
   # one terminal: the TUI starts Panic (`app --serve`) and stops it on exit
   tui/target/release/panic-tui --spawn app/build/install/app/bin/app

   # or two terminals: Panic keeps running between TUI sessions (and serves the web UI too)
   JAVA_OPTS="-Xmx256m -XX:+UseSerialGC" app/build/install/app/bin/app --serve   # terminal 1
   tui/target/release/panic-tui                                                  # terminal 2
   ```

Other ways to run it:

```bash
tui/target/release/panic-tui --session <id>               # open a stored chat (its id is in the web UI's /c/<id>)
tui/target/release/panic-tui --url http://host:8080       # Panic on another machine, e.g. through an SSH tunnel
tui/target/release/panic-tui ask "Explain SSE in one line" # one answer to stdout, tool calls to stderr
tui/target/release/panic-tui --help
```

`JAVA_OPTS="-Xmx256m -XX:+UseSerialGC"` keeps Panic at about 150 MB on a weak machine. With `--spawn`, set it in the
shell before you run the TUI.

**When something goes wrong:**
- **The top bar says `offline`.** Panic is not answering at `--url`. Start `app --serve` (or use `--spawn`), then press `r`.
- **"No model is connected".** The provider has no key. Commands still work; pick a provider or model with Ctrl+K,
  or fix `panic.json` (Panic applies it without a restart).
- **`--spawn` fails.** It waits up to 90 s and names the log file holding Panic's output.

## Design

The design starts from ui-v2's weaknesses rather than copying it.

```
 Panic · agent-panic · fix resume                          m/sonnet · medium · ctx 4%
 ❯ fix resume
 ● I'll read ChatStream first.
 ✓ read_file  ChatStream.java                                                 2.1 KB
 ✓ edit_file  ChatStream.java                                           +2 −1  7s
     41 - if (e.seq() >= after)
     41 + if (e.seq() > after)
 ✓ exec       ./gradlew :web:test                                          16 B  1s
   ─ 1 file +2 −1 · m/sonnet · 17k tokens · 4 tools · 10s ─ trace ↵
 Todo 1/3  ✓ read  ◐ fix  ○ run tests
 ⠼ Running exec… 9s  Esc to stop
 ⏎ queued  then run all tests
──────────────────────────────────────────────────────────────────────────────────────
❯ _
 ask first (⇧Tab)  1 chat needs you (^J)                          ^K palette · ? keys
```

**Nothing that waits for you can be missed.**
- An approval, a plan or the agent's questions take the place of the message box until they are answered:
  `y`/`n`, `a`/`f`, or ↑↓ Space Enter. They never sit inside the scrolling thread.
- An approval for a file change shows the change it would make.
- Every chat with an answer in flight stays in memory with its stream open. A chat left in the background that waits for
  you is counted in the footer and rings the terminal bell, and Ctrl+J goes to it.

**You can always type.** A message sent while an answer runs waits in a queue (↑ takes it back) and goes when the answer
ends.

**One place for each thing, and progress in view.**
- Tool calls are shown once: one line each in the thread, with Enter opening the full step (arguments, diff, result,
  sub-agent). There is no second list of steps.
- The todo list and the status are pinned above the message box.
- The context use (the last model call's tokens against the model's window, from the trace) is in the top bar.

**The thread stays short.**
- Diffs are open in the turn that runs and the last one, cut at 20 lines.
- Older turns keep their text and fold each run of tool calls into one line, and every answer ends with a summary line:
  files changed, model, tokens, tools, time. Enter on it opens the trace.
- Space folds or unfolds any line.

**Fast to steer, and a choice leads on to the choices behind it.**
- Ctrl+K is one palette for chats, `/commands`, models, reasoning effort, provider, mode and actions (new chat, delete,
  projects, overview, trace, copy).
- The palette has levels, with a breadcrumb:
  - Enter picks an item, or opens it when it is only a menu.
  - → opens the choices behind an item (marked ›). ← or Backspace goes back.
- Where choices lead on:
  - a provider switches and shows its models (loaded in place; a warning says when the current model is not one of them);
  - a reasoning model goes on to its effort;
  - a chat has Open, Delete… and Copy id;
  - a project has New chat here, Copy path and Remove…;
  - a prompt from the history can be put back or sent;
  - a command whose argument is a choice (`/provider`, `/model`, `/effort`, `/plan`, `/bypass`, `/workspace`,
    `/help`, `/trace`) lists the values.
- Ctrl+P opens the models straight away. A bare `/model`, `/provider` or `/effort` opens its level too.
- After `/plan ` and the like, the composer suggests the values (Tab fills one in).
- Shift+Tab cycles Ask first → Plan → Auto-run. Auto-run asks first.
- A chat started in a project's folder works in that project.

## Keys

| Keys | Action |
| --- | --- |
| Enter, Shift+Enter or Alt+Enter | Send (queue while an answer runs). New line. |
| Esc | Close a view, else stop the answer. |
| Ctrl+C twice | Quit. Once clears the text or stops the answer. |
| Shift+Tab | Next mode. |
| Ctrl+K | Palette. Enter picks, → opens the choices behind an item, ← or Backspace goes back, Esc closes. |
| Ctrl+P | Model: provider, model and reasoning effort (Ctrl+F: free models only). |
| Ctrl+R | Search the prompt history. |
| Ctrl+J | Go to the chat that needs you. |
| Ctrl+N | New chat. |
| Ctrl+O | Run overview: mode, model, context, time, tokens, calls, edits, checklist, files. |
| Ctrl+G | Write the prompt in `$VISUAL` / `$EDITOR`. |
| ↑ / ↓ in an empty prompt | The queued message, then the history (kept in `$XDG_STATE_HOME/panic-tui/history`). |
| PgUp / PgDn, mouse wheel | Scroll. |
| Tab | Select lines in the thread: j/k move, Enter opens, Space folds, `c` / `C` copy the line / its last code block. |
| `/continue` | Go on after an answer stopped at a limit. |
| `?` | All keys. |

Copying uses the OSC 52 terminal sequence, so it works over SSH too.

## Layers

```
src/core    pure logic over the API's data, ported from ui-v2's src/lib/core with its test cases
            (sse, reply timeline, diff, markdown, summary, trace, conversations, formats, session ids)
src/api     the web API behind the `Backend` trait (HttpBackend: blocking HTTP on worker threads)
src/state   chats and runs, the only layer that calls the backend
src/ui      drawing and keys (ratatui), never calls the backend
```

Each layer uses only the ones above it, and `tests/layers.rs` checks this. A later Rust core can implement `Backend`
without the other layers changing.

## Dependencies

AGENTS.md rule 10 asks for a reason for each one. They are kept few so the crate builds quickly on a weak machine.

| Crate | Why |
| --- | --- |
| `ratatui` (with its `crossterm` backend) | Drawing, keys, mouse, paste and resize in every common terminal. Writing this by hand would be a terminal library of our own. |
| `ureq` (no default features: plain HTTP, no TLS) | A blocking HTTP client that reads SSE as it arrives. It needs no async runtime: worker threads and one channel are enough for a front-end. |
| `serde`, `serde_json` | The API's JSON. |
| `unicode-width` | The width of text in terminal cells, for wrapping. ratatui already depends on it. |

There is no async runtime, no CLI-argument crate, no Markdown crate and no clipboard crate:
- Markdown is a port of ui-v2's own parser, so both front-ends read answers the same way.
- The clipboard is OSC 52.

## Tests

```bash
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```

- **Unit tests** in `src/core` are the cases of ui-v2's `*.test.ts`, plus tests of the views (thread lines, cards,
  palette, composer).
- **`tests/state.rs`** drives the state layer against `tests/common`, a scripted fake of the API on a local port. It
  covers answers, approvals, the queue, background chats, resuming after a dropped stream, skills, `/reset`, mode
  switches and deletes.
- **`tests/screen.rs`** draws the whole screen on a test terminal at 80 and 160 columns.
- **`tests/layers.rs`** checks the layers' direction.

When the API changes (an endpoint, an event or a field), change `tests/common/mod.rs` in the same commit as
`ui-v2/e2e/fake-api.ts`.
