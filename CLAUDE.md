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
cd ui && npm run check && npm test   # UI type-check and vitest (needed when touching ui/); `npm run dev` for the dev server
```

Needs JDK 21 (Gradle can provision it) and Node 20+ for the UI. `panic.json` is git-ignored; strings in it can use `${ENV}`.

## Architecture

Panic is a tree of plugins on the **z** lifecycle kernel (`z/java`: `core` = fibers and teardown, `context` = plugins, services, hooks, events, config, tasks). Dependency direction is `z` ← `sdk` ← `plugins/*` ← `app`.

- `sdk/`: API for plugin authors only (`panic.command`, `panic.plugin`, `panic.trace`, and `panic.util` with `Json` and `CancelToken`). Depends on z and Jackson only. No features.
- `app/`: discovers `PluginSet`s via `META-INF/services/panic.plugin.PluginSet`, loads `panic.json` (`ConfigLoader`), hot-reloads (`ConfigWatch`) and starts the tree. No features either, and no plugin names: without a `panic.json`, each `PluginSet.environment(env)` says which environment variables fill its sections (e.g. `LlmPlugins` maps `*_API_KEY` and `PANIC_MODEL`).
- `plugins/<module>/`: one Gradle module per plugin family (llm, chat, tools, todo, verify, trace, agents, cli, web). Any folder with a `build.gradle.kts` is included automatically in `settings.gradle.kts`; nothing to register.
- `ui/`: SvelteKit SPA (Svelte 5 runes). Built and packed into the web plugin's jar; talks only to the `web` plugin's JSON + SSE API.

Plugins never call each other. They meet through z mechanisms: services (`ctx.provide`/`requires()`), hooks (`Hooks.bail`), events, commands (`Commands.command`), config (a record with `DEFAULT`, section named after the plugin), tasks (`ctx.spawn`) and trace spans. Because dependents start when a required service appears and stop when it goes, the tree reconfigures itself (e.g. changing the LLM restarts only the dependent branch). Request flow: front-ends (`cli`, `web`) only run commands or ask the `chat.ask` hook; `chat` runs the agent loop over the tools offered by `tool.list`/`tool.run` hooks, with `permissions` answering `tool.policy`, `review` answering `chat.review`, and `tracing` collecting `trace.span` events.

Front-ends must not `requires()` feature services, and `z` only lets a plugin `ctx.get` services it declared in `requires()`. So `web` reads conversations through the hooks `Conversations.LIST/HISTORY/REMOVE` and answers 503 when nobody answers. One-shot CLI mode is `Commands.once` (in `sdk`), not a `cli` class. A plugin must not update its own config (use a separate plugin on `Configs.KEY`, like `settings` for `llm`).

`app/src/test/java/panic/app/ConventionsTest.java` enforces the machine-checkable rules (README per module, no raw threads or env reads, dependency direction, every imported module declared in `build.gradle.kts`, front-ends require only `App.KEY`, no static mutable state; exceptions are allow-listed with a reason); don't weaken it to make a change pass. Each `plugins/<module>/README.md` documents that module's plugins, config, keys and commands and must be updated in the same change as the code. Tests build a real tree with `Context.root("t")` and use fakes/local HTTP servers (no network or API keys).
