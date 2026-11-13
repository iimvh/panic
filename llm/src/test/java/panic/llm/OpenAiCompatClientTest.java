package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.CancelledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatClientTest {

  private static final ChatRequest HELLO =
      new ChatRequest(List.of(Message.system("be brief"), Message.user("hi")), "m-1", 0.5, 64);

  @Test
  void completeSendsOpenAiBodyAndReadsAnswer() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200, """
        {"model":"m-1","choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"hello"}]},
         "finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}"""))) {
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "sk-1");
      Completion c = client.complete(HELLO, new CancelToken());
      assertEquals("hello", c.text());
      assertEquals("stop", c.finishReason());
      assertEquals(4, c.usage().totalTokens());
      assertEquals("Bearer sk-1", server.lastAuth);
      var body = Json.parseOrNull(server.lastBody);
      assertEquals("m-1", body.path("model").asText());
      assertEquals("system", body.path("messages").path(0).path("role").asText());
      assertEquals(64, body.path("max_tokens").asInt());
      assertTrue(body.path("stream").isMissingNode());
    }
  }

  @Test
  void streamHandsDeltasInOrder() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.sse(ex,
        "{\"model\":\"m-1\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
        "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"lo\"}]}}]}",
        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2}}",
        "[DONE]"))) {
      List<String> deltas = new ArrayList<>();
      Completion c = new OpenAiCompatClient("fake", server.url(), "k").stream(HELLO, new CancelToken(), deltas::add);
      assertEquals(List.of("Hel", "lo"), deltas);
      assertEquals("Hello", c.text());
      assertEquals("stop", c.finishReason());
      assertEquals(4, c.usage().totalTokens());
      assertTrue(Json.parseOrNull(server.lastBody).path("stream").asBoolean());
    }
  }

  @Test
  void httpErrorBecomesLlmExceptionWithProviderMessage() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 401,
        "{\"error\":{\"message\":\"No auth credentials found\",\"code\":401}}"))) {
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "bad");
      LlmException e = assertThrows(LlmException.class, () -> client.stream(HELLO, new CancelToken(), d -> { }));
      assertEquals(401, e.status());
      assertTrue(e.getMessage().contains("No auth credentials found"), e.getMessage());
    }
  }

  @Test
  void missingModelIsRejectedBeforeSending() {
    OpenAiCompatClient client = new OpenAiCompatClient("fake", "http://127.0.0.1:9", "k");
    ChatRequest noModel = ChatRequest.of(List.of(Message.user("hi")));
    assertThrows(LlmException.class, () -> client.complete(noModel, new CancelToken()));
  }

  @Test
  void cancellingTheSignalAbortsAStream() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.sse(ex,
        "{\"choices\":[{\"delta\":{\"content\":\"first\"}}]}", "sleep:30000", "[DONE]"))) {
      CancelToken token = new CancelToken();
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "k");
      assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
          assertThrows(CancelledException.class, () -> client.stream(HELLO, token, d -> token.cancel())));
    }
  }

  @Test
  void cancellingBeforeTheResponseAbortsTheRequest() throws Exception {
    try (FakeServer server = new FakeServer(ex -> {
      Thread.sleep(30_000);
      FakeServer.json(ex, 200, "{}");
    })) {
      CancelToken token = new CancelToken();
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "k");
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(200);
        } catch (InterruptedException ignored) {
          return;
        }
        token.cancel();
      });
      assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
          assertThrows(CancelledException.class, () -> client.complete(HELLO, token)));
    }
  }
}
