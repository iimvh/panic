package panic.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.chat.ChatConfig;
import panic.llm.Json;
import panic.llm.LlmConfig;
import panic.llm.ProviderConfig;

/** The whole app against a fake OpenAI-compatible server standing in for OpenRouter. */
class ReplTest {

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

  private String run(PanicConfig cfg, String input) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    Panic panic = Panic.start(cfg, (source, error) -> out.println("[error-handler] " + source + " " + error));
    CountDownLatch exit = new CountDownLatch(1);
    try {
      Stdin stdin = new Stdin(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
      panic.root().plugin("repl", new Repl(panic, stdin, out, exit::countDown).plugin());
      assertTrue(exit.await(20, TimeUnit.SECONDS), "repl did not exit:\n" + bytes);
    } finally {
      panic.dispose();
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  void chatsStreamsRemembersAndSwitchesModel() throws Exception {
    PanicConfig cfg = new PanicConfig(Map.of("openrouter", new ProviderConfig("k", url())),
        new LlmConfig("openrouter", "model-a", null, null), new ChatConfig("sys", 0));
    String out = run(cfg, "hello\n/model model-b\nagain\n/reset\nfresh\n/exit\n");

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
    PanicConfig cfg = new PanicConfig(Map.of(), new LlmConfig("openrouter", "m", null, null), ChatConfig.DEFAULT);
    String out = run(cfg, "hello\n/provider nope\n/status\n");  // EOF also exits
    assertTrue(out.contains("[no LLM connected]"), out);
    assertTrue(out.contains("unknown provider 'nope'"), out);
    assertTrue(out.contains("(not connected)"), out);
    assertEquals(0, bodies.size());
  }

  @Test
  void applyOnlyPushesSectionsThatChangedInTheFile() throws Exception {
    PanicConfig file = new PanicConfig(Map.of("openrouter", new ProviderConfig("k", url())),
        new LlmConfig("openrouter", "model-a", null, null), ChatConfig.DEFAULT);
    Panic panic = Panic.start(file, (s, e) -> { });
    try {
      panic.llm(panic.llm().withModel("runtime-choice"));
      panic.apply(new PanicConfig(file.providers(), file.llm(), new ChatConfig("edited", 5)));
      assertEquals("runtime-choice", panic.llm().model(), "llm section unchanged in file: runtime change kept");
      panic.apply(new PanicConfig(file.providers(), file.llm().withModel("file-choice"), new ChatConfig("edited", 5)));
      assertEquals("file-choice", panic.llm().model());
    } finally {
      panic.dispose();
    }
  }
}
