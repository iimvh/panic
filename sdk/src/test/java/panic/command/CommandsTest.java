package panic.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.PluginHandle;
import context.Root;
import core.Disposable;
import core.Signal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CommandsTest {

  private final Root root = Context.root("t");
  private final StringBuilder printed = new StringBuilder();
  private final Session session = new Session("s1", printed::append, never());

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private static Signal never() {
    return new Signal() {
      @Override
      public boolean isCancelled() {
        return false;
      }

      @Override
      public Disposable onCancel(Runnable listener) {
        return () -> { };
      }

      @Override
      public void throwIfCancelled() {
      }
    };
  }

  @Test
  void commandsLiveAsLongAsTheirPlugin() {
    PluginHandle echo = root.plugin("echo", ctx -> {
      Commands.command(ctx, "echo <text...>", "say it back", call -> call.out().println(call.session().id() + ":" + call.arg("text")));
      Commands.command(ctx, "beep", "", call -> call.out().println("beep"));
      return null;
    });
    assertTrue(Commands.run(root, "echo", "hello  world", session));
    assertEquals("s1:hello  world\n", printed.toString());
    assertEquals(List.of("beep", "echo"), Commands.list(root).stream().map(Command::name).toList());

    echo.dispose();
    assertFalse(Commands.run(root, "echo", "x", session));
    assertTrue(Commands.list(root).isEmpty());
  }

  @Test
  void argvIsQuotedBackIntoOneArgument() {
    root.plugin("p", ctx -> {
      Commands.command(ctx, "pair <a> <b>", "", call -> call.out().print(call.arg("a") + "|" + call.arg("b")));
      return null;
    });
    assertTrue(Commands.run(root, "pair", List.of("two words", "x"), session));
    assertEquals("two words|x", printed.toString());
  }

  @Test
  void failuresReachTheCallerUnwrapped() {
    IllegalStateException boom = new IllegalStateException("boom");
    root.plugin("p", ctx -> {
      Commands.command(ctx, "fail", "", call -> {
        throw boom;
      });
      Commands.command(ctx, "checked", "", call -> {
        throw new java.io.IOException("disk");
      });
      Commands.command(ctx, "one <x>", "", call -> { });
      return null;
    });
    assertSame(boom, assertThrows(IllegalStateException.class, () -> Commands.run(root, "fail", "", session)));
    assertEquals("disk", assertThrows(RuntimeException.class, () -> Commands.run(root, "checked", "", session)).getMessage());
    assertThrows(UsageException.class, () -> Commands.run(root, "one", "", session));
  }

  @Test
  void firstDeclarationWins() {
    root.plugin("a", ctx -> {
      Commands.command(ctx, "who", "", call -> call.out().print("a"));
      return null;
    });
    root.plugin("b", ctx -> {
      Commands.command(ctx, "who", "", call -> call.out().print("b"));
      return null;
    });
    Commands.run(root, "who", "", session);
    assertEquals("a", printed.toString());
  }
}
