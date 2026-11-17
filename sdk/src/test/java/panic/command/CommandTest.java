package panic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTest {

  private static Command spec(String spec) {
    return Command.parse(spec, "", call -> { });
  }

  @Test
  void parsesSpec() {
    Command c = spec("send <to> [subject] [body...]");
    assertEquals("send", c.name());
    assertEquals("send <to> [subject] [body...]", c.usage());
    assertEquals(3, c.params().size());
  }

  @Test
  void bindsTokensQuotesAndRest() {
    Command c = spec("send <to> [subject] [body...]");
    assertEquals(Map.of("to", "bob"), c.bind("bob"));
    assertEquals(Map.of("to", "bob", "subject", "hi there"), c.bind("bob \"hi there\""));
    assertEquals(Map.of("to", "bob", "subject", "x", "body", "keep   'this'  as typed"),
        c.bind("bob x keep   'this'  as typed"));
    assertEquals(Map.of("to", "bob", "subject", "x", "body", "one quoted"), c.bind("bob x \"one quoted\""));
  }

  @Test
  void usageErrors() {
    Command c = spec("model [id]");
    UsageException tooMany = assertThrows(UsageException.class, () -> c.bind("a b"));
    assertEquals("model [id]", tooMany.usage());
    assertThrows(UsageException.class, () -> spec("ask <text...>").bind("  "));
  }

  @Test
  void rejectsBadSpecs() {
    assertThrows(IllegalArgumentException.class, () -> spec("Bad"));
    assertThrows(IllegalArgumentException.class, () -> spec("_x"));
    assertEquals("mcp__docs__review-code", spec("mcp__docs__review-code [args...]").name());
    assertThrows(IllegalArgumentException.class, () -> spec("x [a] <b>"));
    assertThrows(IllegalArgumentException.class, () -> spec("x [a...] [b]"));
    assertThrows(IllegalArgumentException.class, () -> spec("x <a> <a>"));
    assertThrows(IllegalArgumentException.class, () -> spec("x {a}"));
  }

  @Test
  void quoteRoundTrips() {
    for (String arg : new String[] {"plain", "two words", "say \"hi\"", ""}) {
      assertEquals(arg, Tokens.split(Tokens.quote(arg)).get(0).value());
    }
  }
}
