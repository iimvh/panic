package panic.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.Context;
import context.Root;
import core.CancelledException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.ToolCall;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;

/** The {@code mcp} plugin against {@link FakeHttpMcpServer}: Streamable HTTP and the legacy HTTP+SSE transport. */
class McpHttpTest {

  private final Root root = Context.root("t");
  private FakeHttpMcpServer fake;

  @BeforeEach
  void startServer() throws Exception {
    fake = new FakeHttpMcpServer().start();
  }

  @AfterEach
  void dispose() {
    root.dispose();
    fake.close();
  }

  private void start(String type, String path) {
    root.plugin("user", ctx -> {
      ctx.hook(Tools.APPROVE, a -> Tools.Decision.ALLOW);
      return null;
    });
    McpConfig.Server server = McpConfig.Server.remote(type, fake.url(path), Map.of("Authorization", "Bearer t0k"));
    root.plugin("mcp", new McpPlugin(w -> { }), new McpConfig(Map.of("web", server), null, 30, 10));
  }

  private void startHttp() {
    start("http", "/mcp");
    await(() -> names().contains("mcp__web__wait"), "the tools are listed");
  }

  private List<String> names() {
    return Tools.infos(root).stream().map(i -> i.spec().name()).toList();
  }

  private ToolResult call(String tool, String args) {
    return Tools.call(root, new ToolCall("x", tool, args), new ToolContext("c", new CancelToken()));
  }

  private String mcp() {
    StringBuilder out = new StringBuilder();
    Commands.run(root, "mcp", "", new Session("x", out::append, new CancelToken()));
    return out.toString();
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

  private boolean logged(String prefix) {
    return fake.count(prefix) > 0;
  }

  @Test
  void jsonAnswersCarryTheSessionVersionAndHeaders() {
    startHttp();
    assertEquals(List.of("mcp__web__echo", "mcp__web__grow", "mcp__web__wait"), names());
    ToolResult r = call("mcp__web__echo", "{\"text\":\"hi\"}");
    assertFalse(r.isError(), r.content());
    assertEquals("echo: hi", r.content());
    assertTrue(logged("POST initialize session=null version=null auth=Bearer t0k"), fake.log.toString());
    assertTrue(logged("POST tools/call session=s1 version=2025-06-18 auth=Bearer t0k"), fake.log.toString());
    assertTrue(mcp().contains("web  ready  fake-http 0.2, protocol 2025-06-18"), mcp());
  }

  @Test
  void eventStreamAnswersAreReadAndTheServersPingAnswered() {
    fake.sse = true;
    startHttp();
    ToolResult r = call("mcp__web__echo", "{\"text\":\"streamed\"}");
    assertFalse(r.isError(), r.content());
    assertEquals("echo: streamed", r.content());
    await(() -> logged("POST response srv-1 session=s1"), "the ping is answered");
  }

  @Test
  void theGetStreamCarriesListChanges() {
    startHttp();
    await(() -> logged("GET"), "the stream is open");
    assertEquals("grown", call("mcp__web__grow", "{}").content(), () -> fake.log + " " + mcp());
    await(() -> names().contains("mcp__web__extra"), "the new tool shows");
  }

  @Test
  void aServerWithoutAGetStreamStillWorks() {
    fake.stream = false;
    startHttp();
    await(() -> logged("GET"), "the stream is tried");
    assertEquals("echo: x", call("mcp__web__echo", "{\"text\":\"x\"}").content());
    assertTrue(mcp().contains("web  ready"), mcp());
  }

  @Test
  void anExpiredSessionIsInitializedAgainAndTheCallSentOnce() {
    startHttp();
    fake.expire();
    ToolResult r = call("mcp__web__echo", "{\"text\":\"again\"}");
    assertFalse(r.isError(), r.content());
    assertEquals("echo: again", r.content());
    assertEquals(2, fake.count("POST initialize"));
    assertTrue(logged("POST tools/call session=s2"), fake.log.toString());
  }

  @Test
  void cancellingACallTellsTheServer() {
    startHttp();
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
          () -> Tools.call(root, new ToolCall("x", "mcp__web__wait", "{}"), new ToolContext("c", token)));
    });
    await(() -> logged("cancelled"), "the server hears the cancel");
    assertTrue(fake.log.stream().anyMatch(l -> l.startsWith("cancelled") && l.contains("\"reason\":\"cancelled\"")),
        fake.log.toString());
  }

  @Test
  void disposingEndsTheSession() {
    startHttp();
    root.dispose();
    assertTrue(logged("DELETE s1"), fake.log.toString());
  }

  @Test
  void aServerThatGoesAwayIsReconnected() throws Exception {
    startHttp();
    await(() -> logged("GET"), "the stream is open");
    fake.stop();
    await(() -> mcp().contains("web  reconnecting"), "it notices: " + mcp());
    ToolResult away = call("mcp__web__echo", "{\"text\":\"x\"}");
    assertTrue(away.isError() && away.content().contains("reconnecting"), away.content());
    fake.restart();
    await(() -> mcp().contains("web  ready"), "it is back: " + mcp());
    await(() -> names().contains("mcp__web__echo"), "the tools are back");
    assertEquals("echo: back", call("mcp__web__echo", "{\"text\":\"back\"}").content());
  }

  @Test
  void aRefusedTokenIsReportedWithAHint() {
    fake.requireAuth = "Bearer other";
    start("http", "/mcp");
    await(() -> mcp().contains("HTTP 401"), "the refusal shows: " + mcp());
    assertTrue(mcp().contains("web  reconnecting  HTTP 401: missing token (set the server's \"headers\""), mcp());
  }

  @Test
  void theLegacySseTransportPostsToTheEndpointItIsGiven() {
    start("sse", "/sse");
    await(() -> names().contains("mcp__web__wait"), "the tools are listed");
    assertEquals("echo: old", call("mcp__web__echo", "{\"text\":\"old\"}").content());
    assertTrue(logged("legacy GET auth=Bearer t0k"), fake.log.toString());
    assertTrue(logged("legacy POST tools/call session=1"), fake.log.toString());
  }
}
