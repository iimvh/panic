package panic.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import core.Signal;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversations;
import panic.util.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.Usage;
import panic.trace.Span;
import panic.trace.Trace;
import panic.tools.PermissionsConfig;
import panic.tools.PermissionsPlugin;
import panic.todo.TodoConfig;
import panic.todo.TodoPlugin;

/** The reviewer alone, then the whole verification loop: plan, check, answer, review. */
class ReviewPluginTest {

  /** Two scripts: one for the agent, one for the reviewer (recognised by its prompt). Last step repeats. */
  static final class TwoModels implements Llm {
    final List<ChatRequest> agent = new CopyOnWriteArrayList<>();
    final List<ChatRequest> reviewer = new CopyOnWriteArrayList<>();
    final Deque<Function<ChatRequest, Completion>> agentScript = new ArrayDeque<>();
    final Deque<String> verdicts = new ArrayDeque<>();

    TwoModels agent(Function<ChatRequest, Completion> step) {
      agentScript.add(step);
      return this;
    }

    TwoModels verdict(String text) {
      verdicts.add(text);
      return this;
    }

    /** The reviewer's model call fails with {@code e} instead of answering. */
    volatile RuntimeException reviewerFails;

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return "m";
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      return stream(r, s, d -> { });
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      if (!r.messages().isEmpty() && r.messages().get(0).content().equals(ReviewPlugin.PROMPT)) {
        reviewer.add(r);
        if (reviewerFails != null) throw reviewerFails;
        String v = verdicts.size() > 1 ? verdicts.poll() : verdicts.peek();
        return new Completion(v, "m", "stop", new Usage(7, 2));
      }
      agent.add(r);
      Function<ChatRequest, Completion> step = agentScript.size() > 1 ? agentScript.poll() : agentScript.peek();
      return step.apply(r);
    }
  }

  static Completion calls(ToolCall... calls) {
    return new Completion("", "m", "tool_calls", new Usage(10, 5), List.of(calls));
  }

  static Completion answer(String text) {
    return new Completion(text, "m", "stop", new Usage(10, 5));
  }

  @TempDir
  Path dir;

  private final Root root = Context.root("t");
  private final TwoModels llm = new TwoModels();
  private ConfigHandle<ReviewConfig> review;

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", Conversations.inMemory());
    root.plugin("permissions", new PermissionsPlugin(),
        new PermissionsConfig(null, Map.of("check", "allow", "todo_write", "allow"), 0));
    root.plugin("todo", new TodoPlugin(w -> { }), new TodoConfig(dir.resolve("todo").toString(), 0));
    review = root.plugin("review", new ReviewPlugin(), ReviewConfig.DEFAULT);
    root.plugin("chat", new ChatPlugin(), ChatConfig.DEFAULT);
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private Chat.Reply ask(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { })).orElseThrow();
  }

  private static ToolCall plan(String criteria) {
    return new ToolCall("p", "todo_write",
        "{\"items\":[{\"text\":\"make it pass\",\"status\":\"in_progress\"}],\"criteria\":[\"" + criteria + "\"]}");
  }

  @Test
  void withoutCriteriaNothingIsReviewed() {
    llm.agent(r -> answer("hello"));
    Chat.Reply reply = ask("hi");
    assertEquals(Chat.DONE, reply.stop());
    assertNull(reply.review());
    assertTrue(llm.reviewer.isEmpty(), "no criteria, no extra model call");
  }

  @Test
  void verdictsAreParsedLeniently() {
    Completion c = answer("x");
    assertEquals(new Chat.Verdict(false, "no tests", c.usage()),
        ReviewPlugin.verdict("Sure! ```json\n{\"pass\": false, \"reason\": \"no tests\"}\n```", c));
    assertTrue(ReviewPlugin.verdict("{\"pass\":true}", c).pass());
    assertNull(ReviewPlugin.verdict("looks fine to me", c));
    assertNull(ReviewPlugin.verdict("{\"pass\":\"yes\"}", c));
  }

  @Test
  void aFailingCheckKeepsTheLoopGoingAndARejectionGetsOneMoreAttempt() {
    root.plugin("checks", new ChecksPlugin(), new ChecksConfig(dir.toString(),
        Map.of("test", new ChecksConfig.Check(List.of("sh", "-c", "test -f fixed && echo ok || { echo boom; exit 1; }"), 0)),
        0));
    ToolCall check = new ToolCall("k", "check", "{\"name\":\"test\"}");
    llm.agent(r -> calls(plan("the test check passes")))
        .agent(r -> calls(check))
        .agent(r -> {
          try {
            java.nio.file.Files.writeString(dir.resolve("fixed"), "");
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          return answer("fixed, I think");
        })
        .agent(r -> calls(new ToolCall("k2", "check", "{\"name\":\"test\"}")))
        .agent(r -> answer("fixed, and the check passes"));
    llm.verdict("{\"pass\": false, \"reason\": \"the check was not run after the fix\"}")
        .verdict("{\"pass\": true, \"reason\": \"the last check passed\"}");

    Chat.Reply reply = ask("make the test pass");
    assertEquals("fixed, and the check passes", reply.text());
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("the last check passed", reply.review());

    Message failed = llm.agent.get(2).messages().stream().filter(m -> m.role() == Role.TOOL).toList().get(1);
    assertTrue(failed.content().contains("check test failed (exit 1") && failed.content().contains("boom"),
        "the model saw the failure: " + failed.content());

    String brief = llm.reviewer.get(0).messages().get(1).content();
    assertTrue(brief.contains("1. the test check passes"), brief);
    assertTrue(brief.contains("boom"), "the reviewer sees tool results: " + brief);
    assertTrue(brief.endsWith("Final answer:\nfixed, I think"), brief);
    assertFalse(llm.reviewer.get(1).messages().get(1).content().contains("Final answer:\nfixed, I think"));
    assertEquals(2, llm.reviewer.size());
  }

  private final List<Chat.Reviewed> heard = new CopyOnWriteArrayList<>();

  private Chat.Reply askHeard(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { }, null, heard::add)).orElseThrow();
  }

  @Test
  void aReviewerThatCannotDecideAcceptsTheAnswerButSaysSo() {
    llm.agent(r -> calls(plan("says hi"))).agent(r -> answer("hi"));
    llm.verdict("I am not sure.");
    Chat.Reply reply = askHeard("greet me");
    assertEquals(Chat.DONE, reply.stop());
    assertNull(reply.review());
    assertEquals(1, llm.reviewer.size());
    assertEquals(1, heard.size());
    assertTrue(heard.get(0).error().contains("no verdict") && heard.get(0).error().contains("I am not sure."),
        heard.get(0).error());
  }

  @Test
  void aFailedReviewerCallIsReportedNotSwallowed() {
    llm.reviewerFails = new LlmException(400, "top_p must be 1 when using greedy sampling");
    llm.agent(r -> calls(plan("says hi"))).agent(r -> answer("hi"));
    List<Span> spans = new CopyOnWriteArrayList<>();
    root.plugin("spans", ctx -> {
      ctx.on(Trace.SPAN, spans::add);
      return null;
    });
    Chat.Reply reply = askHeard("greet me");
    assertEquals(Chat.DONE, reply.stop());
    assertEquals("hi", reply.text());
    assertTrue(heard.get(0).failed());
    assertTrue(heard.get(0).error().contains("top_p must be 1"), heard.get(0).error());
    Span review = spans.stream().filter(s -> s.name().equals("review")).findFirst().orElseThrow();
    assertEquals(Span.Status.ERROR, review.status());
    assertTrue(review.error().contains("top_p must be 1"), review.error());
  }

  @Test
  void theReviewerSendsOnlyWhatIsConfigured() {
    llm.agent(r -> calls(plan("says hi"))).agent(r -> answer("hi"));
    llm.verdict("{\"pass\": true}");
    ask("greet me");
    ChatRequest sent = llm.reviewer.get(0);
    assertNull(sent.temperature(), "no temperature unless configured: the model's default applies");
    assertNull(sent.model());
    assertEquals(512, sent.maxTokens());
    assertTrue(sent.tools().isEmpty());

    review.update(new ReviewConfig(null, 100, 0, 0.3));
    llm.reviewer.clear();
    ask("greet me again");
    assertEquals(0.3, llm.reviewer.get(0).temperature());
    assertEquals(100, llm.reviewer.get(0).maxTokens());
  }
}
