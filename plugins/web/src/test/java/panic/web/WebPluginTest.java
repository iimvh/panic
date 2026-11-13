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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import panic.llm.Usage;

class WebPluginTest {

  /** Streams "Hel" "lo <model>"; with {@code block}, waits after the first delta until cancelled. */
  static final class Fake implements Provider, ChatModel {
    volatile CountDownLatch block;
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
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
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
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 0));
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
