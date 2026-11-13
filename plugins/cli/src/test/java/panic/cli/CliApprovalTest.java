package panic.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.Root;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Out;
import panic.command.Session;
import panic.llm.CancelToken;
import panic.llm.ToolSpec;
import panic.plugin.App;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.ToolsPlugin;

/** The REPL answers tool approvals for its own session by asking at the terminal. */
class CliApprovalTest {

  private final Root root = Context.root("t");
  private final StringBuffer screen = new StringBuffer();
  private final AtomicInteger ran = new AtomicInteger();
  private PipedOutputStream keyboard;

  @BeforeEach
  void setUp() throws IOException {
    keyboard = new PipedOutputStream();
    InputStream in = new PipedInputStream(keyboard);
    App app = new App() {
      @Override
      public InputStream in() {
        return in;
      }

      @Override
      public Out out() {
        return screen::append;
      }

      @Override
      public void exit() {
      }
    };
    root.plugin("change", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("change", "", null);
        }

        @Override
        public Risk risk() {
          return Risk.WRITE;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          ran.incrementAndGet();
          return ToolResult.ok("changed");
        }
      });
      return null;
    });
    root.plugin("tools", new ToolsPlugin());
    root.plugin("app", ctx -> {
      ctx.provide(App.KEY, app);
      return null;
    });
    root.plugin("cli", new Cli(), CliConfig.DEFAULT);
  }

  @AfterEach
  void dispose() throws IOException {
    root.dispose();
    keyboard.close();
  }

  private void type(String line) throws IOException {
    keyboard.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    keyboard.flush();
  }

  /** Waits until the screen shows {@code text} {@code times} times. */
  private void see(String text, int times) throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      if (screen.toString().split(java.util.regex.Pattern.quote(text), -1).length - 1 >= times) return;
      Thread.sleep(50);
    }
    fail("never saw " + times + "x '" + text + "' in:\n" + screen);
  }

  @Test
  void yesRunsTheCallAnythingElseDeniesIt() throws Exception {
    type("/tool change");
    see("[approve] change (write) {}\nallow? [y/N] ", 1);
    type("y");
    see("changed\n", 1);
    assertEquals(1, ran.get());

    type("/tool change");
    see("allow? [y/N] ", 2);
    type("");
    see("error: change: denied by the user\n", 1);
    assertEquals(1, ran.get());
  }

  @Test
  void otherSessionsAreNotAskedHere() throws Exception {
    see("> ", 1); // the REPL is up
    StringBuilder out = new StringBuilder();
    Commands.run(root, "tool", "change", new Session("web-tab", out::append, new CancelToken()));
    assertEquals("error: change: denied by the user\n", out.toString());
    assertFalse(screen.toString().contains("[approve]"));
  }

  @Test
  void callsAskingTogetherAreAskedOneAfterTheOther() throws Exception {
    // a command of the REPL session that runs two calls at once, like parallel tools or a sub-agent do
    root.plugin("both", ctx -> {
      Commands.command(ctx, "both", "two calls at once", call -> {
        ToolContext main = new ToolContext(Cli.SESSION, call.signal());
        ToolContext sub = new ToolContext("repl~explore-1", call.signal(), null, Cli.SESSION, "explore", null, null,
            null);
        context.Task<ToolResult> first = ctx.spawn("first",
            s -> Tools.call(ctx, new panic.llm.ToolCall("a", "change", "{}"), main));
        context.Task<ToolResult> second = ctx.spawn("second",
            s -> Tools.call(ctx, new panic.llm.ToolCall("b", "change", "{\"n\":2}"), sub));
        try {
          call.out().println("first " + first.await().content() + ", second " + second.await().content());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      return null;
    });
    see("> ", 1);
    type("/both");
    see("allow? [y/N] ", 1);
    Thread.sleep(300);
    assertEquals(1, screen.toString().split("allow\\?", -1).length - 1, "one question at a time");
    String firstAsked = screen.toString();
    type(firstAsked.contains("explore: ") ? "n" : "y");
    see("allow? [y/N] ", 2);
    type(firstAsked.contains("explore: ") ? "y" : "n");
    see("first changed, second change: denied by the user\n", 1);
    assertEquals(1, ran.get());
    assertEquals(1, screen.toString().split("\\[approve\\] explore: change \\(write\\) \\{\"n\":2\\}", -1).length - 1);
  }
}
