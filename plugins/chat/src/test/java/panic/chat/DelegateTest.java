package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static panic.chat.AgentLoopTest.answer;
import static panic.chat.AgentLoopTest.call;
import static panic.chat.AgentLoopTest.calls;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.Root;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.util.CancelToken;
import panic.util.Json;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolProgress;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.trace.Span;
import panic.trace.Trace;

/** Asks made by a sub-agent ({@link Chat.Delegate}) and what a tool shows of its own work while it runs. */
class DelegateTest {

  private final Root root = Context.root("t");
  private final AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
  private final List<Chat.Step> emitted = new CopyOnWriteArrayList<>();
  private final List<Span> spans = new CopyOnWriteArrayList<>();
  private final List<Tools.Approval> approvals = new CopyOnWriteArrayList<>();
  private final List<Chat.Turn> turns = new CopyOnWriteArrayList<>();
  private final java.util.Map<String, Conversation> all = new java.util.concurrent.ConcurrentHashMap<>();
  private final Conversations conversations = id -> all.computeIfAbsent(id, Conversation::new);

  /** A tool named {@code name} that answers {@code "<name> ran"}. */
  private static Tool tool(String name, Risk risk) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec(name, "", Json.parseOrNull("{\"type\":\"object\"}"));
      }

      @Override
      public Risk risk() {
        return risk;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        return ToolResult.ok(name + " ran");
      }
    };
  }

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, conversations);
      return null;
    });
    root.plugin("listeners", ctx -> {
      ctx.on(Chat.STEP, emitted::add);
      ctx.on(Chat.TURN, turns::add);
      ctx.on(Trace.SPAN, spans::add);
      ctx.hook(Tools.APPROVE, a -> {
        approvals.add(a);
        return Tools.Decision.ALLOW;
      });
      // a reviewer that rejects everything: a sub-agent must never meet it
      ctx.hook(Chat.REVIEW, r -> new Chat.Verdict(false, "never good enough", Usage.NONE));
      return null;
    });
    root.plugin("chat", new ChatPlugin(), new ChatConfig("main prompt", 0));
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private void tools(Tool... tools) {
    root.plugin("tools-under-test", ctx -> {
      for (Tool t : tools) Tools.register(ctx, t);
      return null;
    });
  }

  private Chat.Reply delegate(Chat.Delegate d, String traceId, List<Chat.Step> steps) {
    return Chat.ask(root, new Chat.Ask("c~explore-1", "find it", new CancelToken(), t -> { }, steps::add, null,
        null, traceId, null, d)).orElseThrow();
  }

  private static Chat.Delegate explore(Set<String> tools, int maxSteps, String parentSpanId) {
    return new Chat.Delegate("c", "explore", "you explore", tools, maxSteps, 0, parentSpanId);
  }

  @Test
  void contributorsHearTheOriginAndTheAgentOfASubAgent() {
    List<Chat.ContextQuery> queries = new CopyOnWriteArrayList<>();
    root.plugin("contributor", ctx -> {
      Chat.contribute(ctx, "Where", 1, q -> {
        queries.add(q);
        return "origin " + q.origin();
      });
      return null;
    });
    llm.then(r -> answer("found"));
    delegate(explore(null, 0, null), null, new CopyOnWriteArrayList<>());
    assertEquals(List.of(new Chat.ContextQuery("c~explore-1", "c", "explore")), queries);
    assertTrue(queries.get(0).delegated());
    assertTrue(llm.requests.get(0).messages().get(0).content().contains("## Where\norigin c"));

    queries.clear();
    llm.then(r -> answer("hi"));
    Chat.ask(root, new Chat.Ask("c", "hello", new CancelToken(), t -> { })).orElseThrow();
    assertEquals(List.of(new Chat.ContextQuery("c", "c", null)), queries);
    assertFalse(queries.get(0).delegated());
  }

  @Test
  void aSubAgentSeesOnlyItsToolsAndItsPrompt() {
    tools(tool("look", Risk.READ), tool("change", Risk.WRITE));
    llm.then(r -> calls(call("a", "change", "{}"), call("b", "look", "{}"))).then(r -> answer("found"));

    Chat.Reply reply = delegate(explore(Set.of("look"), 0, null), null, new CopyOnWriteArrayList<>());
    assertEquals("found", reply.text());
    assertEquals(Chat.DONE, reply.stop());
    assertNull(reply.review(), "no review for a sub-agent");

    var first = llm.requests.get(0);
    assertEquals(List.of("look"), first.tools().stream().map(ToolSpec::name).toList());
    assertTrue(first.messages().get(0).content().startsWith("you explore"));
    assertFalse(first.messages().get(0).content().contains("main prompt"));
    List<Message> results = llm.requests.get(1).messages().stream().filter(m -> m.role() == Role.TOOL).toList();
    assertEquals(List.of(Message.tool("a", Chat.TOOL_ERROR_PREFIX + "change is not available to this agent"),
        Message.tool("b", "look ran")), results);
    assertTrue(approvals.isEmpty(), "a refused call is never put to the user");

    assertEquals(5, conversations.get("c~explore-1").snapshot().messages().size(), "saved in its conversation");
    assertEquals(0, conversations.get("c").snapshot().messages().size());
    assertEquals("explore", turns.get(0).delegate().agent());
    assertEquals("c", conversations.get("c~explore-1").parent(), "kept out of listings");
  }

  @Test
  void aSubAgentHasItsOwnStepLimit() {
    tools(tool("look", Risk.READ));
    llm.then(r -> calls(call("a", "look", "{}")));
    assertEquals(Chat.MAX_STEPS, delegate(explore(null, 2, null), null, new CopyOnWriteArrayList<>()).stop());
    assertEquals(2, llm.requests.size());
  }

  @Test
  void approvalsGoToTheOriginNamingTheAgent() {
    tools(tool("change", Risk.WRITE));
    llm.then(r -> calls(call("a", "change", "{}"))).then(r -> answer("done"));
    delegate(explore(null, 0, null), null, new CopyOnWriteArrayList<>());
    assertEquals(1, approvals.size());
    assertEquals("c", approvals.get(0).conversationId());
    assertEquals("explore", approvals.get(0).agent());
  }

  @Test
  void theSubAgentsTurnNestsUnderTheGivenSpan() {
    llm.then(r -> answer("found"));
    delegate(explore(null, 0, "parent-span"), "trace-1", new CopyOnWriteArrayList<>());
    Span turn = spans.stream().filter(s -> s.name().equals("turn")).findFirst().orElseThrow();
    assertEquals("trace-1", turn.traceId());
    assertEquals("parent-span", turn.parentId());
    assertEquals("explore", turn.attrs().get("agent"));
  }

  /** A tool standing for a sub-agent: reports progress and spends tokens of its own. */
  private static Tool helper() {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("helper", "", Json.parseOrNull("{\"type\":\"object\"}"));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        ToolCall inner = new ToolCall("x", "look", "{}");
        ctx.progress().accept(ToolProgress.reasoning("explore", "hmm"));
        ctx.progress().accept(ToolProgress.text("explore", "thinking"));
        ctx.progress().accept(ToolProgress.step("explore", inner, null));
        ctx.progress().accept(ToolProgress.step("explore", inner, ToolResult.ok("seen")));
        return new ToolResult("helped " + ctx.traceId() + " " + (ctx.spanId() != null), false, new Usage(100, 50));
      }
    };
  }

  @Test
  void aToolsProgressReachesTheAskerAndItsTokensCount() {
    tools(helper());
    llm.then(r -> calls(call("a", "helper", "{}"))).then(r -> answer("done"));
    List<String> seen = new CopyOnWriteArrayList<>();
    Chat.Reply reply = Chat.ask(root, new Chat.Ask("c", "go", new CancelToken(), t -> { },
        s -> seen.add((s.nested() ? s.agent() + "/" + s.parentCallId() + " " : "") + s.call().name()
            + (s.done() ? " done" : "")),
        null, null, "trace-2", d -> seen.add(d.agent() + "/" + d.parentCallId() + (d.reasoning() ? " thinks " : " text ") + d.text()),
        null))
        .orElseThrow();

    assertEquals(List.of("helper", "explore/a thinks hmm", "explore/a text thinking", "explore/a look", "explore/a look done",
        "helper done"), seen);
    // three model calls (the main ask is reviewed, rejected and answers once more) and the tool's own tokens
    assertEquals(new Usage(30 + 100, 15 + 50), reply.usage(), "the tool's tokens are the turn's");
    assertEquals("helped trace-2 true",
        llm.requests.get(1).messages().stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow()
            .content());
    assertTrue(emitted.stream().noneMatch(Chat.Step::nested), "a sub-agent's turn emits its own steps");
  }

  @Test
  void aToolsOwnTimeLimitWins() {
    tools(new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("stuck", "", Json.parseOrNull("{\"type\":\"object\"}"));
      }

      @Override
      public Risk risk() {
        return Risk.READ;
      }

      @Override
      public int timeoutSeconds() {
        return 1;
      }

      @Override
      public ToolResult call(JsonNode args, ToolContext ctx) {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ctx.signal().onCancel(release::countDown); // released by the timeout
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        ctx.signal().throwIfCancelled();
        return ToolResult.ok("never");
      }
    });
    llm.then(r -> calls(call("a", "stuck", "{}"))).then(r -> answer("done"));
    Chat.ask(root, new Chat.Ask("c", "go", new CancelToken(), t -> { })).orElseThrow();
    assertEquals(Message.tool("a", Chat.TOOL_ERROR_PREFIX + "stuck timed out after 1s"),
        llm.requests.get(1).messages().stream().filter(m -> m.role() == Role.TOOL).findFirst().orElseThrow());
  }
}
