# Panic (Spring Boot + Spring AI + OpenRouter)

Panic is a multi-agent assistant following the architecture in `idea.md`, built with **Spring Boot 3.x** and **Spring AI**, using **OpenRouter** as the LLM provider. Panic ships with two agents: the **coordinator** (root, answers directly) in `panic-agents-core`, and an example **echo** agent in `panic-agent-example` that shows the module layout. Everything else is added as its own module.

Every agent is a **self-describing plugin**; there is only **one `Agent` type** used by both coordinators and leaf agents, nesting recursively without limits.

## Architecture

| Component | Implementation |
|---|---|
| User Interface | React + Vite + Tailwind SPA (`web/`, built into `panic-app/src/main/resources/static`) |
| API | `AgentController` (`POST /api/agent/chat`) — the user's only entry point |
| **Agent runtime** | `core.agent.runtime`: `AgentRegistry` (wires the tree), `AgentExecutor` (LLM + tool loop, recursion), `AgentTreeEndpoint` (`/actuator/agents`) |
| **Agent SPI** | `core.agent.spi`: `Agent`, `AgentDefinition`, `ToolSource`, `Skill`, `AgentPolicy`, `AgentContext` |
| Bundled agents | `coordinator` (`panic-agents-core`) + example `echo` (`panic-agent-example`) — each is `agent.yaml` + `system.md` in its own module |
| Tool backend | local (`@Tool` / `ToolCallback`) or MCP server — none wired out of the box |
| Guardrails | Unbounded recursion is prevented with `depth` + `chain`; agents cannot mount themselves |
| Observability | Actuator: `/actuator/health`, `/actuator/agents` |
| Data Sources | H2 (file-based) for conversation memory |

### What an agent consists of

```java
AgentDefinition.builder("search")
    .description("Find free rooms by time, headcount and location.")        // = what the parent LLM sees
    .systemPromptTemplate("... {now} {tools} {subAgents} {skills} ...")     // rendered EVERY turn
    .toolSources(LocalToolSource.of("search-tools", searchTools))           // local tools
    .subAgentIds("...")                                                     // empty = leaf agent
    .skillIds("my-skill")
    .policy(new AgentPolicy(maxDepth, timeoutMs, maxToolCalls))
    .build();
```

| Field | Meaning |
|---|---|
| `id` | Unique identifier; used as the tool prefix (`<id>__<tool>`) and the pipeline actor |
| `description` | One sentence; this is what the parent LLM reads to decide whether to delegate |
| `systemPromptTemplate` | Re-rendered every turn: `{now}`, `{today}`, `{tools}`, `{subAgents}`, `{skills}` |
| `toolSources` | `LocalToolSource` (a `@Tool`/`ToolCallback` method) and/or `McpToolSource` |
| `subAgentIds` | Each sub-agent = exactly **one** `call_<id>` tool on the parent; leaf tools are not flattened |
| `skillIds` | Unlocks the `load_skill` tool (see below) |
| `policy` | `maxDepth`, `timeoutMs`, `maxToolCalls` |

### Request flow

```
User → AgentController → AgentExecutor.run(root, depth=0)
    render system prompt ({now},{tools},{subAgents},{skills})
    LLM call ─┬─ local tool  <id>__x  → *Tools → service
              ├─ MCP tool    <id>__x  → MCP server
              └─ call_<subId> tool    → AgentExecutor.run(sub, depth+1)
    synthesize reply
```

The current agent tree:

```
coordinator                  (no tools of its own)
└── echo                     echo__echo   (example module)
```

## Enabling / disabling agents

Each agent bean carries `@ConditionalOnProperty(app.agents.<id>.enabled)` (default `true`).
A disabled agent disappears from the registry, the prompt and the `call_<id>` tool; the app still starts.

```bash
APP_AGENTS_COORDINATOR_ENABLED=false mvn -pl panic-app -am spring-boot:run
curl -s localhost:8080/actuator/agents   # coordinator is disabled; the app is still UP
```

Disabling the root → the app still starts and `/api/agent/chat` returns a clear error (400).
Structurally invalid declarations (cycle, duplicate id) → **fail-fast** at startup with an explicit message.
A declared sub-agent whose module is not on the classpath is **not** an error: it is dropped and listed
under `missingSubAgents` in `/actuator/agents`.

## Module boundaries (agents vs runtime)

| Module | Contents | Rule |
|---|---|---|
| `panic-core` | agent runtime, SPI, registry, executor, pipeline, memory, prompt/skill repositories | **framework only** — no agent, no business logic |
| `panic-agents-core` | agents of the base product (currently only the `coordinator`) | no domain features |
| `panic-agent-example` | `echo` — reference implementation of an agent module | template to copy |
| `panic-app` | entry point, configuration, UI build, installed modules | installs agents |

The runtime scans `classpath*:agents/*/agent.yaml` on the whole classpath, so **installing an agent =
adding its module dependency** to `panic-app/pom.xml`. A missing module is not an error: the parent
just drops that sub-agent (visible in `/actuator/agents` as `missingSubAgents`).

`panic-core` may still be edited when a feature needs a **new capability** (tool streaming, per-tenant
memory, a guardrail hook, a new SPI). It must not be edited for one agent's prompt, tools or domain logic.

## Adding a new agent (new module, 4 steps)

Copy the layout of `panic-agent-example`:

1. `src/main/resources/agents/<name>/agent.yaml` — `id`, `description`, `prompt`, `skills`,
   `subAgents`, `tools: [local:<tool-source-id>]`.
2. `src/main/resources/agents/<name>/system.md` — the prompt (`{now}`, `{today}`, `{tools}`,
   `{subAgents}`, `{skills}` are rendered every turn).
3. `src/main/java/.../<Name>Tools.java` with `@Tool` methods, plus a config with two beans:

   ```java
   @Configuration
   @ConditionalOnProperty(name = "app.agents.greeting.enabled", havingValue = "true", matchIfMissing = true)
   public class GreetingAgentConfig {

       @Bean
       public AgentDefinition greetingAgent() {
           return AgentDefinition.builder("greeting")
                   .description("Greets users and answers general questions.")
                   .promptFile("agents/greeting/system.md")
                   .build();
       }

       @Bean
       public AgentToolSources greetingToolSources(GreetingTools tools) {
           return new AgentToolSources().local(LocalToolSource.of("greeting-tools", tools));
       }
   }
   ```

   The `AgentDefinition` carries no tool objects: the `tools:` entries in `agent.yaml` are resolved by
   the loader against the `AgentToolSources` contributed by the same module.
4. Add the module dependency to `panic-app/pom.xml` and (optionally) `app.agents.<name>.enabled=true`.

To mount it under the coordinator add `greeting` to `subAgents` in
`panic-agents-core/src/main/resources/agents/coordinator/agent.yaml`. Nothing in `panic-core` changes,
and disabling it is a property flip.

Removing an agent = dropping the module dependency (or `app.agents.<name>.enabled=false`).

## MCP

The bundled coordinator declares no tools, so no MCP server is wired. The runtime still supports MCP:
the `McpToolSource` SPI can be wrapped around any `ToolCallbackProvider`, then mounted on an agent with
`.toolSources(...)` and referenced from `agent.yaml` as `tools: [mcp:<server>]`.

- Enable the client with `spring.ai.mcp.client.enabled=true` and declare the connection
  (`spring.ai.mcp.client.sse.connections.<name>.url`).
- Server unreachable → a single WARN log, the agent degrades, the app does not die.

## Skills

A skill = a markdown file with frontmatter under `classpath*:skills/*.md`:

```markdown
---
name: my-skill
description: Rules or procedures the agent can load on demand
mcpServers: []
---
<procedure content>
```

Agents declare `skillIds(...)`. The LLM only sees `name + description` in the prompt; when it needs details it calls
the `load_skill(skillId)` tool to load the full text (progressive disclosure). Missing `name` in the frontmatter → skipped + WARN.

## Requirements

- JDK 21+
- Maven 3.8+
- `OPENROUTER_API_KEY` (needed for agents to actually call the LLM; without it the app still starts and returns a clear error in chat)

## OpenRouter configuration (`panic-app/src/main/resources/application.properties`)

```properties
# Spring AI appends /v1/chat/completions itself, so base-url must NOT include /v1
spring.ai.openai.base-url=https://openrouter.ai/api
spring.ai.openai.api-key=${OPENROUTER_API_KEY:missing-openrouter-api-key}
spring.ai.openai.chat.options.model=${LLM_MODEL:${OPENROUTER_MODEL:openai/gpt-4o-mini}}
```

`OpenAiChatModel` cannot be initialised with an empty key, so a placeholder is used to let the app run without
a key; `AgentExecutor` treats the placeholder as "not configured" and reports an error in chat.

Environment variables: `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OPENROUTER_REFERER`, `OPENROUTER_TITLE`.


## Build and run

The repo is a Maven multi-module project: `panic-core` (pipeline + agent runtime),
`panic-agents-core` (coordinator), `panic-agent-example` (example agent module), `panic-app`
(entry point + static UI + installed agents).

```bash
mvn -q clean package                                   # build the whole reactor
java -jar panic-app/target/panic-app-1.0.0.jar
```

Or run a single module — **always with `-am`** so the latest core is built (without `-am` you get the stale JAR in `~/.m2`):

```bash
mvn -pl panic-app -am spring-boot:run
mvn -pl panic-core test                         # run core tests only
```

`dev.ps1` bundles the common commands:

```powershell
./dev.ps1 build      # mvn -o clean package
./dev.ps1 app        # run the app
./dev.ps1 test       # test core
./dev.ps1 ui         # build the frontend
```

The server listens on port 8080. H2 is file-based (`./data/panic`), so conversation memory survives a restart.

## Chat UI (React + Vite + Tailwind)

```bash
cd web
npm install
npm run dev        # http://localhost:5173, proxies /api -> http://localhost:8080
npm run build      # tsc + vite build -> ../panic-app/src/main/resources/static
```

After building, just restart `panic-app`; core is untouched.

- Every action goes through the agent; there is no business REST API.
- The conversation is stored in `localStorage`, with a clear-conversation button.
- A clear error is shown when `OPENROUTER_API_KEY` is missing.

## Realtime agent pipeline

```bash
GET /api/agent/pipeline/stream?traceId=<id>   # SSE: per-step pipeline events + done/error
```

- `POST /api/agent/chat` returns `text/event-stream`: the `start` frame (`traceId`) is flushed immediately and the final `result` frame holds the `reply` + the whole `pipeline`.
- Every event has a `parentId` (tree), plus `agentId` and `depth`; existing fields are unchanged so older UI code still works.
- A sub-agent tool call opens a `tool` event (actor = parent agent, title `Sub-agent <id>`); every step of the child agent nests under that event.

## Endpoints

```
POST /api/agent/chat                          # { "message": "..." } → SSE start/result
GET  /api/agent/pipeline/stream?traceId=<id>  # SSE of realtime steps
GET  /actuator/health
GET  /actuator/agents                         # runtime agent tree + tools + skills + policy
```

## Database

**H2 file-based** (`./data/panic`) so conversation memory survives a restart. To use PostgreSQL, change `spring.datasource.*` and add the PostgreSQL driver.

## Out of scope for the MVP

- Spring Security / JWT
- PII redaction, cost tracking, session store, conversation history
- WAF/DDoS/rate limiting at the edge
- Splitting each agent into its own JAR, hot-loading plugins, `search_tools`, per-tenant MCP sessions
- Mobile app / realtime multi-user support
