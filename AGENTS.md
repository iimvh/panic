# AGENTS.md: how to work on Panic

This file is for every coding agent (and human) working in this repo. Read it before you change anything.

## The one rule: everything is a plugin

Panic is a tree of **plugins** on the [z](z/README.md) lifecycle kernel (`z/java`: `core` = fibers and
teardown, `context` = plugins, services, hooks, events, config, tasks). Every feature, front-end and
integration is a plugin. Plugins never call each other directly; they meet through z:

| Mechanism | Use it when | API |
| --- | --- | --- |
| **Service** | A plugin offers a long-lived object that others need *while it exists* (a provider, the LLM, a store). Dependents start when it appears and stop before it goes. | `ctx.provide(KEY, impl)`; consumers declare `requires()` and call `ctx.get(KEY)` |
| **Hook** | A request/answer where the caller must keep running even when nobody answers (front-ends asking the chat). | `ctx.hook(KEY, handler)`; callers use `Hooks.bail(ctx, KEY, event)` / `ctx.collect` |
| **Event** | Fire-and-forget notifications (`chat.turn`). | `ctx.on(KEY, listener)`, `ctx.emit(KEY, event)` |
| **Command** | Something a user can run from the CLI or the web UI. | `Commands.command(ctx, "name <arg> [opt] [rest...]", "description", call -> ...)` |
| **Config** | Anything a user may tune. One immutable record per plugin, read from the `panic.json` section named after the plugin. | `PluginSpec.of(name, ConfigType.class, DEFAULT, Factory::new)` |
| **Task** | Background or blocking work. | `ctx.spawn(name, signal -> ...)` |
| **Span** | Work worth timing (a model call, a tool run). Emitted when it ends, stored by `tracing` if installed. | `Trace.open(ctx, traceId, parentId, name)` → `attr(...)`, `end()` / `fail(e)` |

Everything a plugin creates goes through its `Context`, so z disposes it when the plugin stops: in reverse
order, exactly once, after any call still running.

## Rules (MUST)

1. **New behaviour = a plugin.** Put it in an existing plugin module or in a new one under `plugins/`.
   Never add features to `app/` (it only discovers, configures and starts plugins) or to `sdk/` (it only
   holds the API for plugin authors).
2. **No direct coupling between plugins.** Do not call another plugin's classes to do work. Do not share
   static mutable state or singletons. Use the public keys (`ServiceKey`, `HookKey`, `EventKey`) and
   commands that the other module documents in its README.
3. **Own your lifetime.** Create threads with `ctx.spawn`, resources with `ctx.effect`, listeners with
   `ctx.on` or `ctx.hook`, and sub-plugins with `ctx.plugin`. Never start a raw `Thread` or executor that
   outlives the plugin. Long or blocking work must honour the `Signal` (pass it to `ChatModel`/`Provider`
   calls, check `isCancelled()`, or block interruptibly).
4. **Config comes from the plugin's section.** It is a record with a `DEFAULT` constant, compared with
   `equals()`, and normalized in its compact constructor. Plugins never read environment variables or
   files for settings: `panic.json` supports `${ENV}` for that.
5. **A plugin never updates its own config** (the restart would wait for its own caller). Put such commands
   in a separate plugin that requires `Configs.KEY`, as `settings` does for `llm`.
6. **Front-ends own no features.** `cli` and `web` only run commands and ask hooks. They must not
   `requires()` a feature service, or switching the LLM would restart them. A new front-end (Telegram,
   Discord, ...) follows the same pattern and requires `App.KEY` if it must start after the whole tree.
7. **Commands belong to the plugin that does the work**, so they appear and disappear with it.
8. **Dependency direction:** `z` ← `sdk` ← `plugins/*` ← `app`. A plugin module may depend on another
   plugin module only to use its public keys and types. Nothing depends on `app`, and no plugin depends on
   a front-end module (`cli`, `web`).
9. **Document the contract.** Every `plugins/<module>/README.md` lists its plugins, config, the keys it
   provides and consumes, and its commands. Update it in the same change as the code.
   The same goes for `AGENTS.md` and `CLAUDE.md`: if a change makes a sentence there false (a command, a
   path, a rule, a key name), fix that sentence in the same commit. Correct them, do not pad them.
10. **No new libraries without a reason** that you write down in the change. The Java side uses only z and
    Jackson. The UI uses only SvelteKit.

`app/src/test/java/panic/app/ConventionsTest.java` enforces the rules a machine can check. Do not weaken it
to make a change pass.

## Adding a plugin

1. Pick a module: extend one in `plugins/` if the plugin belongs to its domain, otherwise create
   `plugins/<module>/` (Gradle and the app pick it up automatically):
   ```text
   plugins/<module>/
   ├── README.md
   ├── build.gradle.kts                          plugins { id("java-conventions"); `java-library` }
   │                                             dependencies { api(project(":sdk")) }   // + :llm / :chat if needed
   └── src/
       ├── main/java/panic/<module>/             <Name>Plugin.java, <Name>Config.java, <Module>Plugins.java
       ├── main/resources/META-INF/services/panic.plugin.PluginSet   one line: panic.<module>.<Module>Plugins
       └── test/java/panic/<module>/             <Name>PluginTest.java
   ```
2. Write the plugin: `implements ConfigPlugin<Config>` (or `Plugin`), declare `requires()`, and do all
   work in `apply(ctx, cfg)`. Return the cleanup, or `null`.
3. List it in the module's `PluginSet` with a unique, lowercase, dashed name. The name is also its
   `panic.json` section and its node in the tree.
4. Export public keys as `public static final` constants on a small interface or class named after the
   concept (`Llm.KEY`, `Chat.ASK`, `Conversations.KEY`, `Tools.RUN`).
5. Add the plugin's section to `panic.example.json` if it has user-facing config, and its commands to the
   root README if users will run them.
6. Write tests and the module README, then run the checks below.

## Tests

- JUnit 5. Build a real tree with `Context.root("t")`, install the plugins under test, dispose it in
  `@AfterEach`. Use fakes for services (see `LlmPluginTest.Echo`, `ChatPluginTest.FakeLlm`) and local
  HTTP servers for HTTP (`FakeServer`). No network, no API keys.
- Test the lifecycle as well as the happy path. Check what happens when a required service is retracted
  and when it comes back, when the config changes, and when a call is still running while the plugin is
  disposed (it must be cancelled and awaited).
- Test commands through `Commands.run` and hooks through `Hooks.bail`, the way front-ends call them.

## Checks before you finish

```bash
./gradlew check                     # Java tests (+ UI build); compiler warnings are errors
./gradlew check -PskipUi            # without Node
cd ui && npm run check && npm test  # when you touch ui/
```

CI (`.github/workflows/ci.yml`, job **Build and test**) runs the same checks on every push to `master` and
every pull request. A change is not done until it passes.

## Style

- Java 21, 2-space indentation, `-Xlint:all -Werror`. Prefer records and small final classes.
- Javadoc says **why** and states the lifecycle contract (what a plugin requires, provides and cancels).
  Skip comments that only repeat the code.
- Code, comments, commit messages and docs are in English.
- Commits use Conventional Commits (`feat(web): ...`, `fix(llm): ...`, `docs: ...`), one logical change
  per commit. Do not add AI co-author trailers.
- The UI is in `ui/` (SvelteKit SPA, Svelte 5 runes, plain scoped CSS). It talks only to the `web` plugin's
  JSON + SSE API, never to anything else.

## Map

| Module | Plugins | Contract |
| --- | --- | --- |
| `sdk` | none | [`panic.command`](sdk/src/main/java/panic/command), [`panic.plugin`](sdk/src/main/java/panic/plugin), [`panic.trace`](sdk/src/main/java/panic/trace), [`panic.util`](sdk/src/main/java/panic/util) (`Json`, `CancelToken`) |
| `plugins/llm` | `openrouter`, `mistral`, `llm`, `settings` | [README](plugins/llm/README.md) |
| `plugins/chat` | `conversations`, `chat` | [README](plugins/chat/README.md) |
| `plugins/tools` | `tools`, `permissions`, `files`, `clock`, `shell` | [README](plugins/tools/README.md) |
| `plugins/todo` | `todo` | [README](plugins/todo/README.md) |
| `plugins/planner` | `planner` | [README](plugins/planner/README.md) |
| `plugins/interact` | `ask-user` | [README](plugins/interact/README.md) |
| `plugins/mcp` | `mcp` | [README](plugins/mcp/README.md) |
| `plugins/workspace` | `workspace`, `folder-picker` | [README](plugins/workspace/README.md) |
| `plugins/verify` | `checks`, `review`, `stop-hooks` | [README](plugins/verify/README.md) |
| `plugins/trace` | `tracing` | [README](plugins/trace/README.md) |
| `plugins/agents` | `agents` | [README](plugins/agents/README.md) |
| `plugins/cli` | `cli` | [README](plugins/cli/README.md) |
| `plugins/web` | `web` | [README](plugins/web/README.md) |
| `app` | none (bootstraps) | `Panic`, `PluginHost`, `ConfigLoader`, `ConfigWatch`, `Main` |
