package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import context.Context;
import context.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.util.CancelToken;
import panic.util.Json;

/** Which shell runs the agent's commands, and that the model is told the truth about it. */
class ShellTest {

  @TempDir
  Path dir;

  private static final Path BASH = Path.of("C:\\Git\\bin\\bash.exe");

  @Test
  void autoIsAPosixShellWhereverThereIsOne() {
    Processes.Shell unix = Processes.Shell.resolve("auto", false, () -> {
      throw new AssertionError("no Git Bash lookup off Windows");
    });
    assertEquals(List.of("sh", "-c", "echo \"a b\""), unix.command("echo \"a b\""));

    Processes.Shell git = Processes.Shell.resolve(null, true, () -> BASH);
    assertEquals(Processes.Shell.Kind.POSIX, git.kind());
    assertTrue(git.name().contains("Git Bash"), git.name());
    assertEquals(List.of(BASH.toString(), "-c", "\"echo \\\"a b\\\"\""), git.command("echo \"a b\""));

    Processes.Shell ps = Processes.Shell.resolve("auto", true, () -> null);
    assertEquals(Processes.Shell.Kind.POWERSHELL, ps.kind());
    assertTrue(ps.command("ls").contains("-EncodedCommand"));
  }

  @Test
  void aNamedShellIsUsedOrIsAnError() throws Exception {
    assertEquals(Processes.Shell.Kind.CMD, Processes.Shell.resolve("cmd", true, () -> BASH).kind());
    assertEquals(Processes.Shell.Kind.POWERSHELL, Processes.Shell.resolve("pwsh", true, () -> BASH).kind());
    assertThrows(IllegalArgumentException.class, () -> Processes.Shell.resolve("bash", true, () -> null));
    assertThrows(IllegalArgumentException.class, () -> Processes.Shell.resolve(dir.resolve("nope").toString(),
        false, () -> null));
    Path zsh = Files.createFile(dir.resolve("zsh"));
    assertEquals(List.of(zsh.toString(), "-c", "x"), Processes.Shell.resolve(zsh.toString(), false, () -> null)
        .command("x"));
  }

  @Test
  void quotesForTheWindowsCRuntime() {
    assertEquals("\"a b\"", Processes.quoteForWindows("a b"));
    assertEquals("\"say \\\"hi\\\"\"", Processes.quoteForWindows("say \"hi\""));
    assertEquals("\"a\\\\\\\"b\"", Processes.quoteForWindows("a\\\"b"));   // a\"b: the backslash doubled, then \"
    assertEquals("\"C:\\x\\y\"", Processes.quoteForWindows("C:\\x\\y"));   // backslashes not before a quote stay
    assertEquals("\"x\\\n\"", Processes.quoteForWindows("x\\"));            // never ends in \"
  }

  @Test
  void theToolDescriptionNamesTheShellThatRuns() {
    Root root = Context.root("t");
    try {
      root.plugin("shell", new ShellPlugin(), config("cmd"));
      ToolSpec spec = Tools.specs(root, "c").stream().filter(s -> s.name().equals(ShellPlugin.EXEC)).findFirst()
          .orElseThrow();
      String text = spec.description() + spec.parameters();
      assertTrue(text.contains("cmd.exe"), text);
      assertFalse(text.contains("sh -c"), text);
    } finally {
      root.dispose();
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void gitBashRunsPosixCommandsOnWindows() {
    assumeTrue(Processes.findGitBash() != null, "Git Bash is not installed");
    Root root = Context.root("t");
    try {
      root.plugin("user", ctx -> {
        ctx.hook(Tools.APPROVE, a -> Tools.Decision.ALLOW);
        return null;
      });
      root.plugin("shell", new ShellPlugin(), config("auto"));
      String command = "echo \"a  b\" 'c' && printf '%s\\n' \"tiếng Việt\" | tr a-z A-Z; test -d . && echo ok";
      ToolResult r = Tools.call(root, new ToolCall("x", ShellPlugin.EXEC, Json.object().put("command", command)
          .toString()), new ToolContext("c", new CancelToken()));
      assertFalse(r.isError(), r.content());
      assertTrue(r.content().endsWith("a  b c\nTIếNG VIệT\nok"), r.content());
    } finally {
      root.dispose();
    }
  }

  private ShellConfig config(String shell) {
    return new ShellConfig(dir.toString(), 0, 30, 0, 0, null, shell);
  }
}
