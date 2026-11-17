package panic.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.Context;
import context.PluginHandle;
import context.Root;
import core.Signal;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversations;
import panic.command.Commands;
import panic.llm.ChatModel;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.util.Json;
import panic.llm.KeyCheck;
import panic.llm.LlmConfig;
import panic.llm.LlmPlugin;
import panic.llm.ModelInfo;
import panic.llm.Provider;
import panic.llm.Providers;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolCallDelta;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolProgress;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.trace.Span;
import panic.trace.Trace;

class WebPluginTest {

  /** Where plan files go: these conversations have no project. */
  @org.junit.jupiter.api.io.TempDir
  java.nio.file.Path plans;

  /**
   * Streams "Hel" "lo <model>"; with {@code block}, waits after the first delta until cancelled or until
   * {@code released}.
   */
  static final class Fake implements Provider, ChatModel {
    volatile CountDownLatch block;
    /** When set, a question is first answered with a call to the {@code echo} tool. */
    volatile boolean useTool;
    /** With {@link #useTool}: the call made instead of {@code echo {text: ping}}. */
    volatile ToolCall call;
    /** With {@link #useTool}: the call also comes in two pieces before the completion. */
    volatile boolean streamCalls;
    final CountDownLatch cancelled = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    @Override
    public String id() {
      return "fake";
    }

    @Override
    public ChatModel chat() {
      return this;
    }

    @Override
    public List<ModelInfo> models(Signal s) {
      return List.of(new ModelInfo("a:free", "A", 8000, true), new ModelInfo("b", "B", 0, false));
    }

    @Override
    public KeyCheck checkKey(Signal s) {
      return new KeyCheck(true, "fine");
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return stream(r, s, d -> { });
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta, Consumer<ToolCallDelta> onToolDelta) {
      Completion c = stream(r, s, onDelta);
      if (streamCalls && c.hasToolCalls()) {
        onToolDelta.accept(new ToolCallDelta(0, "t1", "echo", "{\"text\":"));
        onToolDelta.accept(new ToolCallDelta(0, null, null, "\"ping\"}"));
      }
      return c;
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      if (useTool && r.messages().get(r.messages().size() - 1).role() == Role.USER) {
        ToolCall c = call;
        return new Completion("", r.model(), "tool_calls", new Usage(1, 1),
            List.of(c != null ? c : new ToolCall("t1", "echo", "{\"text\":\"ping\"}")));
      }
      onDelta.accept("Hel");
      CountDownLatch b = block;
      if (b != null) {
        s.onCancel(() -> {
          cancelled.countDown();
          released.countDown();
        });
        b.countDown();
        try {
          released.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        s.throwIfCancelled();
      }
      onDelta.accept("lo " + r.model());
      return new Completion("Hello " + r.model(), r.model(), "stop", new Usage(2, 3));
    }
  }

  private final Fake fake = new Fake();
  private final HttpClient http = HttpClient.newHttpClient();
  private Root root;
  private PluginHandle conversations;
  private ConfigHandle<Boolean> connected;
  private ConfigHandle<LlmConfig> llm;
  private ConfigHandle<WebConfig> web;
  private ConfigHandle<ChatConfig> chat;
  private volatile String base;

  @BeforeEach
  void start() {
    root = Context.root("t");
    connected = root.plugin("fake", (ctx, on) -> {
      ctx.hook(Providers.STATUS, q -> new Providers.Status("fake", on));
      if (on) ctx.provide(Providers.key("fake"), fake);
      return null;
    }, true);
    llm = root.plugin("llm", new LlmPlugin(), new LlmConfig("fake", "m1", null, null));
    conversations = root.plugin("conversations", Conversations.inMemory());
    chat = root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
    root.plugin("settings", ctx -> {
      Commands.command(ctx, "provider [id]", "", c -> llm.update(llm.config().withProvider(c.arg("id"))));
      Commands.command(ctx, "model [id]", "", c -> llm.update(llm.config().withModel(c.arg("id"))));
      return null;
    });
    web = root.plugin("web", new WebPlugin(line -> base = line.substring("web: ".length()), Duration.ofMillis(100)),
        new WebConfig(true, "127.0.0.1", 0));
  }

  @AfterEach
  void stop() {
    root.dispose();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> send(String method, String path, String json) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
        .method(method, HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json").build();
    return http.send(req, HttpResponse.BodyHandlers.ofString());
  }

  record Event(String name, JsonNode data) {
  }

  private static List<Event> events(String body) {
    List<Event> out = new ArrayList<>();
    for (String block : body.split("\n\n")) {
      String name = null;
      String data = null;
      for (String line : block.split("\n")) {
        if (line.startsWith("event: ")) name = line.substring(7);
        if (line.startsWith("data: ")) data = line.substring(6);
      }
      // every answer starts with "start {question, startedAt}", for a page that resumes it; tested on its own
      if (name != null && !name.equals("start")) out.add(new Event(name, Json.parseOrNull(data)));
    }
    return out;
  }

  @Test
  void stateReflectsWhatIsRunning() throws Exception {
    JsonNode s = Json.parseOrNull(get("/api/state").body());
    assertEquals("fake", s.path("provider").asText());
    assertEquals("m1", s.path("model").asText());
    assertTrue(s.path("connected").asBoolean());
    assertEquals("fake", s.path("providers").path(0).path("id").asText());
    List<String> commands = new ArrayList<>();
    s.path("commands").forEach(c -> commands.add(c.path("name").asText()));
    assertTrue(commands.containsAll(List.of("ask", "check", "model", "models", "reset", "status")), commands.toString());

    connected.update(false);
    s = Json.parseOrNull(get("/api/state").body());
    assertFalse(s.path("connected").asBoolean());
    assertFalse(s.path("providers").path(0).path("connected").asBoolean());
    assertEquals(409, get("/api/models").statusCode());
  }

  @Test
  void chatStreamsAndKeepsHistoryPerSession() throws Exception {
    List<Event> ev = events(send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"hi\"}").body());
    assertEquals(List.of("delta", "delta", "done"), ev.stream().map(Event::name).toList());
    assertEquals("Hel", ev.get(0).data().path("text").asText());
    assertEquals("m1", ev.get(2).data().path("model").asText());
    assertEquals(3, ev.get(2).data().path("usage").path("completionTokens").asInt());

    JsonNode history = Json.parseOrNull(get("/api/history?session=s1").body());
    assertEquals(2, history.size());
    assertEquals("assistant", history.path(1).path("role").asText());
    assertEquals(0, Json.parseOrNull(get("/api/history?session=other").body()).size());

    connected.update(false);
    assertEquals("unavailable", events(send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"hi\"}").body()).get(0).name());
  }

  /** Keeps spans in memory and answers the trace hooks, like the {@code tracing} plugin. */
  private void tracer() {
    List<Span> spans = new java.util.concurrent.CopyOnWriteArrayList<>();
    root.plugin("tracer", ctx -> {
      ctx.on(Trace.SPAN, spans::add);
      ctx.hook(Trace.GET, q -> {
        List<Span> t = spans.stream().filter(s -> s.traceId().equals(q.traceId())).toList();
        return t.isEmpty() ? null : new Trace.Spans(t.reversed()); // the root, which ends last, first
      });
      ctx.hook(Trace.LIST, q -> new Trace.Roots(spans.stream()
          .filter(s -> s.root() && q.conversationId().equals(s.stringAttr(Trace.CONVERSATION)))
          .toList().reversed()));
      return null;
    });
  }

  private static String startTraceId(String body) {
    for (String block : body.split("\n\n")) {
      if (block.contains("event: start\n")) {
        return Json.parseOrNull(block.substring(block.indexOf("data: ") + 6)).path("traceId").asText();
      }
    }
    throw new AssertionError("no start event in " + body);
  }

  @Test
  void eachAnswerNamesItsTraceWhichTheApiServes() throws Exception {
    tracer();
    String body = send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"hi\"}").body();
    String id = startTraceId(body);
    List<Event> ev = events(body);
    assertEquals(id, ev.get(ev.size() - 1).data().path("traceId").asText(), "done names the same trace");

    HttpResponse<String> r = get("/api/trace?session=s1&id=" + id);
    assertEquals(200, r.statusCode(), r.body());
    JsonNode t = Json.parseOrNull(r.body());
    assertEquals(id, t.path("traceId").asText());
    JsonNode turn = t.path("spans").path(0);
    assertEquals("turn", turn.path("name").asText());
    assertEquals("ok", turn.path("status").asText());
    assertTrue(turn.path("parentId").isNull());
    assertEquals(Trace.questionHash("hi"), turn.path("attrs").path(Trace.QUESTION_HASH).asText());
    assertEquals("llm", t.path("spans").path(1).path("name").asText());
    assertEquals(turn.path("id").asText(), t.path("spans").path(1).path("parentId").asText());
    assertEquals(3, t.path("spans").path(1).path("attrs").path("completionTokens").asInt());

    assertEquals(404, get("/api/trace?session=s1&id=nope").statusCode());
    assertTrue(get("/api/trace?session=s1&id=nope").body().contains("no such trace"));
    assertEquals(400, get("/api/trace?session=s1").statusCode());
  }

  @Test
  void withoutATracerTracesAreNotFound() throws Exception {
    String body = send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"hi\"}").body();
    HttpResponse<String> r = get("/api/trace?session=s1&id=" + startTraceId(body));
    assertEquals(404, r.statusCode());
    assertTrue(r.body().contains("tracing is off"), r.body());
    assertFalse(Json.parseOrNull(get("/api/history?session=s1").body()).path(0).has("traceId"));
  }

  @Test
  void historyQuestionsKnowTheirTraces() throws Exception {
    send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"untraced\"}");
    tracer();
    List<String> ids = new ArrayList<>();
    for (String q : List.of("a", "b", "a")) {
      ids.add(startTraceId(send("POST", "/api/chat", "{\"session\":\"s1\",\"text\":\"" + q + "\"}").body()));
    }
    List<String> linked = new ArrayList<>();
    for (JsonNode m : Json.parseOrNull(get("/api/history?session=s1").body())) {
      if (m.path("role").asText().equals("user")) linked.add(m.path("traceId").asText("-"));
    }
    assertEquals(List.of("-", ids.get(0), ids.get(1), ids.get(2)), linked,
        "the same question asked twice keeps each its own trace; one asked before tracing has none");
  }

  @Test
  void reviewVerdictsAreStreamedBetweenTheAttempts() throws Exception {
    chat.update(new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, null, null, 1));
    root.plugin("reviewer", ctx -> {
      ctx.hook(Chat.REVIEW, r -> new Chat.Verdict(r.attempt() > 1, r.attempt() > 1 ? "fine" : "no tests", null));
      return null;
    });
    List<Event> ev = events(send("POST", "/api/chat", "{\"session\":\"r\",\"text\":\"hi\"}").body());
    assertEquals(List.of("delta", "delta", "review", "delta", "delta", "review", "done"),
        ev.stream().map(Event::name).toList());
    JsonNode rejected = ev.get(2).data();
    assertFalse(rejected.path("pass").asBoolean());
    assertEquals("no tests", rejected.path("reason").asText());
    assertEquals(1, rejected.path("attempt").asInt());
    assertTrue(ev.get(5).data().path("pass").asBoolean());
    assertEquals("fine", ev.get(6).data().path("review").asText());

    JsonNode history = Json.parseOrNull(get("/api/history?session=r").body());
    assertEquals(List.of("user", "assistant", "review", "assistant"),
        Json.MAPPER.convertValue(history.findValuesAsText("role"), List.class), history.toString());
    assertEquals("no tests", history.path(2).path("reason").asText().lines().findFirst().orElse(""));
  }

  /** Offers {@code echo} with risk write, so every call of it asks the browser. */
  private void writingEcho() {
    root.plugin("echo", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("echo", "", null);
        }

        @Override
        public Risk risk() {
          return Risk.WRITE;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          return ToolResult.ok("pong");
        }
      });
      return null;
    });
  }

  /** Asks in session t and answers the approval question with {@code allow}; returns every event. */
  private List<Event> chatAndApprove(boolean allow) throws Exception {
    return chatAndAnswer("approve", "/api/approve", "\"allow\":" + allow);
  }

  /**
   * Asks in session t and answers the first SSE {@code event} by posting {@code {session, id, <answer>}} to
   * {@code endpoint} (after checking another session may not); returns every event.
   */
  private List<Event> chatAndAnswer(String event, String endpoint, String answer) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"t\",\"text\":\"hi\"}"))
        .header("Content-Type", "application/json").build();
    HttpResponse<Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
    StringBuilder all = new StringBuilder();
    boolean answered = false;
    try (Stream<String> body = res.body()) {
      Iterator<String> lines = body.iterator();
      while (lines.hasNext()) {
        String line = lines.next();
        all.append(line).append('\n');
        if (!answered && line.startsWith("data: ") && all.toString().endsWith("event: " + event + "\n" + line + "\n")) {
          JsonNode q = Json.parseOrNull(line.substring(6));
          assertEquals(404, send("POST", endpoint, "{\"session\":\"other\",\"id\":\"" + q.path("id").asText()
              + "\"," + answer + "}").statusCode(), "only the session that asked may answer");
          assertEquals(200, send("POST", endpoint, "{\"session\":\"t\",\"id\":\"" + q.path("id").asText()
              + "\"," + answer + "}").statusCode());
          answered = true;
        }
      }
    }
    return events(all.toString());
  }

  @Test
  void theBrowserApprovesToolCalls() throws Exception {
    fake.useTool = true;
    writingEcho();
    List<Event> ev = chatAndApprove(true);
    assertEquals(List.of("tool", "approve", "tool", "delta", "delta", "done"), ev.stream().map(Event::name).toList());
    JsonNode q = ev.get(1).data();
    assertEquals("echo", q.path("name").asText());
    assertEquals("write", q.path("risk").asText());
    assertEquals("{\"text\":\"ping\"}", q.path("arguments").asText());
    assertEquals("ok", ev.get(2).data().path("status").asText());
    assertEquals("pong", ev.get(2).data().path("content").asText());
  }

  /**
   * Offers {@code echo} standing for a sub-agent: it writes text, then makes a {@code write} call (risk write)
   * in its own conversation, asked in the user's, and reports both as progress.
   */
  private void subAgentEcho() {
    root.plugin("echo", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("write", "", null);
        }

        @Override
        public Risk risk() {
          return Risk.WRITE;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          return ToolResult.ok("written");
        }
      });
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("echo", "", null);
        }

        @Override
        public Risk risk() {
          return Risk.READ;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          ToolCall inner = new ToolCall("w1", "write", "{}");
          tc.progress().accept(ToolProgress.text("explore", "looking"));
          tc.progress().accept(ToolProgress.step("explore", inner, null));
          ToolResult r = Tools.call(ctx, inner, new ToolContext(tc.conversationId() + "~explore-1", tc.signal(), null,
              tc.origin(), "explore", null, null, null));
          tc.progress().accept(ToolProgress.step("explore", inner, r));
          return ToolResult.ok("pong");
        }
      });
      return null;
    });
  }

  @Test
  void aSubAgentsWorkIsStreamedUnderItsCall() throws Exception {
    fake.useTool = true;
    subAgentEcho();
    List<Event> ev = chatAndApprove(true);
    assertEquals(List.of("tool", "agent_delta", "tool", "approve", "tool", "tool", "delta", "delta", "done"),
        ev.stream().map(Event::name).toList());
    assertFalse(ev.get(0).data().has("parent"));
    assertEquals("{\"parent\":\"t1\",\"agent\":\"explore\",\"text\":\"looking\"}", ev.get(1).data().toString());
    JsonNode running = ev.get(2).data();
    assertEquals("w1", running.path("id").asText());
    assertEquals("t1", running.path("parent").asText());
    assertEquals("explore", running.path("agent").asText());
    JsonNode q = ev.get(3).data();
    assertEquals("w1", q.path("callId").asText());
    assertEquals("explore", q.path("agent").asText());
    assertEquals("ok", ev.get(4).data().path("status").asText());
    assertEquals("t1", ev.get(4).data().path("parent").asText());
    assertEquals("t1", ev.get(5).data().path("id").asText());
    assertEquals("pong", ev.get(5).data().path("content").asText());
  }

  @Test
  void theBrowserDeniesToolCalls() throws Exception {
    fake.useTool = true;
    writingEcho();
    List<Event> ev = chatAndApprove(false);
    assertEquals("error", ev.get(2).data().path("status").asText());
    assertEquals("echo: denied by the user", ev.get(2).data().path("content").asText());
    assertEquals("done", ev.get(ev.size() - 1).name());
  }

  @Test
  void unknownApprovalsAreNotFound() throws Exception {
    assertEquals(404, send("POST", "/api/approve", "{\"session\":\"t\",\"id\":\"nope\",\"allow\":true}").statusCode());
    assertEquals(400, send("POST", "/api/approve", "{\"id\":\"nope\"}").statusCode());
    assertEquals(405, get("/api/approve").statusCode());
  }

  @Test
  void toolCallsAreStreamedAndKeptInHistory() throws Exception {
    fake.useTool = true;
    fake.streamCalls = true;
    root.plugin("echo", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("echo", "", null);
        }

        @Override
        public Risk risk() {
          return Risk.READ;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          return ToolResult.ok("pong");
        }
      });
      return null;
    });
    List<Event> ev = events(send("POST", "/api/chat", "{\"session\":\"t\",\"text\":\"hi\"}").body());
    assertEquals(List.of("tool_delta", "tool_delta", "tool", "tool", "delta", "delta", "done"),
        ev.stream().map(Event::name).toList());
    assertEquals("{\"index\":0,\"id\":\"t1\",\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\"}",
        ev.get(0).data().toString());
    assertEquals("{\"index\":0,\"arguments\":\"\\\"ping\\\"}\"}", ev.get(1).data().toString());
    assertEquals("running", ev.get(2).data().path("status").asText());
    assertEquals("echo", ev.get(2).data().path("name").asText());
    assertEquals("ok", ev.get(3).data().path("status").asText());
    assertEquals("pong", ev.get(3).data().path("content").asText());
    assertEquals(1, ev.get(6).data().path("toolCalls").asInt());
    assertEquals("done", ev.get(6).data().path("stop").asText());

    JsonNode history = Json.parseOrNull(get("/api/history?session=t").body());
    assertEquals(List.of("user", "assistant", "tool", "assistant"),
        Json.MAPPER.convertValue(history.findValuesAsText("role"), List.class), history.toString());
    JsonNode call = history.path(1).path("toolCalls").path(0);
    assertEquals("t1", call.path("id").asText());
    assertEquals("echo", call.path("name").asText());
    assertEquals("t1", history.path(2).path("toolCallId").asText());
    assertEquals("ok", history.path(2).path("status").asText());
    assertEquals("pong", history.path(2).path("content").asText());
    assertEquals("Hello m1", history.path(3).path("content").asText());
  }

  @Test
  void commandsRunLikeInTheCli() throws Exception {
    List<Event> ev = events(send("POST", "/api/command", "{\"session\":\"s1\",\"line\":\"/models free\"}").body());
    assertEquals("delta", ev.get(0).name());
    assertTrue(ev.get(0).data().path("text").asText().contains("a:free"));
    assertEquals("done", ev.get(ev.size() - 1).name());

    assertEquals("unknown", events(send("POST", "/api/command", "{\"session\":\"s1\",\"line\":\"nope\"}").body()).get(0).name());
    Event usage = events(send("POST", "/api/command", "{\"session\":\"s1\",\"line\":\"reset now\"}").body()).get(0);
    assertEquals("usage", usage.name());
    assertEquals("reset", usage.data().path("usage").asText());
  }

  @Test
  void settingsSwitchTheModel() throws Exception {
    HttpResponse<String> r = send("PUT", "/api/settings", "{\"model\":\"m2\"}");
    assertEquals(200, r.statusCode());
    assertEquals("m2", Json.parseOrNull(r.body()).path("model").asText());
    List<Event> ev = events(send("POST", "/api/chat", "{\"session\":\"s\",\"text\":\"x\"}").body());
    assertEquals("lo m2", ev.get(1).data().path("text").asText());
    assertEquals(400, send("PUT", "/api/settings", "{\"provider\":\"nope\"}").statusCode());
  }

  @Test
  void badRequests() throws Exception {
    assertEquals(400, send("POST", "/api/chat", "{\"session\":\"../x\",\"text\":\"hi\"}").statusCode());
    assertEquals(400, send("POST", "/api/chat", "{\"session\":\"s\",\"text\":\"  \"}").statusCode());
    assertEquals(400, send("POST", "/api/chat", "not json").statusCode());
    assertEquals(405, get("/api/chat").statusCode());
    assertEquals(404, get("/api/nope").statusCode());
  }

  @Test
  void staticFilesFallBackToIndexForClientRoutes() throws Exception {
    HttpResponse<String> page = get("/some/client/route");
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("<title>Panic</title>"), page.body());
    assertEquals(404, get("/missing.js").statusCode());
    assertEquals(400, get("/..%2F..%2Fetc/passwd").statusCode());
  }

  /** Opens an answer in session {@code s} and reads it up to the first delta ("Hel"), which the fake then holds. */
  private BufferedReader slowAnswer() throws Exception {
    fake.block = new CountDownLatch(1);
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"s\",\"text\":\"slow\"}")).build();
    HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, r.statusCode());
    BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
    assertEquals(List.of("id: 1", "event: start"), List.of(in.readLine(), in.readLine()));
    String start = in.readLine();
    assertTrue(start.contains("\"question\":\"slow\"") && start.contains("\"traceId\":"), start);
    assertEquals(List.of("", "id: 2", "event: delta", "data: {\"text\":\"Hel\"}", ""),
        List.of(in.readLine(), in.readLine(), in.readLine(), in.readLine(), in.readLine()));
    fake.block.await();
    return in;
  }

  private BufferedReader follow(String query) throws Exception {
    HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/stream?" + query)).build(),
        HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, r.statusCode());
    return new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
  }

  private static String rest(BufferedReader in) throws Exception {
    StringBuilder out = new StringBuilder();
    for (String line; (line = in.readLine()) != null; ) out.append(line).append('\n');
    return out.toString();
  }

  @Test
  void anAnswerOutlivesItsConnectionAndCanBeResumed() throws Exception {
    slowAnswer().close(); // the page reloads
    assertFalse(fake.cancelled.await(500, TimeUnit.MILLISECONDS), "leaving does not stop the answer");

    try (BufferedReader in = follow("session=s&after=1")) {
      assertEquals(List.of("id: 2", "event: delta"), List.of(in.readLine(), in.readLine()), "only what came after 1");
      in.readLine();
      in.readLine();
      fake.released.countDown();
      String body = rest(in);
      assertTrue(body.startsWith("id: 3\nevent: delta\ndata: {\"text\":\"lo m1\"}"), body);
      assertEquals(List.of("delta", "done"), events(body).stream().map(Event::name).toList());
    }
    assertEquals(204, get("/api/stream?session=s").statusCode(), "nothing runs any more");
    assertEquals(2, Json.parseOrNull(get("/api/history?session=s").body()).size());
  }

  @Test
  void conversationsAreListedAndDeleted() throws Exception {
    assertEquals("[]", get("/api/conversations").body());
    send("POST", "/api/chat", "{\"session\":\"one\",\"text\":\"first chat\"}").body();
    Thread.sleep(20);
    send("POST", "/api/chat", "{\"session\":\"two\",\"text\":\"second chat\\nmore\"}").body();

    JsonNode list = Json.parseOrNull(get("/api/conversations").body());
    assertEquals(List.of("two", "one"), Json.MAPPER.convertValue(list.findValuesAsText("id"), List.class));
    assertEquals("second chat", list.path(0).path("title").asText());
    assertTrue(list.path(0).path("updatedAt").asLong() >= list.path(1).path("updatedAt").asLong());

    assertEquals(200, send("DELETE", "/api/conversations?session=one", "").statusCode());
    assertEquals(List.of("two"),
        Json.MAPPER.convertValue(Json.parseOrNull(get("/api/conversations").body()).findValuesAsText("id"), List.class));
    assertEquals(0, Json.parseOrNull(get("/api/history?session=one").body()).size());
    assertEquals(404, send("DELETE", "/api/conversations?session=one", "").statusCode());
    assertEquals(400, send("DELETE", "/api/conversations?session=..", "").statusCode());
    assertEquals(405, send("PUT", "/api/conversations", "{}").statusCode());
  }

  @Test
  void bypassStateComesFromThePermissionsHookAnd503WithoutAnswer() throws Exception {
    assertEquals(503, get("/api/permissions?session=s").statusCode());
    root.plugin("permissions", new panic.tools.PermissionsPlugin(), new panic.tools.PermissionsConfig(null, null, 0, false));
    JsonNode off = Json.parseOrNull(get("/api/permissions?session=s").body());
    assertFalse(off.path("bypass").asBoolean());
    assertFalse(off.path("default").asBoolean());
    send("POST", "/api/command", "{\"session\":\"s\",\"line\":\"bypass on\"}");
    assertTrue(Json.parseOrNull(get("/api/permissions?session=s").body()).path("bypass").asBoolean());
    assertFalse(Json.parseOrNull(get("/api/permissions?session=other").body()).path("bypass").asBoolean());
    assertEquals(400, get("/api/permissions?session=..").statusCode());
    assertEquals(405, send("PUT", "/api/permissions", "{}").statusCode());
  }

  @Test
  void planModeComesFromThePlannerHookAnd503WithoutAnswer() throws Exception {
    assertEquals(503, get("/api/mode?session=s").statusCode());
    root.plugin("planner", new panic.planner.PlannerPlugin(), new panic.planner.PlannerConfig(false, null, 0, null, plans.toString()));
    JsonNode off = Json.parseOrNull(get("/api/mode?session=s").body());
    assertFalse(off.path("plan").asBoolean());
    assertFalse(off.path("default").asBoolean());
    send("POST", "/api/command", "{\"session\":\"s\",\"line\":\"plan on\"}");
    assertTrue(Json.parseOrNull(get("/api/mode?session=s").body()).path("plan").asBoolean());
    assertFalse(Json.parseOrNull(get("/api/mode?session=other").body()).path("plan").asBoolean());
    assertEquals(400, get("/api/mode?session=..").statusCode());
    assertEquals(405, send("PUT", "/api/mode", "{}").statusCode());
  }

  /** Session t in plan mode, with a model that first proposes a plan. */
  private void planning() throws Exception {
    root.plugin("planner", new panic.planner.PlannerPlugin(), new panic.planner.PlannerConfig(false, null, 0, null, plans.toString()));
    send("POST", "/api/command", "{\"session\":\"t\",\"line\":\"plan on\"}");
    fake.useTool = true;
    fake.call = new ToolCall("p1", panic.planner.Planner.EXIT_TOOL,
        "{\"plan\":\"# Plan\\nfix it\",\"items\":[\"look\",\"fix\"],\"criteria\":[\"fixed\"]}");
  }

  @Test
  void theBrowserApprovesAPlan() throws Exception {
    planning();
    List<Event> ev = chatAndAnswer("plan", "/api/plan", "\"approve\":true");
    assertEquals(List.of("tool", "plan", "tool", "delta", "delta", "done"), ev.stream().map(Event::name).toList());
    JsonNode q = ev.get(1).data();
    assertEquals("# Plan\nfix it", q.path("plan").asText());
    assertEquals("[\"look\",\"fix\"]", q.path("items").toString());
    assertEquals("[\"fixed\"]", q.path("criteria").toString());
    assertEquals("ok", ev.get(2).data().path("status").asText());
    assertFalse(Json.parseOrNull(get("/api/mode?session=t").body()).path("plan").asBoolean(), "plan mode ended");
  }

  @Test
  void theBrowserSendsAPlanBackWithFeedback() throws Exception {
    planning();
    List<Event> ev = chatAndAnswer("plan", "/api/plan", "\"approve\":false,\"feedback\":\"smaller\"");
    assertEquals("error", ev.get(2).data().path("status").asText());
    assertTrue(ev.get(2).data().path("content").asText().contains("did not approve the plan: smaller"));
    assertTrue(Json.parseOrNull(get("/api/mode?session=t").body()).path("plan").asBoolean(), "still planning");
    assertEquals(404, send("POST", "/api/plan", "{\"session\":\"t\",\"id\":\"nope\",\"approve\":true}").statusCode());
    assertEquals(405, get("/api/plan").statusCode());
  }

  /** A model that first asks the user which cache to use. */
  private void questioning() {
    root.plugin("ask-user", new panic.interact.AskUserPlugin(), panic.interact.AskUserConfig.DEFAULT);
    fake.useTool = true;
    fake.call = new ToolCall("q1", panic.interact.AskUser.TOOL, "{\"questions\":[{\"question\":\"Which cache?\","
        + "\"header\":\"Cache\",\"options\":[{\"label\":\"Redis\",\"description\":\"shared\"},{\"label\":\"Memory\"}]}]}");
  }

  @Test
  void theBrowserAnswersTheAgentsQuestions() throws Exception {
    questioning();
    List<Event> ev = chatAndAnswer("question", "/api/answer", "\"answers\":[{\"selected\":[\"Redis\"],\"other\":\"v7\"}]");
    assertEquals(List.of("tool", "question", "tool", "delta", "delta", "done"), ev.stream().map(Event::name).toList());
    JsonNode q = ev.get(1).data().path("questions").get(0);
    assertEquals("Which cache?", q.path("question").asText());
    assertEquals("Cache", q.path("header").asText());
    assertFalse(q.path("multiSelect").asBoolean());
    assertEquals("shared", q.path("options").get(0).path("description").asText());
    assertEquals("ok", ev.get(2).data().path("status").asText());
    assertEquals("The user answered:\n\"Which cache?\" → Redis (other: v7)", ev.get(2).data().path("content").asText());
  }

  @Test
  void badAnswersAreRefusedAndTheQuestionStaysOpen() throws Exception {
    questioning();
    List<Integer> codes = new java.util.concurrent.CopyOnWriteArrayList<>();
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"t\",\"text\":\"hi\"}")).build();
    HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
      String id = null;
      for (String line; (line = in.readLine()) != null; ) {
        if (id == null && line.startsWith("data: ") && line.contains("\"questions\"")) {
          id = Json.parseOrNull(line.substring(6)).path("id").asText();
          String head = "{\"session\":\"t\",\"id\":\"" + id + "\",";
          codes.add(send("POST", "/api/answer", head + "\"answers\":[]}").statusCode());
          codes.add(send("POST", "/api/answer", head + "\"answers\":[{\"selected\":[\"Nope\"]}]}").statusCode());
          codes.add(send("POST", "/api/answer", head + "\"answers\":[{\"selected\":[\"Redis\",\"Memory\"]}]}").statusCode());
          codes.add(send("POST", "/api/answer", head + "\"answers\":[{\"selected\":[]}]}").statusCode()); // skip
        }
        if (line.startsWith("data: ") && line.contains("did not answer (skipped)")) codes.add(0);
      }
    }
    assertEquals(List.of(400, 400, 400, 200, 0), codes);
    assertEquals(404, send("POST", "/api/answer", "{\"session\":\"t\",\"id\":\"nope\",\"answers\":[]}").statusCode());
    assertEquals(405, get("/api/answer").statusCode());
  }

  @Test
  void workspacesAreListedFromTheHookAnd503WithoutAnswer() throws Exception {
    assertEquals(503, get("/api/workspaces").statusCode());
    root.hook(panic.tools.Workspace.LIST, q -> new panic.tools.Workspace.Projects(
        List.of(new panic.tools.Workspace.Project(java.nio.file.Path.of("/w/meeting-room").toAbsolutePath(),
            "meeting-room", List.of("one", "bad id!"))), List.of("old")));
    HttpResponse<String> r = get("/api/workspaces");
    assertEquals(200, r.statusCode());
    JsonNode body = Json.parseOrNull(r.body());
    assertEquals("meeting-room", body.path("projects").path(0).path("name").asText());
    assertEquals(List.of("one"),
        Json.MAPPER.convertValue(body.path("projects").path(0).path("conversations"), List.class),
        "ids that are not valid sessions are left out");
    assertEquals(List.of("old"), Json.MAPPER.convertValue(body.path("hidden"), List.class));
    assertEquals(405, send("PUT", "/api/workspaces", "{}").statusCode());
  }

  @Test
  void webKeepsRunningWhenConversationsStopAndAnswersUnavailable() throws Exception {
    String before = base;
    conversations.dispose();
    assertEquals(before, base, "the server was not restarted");
    assertEquals(503, get("/api/conversations").statusCode());
    assertEquals(503, get("/api/history?session=s1").statusCode());
    assertEquals(503, send("DELETE", "/api/conversations?session=s1", "").statusCode());
  }

  @Test
  void aConversationWithAnAnswerRunningCannotBeDeleted() throws Exception {
    try (BufferedReader in = slowAnswer()) {
      assertEquals(409, send("DELETE", "/api/conversations?session=s", "").statusCode());
      fake.released.countDown();
      rest(in);
    }
    assertEquals(200, send("DELETE", "/api/conversations?session=s", "").statusCode());
  }

  @Test
  void aSessionRunsOneAnswerAtATime() throws Exception {
    try (BufferedReader in = slowAnswer()) {
      assertEquals(409, send("POST", "/api/chat", "{\"session\":\"s\",\"text\":\"again\"}").statusCode());
      assertEquals(204, get("/api/stream?session=other").statusCode());
      fake.released.countDown();
      assertEquals(List.of("delta", "done"), events(rest(in)).stream().map(Event::name).toList());
    }
  }

  @Test
  void stopCancelsTheAnswer() throws Exception {
    try (BufferedReader in = slowAnswer()) {
      assertEquals(404, send("POST", "/api/stop", "{\"session\":\"other\"}").statusCode());
      assertEquals(200, send("POST", "/api/stop", "{\"session\":\"s\"}").statusCode());
      assertTrue(fake.cancelled.await(5, TimeUnit.SECONDS));
      assertEquals(List.of("cancelled"), events(rest(in)).stream().map(Event::name).toList());
    }
  }

  @Test
  void anAnswerNobodyWatchesIsStopped() throws Exception {
    web.update(new WebConfig(true, "127.0.0.1", 0, 0));
    slowAnswer().close(); // the next heartbeat fails, and with detachSeconds 0 the answer stops
    assertTrue(fake.cancelled.await(5, TimeUnit.SECONDS), "the answer in flight was cancelled");
  }

  @Test
  void anAbandonedAnswerStopsAfterDetachSeconds() throws Exception {
    web.update(new WebConfig(true, "127.0.0.1", 0, 1));
    slowAnswer().close();
    assertFalse(fake.cancelled.await(300, TimeUnit.MILLISECONDS), "still within detachSeconds");
    assertTrue(fake.cancelled.await(5, TimeUnit.SECONDS), "stopped once nobody came back");
  }

  @Test
  void anApprovalAskedWhileAwayIsAskedAgainOnResume() throws Exception {
    fake.useTool = true;
    writingEcho();
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"t\",\"text\":\"hi\"}")).build();
    HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
      while (!"event: approve".equals(in.readLine())) {
        // up to the question
      }
    } // the page goes before answering

    try (BufferedReader in = follow("session=t&after=0")) {
      String id = null;
      List<String> names = new ArrayList<>();
      for (String line; (line = in.readLine()) != null; ) {
        if (line.startsWith("event: ")) names.add(line.substring(7));
        if (id == null && line.startsWith("data: ") && names.get(names.size() - 1).equals("approve")) {
          id = Json.parseOrNull(line.substring(6)).path("id").asText();
          assertEquals(200, send("POST", "/api/approve", "{\"session\":\"t\",\"id\":\"" + id + "\",\"allow\":true}")
              .statusCode());
        }
      }
      assertEquals(List.of("start", "tool", "approve", "tool", "delta", "delta", "done"), names);
    }
  }

  @Test
  void disposingTheWebPluginCancelsAnswersInFlightAndWaits() throws Exception {
    fake.block = new CountDownLatch(1);
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"s\",\"text\":\"slow\"}")).build();
    var pending = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
    fake.block.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> web.update(new WebConfig(false, "127.0.0.1", 0)));
    assertTrue(fake.cancelled.await(0, TimeUnit.SECONDS), "cancelled before the update returned");
    pending.cancel(true);
  }

  @Test
  void disablingStopsTheServer() throws Exception {
    String was = base;
    web.update(new WebConfig(false, "127.0.0.1", 0));
    assertThrows(ConnectException.class, () -> http.send(HttpRequest.newBuilder(URI.create(was + "/api/state")).build(),
        HttpResponse.BodyHandlers.ofString()));
  }
}
