package panic.interact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.Root;
import context.Task;
import core.CancelledException;
import core.Signal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.chat.Chat;
import panic.chat.ChatConfig;
import panic.chat.ChatPlugin;
import panic.chat.Conversation;
import panic.chat.Conversations;
import panic.command.Commands;
import panic.command.Session;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;

/** The {@code ask_user} tool in a real tree, with a fake user answering {@link AskUser#ASK} for conversation c. */
class AskUserPluginTest {

  private final Root root = Context.root("t");
  private final List<AskUser.Request> asked = new CopyOnWriteArrayList<>();
  private volatile Function<AskUser.Request, AskUser.Answers> user =
      r -> new AskUser.Answers(List.of(new AskUser.Answer(List.of("Redis"), null)));
  private ConfigHandle<AskUserConfig> plugin;

  @BeforeEach
  void setUp() {
    root.plugin("user", ctx -> {
      ctx.hook(AskUser.ASK, r -> {
        if (!r.conversationId().equals("c")) return null;
        asked.add(r);
        return user.apply(r);
      });
      return null;
    });
    plugin = root.plugin("ask-user", new AskUserPlugin(), AskUserConfig.DEFAULT);
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private static final String ONE = """
      {"questions":[{"question":"Which cache?","header":"Cache","options":[
        {"label":"Redis","description":"shared"},{"label":"In memory"}]}]}""";

  private static final String TWO = """
      {"questions":[
        {"question":"Which cache?","options":[{"label":"Redis"},{"label":"In memory"}]},
        {"question":"What to cache?","multiSelect":true,"options":[{"label":"users"},{"label":"posts"},{"label":"tags"}]}]}""";

  private ToolResult call(String args) {
    return call(args, new ToolContext("c", new CancelToken()));
  }

  private ToolResult call(String args, ToolContext tc) {
    return Tools.call(root, new ToolCall("q", AskUser.TOOL, args), tc);
  }

  @Test
  void theQuestionsReachTheUserAndTheAnswersTheModel() {
    ToolResult r = call(ONE);
    assertEquals(ToolResult.ok("The user answered:\n\"Which cache?\" → Redis"), r);
    AskUser.Question q = asked.get(0).questions().get(0);
    assertEquals("Cache", q.header());
    assertEquals(List.of(new AskUser.Option("Redis", "shared"), new AskUser.Option("In memory", "")), q.options());
    assertFalse(q.multiSelect());

    user = req -> new AskUser.Answers(List.of(new AskUser.Answer(null, "Caffeine"),
        new AskUser.Answer(List.of("users", "tags"), "and sessions")));
    assertEquals(ToolResult.ok("The user answered:\n\"Which cache?\" → (other: Caffeine)\n"
        + "\"What to cache?\" → users, tags (other: and sessions)"), call(TWO));
    assertTrue(asked.get(1).questions().get(1).multiSelect());

    user = req -> new AskUser.Answers(List.of(new AskUser.Answer(List.of("Redis"), null), new AskUser.Answer(null, null)));
    assertTrue(call(TWO).content().endsWith("\"What to cache?\" → (no answer)"));
  }

  @Test
  void badQuestionsAreRefusedBeforeAnyoneIsAsked() {
    assertTrue(call("{\"questions\":[]}").content().contains("ask 1 to 4 questions, not 0"));
    assertTrue(call("{\"questions\":[{\"question\":\"x?\",\"options\":[{\"label\":\"a\"}]}]}").content()
        .contains("needs 2 to 6 options, not 1"));
    assertTrue(call("{\"questions\":[{\"question\":\"x?\",\"options\":[{\"label\":\"a\"},{\"label\":\"a\"}]}]}")
        .content().contains("repeats option 'a'"));
    assertTrue(call("{\"questions\":[{\"question\":\" \",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"}]}]}")
        .content().contains("question is empty"));
    plugin.update(new AskUserConfig(0, 1, 0));
    assertTrue(call(TWO).content().contains("ask 1 to 1 questions, not 2"));
    assertTrue(asked.isEmpty());
  }

  @Test
  void noAnswerOrASkipLetsTheAgentGoOn() {
    ToolResult none = call(ONE, new ToolContext("nobody-here", new CancelToken()));
    assertTrue(none.isError());
    assertTrue(none.content().contains("did not answer (no answer)"), none.content());

    user = r -> new AskUser.Answers(List.of(new AskUser.Answer(null, " ")));
    assertTrue(call(ONE).content().contains("did not answer (skipped)"));
  }

  @Test
  void onlyTheMainAgentAsks() {
    ToolContext sub = new ToolContext("c~explore-1", new CancelToken(), null, "c", "explore", null, null, p -> { });
    assertTrue(call(ONE, sub).content().contains("sub-agents cannot ask the user"));
    assertTrue(asked.isEmpty());
  }

  /** The user never answers, but honours the request's signal. */
  private void silentUser(CountDownLatch reached) {
    user = r -> {
      reached.countDown();
      while (!r.signal().isCancelled()) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          return null;
        }
      }
      return null;
    };
  }

  @Test
  void unansweredQuestionsTimeOut() {
    plugin.update(new AskUserConfig(1, 0, 0));
    silentUser(new CountDownLatch(1));
    ToolResult r = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> call(ONE));
    assertTrue(r.content().contains("did not answer (no answer)"), r.content());
  }

  @Test
  void disposingThePluginCancelsAWaitingQuestion() throws Exception {
    CountDownLatch reached = new CountDownLatch(1);
    silentUser(reached);
    Task<ToolResult> running = root.spawn("ask", s -> call(ONE));
    assertTrue(reached.await(5, TimeUnit.SECONDS));
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> plugin.update(new AskUserConfig(5, 0, 0)));
    assertThrows(CancelledException.class, running::await);
  }

  @Test
  void itStaysAvailableInPlanMode() {
    root.plugin("planner", new panic.planner.PlannerPlugin(), panic.planner.PlannerConfig.DEFAULT);
    Commands.run(root, "plan", "on", new Session("c", s -> { }, new CancelToken()));
    assertTrue(Tools.specs(root, "c").stream().map(ToolSpec::name).toList().contains(AskUser.TOOL));
    assertEquals(ToolResult.ok("The user answered:\n\"Which cache?\" → Redis"), call(ONE));
  }

  // ---------------- a whole turn through chat ----------------

  static final class FakeLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    volatile Function<ChatRequest, Completion> answer;

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
      requests.add(r);
      return answer.apply(r);
    }
  }

  @Test
  void theTurnGoesOnWithTheAnswer() {
    FakeLlm llm = new FakeLlm();
    Map<String, Conversation> all = new ConcurrentHashMap<>();
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
    root.plugin("chat", new ChatPlugin(), new ChatConfig("main", 0, true, 0, 0, 0, 0, 0, null, null, null, 0));
    llm.answer = r -> {
      List<Message> results = r.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
      if (results.isEmpty()) {
        return new Completion("", "m", "tool_calls", Usage.NONE, List.of(new ToolCall("1", AskUser.TOOL, ONE)));
      }
      return new Completion("using " + results.get(0).content().substring(results.get(0).content().lastIndexOf(' ') + 1),
          "m", "stop", Usage.NONE);
    };

    Chat.Reply reply = Chat.ask(root, new Chat.Ask("c", "add a cache", new CancelToken(), d -> { })).orElseThrow();
    assertEquals("using Redis", reply.text());
    assertEquals(1, reply.toolCalls());
    assertTrue(llm.requests.get(0).messages().get(0).content().contains(AskUserPlugin.HINT));
  }
}
