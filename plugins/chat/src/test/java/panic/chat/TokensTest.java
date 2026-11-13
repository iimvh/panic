package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import panic.llm.Message;
import panic.llm.ToolCall;

class TokensTest {

  @Test
  void aboutFourCharactersPerToken() {
    assertEquals(Tokens.PER_MESSAGE + 2, Tokens.raw(Message.user("abcdefgh")));
    assertEquals(Tokens.PER_MESSAGE + 3, Tokens.raw(Message.user("abcdefghi")), "rounded up");
    assertEquals(Tokens.PER_MESSAGE, Tokens.raw(Message.user("")));
  }

  @Test
  void toolCallsCount() {
    Message m = Message.assistant("", List.of(new ToolCall("id", "read", "{\"path\":\"x\"}")));
    assertEquals(Tokens.PER_MESSAGE + 4, Tokens.raw(m), "read + {\"path\":\"x\"} = 16 chars");
  }

  @Test
  void ratioFollowsReportedUsageWithinBounds() {
    Tokens tokens = new Tokens();
    assertEquals(1.0, tokens.ratio());
    tokens.observe(100, 200);
    assertEquals(1.5, tokens.ratio(), "halfway to what was seen");
    assertEquals(150, tokens.estimate(List.of(Message.user("x".repeat(384)))), "(4 + 96) * 1.5");
    tokens.observe(100, 100_000);
    assertEquals((1.5 + Tokens.MAX_RATIO) / 2, tokens.ratio(), "one odd answer is capped");
    for (int i = 0; i < 50; i++) tokens.observe(1_000, 1);
    assertEquals(Tokens.MIN_RATIO, tokens.ratio(), 1e-9);
  }

  @Test
  void noReportedUsageChangesNothing() {
    Tokens tokens = new Tokens();
    tokens.observe(100, 0);
    tokens.observe(0, 50);
    assertEquals(1.0, tokens.ratio());
  }
}
