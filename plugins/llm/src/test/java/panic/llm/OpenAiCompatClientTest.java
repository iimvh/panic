package panic.llm;

import panic.util.CancelToken;
import panic.util.Json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.CancelledException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

  // ---------------- tool calling ----------------

  private static final ToolSpec WEATHER = new ToolSpec("weather", "current weather",
      Json.parseOrNull("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"));

  @Test
  void toolsAndToolMessagesAreSentInOpenAiFormat() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200,
        "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"))) {
      ChatRequest request = new ChatRequest(List.of(
          Message.user("weather in Hue?"),
          Message.assistant("", List.of(new ToolCall("c1", "weather", "{\"city\":\"Hue\"}"))),
          Message.tool("c1", "31C, sunny")), "m-1", null, null)
          .withTools(List.of(WEATHER), ToolChoice.named("weather"));
      new OpenAiCompatClient("fake", server.url(), "k").complete(request, new CancelToken());

      var body = Json.parseOrNull(server.lastBody);
      var tool = body.path("tools").path(0);
      assertEquals("function", tool.path("type").asText());
      assertEquals("weather", tool.path("function").path("name").asText());
      assertEquals("current weather", tool.path("function").path("description").asText());
      assertEquals("city", tool.path("function").path("parameters").path("required").path(0).asText());
      assertEquals("weather", body.path("tool_choice").path("function").path("name").asText());

      var assistant = body.path("messages").path(1);
      assertTrue(assistant.path("content").isNull(), "empty text next to tool calls is sent as null");
      var call = assistant.path("tool_calls").path(0);
      assertEquals("c1", call.path("id").asText());
      assertEquals("function", call.path("type").asText());
      assertEquals("weather", call.path("function").path("name").asText());
      assertEquals("{\"city\":\"Hue\"}", call.path("function").path("arguments").asText());

      var result = body.path("messages").path(2);
      assertEquals("tool", result.path("role").asText());
      assertEquals("c1", result.path("tool_call_id").asText());
      assertEquals("31C, sunny", result.path("content").asText());
    }
  }

  @Test
  void anEmptyAnswerInTheHistoryIsSentWithPlaceholderText() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200,
        "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}"))) {
      ChatRequest request = new ChatRequest(List.of(
          Message.user("weather in Hue?"),
          Message.assistant("", List.of(new ToolCall("c1", "weather", "{\"city\":\"Hue\"}"))),
          Message.tool("c1", "31C, sunny"),
          Message.assistant(""),
          Message.user("and now?")), "m-1", null, null);
      new OpenAiCompatClient("fake", server.url(), "k").complete(request, new CancelToken());

      var messages = Json.parseOrNull(server.lastBody).path("messages");
      assertEquals(5, messages.size(), "kept, so a user message never follows a tool result");
      assertEquals("assistant", messages.path(3).path("role").asText());
      assertEquals(OpenAiCompatClient.EMPTY_ANSWER, messages.path(3).path("content").asText());
      assertTrue(messages.path(3).path("tool_calls").isMissingNode());
    }
  }

  @Test
  void plainRequestsSendNoToolFields() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200, "{\"choices\":[]}"))) {
      new OpenAiCompatClient("fake", server.url(), "k").complete(HELLO, new CancelToken());
      var body = Json.parseOrNull(server.lastBody);
      assertTrue(body.path("tools").isMissingNode());
      assertTrue(body.path("tool_choice").isMissingNode());
      assertTrue(body.path("messages").path(1).path("tool_calls").isMissingNode());
    }
  }

  @Test
  void toolChoiceModesAreSentAsStrings() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200, "{\"choices\":[]}"))) {
      new OpenAiCompatClient("fake", server.url(), "k")
          .complete(HELLO.withTools(List.of(WEATHER), ToolChoice.REQUIRED), new CancelToken());
      assertEquals("required", Json.parseOrNull(server.lastBody).path("tool_choice").asText());
    }
  }

  @Test
  void completeReadsToolCalls() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.json(ex, 200, """
        {"model":"m-1","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"a","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"Hue\\"}"}},
          {"id":"b","type":"function","function":{"name":"weather","arguments":{"city":"Hanoi"}}}]},
         "finish_reason":"tool_calls"}]}"""))) {
      Completion c = new OpenAiCompatClient("fake", server.url(), "k").complete(HELLO, new CancelToken());
      assertEquals("", c.text());
      assertEquals("tool_calls", c.finishReason());
      assertEquals(List.of(new ToolCall("a", "weather", "{\"city\":\"Hue\"}"),
          new ToolCall("b", "weather", "{\"city\":\"Hanoi\"}")), c.toolCalls());
      assertEquals(c.toolCalls(), c.message().toolCalls());
    }
  }

  @Test
  void streamRebuildsToolCallsFromFragments() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.sse(ex,
        "{\"choices\":[{\"delta\":{\"content\":\"Checking\"}}]}",
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"type\":\"function\","
            + "\"function\":{\"name\":\"weather\",\"arguments\":\"\"}}]}}]}",
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"ci\"}}]}}]}",
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"b\",\"type\":\"function\","
            + "\"function\":{\"name\":\"time\",\"arguments\":\"{}\"}}]}}]}",
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ty\\\":\\\"Hue\\\"}\"}}]}}]}",
        "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
        "[DONE]"))) {
      List<String> deltas = new ArrayList<>();
      List<ToolCallDelta> pieces = new ArrayList<>();
      Completion c = new OpenAiCompatClient("fake", server.url(), "k")
          .stream(HELLO, new CancelToken(), deltas::add, pieces::add);
      assertEquals(List.of("Checking"), deltas, "tool calls are not text deltas");
      assertEquals(List.of(
          new ToolCallDelta(0, "a", "weather", ""),
          new ToolCallDelta(0, null, null, "{\"ci"),
          new ToolCallDelta(1, "b", "time", "{}"),
          new ToolCallDelta(0, null, null, "ty\":\"Hue\"}")), pieces);
      assertEquals("Checking", c.text());
      assertEquals("tool_calls", c.finishReason());
      assertEquals(List.of(new ToolCall("a", "weather", "{\"city\":\"Hue\"}"), new ToolCall("b", "time", "{}")),
          c.toolCalls());
    }
  }

  @Test
  void streamReadsWholeToolCallsWithoutIndex() throws Exception {
    // Mistral's shape: complete calls in one chunk, no index
    try (FakeServer server = new FakeServer(ex -> FakeServer.sse(ex,
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"x1\",\"function\":{\"name\":\"weather\","
            + "\"arguments\":\"{\\\"city\\\":\\\"Hue\\\"}\"}},{\"id\":\"x2\",\"function\":{\"name\":\"time\","
            + "\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
        "[DONE]"))) {
      List<ToolCallDelta> pieces = new ArrayList<>();
      Completion c = new OpenAiCompatClient("fake", server.url(), "k")
          .stream(HELLO, new CancelToken(), d -> { }, pieces::add);
      assertEquals(List.of(new ToolCall("x1", "weather", "{\"city\":\"Hue\"}"), new ToolCall("x2", "time", "{}")),
          c.toolCalls());
      assertEquals(List.of(new ToolCallDelta(0, "x1", "weather", "{\"city\":\"Hue\"}"),
          new ToolCallDelta(1, "x2", "time", "{}")), pieces, "a whole call is one piece");
    }
  }

  @Test
  void streamWithoutToolCallsHasNone() throws Exception {
    try (FakeServer server = new FakeServer(ex -> FakeServer.sse(ex,
        "{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}", "[DONE]"))) {
      Completion c = new OpenAiCompatClient("fake", server.url(), "k").stream(HELLO, new CancelToken(), d -> { });
      assertFalse(c.hasToolCalls());
    }
  }

  // ---------------- retry ----------------

  private static final RetryPolicy FAST = new RetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(5));

  /** Answers {@code failures} times with {@code status}, then with a normal reply. */
  private static FakeServer flaky(AtomicInteger hits, int failures, int status, String retryAfter) throws Exception {
    return new FakeServer(ex -> {
      if (hits.incrementAndGet() <= failures) {
        if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
        FakeServer.json(ex, status, "{\"error\":{\"message\":\"busy\"}}");
      } else {
        FakeServer.json(ex, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
      }
    });
  }

  @Test
  void rateLimitIsRetried() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = flaky(hits, 1, 429, null)) {
      Completion c = new OpenAiCompatClient("fake", server.url(), "k", FAST).complete(HELLO, new CancelToken());
      assertEquals("ok", c.text());
      assertEquals(2, hits.get());
    }
  }

  @Test
  void streamIsRetriedBeforeItStarts() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = new FakeServer(ex -> {
      if (hits.incrementAndGet() == 1) {
        FakeServer.json(ex, 503, "{}");
      } else {
        FakeServer.sse(ex, "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}", "[DONE]");
      }
    })) {
      Completion c = new OpenAiCompatClient("fake", server.url(), "k", FAST).stream(HELLO, new CancelToken(), d -> { });
      assertEquals("ok", c.text());
      assertEquals(2, hits.get());
    }
  }

  @Test
  void clientErrorsAreNotRetried() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = flaky(hits, 5, 400, null)) {
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "k", FAST);
      LlmException e = assertThrows(LlmException.class, () -> client.complete(HELLO, new CancelToken()));
      assertEquals(400, e.status());
      assertEquals(1, hits.get());
    }
  }

  @Test
  void retriesAreBounded() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = flaky(hits, 100, 503, null)) {
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "k", FAST);
      LlmException e = assertThrows(LlmException.class, () -> client.complete(HELLO, new CancelToken()));
      assertEquals(503, e.status());
      assertEquals(3, hits.get(), "first attempt + maxRetries");
    }
  }

  @Test
  void unreachableProviderIsRetried() {
    OpenAiCompatClient client = new OpenAiCompatClient("fake", "http://127.0.0.1:9", "k",
        new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(10)));
    LlmException e = assertThrows(LlmException.class, () -> client.complete(HELLO, new CancelToken()));
    assertEquals(0, e.status());
    assertTrue(e.getMessage().contains("unreachable"), e.getMessage());
  }

  @Test
  void retryAfterIsHonoured() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = flaky(hits, 1, 429, "1")) {
      long start = System.nanoTime();
      new OpenAiCompatClient("fake", server.url(), "k", FAST).complete(HELLO, new CancelToken());
      long waitedMs = (System.nanoTime() - start) / 1_000_000;
      assertTrue(waitedMs >= 900, "waited " + waitedMs + "ms");
    }
  }

  @Test
  void cancellingDuringBackoffStopsAtOnce() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    try (FakeServer server = flaky(hits, 100, 429, "30")) {
      OpenAiCompatClient client = new OpenAiCompatClient("fake", server.url(), "k",
          new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMinutes(1)));
      CancelToken token = new CancelToken();
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException ignored) {
          return;
        }
        token.cancel();
      });
      assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
          assertThrows(CancelledException.class, () -> client.complete(HELLO, token)));
      assertEquals(1, hits.get());
    }
  }
}
