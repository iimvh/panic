package panic.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure: synthetic command lines, so it runs on every OS. */
class WindowsArgsTest {

  private static final Charset CP1252 = Charset.forName("windows-1252");

  @Test
  void splitsByTheCRuntimeRules() {
    assertEquals(List.of("a", "b c", "d"), WindowsArgs.split("a \"b c\"\td"));
    assertEquals(List.of("a\"b"), WindowsArgs.split("a\\\"b"));           // \" is a literal quote
    assertEquals(List.of("a\\", "b"), WindowsArgs.split("\"a\\\\\" b"));   // \\" is one backslash, then the quote
    assertEquals(List.of("a\\\\b"), WindowsArgs.split("a\\\\b"));          // backslashes not before a quote stay
    assertEquals(List.of("say \"hi\""), WindowsArgs.split("\"say \"\"hi\"\"\"")); // "" inside quotes
    assertEquals(List.of(""), WindowsArgs.split("\"\""));                   // an empty argument
    assertEquals(List.of("x"), WindowsArgs.split("   x   "));
  }

  @Test
  void putsBackWhatTheCodePageLost() {
    String line = "\"C:\\Program Files\\Java\\bin\\java.exe\" -cp \"a;b\" panic.app.Main ask \"viết tiếng Việt 😀\"";
    String[] mangled = {"ask", "vi?t ti?ng Vi?t ??"}; // the emoji is two UTF-16 units, so two ?
    assertArrayEquals(new String[] {"ask", "viết tiếng Việt 😀"},
        WindowsArgs.match(mangled, WindowsArgs.split(line), CP1252));
  }

  @Test
  void keepsTheArgumentsWhenTheTokensDoNotLineUp() {
    String[] mangled = {"ask", "vi?t"};
    // a launcher that expanded something: the tail is not what Windows decoded
    assertArrayEquals(mangled, WindowsArgs.match(mangled, List.of("java", "ask", "viết", "extra"), CP1252));
    assertArrayEquals(mangled, WindowsArgs.match(mangled, List.of("viết"), CP1252));
    // a real ? stays a ?
    String[] literal = {"what?"};
    assertArrayEquals(literal, WindowsArgs.match(literal, List.of("java", "what?"), CP1252));
  }

  @Test
  void leavesArgumentsAloneWhenNothingWasLost() {
    String[] plain = {"models", "free"};
    assertArrayEquals(plain, WindowsArgs.recover(plain));
  }
}
