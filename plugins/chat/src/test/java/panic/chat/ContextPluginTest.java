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

/** The context window of the chat plugin: token budget, compaction and contributed sections. */
class ContextPluginTest {

  /**
   * Answers "ok" with no usage (so estimates stay exact) and recognises summary requests by their prompt.
   * Records both kinds apart.
   */
  static final class ContextLlm implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final List<ChatRequest> summaries = new CopyOnWriteArrayList<>();
    /** Tool calls (to tool {@code big}) made before answering, one per step. */
    volatile int toolTurns;
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
      long results = r.messages().stream().filter(m -> m.role() == Role.TOOL).count();
      if (results < toolTurns && r.messages().get(r.messages().size() - 1).role() != Role.ASSISTANT) {
        // read a file per step, then answer: the turn grows with each result
        return new Completion("", "m", "tool_calls", Usage.NONE,
            List.of(new ToolCall("c" + results, "big", "{\"n\":" + results + "}")));
      }
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
    root.plugin("big", ctx -> {
      panic.tools.Tools.register(ctx, new panic.tools.Tool() {
        @Override
        public panic.llm.ToolSpec spec() {
          return new panic.llm.ToolSpec("big", "A big result.", null);
        }

        @Override
        public panic.tools.Risk risk() {
          return panic.tools.Risk.READ;
        }

        @Override
        public panic.tools.ToolResult call(com.fasterxml.jackson.databind.JsonNode args, panic.tools.ToolContext tc) {
          return panic.tools.ToolResult.ok("z".repeat(1000));
        }
      });
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
    assertEquals(ChatRequest.NO_REASONING_EFFORT, llm.summaries.get(0).reasoningEffort(),
        "a summary never thinks: thinking would spend its small limit");
    String transcript = llm.summaries.get(0).messages().get(1).content();
    assertTrue(transcript.contains("q1 ") && transcript.contains("q3 "), transcript);
    assertFalse(transcript.contains("q4 "), "compacted down to half the budget: the newest turn stays");
    assertEquals(new Usage(7, 3), reply.usage(), "the summary is paid by the turn that needed it");

    String summary = conversation().summary();
    assertTrue(summary.startsWith("S1\n\n" + ChatPlugin.VERBATIM_HEADING + ":\n- " + question(1) + "\n- " + question(2)),
        "the user's messages the summary left out are added word for word: " + summary);
    assertTrue(summary.endsWith("- " + question(3)), summary);
    assertEquals(List.of("q4", "q5"), userTexts(new ChatRequest(conversation().recent(0), null, null, null)));
    Message system = llm.last().messages().get(0);
    assertEquals(Message.system("## " + ChatPlugin.SUMMARY_HEADING + "\n" + summary), system);
  }

  @Test
  void aSummaryThatQuotesTheUserIsKeptAsIs() {
    llm.onSummary = (r, s) -> {
      String transcript = r.messages().get(1).content();
      StringBuilder out = new StringBuilder("S");
      transcript.lines().filter(l -> l.startsWith("user: ")).forEach(l -> out.append('\n').append(l.substring(6)));
      return new Completion(out.toString(), "m", "stop", Usage.NONE);
    };
    chat(cfg(400, true));
    askQuestions(1, 5);
    assertFalse(conversation().summary().contains(ChatPlugin.VERBATIM_HEADING), conversation().summary());
  }

  @Test
  void compactionNeverLeavesTheHistoryEmpty() {
    chat(cfg(200, true));
    askQuestions(1, 3); // at q3, half of what is left (60) holds no turn of 85
    assertEquals(1, llm.summaries.size());
    List<Message> kept = conversation().recent(0);
    assertEquals(Message.user(question(2)), kept.get(0), "the last turn's question stays");
    assertEquals(Message.assistant("ok"), kept.get(1), "and its answer");
    assertTrue(conversation().summary().contains(question(1)) && conversation().summary().contains(question(2)));
  }

  @Test
  void theLastTurnIsCondensedToItsQuestionAndAnswer() {
    ToolCall call = new ToolCall("t1", "read_file", "{}");
    List<Message> turns = List.of(Message.user("q1"), Message.assistant("a1"), Message.user("q2"),
        Message.assistant("", List.of(call)), Message.tool("t1", "lots of text"), Message.assistant("done"));
    assertEquals(List.of(Message.user("q2"), Message.assistant("done")), ChatPlugin.condensed(turns));
    assertEquals(List.of(), ChatPlugin.condensed(List.of(Message.user("q"), Message.assistant("", List.of(call)),
        Message.tool("t1", "x"))), "no answer in words: nothing to keep");
  }

  @Test
  void reviewFeedbackIsNotAUserMessage() {
    String s = ChatPlugin.verbatim("S", List.of(Message.user("fix the footer"),
        Message.user(Chat.REVIEW_FEEDBACK_PREFIX + "no tests")));
    assertEquals("S\n\n" + ChatPlugin.VERBATIM_HEADING + ":\n- fix the footer", s);
    assertEquals("S fix the footer", ChatPlugin.verbatim("S fix the footer", List.of(Message.user("fix the footer"))));
  }

  @Test
  void oldToolResultsAreClearedNewestKept() {
    String big = "y".repeat(500);
    List<Message> messages = new java.util.ArrayList<>(List.of(Message.user("q"),
        Message.tool("1", big), Message.tool("2", "short"), Message.tool("3", big), Message.tool("4", big),
        Message.tool("5", big)));
    assertEquals(2, ChatPlugin.clearToolResults(messages, 2));
    assertEquals(String.format(java.util.Locale.ROOT, ChatPlugin.CLEARED, 500), messages.get(1).content());
    assertEquals("1", messages.get(1).toolCallId(), "the placeholder still answers its call");
    assertEquals("short", messages.get(2).content(), "a short result costs less than its placeholder");
    assertEquals(String.format(java.util.Locale.ROOT, ChatPlugin.CLEARED, 500), messages.get(3).content());
    assertEquals(big, messages.get(4).content());
    assertEquals(big, messages.get(5).content());
  }

  @Test
  void requestsOverTheTriggerSendOldToolResultsAsPlaceholders() {
    llm.toolTurns = 4;
    chat(new ChatConfig(null, 0, true, 0, 0, 0, 0, 0, 100_000, false, null, 0, 300, 1));
    ask("read four files");
    ChatRequest last = llm.last();
    List<String> results = last.messages().stream().filter(m -> m.role() == Role.TOOL).map(Message::content).toList();
    assertEquals(4, results.size());
    assertTrue(results.subList(0, 3).stream().allMatch(c -> c.startsWith("[tool result cleared")), results.toString());
    assertEquals("z".repeat(1000), results.get(3));
    List<Message> stored = conversation().recent(0);
    assertTrue(stored.stream().filter(m -> m.role() == Role.TOOL).allMatch(m -> m.content().equals("z".repeat(1000))),
        "the conversation keeps every result");
  }

  @Test
  void theNextSummaryBuildsOnThePreviousOne() {
    chat(cfg(400, true));
    askQuestions(1, 9);
    assertTrue(llm.summaries.size() >= 2);
    String transcript = llm.summaries.get(1).messages().get(1).content();
    assertTrue(transcript.startsWith("Summary so far:\nS1"), transcript);
    assertTrue(conversation().summary().startsWith("S" + (llm.summaries.size())), conversation().summary());
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
