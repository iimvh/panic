package panic.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import core.CancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.ToolCall;
import panic.tools.Risk;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;

/** The {@code mcp} plugin against {@link FakeMcpServer}, a real process speaking MCP over stdio. */
class McpPluginTest {

  @TempDir
  Path dir;

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private McpConfig.Server fake() {
    String java = ProcessHandle.current().info().command().orElse("java");
    return new McpConfig.Server(java, List.of("-cp", System.getProperty("java.class.path"),
        FakeMcpServer.class.getName(), dir.toString()));
  }

  private void start(McpConfig.Server server, int timeoutSeconds) {
    root.plugin("user", ctx -> {
      ctx.hook(Tools.APPROVE, a -> Tools.Decision.ALLOW);
      return null;
    });
    root.plugin("mcp", new McpPlugin(w -> { }),
        new McpConfig(Map.of("fake", server), dir.resolve("media").toString(), timeoutSeconds, 20));
  }

  private void startFake() {
    start(fake(), 30);
    await(() -> names().contains("mcp__fake__grow"), "the tools are listed");
  }

  private List<String> names() {
    return Tools.infos(root).stream().map(i -> i.spec().name()).toList();
  }

  private ToolResult call(String tool, String args) {
    return Tools.call(root, new ToolCall("x", tool, args), new ToolContext("c", new CancelToken()));
  }

  private static void await(BooleanSupplier condition, String what) {
    long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) throw new AssertionError("timed out waiting: " + what);
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        throw new AssertionError(e);
      }
    }
  }

  private String notes() throws Exception {
    Path notes = dir.resolve("notes");
    return Files.exists(notes) ? Files.readString(notes) : "";
  }

  private boolean notesContain(String text) {
    try {
      return notes().contains(text);
    } catch (Exception e) {
      return false;
    }
  }

  private String mcp() {
    return run("mcp", "");
  }

  private String run(String command, String args) {
    StringBuilder out = new StringBuilder();
    Commands.run(root, command, args, new Session("x", out::append, new CancelToken()));
    return out.toString();
  }

  private ProcessHandle server() throws Exception {
    return ProcessHandle.of(Long.parseLong(Files.readString(dir.resolve("pid")).strip())).orElseThrow();
  }

  @Test
  void everyToolOfEveryPageIsRegisteredWithTheServersRisk() throws Exception {
    startFake();
    assertEquals(List.of("mcp__fake__bad_name", "mcp__fake__broken", "mcp__fake__die", "mcp__fake__echo",
        "mcp__fake__grow", "mcp__fake__picture", "mcp__fake__wait"), names());
    Tools.Info echo = Tools.infos(root).stream().filter(i -> i.spec().name().equals("mcp__fake__echo")).findFirst()
        .orElseThrow();
    assertEquals(Risk.NETWORK, echo.risk());
    assertEquals("Says the text back.", echo.spec().description());
    assertEquals(List.of("text"), List.of(echo.spec().parameters().path("required").get(0).asText()));
    assertTrue(notes().contains("notifications/initialized"), notes());
  }

  @Test
  void callsGoToTheServerAndComeBackAsText() {
    startFake();
    ToolResult ok = call("mcp__fake__echo", "{\"text\":\"hi\"}");
    assertFalse(ok.isError(), ok.content());
    assertEquals("echo: hi", ok.content());

    ToolResult broken = call("mcp__fake__broken", "{}");
    assertTrue(broken.isError());
    assertEquals("it broke", broken.content());

    ToolResult invalid = call("mcp__fake__echo", "{}");
    assertTrue(invalid.isError() && invalid.content().contains("text"), "the schema is checked: " + invalid.content());
  }

  @Test
  void imagesAreSavedAndNamedByTheirPath() throws Exception {
    startFake();
    ToolResult r = call("mcp__fake__picture", "{}");
    assertFalse(r.isError(), r.content());
    try (Stream<Path> files = Files.list(dir.resolve("media").resolve("fake"))) {
      Path png = files.findFirst().orElseThrow();
      assertTrue(png.toString().endsWith(".png"), png.toString());
      assertEquals(4, Files.size(png));
      assertTrue(r.content().contains(png.toString()), r.content());
    }
  }

  @Test
  void aServerThatDiesFailsTheCallAndTakesItsToolsAway() {
    startFake();
    ToolResult r = call("mcp__fake__die", "{}");
    assertTrue(r.isError(), r.content());
    assertTrue(r.content().contains("server"), r.content());
    await(() -> names().isEmpty(), "the tools are gone");

    StringBuilder out = new StringBuilder();
    Commands.run(root, "mcp", "", new Session("x", out::append, new CancelToken()));
    assertTrue(out.toString().contains("fake  exited (exit 3)"), out.toString());
    assertTrue(out.toString().contains("fake server starting"), "stderr tells why: " + out);
  }

  @Test
  void cancellingACallTellsTheServer() throws Exception {
    startFake();
    CancelToken token = new CancelToken();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(300);
        } catch (InterruptedException e) {
          return;
        }
        token.cancel();
      });
      assertThrows(CancelledException.class,
          () -> Tools.call(root, new ToolCall("x", "mcp__fake__wait", "{}"), new ToolContext("c", token)));
    });
    await(() -> notesContain("notifications/cancelled"), "the server hears the cancel");
    assertTrue(notes().contains("\"reason\":\"cancelled\""), notes());
  }

  @Test
  void aCallPastItsTimeLimitIsCancelled() throws Exception {
    start(fake(), 1);
    await(() -> names().contains("mcp__fake__wait"), "the tools are listed");
    ToolResult r = call("mcp__fake__wait", "{}");
    assertTrue(r.isError() && r.content().contains("timed out"), r.content());
    await(() -> notesContain("timed out"), "the server hears the cancel");
  }

  @Test
  void aChangedListIsReadAgain() {
    startFake();
    assertFalse(names().contains("mcp__fake__extra"));
    assertEquals("grown", call("mcp__fake__grow", "{}").content());
    await(() -> names().contains("mcp__fake__extra"), "the new tool shows");
  }

  @Test
  void disposingThePluginStopsTheServer() throws Exception {
    startFake();
    ProcessHandle server = server();
    assertTrue(server.isAlive());
    root.dispose();
    assertFalse(server.isAlive(), "the server is gone once the plugin is");
  }

  @Test
  void restartStartsANewProcess() throws Exception {
    startFake();
    ProcessHandle first = server();
    StringBuilder out = new StringBuilder();
    Commands.run(root, "mcp", "restart fake", new Session("x", out::append, new CancelToken()));
    assertTrue(out.toString().contains("restarted"), out.toString());
    assertFalse(first.isAlive());
    await(() -> names().contains("mcp__fake__grow"), "the tools are back");
    assertTrue(server().pid() != first.pid());
  }

  @Test
  void aServerThatCannotStartIsReported() {
    start(new McpConfig.Server("/no/such/program", List.of()), 5);
    await(() -> mcp().contains("failed"), "the server is reported");
    String out = mcp();
    assertTrue(out.contains("fake  failed  could not start /no/such/program"), out);
    assertTrue(names().isEmpty());
  }

  private static McpConfig.Server server(String type, String command, String url, String risk) {
    return new McpConfig.Server(type, command, null, null, null, url, null, risk, null, null, null);
  }

  @Test
  void filteredToolsAreNotOfferedButListed() {
    McpConfig.Server f = fake();
    start(new McpConfig.Server(null, f.command(), f.args(), null, null, null, null, null, null, null,
        List.of("die", "wait")), 30);
    await(() -> names().contains("mcp__fake__grow"), "the tools are listed");
    assertFalse(names().contains("mcp__fake__die"));
    assertFalse(names().contains("mcp__fake__wait"));
    assertTrue(names().contains("mcp__fake__echo"));
    assertTrue(mcp().contains("  mcp__fake__die (filtered)"), mcp());
    assertTrue(f.offers("die"));
    McpConfig.Server only = new McpConfig.Server(null, "x", null, null, null, null, null, null, null,
        List.of("echo"), List.of("echo"));
    assertFalse(only.offers("echo"), "exclude wins");
    assertFalse(only.offers("grow"), "only the included ones");
  }

  @Test
  void theServersInstructionsGoIntoTheSystemPrompt() {
    startFake();
    List<Chat.Section> sections = root.collect(Chat.CONTEXT, new Chat.ContextQuery("c"));
    Chat.Section mcp = sections.stream().filter(x -> x.name().equals(McpPlugin.SECTION)).findFirst().orElseThrow();
    assertTrue(mcp.text().contains("### fake\nUse echo to repeat text."), mcp.text());
    assertTrue(mcp.text().startsWith("The following MCP servers have provided instructions"), mcp.text());
  }

  @Test
  void configIsCheckedAndLooksLikeMcpJson() {
    assertThrows(IllegalArgumentException.class, () -> server("http", "x", null, null));
    assertThrows(IllegalArgumentException.class, () -> server(null, " ", null, null));
    assertThrows(IllegalArgumentException.class, () -> server(null, "x", null, "dangerous"));
    assertThrows(IllegalArgumentException.class, () -> server("websocket", null, "http://h/mcp", null));
    assertThrows(IllegalArgumentException.class, () -> server("stdio", "x", "http://h/mcp", null));
    assertThrows(IllegalArgumentException.class, () -> server("sse", null, "ftp://h/mcp", null));
    assertThrows(IllegalArgumentException.class, () -> server("http", null, null, null));
    assertThrows(IllegalArgumentException.class,
        () -> new McpConfig(Map.of("bad name", new McpConfig.Server("x", null)), null, 0, 0));
    McpConfig.Server s = new McpConfig.Server("stdio", "npx", List.of("-y", "chrome-devtools-mcp@latest"), null, null,
        null, null, null, null, null, null);
    assertEquals("network", s.risk());
    assertTrue(s.enabled());
    assertTrue(s.local());
    assertEquals(List.of("npx", "-y", "chrome-devtools-mcp@latest"), s.commandLine());
    McpConfig.Server remote = server(null, null, "https://mcp.example.com/mcp", null);
    assertEquals("http", remote.type(), "a url without a type is Streamable HTTP");
    assertFalse(remote.local());
    assertEquals("sse", server("SSE", null, "http://localhost:1/sse", null).type());
    assertEquals("mcp__chrome-devtools__take_screenshot", McpPlugin.toolName("chrome-devtools", "take_screenshot"));
    assertEquals(64, McpPlugin.toolName("s", "x".repeat(100)).length());
  }
}
