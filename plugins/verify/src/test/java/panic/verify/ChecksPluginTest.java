package panic.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import core.CancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.command.Commands;
import panic.command.Session;
import panic.util.CancelToken;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.tools.Workspace;

class ChecksPluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private static ChecksConfig.Check sh(String script, int timeoutSeconds) {
    return new ChecksConfig.Check(List.of("sh", "-c", script), timeoutSeconds);
  }

  private ConfigHandle<ChecksConfig> checks(Map<String, ChecksConfig.Check> checks, int maxOutput) {
    root.plugin("permissions", new PermissionsPlugin(), new PermissionsConfig(null, Map.of("check", "allow"), 0));
    return root.plugin("checks", new ChecksPlugin(), new ChecksConfig(dir.toString(), checks, maxOutput));
  }

  private ToolResult call(String args, CancelToken signal) {
    return Tools.call(root, new ToolCall("id", "check", args), new ToolContext("c", signal));
  }

  private ToolResult call(String args) {
    return call(args, new CancelToken());
  }

  @Test
  void runsInTheProjectOfTheConversation() throws Exception {
    Files.writeString(dir.resolve("default-marker"), "x");
    Path project = Files.createDirectory(dir.resolve("project"));
    Files.writeString(project.resolve("project-marker"), "x");
    checks(Map.of("test", sh("ls", 0)), 0);
    root.hook(Workspace.ROOT, q -> q.conversationId().equals("c") ? project : null);
    String inProject = call("{\"name\":\"test\"}").content();
    assertTrue(inProject.contains("project-marker") && !inProject.contains("default-marker"), inProject);
    ToolResult other = Tools.call(root, new ToolCall("id", "check", "{\"name\":\"test\"}"),
        new ToolContext("d", new CancelToken()));
    assertTrue(other.content().contains("default-marker"), "no project: the configured dir: " + other.content());
  }

  @Test
  void aPassingCheckReturnsItsOutput() throws Exception {
    Files.writeString(dir.resolve("marker"), "x");
    checks(Map.of("test", sh("ls; echo all good >&2", 0)), 0);
    ToolResult r = call("{\"name\":\"test\"}");
    assertFalse(r.isError(), r.content());
    assertTrue(r.content().startsWith("check test passed (exit 0, "), r.content());
    assertTrue(r.content().contains("marker"), "runs in dir: " + r.content());
    assertTrue(r.content().contains("all good"), "stderr included: " + r.content());
  }

  @Test
  void aFailingCheckIsAnErrorWithTheEndOfItsOutput() {
    checks(Map.of("test", sh("i=0; while [ $i -lt 200 ]; do echo line$i; i=$((i+1)); done; exit 3", 0)), 50);
    ToolResult r = call("{\"name\":\"test\"}");
    assertTrue(r.isError(), r.content());
    assertTrue(r.content().startsWith("check test failed (exit 3, "), r.content());
    assertTrue(r.content().contains("earlier chars cut") && r.content().endsWith("line199"), r.content());
    assertFalse(r.content().contains("line0\n"), r.content());
  }

  @Test
  void aCheckThatRunsTooLongIsKilled() {
    checks(Map.of("slow", sh("echo started; sleep 30", 1)), 0);
    ToolResult r = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> call("{\"name\":\"slow\"}"));
    assertTrue(r.isError() && r.content().startsWith("check slow timed out after 1s"), r.content());
    assertTrue(r.content().contains("started"), r.content());
  }

  @Test
  void cancellingKillsTheProcessAndThrows() {
    checks(Map.of("slow", sh("sleep 30", 0)), 0);
    CancelToken signal = new CancelToken();
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(300);
      } catch (InterruptedException e) {
        return;
      }
      signal.cancel();
    });
    assertTimeoutPreemptively(Duration.ofSeconds(10),
        () -> assertThrows(CancelledException.class, () -> call("{\"name\":\"slow\"}", signal)));
  }

  @Test
  void onlyConfiguredNamesAreAccepted() {
    checks(Map.of("test", sh("true", 0)), 0);
    ToolResult r = call("{\"name\":\"rm -rf /\"}");
    assertTrue(r.isError() && r.content().contains("name"), r.content());
    ToolResult missing = call("{\"name\":\"test\",\"command\":\"ls\"}");
    assertTrue(missing.isError(), "no extra arguments: " + missing.content());
  }

  @Test
  void execIsAskedByDefault() {
    root.plugin("checks", new ChecksPlugin(), new ChecksConfig(dir.toString(), Map.of("test", sh("true", 0)), 0));
    ToolResult r = call("{\"name\":\"test\"}");
    assertTrue(r.isError() && r.content().contains("denied"), r.content());
  }

  @Test
  void theToolAsksForTheLongestCheckAsItsOwnLimit() {
    checks(Map.of("fast", sh("true", 5), "slow", sh("true", 900)), 0);
    assertEquals(910, Tools.info(root, "check").orElseThrow().timeoutSeconds());
  }

  @Test
  void noChecksNoToolAndTheToolGoesWithThePlugin() {
    ConfigHandle<ChecksConfig> h = checks(Map.of(), 0);
    assertTrue(Tools.specs(root).isEmpty());
    StringBuilder out = new StringBuilder();
    Commands.run(root, "checks", "", new Session("s", out::append, new CancelToken()));
    assertTrue(out.toString().contains("no checks configured"), out.toString());

    h.update(new ChecksConfig(dir.toString(), Map.of("test", sh("true", 0)), 0));
    assertEquals(List.of("check"), Tools.specs(root).stream().map(ToolSpec::name).toList());
    h.dispose();
    assertTrue(Tools.specs(root).isEmpty());
  }

  @Test
  void aCheckWithoutACommandIsAConfigError() {
    assertThrows(IllegalArgumentException.class, () -> new ChecksConfig.Check(List.of(), 0));
    assertThrows(IllegalArgumentException.class, () -> new ChecksConfig.Check(null, 0));
  }
}
