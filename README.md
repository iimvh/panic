# Meeting Room Booking (Spring Boot + Spring AI + OpenRouter)

A small meeting room booking system following the architecture in `idea.md`, built with **Spring Boot 3.x** and **Spring AI**, using **OpenRouter** as the LLM provider.

Every agent is a **self-describing plugin**; there is only **one `Agent` type** used by both coordinators and leaf agents, nesting recursively without limits.

## Architecture

| Component | Implementation |
|---|---|
| User Interface | React + Vite + Tailwind SPA (`frontend/`, built into `meeting-room-app/src/main/resources/static`) — even the room list panel goes through an agent |
| API | `AgentController` (`POST /api/agent/chat`) — the user's only entry point |
| **Agent runtime** | `core.agent.runtime`: `AgentRegistry` (wires the tree), `AgentExecutor` (LLM + tool loop, recursion), `AgentTreeEndpoint` (`/actuator/agents`) |
| **Agent SPI** | `core.agent.spi`: `Agent`, `AgentDefinition`, `ToolSource`, `Skill`, `AgentPolicy`, `AgentContext` |
| Domain Agents | `agents.search`, `agents.booking`, `agents.cancellation`, `agents.catalog` (MCP) — one package per agent containing `*Tools` + the `AgentDefinition` bean |
| Tool Adapters | `MeetingRoomService`, `MeetingService` + Spring Data JPA repositories on H2 |
| Tool backend | local (`@Tool` / `ToolCallback`) or MCP server (`meeting-room-mcp-demo`) |
| Authorization | Room existence / status checks in the service layer |
| Guardrails | Service checks for missing roomId/title/organizer/past time + overlap guard; agents never invent roomId/meetingId; unbounded recursion is prevented with `depth` + `chain` |
| Observability | Actuator: `/actuator/health`, `/actuator/agents` |
| Data Sources | `meeting_rooms`, `meetings` (JPA, H2 in-memory) |

### What an agent consists of

```java
AgentDefinition.builder("search")
    .description("Find free meeting rooms by time, headcount and location.") // = what the parent LLM sees
    .systemPromptTemplate("... {now} {tools} {subAgents} {skills} ...")     // rendered EVERY turn
    .toolSources(LocalToolSource.of("search-tools", searchTools))           // local or MCP
    .subAgentIds("...")                                                     // empty = leaf agent
    .skillIds("booking-policy")
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
coordinator                  (no tools of its own, only sub-agents)
├── search                   search__searchRooms        + booking-policy skill
├── booking                  booking__bookMeeting
├── cancellation             cancellation__cancelMeeting
└── roomCatalog              roomCatalog__listRooms, roomCatalog__roomDetail  (MCP)
```

## Enabling / disabling agents

Each agent bean carries `@ConditionalOnProperty(app.agents.<id>.enabled)` (default `true`).
A disabled agent disappears from the registry, the prompt and the `call_<id>` tool; the app still starts.

```bash
APP_AGENTS_BOOKING_ENABLED=false mvn -pl meeting-room-app -am spring-boot:run
curl -s localhost:8080/actuator/agents   # booking is gone; the app is still UP
```

Disabling the root → the app still starts and `/api/agent/chat` returns a clear error (400).
Invalid declarations (unknown sub-agent id, cycle) → **fail-fast** at startup with an explicit message.

## Adding a new agent (3 steps)

1. Create `agents/<name>/<Name>Tools.java` containing `@Tool` methods.
2. Create `agents/<name>/<Name>AgentConfig.java`:

   ```java
   @Configuration
   public class GreetingAgentConfig {
       public static final String ID = "greeting";
       @Bean
       @ConditionalOnProperty(name = "app.agents.greeting.enabled", havingValue = "true", matchIfMissing = true)
       public AgentDefinition greetingAgent(GreetingTools tools) {
           return AgentDefinition.builder(ID)
                   .description("Greets users and answers general questions.")
                   .systemPromptTemplate("You are a greeting agent. The current time is {now}.")
                   .toolSources(LocalToolSource.of("greeting-tools", tools))
                   .build();
       }
   }
   ```
3. Add `"greeting"` to the coordinator's `subAgentIds(...)` (`CoordinatorAgentConfig`) and
   declare `app.agents.greeting.enabled=true`. No prompt change, no controller change.

Removing an agent = deleting it from `subAgentIds` (or toggling it off via the property).

## MCP

`meeting-room-mcp-demo` is a separate process serving the room catalog over MCP (SSE, port 8081).
The `roomCatalog` agent only uses `McpToolSource`, so catalog data can only come from MCP.

```bash
# window 1
mvn -pl meeting-room-mcp-demo -am spring-boot:run

# window 2
SPRING_PROFILES_ACTIVE=mcp mvn -pl meeting-room-app -am spring-boot:run
curl -s localhost:8080/actuator/agents   # roomCatalog has roomCatalog__listRooms, roomCatalog__roomDetail

# chat: "Show me the whole company room catalog"
```

- The `mcp` profile enables `spring.ai.mcp.client` and connects to `${MCP_CATALOG_URL:http://localhost:8081}`.
- The MCP client is **off by default**: without the profile `roomCatalog` runs without tools (the app is still UP);
  to remove it entirely set `app.agents.roomCatalog.enabled=false`.
- Server unreachable → a single WARN log, the agent degrades, the app does not die.
- Adding another MCP server: declare `spring.ai.mcp.client.sse.connections.<name>.url`, create an
  `McpToolSource("<name>", provider)` and mount it on an agent via `.toolSources(...)`.
- The current versions (Spring AI 1.0.0 / MCP SDK 0.10.0) support **SSE**; streamable-http needs a newer SDK.

## Skills

A skill = a markdown file with frontmatter under `classpath*:skills/*.md`:

```markdown
---
name: booking-policy
description: Company rules for choosing, booking and cancelling meeting rooms
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

## OpenRouter configuration (`meeting-room-app/src/main/resources/application.properties`)

```properties
# Spring AI appends /v1/chat/completions itself, so base-url must NOT include /v1
spring.ai.openai.base-url=https://openrouter.ai/api
spring.ai.openai.api-key=${OPENROUTER_API_KEY:missing-openrouter-api-key}
spring.ai.openai.chat.options.model=${LLM_MODEL:${OPENROUTER_MODEL:openai/gpt-4o-mini}}
```

`OpenAiChatModel` cannot be initialised with an empty key, so a placeholder is used to let the app run without
a key; `AgentExecutor` treats the placeholder as "not configured" and reports an error in chat.

Environment variables: `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OPENROUTER_REFERER`, `OPENROUTER_TITLE`,
`MCP_CATALOG_URL`, `RUN_MCP_IT=true` (enables the MCP integration test).

## Build and run

The repo is a Maven multi-module project: `meeting-room-core` (domain + pipeline + agent runtime),
`meeting-room-app` (entry point + static UI), `meeting-room-mcp-demo` (standalone MCP server).

```bash
mvn -q clean package                                   # build the whole reactor
java -jar meeting-room-app/target/meeting-room-app-1.0.0.jar
```

Or run a single module — **always with `-am`** so the latest core is built (without `-am` you get the stale JAR in `~/.m2`):

```bash
mvn -pl meeting-room-app -am spring-boot:run
mvn -pl meeting-room-core test                         # run core tests only
```

`dev.ps1` bundles the common commands:

```powershell
./dev.ps1 build      # mvn -o clean package
./dev.ps1 app        # run the app
./dev.ps1 mcp        # run the MCP demo (port 8081)
./dev.ps1 mcp-app    # run the demo then the app with the mcp profile
./dev.ps1 test       # test core
./dev.ps1 ui         # build the frontend
```

The server listens on port 8080. H2 is in-memory (`create-drop`), so data is lost on restart.

## Chat UI (React + Vite + Tailwind)

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173, proxies /api -> http://localhost:8080
npm run build      # tsc + vite build -> ../meeting-room-app/src/main/resources/static
```

After building, just restart `meeting-room-app`; core is untouched.

- Every action (including the room list in the sidebar) goes through the agent; there is no business REST API.
- The room list is parsed from `- id=... | name=... | capacity=... | location=...` lines returned by the agent.
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

**H2 in-memory** by default (`create-drop`). To use PostgreSQL, change `spring.datasource.*` and add the PostgreSQL driver.

## Out of scope for the MVP

- Spring Security / JWT
- PII redaction, cost tracking, session store, conversation history
- WAF/DDoS/rate limiting at the edge
- Splitting each agent into its own JAR, hot-loading plugins, `search_tools`, per-tenant MCP sessions
- Mobile app / realtime multi-user support
