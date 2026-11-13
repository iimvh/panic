package panic.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolTypesTest {

  private static final ToolCall CALL = new ToolCall("c1", "weather", "{}");

  @Test
  void messagesKeepToolFieldsConsistent() {
    assertTrue(Message.assistant("", List.of(CALL)).hasToolCalls());
    assertEquals("c1", Message.tool("c1", "done").toolCallId());
    assertEquals(List.of(), Message.user("hi").toolCalls());

    assertThrows(IllegalArgumentException.class, () -> new Message(Role.USER, "x", List.of(CALL), null));
    assertThrows(IllegalArgumentException.class, () -> new Message(Role.TOOL, "x"));
    assertThrows(IllegalArgumentException.class, () -> new Message(Role.USER, "x", null, "c1"));
  }

  @Test
  void toolSpecValidatesNameAndDefaultsParameters() {
    ToolSpec spec = new ToolSpec("read_file", null, null);
    assertEquals("", spec.description());
    assertEquals("object", spec.parameters().path("type").asText());
    assertThrows(IllegalArgumentException.class, () -> new ToolSpec("read file", "", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolSpec("", "", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolSpec("t", "", Json.MAPPER.createArrayNode()));
  }

  @Test
  void toolCallDefaultsBlankArguments() {
    assertEquals("{}", new ToolCall("a", "t", " ").arguments());
  }

  @Test
  void toolChoiceRejectsUnknownModes() {
    assertEquals("x", ToolChoice.named("x").name());
    assertThrows(IllegalArgumentException.class, () -> new ToolChoice("any", null));
    assertThrows(IllegalArgumentException.class, () -> new ToolChoice("auto", "x"));
  }

  @Test
  void defaultsKeepTools() {
    ChatRequest r = ChatRequest.of(List.of(Message.user("q")))
        .withTools(List.of(new ToolSpec("t", "", null)), ToolChoice.AUTO)
        .withDefaults("m", 0.1, 10);
    assertEquals("m", r.model());
    assertEquals(1, r.tools().size());
    assertEquals(ToolChoice.AUTO, r.toolChoice());
  }

  @Test
  void retryDelaysGrowAndAreCapped() {
    RetryPolicy p = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofMillis(300));
    for (int i = 0; i < 20; i++) {
      long first = p.delay(0).toMillis();
      assertTrue(first >= 50 && first <= 100, "attempt 0: " + first);
      long second = p.delay(1).toMillis();
      assertTrue(second >= 100 && second <= 200, "attempt 1: " + second);
      assertTrue(p.delay(10).toMillis() <= 300);
    }
    assertEquals(Duration.ZERO, RetryPolicy.NONE.delay(3));
  }

  @Test
  void providerConfigReadsMaxRetries() throws Exception {
    ProviderConfig none = Json.MAPPER.readValue("{\"apiKey\":\"k\"}", ProviderConfig.class);
    assertNull(none.maxRetries());
    assertEquals(RetryPolicy.DEFAULT, none.retryPolicy());
    ProviderConfig zero = Json.MAPPER.readValue("{\"apiKey\":\"k\",\"maxRetries\":0}", ProviderConfig.class);
    assertEquals(0, zero.retryPolicy().maxRetries());
  }
}
