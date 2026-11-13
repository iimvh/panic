package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import panic.cli.Cli;
import panic.command.Out;
import panic.llm.Json;

/** The whole app against a fake OpenAI-compatible server standing in for OpenRouter. */
class CliTest {

  private HttpServer server;
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", ex -> {
      try (ex) {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        String model = Json.parseOrNull(body).path("model").asText();
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        for (String piece : new String[] {"Hi", " from ", model}) {
          out.write(("data: {\"model\":\"" + model + "\",\"choices\":[{\"delta\":{\"content\":\"" + piece
              + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
  }

  /** panic.json with the fake server as OpenRouter. */
  private ObjectNode config(String key, String model, String systemPrompt) throws Exception {
    String chat = systemPrompt == null ? "" : ", \"chat\": {\"systemPrompt\": \"" + systemPrompt + "\"}";
    return new ConfigLoader(n -> null).parse("""
        {"openrouter": {"apiKey": "%s", "baseUrl": "%s"},
         "llm": {"provider": "openrouter", "model": "%s"}%s}""".formatted(key, url(), model, chat));
  }

  private static ObjectNode overrides(boolean repl) {
    ObjectNode o = Json.object();
    o.putObject("cli").put("repl", repl);
    o.putObject("conversations").put("persist", false); // no history left behind between runs
    return o;
  }

  /** The real app: every discovered plugin, the REPL reading {@code input}. */
  private String run(ObjectNode cfg, String input) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    Out out = text -> {
      synchronized (bytes) {
        bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
      }
    };
    CountDownLatch exit = new CountDownLatch(1);
    Panic panic = Panic.start(Panic.discover(), cfg, overrides(true),
        new Panic.Process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out, exit::countDown),
        (source, error) -> out.println("[error-handler] " + source + " " + error), w -> out.println(w));
    try {
      assertTrue(exit.await(20, TimeUnit.SECONDS), "repl did not exit:\n" + bytes);
    } finally {
      panic.dispose();
    }
    synchronized (bytes) {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }

  @Test
  void chatsStreamsRemembersAndSwitchesModel() throws Exception {
    String out = run(config("k", "model-a", "sys"), "hello\n/model model-b\nagain\n/reset\nfresh\n/exit\n");

    assertTrue(out.contains("(connected)"), out);
    assertTrue(out.contains("Hi from model-a"), out);
    assertTrue(out.contains("Hi from model-b"), out);
    assertEquals(3, bodies.size());
    // second request: sys, hello, reply, again
    assertEquals(4, Json.parseOrNull(bodies.get(1)).path("messages").size());
    assertEquals("Hi from model-a",
        Json.parseOrNull(bodies.get(1)).path("messages").path(2).path("content").asText());
    // after /reset: sys, fresh
    assertEquals(2, Json.parseOrNull(bodies.get(2)).path("messages").size());
    assertTrue(!out.contains("[error"), out);
  }

  @Test
  void withoutKeyTheBotSaysSoAndKeepsRunning() throws Exception {
    String out = run(config("", "m", null), "hello\n/provider nope\n/models\n/model\n/help\n");  // EOF also exits
    assertTrue(out.contains("[no LLM connected]"), out);
    assertTrue(out.contains("unknown provider 'nope'"), out);
    assertTrue(out.contains("(not connected)"), out);
    assertTrue(out.contains("unknown command /models"), out);
    assertTrue(out.contains("model: m"), out);
    assertTrue(out.contains("/help [command]") && !out.contains("/ask"), "help lists only what runs:\n" + out);
    assertEquals(0, bodies.size());
  }

  @Test
  void helpListsEveryPluginsCommandsWhenConnected() throws Exception {
    String out = run(config("k", "model-a", null), "/help\n/model a b\n/exit\n");
    for (String c : new String[] {"/ask <text...>", "/check", "/exit", "/help [command]", "/model [id]",
        "/models [filter]", "/provider [id]", "/reset", "/status"}) {
      assertTrue(out.contains(c), c + " missing:\n" + out);
    }
    assertTrue(out.contains("usage: /model [id]  (too many arguments)"), out);
  }

  @Test
  void runsOneCommandFromArgv() throws Exception {
    Panic panic = Panic.start(Panic.discover(), config("k", "model-a", null), overrides(false),
        new Panic.Process(InputStream.nullInputStream(), t -> { }, () -> { }), (s, e) -> { }, w -> { });
    try {
      StringBuilder out = new StringBuilder();
      StringBuilder err = new StringBuilder();
      assertEquals(0, Cli.once(panic.root(), List.of("ask", "hello world"), out::append, err::append, panic.root().signal()));
      assertTrue(out.toString().startsWith("Hi from model-a"), out.toString());
      assertEquals("hello world", Json.parseOrNull(bodies.get(0)).path("messages").path(1).path("content").asText());
      assertEquals(2, Cli.once(panic.root(), List.of("nope"), out::append, err::append, panic.root().signal()));
      assertTrue(err.toString().contains("unknown command nope"), err.toString());
      assertEquals(0, Cli.once(panic.root(), List.of("help"), out::append, err::append, panic.root().signal()));
      assertTrue(out.toString().contains("  help [command]"), out.toString());
    } finally {
      panic.dispose();
    }
  }
}
