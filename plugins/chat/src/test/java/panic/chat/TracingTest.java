package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static panic.chat.AgentLoopTest.ECHO;
import static panic.chat.AgentLoopTest.answer;
import static panic.chat.AgentLoopTest.call;
import static panic.chat.AgentLoopTest.calls;

import context.Context;
import context.Root;
import core.CancelledException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.util.CancelToken;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Usage;
import panic.tools.Tools;
import panic.trace.Span;
import panic.trace.Trace;

/** The spans a turn emits on {@link Trace#SPAN}: one tree per turn, however the turn ends. */
class TracingTest {

  private final Root root = Context.root("t");
  private final AgentLoopTest.ScriptedLlm llm = new AgentLoopTest.ScriptedLlm();
  private final List<Span> spans = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("tools-under-test", ctx -> {
      Tools.register(ctx, ECHO);
      return null;
    });
    root.plugin("tracer", ctx -> {
      ctx.on(Trace.SPAN, spans::add);
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private void chat(ChatConfig cfg) {
    root.plugin("chat", new ChatPlugin(), cfg);
  }

  private Chat.Reply ask(String text, String traceId, CancelToken token) {
    return Chat.ask(root, new Chat.Ask("c", text, token, d -> { }, null, null, null, traceId)).orElseThrow();
  }

  private List<Span> named(String name) {
    return spans.stream().filter(s -> s.name().equals(name)).toList();
  }

  private Span root() {
    List<Span> roots = spans.stream().filter(Span::root).toList();
    assertEquals(1, roots.size(), "one root per turn: " + spans);
    return roots.get(0);
  }

  @Test
  void aTurnWithAToolIsOneTreeWithTimingsAndTokens() {
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("done"));

    Chat.Reply reply = ask("say hi", "trace-1", new CancelToken());

    assertEquals("trace-1", reply.traceId(), "the asker's trace id is used and returned");
    Span turn = root();
    assertEquals("turn", turn.name());
    assertEquals(turn, spans.get(spans.size() - 1), "the root ends last");
    assertTrue(spans.stream().allMatch(s -> s.traceId().equals("trace-1")));
    assertEquals("c", turn.stringAttr(Trace.CONVERSATION));
    assertEquals("say hi", turn.stringAttr("question"));
    assertEquals(Trace.questionHash("say hi"), turn.stringAttr(Trace.QUESTION_HASH));
    assertEquals(Chat.DONE, turn.stringAttr("stop"));
    assertEquals(2, turn.longAttr("steps", -1));
    assertEquals(1, turn.longAttr("toolCalls", -1));
    assertEquals(20, turn.longAttr("promptTokens", -1));
    assertEquals(10, turn.longAttr("completionTokens", -1));
    assertEquals(true, turn.attrs().get(Trace.SAVED));

    List<Span> llms = named("llm");
    assertEquals(2, llms.size());
    assertEquals(1, llms.get(0).longAttr("step", -1));
    assertEquals("tool_calls", llms.get(0).stringAttr("finishReason"));
    assertEquals(1, llms.get(0).longAttr("toolCalls", -1));
    assertTrue(llms.get(0).longAttr("firstTokenMicros", -1) >= 0, "a streamed tool call counts as the first token");
    assertEquals(10, llms.get(1).longAttr("promptTokens", -1));

    Span tool = named("tool").get(0);
    assertEquals("echo", tool.stringAttr("tool"));
    assertEquals("a", tool.stringAttr("callId"));
    assertEquals(2, tool.longAttr("outputChars", -1));
    assertEquals(false, tool.attrs().get("truncated"));
    assertTrue(tool.longAttr("approvalMicros", -1) >= 0);
    assertEquals(Span.Status.OK, tool.status());

    for (Span child : List.of(llms.get(0), llms.get(1), tool)) {
      assertEquals(turn.spanId(), child.parentId());
      assertTrue(child.startMicros() >= turn.startMicros());
    }
  }

  @Test
  void aChatPicksATraceIdWhenTheAskerHasNone() {
    chat(ChatConfig.DEFAULT);
    llm.then(r -> answer("hello"));
    Chat.Reply reply = ask("hi", null, new CancelToken());
    assertEquals(root().traceId(), reply.traceId());
  }

  @Test
  void toolFailuresAreErrorSpans() {
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "nope", "{}"))).then(r -> answer("sorry"));
    ask("go", null, new CancelToken());
    Span tool = named("tool").get(0);
    assertEquals(Span.Status.ERROR, tool.status());
    assertTrue(tool.error().contains("nope"), tool.error());
    assertEquals(Span.Status.OK, root().status(), "the turn itself went fine");
  }

  @Test
  void reviewsAreSpansWithTheirVerdictAndCost() {
    root.plugin("reviewer", ctx -> {
      ctx.hook(Chat.REVIEW, r -> new Chat.Verdict(r.attempt() > 1, "missing tests", new Usage(3, 1)));
      return null;
    });
    chat(new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, null, null, 2));
    llm.then(r -> answer("first")).then(r -> answer("second"));
    ask("do it", null, new CancelToken());

    List<Span> reviews = named("review");
    assertEquals(2, reviews.size());
    assertEquals("fail", reviews.get(0).stringAttr("verdict"));
    assertEquals("missing tests", reviews.get(0).stringAttr("reason"));
    assertEquals(3, reviews.get(0).longAttr("promptTokens", -1));
    assertEquals("pass", reviews.get(1).stringAttr("verdict"));
    assertEquals(2, reviews.get(1).longAttr("attempt", -1));
  }

  @Test
  void aBrokenReviewerLeavesAnErrorSpan() {
    root.plugin("reviewer", ctx -> {
      ctx.hook(Chat.REVIEW, r -> {
        throw new IllegalStateException("reviewer model down");
      });
      return null;
    });
    chat(ChatConfig.DEFAULT);
    llm.then(r -> answer("done"));
    ask("do it", null, new CancelToken());

    Span review = named("review").get(0);
    assertEquals(Span.Status.ERROR, review.status());
    assertEquals("reviewer model down", review.error());
    assertEquals(Span.Status.OK, root().status(), "the answer still stands");
  }

  @Test
  void withoutAReviewerThereIsNoReviewSpan() {
    chat(ChatConfig.DEFAULT);
    llm.then(r -> answer("hello"));
    ask("hi", null, new CancelToken());
    assertEquals(List.of("llm", "turn"), spans.stream().map(Span::name).toList());
  }

  @Test
  void compactionIsASpan() {
    chat(new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, 400, true));
    // no reported usage on answers, so the token estimate is not corrected (as in ContextPluginTest)
    llm.then(r -> r.messages().get(0).content().equals(ChatPlugin.SUMMARY_PROMPT)
        ? new Completion("S1", "m", "stop", new Usage(7, 3))
        : new Completion("ok", "m", "stop", Usage.NONE));
    for (int i = 1; i <= 5; i++) ask("q" + i + " " + "x".repeat(301), null, new CancelToken());

    List<Span> compactions = named("compaction");
    assertEquals(1, compactions.size(), "only the fifth question needs a summary");
    Span c = compactions.get(0);
    assertEquals(true, c.attrs().get("applied"));
    assertTrue(c.longAttr("messages", 0) > 0);
    assertEquals(7, c.longAttr("promptTokens", -1));
    assertEquals(Span.Status.OK, c.status());
  }

  @Test
  void aCancelledTurnIsTracedAsCancelledAndNotSaved() {
    chat(ChatConfig.DEFAULT);
    CancelToken token = new CancelToken();
    llm.then(r -> {
      token.cancel();
      throw new CancelledException("cancelled");
    });
    assertThrows(CancelledException.class, () -> ask("go", "t-c", token));

    Span turn = root();
    assertEquals(Span.Status.CANCELLED, turn.status());
    assertEquals(false, turn.attrs().get(Trace.SAVED));
    assertEquals(Span.Status.CANCELLED, named("llm").get(0).status());
  }

  @Test
  void aFailingModelIsAnErrorTrace() {
    chat(ChatConfig.DEFAULT);
    llm.then(r -> {
      throw new IllegalStateException("provider down");
    });
    assertThrows(IllegalStateException.class, () -> ask("go", null, new CancelToken()));
    Span turn = root();
    assertEquals(Span.Status.ERROR, turn.status());
    assertEquals("provider down", turn.error());
    assertNull(turn.stringAttr("model"), "no model answered");
  }

  @Test
  void aBrokenTracerNeverBreaksTheTurn() {
    root.plugin("broken-tracer", ctx -> {
      ctx.on(Trace.SPAN, s -> {
        throw new IllegalStateException("disk full");
      });
      return null;
    });
    chat(ChatConfig.DEFAULT);
    llm.then(r -> calls(call("a", "echo", "{\"text\":\"hi\"}"))).then(r -> answer("done"));
    assertEquals("done", ask("go", null, new CancelToken()).text());
    assertFalse(spans.isEmpty(), "other listeners still hear every span");
  }
}
