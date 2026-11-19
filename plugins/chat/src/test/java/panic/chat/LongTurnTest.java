package panic.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import context.Context;
import context.Root;
import core.Signal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import panic.llm.ChatRequest;
import panic.llm.Completion;
import panic.llm.Llm;
import panic.llm.Message;
import panic.llm.Provider;
import panic.llm.Role;
import panic.llm.ToolCall;
import panic.llm.ToolSpec;
import panic.llm.Usage;
import panic.tools.Resource;
import panic.tools.Risk;
import panic.tools.Tool;
import panic.tools.ToolContext;
import panic.tools.ToolResult;
import panic.tools.Tools;
import panic.util.CancelToken;

/**
 * Turns longer than the budget: the budget taken from the model's window, the older steps summarised while the turn
 * runs (every request stays under the budget, whatever the number of steps), and slices of a file already seen
 * counted as nothing new.
 */
class LongTurnTest {

  /** A model that calls {@code read_file} with the arguments {@code next} gives, until it gives null. */
  static final class Script implements Llm {
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
    final List<ChatRequest> summaries = new CopyOnWriteArrayList<>();
    final java.util.function.IntFunction<String> next;
    final int window;
    final String summary;

    Script(int window, String summary, java.util.function.IntFunction<String> next) {
      this.window = window;
      this.summary = summary;
      this.next = next;
    }

    @Override
    public Provider provider() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String model() {
      return "m";
    }

    @Override
    public int inputWindow() {
      return window;
    }

    @Override
    public Completion complete(ChatRequest r, Signal s) {
      summaries.add(r);
      return new Completion(summary, "m", "stop", Usage.NONE);
    }

    @Override
    public Completion stream(ChatRequest r, Signal s, Consumer<String> onDelta) {
      requests.add(r);
      String args = next.apply(requests.size());
      if (args == null) return new Completion("done", "m", "stop", Usage.NONE);
      return new Completion("", "m", "tool_calls", Usage.NONE,
          List.of(new ToolCall("c" + requests.size(), "read_file", args)));
    }
  }

  private final Root root = Context.root("t");
  private final Map<String, Conversation> all = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    root.plugin("conversations", ctx -> {
      ctx.provide(Conversations.KEY, id -> all.computeIfAbsent(id, Conversation::new));
      return null;
    });
    root.plugin("files", ctx -> {
      Tools.register(ctx, new Tool() {
        @Override
        public ToolSpec spec() {
          return new ToolSpec("read_file", "Read a file.", null);
        }

        @Override
        public Risk risk() {
          return Risk.READ;
        }

        @Override
        public ToolResult call(JsonNode args, ToolContext tc) {
          String path = args.path("path").asText();
          int from = args.path("from").asInt(1);
          int to = args.path("to").asInt(100);
          String text = (path + ":" + from + "-" + to + " ").repeat(400).substring(0, 2_000);
          return ToolResult.ok(text).about(Resource.view("file:" + path, "v1", from, to), "outline of " + path);
        }
      });
      return null;
    });
  }

  @AfterEach
  void dispose() {
    root.dispose();
  }

  private Chat.Reply run(Script model, Integer contextTokens) {
    root.plugin("llm", ctx -> {
      ctx.provide(Llm.KEY, model);
      return null;
    });
    root.plugin("chat", new ChatPlugin(), new ChatConfig(null, 40, true, 1_000, 0, 0, 0, 0, contextTokens, true));
    return Chat.ask(root, new Chat.Ask("c", "change every file", new CancelToken(), d -> { })).orElseThrow();
  }

  /** The chat's own estimate before it learns a ratio: four characters per token plus 4 per message. */
  private static long estimate(ChatRequest r) {
    long total = 0;
    for (Message m : r.messages()) {
      long chars = m.content().length();
      for (ToolCall c : m.toolCalls()) chars += c.name().length() + c.arguments().length();
      total += 4 + (chars + 3) / 4;
    }
    return total;
  }

  @Test
  void aLongTurnIsSummarisedAndEveryRequestStaysUnderTheBudget() {
    // 300 files of 2000 chars, one per step: even cleared, their steps (a call and an outline each) outgrow 8K
    Script model = new Script(0, "Goal: change every file. Done: read f1 to fN. Next: read the rest.",
        i -> i <= 300 ? "{\"path\":\"f" + i + "\"}" : null);
    Chat.Reply reply = run(model, 8_000);
    assertEquals(Chat.DONE, reply.stop());
    assertEquals(300, reply.toolCalls());
    assertFalse(model.summaries.isEmpty(), "the older steps were summarised");
    assertTrue(model.summaries.size() < 20, "rarely, not on every step: " + model.summaries.size());
    boolean progress = false;
    for (ChatRequest r : model.requests) {
      assertTrue(estimate(r) <= 8_000, "under the budget: " + estimate(r));
      for (int i = 1; i < r.messages().size(); i++) {
        assertFalse(r.messages().get(i).role() == Role.USER && r.messages().get(i - 1).role() == Role.USER,
            "never two user messages in a row");
      }
      Message first = r.messages().get(0);
      assertEquals(Role.USER, first.role());
      assertTrue(first.content().startsWith("change every file"), "the question stays word for word");
      if (first.content().contains(TurnCompaction.HEADING)) {
        progress = true;
        assertTrue(first.content().contains("Goal: change every file."), "the model's summary");
        assertTrue(first.content().contains("Files read: f1, f2"), "and the manifest");
      }
    }
    assertTrue(progress, "requests carried the progress");
    // the conversation keeps every step: only the requests were summarised
    assertEquals(300, all.get("c").recent(0).stream().filter(m -> m.role() == Role.TOOL).count());
  }

  @Test
  void whenTheSummaryFailsTheManifestStandsForTheSteps() {
    Script model = new Script(0, "", i -> i <= 300 ? "{\"path\":\"f" + i + "\"}" : null);
    Chat.Reply reply = run(model, 8_000);
    assertEquals(Chat.DONE, reply.stop());
    ChatRequest last = model.requests.get(model.requests.size() - 1);
    String first = last.messages().get(0).content();
    assertTrue(first.contains(TurnCompaction.HEADING), first);
    assertTrue(first.contains("Tool calls: read_file"), first);
    assertTrue(estimate(last) <= 8_000);
  }

  @Test
  void theBudgetFollowsTheModelsWindowWhenTheConfigGivesNone() {
    Script roomy = new Script(128_000, "s", i -> i <= 40 ? "{\"path\":\"f" + i + "\"}" : null);
    run(roomy, null);
    assertTrue(roomy.summaries.isEmpty(), "40 x 2000 chars fit a 128K window");
    assertTrue(roomy.requests.stream().allMatch(r -> r.messages().stream()
        .noneMatch(m -> m.role() == Role.TOOL && m.content().startsWith("["))), "nothing cleared");
  }

  @Test
  void anUnknownWindowFallsBackToTheDefaultBudget() {
    ChatConfig cfg = new ChatConfig(null, 40);
    assertEquals(ChatConfig.DEFAULT_CONTEXT_TOKENS, cfg.budget(0).contextTokens());
    assertEquals(114_688, cfg.budget(114_688).contextTokens());
    assertEquals(ChatConfig.AUTO_MAX_CONTEXT_TOKENS, cfg.budget(1_000_000).contextTokens());
    assertEquals(new ChatConfig.Budget(24_000, 18_000, 12_000), cfg.budget(24_000));
    ChatConfig fixed = new ChatConfig(null, 40, null, 0, null, 0, 0, 0, 10_000, null);
    assertEquals(10_000, fixed.budget(128_000).contextTokens(), "a number in the config wins");
    ChatConfig off = new ChatConfig(null, 40, null, 0, null, 0, 0, 0, 0, null);
    assertFalse(off.budget(128_000).limited());
  }

  @Test
  void slicesOfAFileAlreadySeenBringNothingNew() {
    // the whole file once, then ever other slices of it: before, each new range counted as news and never stalled
    Script model = new Script(0, "s", i -> i == 1 ? "{\"path\":\"a\",\"from\":1,\"to\":100}"
        : "{\"path\":\"a\",\"from\":" + (i % 90 + 1) + ",\"to\":" + (i % 90 + 10) + "}");
    Chat.Reply reply = run(model, 128_000);
    assertEquals(Chat.STALLED, reply.stop());
    assertTrue(reply.steps() <= 1 + Chat.STALL_STOP_CALLS + 1, "steps: " + reply.steps());
  }
}
