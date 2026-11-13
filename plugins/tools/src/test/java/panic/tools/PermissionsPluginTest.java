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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.CancelToken;
import panic.llm.Json;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;

/** The permission gate in {@link Tools}, the {@code permissions} policy and the approval hook. */
class PermissionsPluginTest {

  private final Root root = Context.root("t");
  private final AtomicInteger ran = new AtomicInteger();
  private final List<Tools.Approval> asked = new CopyOnWriteArrayList<>();

  @AfterEach
  void dispose() {
    root.dispose();
  }

  /** {@code name {n?}}: a tool of {@code risk} that counts its runs. */
  private Tool tool(String name, Risk risk) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec(name, "", Json.parseOrNull(
            "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"additionalProperties\":false}"));
      }

      @Override
      public Risk risk() {
        return risk;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        ran.incrementAndGet();
        return ToolResult.ok(name + " ran");
      }
    };
  }

  @BeforeEach
  void tools() {
    root.plugin("tools-under-test", ctx -> {
      Tools.register(ctx, tool("look", Risk.READ));
      Tools.register(ctx, tool("change", Risk.WRITE));
      Tools.register(ctx, tool("run", Risk.EXEC));
      return null;
    });
  }

  private ToolResult call(String name) {
    return call(name, "{}", "c");
  }

  private ToolResult call(String name, String args, String conversation) {
    return Tools.call(root, new ToolCall("id", name, args), new ToolContext(conversation, new CancelToken()));
  }

  /** A front-end that answers for conversation {@code c} only. */
  private void approver(Function<Tools.Approval, Tools.Decision> answer) {
    root.plugin("front-end", ctx -> {
      ctx.hook(Tools.APPROVE, a -> {
        if (!a.conversationId().equals("c")) return null;
        asked.add(a);
        return answer.apply(a);
      });
      return null;
    });
  }

  private ConfigHandle<PermissionsConfig> policy(PermissionsConfig cfg) {
    return root.plugin("permissions", new PermissionsPlugin(), cfg);
  }

  @Test
  void withoutPolicyReadsRunAndTheRestNeedsAnAnswer() {
    assertEquals(ToolResult.ok("look ran"), call("look"));
    assertEquals(ToolResult.error("change: denied by the user"), call("change"));
    assertEquals(ToolResult.error("run: denied by the user"), call("run"));
    assertEquals(1, ran.get());
  }

  @Test
  void theUserDecides() {
    approver(a -> a.call().name().equals("change") ? Tools.Decision.ALLOW : Tools.Decision.DENY);
    assertEquals(ToolResult.ok("change ran"), call("change"));
    assertEquals(ToolResult.error("run: denied by the user"), call("run"));
    assertEquals(List.of(Risk.WRITE, Risk.EXEC), asked.stream().map(Tools.Approval::risk).toList());
    assertEquals("c", asked.get(0).conversationId());

    // another conversation has no front-end to ask: denied
    assertEquals(ToolResult.error("change: denied by the user"), call("change", "{}", "elsewhere"));
    assertEquals(1, ran.get());
  }

  @Test
  void invalidCallsAreRefusedBeforeAnyoneIsAsked() {
    approver(a -> Tools.Decision.ALLOW);
    assertTrue(call("change", "{\"n\":\"x\"}", "c").content().contains("expected integer"));
    assertTrue(call("change", "{oops", "c").content().contains("not valid JSON"));
    assertTrue(asked.isEmpty());
  }

  @Test
  void rulesPerRiskAndPerTool() {
    approver(a -> Tools.Decision.DENY);
    policy(new PermissionsConfig(Map.of("write", "allow", "read", "deny"), Map.of("run", "allow", "look", "ask"), 0));
    assertEquals(ToolResult.ok("change ran"), call("change"));
    assertEquals(ToolResult.ok("run ran"), call("run"));
    assertEquals(ToolResult.error("look: denied by the user"), call("look"));
    assertEquals(1, asked.size());

    root.plugin("more", ctx -> {
      Tools.register(ctx, tool("peek", Risk.READ));
      return null;
    });
    assertEquals(ToolResult.error("peek: denied by policy"), call("peek"));
    assertEquals(1, asked.size(), "a denied call is never asked");
  }

  @Test
  void policyChangesTakeEffectWithoutRestartingOtherPlugins() {
    AtomicInteger starts = new AtomicInteger();
    root.plugin("bystander", (ctx, cfg) -> {
      starts.incrementAndGet();
      return null;
    }, "x");
    ConfigHandle<PermissionsConfig> permissions = policy(PermissionsConfig.DEFAULT);
    assertEquals(ToolResult.ok("look ran"), call("look"));

    permissions.update(new PermissionsConfig(null, Map.of("look", "deny"), 0));
    assertEquals(ToolResult.error("look: denied by policy"), call("look"));
    permissions.update(PermissionsConfig.DEFAULT);
    assertEquals(ToolResult.ok("look ran"), call("look"));
    assertEquals(1, starts.get());
  }

  @Test
  void configIsNormalizedAndChecked() {
    PermissionsConfig cfg = new PermissionsConfig(Map.of("WRITE", " Allow "), Map.of(" x ", "DENY"), -1);
    assertEquals(Map.of("read", "allow", "write", "allow", "exec", "ask", "network", "ask"), cfg.risks());
    assertEquals(Map.of("x", "deny"), cfg.tools());
    assertEquals(Tools.Verdict.DEFAULT_ASK_TIMEOUT_SECONDS, cfg.askTimeoutSeconds());
    assertEquals(cfg, new PermissionsConfig(Map.of("write", "allow"), Map.of("x", "deny"), 0));

    assertThrows(IllegalArgumentException.class, () -> new PermissionsConfig(Map.of("disk", "allow"), null, 0));
    assertThrows(IllegalArgumentException.class, () -> new PermissionsConfig(Map.of("write", "maybe"), null, 0));
    assertThrows(IllegalArgumentException.class, () -> new PermissionsConfig(null, Map.of("x", "yes"), 0));
  }

  @Test
  void startedRunsOnlyForCallsThatRun() {
    approver(a -> Tools.Decision.DENY);
    AtomicInteger started = new AtomicInteger();
    for (String name : List.of("look", "change", "nope")) {
      Tools.call(root, new ToolCall("id", name, "{}"), new ToolContext("c", new CancelToken(), started::incrementAndGet));
    }
    Tools.call(root, new ToolCall("id", "look", "{\"n\":true}"),
        new ToolContext("c", new CancelToken(), started::incrementAndGet));
    assertEquals(1, started.get());
  }

  /** Blocks until the approval's signal is cancelled, then gives up. */
  private static Tools.Decision blockUntilCancelled(Tools.Approval a, CountDownLatch waiting) {
    CountDownLatch release = new CountDownLatch(1);
    a.signal().onCancel(release::countDown);
    waiting.countDown();
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    a.signal().throwIfCancelled();
    return Tools.Decision.ALLOW;
  }

  @Test
  void anUnansweredAskTimesOutAsDenied() {
    approver(a -> blockUntilCancelled(a, new CountDownLatch(1)));
    policy(new PermissionsConfig(null, null, 1));
    ToolResult r = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> call("change"));
    assertEquals(ToolResult.error("change: denied by the user"), r);
    assertEquals(0, ran.get());
  }

  @Test
  void cancellingTheCallerWhileAskingCancelsTheCall() throws Exception {
    CountDownLatch waiting = new CountDownLatch(1);
    approver(a -> blockUntilCancelled(a, waiting));
    CancelToken caller = new CancelToken();
    Task<ToolResult> pending = root.spawn("caller",
        s -> Tools.call(root, new ToolCall("id", "change", "{}"), new ToolContext("c", caller)));
    waiting.await();
    caller.cancel();
    assertThrows(CancelledException.class, () -> pending.await(10, TimeUnit.SECONDS));
    assertEquals(0, ran.get());
  }

  @Test
  void theToolsCommandShowsRiskAndPermission() {
    root.plugin("tools", new ToolsPlugin());
    policy(new PermissionsConfig(null, Map.of("run", "deny"), 0));
    StringBuilder out = new StringBuilder();
    assertTrue(Commands.run(root, "tools", "", new Session("me", out::append, new CancelToken())));
    assertEquals("  change [write: ask]\n  look [read: allow]\n  run [exec: deny]\n", out.toString());

    out.setLength(0);
    Commands.run(root, "tool", "run", new Session("me", out::append, new CancelToken()));
    assertEquals("error: run: denied by policy\n", out.toString());
    assertFalse(ran.get() > 0);
  }
}
