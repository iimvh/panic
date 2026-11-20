# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` holds the binding rules for this repo (the plugin conventions, the MUST list, style, commit format). Read it first; this file only adds commands and the big picture.

## Commands

```bash
git submodule update --init          # z/ is a git submodule (github.com/iimvh/z), consumed as a composite build via includeBuild("z/java"); empty until initialised. Fresh clones: use `git clone --recurse-submodules`
./gradlew check                      # all Java tests + UI build; -Xlint:all -Werror, so warnings fail the build
./gradlew check -PskipUi             # same, without Node
./gradlew :chat:test --tests 'panic.chat.ChatPluginTest'            # one test class (module name = folder name under plugins/)
./gradlew :app:test --tests 'panic.app.ConventionsTest'             # architecture rules checker
./gradlew :app:run -q --console=plain                               # run Panic (REPL); needs panic.json (copy panic.example.json)
./gradlew :app:installDist && app/build/install/app/bin/app models free   # run one command and exit
cd ui-v2 && npm run check && npm test   # UI type-check, vitest (logic, layers, tokens, components); `npm run test:e2e` Playwright; `npm run dev` for the dev server
cd tui && cargo test                    # terminal UI (Rust; cargo is in ~/.cargo/bin); also cargo fmt --check, cargo clippy --all-targets -- -D warnings
node eval/run.mjs --app eval/.builds/new --variant full   # real-model runs on fixed tasks (API keys, not CI); see eval/README.md
```

Needs JDK 21 (Gradle can provision it) and Node 20+ for the UI. `panic.json` is git-ignored; strings in it can use `${ENV}`.

## Architecture

Panic is a tree of plugins on the **z** lifecycle kernel (`z/java`: `core` = fibers and teardown, `context` = plugins, services, hooks, events, config, tasks). Dependency direction is `z` ← `sdk` ← `plugins/*` ← `app`.

- `sdk/`: API for plugin authors only (`panic.command`, `panic.plugin`, `panic.trace`, and `panic.util` with `Json` and `CancelToken`). Depends on z and Jackson only. No features.
- `app/`: discovers `PluginSet`s via `META-INF/services/panic.plugin.PluginSet`, loads `panic.json` (`ConfigLoader`), hot-reloads (`ConfigWatch`) and starts the tree. No features either, and no plugin names: without a `panic.json`, each `PluginSet.environment(env)` says which environment variables fill its sections (e.g. `LlmPlugins` maps `*_API_KEY` and `PANIC_MODEL`).
- `plugins/<module>/`: one Gradle module per plugin family (llm, chat, tools, todo, memory, skills, planner, interact, mcp, workspace, verify, trace, agents, cli, web). Any folder with a `build.gradle.kts` is included automatically in `settings.gradle.kts`; nothing to register.
- `ui-v2/`: SvelteKit SPA (Svelte 5 runes, Bits UI primitives), layered core / state / ui / features (see its README). Built and packed into the web plugin's jar by default; talks only to the `web` plugin's JSON + SSE API. `ui-v1/` is deprecated and frozen (packable with `-Pui=ui-v1`, not checked by CI).
- `tui/`: Rust terminal front-end (ratatui, blocking ureq on worker threads, no async runtime) over the same API, layered core / api / state / ui. `core` ports ui-v2's `lib/core` with its test cases; `state` keeps chats with an answer in flight in memory (background chats that need the user, a queue of messages sent while one runs). Not a Gradle module.

Plugins never call each other. They meet through z mechanisms: services (`ctx.provide`/`requires()`), hooks (`Hooks.bail`), events, commands (`Commands.command`), config (a record with `DEFAULT`, section named after the plugin), tasks (`ctx.spawn`) and trace spans. Because dependents start when a required service appears and stop when it goes, the tree reconfigures itself (e.g. changing the LLM restarts only the dependent branch). Request flow: front-ends (`cli`, `web`) only run commands or ask the `chat.ask` hook; `chat` runs the agent loop over the tools offered by `tool.list`/`tool.run` hooks, with `planner` answering `tool.gate` (plan mode), `permissions` answering `tool.policy`, `stop-hooks` answering `chat.stop`, `review` answering `chat.review`, and `tracing` collecting `trace.span` events. `instructions`, `memory` and `skills` add sections to the system prompt through `chat.context` (`Chat.contribute`), never through a service, so changing them restarts nothing else. A `/name` line that is no command but that `Prompts.EXPAND` (in `sdk`) answers, as `skills` does for a skill, is sent by the front-ends as a plain ask, and `chat` expands it when the turn starts.

Front-ends must not `requires()` feature services, and `z` only lets a plugin `ctx.get` services it declared in `requires()`. So `web` reads conversations through the hooks `Conversations.LIST/HISTORY/REMOVE` and answers 503 when nobody answers. One-shot CLI mode is `Commands.once` (in `sdk`), not a `cli` class. A plugin must not update its own config (use a separate plugin on `Configs.KEY`, like `settings` for `llm`).

`app/src/test/java/panic/app/ConventionsTest.java` enforces the machine-checkable rules (README per module, no raw threads or env reads, dependency direction, every imported module declared in `build.gradle.kts`, front-ends require only `App.KEY`, no static mutable state; exceptions are allow-listed with a reason); don't weaken it to make a change pass. Each `plugins/<module>/README.md` documents that module's plugins, config, keys and commands and must be updated in the same change as the code. Tests build a real tree with `Context.root("t")` and use fakes/local HTTP servers (no network or API keys).
