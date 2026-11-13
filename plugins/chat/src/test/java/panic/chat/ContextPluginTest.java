package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import context.ConfigHandle;
import context.Context;
import context.PluginHandle;
import context.Root;
import context.Task;
import core.CancelledException;
import core.Signal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.llm.CancelToken;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.LlmException;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.Usage;

/** The context window of the chat plugin: token budget, compaction and contributed sections. */
class ContextPluginTest {

  /**
   * Answers "ok" with no usage (so estimates stay exact) and recognises summary requests by their prompt.
   * Records both kinds apart.
   */
  static final class ContextLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final List<ChatRequest> summaries = new CopyOnWriteArrayList<>();
    volatile BiFunction<ChatRequest, Signal, Completion> onSummary =
        (r, s) -> new Completion("S" + summaries.size(), "m", "stop", new Usage(7, 3));

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
      Message first = r.messages().get(0);
      if (first.role() == Role.SYSTEM && first.content().equals(ChatPlugin.SUMMARY_PROMPT)) {
        summaries.add(r);
        return onSummary.apply(r, s);
      }
      requests.add(r);
      return new Completion("ok", "m", "stop", Usage.NONE);
    }

    ChatRequest last() {
      return requests.get(requests.size() - 1);
    }
  }

  private final Root root = Context.root("t");
  private final ContextLlm llm = new ContextLlm();
  private final Map<String, Conversation> all = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, llm);
      return null;
    });
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private static ChatConfig cfg(int contextTokens, boolean compact) {
    return new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, contextTokens, compact);
  }

  private ConfigHandle<ChatConfig> chat(ChatConfig cfg) {
    return root.plugin("chat", new ChatPlugin(), cfg);
  }

  private Chat.Reply ask(String text) {
    return Chat.ask(root, new Chat.Ask("c", text, new CancelToken(), d -> { })).orElseThrow();
  }

  /**
   * A question of 80 estimated tokens (4 + 304 chars / 4); with its "ok" answer (5) a turn costs 85.
   */
  private static String question(int n) {
    return "q" + n + " " + "x".repeat(301);
  }

  private void askQuestions(int from, int to) {
    for (int i = from; i <= to; i++) ask(question(i));
  }

  private static List<String> userTexts(ChatRequest r) {
    return r.messages().stream().filter(m -> m.role() == Role.USER).map(m -> m.content().split(" ")[0]).toList();
  }

  private Conversation conversation() {
    return all.get("c");
  }

  @Test
  void longHistoryIsCutByTokens() {
    chat(cfg(200, false));
    askQuestions(1, 5);
    // 200 - 80 for the question leaves 120: one earlier turn of 85
    assertEquals(List.of("q4", "q5"), userTexts(llm.last()));
    assertEquals(Role.USER, llm.last().messages().get(0).role());
    assertEquals(10, conversation().size(), "cutting what is sent does not touch the history");
    assertTrue(llm.summaries.isEmpty());
  }

  @Test
  void noLimitSendsEverything() {
    chat(cfg(0, true));
    askQuestions(1, 5);
    assertEquals(List.of("q1", "q2", "q3", "q4", "q5"), userTexts(llm.last()));
    assertTrue(llm.summaries.isEmpty());
  }

  @Test
  void compactionKeepsTheSummaryAndTheRecentTurns() {
    chat(cfg(400, true));
    askQuestions(1, 4); // 400 - 80 = 320 leaves room for three turns of 85
    assertTrue(llm.summaries.isEmpty());

    Chat.Reply reply = ask(question(5)); // four turns (340) no longer fit
    assertEquals(1, llm.summaries.size());
    String transcript = llm.summaries.get(0).messages().get(1).content();
    assertTrue(transcript.contains("q1 ") && transcript.contains("q3 "), transcript);
    assertFalse(transcript.contains("q4 "), "compacted down to half the budget: the newest turn stays");
    assertEquals(new Usage(7, 3), reply.usage(), "the summary is paid by the turn that needed it");

    assertEquals("S1", conversation().summary());
    assertEquals(List.of("q4", "q5"), userTexts(new ChatRequest(conversation().recent(0), null, null, null)));
    Message system = llm.last().messages().get(0);
    assertEquals(Message.system("## " + ChatPlugin.SUMMARY_HEADING + "\nS1"), system);
    assertEquals(List.of("q4", "q5"), userTexts(llm.last()));

    ask(question(6));
    assertEquals(1, llm.summaries.size(), "room was left: the next ask needs no summary");
    assertEquals(List.of("q4", "q5", "q6"), userTexts(llm.last()));
  }

  @Test
  void theNextSummaryBuildsOnThePreviousOne() {
    chat(cfg(400, true));
    askQuestions(1, 9);
    assertEquals(2, llm.summaries.size());
    String transcript = llm.summaries.get(1).messages().get(1).content();
    assertTrue(transcript.startsWith("Summary so far:\nS1"), transcript);
    assertEquals("S2", conversation().summary());
  }

  @Test
  void aFailedSummaryOnlyCuts() {
    llm.onSummary = (r, s) -> {
      throw new LlmException(500, "boom");
    };
    chat(cfg(400, true));
    askQuestions(1, 4);
    assertEquals("ok", ask(question(5)).text());
    assertEquals(1, llm.summaries.size());
    assertNull(conversation().summary());
    assertEquals(10, conversation().size());
    assertEquals(List.of("q2", "q3", "q4", "q5"), userTexts(llm.last()));
  }

  @Test
  void cancellingDuringTheSummaryChangesNothing() throws Exception {
    CountDownLatch summarising = new CountDownLatch(1);
    llm.onSummary = (r, s) -> {
      summarising.countDown();
      while (!s.isCancelled()) Thread.onSpinWait();
      s.throwIfCancelled();
      throw new AssertionError("unreachable");
    };
    chat(cfg(400, true));
    askQuestions(1, 4);

    CancelToken token = new CancelToken();
    Task<Optional<Chat.Reply>> pending = root.spawn("asker",
        signal -> Chat.ask(root, new Chat.Ask("c", question(5), token, d -> { })));
    summarising.await();
    token.cancel();
    assertThrows(CancelledException.class, pending::await);
    assertEquals(8, conversation().size());
    assertNull(conversation().summary());
  }

  @Test
  void contributedSectionsAreOrderedAndGoWithTheirPlugin() {
    chat(new ChatConfig("base", 0));
    root.plugin("env", ctx -> {
      Chat.contribute(ctx, "Environment", 1, q -> "cwd /w");
      Chat.contribute(ctx, "Alpha", 1, q -> "a");
      return null;
    });
    PluginHandle memory = root.plugin("memory", ctx -> {
      Chat.contribute(ctx, "Memory", 0, q -> "remember " + q.conversationId());
      Chat.contribute(ctx, "Broken", 0, q -> {
        throw new IllegalStateException("broken");
      });
      Chat.contribute(ctx, "Empty", 0, q -> null);
      return null;
    });

    ask("hi");
    assertEquals(Message.system("base\n\n## Memory\nremember c\n\n## Alpha\na\n\n## Environment\ncwd /w"),
        llm.last().messages().get(0));

    memory.dispose();
    ask("again");
    assertEquals(Message.system("base\n\n## Alpha\na\n\n## Environment\ncwd /w"), llm.last().messages().get(0));
  }

  @Test
  void aNewBudgetAppliesToTheNextAsk() {
    ConfigHandle<ChatConfig> chat = chat(cfg(0, false));
    askQuestions(1, 4);
    assertEquals(4, userTexts(llm.last()).size());
    chat.update(cfg(200, false));
    ask(question(5));
    assertEquals(List.of("q4", "q5"), userTexts(llm.last()));
  }
}
