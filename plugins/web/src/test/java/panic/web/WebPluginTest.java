package panic.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.Context;
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
import panic.llm.Json;
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
import panic.tools.ToolResult;
import panic.tools.Tools;

class WebPluginTest {

  /** Streams "Hel" "lo <model>"; with {@code block}, waits after the first delta until cancelled. */
  static final class Fake implements Provider, ChatModel {
    volatile CountDownLatch block;
    /** When set, a question is first answered with a call to the {@code echo} tool. */
    volatile boolean useTool;
    /** With {@link #useTool}: the call also comes in two pieces before the completion. */
    volatile boolean streamCalls;
    final CountDownLatch cancelled = new CountDownLatch(1);

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
        return new Completion("", r.model(), "tool_calls", new Usage(1, 1),
            List.of(new ToolCall("t1", "echo", "{\"text\":\"ping\"}")));
      }
      onDelta.accept("Hel");
      CountDownLatch b = block;
      if (b != null) {
        CountDownLatch release = new CountDownLatch(1);
        s.onCancel(() -> {
          cancelled.countDown();
          release.countDown();
        });
        b.countDown();
        try {
          release.await();
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
    root.plugin("conversations", Conversations.inMemory());
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
      if (name != null) out.add(new Event(name, Json.parseOrNull(data)));
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
        if (!answered && line.startsWith("data: ") && all.toString().endsWith("event: approve\n" + line + "\n")) {
          JsonNode q = Json.parseOrNull(line.substring(6));
          assertEquals(404, send("POST", "/api/approve", "{\"session\":\"other\",\"id\":\"" + q.path("id").asText()
              + "\",\"allow\":true}").statusCode(), "only the session that asked may answer");
          assertEquals(200, send("POST", "/api/approve", "{\"session\":\"t\",\"id\":\"" + q.path("id").asText()
              + "\",\"allow\":" + allow + "}").statusCode());
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

  @Test
  void closingTheConnectionCancelsTheAnswer() throws Exception {
    fake.block = new CountDownLatch(1);
    HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"session\":\"s\",\"text\":\"slow\"}")).build();
    HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8));
    assertEquals("event: delta", in.readLine());
    fake.block.await();
    in.close(); // the browser goes away (or Stop): the next heartbeat fails and cancels the answer
    assertTrue(fake.cancelled.await(5, TimeUnit.SECONDS), "the answer in flight was cancelled");
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
