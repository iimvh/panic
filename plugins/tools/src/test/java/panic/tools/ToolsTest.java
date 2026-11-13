package panic.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.ConfigHandle;
import context.Context;
import context.Root;
import context.Task;
import core.CancelledException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Session;
import panic.util.CancelToken;
import panic.util.Json;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;

class ToolsTest {

  private final Root root = Context.root("t");

  @AfterEach
  void dispose() {
    root.dispose();
  }

  /** {@code echo {text}}: answers its text; "boom" throws. */
  private static Tool echo(String description) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("echo", description, Json.parseOrNull("""
            {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        String text = args.get("text").asText();
        if (text.equals("boom")) throw new IllegalStateException("exploded");
        return ToolResult.ok(ctx.conversationId() + ":" + text);
      }
    };
  }

  private ToolResult call(String name, String args) {
    return Tools.call(root, new ToolCall("id", name, args), new ToolContext("c", new CancelToken()));
  }

  /** Installs a plugin offering {@code tool}; configs true/false add/remove it. */
  private ConfigHandle<Boolean> offer(String name, Tool tool) {
    return root.plugin(name, (ctx, on) -> {
      if (on) Tools.register(ctx, tool);
      return null;
    }, true);
  }

  @Test
  void theToolSeesItsCallersContextAndReportsProgress() {
    List<ToolProgress> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
    offer("slow", new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("slow", "", Json.parseOrNull("{\"type\":\"object\"}"));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public int timeoutSeconds() {
        return 600;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        ctx.progress().accept(ToolProgress.text("helper", "working"));
        return new ToolResult(ctx.origin() + "/" + ctx.agent() + "/" + ctx.traceId() + "/" + ctx.spanId(), false,
            new panic.llm.Usage(3, 4));
      }
    });
    assertEquals(600, Tools.info(root, "slow").orElseThrow().timeoutSeconds());

    ToolContext tc = new ToolContext("c~x-1", new CancelToken(), null, "c", "x", "trace", "span", heard::add);
    ToolResult r = Tools.call(root, new ToolCall("id", "slow", "{}"), tc);
    assertEquals("c/x/trace/span", r.content());
    assertEquals(new panic.llm.Usage(3, 4), r.usage());
    assertEquals(List.of(ToolProgress.text("helper", "working")), heard);
  }

  @Test
  void aToolWithoutAnOwnLimitLeavesItToTheCaller() {
    offer("echoer", echo("first"));
    assertEquals(0, Tools.info(root, "echo").orElseThrow().timeoutSeconds());
    ToolContext plain = new ToolContext("c", new CancelToken());
    assertEquals("c", plain.origin());
    assertEquals(null, plain.agent());
  }

  @Test
  void toolsLiveAsLongAsTheirPlugin() {
    ConfigHandle<Boolean> plugin = offer("echoer", echo("first"));
    assertEquals(List.of("echo"), Tools.specs(root).stream().map(ToolSpec::name).toList());
    assertEquals(ToolResult.ok("c:hi"), call("echo", "{\"text\":\"hi\"}"));

    plugin.update(false);
    assertTrue(Tools.specs(root).isEmpty());
    assertEquals(ToolResult.error("unknown tool: echo"), call("echo", "{\"text\":\"hi\"}"));

    plugin.update(true);
    assertTrue(Tools.find(root, "echo").isPresent());
  }

  @Test
  void firstToolOfANameWins() {
    offer("one", echo("first"));
    offer("two", echo("second"));
    assertEquals(1, Tools.specs(root).size());
    assertEquals("first", Tools.find(root, "echo").orElseThrow().description());
  }

  @Test
  void badCallsBecomeErrorResults() {
    offer("echoer", echo(""));
    ToolResult notJson = call("echo", "{text:");
    assertTrue(notJson.isError());
    assertTrue(notJson.content().contains("not valid JSON"), notJson.content());

    ToolResult missing = call("echo", "{}");
    assertTrue(missing.isError());
    assertTrue(missing.content().contains("text: required"), missing.content());

    ToolResult wrongType = call("echo", "{\"text\":3}");
    assertTrue(wrongType.content().contains("text: expected string, got integer"), wrongType.content());

    ToolResult thrown = call("echo", "{\"text\":\"boom\"}");
    assertEquals(ToolResult.error("echo failed: exploded"), thrown);
  }

  @Test
  void disposingThePluginCancelsARunningToolAndWaitsForIt() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean returned = new AtomicBoolean();
    ConfigHandle<Boolean> plugin = offer("slow", new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("slow", "", null);
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        CountDownLatch release = new CountDownLatch(1);
        ctx.signal().onCancel(release::countDown);
        started.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        returned.set(true);
        ctx.signal().throwIfCancelled();
        return ToolResult.ok("done");
      }
    });

    Task<ToolResult> pending = root.spawn("caller", signal -> call("slow", "{}"));
    started.await();
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> plugin.update(false));
    assertTrue(returned.get(), "disposal waited for the call");
    assertThrows(CancelledException.class, pending::await);
  }

  @Test
  void cancellingTheCallerCancelsTheTool() {
    offer("echoer", echo(""));
    CancelToken token = new CancelToken();
    token.cancel();
    assertThrows(CancelledException.class,
        () -> Tools.call(root, new ToolCall("id", "echo", "{\"text\":\"x\"}"), new ToolContext("c", token)));
  }

  @Test
  void commandsListAndRunTools() {
    root.plugin("tools", new ToolsPlugin());
    offer("echoer", echo("says it back"));
    StringBuilder out = new StringBuilder();
    Session session = new Session("me", out::append, new CancelToken());

    assertTrue(Commands.run(root, "tools", "", session));
    assertEquals("  echo [read: allow]  says it back\n", out.toString());

    out.setLength(0);
    Commands.run(root, "tool", "echo {\"text\": \"hi there\"}", session);
    assertEquals("me:hi there\n", out.toString());

    out.setLength(0);
    Commands.run(root, "tool", "echo", session);
    assertEquals("error: echo: invalid arguments: text: required\n", out.toString());

    out.setLength(0);
    Commands.run(root, "tool", "nope", session);
    assertEquals("error: unknown tool: nope\n", out.toString());
  }

  @Test
  void clockTellsTheTime() {
    root.plugin("clock", new ClockPlugin());
    ToolResult utc = call("time", "{\"timezone\":\"UTC\"}");
    assertFalse(utc.isError(), utc.content());
    assertTrue(utc.content().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z\\[UTC] \\(\\w+day\\)"), utc.content());
    assertTrue(call("time", "{\"timezone\":\"Mars/Base\"}").isError());
    assertFalse(call("time", "{}").isError());
  }
}
